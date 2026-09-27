package com.example.charge_to_an_account;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.Locale;

/**
 * 年度月度支出垂直柱状图（复古印刷风）：
 * 12 根柱，最高月为印章红实心，其余墨框 + 斜线阴影排。
 */
public class MonthBarsView extends View {

    private static final int INK = 0xFF221D15;
    private static final int INK_SOFT = 0xFF5C5346;
    private static final int SEAL_RED = 0xFFA63A2B;

    private final double[] monthly = new double[12];
    private double max = 0;

    private final Paint solidPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hatchPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint amountPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    /** 柱子生长进度 0→1 与数据指纹（数据没变就不重播，避免页面恢复时乱抖） */
    private float grow = 1f;
    private String dataKey = "";
    private android.animation.ValueAnimator growAnim;

    public MonthBarsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        solidPaint.setColor(SEAL_RED);
        hatchPaint.setColor(INK);
        hatchPaint.setStrokeWidth(1f);
        strokePaint.setColor(INK);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(Math.max(1.5f, getResources().getDisplayMetrics().density));
        labelPaint.setColor(INK_SOFT);
        amountPaint.setColor(SEAL_RED);
        amountPaint.setFakeBoldText(true);
    }

    /** 传入 12 个月的支出总额（下标 0 = 1 月）。 */
    public void setData(double[] sums) {
        max = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            monthly[i] = sums[i];
            if (sums[i] > max) max = sums[i];
            sb.append(Math.round(sums[i] * 100)).append(',');
        }
        String key = sb.toString();
        boolean changed = !key.equals(dataKey);
        dataKey = key;
        if (changed) startGrow();
        invalidate();
    }

    /** 柱子从底部生长（数据变化时播放） */
    private void startGrow() {
        if (growAnim != null) growAnim.cancel();
        if (max <= 0) {
            grow = 1f;
            return;
        }
        grow = 0f;
        // post：等这一帧布局完成（首次加载时视图还没 attach）再开播
        post(this::runGrow);
    }

    private void runGrow() {
        grow = 0f;
        growAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        growAnim.setDuration(560);
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
        float density = getResources().getDisplayMetrics().density;
        int w = getWidth();
        int h = getHeight();
        float labelH = 14f * density;
        float chartTop = (max > 0 ? 16f * density : 4f * density);
        float chartBottom = h - labelH - 2f * density;
        float chartH = chartBottom - chartTop;
        if (chartH <= 0) return;

        float slot = w / 12f;
        float barW = Math.min(slot * 0.52f, 26f * density);
        int maxIdx = -1;
        for (int i = 0; i < 12; i++) {
            // 并列最高取最早月份，只标一根印章红
            if (max > 0 && monthly[i] == max && maxIdx == -1) maxIdx = i;
        }

        for (int i = 0; i < 12; i++) {
            float cx = slot * i + slot / 2f;
            float left = cx - barW / 2;
            float right = cx + barW / 2;
            float barH = max > 0 ? (float) (monthly[i] / max * chartH) * grow : 0;
            float top = chartBottom - Math.max(barH, 1.5f * density);
            int alpha = (int) (255 * Math.min(1f, grow * 1.2f)); // 文字稍早到位

            if (monthly[i] > 0 && i == maxIdx) {
                solidPaint.setAlpha(alpha);
                // 柱顶圆角（底部保持直角，立在基线上）
                float r = Math.min(barW / 2f, 5f * density);
                rect.set(left, top, right, chartBottom);
                canvas.drawRoundRect(rect, r, r, solidPaint);
                canvas.drawRect(left, chartBottom - r, right, chartBottom, solidPaint);
                // 红柱顶部标金额
                String amount = formatAmount(monthly[i]);
                amountPaint.setTextSize(11f * density);
                amountPaint.setAlpha(alpha);
                float tw = amountPaint.measureText(amount);
                canvas.drawText(amount, cx - tw / 2, top - 4f * density, amountPaint);
            } else if (monthly[i] > 0) {
                strokePaint.setAlpha(alpha);
                hatchPaint.setAlpha(alpha);
                float r = Math.min(barW / 2f, 5f * density);
                rect.set(left, top, right, chartBottom);
                canvas.drawRoundRect(rect, r, r, strokePaint);
                int save = canvas.save();
                canvas.clipRect(left + 1, top + 1, right - 1, chartBottom - 1);
                float gap = 4.5f * density;
                for (float x = left - chartH; x < right; x += gap) {
                    canvas.drawLine(x, chartBottom, x + chartH, top, hatchPaint);
                }
                canvas.restoreToCount(save);
            }

            labelPaint.setTextSize(11f * density);
            labelPaint.setAlpha((int) (255 * Math.min(1f, grow + 0.25f)));
            String label = String.format(Locale.CHINA, "%d", i + 1);
            float lw = labelPaint.measureText(label);
            canvas.drawText(label, cx - lw / 2, h - 3f * density, labelPaint);
        }
    }

    private static String formatAmount(double v) {
        if (v >= 10000) return String.format(Locale.CHINA, "%.1f万", v / 10000);
        return String.format(Locale.CHINA, "%.0f", v);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int h = (int) (150 * getResources().getDisplayMetrics().density);
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec),
                resolveSize(h, heightMeasureSpec));
    }
}
