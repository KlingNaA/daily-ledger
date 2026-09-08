package com.example.charge_to_an_account;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 触发日志：每条通知/短信的判定过程，用于排查漏弹/误弹 */
public class TriggerLogActivity extends Activity {

    private RecordDbHelper db;
    private LinearLayout list;
    private final SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_trigger_log);
        // 水滴屏/刘海屏避让：顶部加 system bar + 剪裁内边距
        android.view.View root = findViewById(R.id.root);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
                            | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return androidx.core.view.WindowInsetsCompat.CONSUMED;
        });
        db = RecordDbHelper.get(this);
        list = findViewById(R.id.listLogs);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnClear).setOnClickListener(v -> {
            db.clearTriggerLogs();
            Toast.makeText(this, R.string.log_cleared, Toast.LENGTH_SHORT).show();
            rebuild();
        });
        findViewById(R.id.btnExportLog).setOnClickListener(v -> exportLogCsv());
        rebuild();
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuild();
    }

    private void rebuild() {
        list.removeAllViews();
        List<RecordDbHelper.TriggerLogEntry> logs = db.triggerLogs();
        if (logs.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.log_empty);
            empty.setTextColor(ContextCompat.getColor(this, R.color.ink_faint));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(16), dp(32), dp(16), dp(32));
            list.addView(empty);
            return;
        }

        for (RecordDbHelper.TriggerLogEntry e : logs) {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(0, dp(10), 0, dp(10));

            TextView head = new TextView(this);
            String resultLabel = resultLabel(e.result);
            boolean triggered = e.result.startsWith("queued") || e.result.equals("acc_trigger");
            head.setText(String.format(Locale.CHINA, "%s · %s · %s",
                    fmt.format(new Date(e.at)),
                    displaySource(e.source), resultLabel));
            head.setTextColor(ContextCompat.getColor(this, triggered ? R.color.seal_red : R.color.ink_faint));
            head.setTextSize(12);
            head.setTypeface(null, android.graphics.Typeface.BOLD);
            box.addView(head);

            if (e.text != null && !e.text.isEmpty()) {
                TextView body = new TextView(this);
                body.setText(e.text);
                body.setTextColor(ContextCompat.getColor(this, R.color.ink_soft));
                body.setTextSize(13);
                body.setMaxLines(2);
                box.addView(body);
            }
            if (triggered && e.amount > 0) {
                TextView amount = new TextView(this);
                amount.setText(String.format(Locale.CHINA, "金额 ¥%.2f", e.amount));
                amount.setTextColor(ContextCompat.getColor(this, R.color.ink));
                amount.setTextSize(12);
                box.addView(amount);
            }

            View rule = new View(this);
            rule.setBackgroundResource(R.drawable.dotted_leader);
            list.addView(box);
            list.addView(rule);
        }
    }

    private String displaySource(String source) {
        if (source == null || source.isEmpty()) return getString(R.string.log_untitled);
        if (source.startsWith("com.")) {
            try {
                return PaymentListenerService.sourceName(source);
            } catch (Exception ignored) {
            }
        }
        return source;
    }

    private String resultLabel(String result) {
        switch (result) {
            case "queued_popup": return getString(R.string.log_result_queued_popup);
            case "queued_notify": return getString(R.string.log_result_queued_notify);
            case "popup_blocked": return getString(R.string.log_result_popup_blocked);
            case "not_payment": return getString(R.string.log_result_not_payment);
            case "not_whitelist": return getString(R.string.log_result_not_whitelist);
            case "no_text": return getString(R.string.log_result_no_text);
            case "self": return getString(R.string.log_result_self);
            case "dup_key": return getString(R.string.log_result_dup_key);
            case "dup_amount": return getString(R.string.log_result_dup_amount);
            case "sms_not_debit": return getString(R.string.log_result_sms_not_debit);
            case "sms_no_amount": return getString(R.string.log_result_sms_no_amount);
            case "acc_trigger": return getString(R.string.log_result_acc_trigger);
            case "acc_page": return getString(R.string.log_result_acc_page);
            case "acc_debug_page": return getString(R.string.log_result_acc_debug_page);
            case "acc_armed": return getString(R.string.log_result_acc_armed);
            case "acc_pay_dialog": return getString(R.string.log_result_acc_pay_dialog);
            case "acc_settle_read": return getString(R.string.log_result_acc_settle_read);
            case "acc_settle_no_receipt": return getString(R.string.log_result_acc_settle_no_receipt);
            case "acc_no_root": return getString(R.string.log_result_acc_no_root);
            case "acc_empty_page": return getString(R.string.log_result_acc_empty_page);
            case "acc_no_success": return getString(R.string.log_result_acc_no_success);
            case "acc_not_payment": return getString(R.string.log_result_acc_not_payment);
            default: return result;
        }
    }

    /** 导出触发日志 CSV（排查漏弹误弹时把日志发给开发者） */
    private void exportLogCsv() {
        List<RecordDbHelper.TriggerLogEntry> logs = db.triggerLogs();
        if (logs.isEmpty()) {
            Toast.makeText(this, R.string.log_empty_short, Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder sb = new StringBuilder("\uFEFF时间,来源,判定,金额,文本\r\n");
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
        for (RecordDbHelper.TriggerLogEntry e : logs) {
            sb.append(f.format(new Date(e.at))).append(',')
                    .append(csv(e.source)).append(',')
                    .append(csv(resultLabel(e.result))).append(',')
                    .append(e.amount > 0 ? String.valueOf(e.amount) : "").append(',')
                    .append(csv(e.text)).append("\r\n");
        }
        String name = "记账触发日志_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA)
                .format(new Date()) + ".csv";
        byte[] bytes = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/csv");
                android.net.Uri uri = getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new IllegalStateException("insert failed");
                try (java.io.OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os == null) throw new IllegalStateException("stream null");
                    os.write(bytes);
                }
            } else {
                java.io.File file = new java.io.File(getExternalFilesDir(null), name);
                try (java.io.FileOutputStream os = new java.io.FileOutputStream(file)) {
                    os.write(bytes);
                }
            }
            Toast.makeText(this, getString(R.string.export_done, "下载/" + name), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_LONG).show();
        }
    }

    private static String csv(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
