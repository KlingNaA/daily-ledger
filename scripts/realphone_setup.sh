#!/usr/bin/env bash
# 真机一键部署+验证脚本（小米15 插线后运行）
# 用法: bash scripts/realphone_setup.sh
set -e
# adb 路径自动探测：优先 ANDROID_HOME/SDK 路径，其次 PATH
ADB="${ADB:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/AppData/Local/Android/Sdk}}}/platform-tools/adb.exe"
[ -x "$ADB" ] || ADB=adb
export MSYS_NO_PATHCONV=1
PKG=com.example.charge_to_an_account
R=$("$ADB" devices | grep -v "emulator\|List" | grep "device$" | head -1 | cut -f1)
if [ -z "$R" ]; then echo "!! 未检测到真机，请插线并授权 USB 调试"; exit 1; fi
echo "== 真机: $R =="

echo "== 1/4 安装 APK =="
"$ADB" -s $R install -r app/build/outputs/apk/debug/app-debug.apk

echo "== 2/4 授权（adb 可授部分） =="
"$ADB" -s $R shell cmd notification allow_listener $PKG/.PaymentListenerService || true
"$ADB" -s $R shell appops set $PKG SYSTEM_ALERT_WINDOW allow || true
"$ADB" -s $R shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true
"$ADB" -s $R shell pm grant $PKG android.permission.RECEIVE_SMS 2>/dev/null || true

echo "== 3/4 无障碍状态 =="
ACC=$("$ADB" -s $R shell settings get secure enabled_accessibility_services | grep -c "PayAccessibilityService" || true)
if [ "$ACC" = "1" ]; then
  echo "   无障碍已开启 ✓"
else
  echo "   !! 无障碍未开启（重装会掉，MIUI 不允许 adb 代开）"
  echo "   请在手机上手动开: 我的→微信转账/红包识别（无障碍）→前往"
  "$ADB" -s $R shell am start -a android.settings.ACCESSIBILITY_SETTINGS
fi

echo "== 4/4 清空旧日志并启动 =="
"$ADB" -s $R shell "run-as $PKG sh -c 'rm -f databases/records.db databases/records.db-journal shared_prefs/pending_payments.xml'" 2>/dev/null || true
"$ADB" -s $R shell am start -n $PKG/.MainActivity

echo ""
echo "现在请在手机上: 微信→文件传输助手→发 0.01 元转账 + 一个 0.01 元红包"
echo "完成后回来运行: bash scripts/realphone_check.sh 查看触发日志"
