@echo off
setlocal
cd /d "%~dp0"
title Pig Harness 启动器

REM ===== 1. 检查 Java >= 21 =====
where java >nul 2>nul
if errorlevel 1 (
  echo [错误] 未找到 java。请安装 JDK 21 或更高版本：
  echo        https://adoptium.net/temurin/releases/?version=21
  echo        安装后请把 JAVA_HOME\bin 加入 PATH（或先设置 JAVA_HOME 再重开终端）。
  pause
  exit /b 1
)
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do set JAVA_VER=%%v
set JAVA_VER=%JAVA_VER:"=%
for /f "tokens=1 delims=." %%a in ("%JAVA_VER%") do set JAVA_MAJOR=%%a
REM 兼容老式 "1.8.0_xxx" 版本号
if "%JAVA_MAJOR%"=="1" set JAVA_MAJOR=8
if %JAVA_MAJOR% LSS 21 (
  echo [错误] 本项目需要 Java 21+，当前版本为 %JAVA_VER%。
  echo        下载 JDK 21：https://adoptium.net/temurin/releases/?version=21
  pause
  exit /b 1
)

REM ===== 2. jar 不存在则自动构建（需要 Maven 3.8+） =====
if not exist "harness\target\harness.jar" (
  echo [提示] 未找到 harness.jar，开始构建（首次构建需联网下载依赖，约 1~3 分钟）...
  where mvn >nul 2>nul
  if errorlevel 1 (
    echo [错误] 未找到 mvn。请安装 Maven 3.8+（https://maven.apache.org/download.cgi），
    echo        或手动执行：mvn -f harness/pom.xml -DskipTests package
    pause
    exit /b 1
  )
  call mvn -q -f harness/pom.xml -DskipTests package
  if errorlevel 1 (
    echo [错误] 构建失败，请查看上方日志。
    pause
    exit /b 1
  )
)

REM ===== 3. 启动服务（新窗口）并等待就绪后打开浏览器 =====
echo [提示] 启动 Pig Harness：http://localhost:8080 （未配置 API Key 时自动进入 Mock 演示模式）
start "pig-harness" java -jar harness\target\harness.jar
powershell -NoProfile -Command "$ok=$false; for($i=0;$i -lt 45;$i++){ try { Invoke-WebRequest -UseBasicParsing http://localhost:8080/api/config -TimeoutSec 2 | Out-Null; $ok=$true; break } catch { Start-Sleep -Seconds 2 } }; if($ok){ Start-Process 'http://localhost:8080' } else { Write-Host '服务未在 90 秒内就绪，请检查 pig-harness 窗口的日志' }"
echo [提示] 就绪后浏览器会自动打开 http://localhost:8080 ；关闭 pig-harness 窗口即停止服务。
pause
