#!/usr/bin/env bash
set -euo pipefail

# 安装测试包和清理数据只允许作用于 CI 模拟器。
test "${CI:-}" = "true"
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = "1"

mkdir -p app/build/reports/smoke
adb logcat -c
./gradlew --no-daemon --console=plain --max-workers=2 \
  '-Dorg.gradle.jvmargs=-Xmx6g -Xss8m -Dfile.encoding=UTF-8' \
  -Pkotlin.incremental=false -Pnekobot.testAbi=x86_64 connectedDebugAndroidTest
adb logcat -d -s NekoPerformance:I > app/build/reports/smoke/session-query.txt

# CI 使用临时测试证书验证混淆后的安装与启动，不使用发布私钥。
mkdir -p .signing
keytool -genkeypair -noprompt -keystore .signing/legacy-debug.keystore \
  -storepass android -keypass android -alias androiddebugkey \
  -dname 'CN=Android CI' -keyalg RSA -keysize 2048 -validity 2
./gradlew --no-daemon --console=plain --max-workers=2 \
  '-Dorg.gradle.jvmargs=-Xmx6g -Xss8m -Dfile.encoding=UTF-8' \
  -Pkotlin.incremental=false -Pnekobot.testAbi=x86_64 assembleRelease
adb uninstall com.nekobot.app
adb install app/build/outputs/apk/release/app-release.apk
adb logcat -c
for attempt in 1 2 3 4 5; do
  adb shell am force-stop com.nekobot.app
  adb shell am start -W -n com.nekobot.app/.MainActivity \
    | tee "app/build/reports/smoke/startup-${attempt}.txt"
  grep -q 'Status: ok' "app/build/reports/smoke/startup-${attempt}.txt"
  sleep 2
  adb shell pidof com.nekobot.app
done
adb logcat -d -s AndroidRuntime:E > app/build/reports/smoke/runtime.txt
if grep -q 'FATAL EXCEPTION' app/build/reports/smoke/runtime.txt; then
  cat app/build/reports/smoke/runtime.txt
  exit 1
fi
