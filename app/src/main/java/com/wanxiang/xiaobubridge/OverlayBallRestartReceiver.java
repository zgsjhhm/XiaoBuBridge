package com.wanxiang.xiaobubridge;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

/**
 * OverlayBallRestartReceiver — v3.10 悬浮球「消失后自己回来」的复活入口。
 *
 * <p><b>为什么必须有它</b>：悬浮球挂在本模块进程的常驻前台服务上，服务一旦被
 * ColorOS 省电策略回收（或模块被覆盖安装、设备重启），窗口就随之消失。此前模块
 * 没有任何复活路径——{@code OverlayBallService} 既不是 {@code BOOT_COMPLETED}
 * 接收者，{@code MainActivity.onStart} 也不做同步，于是只有「用户手动再进设置页
 * 点一次」才回得来。用户看到的正是「悬浮球有时候消失不见，重开小布也没有」。</p>
 *
 * <p>本接收器接收三类事件，统一收敛到 {@link OverlayBallService#syncState}：</p>
 * <ul>
 *   <li>{@code BOOT_COMPLETED} —— 重启后恢复；</li>
 *   <li>{@code MY_PACKAGE_REPLACED} —— 模块被覆盖安装后恢复（安装会杀掉进程）；</li>
 *   <li>自身定时闹钟 —— 进程被杀后按 {@link #ACTION_REVIVE} 尽力补拉。</li>
 * </ul>
 *
 * <p><b>诚实交代边界</b>：Android 12(API 31) 起「后台启动前台服务」受限，
 * 定时闹钟触发时若应用处于后台，{@code startForegroundService} 可能被系统拒绝
 * （{@code ForegroundServiceStartNotAllowedException}）。这条路径因此只是
 * 「尽力而为」：能拉起来最好，拉不起来也不会崩——{@link OverlayBallService#syncState}
 * 内部已兜住全部异常，用户下次打开模块界面时会由
 * {@code MainActivity.onStart} 立刻恢复。</p>
 */
public class OverlayBallRestartReceiver extends BroadcastReceiver {

    private static final String TAG = "[XiaoBuBridge]";

    /** 自身定时闹钟的动作名 */
    static final String ACTION_REVIVE = "com.wanxiang.xiaobubridge.action.REVIVE_OVERLAY";

    private static final int REQUEST_CODE = 0x5844;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        BridgeLog.i(TAG + " OverlayBallRestartReceiver: " + action);

        // 只认自己处理的动作，避免被同 action 的第三方广播带着走
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !ACTION_REVIVE.equals(action)) {
            return;
        }

        if (!ConfigManager.isOverlayBallEnabled(context)) {
            // 用户关掉了悬浮球：撤销闹钟，不做任何拉起
            cancelReviveAlarm(context);
            return;
        }
        if (!OverlayBallService.canDrawOverlays(context)) {
            BridgeLog.i(TAG + " OverlayBallRestartReceiver: overlay permission missing, skip");
            return;
        }

        // 唤醒小布并未必要：悬浮球完全由模块进程持有，与小布无关。
        // 这一点也是「重开小布没用」的原因——两条链路本来就不相干。
        OverlayBallService.syncState(context);

        if (ACTION_REVIVE.equals(action)) {
            // 循环续期：只要开关还开着，就持续留一条复活路径
            scheduleReviveAlarm(context, 15 * 60 * 1000L);
        }
    }

    /**
     * 安排一次「尽力复活」闹钟。
     *
     * <p>用 {@code set} + 固定 {@code RTC} 而不是 {@code setRepeating}：后者的间隔
     * 语义在 Doze 下会被系统拉长，也不方便读取下次触发时间；这里每次触发后
     * 自行续期，语义更明确。</p>
     */
    static void scheduleReviveAlarm(Context context, long delayMs) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) {
                return;
            }
            PendingIntent pi = revivePendingIntent(context);
            if (pi == null) {
                return;
            }
            long triggerAt = SystemClock.elapsedRealtime() + delayMs;
            // setAndAllowWhileIdle：Doze 下也能触发（但仍受平台节流），
            // 对「悬浮球自己回来」这种非精确需求足够。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
            }
            BridgeLog.i(TAG + " OverlayBallRestartReceiver: revive alarm in " + delayMs + "ms");
        } catch (Throwable t) {
            BridgeLog.i(TAG + " scheduleReviveAlarm failed: " + t);
        }
    }

    /** 撤销复活闹钟（用户关闭悬浮球时调用） */
    static void cancelReviveAlarm(Context context) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            PendingIntent pi = revivePendingIntent(context);
            if (am != null && pi != null) {
                am.cancel(pi);
            }
        } catch (Throwable ignored) {
        }
    }

    private static PendingIntent revivePendingIntent(Context context) {
        try {
            Intent intent = new Intent(context, OverlayBallRestartReceiver.class);
            intent.setAction(ACTION_REVIVE);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags);
        } catch (Throwable t) {
            BridgeLog.i(TAG + " revivePendingIntent failed: " + t);
            return null;
        }
    }
}
