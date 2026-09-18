package com.wanxiang.xiaobubridge;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import de.robv.android.xposed.XposedBridge;

/**
 * GatewayWatchdog — v3.9 网关看门狗 + 心跳保活。
 *
 * <p><b>为什么要有它</b>：v3.8 之前，网关的启停只在进程冷启动时求值一次
 * （{@code MainHook.startHttpServer()} 里的双重检查锁）。于是两件事做不到：</p>
 * <ul>
 *   <li>悬浮球面板上点「关闭网关」只是改了个配置值，监听 socket 还在；
 *       点「开启」也要等小布下次重启进程才生效；</li>
 *   <li>没有任何东西能发现「网关自以为在跑、其实 socket 已经死了」
 *       （小布进程被系统 doze、socket 被回收）——面板上显示运行中，
 *       客户端却连不上。</li>
 * </ul>
 *
 * <p>本类用一个 1 秒粒度的守护线程承担三件事：</p>
 * <ol>
 *   <li><b>配置跟随</b>：比对 {@code server_enabled} 与 {@link OpenAIServer#isRunning()}，
 *       不一致就调用 {@link OpenAIServer#setEnabled}。这是「悬浮球面板开关网关」
 *       能立即生效的唯一实现方式（模块进程无法回调小布进程，只能轮询）。</li>
 *   <li><b>心跳探测</b>：按配置的间隔向本地 {@code /health} 发一次探活。
 *       拿到 200 记一次 tick；连续失败则判定网关已死并重新拉起。</li>
 *   <li><b>保活</b>：心跳开启期间，向 {@link MainHook} 的自杀拦截器暴露
 *       {@link #shouldBlockKillSelf()}，让「心跳」本身成为一种保活手段，
 *       而不必再单独打开「保活」开关。</li>
 * </ol>
 *
 * <p><b>成本与边界的诚实交代</b>：</p>
 * <ul>
 *   <li>1 秒 tick 只做两次 {@code getBooleanInTarget}（各一次 ContentProvider 查询），
 *       实测开销可忽略；心跳动作本身按 {@code heartbeat_interval_ms} 节流，
 *       默认 60 秒一次，不会持续占电。</li>
 *   <li>心跳<b>不能</b>让被系统强杀的进程复活——本类跑在小布进程内，
 *       进程没了线程也没了。它保的是「进程还在、但小布自己准备退出」
 *       和「socket 死了但对象还在」这两类。<b>进程级复活需要外部拉起，
 *       本模块不做，也不假装能做。</b></li>
 *   <li>心跳开启会周期性拉起小布 Activity（复用 {@link AutoWaker}），
 *       用户会看到小布偶尔出现在前台。这是保活的代价，因此默认关闭。</li>
 * </ul>
 */
public final class GatewayWatchdog {

    private static final String TAG = "[XiaoBuBridge]";

    /** 配置跟随的 tick 粒度：悬浮球上点开关后最多 1 秒生效 */
    private static final long TICK_INTERVAL_MS = 1000L;

    /** 连续多少次心跳失败后判定网关已死并重新拉起 */
    private static final int HEARTBEAT_FAILS_TO_RESTART = 3;

    private static volatile boolean started;
    private static volatile Thread watchdogThread;

    /** 心跳开关的实时快照，供 {@link #shouldBlockKillSelf()} 无锁读取 */
    private static volatile boolean heartbeatEnabled;
    private static volatile long heartbeatIntervalMs = ConfigManager.DEFAULT_HEARTBEAT_INTERVAL_MS;

    private static long lastHeartbeatAt;
    private static int consecutiveHeartbeatFails;

    private GatewayWatchdog() {
    }

    /** 幂等启动；重复调用直接返回 */
    public static synchronized void startOnce() {
        if (started) {
            return;
        }
        started = true;

        watchdogThread = new Thread(GatewayWatchdog::loop, "xiaobu-gateway-watchdog");
        watchdogThread.setDaemon(true);
        watchdogThread.start();
        XposedBridge.log(TAG + " GatewayWatchdog started (tick=" + TICK_INTERVAL_MS + "ms)");
    }

    private static void loop() {
        while (true) {
            try {
                tick();
            } catch (Throwable t) {
                // 守护线程绝不能因单次异常退出：退出后网关开关与心跳会永久停在最后一次状态，
                // 面板上却仍显示「运行中」，比直接崩掉更难排查。
                XposedBridge.log(TAG + " GatewayWatchdog tick error: " + t);
            }
            try {
                Thread.sleep(TICK_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void tick() {
        // ---- 1) 网关启停跟随配置 ----
        boolean wantRunning = ConfigManager.isServerEnabledInTarget();
        OpenAIServer server = MainHook.getHttpServer();
        if (server != null) {
            if (wantRunning != server.isRunning()) {
                XposedBridge.log(TAG + " [watchdog] gateway " + (wantRunning ? "enable" : "disable")
                        + " requested by config");
                server.setEnabled(wantRunning);
            }
        } else if (wantRunning) {
            // 冷启动时 startHttpServer 可能因 ConfigProvider 尚未就绪而放弃，
            // 这里负责补拉起。
            MainHook.ensureHttpServer();
        }

        // ---- 2) 心跳开关快照 ----
        heartbeatEnabled = ConfigManager.isHeartbeatEnabledInTarget();
        heartbeatIntervalMs = ConfigManager.getHeartbeatIntervalMsInTarget();

        if (!heartbeatEnabled) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastHeartbeatAt < heartbeatIntervalMs) {
            return;
        }
        lastHeartbeatAt = now;
        beat(server);
    }

    /** 一次心跳：探活 → 失败则自愈 */
    private static void beat(OpenAIServer server) {
        boolean ok = probeHealth();
        GatewayStats.incrHeartbeatTicks();

        if (ok) {
            consecutiveHeartbeatFails = 0;
            return;
        }

        consecutiveHeartbeatFails++;
        GatewayStats.countError("heartbeat_miss");
        XposedBridge.log(TAG + " [heartbeat] health probe failed ("
                + consecutiveHeartbeatFails + "/" + HEARTBEAT_FAILS_TO_RESTART + ")");

        if (consecutiveHeartbeatFails < HEARTBEAT_FAILS_TO_RESTART) {
            return;
        }
        consecutiveHeartbeatFails = 0;

        // 网关自愈：先停再起，避免旧 socket 仍占着端口时 bind 直接失败。
        // （setEnabled 内部只在状态变化时动作，所以这里必须先 stop 再 enable。）
        if (server != null) {
            XposedBridge.log(TAG + " [heartbeat] restarting gateway after repeated failures");
            server.setEnabled(false);
            server.setEnabled(ConfigManager.isServerEnabledInTarget());
        } else {
            MainHook.ensureHttpServer();
        }

        // 会话保活：小布退到后台后 /health 仍可能正常（HTTP 服务在进程内），
        // 真正会失效的是「前台窗口」。心跳顺带把它拉回来，与 AutoWaker 同语义。
        if (ConfigManager.isAutoWakeEnabledInTarget()) {
            boolean fg = AutoWaker.ensureForeground();
            XposedBridge.log(TAG + " [heartbeat] auto-wake result=" + fg);
        }
    }

    /** GET /health；拿到 2xx 记为成功。鉴权开启时 /health 不鉴权（见 OpenAIServer） */
    private static boolean probeHealth() {
        int port = ConfigManager.getPortInTarget();
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/health")
                    .openConnection();
            conn.setConnectTimeout(1200);
            conn.setReadTimeout(1500);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return false;
            }
            // 必须把响应体读完，否则连接不会回到池里，长期下来会耗光本地端口
            in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8).contains("\"ok\"");
        } catch (Throwable t) {
            return false;
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

    /**
     * 是否应当拦截小布的「空闲自杀」。
     *
     * <p>两种开关都能让它为真：显式的「保活」开关，或「心跳保活」——
     * 心跳的语义本身就包含保活，否则用户打开心跳却发现小布仍会退出，
     * 只能算是半个功能。</p>
     */
    public static boolean shouldBlockKillSelf() {
        if (heartbeatEnabled) {
            return true;
        }
        try {
            return ConfigManager.isKeepAliveEnabledInTarget();
        } catch (Throwable t) {
            return false;
        }
    }
}
