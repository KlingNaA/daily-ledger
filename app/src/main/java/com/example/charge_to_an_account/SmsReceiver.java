package com.example.charge_to_an_account;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.util.Log;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 银行扣款短信兜底：美团/抖音等平台直连银行卡支付时，平台本身往往不发系统通知，
 * 唯一的本地信号是银行开卡的短信提醒（"尾号1234的卡消费88.00元"）。
 * 需要用户授予短信权限（可选，自用 sideload 应用不受商店限制）。
 */
public class SmsReceiver extends BroadcastReceiver {

    private static final String TAG = "PaySms";
    // 支出动作 + 金额（元）。常见："POS消费1000.00元" "支出500元" "扣款88.00元" "转出200元" "在美团交易200.00元"
    // group(1)=关键字与金额之间的文字（用来识别"…消费，余额5000.00元"这种余额句）
    // group(2)=金额；金额本身允许千分位逗号（"人民币1,234.56元"曾只取到 234.56，v1.6 修）
    private static final Pattern SPEND_PATTERN = Pattern.compile(
            "(?:消费|支出|扣款|扣付|支付|交易|转出)([^0-9元。]{0,24}?)"
                    + "(" + PaymentListenerService.AMOUNT_NUM + ")\\s*元");
    // 任意 "xx.xx元"
    private static final Pattern ANY_AMOUNT = Pattern.compile(
            "(" + PaymentListenerService.AMOUNT_NUM + ")\\s*元");
    // 余额/额度类提示词：紧跟其后的金额不是本次支出，绝不能记成消费
    private static final String[] BALANCE_HINTS = {"余额", "额度", "结余", "剩余", "可用"};
    private static final String[] INCOME_KEYWORDS =
            {"入账", "到账", "收款", "工资", "退款", "转入", "存入", "退票"};

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;

        StringBuilder body = new StringBuilder();
        for (android.telephony.SmsMessage msg : Telephony.Sms.Intents.getMessagesFromIntent(intent)) {
            if (msg != null && msg.getMessageBody() != null) body.append(msg.getMessageBody());
        }
        String text = body.toString().trim();
        if (text.isEmpty()) return;
        Log.d(TAG, "sms=" + text);

        if (!isDebitSms(text)) {
            Log.d(TAG, "not debit sms");
            logSafe(context, text, "sms_not_debit");
            return;
        }
        double amount = parseAmount(text);
        if (amount <= 0) {
            Log.d(TAG, "no amount parsed");
            logSafe(context, text, "sms_no_amount");
            return;
        }
        PaymentListenerService.trigger(context, amount, "银行短信", null);
    }

    /** 日志落地失败绝不能连带广播接收器崩掉（onReceive 在主线程，未捕获异常=进程崩溃） */
    private static void logSafe(Context context, String text, String result) {
        try {
            RecordDbHelper.get(context).logTrigger(
                    System.currentTimeMillis(), "银行短信", text, result, -1);
        } catch (Exception ignored) {
        }
    }

    static boolean isDebitSms(String text) {
        // 验证码短信绝不能弹；收入/退款短信不弹
        if (text.contains("验证码")) return false;
        for (String kw : INCOME_KEYWORDS) {
            if (text.contains(kw)) return false;
        }
        for (String kw : new String[]{"消费", "支出", "扣款", "扣付", "支付", "交易", "转出"}) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    static double parseAmount(String text) {
        // ① 关键字与金额相邻（gap 里不含数字，但金额本身可带千分位）
        Matcher m = SPEND_PATTERN.matcher(text);
        while (m.find()) {
            if (isBalanceHint(m.group(1))) continue; // "消费，余额5000.00元" → 这个数是余额
            double v = PaymentListenerService.parseNumber(m.group(2));
            if (v > 0) return v;
        }
        // ② 兜底：第一个 "xx.xx元"，同样跳过余额/额度后面的数字
        Matcher any = ANY_AMOUNT.matcher(text);
        while (any.find()) {
            String before = text.substring(Math.max(0, any.start() - 4), any.start());
            if (isBalanceHint(before)) continue;
            double v = PaymentListenerService.parseNumber(any.group(1));
            if (v > 0) return v;
        }
        return -1;
    }

    private static boolean isBalanceHint(String gap) {
        if (gap == null) return false;
        for (String hint : BALANCE_HINTS) {
            if (gap.contains(hint)) return true;
        }
        return false;
    }
}
