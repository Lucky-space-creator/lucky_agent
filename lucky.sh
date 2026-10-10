#!/usr/bin/env bash
# Lucky Agent 一键启动（Web 端 · macOS / Linux）
# 用法：./lucky.sh   首次运行自动构建后端 jar 与前端 dist，然后启动
#
# CLI 端请用 lucky-cli.sh（两者共用同一内核/配置/记忆/执行臂，决策 D11）。
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP="$DIR/web/app"
FRONTED="$DIR/web/fronted"
HOST="${LUCKY_HOST:-127.0.0.1}"
PORT="${LUCKY_PORT:-8080}"

# JDK 21+ 定位：不依赖 PATH 上的 java（若默认是更低版本，会在 Spring 启动前
# 直接抛 UnsupportedClassVersionError，用户很难归因）
# shellcheck source=/dev/null
source "$DIR/_jdk.sh"
if ! lucky_resolve_jdk; then
  echo "[错误] 未找到 JDK 21 或更高版本。" >&2
  echo "       本产品需要 JDK 21+（用 JDK 17 启动会立即报 UnsupportedClassVersionError）。" >&2
  echo "       请设置 JAVA_HOME 或 LUCKY_JDK_HOME 指向 JDK 21 后重试。" >&2
  exit 1
fi

# 1) 构建后端
JAR=""
if compgen -G "$APP/agent-web/target/agent-web-*.jar" >/dev/null 2>&1; then
  JAR="$(ls "$APP"/agent-web/target/agent-web-*.jar | head -1)"
else
  echo "== 构建后端（首次） =="
  (cd "$APP" && mvn -q -DskipTests package)
  JAR="$(ls "$APP"/agent-web/target/agent-web-*.jar | head -1)"
fi

# 2) 构建前端
if [ ! -f "$FRONTED/dist/index.html" ]; then
  echo "== 构建前端（首次） =="
  (cd "$FRONTED" && npm ci && npm run build)
fi

echo "== 启动 Lucky Agent：http://$HOST:$PORT =="
(cd "$APP" && exec "$JAVA_EXE" -Dserver.address="$HOST" -Dserver.port="$PORT" -jar "$JAR")