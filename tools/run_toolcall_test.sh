#!/bin/bash
# 工具调用层离线回归：在 PC 上直接编译三个纯逻辑类 + 测试，不碰 Android / Xposed。
#
# 为什么能离线跑：ToolCallCodec / ToolCallPrompt / ToolCallBridge 只依赖 org.json，
# 日志走可替换的 Logger。这样每次改逻辑都能秒级验证，不必先出一版 APK 装到设备上
# 才发现预算算错或名字校正失效。
set -e

REPO="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$REPO/app/src/main/java/com/wanxiang/xiaobubridge"
JSON_JAR="${JSON_JAR:-/workspace/libs/json-20231013.jar}"
OUT="${OUT:-/tmp/toolcall-test-classes}"

[ -f "$JSON_JAR" ] || { echo "缺少 org.json 实现：$JSON_JAR（可用 JSON_JAR=… 覆盖）" >&2; exit 1; }
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}
JAVAC="$JAVA_HOME/bin/javac"
JAVA="$JAVA_HOME/bin/java"
[ -x "$JAVAC" ] || { echo "缺少 JDK：$JAVAC" >&2; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT"

echo "########## 1/2 编译三个纯逻辑类 ##########"
"$JAVAC" -encoding UTF-8 -nowarn -cp "$JSON_JAR" -d "$OUT" \
    "$SRC/ToolCallCodec.java" "$SRC/ToolCallPrompt.java" "$SRC/ToolCallBridge.java" \
    "$REPO/tools/ToolCallLayerTest.java"

echo "########## 2/2 跑回归 ##########"
"$JAVA" -Dfile.encoding=UTF-8 -cp "$OUT:$JSON_JAR" ToolCallLayerTest
