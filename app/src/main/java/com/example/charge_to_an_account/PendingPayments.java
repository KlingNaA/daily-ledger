package com.example.charge_to_an_account;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 待记账队列：连续付款（金额各不相同）先入队，用户腾出手后打开弹窗逐笔确认。
 * 持久化到 SharedPreferences，进程被杀也不丢；条目 24 小时过期自动清理。
 */
public class PendingPayments {

    /** 一笔待记的付款 */
    public static class Entry {
        public final double amount;   // 付款金额；<=0 表示通知里没解析出金额，需手填
        public final long createdAt;  // 付款时间（记账时作为记录时间）
        public final String source;   // 来源：微信/支付宝/银行短信…
        public final String note;     // 商家提示（通知标题，用于备注预填与分类记忆）；可为空

        Entry(double amount, long createdAt, String source, String note) {
            this.amount = amount;
            this.createdAt = createdAt;
            this.source = source == null ? "" : source;
            this.note = note == null ? "" : note;
        }
    }

    private static final long EXPIRE_MS = 24 * 3600 * 1000L;

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("pending_payments", Context.MODE_PRIVATE);
    }

    public static synchronized void add(Context c, double amount, long at, String source, String note) {
        try {
            List<Entry> list = parse(prefs(c).getString("queue", "[]"));
            list.add(new Entry(amount, at, source, note));
            save(c, list);
        } catch (Exception ignored) {
        }
    }

    public static synchronized List<Entry> all(Context c) {
        try {
            List<Entry> list = parse(prefs(c).getString("queue", "[]"));
            save(c, list); // 顺带清掉过期条目
            return list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static synchronized void remove(Context c, Entry e) {
        try {
            List<Entry> list = parse(prefs(c).getString("queue", "[]"));
            for (int i = 0; i < list.size(); i++) {
                Entry it = list.get(i);
                if (it.createdAt == e.createdAt && it.amount == e.amount
                        && it.source.equals(e.source)) {
                    list.remove(i);
                    break;
                }
            }
            save(c, list);
        } catch (Exception ignored) {
        }
    }

    private static List<Entry> parse(String json) throws org.json.JSONException {
        List<Entry> list = new ArrayList<>();
        long now = System.currentTimeMillis();
        JSONArray arr = new JSONArray(json);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            long at = o.optLong("t", 0);
            if (now - at > EXPIRE_MS) continue; // 过期丢弃
            list.add(new Entry(o.optDouble("a", 0), at,
                    o.optString("s", ""), o.optString("n", "")));
        }
        return list;
    }

    private static void save(Context c, List<Entry> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : list) {
                JSONObject o = new JSONObject();
                o.put("a", e.amount);
                o.put("t", e.createdAt);
                o.put("s", e.source);
                o.put("n", e.note);
                arr.put(o);
            }
            prefs(c).edit().putString("queue", arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }
}
