package com.wanxiang.xiaobubridge;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;

import de.robv.android.xposed.XposedBridge;

/**
 * AutoWaker — v3.6 自动唤醒（v3.13 起降级为兜底）。
 *
 * <p><b>v3.13 语义修正</b>：v3.6 的原假设是「小布在后台时外部注入收不到任何回调，
 * 必须先拉到前台」。真机取证（PHB110 / ColorOS 16 / Android 16，小布 12.9.9）
 * 表明该假设<b>不成立</b>：在小布进程存活、对话引擎可用的前提下，后台注入
 * 在锁屏 / 流式 / 多轮 / tools 场景下都能拿到回调。真正让调用失效的是「进程或引擎
 * 不可用」，而非「不在前台」。</p>
 *
 * <p>因此本类在 v3.13 不再是 API 调用的默认前置步骤，而是
 * {@link OpenAIServer} 里「后台整轮零回调」时的<b>兜底手段</b>：
 * 先按后台静默跑一轮，确实拿不到回调、且用户开启了「自动唤醒」时，
 * 才调用 {@link #ensureForeground()} 拉前台重试一次。心跳保活路径
 * （{@link GatewayWatchdog}）也复用本类，语义独立。</p>
 *
 * <p><b>已知边界（不粉饰）</b>：
 * <ul>
 *   <li>锁屏状态下 Android 不允许后台起 Activity，唤醒会失败。此时请求会
 *       返回 503（{@code not_foreground}），而不是静默挂死；</li>
 *   <li>从后台强拉 Activity 会被系统标记为「后台启动」，部分 ROM 会弹确认或
 *       直接拦截，这里只记录日志，不做循环重试；</li>
 *   <li>本类只负责「拉到前台」，不保证拉到前台就能拿到回答——回答仍取决于
 *       小布自身的网络与额度状态。</li>
 * </ul></p>
 */
public class AutoWaker {

    private static final String TAG = "[XiaoBuBridge]";
    private static final String MAIN_ACTIVITY =
            "com.heytap.speechassist.launcher.SpeechAssistMainActivity";

    /** 等待前台的轮询间隔与上限：太短会误判，太长会把请求整体拖慢 */
    private static final long POLL_INTERVAL_MS = 200L;
    private static final long WAIT_TIMEOUT_MS = 5000L;

    private AutoWaker() {
    }

    /**
     * 确保小布处于前台。
     *
     * @return true 表示调用返回时小布已在前台（或本次成功唤起）；
     *         false 表示未能唤起（锁屏 / 系统拦截 / 拿不到 Context），调用方应据此放弃本轮
     */
    public static boolean ensureForeground() {
        Context ctx = targetContext();
        if (ctx == null) {
            XposedBridge.log(TAG + " AutoWaker: no target context, cannot wake");
            return false;
        }
        if (isSelfForeground(ctx)) {
            return true;
        }

        XposedBridge.log(TAG + " AutoWaker: XiaoBu not foreground, launching main activity");
        try {
            Intent intent = new Intent();
            intent.setClassName(ctx.getPackageName(), MAIN_ACTIVITY);
            // NEW_TASK | CLEAR_TOP：非 Activity 上下文必须带 NEW_TASK；
            // CLEAR_TOP 避免在已有任务栈上再叠一层，保持 singleTop 语义。
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            ctx.startActivity(intent);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " AutoWaker: startActivity failed: " + t);
            return false;
        }

        long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (isSelfForeground(ctx)) {
                XposedBridge.log(TAG + " AutoWaker: XiaoBu is foreground now");
                return true;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        XposedBridge.log(TAG + " AutoWaker: still not foreground after "
                + WAIT_TIMEOUT_MS + "ms (locked screen or launch blocked?)");
        return false;
    }

    /**
     * 本进程是否持有前台窗口。
     *
     * <p>用 {@code ActivityManager.getRunningAppProcesses()} 而不是
     * {@code getRunningTasks}：前者在 Android 5.0+ 对「本应用自身的
     * importance == IMPORTANCE_FOREGROUND」依然可用，后者需要
     * GET_TASKS/REAL_GET_TASKS 特殊权限，在普通应用里返回空列表。</p>
     */
    private static boolean isSelfForeground(Context ctx) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return false;
            }
            for (ActivityManager.RunningAppProcessInfo info : am.getRunningAppProcesses()) {
                if (info.processName != null
                        && (info.processName.equals(ctx.getPackageName())
                            || info.processName.startsWith(ctx.getPackageName() + ":"))) {
                    if (info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " AutoWaker: isSelfForeground failed: " + t);
        }
        return false;
    }

    /** 目标进程 Context，取不到返回 null */
    private static Context targetContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                return (Context) app;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
