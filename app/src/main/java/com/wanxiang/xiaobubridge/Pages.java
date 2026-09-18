package com.wanxiang.xiaobubridge;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.google.android.material.switchmaterial.SwitchMaterial;

/**
 * 三页内容 —— 复刻参照物 j477si.apk（Qwen AppHook）的
 * {@code HomeTab} / {@code SettingsTab} / {@code AboutTab}。
 *
 * <p>对照关系：</p>
 * <table>
 *   <tr><th>参照物</th><th>本类</th></tr>
 *   <tr><td>{@code HomeTab}：设备信息 / 运行模式 / 运行状态 / 操作 / 配置目录</td>
 *       <td>{@link HomePage}</td></tr>
 *   <tr><td>{@code SettingsTab}：应用设置 / 网关 / 鉴权 / 提示词</td>
 *       <td>{@link SettingsPage}</td></tr>
 *   <tr><td>{@code AboutTab}：版本 / 致谢 / 交流群 / 功能说明</td>
 *       <td>{@link AboutPage}</td></tr>
 * </table>
 *
 * <p><b>未复刻项</b>：「致谢」「交流群」是参照物作者的署名与群号，与本模块无关，
 * 换成等价的「关于本模块」信息块；参照物的「内置模式（未连接 LSPosed）」也未复刻，
 * 原因见 {@link HomePage#render}。</p>
 */
final class Pages {

    private Pages() {
    }

    // ==================== 首页 ====================

    /**
     * 首页（对应参照物 {@code HomeTab}）。
     *
     * <p>卡片顺序照搬参照物：设备信息 → 运行模式 → 运行状态 → 操作 → 配置目录。</p>
     */
    static final class HomePage {
        final View root;

        // 设备信息
        private final TextView tvBrand;
        private final TextView tvModel;
        private final TextView tvDevice;
        private final TextView tvAndroid;
        private final TextView tvSdk;
        private final TextView tvAbi;
        private final TextView tvDensity;
        private final TextView tvResolution;

        // 运行模式
        private final TextView tvRunMode;
        private final TextView tvScope;

        // 运行状态
        private final TextView tvGatewayChip;
        private final TextView tvAddress;
        private final TextView tvApiFormat;
        private final TextView tvAuth;
        private final TextView tvActive;
        private final TextView tvRequests;
        private final TextView tvToolCalls;
        private final TextView tvLastTools;
        private final TextView tvFailed;
        private final TextView tvLatency;
        private final TextView tvRetries;
        private final TextView tvSystemPrompt;
        private final TextView tvConcurrency;
        private final TextView tvHeartbeat;

        HomePage(Context ctx, Actions actions) {
            LinearLayout page = LinearLayoutHolder.create(ctx);

            // ---------- 设备信息 ----------
            LinearLayout devCard = UIKit.card(ctx);
            devCard.addView(UIKit.sectionTitle(ctx, "设备信息"));
            tvBrand = UIKit.infoRow(devCard, ctx, "品牌", safe(Build.BRAND));
            tvModel = UIKit.infoRow(devCard, ctx, "型号", safe(Build.MODEL));
            tvDevice = UIKit.infoRow(devCard, ctx, "设备代号", safe(Build.DEVICE));
            tvAndroid = UIKit.infoRow(devCard, ctx, "Android 版本", safe(Build.VERSION.RELEASE));
            tvSdk = UIKit.infoRow(devCard, ctx, "SDK", String.valueOf(Build.VERSION.SDK_INT));
            tvAbi = UIKit.infoRow(devCard, ctx, "CPU 架构", firstAbi());
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            tvDensity = UIKit.infoRow(devCard, ctx, "屏幕密度", dm.densityDpi + "dpi");
            tvResolution = UIKit.infoRow(devCard, ctx, "分辨率",
                    dm.widthPixels + " × " + dm.heightPixels);
            page.addView(devCard);

            // ---------- 运行模式 ----------
            LinearLayout modeCard = UIKit.card(ctx);
            modeCard.addView(UIKit.sectionTitle(ctx, "运行模式"));
            tvRunMode = UIKit.infoRow(modeCard, ctx, "框架", "检测中…");
            tvScope = UIKit.infoRow(modeCard, ctx, "作用域", MainActivity.TARGET_PACKAGE);
            page.addView(modeCard);

            // ---------- 运行状态 ----------
            LinearLayout statusCard = UIKit.card(ctx);
            statusCard.addView(UIKit.sectionTitle(ctx, "运行状态"));

            // 网关一行用状态胶囊呈现，比纯文字更快看出是否在跑
            LinearLayout gwRow = new LinearLayout(ctx);
            gwRow.setOrientation(LinearLayout.HORIZONTAL);
            gwRow.setGravity(Gravity.CENTER_VERTICAL);
            gwRow.setPadding(0, UIKit.dp(ctx, 4), 0, UIKit.dp(ctx, 4));
            TextView gwLabel = new TextView(ctx);
            gwLabel.setText("网关");
            gwLabel.setTextSize(UIKit.SP_BODY);
            gwLabel.setTextColor(UIKit.TEXT_SECONDARY);
            gwRow.addView(gwLabel, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            tvGatewayChip = UIKit.statusChip(ctx, "检测中", UIKit.YELLOW);
            gwRow.addView(tvGatewayChip);
            statusCard.addView(gwRow);

            tvAddress = UIKit.infoRow(statusCard, ctx, "API 地址", "—");
            tvApiFormat = UIKit.infoRow(statusCard, ctx, "API 格式", "—");
            tvAuth = UIKit.infoRow(statusCard, ctx, "鉴权", "—");
            UIKit.divider(statusCard, ctx);
            tvActive = UIKit.infoRow(statusCard, ctx, "活跃连接", "—");
            tvRequests = UIKit.infoRow(statusCard, ctx, "累计请求", "—");
            tvToolCalls = UIKit.infoRow(statusCard, ctx, "工具调用请求", "—");
            tvLastTools = UIKit.infoRow(statusCard, ctx, "最近调用", "—");
            tvFailed = UIKit.infoRow(statusCard, ctx, "失败请求", "—");
            tvLatency = UIKit.infoRow(statusCard, ctx, "平均耗时", "—");
            tvRetries = UIKit.infoRow(statusCard, ctx, "自动重试", "—");
            UIKit.divider(statusCard, ctx);
            tvSystemPrompt = UIKit.infoRow(statusCard, ctx, "系统提示", "—");
            tvConcurrency = UIKit.infoRow(statusCard, ctx, "并发上限", "—");
            tvHeartbeat = UIKit.infoRow(statusCard, ctx, "心跳保活", "—");
            page.addView(statusCard);

            // ---------- 操作 ----------
            LinearLayout actionCard = UIKit.card(ctx);
            actionCard.addView(UIKit.sectionTitle(ctx, "操作"));
            actionCard.addView(UIKit.primaryButton(ctx, "刷新状态",
                    v -> actions.onRefresh()),
                    UIKit.matchWrap(ctx, 6f));
            actionCard.addView(UIKit.outlineButton(ctx, "复制 API 地址",
                    v -> actions.onCopyAddress()),
                    UIKit.matchWrap(ctx, 6f));
            actionCard.addView(UIKit.outlineButton(ctx, "打开 LSPosed 作用域",
                    v -> actions.onOpenLsposed()),
                    UIKit.matchWrap(ctx, 6f));
            actionCard.addView(UIKit.outlineButton(ctx, "打开小布助手",
                    v -> actions.onOpenTargetApp()),
                    UIKit.matchWrap(ctx, 6f));
            page.addView(actionCard);

            // ---------- 配置目录（对应参照物的同名卡片） ----------
            LinearLayout cfgCard = UIKit.card(ctx);
            cfgCard.addView(UIKit.sectionTitle(ctx, "配置目录"));
            UIKit.body(cfgCard, ctx, "配置由模块进程写入，小布进程通过导出的 ContentProvider 只读获取：");
            UIKit.mono(cfgCard, ctx,
                    "SharedPreferences\n  " + ConfigManager.PREFS_NAME + ".xml\n\n"
                            + "ContentProvider\n  content://" + ConfigManager.AUTHORITY + "/config");
            UIKit.hint(cfgCard, ctx,
                    "  server_port    - 监听端口\n"
                            + "  api_format     - API 格式\n"
                            + "  api_key        - 鉴权密钥\n"
                            + "  system_prompt  - System Prompt\n"
                            + "  （共 " + ConfigManager.ALL_KEYS.length + " 项，见设置页）",
                    4);
            page.addView(cfgCard);

            this.root = page;
        }

        /** 渲染首页动态部分：设备信息 + 运行模式 + 运行状态 */
        void render(Context ctx, ProbeResult probe) {
            tvRunMode.setText(probe.frameworkText);
            tvScope.setText(MainActivity.TARGET_PACKAGE);

            if (probe.alive) {
                tvGatewayChip.setText("运行中");
                UIKit.tintChip(tvGatewayChip, UIKit.GREEN);
                tvAddress.setText(probe.host + ":" + probe.port);
            } else {
                tvGatewayChip.setText("未响应");
                UIKit.tintChip(tvGatewayChip, UIKit.RED);
                tvAddress.setText("（小布未启动）");
            }

            // 网关不通时统计行一律显示「—」。旧实现无条件赋值，于是「小布被杀」
            // 之后面板仍显示上一轮的数字，看起来像网关还在跑 —— 运行状态卡片
            // 只要有一个数字是陈旧的，整张卡片就不可信了。
            boolean live = probe.alive;

            tvApiFormat.setText(apiFormatLabel(probe.apiFormat));
            tvAuth.setText(probe.apiKeySet ? "已开启 API Key" : "未开启");
            tvActive.setText(live ? String.valueOf(probe.activeConnections) : "—");
            tvRequests.setText(live ? String.valueOf(probe.requests) : "—");
            // 工具调用请求：只有带 tools 的请求才计入，后面跟的是真正回吐的调用数。
            // 两个数放在一起才有诊断价值 —— 「5 次 / 0 调用」说明请求确实带了 tools
            // 但模型没按协议吐调用块，而不是「没人用工具调用」。
            tvToolCalls.setText(live
                    ? (probe.toolRequests + " 次 / " + probe.toolCalls + " 调用") : "—");
            tvLastTools.setText(live && !probe.lastToolNames.isEmpty()
                    ? probe.lastToolNames : "—");
            tvFailed.setText(live ? String.valueOf(probe.failed) : "—");
            tvLatency.setText(live && probe.requests > 0 ? probe.avgLatencyMs + " ms" : "—");
            tvRetries.setText(live ? String.valueOf(probe.autoRetries) : "—");
            tvSystemPrompt.setText(probe.systemPromptSet ? "已设置" : "未设置");
            tvConcurrency.setText(String.valueOf(probe.maxConcurrency));
            tvHeartbeat.setText(probe.heartbeatEnabled
                    ? ("运行中（" + (probe.heartbeatIntervalMs / 1000) + "s/次，"
                        + probe.heartbeatTicks + " 次）")
                    : "已关闭");

            // 设备信息在本模块进程里读取，与 Hook 侧无关，页面构建时已填好；
            // 这里只在首次渲染时兜底一次，避免为空。
            if (tvBrand.getText().length() == 0) {
                tvBrand.setText(safe(Build.BRAND));
            }
        }
    }

    // ==================== 设置页 ====================

    /** 设置页（对应参照物 {@code SettingsTab}），带回填与保存 */
    static final class SettingsPage {
        final View root;

        private final SwitchMaterial swServer;
        private final SwitchMaterial swStream;
        private final SwitchMaterial swChunked;
        private final SwitchMaterial swAutoWake;
        private final SwitchMaterial swKeepAlive;
        private final SwitchMaterial swOverlayBall;
        private final SwitchMaterial swHeartbeat;
        private final SwitchMaterial swApiKey;
        private final SwitchMaterial swCors;
        private final SwitchMaterial swAutoRetry;
        private final EditText etPort;
        private final EditText etConcurrency;
        private final EditText etRetryMax;
        private final EditText etTimeout;
        private final EditText etHeartbeatInterval;
        private final EditText etApiKey;
        private final EditText etSystemPrompt;
        private final Spinner spLogLevel;
        private final TextView[] formatTabs = new TextView[3];
        private String format = ConfigManager.DEFAULT_API_FORMAT;

        SettingsPage(Context ctx, Actions actions) {
            LinearLayout page = LinearLayoutHolder.create(ctx);

            // ---------- 服务设置 ----------
            LinearLayout svc = UIKit.card(ctx);
            svc.addView(UIKit.sectionTitle(ctx, "服务设置"));
            swServer = UIKit.switchRow(svc, ctx, "启用本地 API 服务",
                    "关闭后小布进程不再监听端口", ConfigManager.DEFAULT_SERVER_ENABLED);
            swStream = UIKit.switchRow(svc, ctx, "启用 SSE 流式响应",
                    "响应体按 text/event-stream 组织", ConfigManager.DEFAULT_STREAM_ENABLED);
            swChunked = UIKit.switchRow(svc, ctx, "真流式输出（chunked 分片下发）",
                    "边收边发，客户端可逐字显示；关闭则等整段回答结束再一次性返回",
                    ConfigManager.DEFAULT_CHUNKED_STREAM_ENABLED);
            swAutoWake = UIKit.switchRow(svc, ctx, "请求到达时自动唤醒小布",
                    "小布在后台时注入收不到回调，需先拉到前台；锁屏状态下无法唤起",
                    ConfigManager.DEFAULT_AUTO_WAKE_ENABLED);
            swKeepAlive = UIKit.switchRow(svc, ctx, "保活（拦截小布的自动退出）",
                    "小布空闲约 90 秒会自杀，开启后常驻后台",
                    ConfigManager.DEFAULT_KEEP_ALIVE_ENABLED);
            etPort = UIKit.inputRow(svc, ctx,
                    "监听端口（" + ConfigManager.MIN_PORT + "-" + ConfigManager.MAX_PORT + "）",
                    null, InputType.TYPE_CLASS_NUMBER, false);
            etConcurrency = UIKit.inputRow(svc, ctx,
                    "并发上限（" + ConfigManager.MIN_CONCURRENCY
                            + "-" + ConfigManager.MAX_CONCURRENCY + "）",
                    null, InputType.TYPE_CLASS_NUMBER, false);
            etTimeout = UIKit.inputRow(svc, ctx,
                    "请求超时（毫秒，" + ConfigManager.MIN_REQUEST_TIMEOUT_MS
                            + "-" + ConfigManager.MAX_REQUEST_TIMEOUT_MS + "）",
                    null, InputType.TYPE_CLASS_NUMBER, false);
            page.addView(svc);

            // ---------- 保活与悬浮球 ----------
            LinearLayout alive = UIKit.card(ctx);
            alive.addView(UIKit.sectionTitle(ctx, "保活与悬浮球"));

            swHeartbeat = UIKit.switchRow(alive, ctx, "心跳保活",
                    "周期性探活网关，失联时自动重启监听；同时拦截小布自动退出",
                    ConfigManager.DEFAULT_HEARTBEAT_ENABLED);
            etHeartbeatInterval = UIKit.inputRow(alive, ctx,
                    "心跳间隔（毫秒，" + ConfigManager.MIN_HEARTBEAT_INTERVAL_MS
                            + "-" + ConfigManager.MAX_HEARTBEAT_INTERVAL_MS + "）",
                    null, InputType.TYPE_CLASS_NUMBER, false);

            swOverlayBall = UIKit.switchRow(alive, ctx, "系统悬浮球（需悬浮窗权限）",
                    "任何界面都能点开：网关开关 / API Key / 心跳保活",
                    ConfigManager.DEFAULT_OVERLAY_BALL_ENABLED);

            alive.addView(UIKit.outlineButton(ctx, "授予悬浮窗权限 / 检查系统设置",
                    v -> actions.onRequestOverlayPermission()),
                    UIKit.matchWrap(ctx, 8f));
            alive.addView(UIKit.outlineButton(ctx, "立即启停悬浮球服务",
                    v -> actions.onApplyOverlayBall()),
                    UIKit.matchWrap(ctx, 6f));
            UIKit.hint(alive, ctx,
                    "系统悬浮球由常驻前台服务持有，会显示一条最低优先级的常驻通知。"
                            + "关闭开关会一并停止服务与通知。", 6);
            page.addView(alive);

            // ---------- API 格式 / 兼容性 ----------
            LinearLayout api = UIKit.card(ctx);
            api.addView(UIKit.sectionTitle(ctx, "API 格式与兼容性"));

            // 分段选择（对应参照物的 API 格式切换）
            LinearLayout seg = new LinearLayout(ctx);
            seg.setOrientation(LinearLayout.HORIZONTAL);
            seg.setPadding(0, UIKit.dp(ctx, 2), 0, UIKit.dp(ctx, 6));
            String[] keys = {ConfigManager.API_FORMAT_OPENAI,
                    ConfigManager.API_FORMAT_ANTHROPIC, ConfigManager.API_FORMAT_BOTH};
            String[] labels = {"OpenAI", "Anthropic", "两者"};
            for (int i = 0; i < keys.length; i++) {
                final String key = keys[i];
                TextView tab = new TextView(ctx);
                tab.setText(labels[i]);
                tab.setTextSize(UIKit.SP_BODY);
                tab.setGravity(Gravity.CENTER);
                tab.setPadding(0, UIKit.dp(ctx, 8), 0, UIKit.dp(ctx, 8));
                tab.setOnClickListener(v -> {
                    format = key;
                    applyFormatTabs(ctx);
                });
                formatTabs[i] = tab;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (i > 0) lp.leftMargin = UIKit.dp(ctx, 6);
                seg.addView(tab, lp);
            }
            api.addView(seg, UIKit.matchWrap(ctx, 0f));
            UIKit.hint(api, ctx,
                    "OpenAI：/v1/chat/completions 与 /v1/models\n"
                            + "Anthropic：/v1/messages（兼容 Anthropic SDK）\n"
                            + "两者：同时开放全部端点",
                    6);

            swCors = UIKit.switchRow(api, ctx, "CORS 允许跨域",
                    "下发 Access-Control-Allow-* 头，便于浏览器直连调试",
                    ConfigManager.DEFAULT_CORS_ENABLED);
            swAutoRetry = UIKit.switchRow(api, ctx, "注入零回调时自动重试",
                    "小布偶发不回调，重试一轮通常即可成功",
                    ConfigManager.DEFAULT_AUTO_RETRY_ENABLED);
            etRetryMax = UIKit.inputRow(api, ctx,
                    "重试次数上限（" + ConfigManager.MIN_AUTO_RETRY_MAX
                            + "-" + ConfigManager.MAX_AUTO_RETRY_MAX + "）",
                    null, InputType.TYPE_CLASS_NUMBER, false);
            page.addView(api);

            // ---------- 鉴权与提示词 ----------
            LinearLayout key = UIKit.card(ctx);
            key.addView(UIKit.sectionTitle(ctx, "鉴权与提示词"));
            swApiKey = UIKit.switchRow(key, ctx, "启用 API Key 鉴权",
                    "开启后请求需带 Authorization: Bearer <key>",
                    ConfigManager.DEFAULT_API_KEY_ENABLED);
            etApiKey = UIKit.inputRow(key, ctx, "API Key（点下方按钮可生成/复制）", null, 0, false);

            LinearLayout keyActions = new LinearLayout(ctx);
            keyActions.setOrientation(LinearLayout.HORIZONTAL);
            keyActions.setPadding(0, UIKit.dp(ctx, 8), 0, 0);
            keyActions.addView(UIKit.outlineButton(ctx, "生成新密钥",
                            v -> actions.onGenerateApiKey()),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams copyKeyLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            copyKeyLp.leftMargin = UIKit.dp(ctx, 8);
            keyActions.addView(UIKit.outlineButton(ctx, "复制密钥",
                            v -> actions.onCopyApiKey()),
                    copyKeyLp);
            key.addView(keyActions, UIKit.matchWrap(ctx, 0f));
            UIKit.hint(key, ctx,
                    "「生成新密钥」会同时开启鉴权开关并立即落盘——生成密钥的唯一目的就是用它，"
                            + "不顺手开启只会让人以为没生效。", 4);

            UIKit.divider(key, ctx);
            etSystemPrompt = UIKit.inputRow(key, ctx,
                    "系统提示（拼接到用户消息前，可留空）", null, 0, true);
            // 导入走「读进输入框、再点保存」两步：选错文件不会直接覆盖线上配置
            key.addView(UIKit.outlineButton(ctx, "从文本文件导入",
                            v -> actions.onImportSystemPrompt()),
                    UIKit.matchWrap(ctx, 6f));
            key.addView(UIKit.outlineButton(ctx, "从剪贴板导入",
                            v -> actions.onImportSystemPromptFromClipboard()),
                    UIKit.matchWrap(ctx, 6f));
            UIKit.hint(key, ctx,
                    "导入只是把内容填进上面的输入框，仍需点「保存配置」才落盘。"
                            + "导出：长按输入框全选复制即可。", 6);
            page.addView(key);

            // ---------- 日志 ----------
            LinearLayout log = UIKit.card(ctx);
            log.addView(UIKit.sectionTitle(ctx, "日志"));
            TextView logLabel = new TextView(ctx);
            logLabel.setText("日志级别");
            logLabel.setTextSize(UIKit.SP_HINT);
            logLabel.setTextColor(UIKit.TEXT_SECONDARY);
            logLabel.setPadding(0, UIKit.dp(ctx, 10), 0, UIKit.dp(ctx, 4));
            log.addView(logLabel);
            spLogLevel = new Spinner(ctx);
            spLogLevel.setAdapter(new ArrayAdapter<>(ctx,
                    android.R.layout.simple_spinner_dropdown_item, MainActivity.LOG_LEVELS));
            log.addView(spLogLevel, UIKit.matchWrap(ctx, 0f));
            page.addView(log);

            // ---------- 保存 ----------
            android.widget.Button save = UIKit.primaryButton(ctx, "保存配置",
                    v -> actions.onSaveConfig());
            LinearLayout.LayoutParams saveLp = UIKit.matchWrap(ctx, 16f);
            page.addView(save, saveLp);

            this.root = page;
        }

        /** 从 ConfigManager 回填全部控件 */
        void load(Context ctx) {
            etPort.setText(String.valueOf(ConfigManager.getPort(ctx)));
            etConcurrency.setText(String.valueOf(ConfigManager.getMaxConcurrency(ctx)));
            etTimeout.setText(String.valueOf(ConfigManager.getRequestTimeoutMs(ctx)));
            etRetryMax.setText(String.valueOf(ConfigManager.getAutoRetryMax(ctx)));
            etHeartbeatInterval.setText(String.valueOf(ConfigManager.getHeartbeatIntervalMs(ctx)));
            etApiKey.setText(ConfigManager.getApiKey(ctx));
            etSystemPrompt.setText(ConfigManager.getSystemPrompt(ctx));

            swServer.setChecked(ConfigManager.isServerEnabled(ctx));
            swStream.setChecked(ConfigManager.isStreamEnabled(ctx));
            swChunked.setChecked(ConfigManager.isChunkedStreamEnabled(ctx));
            swAutoWake.setChecked(ConfigManager.isAutoWakeEnabled(ctx));
            swKeepAlive.setChecked(ConfigManager.isKeepAliveEnabled(ctx));
            swOverlayBall.setChecked(ConfigManager.isOverlayBallEnabled(ctx));
            swHeartbeat.setChecked(ConfigManager.isHeartbeatEnabled(ctx));
            swApiKey.setChecked(ConfigManager.isApiKeyEnabled(ctx));
            swCors.setChecked(ConfigManager.isCorsEnabled(ctx));
            swAutoRetry.setChecked(ConfigManager.isAutoRetryEnabled(ctx));

            format = ConfigManager.getApiFormat(ctx);
            applyFormatTabs(ctx);

            String level = ConfigManager.getLogLevel(ctx);
            for (int i = 0; i < MainActivity.LOG_LEVELS.length; i++) {
                if (MainActivity.LOG_LEVELS[i].equalsIgnoreCase(level)) {
                    spLogLevel.setSelection(i);
                    break;
                }
            }
        }

        /** 保存全部控件到 SharedPreferences，返回提示文案 */
        String save(Context ctx) {
            Integer port = parse(etPort);
            if (port == null) return "端口必须是数字";
            Integer concurrency = parse(etConcurrency);
            if (concurrency == null) return "并发上限必须是数字";
            Integer timeout = parse(etTimeout);
            if (timeout == null) return "请求超时必须是数字";
            Integer retryMax = parse(etRetryMax);
            if (retryMax == null) return "重试次数必须是数字";
            Integer heartbeatInterval = parse(etHeartbeatInterval);
            if (heartbeatInterval == null) return "心跳间隔必须是数字";

            int clampedPort = ConfigManager.clampPort(port);
            int clampedConcurrency = ConfigManager.clampConcurrency(concurrency);
            int clampedTimeout = ConfigManager.clampRequestTimeoutMs(timeout);
            int clampedRetry = ConfigManager.clampAutoRetryMax(retryMax);
            int clampedHeartbeat = ConfigManager.clampHeartbeatIntervalMs(heartbeatInterval);

            ConfigManager.setPort(ctx, clampedPort);
            ConfigManager.setMaxConcurrency(ctx, clampedConcurrency);
            ConfigManager.setRequestTimeoutMs(ctx, clampedTimeout);
            ConfigManager.setAutoRetryMax(ctx, clampedRetry);
            ConfigManager.setHeartbeatIntervalMs(ctx, clampedHeartbeat);
            ConfigManager.setApiKey(ctx, text(etApiKey));
            ConfigManager.setSystemPrompt(ctx, text(etSystemPrompt));
            ConfigManager.setApiFormat(ctx, format);
            ConfigManager.setLogLevel(ctx, String.valueOf(spLogLevel.getSelectedItem()));

            ConfigManager.setServerEnabled(ctx, swServer.isChecked());
            ConfigManager.setStreamEnabled(ctx, swStream.isChecked());
            ConfigManager.setChunkedStreamEnabled(ctx, swChunked.isChecked());
            ConfigManager.setAutoWakeEnabled(ctx, swAutoWake.isChecked());
            ConfigManager.setKeepAliveEnabled(ctx, swKeepAlive.isChecked());
            ConfigManager.setOverlayBallEnabled(ctx, swOverlayBall.isChecked());
            ConfigManager.setHeartbeatEnabled(ctx, swHeartbeat.isChecked());
            ConfigManager.setApiKeyEnabled(ctx, swApiKey.isChecked());
            ConfigManager.setCorsEnabled(ctx, swCors.isChecked());
            ConfigManager.setAutoRetryEnabled(ctx, swAutoRetry.isChecked());

            load(ctx);
            return "配置已保存（端口 " + clampedPort + "，并发 " + clampedConcurrency
                    + "，格式 " + format + "）";
        }

        /** 把导入的文本填进系统提示输入框（不落盘，用户仍需点保存） */
        void setSystemPromptText(String text) {
            etSystemPrompt.setText(text == null ? "" : text);
        }

        /** 供「复制密钥」读取当前输入框里的值（可能刚生成还没保存） */
        String currentApiKeyText() {
            return text(etApiKey);
        }

        private void applyFormatTabs(Context ctx) {
            String[] keys = {ConfigManager.API_FORMAT_OPENAI,
                    ConfigManager.API_FORMAT_ANTHROPIC, ConfigManager.API_FORMAT_BOTH};
            for (int i = 0; i < formatTabs.length; i++) {
                boolean on = keys[i].equals(format);
                formatTabs[i].setTextColor(on ? UIKit.WHITE : UIKit.TEXT_SECONDARY);
                formatTabs[i].setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
                android.graphics.drawable.GradientDrawable bg =
                        new android.graphics.drawable.GradientDrawable();
                bg.setCornerRadius(UIKit.dp(ctx, UIKit.R_CONTROL));
                bg.setColor(on ? UIKit.ACCENT : UIKit.INPUT_BG);
                bg.setStroke(Math.max(1, UIKit.dp(ctx, 1)),
                        on ? UIKit.ACCENT : UIKit.INPUT_BORDER);
                formatTabs[i].setBackground(bg);
            }
        }

        private static Integer parse(EditText et) {
            try {
                return Integer.valueOf(Integer.parseInt(text(et).trim()));
            } catch (Throwable t) {
                return null;
            }
        }

        private static String text(EditText et) {
            return et.getText() == null ? "" : et.getText().toString();
        }
    }

    // ==================== 关于页 ====================

    /** 关于页（对应参照物 {@code AboutTab}） */
    static final class AboutPage {
        final View root;

        AboutPage(Context ctx) {
            LinearLayout page = LinearLayoutHolder.create(ctx);

            // ---------- 版本 ----------
            LinearLayout ver = UIKit.card(ctx);
            ver.addView(UIKit.sectionTitle(ctx, "版本"));
            TextView name = new TextView(ctx);
            name.setText("XiaoBu Bridge v" + BuildConfig.VERSION_NAME);
            name.setTextSize(16f);
            name.setTypeface(null, Typeface.BOLD);
            name.setTextColor(UIKit.TEXT_PRIMARY);
            ver.addView(name, UIKit.matchWrap(ctx, 0f));
            UIKit.hint(ver, ctx,
                    "LSPosed 模块，把小布助手的 AI 对话能力封装为 OpenAI / Anthropic "
                            + "兼容的本地 HTTP API，供任意支持自定义 Base URL 的客户端调用。",
                    6);
            UIKit.divider(ver, ctx);
            UIKit.infoRow(ver, ctx, "版本号", BuildConfig.VERSION_NAME
                    + "（" + BuildConfig.VERSION_CODE + "）");
            UIKit.infoRow(ver, ctx, "包名", ctx.getPackageName());
            UIKit.infoRow(ver, ctx, "模块作用域", MainActivity.TARGET_PACKAGE);
            page.addView(ver);

            // ---------- 功能说明（对应参照物的同名卡片） ----------
            LinearLayout usage = UIKit.card(ctx);
            usage.addView(UIKit.sectionTitle(ctx, "功能说明"));
            UIKit.body(usage, ctx,
                    "1. 在 LSPosed 中启用本模块，作用域勾选 " + MainActivity.TARGET_PACKAGE + "\n"
                            + "2. 重启小布助手，右侧出现悬浮球\n"
                            + "3. 点击悬浮球打开控制面板，或直接使用下面的 API\n"
                            + "4. 隐藏悬浮球后，仍可在模块 App 设置页重新开启");
            UIKit.divider(usage, ctx);
            UIKit.hint(usage, ctx,
                    "小布必须处于前台才能应答：后台时注入不会触发任何回调。"
                            + "开启设置页的「请求到达时自动唤醒小布」可自动处理，"
                            + "锁屏状态下则无法唤起。",
                    0);
            page.addView(usage);

            // ---------- 调用示例 ----------
            LinearLayout api = UIKit.card(ctx);
            api.addView(UIKit.sectionTitle(ctx, "调用示例"));

            int port = ConfigManager.getPort(ctx);
            String host = "127.0.0.1";
            UIKit.infoRow(api, ctx, "Base URL", "http://" + host + ":" + port + "/v1");
            UIKit.infoRow(api, ctx, "局域网", "http://" + localIp() + ":" + port + "/v1");

            UIKit.hint(api, ctx, "OpenAI 兼容", 2);
            UIKit.mono(api, ctx,
                    "curl http://" + host + ":" + port + "/v1/chat/completions \\\n"
                            + "  -H 'Content-Type: application/json' \\\n"
                            + "  -d '{\"model\":\"xiaobu\",\"messages\":"
                            + "[{\"role\":\"user\",\"content\":\"你好\"}]}'");

            UIKit.hint(api, ctx, "Anthropic 兼容", 2);
            UIKit.mono(api, ctx,
                    "curl http://" + host + ":" + port + "/v1/messages \\\n"
                            + "  -H 'Content-Type: application/json' \\\n"
                            + "  -d '{\"model\":\"xiaobu\",\"max_tokens\":1024,"
                            + "\"messages\":[{\"role\":\"user\",\"content\":\"你好\"}]}'");

            UIKit.hint(api, ctx, "状态与探活", 2);
            UIKit.mono(api, ctx,
                    "curl http://" + host + ":" + port + "/status\n"
                            + "curl http://" + host + ":" + port + "/health");
            page.addView(api);

            // ---------- 端点清单 ----------
            LinearLayout routes = UIKit.card(ctx);
            routes.addView(UIKit.sectionTitle(ctx, "端点"));
            UIKit.infoRow(routes, ctx, "GET /v1/models", "模型列表");
            UIKit.infoRow(routes, ctx, "POST /v1/chat/completions", "OpenAI 对话");
            UIKit.infoRow(routes, ctx, "POST /v1/messages", "Anthropic 对话");
            UIKit.infoRow(routes, ctx, "POST /v1/completions", "Legacy 文本补全");
            UIKit.infoRow(routes, ctx, "GET /status", "运行统计");
            UIKit.infoRow(routes, ctx, "GET /health", "探活");
            page.addView(routes);

            this.root = page;
        }
    }

    // ==================== 共享支撑 ====================

    /** 页面动作回调，由 MainActivity 实现（需要 Activity 能力：剪贴板/跳转/Toast/文件选择） */
    interface Actions {
        void onRefresh();

        void onCopyAddress();

        void onOpenLsposed();

        void onOpenTargetApp();

        void onSaveConfig();

        // ---- v3.9 ----

        /** 生成一个新 API Key 并填入输入框（同时打开鉴权开关） */
        void onGenerateApiKey();

        /** 复制当前 API Key 到剪贴板 */
        void onCopyApiKey();

        /** 通过系统文件选择器（SAF）导入一个文本文件作为系统提示词 */
        void onImportSystemPrompt();

        /** 从剪贴板导入系统提示词 */
        void onImportSystemPromptFromClipboard();

        /** 跳系统设置页申请「显示在其他应用上层」权限 */
        void onRequestOverlayPermission();

        /** 立即按当前开关启停系统悬浮球服务（不等待保存） */
        void onApplyOverlayBall();
    }

    /** 探测结果：由 MainActivity 从 {@code GET /status} 拉取后填入 */
    static final class ProbeResult {
        boolean alive;
        String host = "127.0.0.1";
        int port = ConfigManager.DEFAULT_PORT;
        String frameworkText = "检测中…";
        String apiFormat = ConfigManager.DEFAULT_API_FORMAT;
        boolean apiKeySet;
        boolean systemPromptSet;
        int maxConcurrency;
        long activeConnections;
        long requests;
        long failed;
        long autoRetries;
        long avgLatencyMs;
        // ---- v3.9 ----
        long toolRequests;
        long toolCalls;
        String lastToolNames = "";
        boolean heartbeatEnabled;
        int heartbeatIntervalMs = ConfigManager.DEFAULT_HEARTBEAT_INTERVAL_MS;
        long heartbeatTicks;
    }

    /** 把内联的竖排 LinearLayout 创建收口，避免每页重复样板 */
    private static final class LinearLayoutHolder {
        static LinearLayout create(Context ctx) {
            LinearLayout page = new LinearLayout(ctx);
            page.setOrientation(LinearLayout.VERTICAL);
            int pad = UIKit.dp(ctx, 16);
            page.setPadding(pad, UIKit.dp(ctx, 4), pad, UIKit.dp(ctx, 24));
            return page;
        }
    }

    private static String apiFormatLabel(String format) {
        if (ConfigManager.API_FORMAT_OPENAI.equals(format)) return "OpenAI";
        if (ConfigManager.API_FORMAT_ANTHROPIC.equals(format)) return "Anthropic";
        if (ConfigManager.API_FORMAT_BOTH.equals(format)) return "OpenAI + Anthropic";
        return format;
    }

    private static String firstAbi() {
        if (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0) {
            return safe(Build.SUPPORTED_ABIS[0]);
        }
        return "未知";
    }

    private static String safe(String value) {
        return (value == null || value.isEmpty()) ? "未知" : value;
    }

    /**
     * 取本机局域网 IPv4。
     *
     * <p>遍历网卡枚举地址；失败返回 "127.0.0.1"，绝不抛异常——
     * 关于页只是展示信息，取不到 IPv4 不该让整页崩掉。</p>
     */
    private static String localIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> nis =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                java.net.NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "127.0.0.1";
    }
}
