package com.wanxiang.xiaobubridge;

import android.util.Log;

import java.lang.reflect.Method;

/**
 * BridgeLog — 模块进程 / Hook 进程双通道日志。
 *
 * <p><b>为什么不能直接调 {@code XposedBridge.log()}</b>：
 * {@code de.robv.android.xposed:api} 是 {@code compileOnly} 依赖，这个类只在被 LSPosed
 * 注入的宿主进程（小布）里由框架提供。而本模块自己也有进程——{@code MainActivity}、
 * {@code ConfigService}、{@code OverlayBallService}、{@code ConfigProvider} 都跑在
 * {@code com.wanxiang.xiaobubridge} 进程里，那里根本没有 {@code XposedBridge}。
 * 一旦这些类直接调用它，就会抛
 * {@code NoClassDefFoundError: Failed resolution of: Lde/robv/android/xposed/XposedBridge;}
 * 并直接把进程带崩。</p>
 *
 * <p>这个坑曾经真实发生：{@code OverlayBallService.showBall()} 里一句
 * {@code XposedBridge.log(...)} 让系统悬浮球「服务起来了、球永远不出现」。</p>
 *
 * <p>因此统一走本类：优先反射调用 {@code XposedBridge.log}（Hook 进程里能写进 LSPosed
 * 日志），不可用时回退到 {@code android.util.Log}（模块进程里写 logcat）。
 * 反射只在首次调用时解析一次并缓存结果，后续没有额外开销。</p>
 */
public final class BridgeLog {

    private static final String TAG = "XiaoBuBridge";
    private static final String XPOSED_TAG = "[XiaoBuBridge]";

    /** null = 尚未探测；非 null = 已解析到的静态 log(String) 方法 */
    private static volatile Method xposedLog;
    private static volatile boolean probed;

    private BridgeLog() {
    }

    public static void i(String message) {
        Method m = xposedMethod();
        if (m != null) {
            try {
                m.invoke(null, XPOSED_TAG + " " + message);
                return;
            } catch (Throwable ignored) {
                // 反射调用失败则走 logcat，不因为「日志写不出去」影响业务
            }
        }
        Log.i(TAG, message);
    }

    public static void e(String message) {
        Method m = xposedMethod();
        if (m != null) {
            try {
                m.invoke(null, XPOSED_TAG + " " + message);
                return;
            } catch (Throwable ignored) {
            }
        }
        Log.e(TAG, message);
    }

    public static void e(String message, Throwable t) {
        e(message + ": " + t);
    }

    /** 探测并缓存 {@code XposedBridge.log(String)}；不存在返回 null */
    private static Method xposedMethod() {
        if (!probed) {
            synchronized (BridgeLog.class) {
                if (!probed) {
                    try {
                        Class<?> bridge = Class.forName("de.robv.android.xposed.XposedBridge");
                        xposedLog = bridge.getMethod("log", String.class);
                    } catch (Throwable ignored) {
                        xposedLog = null;
                    }
                    probed = true;
                }
            }
        }
        return xposedLog;
    }
}
