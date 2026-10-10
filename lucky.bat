@echo off
rem Lucky Agent 一键启动（Windows，Web 端）
rem
rem 起 Web 服务并在浏览器访问 http://127.0.0.1:8080。
rem CLI 端请用 lucky-cli.bat（两者共用同一内核/配置/记忆/执行臂，决策 D11）。
setlocal enabledelayedexpansion

set "DIR=%~dp0"
set "APP=%DIR%web\app"
set "FRONTED=%DIR%web\fronted"

rem 控制台改 UTF-8：日志含中文，默认代码页 936(GBK) 下会写成乱码。
rem 记下原代码页，服务停止后恢复。
for /f "tokens=2 delims=:" %%c in ('chcp 2^>nul') do set "ORIG_CP=%%c"
set "ORIG_CP=%ORIG_CP: =%"
chcp 65001 >nul 2>&1

rem JDK 21+ 定位：不依赖 PATH 上的 java（本机 PATH 常为 JDK 17，会直接崩溃）
call "%DIR%_jdk.bat"
if errorlevel 1 (
    endlocal
    exit /b 1
)

rem 1) 构建后端
set "JAR="
for /f "delims=" %%i in ('dir /b /s "%APP%\agent-web\target\agent-web-*.jar" 2^>nul') do set "JAR=%%i"
if not defined JAR (
    echo == 构建后端（首次）==
    pushd "%APP%"
    call mvn -q -DskipTests package
    if errorlevel 1 (
        popd
        echo [错误] 后端构建失败，请查看上方 Maven 输出。
        endlocal
        exit /b 1
    )
    popd
    for /f "delims=" %%i in ('dir /b /s "%APP%\agent-web\target\agent-web-*.jar" 2^>nul') do set "JAR=%%i"
)
if not defined JAR (
    echo [错误] 未找到 agent-web 可执行 jar。
    endlocal
    exit /b 1
)

rem 2) 构建前端
if not exist "%FRONTED%\dist\index.html" (
    echo == 构建前端（首次）==
    pushd "%FRONTED%"
    call npm ci
    if errorlevel 1 (
        popd
        echo [错误] 前端依赖安装失败。
        endlocal
        exit /b 1
    )
    call npm run build
    if errorlevel 1 (
        popd
        echo [错误] 前端构建失败。
        endlocal
        exit /b 1
    )
    popd
)

echo == 启动 Lucky Agent：http://127.0.0.1:8080 ==
rem 从 agent-web 模块目录启动，使 frontend-dist 相对路径 ../../fronted/dist 正确解析到 web\fronted\dist
pushd "%APP%\agent-web"
"%JAVA_EXE%" -jar "%JAR%"
set "RC=%ERRORLEVEL%"
popd

if defined ORIG_CP chcp %ORIG_CP% >nul 2>&1
endlocal & exit /b %RC%