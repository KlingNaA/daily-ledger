package com.example.charge_to_an_account;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

/**
 * 多版面横向切换容器（记账/统计/我的）：
 * - 底部导航点按或手势滑动均可切换，统一使用「旧版退让淡出、新版滑入淡现」的报纸翻版动画
 * - 手势只在相邻版面间拖动，纵向滚动与子视图独占手势（滑块删除行）不受影响
 */
public class SectionPager extends FrameLayout {

    private int currentPage = 0;
    private int pendingTarget = -1; // 过渡中正在进入的版面
    private float t;                // 过渡进度 0..1
    private float downX, downY;
    private int dragSign;           // 手势方向：+1 左滑(向右翻页)，-1 右滑
    private boolean dragging;
    private ValueAnimator animator;
    private VelocityTracker tracker;
    private final int touchSlop;
    private OnPageSelected onPageSelected;

    /** 版面落定回调（滑动松手翻页或导航点按），用于同步导航栏高亮 */
    public interface OnPageSelected {
        void onPageSelected(int page);
    }

    public void setOnPageSelected(OnPageSelected l) {
        onPageSelected = l;
    }

    public SectionPager(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        applyRestState();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        applyRestState();
    }

    private View page(int i) {
        return getChildAt(i);
    }

    /** 静置：当前版面完全可见，其余隐藏并推到屏外 */
    private void applyRestState() {
        float w = getWidth();
        for (int i = 0; i < getChildCount(); i++) {
            View p = page(i);
            if (i == currentPage) {
                p.setAlpha(1f);
                p.setTranslationX(0);
            } else {
                p.setAlpha(0f);
                p.setTranslationX(i > currentPage ? w : -w);
            }
        }
        pendingTarget = -1;
        t = 0;
    }

    /** 过渡中的每帧布局：dir=+1 新版从右进，-1 从左进 */
    private void applyTransition(float w) {
        View out = page(currentPage);
        View in = page(pendingTarget);
        int dir = pendingTarget > currentPage ? 1 : -1;
        in.setTranslationX(dir * w * (1 - t));
        in.setAlpha(0.4f + 0.6f * t);
        out.setTranslationX(-dir * w * 0.35f * t);
        out.setAlpha(1f - 0.6f * t);
    }

    private void animateT(float from, float to) {
        stopAnimator();
        // 前进过渡（进度增加=正在翻向新页）开始即回调：导航指示条与翻页动画同步滑动；
        // 回弹（to<from）不回调
        if (to > from && pendingTarget >= 0 && pendingTarget != currentPage
                && onPageSelected != null) {
            onPageSelected.onPageSelected(pendingTarget);
        }
        animator = ValueAnimator.ofFloat(from, to);
        animator.setDuration(240);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            t = (float) a.getAnimatedValue();
            applyTransition(Math.max(getWidth(), 1));
        });
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator a) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator a) {
                if (cancelled) return;
                if (t >= 0.999f) {
                    commit();
                } else if (t <= 0.001f) {
                    applyRestState();
                }
            }
        });
        animator.start();
    }

    private void commit() {
        currentPage = pendingTarget;
        pendingTarget = -1;
        t = 0;
        applyRestState();
    }

    /** 导航点按切换 */
    public void setPage(int target) {
        if (animator != null && animator.isRunning()) {
            animator.cancel(); // onAnimationEnd(c cancelled) 不会收尾，这里手动处理半途状态
            if (t > 0.5f && pendingTarget >= 0) {
                animateT(t, 1f); // 先完成进行中的过渡
                return;
            }
            if (pendingTarget >= 0) {
                pendingTarget = -1;
                t = 0;
            }
            applyRestState();
        }
        if (target == currentPage || target < 0 || target >= getChildCount()) return;
        pendingTarget = target;
        animateT(0f, 1f);
    }

    private void beginDrag(float x, float y) {
        downX = x;
        downY = y;
        dragSign = 0;
        dragging = false;
        stopAnimator();
        if (tracker == null) tracker = VelocityTracker.obtain();
        else tracker.clear();
        tracker.addMovement(MotionEvent.obtain(0, System.currentTimeMillis(),
                MotionEvent.ACTION_DOWN, x, y, 0));
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                beginDrag(ev.getX(), ev.getY());
                return false;
            case MotionEvent.ACTION_MOVE:
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                if (!dragging && Math.abs(dx) > touchSlop * 2 && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    int sign = dx < 0 ? 1 : -1;
                    int target = currentPage + sign;
                    if (target < 0 || target >= getChildCount()) return false;
                    dragging = true;
                    dragSign = sign;
                    pendingTarget = target;
                    t = 0;
                    return true;
                }
                return dragging;
        }
        return dragging;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (tracker != null) tracker.addMovement(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                beginDrag(ev.getX(), ev.getY());
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                if (!dragging) {
                    if (Math.abs(dx) > touchSlop * 2 && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        int sign = dx < 0 ? 1 : -1;
                        int target = currentPage + sign;
                        if (target >= 0 && target < getChildCount()) {
                            dragging = true;
                            dragSign = sign;
                            pendingTarget = target;
                            t = 0;
                        }
                    }
                    return true;
                }
                if (dragging) {
                    t = Math.min(Math.abs(dx) / Math.max(getWidth(), 1), 1f);
                    applyTransition(Math.max(getWidth(), 1));
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging && pendingTarget >= 0) {
                    if (tracker != null) tracker.computeCurrentVelocity(1000);
                    float vx = tracker != null ? tracker.getXVelocity() : 0;
                    // dragSign=+1 表示左滑（vx<0）；快速轻扫且方向一致即可翻页
                    boolean fling = Math.abs(vx) > 2200
                            && Math.signum(vx) == (dragSign == 1 ? -1f : 1f);
                    if (t > 0.5f || (fling && t > 0.08f)) {
                        animateT(t, 1f);
                    } else {
                        animateT(t, 0f);
                    }
                }
                if (tracker != null) {
                    tracker.recycle();
                    tracker = null;
                }
                dragging = false;
                return true;
        }
        return true;
    }

    private void stopAnimator() {
        if (animator != null && animator.isRunning()) animator.cancel();
    }
}
