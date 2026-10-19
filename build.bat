@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

REM ===== 工具路径探测（优先内置 JBR / managed node，缺失则回退系统 PATH）=====
set "NODE_HOME=C:\Users\tianchengzhu\.workbuddy\binaries\node\versions\22.22.2"
if not exist "%NODE_HOME%\node.exe" set "NODE_HOME="

set "JAVA_HOME=D:\software\IntelliJ IDEA 2026.1\jbr"
if not exist "%JAVA_HOME%\bin\java.exe" set "JAVA_HOME="

set "MVN=D:\software\IntelliJ IDEA 2026.1\plugins\maven\lib\maven3\bin\mvn.cmd"
if not exist "%MVN%" set "MVN="

if defined NODE_HOME set "PATH=%NODE_HOME%;%PATH%"
if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"

set "MODE=%1"
if "%MODE%"=="" set "MODE=all"

if "%MODE%"=="build" goto :build
if "%MODE%"=="run" goto :run
goto :all

:build
echo [1/2] 前端：安装依赖（node_modules 已存在则跳过）
if not exist "frontend\node_modules" (
  pushd frontend
  call npm install
  popd
) else (
  echo     node_modules 已存在，跳过 npm install
)
echo [1/2] 前端：vite build -^> harness\src\main\resources\static
pushd frontend
call npm run build
popd
echo [2/2] 后端：mvn package -^> harness\target\harness.jar
if defined MVN (
  "%MVN%" -f harness/pom.xml package -DskipTests
) else (
  mvn -f harness/pom.xml package -DskipTests
)
echo 构建完成：harness\target\harness.jar 已生成
goto :eof

:run
echo [运行] 启动服务 http://localhost:8080
set "SERVER__PORT="
if defined JAVA_HOME (
  "%JAVA_HOME%\bin\java.exe" -jar harness\target\harness.jar
) else (
  java -jar harness\target\harness.jar
)
goto :eof

:all
echo [1/2] 前端：安装依赖（node_modules 已存在则跳过）
if not exist "frontend\node_modules" (
  pushd frontend
  call npm install
  popd
) else (
  echo     node_modules 已存在，跳过 npm install
)
echo [1/2] 前端：vite build -^> harness\src\main\resources\static
pushd frontend
call npm run build
popd
echo [2/2] 后端：mvn package -^> harness\target\harness.jar
if defined MVN (
  "%MVN%" -f harness/pom.xml package -DskipTests
) else (
  mvn -f harness/pom.xml package -DskipTests
)
echo [运行] 启动服务 http://localhost:8080
set "SERVER__PORT="
if defined JAVA_HOME (
  "%JAVA_HOME%\bin\java.exe" -jar harness\target\harness.jar
) else (
  java -jar harness\target\harness.jar
)
goto :eof
