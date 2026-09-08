#!/usr/bin/env bash
# 真机测试结果检查：拉取触发日志判定 Toast/读屏锚点命中情况
# 用法: bash scripts/realphone_check.sh
# adb 路径自动探测：优先 ANDROID_HOME/SDK 路径，其次 PATH
ADB="${ADB:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/AppData/Local/Android/Sdk}}}/platform-tools/adb.exe"
[ -x "$ADB" ] || ADB=adb
export MSYS_NO_PATHCONV=1
PKG=com.example.charge_to_an_account
R=$("$ADB" devices | grep -v "emulator\|List" | grep "device$" | head -1 | cut -f1)
if [ -z "$R" ]; then echo "!! 未检测到真机"; exit 1; fi

"$ADB" -s $R exec-out "run-as $PKG cat databases/records.db" > "$TEMP/reallog.db" 2>/dev/null || { echo "!! 拉取失败（App 未运行过？）"; exit 1; }
py - <<'EOF'
import sqlite3, os
con = sqlite3.connect(os.path.join(os.environ['TEMP'], 'reallog.db'))
try:
    rows = list(con.execute("SELECT at, source, text, result, amount FROM trigger_log ORDER BY id"))
except Exception as e:
    print("!! 无日志表:", e); raise SystemExit
if not rows:
    print("日志为空：无障碍没收到任何事件（检查服务是否开启/微信是否前台）"); raise SystemExit
import datetime
for at, src, txt, res, amt in rows:
    t = datetime.datetime.fromtimestamp(at/1000).strftime('%H:%M:%S')
    print(f"{t} {res:16s} {amt if amt and amt>0 else '':>7} {src}: {(txt or '')[:60]}")
acc = [r for r in rows if r[3].startswith('acc')]
if not acc:
    print("\n结论：无 acc_* 记录 —— 微信事件未到达无障碍（服务绑定问题）")
elif any(r[3]=='acc_trigger' for r in acc):
    print("\n结论：acc_trigger 出现 —— Toast/读屏锚点命中，微信识别成功 ✓")
else:
    print("\n结论：有 acc 记录但未触发 —— 把上面 toast:/页面文本发给开发者精调关键词")
EOF
