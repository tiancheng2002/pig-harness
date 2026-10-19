#!/usr/bin/env bash
# Pig Harness 一键启动（Linux / macOS）
set -u
cd "$(dirname "$0")"

# ===== 1. 检查 Java >= 21 =====
if ! command -v java >/dev/null 2>&1; then
  echo "[错误] 未找到 java。请安装 JDK 21+：https://adoptium.net/temurin/releases/?version=21"
  exit 1
fi
JAVA_VER=$(java -version 2>&1 | head -1)
JAVA_MAJOR=$(echo "$JAVA_VER" | sed -E 's/.*"([0-9]+)(\..*)?".*/\1/')
[ "$JAVA_MAJOR" = "1" ] && JAVA_MAJOR=8
if [ "$JAVA_MAJOR" -lt 21 ] 2>/dev/null; then
  echo "[错误] 本项目需要 Java 21+，当前为：$JAVA_VER"
  exit 1
fi

# ===== 2. jar 不存在则自动构建 =====
if [ ! -f harness/target/harness.jar ]; then
  echo "[提示] 未找到 harness.jar，开始构建（首次构建需联网，约 1~3 分钟）..."
  if ! command -v mvn >/dev/null 2>&1; then
    echo "[错误] 未找到 mvn。请安装 Maven 3.8+ 或手动执行：mvn -f harness/pom.xml -DskipTests package"
    exit 1
  fi
  mvn -q -f harness/pom.xml -DskipTests package || { echo "[错误] 构建失败"; exit 1; }
fi

# ===== 3. 启动服务并等待就绪后打开浏览器 =====
echo "[提示] 启动 Pig Harness：http://localhost:8080 （未配置 API Key 时自动进入 Mock 演示模式）"
java -jar harness/target/harness.jar &
PID=$!
trap 'kill $PID 2>/dev/null' EXIT

for i in $(seq 1 45); do
  if curl -sf -m 2 http://localhost:8080/api/config >/dev/null 2>&1; then
    echo "[提示] 服务就绪：http://localhost:8080 （Ctrl+C 停止服务）"
    if command -v xdg-open >/dev/null 2>&1; then xdg-open http://localhost:8080 >/dev/null 2>&1 &
    elif command -v open >/dev/null 2>&1; then open http://localhost:8080
    fi
    break
  fi
  sleep 2
done

wait $PID
