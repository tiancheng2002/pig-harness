package com.example.harness.web;

import com.example.harness.config.RootResolver;
import com.example.harness.core.memory.ConversationStore;
import com.example.harness.core.memory.ProjectStore;
import com.example.harness.sandbox.Sandbox;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 产出文件预览/下载：GET /api/file?path=...&conversationId=...
 * 根目录只来自服务端：普通会话=默认沙箱根；项目会话=项目绑定文件夹（客户端不可指定 root）。
 */
@RestController
public class FileController {

    private final RootResolver rootResolver;
    private final ConversationStore conversationStore;
    private final ProjectStore projectStore;

    public FileController(RootResolver rootResolver, ConversationStore conversationStore,
                          ProjectStore projectStore) {
        this.rootResolver = rootResolver;
        this.conversationStore = conversationStore;
        this.projectStore = projectStore;
    }

    @GetMapping("/api/file")
    public ResponseEntity<Resource> serve(@RequestParam String path,
                                          @RequestParam(required = false) String conversationId,
                                          @RequestParam(required = false) String projectId) {
        try {
            String root = rootResolver.defaultRoot().toString();
            if (conversationId != null && !conversationId.isBlank()) {
                root = conversationStore.get(conversationId)
                        .flatMap(c -> c.projectId() == null ? java.util.Optional.<String>empty()
                                : projectStore.get(c.projectId()).map(p -> p.rootPath()))
                        .orElse(root);
            } else if (projectId != null && !projectId.isBlank()) {
                root = projectStore.get(projectId).map(p -> p.rootPath()).orElse(root);
            }
            Sandbox sandbox = new Sandbox(root);
            Path target = sandbox.resolve(path);
            if (!Files.isRegularFile(target)) {
                return ResponseEntity.notFound().build();
            }
            String name = target.getFileName().toString();
            String lower = name.toLowerCase();
            boolean download = lower.endsWith(".docx");
            MediaType mime = switch (lower.substring(lower.lastIndexOf('.') + 1)) {
                case "html", "htm" -> MediaType.parseMediaType("text/html;charset=UTF-8");
                case "md" -> MediaType.parseMediaType("text/markdown;charset=UTF-8");
                case "css" -> MediaType.parseMediaType("text/css;charset=UTF-8");
                case "js" -> MediaType.parseMediaType("text/javascript;charset=UTF-8");
                case "json" -> MediaType.APPLICATION_JSON;
                case "png" -> MediaType.IMAGE_PNG;
                case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
                case "gif" -> MediaType.IMAGE_GIF;
                case "webp" -> MediaType.parseMediaType("image/webp");
                case "bmp" -> MediaType.IMAGE_PNG;
                case "svg" -> MediaType.parseMediaType("image/svg+xml");
                case "docx" -> MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
                default -> MediaType.APPLICATION_OCTET_STREAM;
            };
            String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
            String disposition = (download ? "attachment" : "inline")
                    + "; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded;
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                    .contentType(mime)
                    .body(new FileSystemResource(target));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).build();
        }
    }
}
