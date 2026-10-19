package com.example.harness.skill;

import com.example.harness.config.HarnessProperties;
import com.example.harness.embedding.EmbeddingIndex;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 技能库：启动扫描 skills/ 目录，维护内存索引与数据库同步。
 */
@Component
public class SkillLibrary {

    private static final Logger log = LoggerFactory.getLogger(SkillLibrary.class);

    private final SkillStore store;
    private final EmbeddingIndex embeddingIndex;
    private final HarnessProperties props;
    private final Map<String, Skill> cache = new ConcurrentHashMap<>();

    public SkillLibrary(SkillStore store, EmbeddingIndex embeddingIndex, HarnessProperties props) {
        this.store = store;
        this.embeddingIndex = embeddingIndex;
        this.props = props;
    }

    @PostConstruct
    public void init() {
        Path dir = skillsDir();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建技能目录: " + dir, e);
        }

        // 0. 播种系统内置技能：classpath skills/builtin/*.md → skills/system/（缺则复制，不覆盖用户改动）
        seedBuiltinSkills(dir.resolve("system"));

        // 1. 扫描磁盘并同步到数据库：顶层 = 用户技能，system/ 子目录 = 系统技能
        try (Stream<Path> paths = Files.list(dir)) {
            paths.filter(p -> p.toString().endsWith(".md"))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .forEach(p -> loadFromDisk(p, "user"));
        } catch (IOException e) {
            log.error("扫描技能目录失败: {}", e.getMessage());
        }
        try (Stream<Path> paths = Files.list(dir.resolve("system"))) {
            paths.filter(p -> p.toString().endsWith(".md"))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .forEach(p -> loadFromDisk(p, "system"));
        } catch (IOException e) {
            log.warn("扫描系统技能目录失败: {}", e.getMessage());
        }

        // 2. 清理数据库中已不存在的文件记录
        for (Skill s : store.listAll()) {
            if (s.filePath() == null || !Files.exists(Paths.get(s.filePath()))) {
                log.info("技能文件已消失，清理数据库记录: {}", s.id());
                store.delete(s.id());
                embeddingIndex.remove(s.id());
            }
        }

        // 3. 加载完整 Skill（含 steps）到内存
        refreshCache();

        // 4. 为启用但缺少向量的技能构建索引
        for (Skill skill : cache.values()) {
            if (!skill.enabled()) continue;
            if (store.get(skill.id()).isEmpty()) {
                embeddingIndex.index(skill);
            }
        }

        log.info("技能库加载完成: {} 个技能（启用 {}，系统 {}）", cache.size(),
                cache.values().stream().filter(Skill::enabled).count(),
                cache.values().stream().filter(s -> "system".equals(s.origin())).count());
    }

    /** 把 jar 内置的系统技能复制到 skills/system/（仅当目标不存在；用户可自行修改文件内容）。 */
    private void seedBuiltinSkills(Path systemDir) {
        try {
            Files.createDirectories(systemDir);
            org.springframework.core.io.Resource[] resources =
                    new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                            .getResources("classpath*:skills/builtin/*.md");
            for (org.springframework.core.io.Resource res : resources) {
                String fileName = res.getFilename();
                if (fileName == null || !fileName.endsWith(".md")) continue;
                Path target = systemDir.resolve(fileName);
                if (Files.exists(target)) continue;
                try (var in = res.getInputStream()) {
                    Files.copy(in, target);
                    log.info("播种系统技能: {}", fileName);
                }
            }
        } catch (IOException e) {
            log.warn("播种系统技能失败: {}", e.getMessage());
        }
    }

    public List<Skill> list() {
        return List.copyOf(cache.values());
    }

    public List<Skill> listEnabled() {
        return cache.values().stream().filter(Skill::enabled).toList();
    }

    public Optional<Skill> get(String id) {
        return Optional.ofNullable(cache.get(id));
    }

    public Optional<Skill> getByName(String name) {
        return cache.values().stream().filter(s -> s.name().equalsIgnoreCase(name)).findFirst();
    }

    /** 手动创建/更新技能：写入磁盘并同步数据库。 */
    public synchronized Skill create(String preferredName, String content) {
        SkillParser.Parsed parsed = SkillParser.parse(UUID.randomUUID().toString(), null, content);
        String name = parsed.name() != null && !parsed.name().isBlank() ? parsed.name()
                : (preferredName != null && !preferredName.isBlank() ? preferredName : "skill_" + System.currentTimeMillis());
        String fileName = name.replaceAll("[^a-zA-Z0-9_-]+", "_").toLowerCase() + ".md";
        Path file = skillsDir().resolve(fileName);
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("写入技能文件失败: " + file, e);
        }
        return loadFromDisk(file, "user");
    }

    public synchronized boolean delete(String id) {
        Skill skill = cache.get(id);
        if (skill == null) return false;
        if (skill.filePath() != null) {
            try {
                Files.deleteIfExists(Paths.get(skill.filePath()));
            } catch (IOException e) {
                log.warn("删除技能文件失败: {}", e.getMessage());
            }
        }
        embeddingIndex.remove(id);
        store.delete(id);
        cache.remove(id);
        return true;
    }

    public synchronized boolean update(String id, String name, String description, Boolean enabled) {
        Skill skill = cache.get(id);
        if (skill == null) return false;
        String newName = name != null ? name : skill.name();
        String newDesc = description != null ? description : skill.description();
        boolean newEnabled = enabled != null ? enabled : skill.enabled();
        // P0：编辑（名称/描述实际变更）才递增版本；启停切换不加
        boolean contentChanged = (name != null && !name.equals(skill.name()))
                || (description != null && !description.equals(skill.description()));
        int newVersion = contentChanged ? skill.version() + 1 : skill.version();
        String now = LocalDateTime.now().toString();
        Skill updated = new Skill(skill.id(), newName, newDesc, skill.triggers(), skill.whenToUse(),
                skill.source(), skill.origin(), skill.mode(), skill.filePath(), newEnabled, newVersion,
                skill.createdAt(), now, skill.steps(), skill.params());
        store.save(updated);
        refreshFile(updated);
        cache.put(id, updated);
        if (newEnabled) {
            embeddingIndex.index(updated);
        } else {
            embeddingIndex.remove(id);
        }
        return true;
    }

    public synchronized void reindex(String id) {
        Skill skill = cache.get(id);
        if (skill == null || !skill.enabled()) return;
        embeddingIndex.index(skill);
    }

    public Path skillsDir() {
        // 显式配置优先（Docker/compose 指向 /data/skills）；否则沿用派生规则保持既有行为
        String explicit = props.getSkillsDir();
        if (explicit != null && !explicit.isBlank()) {
            return Paths.get(explicit).toAbsolutePath().normalize();
        }
        String root = props.getRoot();
        if (root == null || root.isBlank()) {
            return Paths.get(System.getProperty("user.dir"), "skills").toAbsolutePath().normalize();
        }
        return Paths.get(root, "skills").toAbsolutePath().normalize();
    }

    private Skill loadFromDisk(Path file, String origin) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            Optional<Skill> existing = store.getByFilePath(file.toString());
            String id = existing.map(Skill::id).orElseGet(() -> UUID.randomUUID().toString());
            String now = LocalDateTime.now().toString();
            String createdAt = existing.map(Skill::createdAt).orElse(now);
            // 已存在的记录保留其来源（避免重建时把用户技能误标为系统技能）
            String actualOrigin = existing.map(Skill::origin).orElse(origin);
            SkillParser.Parsed parsed = SkillParser.parse(id, file.toString(), content);
            boolean enabled = parsed.enabled();
            int version = parsed.version();
            // 如果是已存在记录，保留 enabled/version；否则使用文件内值
            if (existing.isPresent()) {
                enabled = existing.get().enabled();
                version = existing.get().version();
            }
            Skill skill = new Skill(id, parsed.name(), parsed.description(), parsed.triggers(),
                    parsed.whenToUse(), content, actualOrigin, parsed.mode(), file.toString(), enabled, version,
                    createdAt, now, parsed.steps(), parsed.params());
            store.save(skill);
            cache.put(id, skill);
            if (enabled) {
                embeddingIndex.index(skill);
            }
            log.info("加载技能: {} (origin={}, mode={}, enabled={}, steps={})", skill.name(),
                    skill.origin(), skill.mode(), skill.enabled(), skill.steps().size());
            return skill;
        } catch (IOException e) {
            log.error("读取技能文件失败 {}: {}", file, e.getMessage());
            return null;
        }
    }

    private void refreshFile(Skill skill) {
        if (skill.filePath() == null) return;
        StringBuilder front = new StringBuilder();
        front.append("---\n");
        front.append("name: ").append(skill.name()).append("\n");
        if (skill.description() != null) front.append("description: ").append(skill.description()).append("\n");
        if (!skill.triggers().isEmpty()) {
            front.append("triggers:\n");
            for (String t : skill.triggers()) front.append("  - \"").append(t).append("\"\n");
        }
        if (skill.whenToUse() != null) front.append("when_to_use: ").append(skill.whenToUse()).append("\n");
        front.append("enabled: ").append(skill.enabled()).append("\n");
        front.append("version: ").append(skill.version()).append("\n");
        front.append("---\n");
        // 从 source 中剔除所有前导 frontmatter，防止重复
        String source = skill.source() == null ? "" : skill.source();
        String body = source.replaceAll("(?s)^---\\s*\\n.*?\\n---\\s*\\n", "");
        String newContent = front + body;
        try {
            Files.writeString(Paths.get(skill.filePath()), newContent, StandardCharsets.UTF_8);
            cache.put(skill.id(), new Skill(skill.id(), skill.name(), skill.description(),
                    skill.triggers(), skill.whenToUse(), newContent, skill.origin(), skill.mode(), skill.filePath(),
                    skill.enabled(), skill.version(), skill.createdAt(), skill.updatedAt(),
                    skill.steps(), skill.params()));
        } catch (IOException e) {
            log.warn("更新技能文件失败: {}", e.getMessage());
        }
    }

    private void refreshCache() {
        cache.clear();
        for (Skill s : store.listAll()) {
            if (s.filePath() != null && Files.exists(Paths.get(s.filePath()))) {
                try {
                    String content = Files.readString(Paths.get(s.filePath()), StandardCharsets.UTF_8);
                    SkillParser.Parsed parsed = SkillParser.parse(s.id(), s.filePath(), content);
                    cache.put(s.id(), new Skill(s.id(), parsed.name(), parsed.description(),
                            parsed.triggers(), parsed.whenToUse(), content, s.origin(), parsed.mode(), s.filePath(),
                            s.enabled(), s.version(), s.createdAt(), s.updatedAt(),
                            parsed.steps(), parsed.params()));
                } catch (IOException e) {
                    log.warn("缓存技能失败 {}: {}", s.id(), e.getMessage());
                }
            }
        }
    }

}
