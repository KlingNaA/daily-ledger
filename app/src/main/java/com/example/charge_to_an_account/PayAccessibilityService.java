package com.example.charge_to_an_account;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.HashSet;
import java.util.Set;

/**
 * 无障碍识别 v2（补"微信好友转账/红包不发系统通知"盲区，参考钱迹等同类产品路线）：
 * - Toast 锚点（TYPE_NOTIFICATION_STATE_CHANGED）：微信支付完成必弹 Toast（"已支付/支付成功"），
 *   事件文本直接判定，零读屏成本，同时覆盖转账与红包
 * - 读屏兜底（CONTENT/STATE_CHANGED）：限流 1.2s 读一次当前页文本，需含"成功"+支出词+金额
 * - 仅监听微信/支付宝包名（debug 放行全部，供模拟器验证）；全部判定落 acc_* 触发日志
 * 注意：canRetrieveWindowContent 由 res/xml/pay_accessibility.xml 声明（capability 不能代码覆盖）
 */
public class PayAccessibilityService extends AccessibilityService {

    private static final String TAG = "PayAcc";
    private static final Set<String> WATCHED = new HashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** 读屏限流：内容变化事件海量 */
    private long lastReadAt;
    private long lastPageAt;
    private long lastDebugLogAt;

    /** 支付流程状态机 v6：armed 状态跨类共享（通知监听通道也用它结算回执）。
     *  回执（[轉賬]/[微信红包]）通过三条通道到达：无障碍 Toast（微信后台时）、
     *  无障碍读屏、系统通知（通知监听，真机实测最稳定）——任一命中即结算 */
    private static volatile boolean armed;
    private static volatile double payAmount;   // armed 时从页面读到的金额（读不到=0，弹窗手填）
    private static volatile long armedAt;

    static {
        WATCHED.add("com.tencent.mm");
        WATCHED.add("com.eg.android.AlipayGphone");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        // 增量修改：取回系统已合并 XML 的配置（含 canRetrieveWindowContent capability），
        // 只改事件类型与监听包名再回写。切勿 new AccessibilityServiceInfo() 整体覆盖。
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    | AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED;
            info.packageNames = debuggable ? null : WATCHED.toArray(new String[0]);
            setServiceInfo(info);
        } catch (Exception e) {
            Log.w(TAG, "setServiceInfo failed", e);
        }
        Log.d(TAG, "connected v2, watched=" + WATCHED);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        String pkg = String.valueOf(event.getPackageName());
        if ("null".equals(pkg) || pkg.isEmpty()) return;
        boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (!debuggable && !WATCHED.contains(pkg)) return;

        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED: {
                // Toast / 应用内通知：微信支付完成的最可靠锚点。
                // 真 Toast 的文本在 event.getText()；系统通知走 parcelableData（Notification），
                // 其 getText() 是 ticker（现代通知常为空），需从 extras 提取 title/text
                String text = joinText(event.getText());
                if (text.isEmpty()) {
                    android.os.Parcelable p = event.getParcelableData();
                    if (p instanceof android.app.Notification) {
                        android.os.Bundle extras = ((android.app.Notification) p).extras;
                        if (extras != null) {
                            StringBuilder sb = new StringBuilder();
                            CharSequence t = extras.getCharSequence(android.app.Notification.EXTRA_TITLE);
                            CharSequence b = extras.getCharSequence(android.app.Notification.EXTRA_TEXT);
                            if (t != null) sb.append(t).append(' ');
                            if (b != null) sb.append(b);
                            text = sb.toString().trim();
                        }
                    }
                }
                if (text.isEmpty()) return;
                handleText(pkg, "toast:" + text, text, true);
                break;
            }
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED: {
                long now = System.currentTimeMillis();
                String title = joinText(event.getText());
                String cls = String.valueOf(event.getClassName());
                String desc = cls + (title.isEmpty() ? "" : " | " + title);
                String sig = (cls + " " + title).toLowerCase();

                // ── 结算（v9 纯事件序列）：armed 后出现支付完成页（UIPageFragment）= 支付成功。
                //  该序列真机多次实证 100% 到达；读屏在此机型为空树（微信内容隐藏），不再依赖文本。
                //  金额读不到时弹窗手填。取消支付不经过完成页，不会误触。 ──
                if (armed && System.currentTimeMillis() - armedAt < 90_000
                        && sig.contains("uipagefragment")) {
                    double amount = payAmount;
                    log("微信", desc, "acc_trigger", amount);
                    resetPayState();
                    PaymentListenerService.trigger(this, amount, "微信", "微信支付");
                    return;
                }

                // ── armed：进入支付场景（页面或密码框）──
                if (sig.contains("remittance") || sig.contains("luckymoney")
                        || sig.contains("redenvelop") || sig.contains("walletpay")
                        || sig.contains("payui") || sig.contains("dialog.k2")
                        || title.contains("Weixin Pay")) {
                    armed = true;
                    armedAt = now;
                    payAmount = 0; // 新支付流程重读金额
                    readAmountFromScreen();
                    log("微信", desc, "acc_armed", payAmount);
                }

                if (now - lastPageAt < 800) return; // 以下日志/通用读屏限流
                lastPageAt = now;

                // 日志降噪：支付相关窗口必记；普通窗口 debug 下 10s 节流（防环形日志被刷掉关键判定）
                boolean payish = sig.contains("pay") || sig.contains("支付") || sig.contains("红包")
                        || sig.contains("转账") || sig.contains("轉賬") || sig.contains("luckymoney")
                        || sig.contains("remittance") || sig.contains("redenvelop");
                long nowLog = System.currentTimeMillis();
                if (payish || (debuggable && nowLog - lastDebugLogAt > 10_000)) {
                    if (!payish) lastDebugLogAt = nowLog;
                    log(pkg, desc, debuggable && !payish ? "acc_debug_page" : "acc_page", -1);
                }
                readScreen(pkg, "win:" + desc);
                break;
            }
            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED: {
                readScreen(pkg, "content");
                break;
            }
            default:
        }
    }

    /** armed 时读当前页提取金额（转账页/塞钱页/密码框页均有 ¥xx.xx 文本）；
     *  800ms 等页面渲染；仅在仍为 0 时填充，避免后一次读到 0 覆盖已得金额 */
    private void readAmountFromScreen() {
        handler.postDelayed(() -> {
            if (payAmount > 0) return;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            CharSequence rp = root.getPackageName();
            if (rp == null || !String.valueOf(rp).contains("tencent.mm")) return;
            StringBuilder sb = new StringBuilder();
            collectText(root, sb, 0);
            double v = PaymentListenerService.parseAmount(sb.toString());
            if (v > 0) payAmount = v;
        }, 800);
    }

    /** 供通知监听通道查询：是否处于支付流程中（armed 且 90s 内） */
    public static boolean isArmedWithin(long windowMs) {
        return armed && System.currentTimeMillis() - armedAt < windowMs;
    }

    /** 供通知监听通道读取 armed 时读到的金额 */
    public static double armedAmount() {
        return payAmount;
    }

    /** 供通知监听通道在回执结算后重置状态（防多通道重复触发） */
    public static void resetArmed() {
        resetPayState();
    }

    private static void resetPayState() {
        armed = false;
        payAmount = 0;
        armedAt = 0;
    }

    /** 限流读当前页文本并判定（微信支付结果页停留短，限流收紧到 700ms/延迟 300ms 防错过） */
    private void readScreen(String pkg, String origin) {
        long now = System.currentTimeMillis();
        if (now - lastReadAt < 700) return;
        lastReadAt = now;
        handler.postDelayed(() -> {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            // 只读微信/支付宝自己的窗口（避免把 launcher/键盘读进来）
            CharSequence rp = root.getPackageName();
            if (rp == null) return;
            String rootPkg = rp.toString();
            boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            if (!debuggable && !WATCHED.contains(rootPkg)) return;
            if (debuggable && !WATCHED.contains(rootPkg) && !"com.android.chrome".equals(rootPkg)) {
                return; // debug 也只读被监听对象，便于模拟验证
            }
            StringBuilder sb = new StringBuilder();
            collectText(root, sb, 0);
            String text = sb.toString().trim();
            if (text.isEmpty()) return;
            handleText(pkg, origin, text, false);
        }, 300);
    }

    /** 统一文本判定：①微信回执结算（armed 后 90s 内收到 [轉賬]/[微信红包] 消息 = 本人支出，最可靠锚点）
     *  ②常规文本：支出词+金额+收入排除（isPaymentText）；读屏另需"成功"，Toast 不需要 */
    private void handleText(String pkg, String origin, String text, boolean isToast) {
        String source = pkg.contains("Alipay") || pkg.contains("alipay") ? "支付宝" : "微信";
        try {
            // ① 回执驱动结算：真机实测 [轉賬]/[微信红包] 消息以 toast/通知事件 100% 到达
            boolean transferReceipt = text.contains("[轉賬]") || text.contains("[转账]");
            boolean redPacketReceipt = text.contains("[微信紅包]") || text.contains("[微信红包]")
                    || text.contains("恭喜發財") || text.contains("恭喜发财");
            if ((transferReceipt || redPacketReceipt) && armed
                    && System.currentTimeMillis() - armedAt < 90_000) {
                String label = redPacketReceipt ? "微信红包" : "微信转账";
                log("微信", shorten(text), "acc_trigger", payAmount);
                resetPayState();
                PaymentListenerService.trigger(this, payAmount, "微信", label);
                return;
            }
            if (!isToast) {
                // 读屏（竞品同款无状态路线）：页面文本含支付完成特征即强信号，繁简双套
                boolean payDone = text.contains("成功")
                        || text.contains("已支付") || text.contains("已付款")
                        || text.contains("已轉賬") || text.contains("已转账");
                if (!payDone) return;
            }
            if (!PaymentListenerService.isPaymentText(text)) {
                if (isToast) log(source, shorten(text), "acc_not_payment", -1);
                return;
            }
            double amount = PaymentListenerService.parseAmount(text);
            log(source, shorten(text), "acc_trigger", amount);
            PaymentListenerService.trigger(this, amount, source, isToast ? null : source + "支付页");
        } catch (Exception e) {
            Log.w(TAG, "handleText failed", e);
        }
    }

    /** 读一次页面可见文本（深度/数量受限；1200 字上限——聊天页很长，回执消息在列表底部，
     *  400 字会把 [轉賬]/[微信红包] 截掉，真机踩坑），读完即弃 */
    private void collectText(AccessibilityNodeInfo node, StringBuilder sb, int depth) {
        if (node == null || depth > 14 || sb.length() > 1200) return;
        CharSequence t = node.getText();
        if (t != null && t.length() > 0 && t.length() < 60) {
            sb.append(t).append(' ');
        }
        for (int i = 0; i < node.getChildCount() && i < 50; i++) {
            collectText(node.getChild(i), sb, depth + 1);
        }
    }

    private static String joinText(Iterable<CharSequence> list) {
        if (list == null) return "";
        StringBuilder sb = new StringBuilder();
        for (CharSequence c : list) {
            if (c != null) sb.append(c).append(' ');
        }
        return sb.toString().trim();
    }

    private static String shorten(String s) {
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    private void log(String source, String text, String result, double amount) {
        try {
            RecordDbHelper.get(this).logTrigger(System.currentTimeMillis(), source, text, result, amount);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onInterrupt() {
    }
}
