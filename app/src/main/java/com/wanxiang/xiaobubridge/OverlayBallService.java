package com.wanxiang.xiaobubridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;



/**
 * OverlayBallService — v3.9 系统级悬浮球（安卓系统授予的悬浮窗）。
 *
 * <p><b>为什么悬浮球必须挂在系统窗口而不是应用内</b>：
 * v3.0–v3.9 曾同时存在一个 hook 进小布 {@code decorView} 的「应用内悬浮球」，
 * 它免权限，但<b>只有小布在前台时才存在</b>。而用户最需要网关开关的时候恰恰是
 * 小布在后台、或者自己正在别的应用里准备调用 API 的时候。两条通道并存还会让人
 * 分不清该开哪个，因此 v3.10 起只保留这一条：改为 {@code TYPE_APPLICATION_OVERLAY}
 * 系统窗口，由常驻前台服务持有，任何界面都能点开。</p>
 *
 * <p>代价是需要「显示在其他应用上层」权限，因此本服务<b>只在用户显式打开开关
 * 且权限已授予时启动</b>，不做任何自动拉起。这是有意的：一个自己申请到悬浮窗
 * 权限就常驻的应用，比没有悬浮球糟糕得多。</p>
 *
 * <p>面板承担三件事：</p>
 * <ul>
 *   <li><b>网关开关</b>：写 {@code server_enabled}，小布进程的
 *       {@link GatewayWatchdog} 1 秒内跟随生效（无需重启小布）；</li>
 *   <li><b>API Key 管理</b>：一键生成 / 复制 / 启停鉴权；</li>
 *   <li><b>心跳保活开关</b>：写 {@code heartbeat_enabled}，看门狗随即开始
 *       周期性探活并在网关失联时自愈。</li>
 * </ul>
 *
 * <p>所有开关都写模块进程自己的 SharedPreferences；小布进程通过导出的
 * {@link ConfigProvider} 只读获取，与设置页完全共用一份配置。</p>
 */
public class OverlayBallService extends Service {

    private static final String TAG = "[XiaoBuBridge]";
    private static final String CHANNEL_ID = "xiaobu_bridge_overlay";
    private static final int NOTIFICATION_ID = 0x5842;

    /** 悬浮球直径 / 边距（dp） */
    private static final int BALL_SIZE_DP = 48;
    private static final int BALL_MARGIN_DP = 8;
    /** 判定为拖动而非点击的位移阈值（px） */
    private static final int SLOP_PX = 16;
    /** 面板统计刷新间隔 */
    private static final long STATS_INTERVAL_MS = 2000L;
    /**
     * 面板高度下限（dp）。
     *
     * <p>面板实际高度取「半屏」，再夹到 [{@value #PANEL_MIN_HEIGHT_DP}dp, 屏高-边距] 之间；
     * 内容超出部分靠内部 {@code ScrollView} 滚动，不再让四张卡片把面板撑到整屏。</p>
     */
    private static final int PANEL_MIN_HEIGHT_DP = 240;
    /** 面板 / 悬浮球窗口距屏幕边缘的最小留白（dp） */
    private static final int SCREEN_MARGIN_DP = 4;

    /**
     * 服务内自检间隔。
     *
     * <p>覆盖的是「服务还活着、窗口却没了」这一类：系统回收悬浮窗（省电策略、
     * 显示策略变化）、配置变化后坐标落在屏外、面板窗口被移除但状态没回正。
     * 这类问题不会杀掉服务，因此进程级复活手段完全无能为力。</p>
     */
    private static final long SELF_CHECK_INTERVAL_MS = 5000L;

    /** 进程被回收后的「尽力而为」复活闹钟间隔 */
    private static final long REVIVE_ALARM_INTERVAL_MS = 15 * 60 * 1000L;

    /**
     * 本进程里悬浮球服务是否在跑。
     *
     * <p>供 {@link #requestSelfHealAsync} 做零成本快路径判定。它会被
     * {@link ConfigProvider} 以每秒一次的频率调用（小布进程的
     * {@link GatewayWatchdog} 轮询配置），所以「要不要自愈」必须先落在本进程的一个
     * volatile 上，不能每次都去问 AMS 或系统设置——否则正常运行时也会平白多出
     * 每秒一次的 IPC 与 setting 查询。</p>
     */
    private static volatile boolean serviceRunning;

    /** 上次自愈尝试时刻 / 上次尝试后是否仍未生效 / 连续未生效次数（用于退避） */
    private static volatile long lastSelfHealAt;
    private static volatile boolean lastSelfHealPending;
    private static volatile int selfHealFailures;

    /** 自愈重试的最小间隔；连续失败时按 2 的幂退避到 {@link #SELF_HEAL_MAX_INTERVAL_MS} */
    private static final long SELF_HEAL_MIN_INTERVAL_MS = 5000L;
    private static final long SELF_HEAL_MAX_INTERVAL_MS = 60000L;

    /** 悬浮球 / 面板的位置记忆：模块进程私有，不参与跨应用配置导出 */
    private static final String UI_PREFS = "xiaobu_bridge_overlay_ui";
    private static final String UI_KEY_BALL_X = "ball_x";
    private static final String UI_KEY_BALL_Y = "ball_y";
    private static final String UI_KEY_PANEL_X = "panel_x";
    private static final String UI_KEY_PANEL_Y = "panel_y";

    private WindowManager windowManager;
    private WindowManager.LayoutParams ballParams;
    private WindowManager.LayoutParams panelParams;
    private View ballView;
    private View panelView;
    private boolean panelShown;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable statsRunnable = new Runnable() {
        @Override
        public void run() {
            refreshStats();
            handler.postDelayed(this, STATS_INTERVAL_MS);
        }
    };

    /**
     * 自检巡检：窗口没了就补回来。
     *
     * <p>为什么必须有这条兜底：{@link #startForeground} 与 {@link #syncState} 只保证
     * 「服务进程在跑」，而用户看到的是「球在不在」。系统回收悬浮窗、配置变化把窗口
     * 挪到屏外之后，服务照旧活着、日志照旧干净，只有球不见了——这正是用户报的
     * 「悬浮球有时候消失不见」。所以这里以固定间隔核对窗口的挂载状态与可视性。</p>
     */
    private final Runnable selfCheckRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                selfCheck();
            } catch (Throwable t) {
                BridgeLog.i(TAG + " OverlayBall: selfCheck error: " + t);
            }
            handler.postDelayed(this, SELF_CHECK_INTERVAL_MS);
        }
    };

    // 面板内需要随状态刷新的控件
    private TextView tvGatewayChip;
    private TextView tvPort;
    private TextView tvConcurrency;
    private TextView tvToolRequests;
    private TextView tvHeartbeat;
    private TextView tvKeyValue;

    // 拖动状态
    private int ballDownRawX, ballDownRawY, ballStartX, ballStartY;
    private boolean ballMoved;

    // 面板拖动状态
    private int panelDownRawX, panelDownRawY, panelStartX, panelStartY;
    private boolean panelMoved;

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        createChannel();
        // 标记「本进程里服务在跑」：ConfigProvider 的零成本快路径读它。
        serviceRunning = true;
        // 服务真正起来了，重置自愈退避状态，让下次「被划掉」能立刻恢复。
        selfHealFailures = 0;
        lastSelfHealPending = false;
    }

    /**
     * 悬浮窗 UI 专用的带主题 Context。
     *
     * <p><b>为什么必须包一层</b>：{@code Service} 的 Context 没有 Activity 主题，
     * 直接用 {@code this} 去 new {@code SwitchMaterial} / {@code MaterialButton} 时，
     * Material 组件会在构造里做主题校验
     * （{@code ThemeEnforcement.checkMaterialTheme}）并抛
     * {@code IllegalArgumentException: The style on this component requires your app theme
     * to be Theme.MaterialComponents (or a descendant)}。
     * 这个异常发生在 {@code showPanel()} 里，会一路冒到主线程把整个模块进程带崩——
     * 表现就是「点一下悬浮球，球和网关一起消失」。</p>
     *
     * <p>{@code Theme.XiaoBuBridge} 继承自 {@code Theme.Material3.DayNight.NoActionBar}，
     * 是 Material3 主题，属于 MaterialComponents 后代，能满足校验。</p>
     */
    private Context themedContext() {
        try {
            return new ContextThemeWrapper(this, R.style.Theme_XiaoBuBridge);
        } catch (Throwable t) {
            BridgeLog.i(TAG + " themedContext failed: " + t);
            return this;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 前台服务必须带通知；内容刻意保持「无隐私信息」，避免锁屏通知泄露 API Key。
        //
        // 这里必须兜住异常：manifest 声明 foregroundServiceType="specialUse" 后，
        // startForeground() 在 API 34+ 会校验 FOREGROUND_SERVICE_SPECIAL_USE 权限，
        // 缺权限时抛 SecurityException。若不接住，异常会冒到 ActivityThread 导致
        // 整个模块进程崩溃（连 MainActivity/ConfigService 一起挂），而用户看到的
        // 只是「开了开关但悬浮球没出现」。接住后退化为优雅降级。
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (Throwable t) {
            BridgeLog.i(TAG + " OverlayBallService: startForeground failed: " + t);
            toast("前台服务启动失败，悬浮球无法常驻：" + t.getClass().getSimpleName());
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!canDrawOverlays(this)) {
            BridgeLog.i(TAG + " OverlayBallService: no overlay permission, stopping");
            toast("未授予「显示在其他应用上层」权限，悬浮球无法显示");
            stopSelf();
            return START_NOT_STICKY;
        }
        showBall();
        startSelfCheck();
        OverlayBallRestartReceiver.scheduleReviveAlarm(this, REVIVE_ALARM_INTERVAL_MS);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(statsRunnable);
        handler.removeCallbacks(selfCheckRunnable);
        removePanel();
        removeBall();
        // 本进程里服务已经不在跑了：ConfigProvider 快路径据此触发自愈。
        // 必须放在 super.onDestroy() 之前，且用无条件赋值——服务的每一次销毁
        // （用户关闭、系统回收、整包被 force-stop 前的正常停止）都意味着
        // 「本进程不再持有悬浮球」，这正是下一次进程内自愈的触发条件。
        serviceRunning = false;
        // 服务是被用户明确关掉/停掉，还是被系统顺手回收？
        // 前者不该再定时拉起，后者必须留一条复活路径，否则「球消失且永不回来」。
        // 判据：配置里开关仍开着 = 用户还想要这个球。
        if (ConfigManager.isOverlayBallEnabled(this) && canDrawOverlays(this)) {
            OverlayBallRestartReceiver.scheduleReviveAlarm(this, REVIVE_ALARM_INTERVAL_MS);
        } else {
            OverlayBallRestartReceiver.cancelReviveAlarm(this);
        }
        super.onDestroy();
    }

    /**
     * 用户从最近任务里划掉模块时被调用。
     *
     * <p>模块的 Activity 与悬浮球服务在同一个进程；划掉任务卡片会让系统倾向
     * 回收该进程，常驻服务随之消失，球也就没了。这里把「用户明确关闭悬浮球」
     * 与「只是划掉了任务卡片」区分开：后者不该导致球消失。</p>
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        BridgeLog.i(TAG + " OverlayBallService: onTaskRemoved, keep alive = "
                + ConfigManager.isOverlayBallEnabled(this));
        if (ConfigManager.isOverlayBallEnabled(this) && canDrawOverlays(this)) {
            // 用独立接收器再拉一次自身：比在 onTaskRemoved 里直接 startForegroundService
            // 更稳（进程若已被判死，直接启动会被系统忽略，而 manifest 声明的接收器
            // 能从系统侧重新拉起进程）。
            OverlayBallRestartReceiver.scheduleReviveAlarm(this, 2000L);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 旋转/分屏后原先的 x,y 可能落到屏幕外。注意这里必须真的
        // updateViewLayout：只改 ballParams 里的数值不改窗口属性，窗口会继续留在
        // 旧坐标上，一旦旧坐标在新布局下整体落在屏外，球就再也看不到了。
        // 旧实现只调 clampBallToScreen() 而不提交，正是「悬浮球有时消失且不会回来」
        // 的一条真实路径。
        applyBallLayout();
        clampPanelToScreen();
        applyPanelLayout();
    }

    // ==================== 权限与生命周期入口 ====================

    /** 是否已获得系统悬浮窗权限 */
    public static boolean canDrawOverlays(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return Settings.canDrawOverlays(ctx);
            }
            // API 23 以下安装即授予（本模块 minSdk 24，这里只是兜底）
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 跳系统设置页申请悬浮窗权限 */
    public static void requestOverlayPermission(Context ctx) {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + ctx.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (Throwable t) {
            BridgeLog.i(TAG + " requestOverlayPermission failed: " + t);
        }
    }

    /** 按当前配置启停服务：开关关闭或权限缺失时什么都不做 */
    public static void syncState(Context ctx) {
        boolean enabled = ConfigManager.isOverlayBallEnabled(ctx)
                && canDrawOverlays(ctx);
        Intent intent = new Intent(ctx, OverlayBallService.class);
        if (enabled) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent);
                } else {
                    ctx.startService(intent);
                }
            } catch (Throwable t) {
                BridgeLog.i(TAG + " OverlayBallService start failed: " + t);
            }
        } else {
            try {
                ctx.stopService(intent);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 进程内自愈：只要本进程还活着、而悬浮球服务不在跑，就按配置把它补回来。
     *
     * <p><b>为什么需要这条路径</b>：ColorOS 从最近任务划掉模块卡片时，执行的是对
     * <b>整个包</b>的 force-stop（事件日志 {@code am_kill ... due to o-stop(40)}），
     * 一次性做四件事：杀掉进程、停掉 {@code OverlayBallService}、撤销
     * {@code startForeground} 通知、<b>并清空该应用的全部闹钟</b>。因此
     * {@link OverlayBallRestartReceiver} 的「2 秒后自己爬起来」在 ColorOS 上
     * 必然失效——闹钟比进程先没，永远等不到触发。</p>
     *
     * <p><b>为什么要在这里自愈</b>：模块进程虽然会被 ColorOS 连带杀掉，却会被
     * 小布进程按需重新拉起——{@link GatewayWatchdog} 每秒调用一次
     * {@code ConfigManager.isServerEnabledInTarget()}，经 {@link ConfigProvider}
     * 查询配置，系统便会以「content provider」为由启动模块进程（实测划掉后
     * 0.7 秒即发生）。于是进程内一定存在一个稳定、周期性的执行点，本方法就挂在
     * 那个点上：进程刚被拉起时服务必然不在跑，正好补一次。</p>
     *
     * <p><b>为什么能在后台启动前台服务</b>：Android 12+ 限制后台启动 FGS，但
     * 「持有 {@code SYSTEM_ALERT_WINDOW} 权限」是官方文档列明的豁免之一；
     * 本模块的悬浮球功能本就要求用户授予该权限，{@link #syncState} 也只在
     * 权限已授予时才动作。若系统仍然拒绝，异常会被 {@code syncState} 内部兜住，
     * 退化为「等用户下次打开模块界面」——不会崩，也不会反复重试到耗电。</p>
     *
     * <p><b>成本控制</b>：正常运行时（{@code serviceRunning == true}）只读一个
     * volatile 就返回，每秒一次的调用不会产生任何 IPC；仅在服务确实不在跑时才
     * 做实际动作，且带指数退避——避免「权限被撤销」「配置已关闭」这类注定失败的
     * 场景被无意义地反复尝试。</p>
     */
    public static void requestSelfHealAsync(Context ctx) {
        if (ctx == null) {
            return;
        }
        // 零成本快路径：本进程里服务在跑，什么都不用做（绝大多数调用走这里）
        if (serviceRunning) {
            return;
        }

        // 退避闸门放在最前：本方法会被每秒调用一次，退避期间必须立刻返回，
        // 否则下面 canDrawOverlays() 的每次 IPC 都会平白发生。
        // 退避间隔由「已确认失败的次数」决定；先算间隔再决定是否到期，
        // 否则「每调用一次就加一次失败数」会让间隔长得比时间流逝还快，永远排不上。
        long now = SystemClock.elapsedRealtime();
        long interval = SELF_HEAL_MIN_INTERVAL_MS << Math.min(selfHealFailures, 4);
        if (interval > SELF_HEAL_MAX_INTERVAL_MS) {
            interval = SELF_HEAL_MAX_INTERVAL_MS;
        }
        if (lastSelfHealAt != 0 && now - lastSelfHealAt < interval) {
            return;
        }

        // 用户没开悬浮球：本进程 SharedPreferences 本地读，无 IPC。
        // 启停交给 MainActivity / 设置页的 syncState，这里不做多余动作。
        if (!ConfigManager.isOverlayBallEnabled(ctx)) {
            return;
        }
        // 权限缺失时 addView 只会反复抛异常，也没必要自愈（IPC，仅在真要动作时才问）
        if (!canDrawOverlays(ctx)) {
            return;
        }

        // 走到这里说明上一轮尝试的间隔已过；若那时拉起的服务仍未出现，记一次失败
        if (lastSelfHealPending) {
            selfHealFailures = Math.min(selfHealFailures + 1, 4);
        }
        lastSelfHealAt = now;
        lastSelfHealPending = true;

        // 放到独立线程：本方法可能在 ConfigProvider 的 Binder 线程上被调用，
        // 不能让 startForegroundService / canDrawOverlays 的 IPC 拖慢配置查询
        // （小布进程的看门狗每秒都在等这个查询结果）。
        final Context app = ctx.getApplicationContext();
        try {
            new Thread(() -> {
                try {
                    BridgeLog.i(TAG + " OverlayBall: self-heal (service not running in this process)");
                    syncState(app);
                } catch (Throwable t) {
                    BridgeLog.i(TAG + " OverlayBall: self-heal failed: " + t);
                }
            }, "xiaobu-overlay-selfheal").start();
        } catch (Throwable t) {
            BridgeLog.i(TAG + " OverlayBall: self-heal thread failed: " + t);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "网关悬浮球",
                    NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("保持悬浮球与心跳保活常驻所需的最低优先级通知");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        } catch (Throwable t) {
            BridgeLog.i(TAG + " createChannel failed: " + t);
        }
    }

    private Notification buildNotification() {
        int icon = getApplicationInfo().icon;
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setSmallIcon(icon)
                .setContentTitle("XiaoBu Bridge")
                .setContentText("悬浮球与心跳保活运行中")
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MIN)
                .build();
    }

    // ==================== 悬浮球窗口 ====================

    private void showBall() {
        if (ballView != null) {
            return;
        }
        Context ctx = this;
        int size = UIKit.dp(ctx, BALL_SIZE_DP);

        FrameLayout ball = new FrameLayout(ctx);
        ball.setBackground(ballBackground(ctx));
        ball.setElevation(UIKit.dp(ctx, 8));

        TextView glyph = new TextView(ctx);
        glyph.setText("\u2699");
        glyph.setTextSize(20);
        glyph.setTextColor(0xFF93C5FD);
        glyph.setGravity(Gravity.CENTER);
        ball.addView(glyph, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ballParams = new WindowManager.LayoutParams(
                size, size,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        ballParams.gravity = Gravity.TOP | Gravity.START;

        // 位置记忆：上次停在哪就回到哪。默认值仍贴在右边缘偏上，
        // 但坐标先按当前屏幕夹一遍——换过分辨率/旋转后旧坐标可能整个在屏外。
        int defX = getResources().getDisplayMetrics().widthPixels - size - UIKit.dp(ctx, 12);
        int defY = UIKit.dp(ctx, 120);
        ballParams.x = prefs().getInt(UI_KEY_BALL_X, defX);
        ballParams.y = prefs().getInt(UI_KEY_BALL_Y, defY);
        clampBallToScreen();

        ball.setOnTouchListener(this::handleBallTouch);

        try {
            windowManager.addView(ball, ballParams);
            ballView = ball;
            BridgeLog.i(TAG + " OverlayBall: shown at (" + ballParams.x + "," + ballParams.y + ")");
        } catch (Throwable t) {
            BridgeLog.i(TAG + " OverlayBall: addView failed: " + t);
        }
    }

    private void removeBall() {
        if (ballView != null) {
            try {
                windowManager.removeView(ballView);
            } catch (Throwable ignored) {
            }
            ballView = null;
        }
    }

    private GradientDrawable ballBackground(Context ctx) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xF21F2937);
        bg.setStroke(Math.max(1, UIKit.dp(ctx, 2)), UIKit.ACCENT);
        return bg;
    }

    private boolean handleBallTouch(View v, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                ballDownRawX = (int) event.getRawX();
                ballDownRawY = (int) event.getRawY();
                ballStartX = ballParams.x;
                ballStartY = ballParams.y;
                ballMoved = false;
                return true;

            case MotionEvent.ACTION_MOVE: {
                int dx = (int) (event.getRawX() - ballDownRawX);
                int dy = (int) (event.getRawY() - ballDownRawY);
                if (!ballMoved
                        && (Math.abs(dx) > SLOP_PX || Math.abs(dy) > SLOP_PX)) {
                    ballMoved = true;
                }
                if (!ballMoved) {
                    return true;
                }
                ballParams.x = ballStartX + dx;
                ballParams.y = ballStartY + dy;
                clampBallToScreen();
                try {
                    windowManager.updateViewLayout(ballView, ballParams);
                } catch (Throwable t) {
                    BridgeLog.i(TAG + " OverlayBall: drag update failed: " + t);
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!ballMoved) {
                    togglePanel();
                } else {
                    // 松手后吸附到最近的左右边缘：贴在边上比停在屏幕中间更不容易挡住内容
                    snapBallToEdge();
                }
                return true;
            default:
                return false;
        }
    }

    /** 夹到可视区 + 提交窗口布局 + 记住位置（拖动结束与配置变化共用） */
    private void applyBallLayout() {
        if (ballView == null || ballParams == null) {
            return;
        }
        clampBallToScreen();
        try {
            windowManager.updateViewLayout(ballView, ballParams);
        } catch (Throwable t) {
            BridgeLog.i(TAG + " OverlayBall: updateViewLayout failed: " + t);
        }
    }

    private void clampBallToScreen() {
        if (ballParams == null) {
            return;
        }
        int size = UIKit.dp(this, BALL_SIZE_DP);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        // 留一点边距，避免球紧贴屏幕物理边缘时被曲面/手势条吃掉半个
        int margin = UIKit.dp(this, SCREEN_MARGIN_DP);
        ballParams.x = Math.max(margin, Math.min(screenW - size - margin, ballParams.x));
        ballParams.y = Math.max(margin, Math.min(screenH - size - margin, ballParams.y));
    }

    private void snapBallToEdge() {
        if (ballParams == null || ballView == null) {
            return;
        }
        int size = UIKit.dp(this, BALL_SIZE_DP);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int margin = UIKit.dp(this, BALL_MARGIN_DP);
        ballParams.x = (ballParams.x + size / 2 < screenW / 2) ? margin : (screenW - size - margin);
        applyBallLayout();
        rememberBallPosition();
    }

    private void rememberBallPosition() {
        if (ballParams == null) {
            return;
        }
        try {
            prefs().edit()
                    .putInt(UI_KEY_BALL_X, ballParams.x)
                    .putInt(UI_KEY_BALL_Y, ballParams.y)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    private void rememberPanelPosition() {
        if (panelParams == null) {
            return;
        }
        try {
            prefs().edit()
                    .putInt(UI_KEY_PANEL_X, panelParams.x)
                    .putInt(UI_KEY_PANEL_Y, panelParams.y)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    /** 悬浮球 / 面板位置记忆用的私有 prefs，不参与跨应用配置导出 */
    private android.content.SharedPreferences prefs() {
        return getApplicationContext()
                .getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE);
    }

    /**
     * 巡检：球还在不在、在不在看得见的地方。
     *
     * <p>四条判据，任一不满足就重建/夹回：</p>
     * <ol>
     *   <li>球窗口是否还挂在 WindowManager 上（被系统回收时 {@code isAttachedToWindow}
     *       会变 false，而服务本身毫发无损）；</li>
     *   <li>坐标是否还在当前屏幕内（改分辨率、旋转、投屏切换后可能整体跑出屏外）；</li>
     *   <li>用户配置是否仍要求显示悬浮球（在别处关掉了就退出，别赖着）；</li>
     *   <li>悬浮窗权限是否还在被授予（用户撤销后继续 addView 只会反复抛异常）。</li>
     * </ol>
     */
    private void selfCheck() {
        if (!ConfigManager.isOverlayBallEnabled(this)) {
            BridgeLog.i(TAG + " OverlayBall: disabled by config, stopping service");
            stopSelf();
            return;
        }
        if (!canDrawOverlays(this)) {
            return;
        }

        if (ballView == null || !ballView.isAttachedToWindow()) {
            BridgeLog.i(TAG + " OverlayBall: window lost, recreating");
            ballView = null;
            showBall();
            return;
        }

        // 坐标越界：夹回并提交
        int size = UIKit.dp(this, BALL_SIZE_DP);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        if (ballParams.x < 0 || ballParams.y < 0
                || ballParams.x > screenW - size || ballParams.y > screenH - size) {
            BridgeLog.i(TAG + " OverlayBall: out of screen (" + ballParams.x + "," + ballParams.y
                    + "), clamping");
            clampBallToScreen();
            applyBallLayout();
        }

        // 面板开着但窗口丢了：把状态回正，否则再点球会被 panelShown=true 挡住（点了没反应）
        if (panelShown && (panelView == null || !panelView.isAttachedToWindow())) {
            BridgeLog.i(TAG + " OverlayBall: panel window lost, resetting state");
            panelView = null;
            panelShown = false;
            handler.removeCallbacks(statsRunnable);
        }
    }

    private void startSelfCheck() {
        handler.removeCallbacks(selfCheckRunnable);
        handler.postDelayed(selfCheckRunnable, SELF_CHECK_INTERVAL_MS);
    }

    private static int overlayWindowType() {
        // API 26 起 TYPE_PHONE 被废弃并会被系统拒绝，必须用 TYPE_APPLICATION_OVERLAY
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    // ==================== 控制面板窗口 ====================

    private void togglePanel() {
        if (panelShown) {
            removePanel();
        } else {
            showPanel();
        }
    }

    private void showPanel() {
        if (panelView != null) {
            return;
        }
        // 面板里有 SwitchMaterial / MaterialButton，必须用带 Material 主题的 Context，
        // 否则构造期抛 IllegalArgumentException 直接崩进程（详见 themedContext()）。
        Context ctx = themedContext();
        try {
            buildAndAttachPanel(ctx);
        } catch (Throwable t) {
            // 面板属于「锦上添花」的功能，任何构建失败都不该拖垮承载网关开关与心跳保活的
            // 常驻服务进程；失败时退化为提示，服务与悬浮球继续可用。
            BridgeLog.e(TAG + " OverlayPanel: build failed: " + t);
            toast("控制面板打开失败：" + t.getClass().getSimpleName());
            removePanel();
        }
    }

    private void buildAndAttachPanel(Context ctx) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(panelBackground(ctx));
        root.setElevation(UIKit.dp(ctx, 12));

        // 标题栏同时是拖动手柄，触摸监听挂在视图本身上（见 handlePanelTouch）
        View header = buildHeader(ctx);
        header.setOnTouchListener(this::handlePanelTouch);
        root.addView(header, UIKit.matchWrap(ctx, 0f));

        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(UIKit.dp(ctx, 12), UIKit.dp(ctx, 10), UIKit.dp(ctx, 12), UIKit.dp(ctx, 12));

        body.addView(buildStatusCard(ctx), UIKit.matchWrap(ctx, 0f));
        body.addView(buildSwitchCard(ctx), UIKit.matchWrap(ctx, 10f));
        body.addView(buildApiKeyCard(ctx), UIKit.matchWrap(ctx, 10f));
        body.addView(buildActionCard(ctx), UIKit.matchWrap(ctx, 10f));

        // 面板从「按内容撑高」改为「固定高度 + 内部滚动」：
        // 四张卡片全展开接近整屏，把球下方一大片内容都遮住了。现在高度取半屏
        // （夹在 [PANEL_MIN_HEIGHT_DP, 屏高-边距] 之间），超出部分在面板内部滚动。
        android.widget.ScrollView scroller = new android.widget.ScrollView(ctx);
        scroller.setVerticalScrollBarEnabled(true);
        scroller.addView(body, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        int panelWidth = Math.min(UIKit.dp(ctx, 300),
                getResources().getDisplayMetrics().widthPixels - UIKit.dp(ctx, 24));
        int panelHeight = panelHeightPx(ctx);

        panelParams = new WindowManager.LayoutParams(
                panelWidth,
                panelHeight,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.TOP | Gravity.START;

        // 位置记忆：默认在左上角偏下，但拖动过之后从哪关的就从哪开。
        int defX = UIKit.dp(ctx, 12);
        int defY = UIKit.dp(ctx, 100);
        panelParams.x = prefs().getInt(UI_KEY_PANEL_X, defX);
        panelParams.y = prefs().getInt(UI_KEY_PANEL_Y, defY);
        clampPanelToScreen();

        try {
            windowManager.addView(root, panelParams);
            panelView = root;
            panelShown = true;
            refreshStats();
            handler.postDelayed(statsRunnable, STATS_INTERVAL_MS);
            BridgeLog.i(TAG + " OverlayPanel: shown " + panelWidth + "x" + panelHeight
                    + " at (" + panelParams.x + "," + panelParams.y + ")");
        } catch (Throwable t) {
            BridgeLog.i(TAG + " OverlayPanel: addView failed: " + t);
            panelView = null;
            panelShown = false;
        }
    }

    /**
     * 面板高度：取屏幕可用高度的一半。
     *
     * <p>下夹 {@link #PANEL_MIN_HEIGHT_DP}（再矮就只剩标题栏，内容全得滚），
     * 上夹「屏高 - 上下留白」（横屏等矮屏场景下不能高过屏幕本身）。</p>
     */
    private int panelHeightPx(Context ctx) {
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int margin = UIKit.dp(ctx, 24);
        int target = screenH / 2;
        int min = UIKit.dp(ctx, PANEL_MIN_HEIGHT_DP);
        int max = Math.max(min, screenH - margin);
        return Math.max(min, Math.min(max, target));
    }

    private void clampPanelToScreen() {
        if (panelParams == null) {
            return;
        }
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int margin = UIKit.dp(this, SCREEN_MARGIN_DP);
        // 面板高度是固定值，直接把整块夹进屏幕内；宽度允许超出（横屏窄屏时
        // panelParams.width 已经按屏宽算过，这里只兜住 x/y）。
        int w = Math.min(panelParams.width, screenW);
        int h = Math.min(panelParams.height, screenH);
        panelParams.x = Math.max(0, Math.min(screenW - w, panelParams.x));
        panelParams.y = Math.max(margin, Math.min(screenH - h, panelParams.y));
    }

    private void applyPanelLayout() {
        if (panelView == null || panelParams == null) {
            return;
        }
        try {
            windowManager.updateViewLayout(panelView, panelParams);
        } catch (Throwable t) {
            BridgeLog.i(TAG + " OverlayPanel: updateViewLayout failed: " + t);
        }
    }

    /**
     * 面板拖动：按住标题栏拖。
     *
     * <p>只挂标题栏而不是整块面板，是因为面板里全是开关和按钮——整块可拖会让
     * 手指在滑动开关时误触拖动。标题栏本身没有可点内容（关闭按钮在右侧末端，
     * 触摸落在它身上时由它自己消费），作为拖动手柄语义清晰。</p>
     */
    private boolean handlePanelTouch(View v, MotionEvent event) {
        if (panelParams == null || panelView == null) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                panelDownRawX = (int) event.getRawX();
                panelDownRawY = (int) event.getRawY();
                panelStartX = panelParams.x;
                panelStartY = panelParams.y;
                panelMoved = false;
                return true;

            case MotionEvent.ACTION_MOVE: {
                int dx = (int) (event.getRawX() - panelDownRawX);
                int dy = (int) (event.getRawY() - panelDownRawY);
                if (!panelMoved && (Math.abs(dx) > SLOP_PX || Math.abs(dy) > SLOP_PX)) {
                    panelMoved = true;
                }
                if (!panelMoved) {
                    return true;
                }
                panelParams.x = panelStartX + dx;
                panelParams.y = panelStartY + dy;
                clampPanelToScreen();
                applyPanelLayout();
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (panelMoved) {
                    rememberPanelPosition();
                }
                return true;
            default:
                return false;
        }
    }

    private void removePanel() {
        handler.removeCallbacks(statsRunnable);
        if (panelView != null) {
            try {
                windowManager.removeView(panelView);
            } catch (Throwable ignored) {
            }
            panelView = null;
        }
        panelShown = false;
    }

    private GradientDrawable panelBackground(Context ctx) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(UIKit.dp(ctx, UIKit.R_CARD));
        bg.setColor(UIKit.SURFACE);
        bg.setStroke(Math.max(1, UIKit.dp(ctx, 1)), UIKit.SURFACE_BORDER);
        return bg;
    }

    private View buildHeader(Context ctx) {
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(UIKit.dp(ctx, 14), UIKit.dp(ctx, 10),
                UIKit.dp(ctx, 10), UIKit.dp(ctx, 8));

        TextView title = new TextView(ctx);
        title.setText("XiaoBu Bridge");
        title.setTextSize(15f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(UIKit.TEXT_PRIMARY);
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(ctx);
        close.setText("✕");
        close.setTextSize(15f);
        close.setTextColor(UIKit.TEXT_SECONDARY);
        close.setPadding(UIKit.dp(ctx, 10), UIKit.dp(ctx, 2),
                UIKit.dp(ctx, 6), UIKit.dp(ctx, 2));
        close.setOnClickListener(v -> removePanel());
        header.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return header;
    }

    private View buildStatusCard(Context ctx) {
        LinearLayout card = UIKit.card(ctx);
        card.addView(UIKit.sectionTitle(ctx, "运行状态"));

        LinearLayout gwRow = new LinearLayout(ctx);
        gwRow.setOrientation(LinearLayout.HORIZONTAL);
        gwRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(ctx);
        label.setText("网关");
        label.setTextSize(UIKit.SP_BODY);
        label.setTextColor(UIKit.TEXT_SECONDARY);
        gwRow.addView(label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tvGatewayChip = UIKit.statusChip(ctx, "检测中", UIKit.YELLOW);
        gwRow.addView(tvGatewayChip);
        card.addView(gwRow, UIKit.matchWrap(ctx, 0f));

        tvPort = UIKit.infoRow(card, ctx, "监听端口", "—");
        tvConcurrency = UIKit.infoRow(card, ctx, "并发上限", "—");
        tvToolRequests = UIKit.infoRow(card, ctx, "工具调用请求", "—");
        UIKit.divider(card, ctx);
        tvHeartbeat = UIKit.infoRow(card, ctx, "心跳", "—");
        return card;
    }

    private View buildSwitchCard(Context ctx) {
        LinearLayout card = UIKit.card(ctx);
        card.addView(UIKit.sectionTitle(ctx, "快速开关"));

        UIKit.switchRow(card, ctx, "本地 API 服务",
                "关闭后小布进程立即停止监听端口",
                ConfigManager.isServerEnabled(this))
                .setOnCheckedChangeListener((v, checked) -> {
                    ConfigManager.setServerEnabled(this, checked);
                    toast(checked ? "网关已开启" : "网关已关闭");
                    refreshStats();
                });

        UIKit.switchRow(card, ctx, "心跳保活",
                "周期性探活并在网关失联时自愈；同时拦截小布的自动退出",
                ConfigManager.isHeartbeatEnabled(this))
                .setOnCheckedChangeListener((v, checked) -> {
                    ConfigManager.setHeartbeatEnabled(this, checked);
                    toast(checked ? "心跳保活已开启" : "心跳保活已关闭");
                    refreshStats();
                });

        UIKit.switchRow(card, ctx, "请求到达时自动唤醒小布",
                "锁屏状态下无法唤起",
                ConfigManager.isAutoWakeEnabled(this))
                .setOnCheckedChangeListener((v, checked) ->
                        ConfigManager.setAutoWakeEnabled(this, checked));
        return card;
    }

    private View buildApiKeyCard(Context ctx) {
        LinearLayout card = UIKit.card(ctx);
        card.addView(UIKit.sectionTitle(ctx, "API Key"));

        UIKit.switchRow(card, ctx, "启用 API Key 鉴权",
                "开启后请求需带 Authorization: Bearer <key>",
                ConfigManager.isApiKeyEnabled(this))
                .setOnCheckedChangeListener((v, checked) -> {
                    ConfigManager.setApiKeyEnabled(this, checked);
                    toast(checked ? "鉴权已开启" : "鉴权已关闭");
                    refreshStats();
                });

        tvKeyValue = UIKit.infoRow(card, ctx, "当前密钥", "—");

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, UIKit.dp(ctx, 8), 0, 0);

        TextView generate = UIKit.statusChip(ctx, "生成新密钥", UIKit.ACCENT);
        generate.setPadding(UIKit.dp(ctx, 12), UIKit.dp(ctx, 6),
                UIKit.dp(ctx, 12), UIKit.dp(ctx, 6));
        generate.setOnClickListener(v -> {
            String key = ConfigManager.generateApiKey();
            ConfigManager.setApiKey(this, key);
            // 生成即视为要用：顺手打开鉴权开关，避免「生成了但没生效」的困惑
            ConfigManager.setApiKeyEnabled(this, true);
            toast("已生成并启用新密钥");
            refreshStats();
        });
        row.addView(generate);

        TextView copy = UIKit.statusChip(ctx, "复制密钥", UIKit.GREEN);
        copy.setPadding(UIKit.dp(ctx, 12), UIKit.dp(ctx, 6),
                UIKit.dp(ctx, 12), UIKit.dp(ctx, 6));
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        copyLp.leftMargin = UIKit.dp(ctx, 8);
        copy.setOnClickListener(v -> copyApiKey());
        row.addView(copy, copyLp);

        card.addView(row);
        return card;
    }

    private View buildActionCard(Context ctx) {
        LinearLayout card = UIKit.card(ctx);
        card.addView(UIKit.sectionTitle(ctx, "操作"));

        card.addView(UIKit.outlineButton(ctx, "打开模块设置", v -> {
            try {
                Intent intent = new Intent(this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Throwable t) {
                toast("无法打开设置页");
            }
            removePanel();
        }), UIKit.matchWrap(ctx, 4f));

        card.addView(UIKit.outlineButton(ctx, "关闭并隐藏悬浮球", v -> {
            ConfigManager.setOverlayBallEnabled(this, false);
            toast("悬浮球已关闭（可在模块设置页重新开启）");
            stopSelf();
        }), UIKit.matchWrap(ctx, 6f));
        return card;
    }

    // ==================== 状态刷新 ====================

    private void refreshStats() {
        if (!panelShown) {
            return;
        }
        new Thread(() -> {
            JSONObject status = fetchStatus();
            handler.post(() -> renderStats(status));
        }, "xiaobu-overlay-stats").start();
    }

    private void renderStats(JSONObject status) {
        if (panelView == null) {
            return;
        }
        if (status == null) {
            tvGatewayChip.setText("未响应");
            UIKit.tintChip(tvGatewayChip, UIKit.RED);
            tvPort.setText(String.valueOf(ConfigManager.getPort(this)));
        } else {
            tvGatewayChip.setText("运行中");
            UIKit.tintChip(tvGatewayChip, UIKit.GREEN);
            tvPort.setText(String.valueOf(status.optInt("port", ConfigManager.getPort(this))));
            tvConcurrency.setText(String.valueOf(status.optInt("max_concurrency",
                    ConfigManager.getMaxConcurrency(this))));
            tvToolRequests.setText(status.optLong("tool_requests", 0)
                    + " 次 / " + status.optLong("tool_calls", 0) + " 调用");
        }

        // 心跳一行同时给「开关」与「周期」：只看开关无法判断心跳是否真的在跑
        boolean hb = ConfigManager.isHeartbeatEnabled(this);
        int intervalSec = ConfigManager.getHeartbeatIntervalMs(this) / 1000;
        tvHeartbeat.setText(hb ? ("运行中（" + intervalSec + "s/次）") : "已关闭");

        String key = ConfigManager.getApiKey(this);
        boolean keyOn = ConfigManager.isApiKeyEnabled(this);
        if (key == null || key.isEmpty()) {
            tvKeyValue.setText(keyOn ? "已开启但未设置密钥" : "未设置");
        } else {
            tvKeyValue.setText(maskKey(key));
        }
    }

    /**
     * 面板上的密钥只显示头尾。
     *
     * <p>悬浮球是覆盖在任何应用之上的窗口，完整密钥一览无余地摊在屏幕上，
     * 旁边的人一眼就能抄走；完整值走「复制密钥」进剪贴板，需要时再粘贴。</p>
     */
    private static String maskKey(String key) {
        if (key.length() <= 10) {
            return key;
        }
        return key.substring(0, 6) + "…" + key.substring(key.length() - 4);
    }

    private JSONObject fetchStatus() {
        int port = ConfigManager.getPort(this);
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/status")
                    .openConnection();
            conn.setConnectTimeout(1200);
            conn.setReadTimeout(1500);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false);
            String key = ConfigManager.getApiKey(this);
            if (ConfigManager.isApiKeyEnabled(this) && key != null && !key.trim().isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + key.trim());
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }
            in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void copyApiKey() {
        String key = ConfigManager.getApiKey(this);
        if (key == null || key.isEmpty()) {
            toast("尚未设置密钥，请先点「生成新密钥」");
            return;
        }
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("XiaoBuBridge API Key", key));
                toast("密钥已复制到剪贴板");
            }
        } catch (Throwable t) {
            toast("复制失败");
        }
    }

    private void toast(String message) {
        try {
            Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }
}
