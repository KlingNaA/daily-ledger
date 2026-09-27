package com.example.charge_to_an_account;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 快速记账弹窗。Dialog 主题，小窗口秒开。
 * 支持待记账队列：连续付款先入队（PendingPayments），打开后逐笔确认——
 * 保存/跳过一笔自动切下一笔，付款时间沿用支付时刻。
 * 编辑已有记录（EXTRA_ID）时忽略队列。
 */
public class QuickRecordActivity extends Activity {

    public static final String EXTRA_AMOUNT = "amount";
    public static final String EXTRA_ID = "id";
    public static final String EXTRA_CATEGORY = "category";
    public static final String EXTRA_NOTE = "note";
    public static final String EXTRA_CREATED_AT = "createdAt";

    static final String[] FALLBACK_CATEGORIES = {"用餐", "零食", "交通", "购物", "爱好", "日用", "其他"};

    private long editId = -1;
    private long createdAt = System.currentTimeMillis();
    private String selectedCategory;
    private final List<ToggleButton> letterpressBlocks = new ArrayList<>();
    private List<String> categories = new ArrayList<>();
    private RecordDbHelper db;
    private final java.text.SimpleDateFormat fullDateFmt =
            new java.text.SimpleDateFormat("yyyy年M月d日 HH:mm", java.util.Locale.CHINA);

    private EditText etAmount;
    private EditText etNote;
    private TextView tvQueuePos;
    private TextView btnSkip;
    private PendingPayments.Entry currentEntry; // 队列中正在确认的这笔
    private int queueTotal;
    private int queueSaved;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_quick_record);

        setFinishOnTouchOutside(true);

        // 标记弹窗已实际展示，撤销兜底通知（避免残留打扰）
        PaymentListenerService.popupShown = true;
        android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.cancel(PaymentListenerService.BACKUP_NOTIF_ID);

        db = RecordDbHelper.get(this);
        categories = db.categories();
        if (categories.isEmpty()) {
            java.util.Collections.addAll(categories, FALLBACK_CATEGORIES);
        }

        // Android 15+ 强制 edge-to-edge 后 manifest 的 adjustResize 对 target 35+ 已失效，
        // 这里自己把 IME/刘海内边距加到滚动容器上，保证「盖章存档」永远可滚到键盘上方
        android.view.View scroll = findViewById(R.id.quickScroll);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
                            | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            androidx.core.graphics.Insets ime = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right,
                    Math.max(bars.bottom, ime.bottom));
            return androidx.core.view.WindowInsetsCompat.CONSUMED;
        });

        Intent intent = getIntent();
        editId = intent.getLongExtra(EXTRA_ID, -1);

        etAmount = findViewById(R.id.etAmount);
        etNote = findViewById(R.id.etNote);
        tvQueuePos = findViewById(R.id.tvQueuePos);
        btnSkip = findViewById(R.id.btnSkip);
        GridLayout grid = findViewById(R.id.gridCategory);
        // 编辑时点金额框默认全选，直接输入即可覆盖，避免光标插在中间改出错误金额
        etAmount.setSelectAllOnFocus(true);
        // 键盘不再全局强制常显（manifest 已改 adjustResize）：新记一笔主动弹一次键盘提速，
        // 编辑模式不自动弹（点金额框再弹，点空白收起后不会复活）

        for (String cat : categories) {
            ToggleButton block = new ToggleButton(this);
            block.setTextOff(cat);
            block.setTextOn(cat);
            block.setChecked(false);
            block.setAllCaps(false);
            block.setBackgroundResource(R.drawable.selector_letterpress);
            block.setTextColor(ContextCompat.getColorStateList(this, R.color.selector_letterpress_text));
            block.setTextSize(14);
            block.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            block.setMinWidth(0);
            block.setMinimumWidth(0);
            block.setMinHeight(0);
            block.setMinimumHeight(0);
            block.setPadding(0, dp(8), 0, dp(8));
            block.setOnCheckedChangeListener((v, checked) -> {
                if (checked) selectCategory(cat);
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
            lp.setMargins(0, dp(6), dp(8), dp(6));
            block.setLayoutParams(lp);
            grid.addView(block);
            letterpressBlocks.add(block);
        }

        Button btnSave = findViewById(R.id.btnSave);
        btnSave.setOnClickListener(v -> save());
        btnSkip.setOnClickListener(v -> skipCurrent());

        // 编辑模式换样式：标题、可修改的记账时间、按钮文案与"记一笔"区分
        TextView tvTitle = findViewById(R.id.tvTitle);
        TextView tvEditTime = findViewById(R.id.tvEditTime);
        if (editId >= 0) {
            tvTitle.setText(R.string.edit_record_title);
            tvEditTime.setVisibility(android.view.View.VISIBLE);
            // 记账时间也可变更（补记错日期/时间的场景）：点按 → 日期 + 时间选择器
            tvEditTime.setOnClickListener(v -> showDateTimeEditor());
            btnSave.setText(R.string.edit_save);
        } else {
            tvTitle.setText(R.string.quick_record_title);
            tvEditTime.setVisibility(android.view.View.GONE);
            btnSave.setText(R.string.save);
        }

        if (editId >= 0) {
            // 编辑模式：绑定被点记录，忽略队列
            bindExtras(intent);
        } else {
            List<PendingPayments.Entry> queue = PendingPayments.all(this);
            if (!queue.isEmpty()) {
                queueTotal = queue.size();
                queueSaved = 0;
                bindQueueEntry(queue.get(0));
            } else {
                bindExtras(intent);
            }
        }

        // 时间行必须在 bindExtras/bindQueueEntry 之后渲染：createdAt 由它们从 extras/队列赋值，
        // 之前先渲染会显示成"当前时间"（v1.6 修）
        if (editId >= 0) updateEditTimeText();

        // 键盘上直接点确认也可保存，少一次点击
        etNote.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                save();
                return true;
            }
            return false;
        });

        // 新记一笔：主动弹一次键盘（编辑模式不弹，见上）
        if (editId < 0) {
            etAmount.requestFocus();
            getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                            | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    /**
     * 弹窗已在前台时又来一笔付款：系统只回调这里（manifest singleTop + trigger 的 SINGLE_TOP），
     * 不再销毁重建 Activity——否则用户正在输入/编辑的内容会被静默清空（v1.6 修）。
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // 弹窗确实在前台：撤销兜底通知，并标记已展示（onCreate 不会再走一遍）
        PaymentListenerService.popupShown = true;
        android.app.NotificationManager nm =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.cancel(PaymentListenerService.BACKUP_NOTIF_ID);

        if (editId >= 0) {
            // 正在改旧记录：绝不打断，新付款留在队列里
            Toast.makeText(this, R.string.queue_arrived_editing, Toast.LENGTH_LONG).show();
            return;
        }
        List<PendingPayments.Entry> queue = PendingPayments.all(this);
        if (queue.isEmpty()) return;
        if (currentEntry != null) {
            queueTotal = queue.size();
            updateQueueUi();
        } else if (etAmount.getText().toString().trim().isEmpty()
                && etNote.getText().toString().trim().isEmpty()) {
            // 手输但还没填任何内容：直接切到这笔新付款
            queueTotal = queue.size();
            queueSaved = 0;
            bindQueueEntry(queue.get(0));
        }
        Toast.makeText(this, getString(R.string.queue_arrived, queue.size()),
                Toast.LENGTH_SHORT).show();
    }

    /** 手动入口 / 编辑 / 无队列：按 intent extras 绑定 */
    private void bindExtras(Intent intent) {
        currentEntry = null;
        updateQueueUi();
        createdAt = intent.getLongExtra(EXTRA_CREATED_AT, System.currentTimeMillis());
        String presetAmount = intent.hasExtra(EXTRA_AMOUNT)
                ? String.valueOf(intent.getDoubleExtra(EXTRA_AMOUNT, 0)) : null;
        if (presetAmount != null) {
            setAmountText(trimAmount(presetAmount));
        } else {
            etAmount.setText("");
        }
        String note = intent.getStringExtra(EXTRA_NOTE);
        etNote.setText(note);
        String presetCategory = intent.getStringExtra(EXTRA_CATEGORY);
        selectCategory(validCategory(presetCategory, note));
        etAmount.requestFocus();
        etAmount.selectAll();
    }

    /** 绑定队列中的一笔：预填金额/商家备注、沿用付款时间，商家命中记忆分类则自动选中 */
    private void bindQueueEntry(PendingPayments.Entry e) {
        currentEntry = e;
        createdAt = e.createdAt;
        setAmountText(e.amount > 0 ? trimAmount(String.valueOf(e.amount)) : "");
        etNote.setText(e.note == null ? "" : e.note);
        selectCategory(validCategory(db.categoryForNote(e.note), e.note));
        updateQueueUi();
        etAmount.requestFocus();
        etAmount.selectAll();
    }

    /** 校验分类可用（存在列表中），不可用则：有备注先查商家记忆，再回退第一项 */
    private String validCategory(String cat, String note) {
        if (cat != null && categories.contains(cat)) return cat;
        String remembered = db.categoryForNote(note);
        if (remembered != null && categories.contains(remembered)) return remembered;
        return categories.get(0);
    }

    private void updateEditTimeText() {
        TextView tvEditTime = findViewById(R.id.tvEditTime);
        tvEditTime.setText(String.format(java.util.Locale.CHINA,
                getString(R.string.edit_time_fmt), fullDateFmt.format(new java.util.Date(createdAt))));
    }

    /** 修改记账时间：先选日期再选时间（编辑模式专用） */
    private void showDateTimeEditor() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(createdAt);
        new android.app.DatePickerDialog(this, (dp, y, m, day) -> {
            c.set(java.util.Calendar.YEAR, y);
            c.set(java.util.Calendar.MONTH, m);
            c.set(java.util.Calendar.DAY_OF_MONTH, day);
            new android.app.TimePickerDialog(this, (tp, h, min) -> {
                c.set(java.util.Calendar.HOUR_OF_DAY, h);
                c.set(java.util.Calendar.MINUTE, min);
                c.set(java.util.Calendar.SECOND, 0);
                createdAt = c.getTimeInMillis();
                updateEditTimeText();
            }, c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), true).show();
        }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH),
                c.get(java.util.Calendar.DAY_OF_MONTH)).show();
    }

    private void updateQueueUi() {
        if (currentEntry == null) {
            tvQueuePos.setVisibility(android.view.View.GONE);
            btnSkip.setVisibility(android.view.View.GONE);
            return;
        }
        // 按唯一 id 定位（同毫秒孪生条目按 createdAt+amount 会错位，v1.6.4 修）；
        // 队列里已找不到（被外部移除）则隐藏计数
        List<PendingPayments.Entry> rest = PendingPayments.all(this);
        int idx = -1;
        for (int i = 0; i < rest.size(); i++) {
            PendingPayments.Entry it = rest.get(i);
            boolean match = currentEntry.id != 0 && it.id == currentEntry.id;
            if (!match && currentEntry.id == 0 && it.id == 0
                    && it.createdAt == currentEntry.createdAt && it.amount == currentEntry.amount) {
                match = true;
            }
            if (match) {
                idx = i + 1;
                break;
            }
        }
        if (idx <= 0) {
            tvQueuePos.setVisibility(android.view.View.GONE);
            btnSkip.setVisibility(android.view.View.GONE);
            return;
        }
        tvQueuePos.setVisibility(android.view.View.VISIBLE);
        tvQueuePos.setText(getString(R.string.queue_pos, idx, rest.size()));
        btnSkip.setVisibility(android.view.View.VISIBLE);
    }

    private void setAmountText(String s) {
        etAmount.setText(s);
        etAmount.setSelection(etAmount.getText().length());
    }

    private static String trimAmount(String s) {
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    private void selectCategory(String cat) {
        selectedCategory = cat;
        for (ToggleButton block : letterpressBlocks) {
            // setChecked 触发监听器但 checked=false 分支为空操作，不会递归
            block.setChecked(block.getText().toString().equals(cat));
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void save() {
        String text = etAmount.getText().toString().trim();
        if (text.isEmpty()) {
            etAmount.setError(getString(R.string.err_amount_required));
            etAmount.requestFocus();
            return;
        }
        double amount;
        try {
            amount = Double.parseDouble(text);
        } catch (NumberFormatException e) {
            etAmount.setError(getString(R.string.err_amount_invalid));
            return;
        }
        if (amount <= 0) {
            etAmount.setError(getString(R.string.err_amount_invalid));
            return;
        }
        RecordDbHelper db = RecordDbHelper.get(this);
        String note = etNote.getText().toString().trim();
        if (editId >= 0) {
            db.update(editId, amount, selectedCategory, note, createdAt);
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        db.insert(amount, selectedCategory, note, createdAt);
        if (currentEntry != null) {
            PendingPayments.remove(this, currentEntry);
            currentEntry = null;
            queueSaved++;
            List<PendingPayments.Entry> rest = PendingPayments.all(this);
            if (!rest.isEmpty()) {
                Toast.makeText(this, getString(R.string.queue_saved, queueSaved, queueTotal),
                        Toast.LENGTH_SHORT).show();
                bindQueueEntry(rest.get(0));
                return;
            }
        }
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    /** 跳过当前这笔（不入账），继续下一笔 */
    private void skipCurrent() {
        if (currentEntry == null) {
            finish();
            return;
        }
        PendingPayments.remove(this, currentEntry);
        currentEntry = null;
        List<PendingPayments.Entry> rest = PendingPayments.all(this);
        Toast.makeText(this, R.string.skipped, Toast.LENGTH_SHORT).show();
        if (rest.isEmpty()) {
            finish();
        } else {
            bindQueueEntry(rest.get(0));
        }
    }
}
