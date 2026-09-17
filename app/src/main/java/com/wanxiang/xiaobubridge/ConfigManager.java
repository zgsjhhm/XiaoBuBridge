package com.wanxiang.xiaobubridge;

import android.app.AndroidAppHelper;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;

import de.robv.android.xposed.XposedBridge;

/**
 * XiaoBuBridge v2.0 统一配置中心。
 *
 * <p>双通道设计（模块与小布是两个独立应用，数据目录互相隔离，
 * SharedPreferences 无法跨应用共享）：
 * <ul>
 *   <li>UI 侧（模块自身进程）：直接读写模块 SharedPreferences；</li>
 *   <li>Hook 侧（小布进程）：通过模块导出的 {@link ConfigProvider}
 *       以 ContentResolver 读取配置。</li>
 * </ul>
 */
public final class ConfigManager {

    private static final String TAG = "[XiaoBuBridge]";

    /** 模块 SharedPreferences 文件名 */
    public static final String PREFS_NAME = "xiaobu_bridge_config";

    /** ConfigProvider 的 authority，须与 AndroidManifest 声明一致 */
    public static final String AUTHORITY = "com.wanxiang.xiaobubridge.config";

    /** Provider 基础 URI */
    public static final Uri BASE_URI = Uri.parse("content://" + AUTHORITY + "/config");

    // ==================== 配置键 ====================

    public static final String KEY_SERVER_ENABLED = "server_enabled";
    public static final String KEY_PORT = "server_port";
    public static final String KEY_API_KEY_ENABLED = "api_key_enabled";
    public static final String KEY_API_KEY = "api_key";
    public static final String KEY_LOG_LEVEL = "log_level";
    public static final String KEY_STREAM_ENABLED = "stream_enabled";
    public static final String KEY_SYSTEM_PROMPT = "system_prompt";
    public static final String KEY_MAX_CONCURRENCY = "max_concurrency";
    /** v3.0 悬浮球开关 */
    public static final String KEY_FLOAT_BALL_ENABLED = "float_ball_enabled";
    /** v3.6 请求到达时自动唤醒小布（小布后台时收不到任何回调，必须先拉到前台） */
    public static final String KEY_AUTO_WAKE_ENABLED = "auto_wake_enabled";
    /** v3.6 保活：拦截小布自身的空闲自杀定时器，避免请求处理到一半进程消失 */
    public static final String KEY_KEEP_ALIVE_ENABLED = "keep_alive_enabled";
    /** v3.6 真流式：HTTP 层 chunked 分片 flush，客户端可边收边显 */
    public static final String KEY_CHUNKED_STREAM_ENABLED = "chunked_stream_enabled";

    // ---- v3.7 网关能力（对齐参照物 Qwen AppHook 的 ControlSurface） ----

    /** API 格式：openai（/v1/chat/completions）| anthropic（/v1/messages）| both */
    public static final String KEY_API_FORMAT = "api_format";
    /** 是否下发 Access-Control-Allow-* 跨域响应头 */
    public static final String KEY_CORS_ENABLED = "cors_enabled";
    /** 注入后零回调时是否自动重试 */
    public static final String KEY_AUTO_RETRY_ENABLED = "auto_retry_enabled";
    /** 自动重试次数上限 */
    public static final String KEY_AUTO_RETRY_MAX = "auto_retry_max";
    /** 单次请求等待回答的超时（毫秒） */
    public static final String KEY_REQUEST_TIMEOUT_MS = "request_timeout_ms";

    // ==================== 默认值 ====================

    public static final boolean DEFAULT_SERVER_ENABLED = true;
    public static final int DEFAULT_PORT = 9876;
    public static final boolean DEFAULT_API_KEY_ENABLED = false;
    public static final String DEFAULT_API_KEY = "";
    public static final String DEFAULT_LOG_LEVEL = "INFO";
    public static final boolean DEFAULT_STREAM_ENABLED = false;
    public static final String DEFAULT_SYSTEM_PROMPT = "";
    public static final int DEFAULT_MAX_CONCURRENCY = 2;
    /** v3.0 悬浮球默认关闭 */
    public static final boolean DEFAULT_FLOAT_BALL_ENABLED = false;
    /** v3.6 自动唤醒默认开启：不开的话每次调用前都得手动解锁并打开小布 */
    public static final boolean DEFAULT_AUTO_WAKE_ENABLED = true;
    /** v3.6 保活默认关闭：会让小布常驻，耗电与可见性先由用户确认 */
    public static final boolean DEFAULT_KEEP_ALIVE_ENABLED = false;
    /** v3.6 真流式默认开启 */
    public static final boolean DEFAULT_CHUNKED_STREAM_ENABLED = true;

    /** v3.7 默认同时开放 OpenAI 之外的兼容格式（照参照物 both 行为） */
    public static final String API_FORMAT_OPENAI = "openai";
    public static final String API_FORMAT_ANTHROPIC = "anthropic";
    public static final String API_FORMAT_BOTH = "both";
    public static final String DEFAULT_API_FORMAT = API_FORMAT_BOTH;
    /** v3.7 默认允许跨域：本地调试时浏览器直连需要它 */
    public static final boolean DEFAULT_CORS_ENABLED = true;
    /** v3.7 默认开启自动重试：注入零回调是概率事件，重试一次能显著提高成功率 */
    public static final boolean DEFAULT_AUTO_RETRY_ENABLED = true;
    public static final int DEFAULT_AUTO_RETRY_MAX = 1;
    public static final int MIN_AUTO_RETRY_MAX = 0;
    public static final int MAX_AUTO_RETRY_MAX = 5;
    /** v3.7 默认单次请求等待上限：与旧版 waitForActiveSession 的 240*250ms 对齐 */
    public static final int DEFAULT_REQUEST_TIMEOUT_MS = 60000;
    public static final int MIN_REQUEST_TIMEOUT_MS = 10000;
    public static final int MAX_REQUEST_TIMEOUT_MS = 300000;

    /** 端口合法区间（避开特权端口） */
    public static final int MIN_PORT = 1024;
    public static final int MAX_PORT = 65535;

    /** 并发上限合法区间 */
    public static final int MIN_CONCURRENCY = 1;
    public static final int MAX_CONCURRENCY = 16;

    private ConfigManager() {
    }

    /** 端口越界时收敛到合法区间 */
    public static int clampPort(int port) {
        if (port < MIN_PORT) return MIN_PORT;
        if (port > MAX_PORT) return MAX_PORT;
        return port;
    }

    /** 并发上限越界时收敛到合法区间 */
    public static int clampConcurrency(int value) {
        if (value < MIN_CONCURRENCY) return MIN_CONCURRENCY;
        if (value > MAX_CONCURRENCY) return MAX_CONCURRENCY;
        return value;
    }

    /** 重试次数越界时收敛 */
    public static int clampAutoRetryMax(int value) {
        if (value < MIN_AUTO_RETRY_MAX) return MIN_AUTO_RETRY_MAX;
        if (value > MAX_AUTO_RETRY_MAX) return MAX_AUTO_RETRY_MAX;
        return value;
    }

    /** 请求超时越界时收敛 */
    public static int clampRequestTimeoutMs(int value) {
        if (value < MIN_REQUEST_TIMEOUT_MS) return MIN_REQUEST_TIMEOUT_MS;
        if (value > MAX_REQUEST_TIMEOUT_MS) return MAX_REQUEST_TIMEOUT_MS;
        return value;
    }

    /** API 格式只接受白名单值，其余一律回退默认（避免 UI 侧写入脏值导致路由失效） */
    public static String normalizeApiFormat(String raw) {
        if (API_FORMAT_OPENAI.equals(raw) || API_FORMAT_ANTHROPIC.equals(raw)
                || API_FORMAT_BOTH.equals(raw)) {
            return raw;
        }
        return DEFAULT_API_FORMAT;
    }

    /**
     * 全部配置键。
     *
     * <p>供两处使用：设置页展示条目数；{@code ConfigProvider} 全量导出路径
     * 做白名单校验。</p>
     */
    public static final String[] ALL_KEYS = new String[]{
            KEY_SERVER_ENABLED, KEY_PORT, KEY_API_KEY_ENABLED, KEY_API_KEY,
            KEY_LOG_LEVEL, KEY_STREAM_ENABLED, KEY_SYSTEM_PROMPT, KEY_MAX_CONCURRENCY,
            KEY_FLOAT_BALL_ENABLED, KEY_AUTO_WAKE_ENABLED, KEY_KEEP_ALIVE_ENABLED,
            KEY_CHUNKED_STREAM_ENABLED, KEY_API_FORMAT, KEY_CORS_ENABLED,
            KEY_AUTO_RETRY_ENABLED, KEY_AUTO_RETRY_MAX, KEY_REQUEST_TIMEOUT_MS,
    };

    // ==================== UI 侧（模块进程） ====================

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isServerEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_SERVER_ENABLED, DEFAULT_SERVER_ENABLED);
    }

    public static void setServerEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_SERVER_ENABLED, enabled).apply();
    }

    public static int getPort(Context ctx) {
        return clampPort(prefs(ctx).getInt(KEY_PORT, DEFAULT_PORT));
    }

    public static void setPort(Context ctx, int port) {
        prefs(ctx).edit().putInt(KEY_PORT, clampPort(port)).apply();
    }

    public static boolean isApiKeyEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_API_KEY_ENABLED, DEFAULT_API_KEY_ENABLED);
    }

    public static void setApiKeyEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_API_KEY_ENABLED, enabled).apply();
    }

    public static String getApiKey(Context ctx) {
        return prefs(ctx).getString(KEY_API_KEY, DEFAULT_API_KEY);
    }

    public static void setApiKey(Context ctx, String apiKey) {
        prefs(ctx).edit().putString(KEY_API_KEY, apiKey == null ? "" : apiKey).apply();
    }

    public static String getLogLevel(Context ctx) {
        return prefs(ctx).getString(KEY_LOG_LEVEL, DEFAULT_LOG_LEVEL);
    }

    public static void setLogLevel(Context ctx, String level) {
        prefs(ctx).edit()
                .putString(KEY_LOG_LEVEL, level == null ? DEFAULT_LOG_LEVEL : level)
                .apply();
    }

    public static boolean isStreamEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_STREAM_ENABLED, DEFAULT_STREAM_ENABLED);
    }

    public static void setStreamEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_STREAM_ENABLED, enabled).apply();
    }

    public static String getSystemPrompt(Context ctx) {
        return prefs(ctx).getString(KEY_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT);
    }

    public static void setSystemPrompt(Context ctx, String prompt) {
        prefs(ctx).edit()
                .putString(KEY_SYSTEM_PROMPT, prompt == null ? "" : prompt)
                .apply();
    }

    public static int getMaxConcurrency(Context ctx) {
        return clampConcurrency(prefs(ctx).getInt(KEY_MAX_CONCURRENCY, DEFAULT_MAX_CONCURRENCY));
    }

    public static void setMaxConcurrency(Context ctx, int value) {
        prefs(ctx).edit().putInt(KEY_MAX_CONCURRENCY, clampConcurrency(value)).apply();
    }

    // ==================== v3.0 悬浮球配置 ====================

    public static boolean isFloatBallEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_FLOAT_BALL_ENABLED, DEFAULT_FLOAT_BALL_ENABLED);
    }

    public static void setFloatBallEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_FLOAT_BALL_ENABLED, enabled).apply();
    }

    // ==================== v3.6 运行期优化配置 ====================

    public static boolean isAutoWakeEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_AUTO_WAKE_ENABLED, DEFAULT_AUTO_WAKE_ENABLED);
    }

    public static void setAutoWakeEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_WAKE_ENABLED, enabled).apply();
    }

    public static boolean isKeepAliveEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_KEEP_ALIVE_ENABLED, DEFAULT_KEEP_ALIVE_ENABLED);
    }

    public static void setKeepAliveEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_KEEP_ALIVE_ENABLED, enabled).apply();
    }

    public static boolean isChunkedStreamEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_CHUNKED_STREAM_ENABLED, DEFAULT_CHUNKED_STREAM_ENABLED);
    }

    public static void setChunkedStreamEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_CHUNKED_STREAM_ENABLED, enabled).apply();
    }

    // ==================== v3.7 网关能力配置（UI 侧） ====================

    public static String getApiFormat(Context ctx) {
        return normalizeApiFormat(prefs(ctx).getString(KEY_API_FORMAT, DEFAULT_API_FORMAT));
    }

    public static void setApiFormat(Context ctx, String format) {
        prefs(ctx).edit().putString(KEY_API_FORMAT, normalizeApiFormat(format)).apply();
    }

    public static boolean isCorsEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_CORS_ENABLED, DEFAULT_CORS_ENABLED);
    }

    public static void setCorsEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_CORS_ENABLED, enabled).apply();
    }

    public static boolean isAutoRetryEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_AUTO_RETRY_ENABLED, DEFAULT_AUTO_RETRY_ENABLED);
    }

    public static void setAutoRetryEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_RETRY_ENABLED, enabled).apply();
    }

    public static int getAutoRetryMax(Context ctx) {
        return clampAutoRetryMax(prefs(ctx).getInt(KEY_AUTO_RETRY_MAX, DEFAULT_AUTO_RETRY_MAX));
    }

    public static void setAutoRetryMax(Context ctx, int value) {
        prefs(ctx).edit().putInt(KEY_AUTO_RETRY_MAX, clampAutoRetryMax(value)).apply();
    }

    public static int getRequestTimeoutMs(Context ctx) {
        return clampRequestTimeoutMs(
                prefs(ctx).getInt(KEY_REQUEST_TIMEOUT_MS, DEFAULT_REQUEST_TIMEOUT_MS));
    }

    public static void setRequestTimeoutMs(Context ctx, int value) {
        prefs(ctx).edit().putInt(KEY_REQUEST_TIMEOUT_MS, clampRequestTimeoutMs(value)).apply();
    }

    // ==================== Hook 侧（小布进程） ====================

    /** 目标 App 进程内的 Context；Xposed 框架注入，失败返回 null */
    private static Context targetContext() {
        try {
            return AndroidAppHelper.currentApplication();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 跨应用读取一项配置；任何异常都回退默认值，绝不让 Hook 侧崩溃 */
    public static String getStringInTarget(String key, String defaultValue) {
        Context ctx = targetContext();
        if (ctx == null) return defaultValue;

        Cursor cursor = null;
        try {
            cursor = ctx.getContentResolver().query(
                    BASE_URI.buildUpon().appendPath(key).build(),
                    null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(ConfigProvider.COLUMN_VALUE);
                if (idx >= 0) {
                    String value = cursor.getString(idx);
                    if (value != null) return value;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " getStringInTarget(" + key + ") failed: " + t);
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return defaultValue;
    }

    public static boolean getBooleanInTarget(String key, boolean defaultValue) {
        String raw = getStringInTarget(key, String.valueOf(defaultValue));
        return "true".equalsIgnoreCase(raw.trim());
    }

    public static int getPortInTarget() {
        String raw = getStringInTarget(KEY_PORT, String.valueOf(DEFAULT_PORT));
        try {
            return clampPort(Integer.parseInt(raw.trim()));
        } catch (Throwable t) {
            return DEFAULT_PORT;
        }
    }

    public static boolean isServerEnabledInTarget() {
        return getBooleanInTarget(KEY_SERVER_ENABLED, DEFAULT_SERVER_ENABLED);
    }

    public static boolean isApiKeyEnabledInTarget() {
        return getBooleanInTarget(KEY_API_KEY_ENABLED, DEFAULT_API_KEY_ENABLED);
    }

    public static String getApiKeyInTarget() {
        return getStringInTarget(KEY_API_KEY, DEFAULT_API_KEY);
    }

    public static String getLogLevelInTarget() {
        return getStringInTarget(KEY_LOG_LEVEL, DEFAULT_LOG_LEVEL);
    }

    public static boolean isStreamEnabledInTarget() {
        return getBooleanInTarget(KEY_STREAM_ENABLED, DEFAULT_STREAM_ENABLED);
    }

    public static String getSystemPromptInTarget() {
        return getStringInTarget(KEY_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT);
    }

    public static int getMaxConcurrencyInTarget() {
        String raw = getStringInTarget(KEY_MAX_CONCURRENCY, String.valueOf(DEFAULT_MAX_CONCURRENCY));
        try {
            return clampConcurrency(Integer.parseInt(raw.trim()));
        } catch (Throwable t) {
            return DEFAULT_MAX_CONCURRENCY;
        }
    }

    public static boolean isFloatBallEnabledInTarget() {
        return getBooleanInTarget(KEY_FLOAT_BALL_ENABLED, DEFAULT_FLOAT_BALL_ENABLED);
    }

    public static boolean isAutoWakeEnabledInTarget() {
        return getBooleanInTarget(KEY_AUTO_WAKE_ENABLED, DEFAULT_AUTO_WAKE_ENABLED);
    }

    public static boolean isKeepAliveEnabledInTarget() {
        return getBooleanInTarget(KEY_KEEP_ALIVE_ENABLED, DEFAULT_KEEP_ALIVE_ENABLED);
    }

    public static boolean isChunkedStreamEnabledInTarget() {
        return getBooleanInTarget(KEY_CHUNKED_STREAM_ENABLED, DEFAULT_CHUNKED_STREAM_ENABLED);
    }

    // ==================== v3.7 网关能力配置（Hook 侧） ====================

    public static String getApiFormatInTarget() {
        return normalizeApiFormat(getStringInTarget(KEY_API_FORMAT, DEFAULT_API_FORMAT));
    }

    public static boolean isCorsEnabledInTarget() {
        return getBooleanInTarget(KEY_CORS_ENABLED, DEFAULT_CORS_ENABLED);
    }

    public static boolean isAutoRetryEnabledInTarget() {
        return getBooleanInTarget(KEY_AUTO_RETRY_ENABLED, DEFAULT_AUTO_RETRY_ENABLED);
    }

    public static int getAutoRetryMaxInTarget() {
        String raw = getStringInTarget(KEY_AUTO_RETRY_MAX, String.valueOf(DEFAULT_AUTO_RETRY_MAX));
        try {
            return clampAutoRetryMax(Integer.parseInt(raw.trim()));
        } catch (Throwable t) {
            return DEFAULT_AUTO_RETRY_MAX;
        }
    }

    public static int getRequestTimeoutMsInTarget() {
        String raw = getStringInTarget(KEY_REQUEST_TIMEOUT_MS,
                String.valueOf(DEFAULT_REQUEST_TIMEOUT_MS));
        try {
            return clampRequestTimeoutMs(Integer.parseInt(raw.trim()));
        } catch (Throwable t) {
            return DEFAULT_REQUEST_TIMEOUT_MS;
        }
    }

    /**
     * Hook 侧写入一项配置：通过 ContentResolver 写回模块进程的 ConfigProvider。
     *
     * <p>失败时只记日志，<b>不</b>回退到 {@code AndroidAppHelper.currentApplication()}
     * —— 那个 Context 在小布进程里指向的是小布自己，用它的 SharedPreferences
     * 写模块的键等于写进小布的私有目录，模块永远读不到，只会用「成功」的假象
     * 掩盖真实的写入失败。</p>
     *
     * @return 是否写入成功
     */
    public static boolean setStringInTarget(String key, String value) {
        Context ctx = targetContext();
        if (ctx == null) {
            XposedBridge.log(TAG + " setStringInTarget(" + key + "): target context is null");
            return false;
        }
        try {
            Uri uri = BASE_URI.buildUpon().appendPath(key).build();
            ContentValues values = new ContentValues();
            values.put("value", value == null ? "" : value);
            ctx.getContentResolver().insert(uri, values);
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " setStringInTarget(" + key + "=" + value + ") failed: " + t);
            return false;
        }
    }

    /** Hook 侧写入布尔配置 */
    public static boolean setBooleanInTarget(String key, boolean value) {
        return setStringInTarget(key, String.valueOf(value));
    }

    /** Hook 侧写入浮球开关（供控制面板的「隐藏悬浮球」调用） */
    public static boolean setFloatBallEnabledInTarget(boolean enabled) {
        return setBooleanInTarget(KEY_FLOAT_BALL_ENABLED, enabled);
    }
}
