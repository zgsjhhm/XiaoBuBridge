#!/bin/bash
# 文生图取图编解码层离线回归：在 PC 上直接编译纯逻辑类 + 测试，不碰 Android / Xposed。
#
# 为什么能离线跑：ImageResultCodec 只依赖 org.json；BeanExtractor 的反射部分
# 不参与本测试（它需要目标进程的 ClassLoader），这里只用真机抓到的 payload 文本
# 驱动解析逻辑。这样每次改抽取规则都能秒级验证，不必先出 APK 装到设备上
# 才发现结构变了抽不到图。
set -e

REPO="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$REPO/app/src/main/java/com/wanxiang/xiaobubridge"
JSON_JAR="${JSON_JAR:-/workspace/libs/json-20231013.jar}"
OUT="${OUT:-/tmp/image-codec-test-classes}"

[ -f "$JSON_JAR" ] || { echo "缺少 org.json 实现：$JSON_JAR（可用 JSON_JAR=… 覆盖）" >&2; exit 1; }
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}
JAVAC="$JAVA_HOME/bin/javac"
JAVA="$JAVA_HOME/bin/java"
[ -x "$JAVAC" ] || { echo "缺少 JDK：$JAVAC" >&2; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT"

echo "########## 1/2 编译 ImageResultCodec + 测试 ##########"
"$JAVAC" -encoding UTF-8 -nowarn -cp "$JSON_JAR" -d "$OUT" \
    "$SRC/ImageResultCodec.java" "$REPO/tools/ImageCodecTest.java"

echo "########## 2/2 跑回归 ##########"
"$JAVA" -Dfile.encoding=UTF-8 -cp "$OUT:$JSON_JAR" ImageCodecTest
