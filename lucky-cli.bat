@echo off
rem Lucky Agent CLI 一键启动（Windows）
rem
rem 与 lucky.bat 的分工：lucky.bat 起 Web（浏览器交互），本脚本起 CLI（终端交互）。
rem 两者共用同一内核、配置、记忆、执行臂与会话落盘 —— 只是交互外壳不同（决策 D11）。
rem
rem 用法示例：
rem   lucky-cli.bat                         进入交互式 REPL
rem   lucky-cli.bat -p "总结这个项目"         headless 单轮
rem   lucky-cli.bat -p "..." --output-format json
rem   lucky-cli.bat -c                       继续最近会话
rem   lucky-cli.bat --list-sessions          列出历史会话
rem   lucky-cli.bat --help                   查看全部参数
setlocal enabledelayedexpansion

set "DIR=%~dp0"
set "APP=%DIR%web\app"
set "JAR=%APP%\agent-cli\target\agent-cli-1.0.0.jar"

rem UTF-8：CLI 输出与日志都含中文，Windows 默认代码页 936(GBK) 会写出乱码
rem （UTF-8 字节被按 GBK 解释）。先记下原代码页，退出时恢复，避免污染
rem 用户在同一窗口里后续执行的其它命令。
for /f "tokens=2 delims=:" %%c in ('chcp 2^>nul') do set "ORIG_CP=%%c"
set "ORIG_CP=%ORIG_CP: =%"
chcp 65001 >nul 2>&1

rem JDK 21+ 定位：不依赖 PATH 上的 java（本机 PATH 常为 JDK 17，会直接崩溃）
call "%DIR%_jdk.bat"
if errorlevel 1 (
    endlocal
    exit /b 1
)

if not exist "%JAR%" (
    echo == 构建 CLI（首次）==
    pushd "%APP%"
    call mvn -q -DskipTests -pl agent-cli -am package
    if errorlevel 1 (
        popd
        echo [错误] 构建失败，请查看上方 Maven 输出。
        endlocal
        exit /b 1
    )
    popd
)

if not exist "%JAR%" (
    echo [错误] 未找到 %JAR%
    echo 请先执行：cd web\app ^&^& mvn -DskipTests package
    endlocal
    exit /b 1
)

rem -Dfile.encoding=UTF-8：CliApplication 内部也会强制，这里显式给一遍，保证 JVM 启动期
rem （参数解析、日志初始化）就已按 UTF-8 读入中文参数。
"%JAVA_EXE%" -Dfile.encoding=UTF-8 -jar "%JAR%" %*
set "RC=%ERRORLEVEL%"

if defined ORIG_CP chcp %ORIG_CP% >nul 2>&1
rem endlocal 之前先把 RC 展开：endlocal 之后 %RC% 已不可见
endlocal & exit /b %RC%