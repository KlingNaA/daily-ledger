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
    private static final Pattern SPEND_PATTERN = Pattern.compile(
            "(?:消费|支出|扣款|扣付|支付|交易|转出)[^0-9元。]{0,24}?([0-9]+(?:\\.[0-9]{1,2})?)\\s*元");
    private static final String[] INCOME_KEYWORDS =
            {"入账", "到账", "收款", "工资", "退款", "转入", "存入", "转入", "退票"};

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
            RecordDbHelper.get(context).logTrigger(System.currentTimeMillis(),
                    "银行短信", text, "sms_not_debit", -1);
            return;
        }
        double amount = parseAmount(text);
        if (amount <= 0) {
            Log.d(TAG, "no amount parsed");
            RecordDbHelper.get(context).logTrigger(System.currentTimeMillis(),
                    "银行短信", text, "sms_no_amount", -1);
            return;
        }
        PaymentListenerService.trigger(context, amount, "银行短信", null);
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
        Matcher m = SPEND_PATTERN.matcher(text);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        // 兜底：找 "xx.xx元" 的第一个金额
        Matcher any = Pattern.compile("([0-9]+(?:\\.[0-9]{1,2})?)\\s*元").matcher(text);
        if (any.find()) {
            try {
                return Double.parseDouble(any.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }
}
