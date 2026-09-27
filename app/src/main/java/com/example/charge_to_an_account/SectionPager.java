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
import android.view.animation.PathInterpolator;
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
            p.setScaleX(1f); // 清掉过渡里留下的缩放
            p.setScaleY(1f);
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

    /** 过渡中的每帧布局：dir=+1 新版从右进，-1 从左进。
     *  旧版退让（位移 + 轻微缩小 + 淡出），新版滑入并回正缩放，做出层次感 */
    private void applyTransition(float w) {
        View out = page(currentPage);
        View in = page(pendingTarget);
        int dir = pendingTarget > currentPage ? 1 : -1;
        float eased = 1f - (1f - t) * (1f - t); // 位移用减速曲线，收尾更"润"
        in.setTranslationX(dir * w * 0.35f * (1f - eased));
        in.setAlpha(0.2f + 0.8f * t);
        in.setScaleX(0.985f + 0.015f * eased);
        in.setScaleY(0.985f + 0.015f * eased);
        out.setTranslationX(-dir * w * 0.22f * eased);
        out.setAlpha(1f - 0.55f * t);
        out.setScaleX(1f - 0.015f * eased);
        out.setScaleY(1f - 0.015f * eased);
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
        animator.setDuration(260);
        animator.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
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

    /**
     * 手势开始时把进行中的翻版动画就地收尾。
     * 原来直接 cancel()：onAnimationEnd 因 cancelled 直接返回，既不 commit 也不复位，
     * 页面会永远停在半透明中间态、currentPage 与视觉不符（v1.6.3 修）。
     */
    private void settleAnimator() {
        if (animator == null || !animator.isRunning()) return;
        float cur = t;
        animator.cancel();
        if (pendingTarget >= 0 && cur > 0.5f) {
            commit(); // 过半就顺势翻过去
        } else {
            int wasTarget = pendingTarget;
            pendingTarget = -1;
            t = 0;
            applyRestState();
            // 回退时要收回动画开始时提前移动的导航高亮
            if (wasTarget >= 0 && onPageSelected != null) {
                onPageSelected.onPageSelected(currentPage);
            }
        }
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
            if (t > 0.5f && pendingTarget >= 0) {
                animateT(t, 1f); // 先完成进行中的过渡，再接受新目标
                return;
            }
            settleAnimator(); // 过半前：就地复位（连同导航高亮）
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
        settleAnimator(); // 别直接 cancel：会把页面留在半透明中间态（v1.6.3 修）
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
