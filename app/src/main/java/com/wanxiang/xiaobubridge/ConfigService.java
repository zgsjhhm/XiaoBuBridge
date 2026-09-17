package com.wanxiang.xiaobubridge;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import org.json.JSONObject;

/**
 * v2.0 配置服务：{@link IConfigService} 的模块进程实现。
 *
 * <p>UI（MainActivity）通过 bindService 以 AIDL 通道读写配置，
 * 保证配置访问统一收口；后续可由 libxposed-service 通道
 * 把小布进程的运行时状态回送到这里。</p>
 */
public class ConfigService extends Service {

    private static volatile ConfigService sInstance;

    /** 同进程内可直接取用的实例（bindService 失败时的兜底） */
    public static ConfigService getInstance() {
        return sInstance;
    }

    private final IConfigService.Stub binder = new IConfigService.Stub() {

        @Override
        public String getConfig(String key) {
            Context ctx = ConfigService.this;
            if (ConfigManager.KEY_PORT.equals(key)) {
                return String.valueOf(ConfigManager.getPort(ctx));
            } else if (ConfigManager.KEY_SERVER_ENABLED.equals(key)) {
                return String.valueOf(ConfigManager.isServerEnabled(ctx));
            } else if (ConfigManager.KEY_API_KEY_ENABLED.equals(key)) {
                return String.valueOf(ConfigManager.isApiKeyEnabled(ctx));
            } else if (ConfigManager.KEY_API_KEY.equals(key)) {
                return ConfigManager.getApiKey(ctx);
            } else if (ConfigManager.KEY_LOG_LEVEL.equals(key)) {
                return ConfigManager.getLogLevel(ctx);
            } else if (ConfigManager.KEY_STREAM_ENABLED.equals(key)) {
                return String.valueOf(ConfigManager.isStreamEnabled(ctx));
            } else if (ConfigManager.KEY_SYSTEM_PROMPT.equals(key)) {
                return ConfigManager.getSystemPrompt(ctx);
            } else if (ConfigManager.KEY_MAX_CONCURRENCY.equals(key)) {
                return String.valueOf(ConfigManager.getMaxConcurrency(ctx));
            } else if (ConfigManager.KEY_FLOAT_BALL_ENABLED.equals(key)) {
                return String.valueOf(ConfigManager.isFloatBallEnabled(ctx));
            } else if (ConfigManager.KEY_CHUNKED_STREAM_ENABLED.equals(key)) {
                return String.valueOf(ConfigManager.isChunkedStreamEnabled(ctx));
            } else if (ConfigManager.KEY_AUTO_WAKE_ENABLED.equals(key)) {
                return String.valueOf(ConfigManager.isAutoWakeEnabled(ctx));
            } else if (ConfigManager.KEY_KEEP_ALIVE_ENABLED.equals(key)) {
                return String.valueOf(ConfigManager.isKeepAliveEnabled(ctx));
            } else if (ConfigManager.KEY_API_FORMAT.equals(key)) {
                return ConfigManager.getApiFormat(ctx);
            } else if (ConfigManager.KEY_CORS_ENABLED.equals(key)) {
                return String.valueOf(ConfigManager.isCorsEnabled(ctx));
            } else if (ConfigManager.KEY_AUTO_RETRY_ENABLED.equals(key)) {
                return String.valueOf(ConfigManager.isAutoRetryEnabled(ctx));
            } else if (ConfigManager.KEY_AUTO_RETRY_MAX.equals(key)) {
                return String.valueOf(ConfigManager.getAutoRetryMax(ctx));
            } else if (ConfigManager.KEY_REQUEST_TIMEOUT_MS.equals(key)) {
                return String.valueOf(ConfigManager.getRequestTimeoutMs(ctx));
            }
            return "";
        }

        @Override
        public void setConfig(String key, String value) {
            Context ctx = ConfigService.this;
            if (ConfigManager.KEY_PORT.equals(key)) {
                try {
                    ConfigManager.setPort(ctx, Integer.parseInt(value.trim()));
                } catch (Throwable ignored) {
                    // 非法端口忽略，保留旧值
                }
            } else if (ConfigManager.KEY_SERVER_ENABLED.equals(key)) {
                ConfigManager.setServerEnabled(ctx, "true".equalsIgnoreCase(value));
            } else if (ConfigManager.KEY_API_KEY_ENABLED.equals(key)) {
                ConfigManager.setApiKeyEnabled(ctx, "true".equalsIgnoreCase(value));
            } else if (ConfigManager.KEY_API_KEY.equals(key)) {
                ConfigManager.setApiKey(ctx, value);
            } else if (ConfigManager.KEY_LOG_LEVEL.equals(key)) {
                ConfigManager.setLogLevel(ctx, value);
            } else if (ConfigManager.KEY_STREAM_ENABLED.equals(key)) {
                ConfigManager.setStreamEnabled(ctx, "true".equalsIgnoreCase(value));
            } else if (ConfigManager.KEY_SYSTEM_PROMPT.equals(key)) {
                ConfigManager.setSystemPrompt(ctx, value);
            } else if (ConfigManager.KEY_MAX_CONCURRENCY.equals(key)) {
                try {
                    ConfigManager.setMaxConcurrency(ctx, Integer.parseInt(value.trim()));
                } catch (Throwable ignored) {
                    // 非法并发值忽略，保留旧值
                }
            } else if (ConfigManager.KEY_FLOAT_BALL_ENABLED.equals(key)) {
                ConfigManager.setFloatBallEnabled(ctx, "true".equalsIgnoreCase(value));
            } else if (ConfigManager.KEY_CHUNKED_STREAM_ENABLED.equals(key)) {
                ConfigManager.setChunkedStreamEnabled(ctx, "true".equalsIgnoreCase(value));
            } else if (ConfigManager.KEY_AUTO_WAKE_ENABLED.equals(key)) {
                ConfigManager.setAutoWakeEnabled(ctx, "true".equalsIgnoreCase(value));
            } else if (ConfigManager.KEY_KEEP_ALIVE_ENABLED.equals(key)) {
                ConfigManager.setKeepAliveEnabled(ctx, "true".equalsIgnoreCase(value));
            } else if (ConfigManager.KEY_API_FORMAT.equals(key)) {
                ConfigManager.setApiFormat(ctx, value);
            } else if (ConfigManager.KEY_CORS_ENABLED.equals(key)) {
                ConfigManager.setCorsEnabled(ctx, "true".equalsIgnoreCase(value));
            } else if (ConfigManager.KEY_AUTO_RETRY_ENABLED.equals(key)) {
                ConfigManager.setAutoRetryEnabled(ctx, "true".equalsIgnoreCase(value));
            } else if (ConfigManager.KEY_AUTO_RETRY_MAX.equals(key)) {
                try {
                    ConfigManager.setAutoRetryMax(ctx, Integer.parseInt(value.trim()));
                } catch (Throwable ignored) {
                    // 非法重试次数忽略，保留旧值
                }
            } else if (ConfigManager.KEY_REQUEST_TIMEOUT_MS.equals(key)) {
                try {
                    ConfigManager.setRequestTimeoutMs(ctx, Integer.parseInt(value.trim()));
                } catch (Throwable ignored) {
                    // 非法超时值忽略，保留旧值
                }
            }
        }

        @Override
        public int getPort() {
            return ConfigManager.getPort(ConfigService.this);
        }

        @Override
        public boolean isServerEnabled() {
            return ConfigManager.isServerEnabled(ConfigService.this);
        }

        @Override
        public String getLogLevel() {
            return ConfigManager.getLogLevel(ConfigService.this);
        }

        @Override
        public String getServerStatus() {
            JSONObject json = new JSONObject();
            try {
                json.put("enabled", ConfigManager.isServerEnabled(ConfigService.this));
                json.put("port", ConfigManager.getPort(ConfigService.this));
                json.put("apiKeyEnabled", ConfigManager.isApiKeyEnabled(ConfigService.this));
                json.put("logLevel", ConfigManager.getLogLevel(ConfigService.this));
                json.put("systemPromptSet", !ConfigManager.getSystemPrompt(ConfigService.this).isEmpty());
                json.put("maxConcurrency", ConfigManager.getMaxConcurrency(ConfigService.this));
                json.put("chunkedStream", ConfigManager.isChunkedStreamEnabled(ConfigService.this));
                json.put("autoWake", ConfigManager.isAutoWakeEnabled(ConfigService.this));
                json.put("keepAlive", ConfigManager.isKeepAliveEnabled(ConfigService.this));
                json.put("apiFormat", ConfigManager.getApiFormat(ConfigService.this));
                json.put("corsEnabled", ConfigManager.isCorsEnabled(ConfigService.this));
                json.put("autoRetry", ConfigManager.isAutoRetryEnabled(ConfigService.this));
                json.put("autoRetryMax", ConfigManager.getAutoRetryMax(ConfigService.this));
                json.put("requestTimeoutMs", ConfigManager.getRequestTimeoutMs(ConfigService.this));
                json.put("pid", android.os.Process.myPid());
                json.put("time", System.currentTimeMillis());
            } catch (Throwable t) {
                return "{\"error\":\"status unavailable\"}";
            }
            return json.toString();
        }

        @Override
        public void ping() {
            // 连通性探针：能走到这里即说明 AIDL 通道正常
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
}
