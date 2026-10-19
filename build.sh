#!/usr/bin/env bash
# pig-harness 一键构建 + 运行脚本
# 用法:
#   bash build.sh          # 默认：构建前端 + 打包 jar + 启动服务
#   bash build.sh build    # 只构建前后端（不打前端目录、不打 jar、不启动）
#   bash build.sh run      # 只启动服务（假定 harness/target/harness.jar 已存在）
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

# managed node 22 + IntelliJ JBR 25 + 内置 Maven
# 注意：JAVA_HOME 必须 export，否则 mvn.cmd 会回退到系统 Java 8（不支持 text block 等语法）
export NODE_HOME="C:/Users/tianchengzhu/.workbuddy/binaries/node/versions/22.22.2"
export JAVA_HOME="D:/software/IntelliJ IDEA 2026.1/jbr"
MVN="D:/software/IntelliJ IDEA 2026.1/plugins/maven/lib/maven3/bin/mvn.cmd"
export PATH="$NODE_HOME:$JAVA_HOME/bin:$PATH"

MODE="${1:-all}"

build_frontend() {
  echo "==> [1/2 前端] 安装依赖（node_modules 已存在则跳过）"
  if [ ! -d "frontend/node_modules" ]; then
    (cd frontend && npm install)
  else
    echo "    node_modules 已存在，跳过 npm install"
  fi
  echo "==> [1/2 前端] vite build -> harness/src/main/resources/static"
  (cd frontend && npm run build)
}

build_backend() {
  echo "==> [2/2 后端] mvn package -> harness/target/harness.jar"
  "$MVN" -f harness/pom.xml package -DskipTests
}

run_server() {
  echo "==> [运行] 启动服务 http://localhost:8080"
  unset SERVER__PORT   # 去掉沙箱注入的随机端口变量
  # 显式使用 JBR 的 java，避免 PATH 上的系统 Java 8 误用
  "$JAVA_HOME/bin/java" -jar harness/target/harness.jar
}

case "$MODE" in
  build)
    build_frontend
    build_backend
    echo "构建完成：harness/target/harness.jar 已生成"
    ;;
  run)
    run_server
    ;;
  all|*)
    build_frontend
    build_backend
    run_server
    ;;
esac
