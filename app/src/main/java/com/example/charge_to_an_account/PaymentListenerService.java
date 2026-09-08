package com.example.charge_to_an_account;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 监听微信/支付宝/美团/抖音等平台的支付通知，触发快速记账弹窗。
 * 弹出策略：屏幕亮起且解锁且拥有悬浮窗权限时直接启动记账小窗；
 * 否则发一条带 full-screen intent 的高优先级通知兜底。
 * SmsReceiver 的银行扣款短信解析也复用 {@link #trigger}。
 */
public class PaymentListenerService extends NotificationListenerService {

    private static final String TAG = "PayListener";

    private static final Set<String> PAY_PACKAGES = new HashSet<>();
    // 两种金额写法：¥25.80 / 25.80元（支付宝常用"元"后缀）
    private static final Pattern AMOUNT_PATTERN =
            Pattern.compile("(?:[¥￥]|人民币)\\s*([0-9]+(?:\\.[0-9]{1,2})?)|([0-9]+(?:\\.[0-9]{1,2})?)\\s*元");
    // 支出侧关键词：覆盖支付、付款、转账给他人、红包发放、平台/银行卡扣款（繁简双套，微信繁体用户实测必需）
    // "支出"：支付宝真实文案「交易提醒：你有一笔x.xx元的支出」
    private static final String[] PAY_KEYWORDS = {
            "支付成功", "付款成功", "已支付", "已付款", "消费", "付款", "支付",
            "已转账", "转账成功", "已转出", "转出", "红包", "送出", "支出",
            "已扣款", "扣款成功", "自动扣款", "交易金额",
            // 繁体（轉賬/紅包/消費/轉出）
            "已轉賬", "轉賬成功", "轉出", "紅包", "消費"
    };
    // 收入/退款类通知绝不弹窗（转账给好友=支出，收到转账/红包=收入，必须区分；繁简双套）
    private static final String[] INCOME_KEYWORDS = {
            "收到", "入账", "到账", "收款", "退款", "工资", "存入", "领取", "来账", "退回", "收入",
            "入賬", "到賬", "工資", "領取"
    };

    /** 通知去重：key -> 上次触发时间 */
    private final Map<String, Long> seen = new HashMap<>();
    private static final long DEDUP_WINDOW_MS = 10_000;

    /** 兜底提醒的固定通知 id，弹窗展示时据此撤销 */
    static final int BACKUP_NOTIF_ID = 2001;
    /** 弹窗是否已实际展示（QuickRecordActivity 标记），决定是否发兜底通知 */
    static volatile boolean popupShown;
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());

    /** 全局触发去重：同一笔扣款可能同时被短信和通知两个入口看到 */
    private static final Object TRIGGER_LOCK = new Object();
    private static double lastTriggerAmount = -1;
    private static long lastTriggerAt;
    private static final long SAME_AMOUNT_DEDUP_MS = 6_000;

    /** 支付来源包名 → 展示名（日志/弹窗共用） */
    static String sourceName(String pkg) {
        switch (pkg) {
            case "com.tencent.mm": return "微信";
            case "com.eg.android.AlipayGphone":
            case "com.alipay.android.client": return "支付宝";
            case "com.sankuai.meituan":
            case "com.sankuai.meituan.takeoutnew":
            case "com.dianping.v1": return "美团";
            case "com.ss.android.ugc.aweme":
            case "com.ss.android.ugc.aweme.lite": return "抖音";
            case "com.jingdong.app.mall": return "京东";
            case "com.xunmeng.pinduoduo": return "拼多多";
            case "com.unionpay": return "云闪付";
            default: return pkg; // 未知包名原样显示（便于日志排查）
        }
    }

    static {
        PAY_PACKAGES.add("com.tencent.mm");               // 微信
        PAY_PACKAGES.add("com.eg.android.AlipayGphone");  // 支付宝
        PAY_PACKAGES.add("com.alipay.android.client");    // 支付宝备用包名
        PAY_PACKAGES.add("com.sankuai.meituan");          // 美团（美团支付）
        PAY_PACKAGES.add("com.sankuai.meituan.takeoutnew"); // 美团外卖
        PAY_PACKAGES.add("com.dianping.v1");              // 大众点评
        PAY_PACKAGES.add("com.ss.android.ugc.aweme");     // 抖音（抖音支付）
        PAY_PACKAGES.add("com.ss.android.ugc.aweme.lite"); // 抖音极速版
        PAY_PACKAGES.add("com.jingdong.app.mall");        // 京东（京东支付）
        PAY_PACKAGES.add("com.xunmeng.pinduoduo");        // 拼多多
        PAY_PACKAGES.add("com.unionpay");                 // 云闪付
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        boolean debuggable = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        Log.d(TAG, "posted pkg=" + sbn.getPackageName() + " debuggable=" + debuggable);
        // 永远忽略自身通知（防止兜底提醒被再次触发形成循环）
        if (getPackageName().equals(sbn.getPackageName())) {
            log(sbn.getPackageName(), null, "self", -1);
            return;
        }
        // debug 构建放行所有包名，便于用 shell 通知模拟微信/支付宝做端到端测试；release 只认已收录的支付应用
        if (!debuggable && !PAY_PACKAGES.contains(sbn.getPackageName())) {
            log(sbn.getPackageName(), null, "not_whitelist", -1);
            return;
        }

        String text = extractText(sbn.getNotification());
        Log.d(TAG, "text=" + text);
        if (text == null) {
            log(sbn.getPackageName(), null, "no_text", -1);
            return;
        }

        // 微信转账/红包回执结算（第三通道，真机实测最稳）：无障碍 armed + 回执通知 = 本人支出。
        // 回执文本（[轉賬] 請收款 / [微信红包] 恭喜發財）会被常规规则当 not_payment 丢弃，须在此之前专项处理
        if (sbn.getPackageName().equals("com.tencent.mm")
                && PayAccessibilityService.isArmedWithin(90_000)) {
            boolean transferReceipt = text.contains("[轉賬]") || text.contains("[转账]");
            boolean redPacketReceipt = text.contains("[微信紅包]") || text.contains("[微信红包]")
                    || text.contains("恭喜發財") || text.contains("恭喜发财");
            if (transferReceipt || redPacketReceipt) {
                double amount = PayAccessibilityService.armedAmount();
                String label = redPacketReceipt ? "微信红包" : "微信转账";
                log(sbn.getPackageName(), text, "acc_trigger", amount);
                PayAccessibilityService.resetArmed(); // 防其他通道重复触发
                trigger(this, amount, "微信", label);
                return;
            }
        }

        if (!isPaymentText(text)) {
            Log.d(TAG, "not payment text");
            log(sbn.getPackageName(), text, "not_payment", -1);
            return;
        }

        String key = sbn.getKey() != null ? sbn.getKey() : sbn.getPackageName() + "#" + sbn.getId();
        long now = System.currentTimeMillis();
        synchronized (seen) {
            Long last = seen.get(key);
            if (last != null && now - last < DEDUP_WINDOW_MS) {
                log(sbn.getPackageName(), text, "dup_key", -1);
                return;
            }
            seen.values().removeIf(t -> now - t > DEDUP_WINDOW_MS * 12);
            seen.put(key, now);
        }

        trigger(this, parseAmount(text), sourceName(sbn.getPackageName()), merchantHint(sbn.getNotification()));
    }

    /** 通知标题作商家提示；通用词（"微信支付"等）不算商家 */
    private static String merchantHint(Notification n) {
        Bundle extras = n.extras;
        if (extras == null) return null;
        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        if (title == null) return null;
        String t = title.toString().trim();
        if (t.isEmpty() || t.length() > 16) return null;
        for (String generic : new String[]{"微信", "支付宝", "支付", "凭证", "收款", "银行", "通知", "信息"}) {
            if (t.contains(generic)) return null;
        }
        return t;
    }

    private void log(String source, String text, String result, double amount) {
        try {
            RecordDbHelper.get(this).logTrigger(System.currentTimeMillis(), source, text, result, amount);
        } catch (Exception ignored) {
        }
    }

    /**
     * 判定并弹出记账窗：解锁+悬浮窗走小窗，否则全屏通知兜底。通知与短信两个入口共用。
     * 金额先入待记账队列：连续付款各笔都会保留，用户腾出手后逐笔确认。
     */
    public static void trigger(Context context, double amount, String source, String noteHint) {
        synchronized (TRIGGER_LOCK) {
            long now = System.currentTimeMillis();
            // 同金额 6 秒内视为同一笔扣款（短信+通知双入口）；不同金额/超时则正常入队
            if (amount > 0 && amount == lastTriggerAmount && now - lastTriggerAt < SAME_AMOUNT_DEDUP_MS) {
                Log.d(TAG, "dedup trigger amount=" + amount);
                if (context instanceof PaymentListenerService) {
                    ((PaymentListenerService) context).log(source, null, "dup_amount", amount);
                }
                return;
            }
            lastTriggerAmount = amount;
            lastTriggerAt = now;
        }
        Context app = context.getApplicationContext();
        PendingPayments.add(app, amount > 0 ? amount : 0, System.currentTimeMillis(), source, noteHint);

        // 屏幕熄灭或锁屏时悬浮窗不可见，一律走全屏通知兜底
        KeyguardManager km = (KeyguardManager) app.getSystemService(Context.KEYGUARD_SERVICE);
        PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        boolean unlocked = pm.isInteractive() && !km.inKeyguardRestrictedInputMode()
                && !km.isKeyguardLocked();
        boolean canOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(app);

        popupShown = false;
        String route;
        if (unlocked && canOverlay) {
            Log.d(TAG, "overlay route, amount=" + amount);
            route = "queued_popup";
            Intent intent = new Intent(app, QuickRecordActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            if (amount > 0) intent.putExtra(QuickRecordActivity.EXTRA_AMOUNT, amount);
            app.startActivity(intent);
        } else {
            Log.d(TAG, "fullscreen route, unlocked=" + unlocked + " overlay=" + canOverlay);
            route = "queued_notify";
        }
        if (context instanceof PaymentListenerService) {
            ((PaymentListenerService) context).log(source, noteHint, route, amount);
        } else {
            logStatic(app, source, noteHint, route, amount);
        }
        // 小米 HyperOS 等 ROM 会静默拦截"后台弹出界面"，悬浮窗权限给了也没用。
        // 延迟 900ms 发高优兜底通知（固定 id 更新，不连环弹）：若弹窗已实际展示则跳过。
        final String initialRoute = route;
        MAIN.postDelayed(() -> {
            if (!popupShown) {
                Log.d(TAG, "popup not shown, posting backup notification");
                if ("queued_popup".equals(initialRoute)) {
                    // 初始路由是弹窗但 900ms 内没展示 = 被系统拦截，降级成通知；落日志便于区分
                    logStatic(app, source, noteHint, "popup_blocked", amount);
                }
                notifyFullscreen(app);
            }
        }, 900);
    }

    /** SmsReceiver 等非服务入口的日志落地 */
    private static void logStatic(Context app, String source, String text, String result, double amount) {
        try {
            RecordDbHelper.get(app).logTrigger(System.currentTimeMillis(), source, text, result, amount);
        } catch (Exception ignored) {
        }
    }

    private static String extractText(Notification n) {
        Bundle extras = n.extras;
        if (extras == null) return null;
        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
        StringBuilder sb = new StringBuilder();
        if (title != null) sb.append(title).append(' ');
        if (text != null) sb.append(text);
        String result = sb.toString().trim();
        return result.isEmpty() ? null : result;
    }

    static boolean isPaymentText(String text) {
        // 先排除收入/退款，再匹配支出关键词（"收到转账"绝不能弹）
        for (String kw : INCOME_KEYWORDS) {
            if (text.contains(kw)) return false;
        }
        // 必须含金额符号/金额数字，且命中支出关键词
        boolean hasAmountChar = text.contains("¥") || text.contains("￥")
                || text.contains("元") || AMOUNT_PATTERN.matcher(text).find();
        if (!hasAmountChar) return false;
        for (String kw : PAY_KEYWORDS) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    static double parseAmount(String text) {
        Matcher m = AMOUNT_PATTERN.matcher(text);
        if (m.find()) {
            String v = m.group(1) != null ? m.group(1) : m.group(2);
            try {
                return Double.parseDouble(v);
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    /** 兜底通知：固定 id 更新不堆叠；setOnlyAlertOnce 让连续付款只响一次提醒 */
    private static void notifyFullscreen(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        String channelId = "payment_reminder";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(channelId,
                    context.getString(R.string.record_notification_channel),
                    NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(channel);
        }

        List<PendingPayments.Entry> queue = PendingPayments.all(context);
        int count = 0;
        double sum = 0;
        for (PendingPayments.Entry e : queue) {
            if (e.amount > 0) {
                sum += e.amount;
                count++;
            }
        }

        String title;
        String body;
        if (count >= 2) {
            title = String.format(context.getString(R.string.notif_multi_title), count);
            body = String.format(context.getString(R.string.notif_multi_text), sum);
        } else {
            double single = queue.isEmpty() ? 0 : queue.get(queue.size() - 1).amount;
            title = context.getString(R.string.record_notification_title);
            body = single > 0
                    ? String.format(context.getString(R.string.record_notification_text_fmt), single)
                    : context.getString(R.string.record_notification_text);
        }

        Intent intent = new Intent(context, QuickRecordActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        int flag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent fullScreen = PendingIntent.getActivity(context, 0, intent, flag);

        Notification n = new NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setFullScreenIntent(fullScreen, true)
                .build();
        nm.notify(BACKUP_NOTIF_ID, n);
    }
}
