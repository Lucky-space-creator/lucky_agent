#!/usr/bin/env bash
# ============================================================================
#  Lucky Agent 共用 JDK 21+ 定位脚本（macOS / Linux）
#
#  用法：在调用脚本里
#      source "$DIR/_jdk.sh"
#      lucky_resolve_jdk || { echo "..." >&2; exit 1; }
#      exec "$JAVA_EXE" -jar ...
#
#  成功：导出 JAVA_HOME 与 JAVA_EXE，返回 0
#  失败：返回 1（由调用方打印指引，保持「谁面向用户谁说话」）
#
#  为什么必须校验版本：本产品编译目标为 Java 21（class file version 65.0）。
#  若用 JDK 17 启动，JVM 会在 Spring 启动之前抛 UnsupportedClassVersionError，
#  用户几乎无法自行归因到「JDK 版本不对」。Windows 侧同类问题见 _jdk.bat。
#
#  解析顺序：$LUCKY_JDK_HOME > $JAVA_HOME > 常见安装位置 > PATH
# ============================================================================

# 判断某个 JDK 根目录是否满足主版本 >= 21
lucky_jdk_version_ok() {
    local home="$1"
    [ -n "$home" ] && [ -x "$home/bin/java" ] || return 1
    local major
    # java -version 输出到 stderr；形如 openjdk version "21.0.11" / java version "1.8.0_xxx"
    major="$("$home/bin/java" -version 2>&1 | head -1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p')"
    [ -n "$major" ] && [ "$major" -ge 21 ]
}

# 解析并导出 JAVA_HOME / JAVA_EXE
lucky_resolve_jdk() {
    local candidate
    for candidate in \
        "$LUCKY_JDK_HOME" \
        "$JAVA_HOME" \
        /usr/lib/jvm/java-21-openjdk-amd64 \
        /usr/lib/jvm/java-21-openjdk \
        /usr/lib/jvm/temurin-21-jdk-amd64 \
        /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
        /usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
        /Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
        /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
    do
        if lucky_jdk_version_ok "$candidate"; then
            export JAVA_HOME="$candidate"
            export JAVA_EXE="$candidate/bin/java"
            return 0
        fi
    done

    # 最后兜底：PATH 上的 java（仅当版本合格才采纳）
    local bin
    bin="$(command -v java 2>/dev/null || true)"
    if [ -n "$bin" ]; then
        # 解析软链，避免 /usr/bin/java -> /etc/alternatives/java 这类间接层取错根目录
        local resolved
        resolved="$(readlink -f "$bin" 2>/dev/null || echo "$bin")"
        candidate="$(dirname "$(dirname "$resolved")")"
        if lucky_jdk_version_ok "$candidate"; then
            export JAVA_HOME="$candidate"
            export JAVA_EXE="$bin"
            return 0
        fi
    fi
    return 1
}