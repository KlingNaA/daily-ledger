package com.example.charge_to_an_account;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_QUICK_RECORD = 1;

    private final Calendar currentMonth = Calendar.getInstance();
    private int statsYear = Calendar.getInstance().get(Calendar.YEAR);

    private TextView tvMonth, tvMonthTotal, tvStatsYear, tvYearTotal, tvYearAvg;
    private BarChartView barChart;
    private MonthBarsView monthBars;
    private LinearLayout listRecords, layoutPermissions, layoutSmsNotice;
    private SectionPager pager;
    private TextView navLedger, navStats, navMine;
    private TextView tvNotifStatus, tvOverlayStatus, tvFsStatus, tvSmsStatus, tvMiuiStatus, tvAccStatus;
    private View rowAcc, rowFullscreen, rowMiui, rowBattery, tvXiaomiHint;
    private RecordDbHelper db;
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
    private final SimpleDateFormat mastheadDateFmt = new SimpleDateFormat("yyyy年M月d日 EEEE", Locale.CHINA);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        db = RecordDbHelper.get(this);

        // targetSdk 35+ 强制 edge-to-edge：避让状态栏/手势条
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        ((TextView) findViewById(R.id.tvMastheadDate)).setText(mastheadDateFmt.format(new Date()));

        tvMonth = findViewById(R.id.tvMonth);
        tvMonthTotal = findViewById(R.id.tvMonthTotal);
        barChart = findViewById(R.id.barChart);
        listRecords = findViewById(R.id.listRecords);
        layoutPermissions = findViewById(R.id.layoutPermissions);
        layoutSmsNotice = findViewById(R.id.layoutSmsNotice);
        pager = findViewById(R.id.pager);
        navLedger = findViewById(R.id.navLedger);
        navStats = findViewById(R.id.navStats);
        navMine = findViewById(R.id.navMine);
        tvStatsYear = findViewById(R.id.tvStatsYear);
        tvYearTotal = findViewById(R.id.tvYearTotal);
        tvYearAvg = findViewById(R.id.tvYearAvg);
        monthBars = findViewById(R.id.monthBars);
        tvNotifStatus = findViewById(R.id.tvNotifStatus);
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus);
        tvFsStatus = findViewById(R.id.tvFsStatus);
        tvSmsStatus = findViewById(R.id.tvSmsStatus);
        tvMiuiStatus = findViewById(R.id.tvMiuiStatus);
        tvAccStatus = findViewById(R.id.tvAccStatus);
        rowAcc = findViewById(R.id.rowAcc);
        rowFullscreen = findViewById(R.id.rowFullscreen);
        rowMiui = findViewById(R.id.rowMiui);
        rowBattery = findViewById(R.id.rowBattery);
        tvXiaomiHint = findViewById(R.id.tvXiaomiHint);

        findViewById(R.id.btnPrevMonth).setOnClickListener(v -> {
            currentMonth.add(Calendar.MONTH, -1);
            refresh();
        });
        findViewById(R.id.btnNextMonth).setOnClickListener(v -> {
            currentMonth.add(Calendar.MONTH, 1);
            refresh();
        });
        tvMonth.setOnClickListener(v -> showMonthPicker());
        findViewById(R.id.btnYearPrev).setOnClickListener(v -> {
            statsYear--;
            refreshStats();
        });
        findViewById(R.id.btnYearNext).setOnClickListener(v -> {
            statsYear++;
            refreshStats();
        });
        findViewById(R.id.fabAdd).setOnClickListener(v ->
                startActivityForResult(new Intent(this, QuickRecordActivity.class), REQ_QUICK_RECORD));

        // 底部导航切换（保留翻版动画），滑动切换依然可用
        navLedger.setOnClickListener(v -> pager.setPage(0));
        navStats.setOnClickListener(v -> pager.setPage(1));
        navMine.setOnClickListener(v -> pager.setPage(2));
        pager.setOnPageSelected(this::applyNavStyles);

        // 首次布局完成后定位指示条（布局未完成时 animateNavIndicator 会跳过）
        findViewById(R.id.navIndicatorTrack).post(() -> animateNavIndicator(0));

        // 权限入口
        findViewById(R.id.btnNotifAccess).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        findViewById(R.id.btnNotifAccess2).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        findViewById(R.id.btnOverlay).setOnClickListener(v -> openOverlaySettings());
        findViewById(R.id.btnOverlay2).setOnClickListener(v -> openOverlaySettings());
        findViewById(R.id.btnFullScreen).setOnClickListener(v -> openFullScreenSettings());
        findViewById(R.id.btnFs2).setOnClickListener(v -> openFullScreenSettings());
        findViewById(R.id.btnSmsPermission).setOnClickListener(v -> requestSmsPermission());
        findViewById(R.id.btnSms2).setOnClickListener(v -> requestSmsPermission());
        findViewById(R.id.btnMiui).setOnClickListener(v -> openMiuiPopupPermission());
        findViewById(R.id.btnBattery).setOnClickListener(v -> openAppDetails());
        findViewById(R.id.btnAcc).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.btnExport).setOnClickListener(v -> exportCsv());
        findViewById(R.id.btnCategories).setOnClickListener(v ->
                startActivity(new Intent(this, CategoryManageActivity.class)));
        findViewById(R.id.btnTriggerLog).setOnClickListener(v ->
                startActivity(new Intent(this, TriggerLogActivity.class)));
        findViewById(R.id.tvQueueInfo).setOnClickListener(v -> {
            // 队列非空时点击直达逐笔确认
            if (!PendingPayments.all(this).isEmpty()) {
                startActivity(new Intent(this, QuickRecordActivity.class));
            }
        });

        // Android 13+ 需要运行时申请通知权限
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }

        refresh();
        refreshStats();
        applyNavStyles(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        refreshStats();
        refreshMineStatus();
        refreshNoticeCard();
    }

    private void refreshNoticeCard() {
        boolean overlayOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
        boolean notifOk = isNotificationListenerEnabled();
        boolean fullscreenNeeded = Build.VERSION.SDK_INT >= 34;
        boolean fullscreenOk = !fullscreenNeeded || ((android.app.NotificationManager)
                getSystemService(NOTIFICATION_SERVICE)).canUseFullScreenIntent();
        layoutPermissions.setVisibility(overlayOk && notifOk && fullscreenOk ? View.GONE : View.VISIBLE);
        findViewById(R.id.btnFullScreen).setVisibility(
                fullscreenNeeded && !fullscreenOk ? View.VISIBLE : View.GONE);
    }

    private void refreshMineStatus() {
        setStatus(tvNotifStatus, isNotificationListenerEnabled());
        setStatus(tvOverlayStatus,
                Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this));
        boolean fullscreenNeeded = Build.VERSION.SDK_INT >= 34;
        rowFullscreen.setVisibility(fullscreenNeeded ? View.VISIBLE : View.GONE);
        if (fullscreenNeeded) {
            setStatus(tvFsStatus, ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .canUseFullScreenIntent());
        }
        setStatus(tvSmsStatus, checkSelfPermission(Manifest.permission.RECEIVE_SMS)
                == PackageManager.PERMISSION_GRANTED);

        // 无障碍开关状态（微信转账/红包识别）；未开时展示说明
        boolean accOn = isAccServiceEnabled();
        setStatus(tvAccStatus, accOn);
        findViewById(R.id.tvAccHint).setVisibility(accOn ? View.GONE : View.VISIBLE);

        boolean xiaomi = isXiaomi();
        rowMiui.setVisibility(xiaomi ? View.VISIBLE : View.GONE);
        rowBattery.setVisibility(xiaomi ? View.VISIBLE : View.GONE);
        tvXiaomiHint.setVisibility(xiaomi ? View.VISIBLE : View.GONE);
        if (xiaomi) {
            // 系统无公开 API 查询"后台弹出界面"授权状态，显示中性提示避免"已开仍报未开"的误导
            tvMiuiStatus.setText(R.string.status_undetectable);
            tvMiuiStatus.setTextColor(ContextCompat.getColor(this, R.color.ink_faint));
        }

        // 待记账队列可视化
        TextView tvQueueInfo = findViewById(R.id.tvQueueInfo);
        List<PendingPayments.Entry> queue = PendingPayments.all(this);
        if (queue.isEmpty()) {
            tvQueueInfo.setText(R.string.queue_empty);
            tvQueueInfo.setTextColor(ContextCompat.getColor(this, R.color.ink_faint));
        } else {
            double sum = 0;
            for (PendingPayments.Entry e : queue) {
                if (e.amount > 0) sum += e.amount;
            }
            tvQueueInfo.setText(getString(R.string.queue_info_fmt, queue.size(), sum));
            tvQueueInfo.setTextColor(ContextCompat.getColor(this, R.color.seal_red));
            tvQueueInfo.setTypeface(null, android.graphics.Typeface.BOLD);
        }
    }

    private void setStatus(TextView tv, boolean on) {
        tv.setText(on ? R.string.status_on : R.string.status_off);
        tv.setTextColor(ContextCompat.getColor(this, on ? R.color.seal_red : R.color.ink_faint));
    }

    private boolean isAccServiceEnabled() {
        // 优先读系统设置串（MIUI 上 AccessibilityManager 列表查询不可靠，真机踩坑）；
        // 兼容全名与缩写两种存储格式：pkg/pkg.Cls 与 pkg/.Cls
        String flat = android.provider.Settings.Secure.getString(getContentResolver(),
                "enabled_accessibility_services");
        if (flat != null) {
            String cls = PayAccessibilityService.class.getName();
            String simple = PayAccessibilityService.class.getSimpleName();
            return flat.contains(getPackageName() + "/" + cls)
                    || flat.contains(getPackageName() + "/." + simple)
                    || (flat.contains(getPackageName()) && flat.contains(simple));
        }
        android.view.accessibility.AccessibilityManager am =
                (android.view.accessibility.AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        String target = getPackageName() + "/" + PayAccessibilityService.class.getName();
        for (android.accessibilityservice.AccessibilityServiceInfo info :
                am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            String id = info.getId();
            if (target.equals(id) || (getPackageName() + "/." + PayAccessibilityService.class.getSimpleName()).equals(id)) {
                return true;
            }
        }
        return false;
    }

    private boolean isXiaomi() {
        return Build.MANUFACTURER != null
                && Build.MANUFACTURER.toLowerCase(Locale.ROOT).contains("xiaomi");
    }

    private void openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    private void openFullScreenSettings() {
        if (Build.VERSION.SDK_INT >= 31) {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    /** 小米「后台弹出界面」权限编辑页（HyperOS 拦截付款弹窗的元凶），失败则退回应用详情 */
    private void openMiuiPopupPermission() {
        try {
            Intent i = new Intent("miui.intent.action.APP_PERM_EDITOR");
            i.setClassName("com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity");
            i.putExtra("extra_pkgname", getPackageName());
            startActivity(i);
        } catch (Exception e) {
            try {
                Intent i = new Intent("miui.intent.action.APP_PERM_EDITOR");
                i.putExtra("extra_pkgname", getPackageName());
                startActivity(i);
            } catch (Exception e2) {
                openAppDetails();
            }
        }
    }

    private void openAppDetails() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
    }

    private void requestSmsPermission() {
        if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.sms_enabled, Toast.LENGTH_LONG).show();
            return;
        }
        requestPermissions(new String[]{Manifest.permission.RECEIVE_SMS}, 3);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 3) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Toast.makeText(this, granted ? R.string.sms_enabled : R.string.sms_disabled,
                    Toast.LENGTH_SHORT).show();
            refreshMineStatus();
            layoutSmsNotice.setVisibility(granted ? View.GONE : View.VISIBLE);
        }
    }

    private boolean isNotificationListenerEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        return flat != null && flat.contains(getPackageName());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_QUICK_RECORD) refresh();
    }

    private void applyNavStyles(int page) {
        styleNav(navLedger, page == 0);
        styleNav(navStats, page == 1);
        styleNav(navMine, page == 2);
        animateNavIndicator(page);
    }

    private void styleNav(TextView tv, boolean selected) {
        tv.setSelected(selected); // 驱动 nav_tab_bg：选中=加深底色
        tv.setTextColor(ContextCompat.getColor(this, selected ? R.color.seal_red : R.color.ink_soft));
        tv.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    /** 印章红指示条曲线滑到当前版面（与翻页动画同步） */
    private void animateNavIndicator(int page) {
        View track = findViewById(R.id.navIndicatorTrack);
        View indicator = findViewById(R.id.navIndicator);
        int w = track.getWidth();
        if (w == 0) return; // 布局未完成，首次由 post 补
        int tabW = w / 3;
        if (indicator.getWidth() != tabW) {
            indicator.getLayoutParams().width = tabW;
            indicator.requestLayout();
        }
        float targetX = page * tabW;
        indicator.animate()
                .translationX(targetX)
                .setDuration(280)
                .setInterpolator(new android.view.animation.OvershootInterpolator(0.8f))
                .start();
    }

    // ─────────────────────────── 记账页 ───────────────────────────

    private void refresh() {
        int year = currentMonth.get(Calendar.YEAR);
        int month = currentMonth.get(Calendar.MONTH);
        tvMonth.setText(String.format(Locale.CHINA, "%d年%d月", year, month + 1));

        List<Record> records = db.queryByMonth(year, month);

        double total = 0;
        java.util.Map<String, Double> sumByCategory = new java.util.LinkedHashMap<>();
        for (Record r : records) {
            total += r.amount;
            sumByCategory.put(r.category, sumByCategory.getOrDefault(r.category, 0.0) + r.amount);
        }
        tvMonthTotal.setText(String.format(Locale.CHINA, getString(R.string.month_total_format), total));
        barChart.setData(sumByCategory);

        listRecords.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (Record r : records) {
            SwipeDeleteRow container = new SwipeDeleteRow(this);
            View row = inflater.inflate(R.layout.item_record, container, false);
            ((TextView) row.findViewById(R.id.tvCategory)).setText(r.category);
            ((TextView) row.findViewById(R.id.tvNote)).setText(
                    r.note == null || r.note.isEmpty() ? "—" : r.note);
            ((TextView) row.findViewById(R.id.tvDate)).setText(
                    dateFmt.format(new Date(r.createdAt)));
            ((TextView) row.findViewById(R.id.tvAmount)).setText(
                    String.format(Locale.CHINA, "¥%.2f", r.amount));
            container.setContent(row);
            container.setOnRowLongPress(() -> editRecord(r));
            container.setOnDelete(() -> {
                db.delete(r.id);
                refresh();
            });
            listRecords.addView(container);
        }

        if (records.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.no_records);
            empty.setPadding(32, 32, 32, 32);
            empty.setGravity(android.view.Gravity.CENTER);
            empty.setTextColor(0xFF888888);
            listRecords.addView(empty);
        }
    }

    private void editRecord(Record r) {
        Intent intent = new Intent(this, QuickRecordActivity.class);
        intent.putExtra(QuickRecordActivity.EXTRA_ID, r.id);
        intent.putExtra(QuickRecordActivity.EXTRA_AMOUNT, r.amount);
        intent.putExtra(QuickRecordActivity.EXTRA_CATEGORY, r.category);
        intent.putExtra(QuickRecordActivity.EXTRA_NOTE, r.note);
        intent.putExtra(QuickRecordActivity.EXTRA_CREATED_AT, r.createdAt);
        startActivityForResult(intent, REQ_QUICK_RECORD);
    }

    // ─────────────────────────── 统计页 ───────────────────────────

    private void refreshStats() {
        tvStatsYear.setText(String.format(Locale.CHINA, "%d年", statsYear));
        List<Record> records = db.queryByYear(statsYear);
        double[] sums = new double[12];
        double total = 0;
        Calendar c = Calendar.getInstance();
        for (Record r : records) {
            c.setTimeInMillis(r.createdAt);
            sums[c.get(Calendar.MONTH)] += r.amount;
            total += r.amount;
        }
        tvYearTotal.setText(String.format(Locale.CHINA, getString(R.string.month_total_format), total));
        int monthsElapsed = statsYear == c.get(Calendar.YEAR) ? c.get(Calendar.MONTH) + 1 : 12;
        double avg = monthsElapsed > 0 ? total / monthsElapsed : 0;
        tvYearAvg.setText(String.format(Locale.CHINA, "月均支出 ¥%.2f", avg));
        monthBars.setData(sums);

        boolean smsGranted = checkSelfPermission(Manifest.permission.RECEIVE_SMS)
                == PackageManager.PERMISSION_GRANTED;
        layoutSmsNotice.setVisibility(smsGranted ? View.GONE : View.VISIBLE);
    }

    // ─────────────────────────── 我的页 ───────────────────────────

    private void exportCsv() {
        List<Record> all = db.queryAll();
        if (all.isEmpty()) {
            Toast.makeText(this, R.string.no_records, Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder sb = new StringBuilder("\uFEFF金额,分类,备注,时间\r\n");
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
        for (Record r : all) {
            sb.append(r.amount).append(',')
                    .append(csvCell(r.category)).append(',')
                    .append(csvCell(r.note)).append(',')
                    .append(f.format(new Date(r.createdAt))).append("\r\n");
        }
        String name = "记账日报_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date()) + ".csv";
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        try {
            String where;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new IllegalStateException("MediaStore insert failed");
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os == null) throw new IllegalStateException("stream open failed");
                    os.write(bytes);
                }
                where = "下载/" + name;
            } else {
                File file = new File(getExternalFilesDir(null), name);
                try (FileOutputStream os = new FileOutputStream(file)) {
                    os.write(bytes);
                }
                where = file.getAbsolutePath();
            }
            Toast.makeText(this, getString(R.string.export_done, where), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_LONG).show();
        }
    }

    private static String csvCell(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    /** 年月双轮直接选择 */
    private void showMonthPicker() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_month_picker, null);
        NumberPicker yearPicker = view.findViewById(R.id.pickerYear);
        NumberPicker monthPicker = view.findViewById(R.id.pickerMonth);
        yearPicker.setMinValue(2000);
        yearPicker.setMaxValue(2099);
        yearPicker.setValue(currentMonth.get(Calendar.YEAR));
        yearPicker.setWrapSelectorWheel(false);
        monthPicker.setMinValue(1);
        monthPicker.setMaxValue(12);
        monthPicker.setValue(currentMonth.get(Calendar.MONTH) + 1);
        monthPicker.setWrapSelectorWheel(true);

        new AlertDialog.Builder(this)
                .setTitle(R.string.month_picker_title)
                .setView(view)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    currentMonth.set(yearPicker.getValue(), monthPicker.getValue() - 1, 1);
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
