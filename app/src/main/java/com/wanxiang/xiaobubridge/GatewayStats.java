package com.wanxiang.xiaobubridge;

import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 网关运行统计 —— 复刻参照物 j477si.apk（Qwen AppHook）的 {@code GatewayStats}。
 *
 * <p><b>为什么需要它</b>：参照物的首页会展示「运行状态」卡片，内容包含
 * 请求数、失败数、活跃连接、自动重试次数、API 格式、鉴权状态等运行时指标；
 * XiaoBu 原先只有一个「网关是否响应」的布尔探测，用户在面板上看不到任何
 * 运行期信息——出问题时只能靠翻 LSPosed 日志。</p>
 *
 * <p><b>数据流向</b>：计数器在<b>目标进程</b>（小布）里累加，因为 HTTP 服务
 * 跑在那里；而 UI 在<b>模块进程</b>里，两者数据目录隔离。因此本类只负责
 * 「累加 + 导出 JSON」，由 {@link OpenAIServer} 通过 {@code GET /status}
 * 暴露出去，UI 侧 {@link Pages} 再拉取渲染。这与参照物的做法一致。</p>
 *
 * <p>所有计数器都是 {@link AtomicLong}，且本类无实例状态，可安全并发调用。</p>
 */
public final class GatewayStats {

    private GatewayStats() {
    }

    private static final AtomicLong requests = new AtomicLong();
    private static final AtomicLong failed = new AtomicLong();
    private static final AtomicLong clientAborted = new AtomicLong();
    private static final AtomicLong autoRetries = new AtomicLong();
    private static final AtomicLong activeConnections = new AtomicLong();
    private static final AtomicLong injectedRounds = new AtomicLong();
    private static final AtomicLong totalLatencyMs = new AtomicLong();
    private static final ConcurrentHashMap<String, AtomicLong> errorCounters = new ConcurrentHashMap<>();

    private static volatile long startedAt = System.currentTimeMillis();
    private static volatile long lastRequestAt;
    private static volatile String lastError = "";
    private static volatile String listeningHost = "127.0.0.1";
    private static volatile int listeningPort = ConfigManager.DEFAULT_PORT;

    // ==================== 累加接口 ====================

    public static void markListening(String host, int port) {
        if (host != null && !host.isEmpty()) {
            listeningHost = host;
        }
        listeningPort = port;
        startedAt = System.currentTimeMillis();
    }

    public static long incrRequests() {
        lastRequestAt = System.currentTimeMillis();
        return requests.incrementAndGet();
    }

    public static long incrFailed() {
        return failed.incrementAndGet();
    }

    public static long incrClientAborted() {
        return clientAborted.incrementAndGet();
    }

    public static long incrAutoRetries() {
        return autoRetries.incrementAndGet();
    }

    public static long incrInjectedRounds() {
        return injectedRounds.incrementAndGet();
    }

    public static long incrActive() {
        return activeConnections.incrementAndGet();
    }

    public static long decrActive() {
        // 用 CAS 循环而不是直接 decrementAndGet：调用方若因异常路径多减一次，
        // 计数会掉到负数，面板上显示「-1 个活跃连接」比不显示更糟。
        while (true) {
            long cur = activeConnections.get();
            if (cur <= 0) {
                return 0;
            }
            if (activeConnections.compareAndSet(cur, cur - 1)) {
                return cur - 1;
            }
        }
    }

    public static void addLatency(long ms) {
        if (ms > 0) {
            totalLatencyMs.addAndGet(ms);
        }
    }

    /** 记一次具名错误，便于面板上看「最近错误类型」 */
    public static void countError(String code) {
        if (code == null || code.isEmpty()) {
            code = "unknown";
        }
        AtomicLong c = errorCounters.get(code);
        if (c == null) {
            errorCounters.putIfAbsent(code, new AtomicLong());
            c = errorCounters.get(code);
        }
        if (c != null) {
            c.incrementAndGet();
        }
    }

    public static void setLastError(String message) {
        lastError = message == null ? "" : message;
    }

    // ==================== 导出 ====================

    /**
     * 导出快照，供 {@code GET /status} 返回。
     *
     * <p>字段名与参照物保持一致的命名风格（snake_case），便于照搬其客户端
     * 解析逻辑与文档。</p>
     */
    public static JSONObject snapshot() {
        JSONObject o = new JSONObject();
        try {
            o.put("state", "running");
            o.put("host", listeningHost);
            o.put("port", listeningPort);
            o.put("uptime_ms", Math.max(0, System.currentTimeMillis() - startedAt));
            o.put("requests", requests.get());
            o.put("failed", failed.get());
            o.put("client_aborted", clientAborted.get());
            o.put("auto_retries", autoRetries.get());
            o.put("active_connections", activeConnections.get());
            o.put("injected_rounds", injectedRounds.get());
            o.put("avg_latency_ms", requests.get() > 0
                    ? totalLatencyMs.get() / Math.max(1, requests.get()) : 0);
            o.put("last_request_at", lastRequestAt);
            o.put("last_error", lastError);

            // 运行期配置快照：UI 需要展示「服务端实际生效值」，而不是 UI 侧的
            // SharedPreferences —— 两者在配置刚改但小布进程未重启时会不一致。
            o.put("api_format", ConfigManager.getApiFormatInTarget());
            o.put("api_key_set", ConfigManager.isApiKeyEnabledInTarget()
                    && !ConfigManager.getApiKeyInTarget().isEmpty());
            o.put("system_prompt_set", !ConfigManager.getSystemPromptInTarget().trim().isEmpty());
            o.put("max_concurrency", ConfigManager.getMaxConcurrencyInTarget());
            o.put("stream_enabled", ConfigManager.isStreamEnabledInTarget());
            o.put("chunked_stream_enabled", ConfigManager.isChunkedStreamEnabledInTarget());
            o.put("auto_wake_enabled", ConfigManager.isAutoWakeEnabledInTarget());
            o.put("keep_alive_enabled", ConfigManager.isKeepAliveEnabledInTarget());
            o.put("cors_enabled", ConfigManager.isCorsEnabledInTarget());
            o.put("auto_retry_enabled", ConfigManager.isAutoRetryEnabledInTarget());
            o.put("auto_retry_max", ConfigManager.getAutoRetryMaxInTarget());
            o.put("request_timeout_ms", ConfigManager.getRequestTimeoutMsInTarget());
            o.put("version", BuildConfig.VERSION_NAME);

            JSONObject errs = new JSONObject();
            for (Map.Entry<String, AtomicLong> e : errorCounters.entrySet()) {
                errs.put(e.getKey(), e.getValue().get());
            }
            o.put("errors", errs);
        } catch (Throwable t) {
            // 快照序列化绝不能把 /status 打挂
        }
        return o;
    }

    /** 供 {@code /health} 用的极简响应：只有存活与否，供探活轮询高频调用 */
    public static JSONObject health() {
        JSONObject o = new JSONObject();
        try {
            o.put("status", "ok");
            o.put("version", BuildConfig.VERSION_NAME);
            o.put("uptime_ms", Math.max(0, System.currentTimeMillis() - startedAt));
            o.put("active_connections", activeConnections.get());
        } catch (Throwable ignored) {
        }
        return o;
    }

    /** 进程重启后清零（小布被杀后重新注入时调用），避免计数跨进程生命周期累积 */
    public static void reset() {
        requests.set(0);
        failed.set(0);
        clientAborted.set(0);
        autoRetries.set(0);
        activeConnections.set(0);
        injectedRounds.set(0);
        totalLatencyMs.set(0);
        errorCounters.clear();
        startedAt = System.currentTimeMillis();
        lastError = "";
    }
}
