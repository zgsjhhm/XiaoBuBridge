package com.wanxiang.xiaobubridge;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;

import de.robv.android.xposed.XposedBridge;

/**
 * AutoWaker — v3.6 自动唤醒。
 *
 * <p><b>为什么需要它</b>：小布在后台时，外部注入「看起来成功」
 * （{@code injectUserMessage result=true}），但<b>收不到任何回调</b>
 * ——v3.5 真机验证时所有成功用例都是先手动把界面拉到前台才跑通的。
 * 也就是说这个桥在后台状态下等于不可用，每次调用都得先人工打开小布。</p>
 *
 * <p>本类把这一步自动化：HTTP 请求到达时，若小布不在前台，自己
 * {@code startActivity} 到主界面并等待前台窗口就绪，然后再注入。</p>
 *
 * <p><b>已知边界（不粉饰）</b>：
 * <ul>
 *   <li>锁屏状态下 Android 不允许后台起 Activity，唤醒会失败。此时请求会
 *       退化为「注入成功但无回调」，最终由等待超时返回错误，而不是静默挂死；</li>
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
