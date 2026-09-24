#!/usr/bin/env bash
# Lucky Agent CLI 一键启动（macOS / Linux）
#
# 与 lucky 的分工：lucky 起 Web（浏览器交互），本脚本起 CLI（终端交互）。
# 两者共用同一内核、配置、记忆、执行臂与会话落盘 —— 只是交互外壳不同（决策 D11）。
#
# 用法示例：
#   ./lucky-cli                         进入交互式 REPL
#   ./lucky-cli -p "总结这个项目"         headless 单轮
#   ./lucky-cli -p "..." --output-format json
#   ./lucky-cli -c                       继续最近会话
#   ./lucky-cli --list-sessions          列出历史会话
#   ./lucky-cli --help                   查看全部参数
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP="$DIR/web/app"
JAR="$APP/agent-cli/target/agent-cli-1.0.0.jar"

if [ ! -f "$JAR" ]; then
  echo "== 构建 CLI（首次） =="
  (cd "$APP" && mvn -q -DskipTests -pl agent-cli -am package)
fi

if [ ! -f "$JAR" ]; then
  echo "[错误] 未找到 $JAR" >&2
  echo "请先执行：cd web/app && mvn -DskipTests package" >&2
  exit 1
fi

# -Dfile.encoding=UTF-8：CliApplication 内部也会强制，这里显式给一遍，保证 JVM 启动期
# （参数解析、日志初始化）就已按 UTF-8 读入中文参数。
exec java -Dfile.encoding=UTF-8 -jar "$JAR" "$@"
