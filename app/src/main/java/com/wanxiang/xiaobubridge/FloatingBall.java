package com.wanxiang.xiaobubridge;

import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import de.robv.android.xposed.XposedBridge;

/**
 * FloatingBall — v3.0 应用内悬浮球。
 *
 * <p>参考 APK（Qwen AppHook）的 {@code FloatingBall.kt} 实现：
 * <ul>
 *   <li>悬浮球本身是一个 FrameLayout，背景为圆角drawable；</li>
 *   <li>内部包含一个 gear icon TextView 和一个状态指示点 View；</li>
 *   <li>附加到 Activity 的 decor view 根FrameLayout 中，
 *       通过修改 LayoutParams.leftMargin/topMargin 实现拖动；</li>
 *   <li>拖动时边界限制在父容器范围内；</li>
 *   <li>点击松开时播放弹簧回弹动画 + scale 动画；</li>
 *   <li>通过 {@code setOnClickListener} 回调打开控制面板。</li>
 * </ul>
 *
 * <p>无需 SYSTEM_ALERT_WINDOW 权限，悬浮球仅在当前 Activity 窗口内显示。
 */
public class FloatingBall {

    private static final String TAG = "[XiaoBuBridge]";
    private static final String BALL_TAG = "xiaobu_floating_ball";
    private static final int SLOP_PX = 20;      // 判定为拖动的最小位移(px) —— 增加阈值避免误判点击为拖动
    private static final long REFRESH_INTERVAL_MS = 3000L; // 状态刷新间隔

    @NonNull
    private final View ball;         // 悬浮球根视图
    private final View dot;          // 状态指示点
    private final int sizePx;        // 悬浮球直径(px)

    // 拖动记录
    private int downX, downY;
    private int baseLeft, baseTop;
    private boolean moved;
    private boolean animating;

    // 点击监听器
    private Runnable onClickListener;

    // 主线程 Handler（用于状态刷新）
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // 用于移除状态刷新回调
    private final Runnable statusRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            updateDotStatus();
            mainHandler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    /**
     * 附加悬浮球到 Activity 的 decor view。
     *
     * @param decorView Activity 的 decor view
     * @param activity  Activity 实例
     * @return FloatingBall 实例，如果附加失败返回 null
     */
    static FloatingBall attach(@NonNull View decorView, @NonNull Activity activity) {
        // decorView 必须是 FrameLayout（Activity 窗口根容器通常是 FrameLayout）
        if (!(decorView instanceof FrameLayout)) return null;
        FrameLayout root = (FrameLayout) decorView;

        // 避免重复添加
        if (root.findViewWithTag(BALL_TAG) != null) return null;

        Context ctx = root.getContext();
        Resources res = ctx.getResources();
        float density = res.getDisplayMetrics().density;

        int size = Math.round(48 * density); // 48dp —— 与参照物一致

        // 创建悬浮球容器
        FrameLayout ballContainer = new FrameLayout(ctx);
        ballContainer.setTag(BALL_TAG);

        // 背景：近黑圆面 + 主色描边，取值照搬参照物的 FloatingBall 规格
        // （#EE1F2937 填充 / #3B82F6 描边，与 UIKit.ACCENT 同色）
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xEE1F2937);
        int strokePx = Math.round(2 * density);
        bg.setStroke(strokePx, UIKit.ACCENT);
        ballContainer.setBackground(bg);

        // 悬浮球抬升到面板之上
        ballContainer.setElevation(12 * density);
        ballContainer.setClipToPadding(false);

        // gear icon TextView
        TextView gearIcon = new TextView(ctx);
        gearIcon.setText("\u2699"); // \u2699 = 齿轮
        gearIcon.setTextSize(20);
        gearIcon.setTextColor(0xFF93C5FD); // 参照物的浅蓝前景
        gearIcon.setGravity(Gravity.CENTER);
        gearIcon.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        ballContainer.addView(gearIcon);

        // 状态指示点
        View dotView = new View(ctx);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(0xFF6B7280); // 灰=空闲
        dotView.setBackground(dotBg);
        FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(
                Math.round(8 * density), Math.round(8 * density));
        dotLp.gravity = Gravity.END | Gravity.TOP;
        dotLp.setMarginEnd(Math.round(3 * density));
        dotLp.topMargin = Math.round(3 * density);
        dotView.setLayoutParams(dotLp);
        ballContainer.addView(dotView);

        // 悬浮球布局参数 —— 右上角
        int marginTop = Math.round(80 * density);
        int marginEnd = Math.round(16 * density);
        FrameLayout.LayoutParams ballLp = new FrameLayout.LayoutParams(size, size);
        ballLp.gravity = Gravity.END | Gravity.TOP;
        ballLp.topMargin = marginTop;
        ballLp.setMarginEnd(marginEnd);

        FloatingBall fb = new FloatingBall(ballContainer, dotView, size);

        // 安装触摸处理
        fb.installTouch();

        // 确保悬浮球可以接收触摸事件
        ballContainer.setClickable(true);
        ballContainer.setFocusable(true);
        ballContainer.setFocusableInTouchMode(true);

        // 添加到 decor view
        root.addView(ballContainer, ballLp);
        XposedBridge.log(TAG + " FloatingBall: attached to " + root.getClass().getSimpleName()
                + " (width=" + root.getWidth() + ", height=" + root.getHeight() + ")");

        // 启动状态刷新
        fb.startStatusRefresh();

        return fb;
    }

    private FloatingBall(@NonNull View ball, @NonNull View dot, int sizePx) {
        this.ball = ball;
        this.dot = dot;
        this.sizePx = sizePx;
    }

    /**
     * 设置点击监听器。
     *
     * @param listener 点击回调
     */
    public void setOnClickListener(Runnable listener) {
        this.onClickListener = listener;
    }

    /**
     * 安装触摸处理：拖动 + 点击检测 + 动画。
     */
    private void installTouch() {
        ball.setOnTouchListener((v, event) -> handleTouchEvent(v, event));
    }

    private boolean handleTouchEvent(View v, MotionEvent event) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (!(lp instanceof FrameLayout.LayoutParams)) return false;
        FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) lp;

        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                XposedBridge.log(TAG + " FloatingBall: ACTION_DOWN received");
                downX = (int) event.getRawX();
                downY = (int) event.getRawY();
                baseLeft = flp.leftMargin;
                baseTop = flp.topMargin;
                moved = false;

                // 防止父容器拦截触摸事件，确保悬浮球能接收到 ACTION_MOVE 和 ACTION_UP
                ViewGroup parentView = (ViewGroup) v.getParent();
                if (parentView != null) {
                    parentView.requestDisallowInterceptTouchEvent(true);
                }

                // 按下缩放 —— 使用 ValueAnimator 而非 ViewPropertyAnimator，
                // 避免与回弹动画时期的 ValueAnimator 冲突
                ValueAnimator downAnim = ValueAnimator.ofFloat(1.0f, 1.08f);
                downAnim.setDuration(80);
                downAnim.addUpdateListener(animation -> {
                    float value = (Float) animation.getAnimatedValue();
                    v.setScaleX(value);
                    v.setScaleY(value);
                });
                downAnim.start();
                return true;

            case MotionEvent.ACTION_MOVE:
                int deltaX = (int) (event.getRawX() - downX);
                int deltaY = (int) (event.getRawY() - downY);

                if (!moved) {
                    if (Math.abs(deltaX) > SLOP_PX || Math.abs(deltaY) > SLOP_PX) {
                        moved = true;
                    }
                }

                if (moved) {
                    View parent = (View) v.getParent();
                    int parentWidth = parent.getWidth();
                    int parentHeight = parent.getHeight();

                    int maxLeft = parentWidth - sizePx;
                    int maxTop = parentHeight - sizePx;

                    int newLeft = baseLeft + deltaX;
                    if (maxLeft > 0) {
                        newLeft = Math.max(0, Math.min(maxLeft, newLeft));
                    }
                    flp.leftMargin = newLeft;

                    int newTop = baseTop + deltaY;
                    if (maxTop > 0) {
                        newTop = Math.max(0, Math.min(maxTop, newTop));
                    }
                    flp.topMargin = newTop;

                    v.setLayoutParams(flp);
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                // 恢复父容器的拦截能力
                ViewGroup p = (ViewGroup) v.getParent();
                if (p != null) {
                    p.requestDisallowInterceptTouchEvent(false);
                }

                if (!moved && onClickListener != null) {
                    XposedBridge.log(TAG + " FloatingBall: click detected, calling listener");
                    // 在动画之前调用点击监听器，确保面板能及时显示
                    onClickListener.run();
                }

                // 回弹动画
                if (!moved) {
                    animating = true;

                    float[] scales = {1.0f, 0.92f, 1.05f, 1.0f};
                    ValueAnimator animator = ValueAnimator.ofFloat(scales);
                    animator.setDuration(350);
                    animator.setInterpolator(new OvershootInterpolator(1.5f));
                    animator.addUpdateListener(animation -> {
                        float value = (Float) animation.getAnimatedValue();
                        v.setScaleX(value);
                        v.setScaleY(value);
                    });
                    animator.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(android.animation.Animator animation) {
                            XposedBridge.log(TAG + " FloatingBall: bounce animation ended");
                            animating = false;
                        }

                        @Override
                        public void onAnimationCancel(android.animation.Animator animation) {
                            XposedBridge.log(TAG + " FloatingBall: bounce animation canceled");
                            animating = false;
                        }
                    });
                    animator.start();
                } else {
                    // 拖动结束 —— 回弹到正常大小
                    ValueAnimator returnAnim = ValueAnimator.ofFloat(v.getScaleX(), 1.0f);
                    returnAnim.setDuration(100);
                    returnAnim.addUpdateListener(animation -> {
                        float value = (Float) animation.getAnimatedValue();
                        v.setScaleX(value);
                        v.setScaleY(value);
                    });
                    returnAnim.start();
                }
                return true;
        }
        return false;
    }

    /**
     * 更新状态指示点颜色，反映当前 AI 会话活跃状态。
     */
    private void updateDotStatus() {
        try {
            // 检查是否有活跃的会话
            ConversationSession session = ConversationSession.getMostRecent(0);
            boolean active = session != null && !session.isCompleted();
            int color = active ? UIKit.GREEN : 0xFF6B7280; // 绿=活跃，灰=空闲
            if (dot != null && dot.getBackground() instanceof GradientDrawable) {
                ((GradientDrawable) dot.getBackground()).setColor(color);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " updateDotStatus error: " + t);
        }
    }

    /**
     * 启动状态刷新轮询 ——定期更新悬浮球的状态指示点颜色。
     */
    private void startStatusRefresh() {
        ball.post(() -> {
            updateDotStatus();
            mainHandler.postDelayed(statusRefreshRunnable, REFRESH_INTERVAL_MS);
        });
    }

    /**
     * 停止状态刷新轮询 ——当悬浮球被移除时调用。
     */
    public void stopStatusRefresh() {
        mainHandler.removeCallbacks(statusRefreshRunnable);
    }
}
