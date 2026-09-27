package com.example.charge_to_an_account;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 极简自绘水平条形图（复古报纸风）：
 * 最大项为印章红实心，其余为墨色描边 + 斜线阴影排，无第三方依赖。
 */
public class BarChartView extends View {

    private static final int INK = 0xFF221D15;
    private static final int INK_SOFT = 0xFF5C5346;
    private static final int SEAL_RED = 0xFFA63A2B;

    public static class Entry {
        public final String label;
        public final double value;

        public Entry(String label, double value) {
            this.label = label;
            this.value = value;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hatchPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint amountPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 生长动画进度 0→1 与数据指纹（只有数据真的变了才重播，避免每次 onResume 都抖一下） */
    private float grow = 1f;
    private String dataKey = "";
    private android.animation.ValueAnimator growAnim;
    private final android.graphics.RectF rect = new android.graphics.RectF(); // 复用，避免每帧分配

    public BarChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        textPaint.setColor(INK);
        textPaint.setFakeBoldText(true);
        amountPaint.setColor(INK_SOFT);
        hatchPaint.setColor(INK);
        hatchPaint.setStrokeWidth(1f);
        barPaint.setStyle(Paint.Style.STROKE);
        barPaint.setColor(INK);
        barPaint.setStrokeWidth(Math.max(1.5f, getResources().getDisplayMetrics().density));
    }

    public void setData(Map<String, Double> categorySum) {
        entries.clear();
        for (Map.Entry<String, Double> e : categorySum.entrySet()) {
            if (e.getValue() > 0) entries.add(new Entry(e.getKey(), e.getValue()));
        }
        entries.sort((a, b) -> Double.compare(b.value, a.value));
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) sb.append(e.label).append('=').append(Math.round(e.value * 100)).append(';');
        String key = sb.toString();
        boolean changed = !key.equals(dataKey);
        dataKey = key;
        if (changed) startGrow();
        requestLayout();
        invalidate();
    }

    /** 条形从 0 长到目标长度（数据变化时播放） */
    private void startGrow() {
        if (growAnim != null) growAnim.cancel();
        if (entries.isEmpty()) {
            grow = 1f;
            return;
        }
        grow = 0f;
        // post：等这一帧布局完成（首次加载时视图还没 attach）再开播，否则动画在"上屏前"就跑完了
        post(this::runGrow);
    }

    private void runGrow() {
        grow = 0f;
        growAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        growAnim.setDuration(520);
        growAnim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f));
        growAnim.addUpdateListener(a -> {
            grow = (float) a.getAnimatedValue();
            invalidate();
        });
        growAnim.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (growAnim != null) growAnim.cancel();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (entries.isEmpty()) return;
        int w = getWidth();
        int h = getHeight();
        // 自定义 View 的 onDraw 不会自动避让 padding（布局里设了 paddingHorizontal=16dp），
        // 之前用 getWidth() 从 x=0 画，分类名和金额都顶到屏幕边缘（v1.6 修）
        float left = getPaddingLeft();
        float right0 = w - getPaddingRight();
        float density = getResources().getDisplayMetrics().density;
        float rowH = h / (float) entries.size();
        float textSize = Math.min(rowH * 0.34f, 13f * density);
        textPaint.setTextSize(textSize);
        amountPaint.setTextSize(textSize);
        float labelW = 0;
        for (Entry e : entries) {
            labelW = Math.max(labelW, textPaint.measureText(e.label));
        }
        float amountW = 0;
        for (Entry e : entries) {
            amountW = Math.max(amountW, amountPaint.measureText(amountText(e.value)));
        }
        float barLeft = left + labelW + textSize * 0.8f;
        float barRight = right0 - amountW - textSize * 0.8f;
        if (barRight < barLeft) barRight = barLeft; // 分类名过长时不画成反向矩形
        double max = entries.get(0).value;
        int alpha = (int) (255 * Math.min(1f, grow * 1.15f)); // 文字稍早于条形到位
        textPaint.setAlpha(alpha);
        amountPaint.setAlpha(alpha);

        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            float cy = rowH * i + rowH / 2f;
            float textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f;
            canvas.drawText(e.label, left, textY, textPaint);

            float barW = max > 0 ? (float) (e.value / max * (barRight - barLeft)) * grow : 0;
            float barH = Math.min(rowH * 0.5f, 22f * density);
            float right = barLeft + Math.max(barW, 2f);
            float top = cy - barH / 2;
            float bottom = cy + barH / 2;

            rect.set(barLeft, top, right, bottom);
            if (i == 0) {
                // 印章红实心：本期最大支出（圆头，和整体圆角风格一致）
                Paint solid = new Paint(barPaint);
                solid.setStyle(Paint.Style.FILL);
                solid.setColor(SEAL_RED);
                canvas.drawRoundRect(rect, barH / 2f, barH / 2f, solid);
            } else {
                // 墨框 + 斜线阴影排，老式印刷图表质感
                canvas.drawRoundRect(rect, barH / 2f, barH / 2f, barPaint);
                int save = canvas.save();
                canvas.clipRect(barLeft + 1, top + 1, right - 1, bottom - 1);
                float gap = 5f * density;
                for (float x = barLeft - barH; x < right; x += gap) {
                    canvas.drawLine(x, bottom, x + barH, top, hatchPaint);
                }
                canvas.restoreToCount(save);
            }

            canvas.drawText(amountText(e.value), barRight + textSize * 0.8f, textY, amountPaint);
        }
    }

    /** 金额文案（统一 Locale，避免逗号小数点的地区渲染成 ¥25,80） */
    private static String amountText(double v) {
        return String.format(java.util.Locale.CHINA, "¥%.2f", v);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int rows = Math.max(entries.size(), 1);
        float rowMin = 34f * getResources().getDisplayMetrics().density;
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec),
                resolveSize((int) (rows * rowMin), heightMeasureSpec));
    }
}
