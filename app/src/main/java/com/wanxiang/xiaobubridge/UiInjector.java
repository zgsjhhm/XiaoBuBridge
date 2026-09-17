package com.wanxiang.xiaobubridge;

import android.app.Activity;
import android.view.View;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * UiInjector — v3.0 悬浮球注入器（重写）。
 *
 * <p>参考 APK（Qwen AppHook）的 UiInjector 模式：钩住 {@code Activity.onResume}，
 * 在 Activity 恢复后获取 {@code getWindow().getDecorView()}，
 * 将浮球直接添加到 Activity 的 decor view 根容器中。
 *
 * <p>优点：
 * <ul>
 *   <li>无需 {@code SYSTEM_ALERT_WINDOW} 权限，悬浮球仅出现在 App 内部；</li>
 *   <li>无需 Service，依赖 Activity 生命周期自动存在/销毁；</li>
 *   <li>悬浮球随 Activity 切换自动重绑，避免跨进程悬浮窗的复杂性。</li>
 * </ul>
 */
public class UiInjector {

    private static final String TAG = "[XiaoBuBridge]";
    private static final long DEBOUNCE_MS = 1500L; // 防抖：避免重复注入

    private static volatile boolean installed = false;

    /**
     * 安装浮球注入器 ——钩住所有 Activity 的 onResume。
     * 在小布进程内，每个 Activity 恢复时都会尝试注入悬浮球。
     */
    public static void install() {
        if (installed) {
            XposedBridge.log(TAG + " UiInjector already installed, skip");
            return;
        }
        installed = true;

        try {
            XposedHelpers.findAndHookMethod(
                    Activity.class, "onResume", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            injectFloatBall(param);
                        }
                    }
            );
            XposedBridge.log(TAG + " UiInjector: floating ball injector installed (Activity.onResume)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " UiInjector install failed: " + t);
        }
    }

    /**
     * 在 Activity.onResume 后，将浮球注入到 decor view。
     */
    private static void injectFloatBall(XC_MethodHook.MethodHookParam param) {
        try {
            Object thisObject = param.thisObject;
            if (!(thisObject instanceof Activity)) return;

            Activity activity = (Activity) thisObject;

            // 检查悬浮球开关
            if (!ConfigManager.isFloatBallEnabledInTarget()) return;

            View decorView = activity.getWindow().getDecorView();
            if (decorView == null) return;

            // 延迟到 decor view 布局完成后再添加（post 确保根容器已就绪）
            decorView.post(() -> {
                try {
                    XposedBridge.log(TAG + " UiInjector: attaching floating ball to " + activity.getClass().getSimpleName());
                    FloatingBall ball = FloatingBall.attach(decorView, activity);
                    if (ball != null) {
                        XposedBridge.log(TAG + " UiInjector: ball attached successfully, setting click listener");
                        ball.setOnClickListener(() -> {
                            XposedBridge.log(TAG + " UiInjector: ball clicked! Showing panel...");
                            FloatingPanel.show(activity);
                        });
                    } else {
                        XposedBridge.log(TAG + " UiInjector: ball is null (maybe already exists)");
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " UiInjector: ball attach failed: " + t);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + " UiInjector: onResume inject failed: " + t);
        }
    }
}
