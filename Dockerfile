# ===== 构建阶段：Maven + JDK 21（前端产物已内嵌在 harness/src/main/resources/static，无需 Node） =====
FROM maven:3.9-eclipse-temurin-21 AS build
COPY harness /src/harness
WORKDIR /src
RUN mvn -q -f harness/pom.xml -DskipTests package

# ===== 运行阶段：仅 JRE，数据统一落 /data（volume 持久化） =====
FROM eclipse-temurin:21-jre
COPY --from=build /src/harness/target/harness.jar /app/harness.jar

# SQLite 库 / 默认沙箱工作区 / 技能库 全部指向 /data
ENV HARNESS_DB=/data/harness.db \
    HARNESS_ROOT=/data/workspace \
    HARNESS_SKILLS_DIR=/data/skills \
    HARNESS_LLM_API_KEY= \
    HARNESS_LLM_BASE_URL=https://api.deepseek.com/v1 \
    HARNESS_LLM_MODEL=deepseek-chat

VOLUME /data
EXPOSE 8080
WORKDIR /data
ENTRYPOINT ["java", "-jar", "/app/harness.jar"]
