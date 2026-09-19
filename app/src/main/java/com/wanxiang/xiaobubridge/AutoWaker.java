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
 * 在锁屏 / 流式 / 多轮 / tools 场景下都能拿到回调。</p>
 *
 * <p><b>v3.14 归因更正（据实）</b>：v3.13 把零回调归因为「进程或引擎不可用」，
 * 该解释同样不成立。v3.13 发布后的真机数据（含受控空闲分级 sweep）显示：
 * 零回调是<b>概率性</b>的，日志形态为 `injectUserMessage result=true`
 * 之后窗口内零回调、连 `chatType=1` 用户回显都没有——即引擎吞掉了首次注入；
 * 且失败与前后台无关（`warm-idle120` 前台也失败）、与空闲时长无单调关系
 * （3.2s gap 失败、3.3s gap 成功）。引擎「接单」时延（inject→accepted）实测
 * 92 次 1.53–7.01s、中位 2.51s（含正常轮）；两次被吞轮中<b>最终被接单的那次
 * 注入</b>→accepted 为 1.53s / 3.53s，说明引擎本身健康。
 * 冷启动只是「零回调」的一种可能情形，不是唯一原因。</p>
 *
 * <p>发生率只给定性：早期小结的「44 轮 7 轮 ≈ 16%」样本过小、无留存证据，
 * 已不再引用。网关日志不打印版本号（只能按日志形态断代），发生率又随工况波动，
 * 给不出稳定的
 * 百分比常数；冻结快照（2026-09-18 11:10，17061 行）上有两个可直接 grep
 * 复现的计数：450 个进入对话处理的请求中 28 个至少被吞一次（6.2%），
 * 453 次注入中 47 次被吞（10.4%）。这里只保留
 * <b>低频、概率性、不可预测</b> 的定性。</p>
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
     * 唤醒结果（v3.14）。
     *
     * <p><b>为什么要拆开</b>：v3.13 只返回一个 boolean，日志写成
     * {@code fallback AutoWake result=true}。该布尔值的真实语义是「调用返回时
     * 小布是否已在前台」，但「本来就在前台」与「这次真的唤起了」在日志里
     * 长得完全一样，会把排查者引回「必须前台才能拿到回调」的旧结论
     * （v3.13 取证时我本人就被这行日志误导过一轮）。</p>
     */
    public static final class WakeResult {
        /** 调用期间是否发起了 startActivity（false = 检出的那一刻已在前台，唤醒是空操作） */
        public final boolean launchAttempted;
        /** 调用返回时小布是否已处于前台（或本次成功拉起） */
        public final boolean foreground;
        /** 拉了但没起来（锁屏 / 系统拦截后台启动）；launchAttempted=true 且 foreground=false */
        public final boolean blocked;
        /** 耗时毫秒 */
        public final long costMs;

        WakeResult(boolean launchAttempted, boolean foreground, long costMs) {
            this.launchAttempted = launchAttempted;
            this.foreground = foreground;
            this.costMs = costMs;
            this.blocked = launchAttempted && !foreground;
        }

        /** 调用开始前小布已在前台，唤醒是空操作 */
        public boolean alreadyForeground() {
            return !launchAttempted && foreground;
        }

        /** 本次真的发起并成功把小布拉到前台（与「本来就在前台」互斥） */
        public boolean wakeLaunched() {
            return launchAttempted && foreground;
        }

        /** 拿不到目标 Context，连前后台都没能查（三个状态位会同时为 false） */
        public boolean noTargetContext() {
            return !launchAttempted && !foreground;
        }

        @Override
        public String toString() {
            // 三个状态位按真实语义互斥展开：修 v3.13 那种「本来就在前台」被记成
            // 「唤起了」的写法（旧 result=true 在两个语义间不可区分）。
            return "alreadyForeground=" + alreadyForeground()
                    + ", wakeLaunched=" + wakeLaunched()
                    + ", blocked=" + blocked
                    + ", noTargetContext=" + noTargetContext()
                    + ", cost=" + costMs + "ms";
        }
    }

    /**
     * 确保小布处于前台（兼容入口，仅供不需要区分「本来就在前台」的调用方使用）。
     *
     * @return 同 {@link WakeResult#foreground}
     */
    public static boolean ensureForeground() {
        return ensureForegroundDetailed().foreground;
    }

    /**
     * 确保小布处于前台，并区分「本来就在前台」与「本次唤起成功」。
     *
     * <p>注意 {@link WakeResult#foreground} 为 true 时，若 {@code launchAttempted=false}，
     * 只说明小布当时已在前台，<b>不表示唤醒发生了</b>，更不表示前台是拿到回调的
     * 必要条件或充分条件。</p>
     */
    public static WakeResult ensureForegroundDetailed() {
        long start = System.currentTimeMillis();
        Context ctx = targetContext();
        if (ctx == null) {
            XposedBridge.log(TAG + " AutoWaker: no target context, cannot wake");
            return new WakeResult(false, false, System.currentTimeMillis() - start);
        }
        if (isSelfForeground(ctx)) {
            // 关键：这里是空操作。旧日志把这种情形也记成 result=true，语义被掩盖。
            return new WakeResult(false, true, System.currentTimeMillis() - start);
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
            return new WakeResult(true, false, System.currentTimeMillis() - start);
        }

        long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (isSelfForeground(ctx)) {
                XposedBridge.log(TAG + " AutoWaker: XiaoBu is foreground now");
                return new WakeResult(true, true, System.currentTimeMillis() - start);
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new WakeResult(true, false, System.currentTimeMillis() - start);
            }
        }
        XposedBridge.log(TAG + " AutoWaker: still not foreground after "
                + WAIT_TIMEOUT_MS + "ms (locked screen or launch blocked?)");
        return new WakeResult(true, false, System.currentTimeMillis() - start);
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
