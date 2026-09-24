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

rem UTF-8：CLI 输出含中文，Windows 默认代码页 936(GBK) 会写出乱码。
rem 本脚本不改系统设置，只改当前控制台窗口的代码页。
chcp 65001 >nul 2>&1

set "DIR=%~dp0"
set "APP=%DIR%web\app"
set "JAR=%APP%\agent-cli\target\agent-cli-1.0.0.jar"

if not exist "%JAR%" (
  echo == 构建 CLI（首次）==
  pushd "%APP%"
  call mvn -q -DskipTests -pl agent-cli -am package
  popd
)

if not exist "%JAR%" (
  echo [错误] 未找到 %JAR%
  echo 请先执行：cd web\app ^&^& mvn -DskipTests package
  exit /b 1
)

rem -Dfile.encoding=UTF-8：CliApplication 内部也会强制，这里显式给一遍，保证 JVM 启动期
rem （参数解析、日志初始化）就已按 UTF-8 读入中文参数。
java -Dfile.encoding=UTF-8 -jar "%JAR%" %*
exit /b %ERRORLEVEL%
