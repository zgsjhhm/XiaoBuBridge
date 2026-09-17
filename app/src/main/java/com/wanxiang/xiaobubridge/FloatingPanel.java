package com.wanxiang.xiaobubridge;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import de.robv.android.xposed.XposedBridge;

/**
 * FloatingPanel — v3.0 控制面板。
 *
 * <p>参考 APK（Qwen AppHook）的 {@code FloatingPanel} 实现：
 * <ul>
 *   <li>点击悬浮球后弹出，显示在 Activity 内部；</li>
 *   <li>展示当前运行状态（API 端口、激活会话）；</li>
 *   <li>提供设置、复制 API 地址、隐藏悬浮球按钮；</li>
 *   <li>点击外部或按返回键关闭。</li>
 * </ul>
 *
 * <p>面板作为一个 FrameLayout 子视图添加到 Activity 的 decor view，
 * 与悬浮球位于同一视图层级，自动随 Activity 销毁。
 */
public class FloatingPanel {

    private static final String TAG = "[XiaoBuBridge]";
    private static final String PANEL_TAG = "xiaobu_floating_panel";
    private static final String BALL_TAG = "xiaobu_floating_ball";
    private static final String BG_TAG = "xiaobu_floating_bg";

    private static volatile FloatingPanel currentInstance = null;

    @NonNull
    private final Activity activity;
    @NonNull
    private final ViewGroup rootView;        // decor view (FrameLayout)
    @NonNull
    private final LinearLayout panelContent;  // 面板内容容器
    private View panelView;
    private boolean shown = false;

    /**
     * 显示控制面板。
     *
     * @param activity Activity 上下文
     */
    static void show(@NonNull Activity activity) {
        // 单例控制：避免重复创建多个面板
        if (currentInstance != null && currentInstance.shown) {
            currentInstance.updateStatus();
            return;
        }

        try {
            View decorView = activity.getWindow().getDecorView();
            if (!(decorView instanceof ViewGroup)) {
                XposedBridge.log(TAG + " FloatingPanel: decorView is not ViewGroup");
                return;
            }
            ViewGroup root = (ViewGroup) decorView;

            LayoutInflater inflater = LayoutInflater.from(activity);
            View panel = inflater.inflate(R.layout.control_panel, root, false);
            XposedBridge.log(TAG + " FloatingPanel: inflated panel, panel=" + panel.getClass().getSimpleName());

            FloatingPanel fp = new FloatingPanel(activity, root, panel);
            fp.show();

            currentInstance = fp;
            XposedBridge.log(TAG + " FloatingPanel: panel show completed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " FloatingPanel show failed: " + t);
            XposedBridge.log(t);
        }
    }

    private FloatingPanel(@NonNull Activity activity, @NonNull ViewGroup rootView, @NonNull View panelView) {
        this.activity = activity;
        this.rootView = rootView;
        this.panelView = panelView;
        this.panelContent = panelView.findViewById(R.id.control_panel_content);

        // 初始化按钮
        setupButtons();
    }

    private void setupButtons() {
        Context ctx = activity;

        // 刷新状态按钮
        View btnRefresh = panelView.findViewById(R.id.btn_refresh_status);
        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(v -> updateStatus());
        }

        // 打开设置按钮
        View btnSettings = panelView.findViewById(R.id.btn_open_settings);
        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> {
                try {
                    // 直接跳转到模块的 MainActivity
                    Intent intent = new Intent(ctx, MainActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(intent);
                    XposedBridge.log(TAG + " FloatingPanel: open MainActivity");
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " FloatingPanel: open settings failed: " + t);
                }
                hide();
            });
        }

        // 复制 API 地址按钮
        View btnCopy = panelView.findViewById(R.id.btn_copy_address);
        if (btnCopy != null) {
            btnCopy.setOnClickListener(v -> {
                try {
                    int port = ConfigManager.getPortInTarget();
                    String address = "http://127.0.0.1:" + port + "/v1";
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("API 地址", address));
                        XposedBridge.log(TAG + " FloatingPanel: copied address: " + address);
                    }
                    Toast.makeText(ctx, "已复制：" + address, Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " FloatingPanel: copy address failed: " + t);
                }
                hide();
            });
        }

        // 隐藏悬浮球按钮
        View btnHide = panelView.findViewById(R.id.btn_hide_float_ball);
        if (btnHide != null) {
            btnHide.setOnClickListener(v -> {
                // 隐藏悬浮球
                View ball = rootView.findViewWithTag(BALL_TAG);
                if (ball != null) {
                    ball.setVisibility(View.GONE);
                }
                // 关闭面板
                hide();
                // 关闭悬浮球功能 —— 通过 ContentProvider 写入模块 SharedPreferences
                ConfigManager.setFloatBallEnabledInTarget(false);
                Toast.makeText(ctx, "浮球已隐藏，下次启动 App 时自动关闭", Toast.LENGTH_SHORT).show();
                XposedBridge.log(TAG + " FloatingPanel: hide float ball requested");
            });
        }
    }

    /**
     * 显示面板：添加到 decor view。
     */
    private void show() {
        if (shown) return;

        // 隐藏悬浮球
        View ball = rootView.findViewWithTag(BALL_TAG);
        if (ball != null) {
            ball.setVisibility(View.GONE);
        }

        Resources res = activity.getResources();
        float density = res.getDisplayMetrics().density;

        // 面板布局参数 —— 定位在右上角
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END | Gravity.TOP;
        lp.setMargins(
                0,
                Math.round(80 * density),
                Math.round(16 * density),
                0
        );

        // 设置面板位置在右上角
        panelView.setLayoutParams(lp);

        // 确保面板可点击
        panelView.setClickable(true);
        panelView.setFocusable(true);
        panelView.setFocusableInTouchMode(true);

        // 点击外部区域关闭面板 —— 添加透明背景视图到 rootView（位于面板下层）
        View bg = new View(activity);
        bg.setTag(BG_TAG);
        ViewGroup.LayoutParams bgLp = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        bg.setLayoutParams(bgLp);
        bg.setBackgroundColor(0x00000000); // 透明背景用于接收点击
        bg.setOnClickListener(v -> hide());

        // 先添加 bg 背景视图（z-order: 底层），再添加面板（z-order: 顶层）
        rootView.addView(bg);
        rootView.addView(panelView);
        shown = true;

        // 更新状态显示
        updateStatus();
        XposedBridge.log(TAG + " FloatingPanel: panel shown");
    }

    /**
     * 更新面板上的状态显示。
     */
    private void updateStatus() {
        try {
            Context ctx = activity;

            // 获取当前端口
            int port = ConfigManager.getPortInTarget();
            // 获取会话状态
            ConversationSession session = ConversationSession.getMostRecent(0);
            String sessionStatus = "空闲";
            String lastMsg = "";

            if (session != null) {
                if (!session.isCompleted()) {
                    sessionStatus = "活跃中";
                    lastMsg = session.getLastUserMessage();
                    if (lastMsg != null && lastMsg.length() > 20) {
                        lastMsg = lastMsg.substring(0, 20) + "...";
                    }
                } else {
                    sessionStatus = "已完成";
                }
            }

            // 更新标题
            TextView tvTitle = panelView.findViewById(R.id.tv_control_title);
            if (tvTitle != null) {
                tvTitle.setText("XiaoBu Bridge\n[:" + port + "] " + sessionStatus);
            }

            // 显示最后一条用户消息
            TextView tvDetail = panelView.findViewById(R.id.tv_status_detail);
            if (tvDetail != null && lastMsg != null) {
                tvDetail.setText(lastMsg);
                tvDetail.setVisibility(View.VISIBLE);
            }

        } catch (Throwable t) {
            XposedBridge.log(TAG + " updateStatus failed: " + t);
        }
    }

    /**
     * 隐藏面板：从 decor view 移除，恢复悬浮球。
     */
    public void hide() {
        if (!shown) return;
        shown = false;

        try {
            rootView.removeView(panelView);
        } catch (Exception ignored) {
        }

        // 移除透明背景视图
        View bgView = rootView.findViewWithTag(BG_TAG);
        if (bgView != null) {
            try {
                rootView.removeView(bgView);
            } catch (Exception ignored) {
            }
        }

        // 恢复悬浮球
        View ball = rootView.findViewWithTag(BALL_TAG);
        if (ball != null) {
            ball.setVisibility(View.VISIBLE);
        }

        currentInstance = null;
    }

    /**
     * 检查面板是否正在显示。
     */
    public boolean isShown() {
        return shown;
    }
}
