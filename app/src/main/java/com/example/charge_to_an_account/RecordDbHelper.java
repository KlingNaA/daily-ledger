package com.example.charge_to_an_account;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 本地 SQLite 存储：records 单表 + categories/trigger_log/merchant_rules（v2）。无任何网络/云依赖 */
public class RecordDbHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "records.db";
    private static final int DB_VERSION = 2;

    static final String[] BUILTIN_CATEGORIES = {"用餐", "零食", "交通", "购物", "爱好", "日用", "其他"};

    private static final String TABLE = "records";
    private static final String COL_ID = "id";
    private static final String COL_AMOUNT = "amount";
    private static final String COL_CATEGORY = "category";
    private static final String COL_NOTE = "note";
    private static final String COL_CREATED_AT = "created_at";

    private static volatile RecordDbHelper instance;

    public static RecordDbHelper get(Context context) {
        if (instance == null) {
            synchronized (RecordDbHelper.class) {
                if (instance == null) {
                    instance = new RecordDbHelper(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private RecordDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                COL_AMOUNT + " REAL NOT NULL," +
                COL_CATEGORY + " TEXT NOT NULL," +
                COL_NOTE + " TEXT," +
                COL_CREATED_AT + " INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_records_created ON " + TABLE + "(" + COL_CREATED_AT + ")");
        createV2Tables(db);
        seedCategories(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            createV2Tables(db);
            seedCategories(db);
        }
    }

    private void createV2Tables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS categories (" +
                "name TEXT PRIMARY KEY," +
                "sort INTEGER NOT NULL," +
                "builtin INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS trigger_log (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "at INTEGER NOT NULL," +
                "source TEXT," +
                "text TEXT," +
                "result TEXT NOT NULL," +
                "amount REAL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS merchant_rules (" +
                "keyword TEXT PRIMARY KEY," +
                "category TEXT NOT NULL)");
    }

    private void seedCategories(SQLiteDatabase db) {
        // 只播种空表，避免升级时覆盖用户自定义
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM categories", null)) {
            if (c.moveToFirst() && c.getInt(0) > 0) return;
        }
        ContentValues cv = new ContentValues();
        for (int i = 0; i < BUILTIN_CATEGORIES.length; i++) {
            cv.clear();
            cv.put("name", BUILTIN_CATEGORIES[i]);
            cv.put("sort", i);
            cv.put("builtin", 1);
            db.insertWithOnConflict("categories", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
        }
    }

    // ───────── records ─────────

    public long insert(double amount, String category, String note, long createdAt) {
        ContentValues cv = new ContentValues();
        cv.put(COL_AMOUNT, amount);
        cv.put(COL_CATEGORY, category);
        cv.put(COL_NOTE, note);
        cv.put(COL_CREATED_AT, createdAt);
        rememberMerchant(note, category);
        return getWritableDatabase().insert(TABLE, null, cv);
    }

    public int update(long id, double amount, String category, String note) {
        ContentValues cv = new ContentValues();
        cv.put(COL_AMOUNT, amount);
        cv.put(COL_CATEGORY, category);
        cv.put(COL_NOTE, note);
        rememberMerchant(note, category);
        return getWritableDatabase().update(TABLE, cv, COL_ID + "=?",
                new String[]{String.valueOf(id)});
    }

    public void delete(long id) {
        getWritableDatabase().delete(TABLE, COL_ID + "=?",
                new String[]{String.valueOf(id)});
    }

    /** 某月（month 0-11，与 Calendar 一致）的所有记录，时间倒序 */
    public List<Record> queryByMonth(int year, int month) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.clear();
        cal.set(year, month, 1);
        long start = cal.getTimeInMillis();
        cal.add(java.util.Calendar.MONTH, 1);
        long end = cal.getTimeInMillis();

        List<Record> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(TABLE, null,
                COL_CREATED_AT + ">=? AND " + COL_CREATED_AT + "<?",
                new String[]{String.valueOf(start), String.valueOf(end)},
                null, null, COL_CREATED_AT + " DESC")) {
            while (c.moveToNext()) {
                list.add(new Record(
                        c.getLong(0), c.getDouble(1), c.getString(2),
                        c.getString(3), c.getLong(4)));
            }
        }
        return list;
    }

    /** 某月总支出 */
    public double sumByMonth(int year, int month) {
        double total = 0;
        for (Record r : queryByMonth(year, month)) total += r.amount;
        return total;
    }

    /** 全部记录（时间倒序），导出 CSV 用 */
    public List<Record> queryAll() {
        List<Record> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(TABLE, null, null, null,
                null, null, COL_CREATED_AT + " DESC")) {
            while (c.moveToNext()) {
                list.add(new Record(
                        c.getLong(0), c.getDouble(1), c.getString(2),
                        c.getString(3), c.getLong(4)));
            }
        }
        return list;
    }

    /** 某年全部记录（时间倒序），供统计页按月聚合 */
    public List<Record> queryByYear(int year) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.clear();
        cal.set(year, java.util.Calendar.JANUARY, 1);
        long start = cal.getTimeInMillis();
        cal.add(java.util.Calendar.YEAR, 1);
        long end = cal.getTimeInMillis();

        List<Record> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(TABLE, null,
                COL_CREATED_AT + ">=? AND " + COL_CREATED_AT + "<?",
                new String[]{String.valueOf(start), String.valueOf(end)},
                null, null, COL_CREATED_AT + " DESC")) {
            while (c.moveToNext()) {
                list.add(new Record(
                        c.getLong(0), c.getDouble(1), c.getString(2),
                        c.getString(3), c.getLong(4)));
            }
        }
        return list;
    }

    // ───────── categories（v2 自定义分类） ─────────

    /** 分类名列表（按 sort 排序）。空表时回退内置 7 类 */
    public List<String> categories() {
        List<String> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT name FROM categories ORDER BY sort, name", null)) {
            while (c.moveToNext()) list.add(c.getString(0));
        }
        if (list.isEmpty()) {
            java.util.Collections.addAll(list, BUILTIN_CATEGORIES);
        }
        return list;
    }

    public boolean addCategory(String name) {
        if (name == null || name.trim().isEmpty()) return false;
        name = name.trim();
        if (categories().contains(name)) return false;
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("sort", Integer.MAX_VALUE);
        cv.put("builtin", 0);
        return getWritableDatabase().insertWithOnConflict("categories", null, cv,
                SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    /** 删除分类；被流水引用或内置分类返回 false */
    public boolean deleteCategory(String name) {
        if (isBuiltinCategory(name)) return false;
        try (Cursor c = getReadableDatabase().query(TABLE, null,
                COL_CATEGORY + "=?", new String[]{name}, null, null, "1")) {
            if (c.getCount() > 0) return false;
        }
        getWritableDatabase().delete("categories", "name=?", new String[]{name});
        return true;
    }

    public boolean isBuiltinCategory(String name) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT builtin FROM categories WHERE name=?", new String[]{name})) {
            return c.moveToFirst() && c.getInt(0) == 1;
        }
    }

    /** 空分类表时播种内置 7 类（管理页操作前调用） */
    public void ensureCategoriesSeeded() {
        seedCategories(getWritableDatabase());
    }

    /** 上移/下移分类（delta -1/+1），越界无操作 */
    public void moveCategory(String name, int delta) {
        List<String> all = categories();
        int i = all.indexOf(name);
        int j = i + delta;
        if (i < 0 || j < 0 || j >= all.size()) return;
        java.util.Collections.swap(all, i, j);
        ContentValues cv = new ContentValues();
        for (int k = 0; k < all.size(); k++) {
            cv.clear();
            cv.put("sort", k);
            getWritableDatabase().update("categories", cv, "name=?", new String[]{all.get(k)});
        }
    }

    public void clearTriggerLogs() {
        getWritableDatabase().delete("trigger_log", null, null);
    }

    // ───────── trigger_log（v2 触发日志，环形 50 条） ─────────

    public void logTrigger(long at, String source, String text, String result, double amount) {
        ContentValues cv = new ContentValues();
        cv.put("at", at);
        cv.put("source", source);
        cv.put("text", text);
        cv.put("result", result);
        cv.put("amount", amount);
        getWritableDatabase().insert("trigger_log", null, cv);
        // 环形裁剪：只留最近 50 条
        getWritableDatabase().execSQL("DELETE FROM trigger_log WHERE id NOT IN " +
                "(SELECT id FROM trigger_log ORDER BY id DESC LIMIT 50)");
    }

    /** 触发日志（时间倒序） */
    public List<TriggerLogEntry> triggerLogs() {
        List<TriggerLogEntry> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT at, source, text, result, amount FROM trigger_log ORDER BY id DESC LIMIT 50", null)) {
            while (c.moveToNext()) {
                list.add(new TriggerLogEntry(c.getLong(0), c.getString(1),
                        c.getString(2), c.getString(3), c.getDouble(4)));
            }
        }
        return list;
    }

    public static class TriggerLogEntry {
        public final long at;
        public final String source;
        public final String text;
        public final String result;
        public final double amount;

        TriggerLogEntry(long at, String source, String text, String result, double amount) {
            this.at = at;
            this.source = source;
            this.text = text;
            this.result = result;
            this.amount = amount;
        }
    }

    // ───────── merchant_rules（v2 备注关键词→分类） ─────────

    /** 备注前 4 个非空字符为关键词（统一小写，大小写不敏感），记一条映射；关键词太短（<2）不记 */
    private void rememberMerchant(String note, String category) {
        if (note == null || note.length() < 2 || category == null) return;
        String kw = normalizeKeyword(note);
        if (kw.isEmpty()) return;
        ContentValues cv = new ContentValues();
        cv.put("keyword", kw);
        cv.put("category", category);
        getWritableDatabase().insertWithOnConflict("merchant_rules", null, cv,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** 按备注前缀查记忆中的分类；无则 null */
    public String categoryForNote(String note) {
        if (note == null || note.length() < 2) return null;
        String kw = normalizeKeyword(note);
        if (kw.isEmpty()) return null;
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT category FROM merchant_rules WHERE keyword = ? COLLATE NOCASE",
                new String[]{kw})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    private static String normalizeKeyword(String note) {
        String head = note.length() > 4 ? note.substring(0, 4) : note;
        return head.trim().toLowerCase(Locale.ROOT);
    }
}
