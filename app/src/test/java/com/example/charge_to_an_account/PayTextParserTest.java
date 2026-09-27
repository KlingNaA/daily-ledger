package com.example.charge_to_an_account;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 金额/文案解析回归测试（纯 JVM，无 Android 依赖）。
 * 覆盖 v1.6 修掉的两个"静默记错金额"事故：
 *  - 千分位逗号：￥1,234.56 曾记成 1.00，人民币1,234.56元 曾记成 234.56
 *  - 银行短信把"余额"当成消费金额：…100.00元的消费，余额5000.00元 曾记成 5000.00
 */
public class PayTextParserTest {

    // ───────── 通知通道 ─────────

    @Test
    public void notificationAmount_plain() {
        assertEquals(25.80, PaymentListenerService.parseAmount("微信支付 支付成功￥25.80"), 0.001);
        assertEquals(6.66, PaymentListenerService.parseAmount("交易提醒：你有一笔6.66元的支出"), 0.001);
        assertEquals(50.0, PaymentListenerService.parseAmount("已转账 50元"), 0.001);
    }

    @Test
    public void notificationAmount_thousandSeparator() {
        assertEquals(1234.56, PaymentListenerService.parseAmount("支付成功￥1,234.56"), 0.001);
        assertEquals(1234.56, PaymentListenerService.parseAmount("消费支出人民币1,234.56元"), 0.001);
        assertEquals(12345.67, PaymentListenerService.parseAmount("付款成功 人民币12,345.67元"), 0.001);
        assertEquals(1234.56, PaymentListenerService.parseAmount("支付成功￥1，234.56"), 0.001); // 全角逗号
    }

    @Test
    public void notificationAmount_unparsable() {
        assertEquals(-1, PaymentListenerService.parseAmount("支付成功，无金额"), 0.001);
    }

    @Test
    public void notificationPaymentText_classification() {
        assertTrue(PaymentListenerService.isPaymentText("微信支付 支付成功￥25.80"));
        assertTrue(PaymentListenerService.isPaymentText("交易提醒：你有一笔6.66元的支出"));
        assertTrue(PaymentListenerService.isPaymentText("微信红包支付8.88元"));
        assertFalse(PaymentListenerService.isPaymentText("微信支付 收到转账￥50.00"));
        assertFalse(PaymentListenerService.isPaymentText("交易提醒：你有一笔6.66元的收入"));
        assertFalse(PaymentListenerService.isPaymentText("红包已领取8.88元"));
        assertFalse(PaymentListenerService.isPaymentText("[微信红包]")); // 无金额
        assertFalse(PaymentListenerService.isPaymentText("支付成功")); // 无金额
    }

    // ───────── 银行短信通道 ─────────

    @Test
    public void smsAmount_posAndPlain() {
        assertEquals(88.0, SmsReceiver.parseAmount("您尾号1234的卡POS消费88.00元"), 0.001);
        assertEquals(1000.0, SmsReceiver.parseAmount("您尾号1234的卡消费1000.00元，余额5000.00元"), 0.001);
        assertEquals(500.0, SmsReceiver.parseAmount("您尾号1234账户支出500元"), 0.001);
        assertEquals(200.0, SmsReceiver.parseAmount("您尾号1234的卡转出200元"), 0.001);
    }

    @Test
    public void smsAmount_thousandSeparator() {
        assertEquals(1234.56,
                SmsReceiver.parseAmount("您尾号1234的储蓄卡消费支出人民币1,234.56元，余额8,888.00元"), 0.001);
        assertEquals(1234.00,
                SmsReceiver.parseAmount("您尾号1234的卡消费1,234.00元，余额5,000.00元"), 0.001);
    }

    @Test
    public void smsAmount_balanceIsNotSpending() {
        // 关键字之后才是余额：必须取关键字前面的消费金额
        assertEquals(100.0,
                SmsReceiver.parseAmount("您尾号1234的卡有一笔100.00元的消费，余额5000.00元"), 0.001);
        assertEquals(100.0,
                SmsReceiver.parseAmount("您尾号1234的卡有一笔100.00元的消费，可用额度5000.00元"), 0.001);
    }

    @Test
    public void smsAmount_none() {
        assertEquals(-1, SmsReceiver.parseAmount("您尾号1234的卡消费成功"), 0.001);
    }

    @Test
    public void smsDebitClassification() {
        assertTrue(SmsReceiver.isDebitSms("您尾号1234的卡POS消费88.00元"));
        assertTrue(SmsReceiver.isDebitSms("…扣款88.00元"));
        assertFalse(SmsReceiver.isDebitSms("您尾号1234的卡入账5000.00元"));
        assertFalse(SmsReceiver.isDebitSms("验证码123456，请勿泄露"));
        assertFalse(SmsReceiver.isDebitSms("您的退款88.00元已到账"));
    }
}
