package com.example.charge_to_an_account;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 流水行交互（QQ 式）：
 * - 左滑短距离（阈值≈行宽 20%）露出「删除」按钮，松手吸附；点删除才执行，再点内容收回
 * - 长按 2 秒进入编辑（达成瞬间压感震动），快速点按不再误触编辑
 * - 纵向滚动让给外层 ScrollView；行拖动时 requestDisallowInterceptTouchEvent 独占
 */
public class SwipeDeleteRow extends FrameLayout {

    /** 当前展开删除按钮的行（互斥：滑开新行时自动收回旧行，QQ 同款） */
    private static SwipeDeleteRow openedRow;

    private View content;
    private float downX, downY, tx0;
    private long downAt;
    private boolean dragging, longPressFired, opened;
    private int slop;
    private float deleteBtnW;
    private ObjectAnimator animator;
    private TextView deleteBtn;
    private final Runnable longPressRun = new Runnable() {
        @Override
        public void run() {
            // 幂等：DOWN 可能经 onIntercept/onTouch 双路径各 beginTouch 一次（挂两个回调），
            // 无子控件消费时两遍都会执行——不加守卫会同时弹两个编辑弹窗（真机踩坑）
            if (longPressFired || dragging) return;
            longPressFired = true;
            // 压感反馈：长按达成瞬间震动一下
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            setPressedGray(false);
            if (onRowLongPress != null) onRowLongPress.run();
        }
    };
    private Runnable onRowLongPress;
    private Runnable onDelete;

    public SwipeDeleteRow(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public SwipeDeleteRow(Context context) {
        super(context);
        init(context);
    }

    private void init(Context context) {
        float density = getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        deleteBtnW = 84 * density; // 「删除」按钮宽度
        // QQ 式：红底在按钮区域，内容盖住
        setBackgroundColor(getResources().getColor(R.color.seal_red));

        deleteBtn = new TextView(context);
        deleteBtn.setText(R.string.delete_label);
        deleteBtn.setTextColor(getResources().getColor(R.color.paper));
        deleteBtn.setTextSize(15);
        deleteBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        deleteBtn.setGravity(Gravity.CENTER);
        LayoutParams lp = new LayoutParams((int) deleteBtnW, LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        deleteBtn.setLayoutParams(lp);
        deleteBtn.setOnClickListener(v -> {
            if (onDelete != null) onDelete.run();
        });
        addView(deleteBtn);
    }

    /** 设置可滑动的内容行（纸色背景，盖住下层删除按钮） */
    public void setContent(View view) {
        this.content = view;
        addView(view);
    }

    public void setOnRowLongPress(Runnable r) {
        this.onRowLongPress = r;
    }

    public void setOnDelete(Runnable r) {
        this.onDelete = r;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (openedRow == this) openedRow = null;
        removeCallbacks(longPressRun);
        if (animator != null) animator.cancel();
    }

    private void beginTouch(MotionEvent ev) {
        downX = ev.getX();
        downY = ev.getY();
        downAt = System.currentTimeMillis();
        tx0 = content != null ? content.getTranslationX() : 0;
        dragging = false;
        longPressFired = false;
        stopAnimator();
        setPressedGray(true); // QQ 式按压变灰
        // 长按 1s 进入编辑（达成时 longPressRun 内已有震动）；先移除旧回调防重复挂载
        removeCallbacks(longPressRun);
        if (!opened) postDelayed(longPressRun, 1000);
    }

    private void cancelTouch() {
        removeCallbacks(longPressRun);
        setPressedGray(false);
    }

    /** QQ 式按压反馈：内容上方叠半透明黑遮罩（变暗）。
     *  不能用 content.setAlpha——半透明会透出底下红色删除层，看起来像被滑开了（真机踩坑） */
    private void setPressedGray(boolean pressed) {
        if (content == null) return;
        if (pressed) {
            content.setForeground(new android.graphics.drawable.ColorDrawable(0x40000000));
        } else {
            content.setForeground(null);
        }
    }

    private boolean horizontalDrag(float x, float y) {
        float dx = x - downX;
        float dy = y - downY;
        return Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.2f;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                beginTouch(ev);
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && horizontalDrag(ev.getX(), ev.getY())) {
                    startDrag();
                    return true;
                }
                if (Math.abs(ev.getY() - downY) > slop) cancelTouch();
                return dragging;
        }
        return dragging;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                beginTouch(ev);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) {
                    if (horizontalDrag(ev.getX(), ev.getY())) {
                        startDrag();
                        setPressedGray(false);
                    } else {
                        if (Math.abs(ev.getY() - downY) > slop) {
                            cancelTouch(); // 纵向滚动：恢复亮度
                        }
                        return true;
                    }
                }
                if (content != null && dragging) {
                    float tx = clamp(tx0 + (ev.getX() - downX), -deleteBtnW, 0);
                    content.setTranslationX(tx);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                cancelTouch();
                setPressedGray(false);
                if (!dragging && !longPressFired) {
                    float dx = ev.getX() - downX;
                    long dt = System.currentTimeMillis() - downAt;
                    if (Math.abs(dx) <= slop && dt < 500) {
                        // 展开态点内容=收回；未展开点按无动作（编辑只走长按，防误触）
                        if (opened) animateTo(0);
                    }
                } else if (content != null) {
                    // 拖过删除按钮一半宽度即吸附展开，否则收回
                    boolean open = -content.getTranslationX() > deleteBtnW * 0.5f;
                    animateTo(open ? -deleteBtnW : 0);
                }
                dragging = false;
                return true;
        }
        return true;
    }

    private void startDrag() {
        dragging = true;
        cancelTouch(); // 拖动即取消长按
        requestDisallowInterceptTouchEvent(true);
    }

    private void animateTo(float targetTx) {
        if (content == null) return;
        stopAnimator();
        opened = targetTx < 0;
        if (opened) {
            // 互斥：收回其他展开行（isAttachedToWindow 防列表刷新后的失效引用）
            SwipeDeleteRow prev = openedRow;
            if (prev != null && prev != this && prev.opened && prev.isAttachedToWindow()) {
                prev.animateTo(0);
            }
            openedRow = this;
        } else if (openedRow == this) {
            openedRow = null;
        }
        animator = ObjectAnimator.ofFloat(content, "translationX",
                content.getTranslationX(), targetTx);
        animator.setDuration(200);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                if (!opened) requestDisallowInterceptTouchEvent(false);
            }
        });
        animator.start();
    }

    private void stopAnimator() {
        if (animator != null && animator.isRunning()) animator.cancel();
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }
}
