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

    /**
     * 在 Hook 侧（目标 App 进程）写入浮球开关配置。
     * 通过 ContentResolver 调用模块导出的 ConfigProvider。
     */
    public static void setFloatBallEnabledInTarget(boolean enabled) {
        Context ctx = targetContext();
        if (ctx == null) {
            XposedBridge.log(TAG + " setFloatBallEnabledInTarget: target context is null");
            return;
        }
        try {
            Uri uri = BASE_URI.buildUpon().appendPath(KEY_FLOAT_BALL_ENABLED).build();
            ContentValues values = new ContentValues();
            values.put("value", String.valueOf(enabled));
            ctx.getContentResolver().insert(uri, values);
            XposedBridge.log(TAG + " setFloatBallEnabledInTarget(" + enabled + ") via ContentProvider");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " setFloatBallEnabledInTarget failed: " + t);

            // Fallback: 直接写入 ContentProvider 的 SharedPreferences
            // 这种方式只在模块进程中有效
            try {
                Context moduleCtx = AndroidAppHelper.currentApplication();
                if (moduleCtx != null) {
                    prefs(moduleCtx).edit().putBoolean(KEY_FLOAT_BALL_ENABLED, enabled).apply();
                    XposedBridge.log(TAG + " setFloatBallEnabledInTarget: fallback to direct prefs write");
                }
            } catch (Throwable t2) {
                XposedBridge.log(TAG + " setFloatBallEnabledInTarget fallback failed: " + t2);
            }
        }
    }
}
