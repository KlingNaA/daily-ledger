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
 * 流水行交互（QQ/微信式左滑操作栏）：
 * - 左滑露出操作按钮「编辑」「删除」（墨黑 / 印章红），滑过一半宽度即吸附；点按钮才执行，点内容收回
 * - 长按 1 秒也能直接进入编辑（达成瞬间压感震动），快速点按无动作
 * - 纵向滚动让给外层 ScrollView；行拖动时 requestDisallowInterceptTouchEvent 独占
 *
 * v1.6.1：慢速起手的滚动（手指按住后只挪十几 px、还没到系统 slop）以前会被当成"静止长按"，
 * 于是滑列表时偶发弹出编辑窗，并且按压变暗一直不消失。现在：
 * ① 长按的"静止"容差 `stillSlop` 明显小于系统 touch slop —— 手指一动就撤销长按并恢复亮度；
 * ② 只要祖先 ScrollView 真的滚动了，本次手势一律不开编辑。
 *
 * v1.6.2：单按钮改多按钮操作栏（`addAction`），编辑不再只依赖长按。
 */
public class SwipeDeleteRow extends FrameLayout {

    /** 当前展开操作栏的行（互斥：滑开新行时自动收回旧行，QQ 同款） */
    private static SwipeDeleteRow openedRow;

    private View content;
    private float downX, downY, tx0;
    private long downAt;
    private boolean dragging, longPressFired, opened;
    private int slop;
    /** 长按判定用的"手指静止"容差（小于 slop：滚动起手不会被误判为按压） */
    private float stillSlop;
    private boolean grayOn;
    private android.graphics.drawable.ColorDrawable pressOverlay;
    private android.animation.ValueAnimator pressAnim;
    private android.widget.ScrollView scroller;
    private int scrollYAtDown;
    /** 单个操作按钮宽度 */
    private float actionW;
    /** 操作按钮之间的缝隙 */
    private float actionGap;
    /** 操作按钮圆角半径 */
    private float actionRadius;
    /** 操作栏总宽 = 按钮数 × actionW，也是内容行可左移的最大距离 */
    private float actionsW;
    private ObjectAnimator animator;
    private android.widget.LinearLayout actionBar;
    private Runnable onEdit;
    private Runnable onDelete;
    private final Runnable longPressRun = new Runnable() {
        @Override
        public void run() {
            // 幂等：DOWN 可能经 onIntercept/onTouch 双路径各 beginTouch 一次（挂两个回调），
            // 无子控件消费时两遍都会执行——不加守卫会同时弹两个编辑弹窗（真机踩坑）
            if (longPressFired || dragging) return;
            if (scrolledSinceDown()) { // 列表滚过了 = 用户在滚动，不是要编辑
                setPressedGray(false);
                return;
            }
            longPressFired = true;
            // 压感反馈：长按达成瞬间震动一下
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            setPressedGray(false);
            if (onEdit != null) onEdit.run();
        }
    };

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
        // 长按"手指静止"容差：3dp（系统 slop≈8dp 是"算不算滚动"的门槛，长按要求在门槛以下也几乎不能动）。
        // 之前用 slop 判定 → 慢速起手的滚动（十几 px、还没到 slop）被当成静止长按，滑列表时偶发弹编辑窗（v1.6.1 修）
        stillSlop = Math.max(3 * density, slop * 0.35f);
        actionW = 68 * density;  // 单个操作按钮宽度
        actionGap = 6 * density; // 按钮之间的间隙（露出纸色，圆角才看得出来）
        actionRadius = 12 * density;
        // 底色 = 纸色：操作栏是浮在纸面上的圆角块，滑开时缝隙与列表底色一致
        setBackgroundColor(androidx.core.content.ContextCompat.getColor(context, R.color.paper));

        // QQ/微信式操作栏：靠右排开，内容行盖在上面，左滑露出
        actionBar = new android.widget.LinearLayout(context);
        actionBar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        actionBar.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LayoutParams barLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT);
        barLp.gravity = Gravity.END;
        actionBar.setLayoutParams(barLp);

        TextView editBtn = addAction(context, R.string.edit_label, R.color.ink, () -> {
            if (onEdit != null) onEdit.run();
        });
        editBtn.setContentDescription(getResources().getString(R.string.edit_label));
        TextView deleteBtn = addAction(context, R.string.delete_label, R.color.seal_red, () -> {
            if (onDelete != null) onDelete.run();
        });
        deleteBtn.setContentDescription(getResources().getString(R.string.delete_label));
        addView(actionBar);
    }

    /** 往操作栏里加一个圆角按钮（色块 + 纸色文字），点击后自动收回操作栏 */
    private TextView addAction(Context context, int textRes, int bgColorRes, Runnable action) {
        TextView tv = new TextView(context);
        tv.setText(textRes);
        tv.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.paper));
        tv.setTextSize(15);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(androidx.core.content.ContextCompat.getColor(context, bgColorRes));
        bg.setCornerRadius(actionRadius);
        tv.setBackground(bg);

        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                (int) actionW, android.widget.LinearLayout.LayoutParams.MATCH_PARENT);
        lp.setMargins((int) actionGap, (int) (actionGap * 0.9f), 0, (int) (actionGap * 0.9f));
        tv.setLayoutParams(lp);
        tv.setOnClickListener(v -> {
            if (opened) animateTo(0); // 像微信一样：动作执行后操作栏收回
            action.run();
        });
        actionBar.addView(tv);
        actionsW = (actionW + actionGap) * actionBar.getChildCount();
        return tv;
    }

    /** 设置可滑动的内容行（纸色背景，盖住下层操作栏） */
    public void setContent(View view) {
        this.content = view;
        this.grayOn = false;
        addView(view);
    }

    /** 编辑动作：左滑点「编辑」或长按该行都会走这里 */
    public void setOnEdit(Runnable r) {
        this.onEdit = r;
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
        if (pressAnim != null) pressAnim.cancel();
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
        if (!opened) {
            resolveScroller();
            scrollYAtDown = scroller != null ? scroller.getScrollY() : 0;
            postDelayed(longPressRun, 1000);
        }
    }

    /** 手指移动是否已超出"静止"容差（任一方向） */
    private boolean movedTooMuch(MotionEvent ev) {
        return Math.abs(ev.getX() - downX) > stillSlop || Math.abs(ev.getY() - downY) > stillSlop;
    }

    /** 祖先滚动容器本次手势是否真的滚动了 */
    private boolean scrolledSinceDown() {
        return scroller != null && scroller.getScrollY() != scrollYAtDown;
    }

    private void resolveScroller() {
        if (scroller != null && scroller.isAttachedToWindow()) return;
        android.view.ViewParent p = getParent();
        while (p != null && !(p instanceof android.widget.ScrollView)) p = p.getParent();
        scroller = (android.widget.ScrollView) p;
    }

    private void cancelTouch() {
        removeCallbacks(longPressRun);
        setPressedGray(false); // 手势已不是"按压"：立刻恢复亮度，别让用户以为还按着
    }

    /** QQ 式按压反馈：内容上方叠半透明黑遮罩（变暗），90ms 渐入渐出而不是硬切。
     *  不能用 content.setAlpha——半透明会透出底下操作栏，看起来像被滑开了（真机踩坑） */
    private void setPressedGray(boolean pressed) {
        if (content == null || grayOn == pressed) return;
        grayOn = pressed;
        if (pressOverlay == null) {
            pressOverlay = new android.graphics.drawable.ColorDrawable(0x40000000);
            pressOverlay.setAlpha(0);
        }
        if (content.getForeground() != pressOverlay) content.setForeground(pressOverlay);
        if (pressAnim != null) pressAnim.cancel();
        pressAnim = android.animation.ValueAnimator.ofInt(pressOverlay.getAlpha(),
                pressed ? 0x40 : 0);
        pressAnim.setDuration(pressed ? 90 : 150);
        pressAnim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        pressAnim.addUpdateListener(a -> pressOverlay.setAlpha((int) a.getAnimatedValue()));
        pressAnim.start();
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
                // 手指一动（超出静止容差）或列表已经滚起来 → 撤销长按并恢复亮度
                if (movedTooMuch(ev) || scrolledSinceDown()) cancelTouch();
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
                    } else {
                        if (movedTooMuch(ev) || scrolledSinceDown()) cancelTouch();
                        return true;
                    }
                }
                if (content != null && dragging) {
                    float tx = clamp(tx0 + (ev.getX() - downX), -actionsW, 0);
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
                    // 拖过操作栏一半宽度即吸附展开，否则收回
                    boolean open = -content.getTranslationX() > actionsW * 0.5f;
                    animateTo(open ? -actionsW : 0);
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
        animator.setDuration(opened ? 240 : 180);
        // 减速曲线收尾，像弹簧吸住一样（不用回弹，避免和"报纸"的克制感冲突）
        animator.setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f));
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
