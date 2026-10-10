#!/usr/bin/env bash
# Lucky Agent CLI 一键启动（macOS / Linux）
#
# 与 lucky 的分工：lucky 起 Web（浏览器交互），本脚本起 CLI（终端交互）。
# 两者共用同一内核、配置、记忆、执行臂与会话落盘 —— 只是交互外壳不同（决策 D11）。
#
# 用法示例：
#   ./lucky-cli.sh                         进入交互式 REPL
#   ./lucky-cli.sh -p "总结这个项目"         headless 单轮
#   ./lucky-cli.sh -p "..." --output-format json
#   ./lucky-cli.sh -c                       继续最近会话
#   ./lucky-cli.sh --list-sessions          列出历史会话
#   ./lucky-cli.sh --help                   查看全部参数
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP="$DIR/web/app"
JAR="$APP/agent-cli/target/agent-cli-1.0.0.jar"

# JDK 21+ 定位：不依赖 PATH 上的 java（若默认是更低版本，会在 Spring 启动前
# 直接抛 UnsupportedClassVersionError，用户很难归因）
# shellcheck source=/dev/null
source "$DIR/_jdk.sh"
if ! lucky_resolve_jdk; then
  echo "[错误] 未找到 JDK 21 或更高版本。" >&2
  echo "       本产品需要 JDK 21+（用 JDK 17 启动会立即报 UnsupportedClassVersionError）。" >&2
  echo "       请任选其一后重试：" >&2
  echo "         1) export LUCKY_JDK_HOME=/path/to/jdk-21   （推荐，只影响当前会话）" >&2
  echo "         2) export JAVA_HOME=/path/to/jdk-21" >&2
  echo "         3) 把 JDK 21 的 bin 目录加入 PATH" >&2
  exit 1
fi

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
exec "$JAVA_EXE" -Dfile.encoding=UTF-8 -jar "$JAR" "$@"