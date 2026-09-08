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
        requestLayout();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (entries.isEmpty()) return;
        int w = getWidth();
        int h = getHeight();
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
            amountW = Math.max(amountW, amountPaint.measureText(String.format("¥%.2f", e.value)));
        }
        float barLeft = labelW + textSize * 0.8f;
        float barRight = w - amountW - textSize * 0.8f;
        double max = entries.get(0).value;

        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            float cy = rowH * i + rowH / 2f;
            float textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f;
            canvas.drawText(e.label, 0, textY, textPaint);

            float barW = max > 0 ? (float) (e.value / max * (barRight - barLeft)) : 0;
            float barH = Math.min(rowH * 0.5f, 22f * density);
            float right = barLeft + Math.max(barW, 2f);
            float top = cy - barH / 2;
            float bottom = cy + barH / 2;

            if (i == 0) {
                // 印章红实心：本期最大支出
                Paint solid = new Paint(barPaint);
                solid.setStyle(Paint.Style.FILL);
                solid.setColor(SEAL_RED);
                canvas.drawRect(barLeft, top, right, bottom, solid);
            } else {
                // 墨框 + 斜线阴影排，老式印刷图表质感
                canvas.drawRect(barLeft, top, right, bottom, barPaint);
                int save = canvas.save();
                canvas.clipRect(barLeft + 1, top + 1, right - 1, bottom - 1);
                float gap = 5f * density;
                for (float x = barLeft - barH; x < right; x += gap) {
                    canvas.drawLine(x, bottom, x + barH, top, hatchPaint);
                }
                canvas.restoreToCount(save);
            }

            canvas.drawText(String.format("¥%.2f", e.value), barRight + textSize * 0.8f,
                    textY, amountPaint);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int rows = Math.max(entries.size(), 1);
        float rowMin = 34f * getResources().getDisplayMetrics().density;
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec),
                resolveSize((int) (rows * rowMin), heightMeasureSpec));
    }
}
