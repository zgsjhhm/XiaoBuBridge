package com.wanxiang.xiaobubridge;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * XiaoBuBridge v2.0 配置面板（纯 Java 构建 Material3 View UI，无布局 XML）。
 *
 * <p>参考 Qwen AppHook 的三页结构，划分为：
 * <ul>
 *   <li>首页 —— 运行状态（AIDL 通道 / 网关探测 / 运行模式 / 目标 App / 作用域）
 *       与设备信息，外加常用操作入口；</li>
 *   <li>设置 —— 服务开关、流式开关、端口、日志级别、API Key、
 *       系统提示、并发上限；</li>
 *   <li>关于 —— 版本、包名、模块说明与 OpenAI 兼容调用示例。</li>
 * </ul>
 * 配置经 {@link ConfigService} 的 AIDL 通道读写，Hook 侧通过
 * {@link ConfigProvider} 读取。</p>
 */
public class MainActivity extends AppCompatActivity {

    private static final String TARGET_PACKAGE = "com.heytap.speechassist";
    private static final String LSPOSED_PACKAGE = "org.lsposed.manager";
    private static final String[] LOG_LEVELS = {"DEBUG", "INFO", "WARN", "ERROR"};

    private static final int TAB_HOME = 0;
    private static final int TAB_SETTINGS = 1;
    private static final int TAB_ABOUT = 2;

    // ---- 首页状态 ----
    private TextView tvStatus;
    private TextView tvGatewayState;
    private TextView tvRunMode;
    private TextView tvTargetApp;
    private TextView tvScopeHint;
    private TextView tvAddressHome;
    private TextView tvDeviceInfo;

    // ---- 设置项 ----
    private SwitchMaterial swServerEnabled;
    private SwitchMaterial swStreamEnabled;
    private SwitchMaterial swApiKeyEnabled;
    private SwitchMaterial swFloatBallEnabled;
    private SwitchMaterial swChunkedStreamEnabled;
    private SwitchMaterial swAutoWakeEnabled;
    private SwitchMaterial swKeepAliveEnabled;
    private TextInputEditText etPort;
    private TextInputEditText etApiKey;
    private TextInputEditText etSystemPrompt;
    private TextInputEditText etMaxConcurrency;
    private Spinner spLogLevel;

    // ---- 页面容器与导航 ----
    private LinearLayout pageContainer;
    private View pageHome;
    private View pageSettings;
    private View pageAbout;
    private MaterialButton tabHome;
    private MaterialButton tabSettings;
    private MaterialButton tabAbout;

    private IConfigService configService;
    private boolean bound;
    private int currentTab = -1;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            configService = IConfigService.Stub.asInterface(service);
            bound = true;
            try {
                configService.ping();
                tvStatus.setText("AIDL 通道：就绪（PID " + android.os.Process.myPid() + "）");
            } catch (Throwable t) {
                tvStatus.setText("AIDL 通道：异常 " + t.getMessage());
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            configService = null;
            bound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildRootView());
        loadConfigToViews();
        selectTab(TAB_HOME);
        refreshStatus();
        // v3.0 悬浮球已改为 Hook 侧自动注入，无需在此启动 Service
        // （UiInjector 在 handleLoadPackage 中钩住 Activity.onResume）
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, ConfigService.class), conn, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        if (bound) {
            unbindService(conn);
            bound = false;
            configService = null;
        }
        super.onStop();
    }

    // ==================== 根布局 ====================

    private View buildRootView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // 顶部标题栏
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        int hpad = dp(20);
        header.setPadding(hpad, dp(16), hpad, dp(10));

        TextView title = new TextView(this);
        title.setText("XiaoBu Bridge");
        title.setTextSize(22f);
        title.setTypeface(null, Typeface.BOLD);
        header.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("把小布助手 AI 对话封装为 OpenAI 兼容本地 API");
        subtitle.setTextSize(12f);
        subtitle.setPadding(0, dp(2), 0, 0);
        header.addView(subtitle);

        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 内容容器
        NestedScrollView scroll = new NestedScrollView(this);
        pageContainer = new LinearLayout(this);
        pageContainer.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        pageContainer.setPadding(pad, dp(4), pad, dp(20));
        scroll.addView(pageContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 底部导航
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(dp(8), dp(6), dp(8), dp(8));

        tabHome = navButton("首页");
        tabSettings = navButton("设置");
        tabAbout = navButton("关于");
        tabHome.setOnClickListener(v -> selectTab(TAB_HOME));
        tabSettings.setOnClickListener(v -> selectTab(TAB_SETTINGS));
        tabAbout.setOnClickListener(v -> selectTab(TAB_ABOUT));

        nav.addView(tabHome, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nav.addView(tabSettings, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nav.addView(tabAbout, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(nav, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 预构建三页（保留实例，切换时只换容器内容）
        pageHome = buildHomePage();
        pageSettings = buildSettingsPage();
        pageAbout = buildAboutPage();

        return root;
    }

    private MaterialButton navButton(String text) {
        MaterialButton btn = new MaterialButton(this);
        btn.setText(text);
        btn.setAllCaps(false);
        return btn;
    }

    private void selectTab(int tab) {
        if (tab == currentTab) return;
        currentTab = tab;
        pageContainer.removeAllViews();
        View page;
        if (tab == TAB_SETTINGS) {
            page = pageSettings;
        } else if (tab == TAB_ABOUT) {
            page = pageAbout;
        } else {
            page = pageHome;
        }
        pageContainer.addView(page, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        highlightTab(tab);
    }

    private void highlightTab(int tab) {
        tabHome.setAlpha(tab == TAB_HOME ? 1f : 0.55f);
        tabSettings.setAlpha(tab == TAB_SETTINGS ? 1f : 0.55f);
        tabAbout.setAlpha(tab == TAB_ABOUT ? 1f : 0.55f);
    }

    // ==================== 首页 ====================

    private View buildHomePage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        // ---- 运行状态 ----
        LinearLayout statusCard = card();
        statusCard.addView(sectionTitle("运行状态"));

        tvStatus = infoLine("AIDL 通道：连接中…");
        statusCard.addView(tvStatus);

        tvGatewayState = infoLine("网关：检测中…");
        statusCard.addView(tvGatewayState);

        tvRunMode = infoLine("运行模式：检测中…");
        statusCard.addView(tvRunMode);

        tvTargetApp = infoLine("目标 App：检测中…");
        statusCard.addView(tvTargetApp);

        tvScopeHint = infoLine("作用域：请在 LSPosed 中确认已勾选小布助手");
        statusCard.addView(tvScopeHint);

        tvAddressHome = infoLine("API 地址：http://127.0.0.1:"
                + ConfigManager.getPort(this) + "/v1");
        statusCard.addView(tvAddressHome);
        page.addView(statusCard);

        // ---- 设备信息 ----
        LinearLayout deviceCard = card();
        deviceCard.addView(sectionTitle("设备信息"));
        tvDeviceInfo = infoLine("");
        deviceCard.addView(tvDeviceInfo);
        page.addView(deviceCard);
        loadDeviceInfo();

        // ---- 操作 ----
        LinearLayout actionCard = card();
        actionCard.addView(sectionTitle("操作"));

        MaterialButton btnRefresh = new MaterialButton(this);
        btnRefresh.setText("刷新状态");
        btnRefresh.setAllCaps(false);
        btnRefresh.setOnClickListener(v -> refreshStatus());
        actionCard.addView(btnRefresh, matchWrap());

        MaterialButton btnCopy = new MaterialButton(this);
        btnCopy.setText("复制 API 地址");
        btnCopy.setAllCaps(false);
        btnCopy.setOnClickListener(v -> copyAddress());
        actionCard.addView(btnCopy, matchWrap());

        MaterialButton btnScope = new MaterialButton(this);
        btnScope.setText("打开 LSPosed 作用域");
        btnScope.setAllCaps(false);
        btnScope.setOnClickListener(v -> openLsposed());
        actionCard.addView(btnScope, matchWrap());

        MaterialButton btnTarget = new MaterialButton(this);
        btnTarget.setText("打开小布助手");
        btnTarget.setAllCaps(false);
        btnTarget.setOnClickListener(v -> openTargetApp());
        actionCard.addView(btnTarget, matchWrap());

        MaterialButton btnFloatBall = new MaterialButton(this);
        btnFloatBall.setText(ConfigManager.isFloatBallEnabled(this) ? "隐藏悬浮球" : "显示悬浮球");
        btnFloatBall.setAllCaps(false);
        btnFloatBall.setOnClickListener(v -> {
            boolean newState = !ConfigManager.isFloatBallEnabled(this);
            ConfigManager.setFloatBallEnabled(this, newState);
            btnFloatBall.setText(newState ? "隐藏悬浮球" : "显示悬浮球");
            toast(newState ? "悬浮球已启用（下次打开小布时显示）" : "悬浮球已隐藏");
        });
        actionCard.addView(btnFloatBall, matchWrap());

        page.addView(actionCard);
        return page;
    }

    private void refreshStatus() {
        tvAddressHome.setText("API 地址：http://127.0.0.1:"
                + ConfigManager.getPort(this) + "/v1");

        boolean targetInstalled = isInstalled(TARGET_PACKAGE);
        tvTargetApp.setText(targetInstalled
                ? "目标 App：已安装（" + TARGET_PACKAGE + "）"
                : "目标 App：未安装（" + TARGET_PACKAGE + "）");

        boolean lsposedInstalled = isInstalled(LSPOSED_PACKAGE);
        boolean hookActive = isHookActive();

        // 关键：MainActivity 只有在 LSPosed 把本模块加载进目标进程后才有意义，
        // 因此 hookActive 恒为 true，用它区分"框架是否工作"没有意义。
        // 真正能反映运行状况的是 Hook 侧网关的响应——网关在跑，就说明
        // LSPosed 注入成功且 Hook 已生效。
        //
        // 另外 isInstalled() 受 Android 11+ 包可见性限制，仅作辅助信息展示，
        // 不再作为"运行模式"的判定依据（否则未声明 <queries> 时会误报未检测到）。
        // 初始文案：探测结果返回前先给出一个保守判断
        if (lsposedInstalled || hookActive) {
            tvRunMode.setText("运行模式：LSPosed 框架（已加载，等待小布启动）");
        } else {
            tvRunMode.setText("运行模式：未检测到 LSPosed 框架");
        }

        tvScopeHint.setText("作用域：" + TARGET_PACKAGE + "（点击「打开 LSPosed 作用域」按钮进行设置）");

        // 网关探测放在后台线程，避免阻塞 UI；探测结果同时驱动「运行模式」显示，
        // 因此两个控件的文案在同一次回调里一起刷新。
        tvGatewayState.setText("网关：检测中…");
        final int port = ConfigManager.getPort(this);
        new Thread(() -> {
            final boolean ok = probeGateway(port);
            mainHandler.post(() -> {
                tvGatewayState.setText(ok
                        ? "网关：运行中（http://127.0.0.1:" + port + "）"
                        : "网关：未响应（请确认小布已启动且作用域已勾选）");
                // 网关判活后再校正运行模式文案
                if (ok) {
                    tvRunMode.setText("运行模式：LSPosed 框架（已激活）");
                } else if (isInstalled(LSPOSED_PACKAGE)) {
                    tvRunMode.setText("运行模式：LSPosed 框架（已加载，等待小布启动）");
                } else {
                    tvRunMode.setText("运行模式：未检测到 LSPosed 框架");
                }
            });
        }, "xiaobu-gateway-probe").start();
    }

    /** 通过本机回环探测 OpenAI 兼容端点是否可用 */
    private boolean probeGateway(int port) {
        // 判活标准：只要 TCP 连得上、能拿回一个合法 HTTP 状态码，就说明
        // 目标进程里的 HttpServer 已在监听。200 / 401 / 403 / 404 都算存活。
        //
        // 旧实现只认 200，一旦用户在设置里开启了 API Key 鉴权，
        // /v1/models 就会返回 401 Invalid API key，于是明明网关正常
        // 也会被误报成"网关未响应"。
        //
        // HTTP Server 可能刚随小布进程启动，故最多重试 3 次，间隔 500ms。
        for (int i = 0; i < 3; i++) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL("http://127.0.0.1:" + port + "/v1/models");
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                conn.setRequestMethod("GET");
                // 关闭自动跟随重定向，避免非 2xx 时抛异常而非返回状态码
                conn.setInstanceFollowRedirects(false);
                int code = conn.getResponseCode();
                if (code > 0) {
                    return true;
                }
            } catch (Throwable t) {
                // 连接被拒绝/超时：服务可能还没起来，短暂等待后重试
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                break;
            }
        }
        return false;
    }

    private void loadDeviceInfo() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        String abi = (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0)
                ? Build.SUPPORTED_ABIS[0] : "未知";
        tvDeviceInfo.setText(
                "品牌：" + safe(Build.BRAND) + "\n"
                        + "型号：" + safe(Build.MODEL) + "\n"
                        + "设备代号：" + safe(Build.DEVICE) + "\n"
                        + "Android 版本：" + safe(Build.VERSION.RELEASE)
                        + "（SDK " + Build.VERSION.SDK_INT + "）\n"
                        + "CPU 架构：" + abi + "\n"
                        + "分辨率：" + dm.widthPixels + " × " + dm.heightPixels + "\n"
                        + "屏幕密度：" + dm.densityDpi + " dpi");
    }

    // ==================== 设置页 ====================

    private View buildSettingsPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        LinearLayout card = card();
        card.addView(sectionTitle("服务设置"));

        swServerEnabled = new SwitchMaterial(this);
        swServerEnabled.setText("启用本地 API 服务");
        card.addView(swServerEnabled, matchWrap());

        swStreamEnabled = new SwitchMaterial(this);
        swStreamEnabled.setText("启用 SSE 流式响应");
        card.addView(swStreamEnabled, matchWrap());

        // v3.6 真流式：HTTP 层 chunked 分片 flush，客户端可边收边显。
        // 关掉则退回 v3.5 行为（攒完整报文一次性返回）。
        swChunkedStreamEnabled = new SwitchMaterial(this);
        swChunkedStreamEnabled.setText("真流式输出（chunked 分片下发）");
        card.addView(swChunkedStreamEnabled, matchWrap());

        TextView chunkedLabel = new TextView(this);
        chunkedLabel.setText("开启后客户端可边收边显；关闭则等整段回答结束才一次性返回");
        chunkedLabel.setTextSize(12f);
        chunkedLabel.setPadding(0, 0, 0, dp(8));
        card.addView(chunkedLabel, matchWrap());

        // v3.6 自动唤醒：请求到达时若小布不在前台，先把它拉起再注入。
        swAutoWakeEnabled = new SwitchMaterial(this);
        swAutoWakeEnabled.setText("请求到达时自动唤醒小布");
        card.addView(swAutoWakeEnabled, matchWrap());

        TextView autoWakeLabel = new TextView(this);
        autoWakeLabel.setText("小布在后台时注入收不到任何回调，必须处于前台才能拿到回答；"
                + "锁屏状态下无法唤起，请求会明确报错而非静默挂死");
        autoWakeLabel.setTextSize(12f);
        autoWakeLabel.setPadding(0, 0, 0, dp(8));
        card.addView(autoWakeLabel, matchWrap());

        // v3.6 保活：拦截小布自身约 90 秒的空闲自杀定时器。
        swKeepAliveEnabled = new SwitchMaterial(this);
        swKeepAliveEnabled.setText("保活（拦截小布的自动退出）");
        card.addView(swKeepAliveEnabled, matchWrap());

        TextView keepAliveLabel = new TextView(this);
        keepAliveLabel.setText("小布空闲约 90 秒会自杀，请求处理到一半进程消失会导致连接被重置；"
                + "开启后小布将常驻后台");
        keepAliveLabel.setTextSize(12f);
        keepAliveLabel.setPadding(0, 0, 0, dp(8));
        card.addView(keepAliveLabel, matchWrap());

        TextInputLayout portLayout = new TextInputLayout(this);
        portLayout.setHint("监听端口（" + ConfigManager.MIN_PORT + "-" + ConfigManager.MAX_PORT + "）");
        etPort = new TextInputEditText(this);
        etPort.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        portLayout.addView(etPort, matchWrap());
        card.addView(portLayout, matchWrap());

        TextInputLayout concurrencyLayout = new TextInputLayout(this);
        concurrencyLayout.setHint("并发上限（" + ConfigManager.MIN_CONCURRENCY
                + "-" + ConfigManager.MAX_CONCURRENCY + "）");
        etMaxConcurrency = new TextInputEditText(this);
        etMaxConcurrency.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        concurrencyLayout.addView(etMaxConcurrency, matchWrap());
        card.addView(concurrencyLayout, matchWrap());

        // v3.0 悬浮球控制面板 —— 无需悬浮窗权限，悬浮球在小布 App 内部显示。
        // 开关仅修改配置；UiInjector 在 Hook 侧钩住 Activity.onResume 时读取此开关。
        swFloatBallEnabled = new SwitchMaterial(this);
        swFloatBallEnabled.setText("启用应用内悬浮球");
        swFloatBallEnabled.setChecked(ConfigManager.isFloatBallEnabled(this));
        swFloatBallEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            toggleFloatBall(isChecked);
        });
        card.addView(swFloatBallEnabled, matchWrap());

        TextView floatBallLabel = new TextView(this);
        floatBallLabel.setText("点击悬浮球快速唤起控制面板");
        floatBallLabel.setTextSize(12f);
        floatBallLabel.setPadding(0, 0, 0, dp(8));
        card.addView(floatBallLabel, matchWrap());

        TextView logLabel = new TextView(this);
        logLabel.setText("日志级别");
        logLabel.setPadding(0, dp(12), 0, dp(4));
        card.addView(logLabel);

        spLogLevel = new Spinner(this);
        ArrayAdapter<String> levelAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, LOG_LEVELS);
        spLogLevel.setAdapter(levelAdapter);
        card.addView(spLogLevel, matchWrap());

        page.addView(card);

        LinearLayout keyCard = card();
        keyCard.addView(sectionTitle("鉴权与提示词"));

        swApiKeyEnabled = new SwitchMaterial(this);
        swApiKeyEnabled.setText("启用 API Key 鉴权");
        keyCard.addView(swApiKeyEnabled, matchWrap());

        TextInputLayout apiKeyLayout = new TextInputLayout(this);
        apiKeyLayout.setHint("API Key（Authorization: Bearer <key>）");
        etApiKey = new TextInputEditText(this);
        apiKeyLayout.addView(etApiKey, matchWrap());
        keyCard.addView(apiKeyLayout, matchWrap());

        TextInputLayout promptLayout = new TextInputLayout(this);
        promptLayout.setHint("系统提示（拼接到用户消息前，可留空）");
        etSystemPrompt = new TextInputEditText(this);
        etSystemPrompt.setMinLines(2);
        etSystemPrompt.setGravity(Gravity.TOP | Gravity.START);
        promptLayout.addView(etSystemPrompt, matchWrap());
        keyCard.addView(promptLayout, matchWrap());

        page.addView(keyCard);

        MaterialButton btnSave = new MaterialButton(this);
        btnSave.setText("保存配置");
        btnSave.setAllCaps(false);
        btnSave.setOnClickListener(v -> saveConfig());
        LinearLayout.LayoutParams saveLp = matchWrap();
        saveLp.topMargin = dp(16);
        page.addView(btnSave, saveLp);

        return page;
    }

    private void loadConfigToViews() {
        etPort.setText(String.valueOf(ConfigManager.getPort(this)));
        etMaxConcurrency.setText(String.valueOf(ConfigManager.getMaxConcurrency(this)));
        etApiKey.setText(ConfigManager.getApiKey(this));
        etSystemPrompt.setText(ConfigManager.getSystemPrompt(this));
        swServerEnabled.setChecked(ConfigManager.isServerEnabled(this));
        swApiKeyEnabled.setChecked(ConfigManager.isApiKeyEnabled(this));
        swStreamEnabled.setChecked(ConfigManager.isStreamEnabled(this));
        swChunkedStreamEnabled.setChecked(ConfigManager.isChunkedStreamEnabled(this));
        swAutoWakeEnabled.setChecked(ConfigManager.isAutoWakeEnabled(this));
        swKeepAliveEnabled.setChecked(ConfigManager.isKeepAliveEnabled(this));
        swFloatBallEnabled.setChecked(ConfigManager.isFloatBallEnabled(this));

        String level = ConfigManager.getLogLevel(this);
        for (int i = 0; i < LOG_LEVELS.length; i++) {
            if (LOG_LEVELS[i].equalsIgnoreCase(level)) {
                spLogLevel.setSelection(i);
                break;
            }
        }
    }

    private void saveConfig() {
        int port;
        try {
            port = Integer.parseInt(String.valueOf(etPort.getText()).trim());
        } catch (Throwable t) {
            toast("端口必须是数字");
            return;
        }
        int clampedPort = ConfigManager.clampPort(port);
        if (clampedPort != port) {
            toast("端口超出范围，已修正为 " + clampedPort);
        }

        int concurrency;
        try {
            concurrency = Integer.parseInt(String.valueOf(etMaxConcurrency.getText()).trim());
        } catch (Throwable t) {
            toast("并发上限必须是数字");
            return;
        }
        int clampedConcurrency = ConfigManager.clampConcurrency(concurrency);
        if (clampedConcurrency != concurrency) {
            toast("并发上限超出范围，已修正为 " + clampedConcurrency);
        }

        ConfigManager.setPort(this, clampedPort);
        ConfigManager.setMaxConcurrency(this, clampedConcurrency);
        ConfigManager.setApiKey(this, String.valueOf(etApiKey.getText()).trim());
        ConfigManager.setSystemPrompt(this, String.valueOf(etSystemPrompt.getText()).trim());
        ConfigManager.setServerEnabled(this, swServerEnabled.isChecked());
        ConfigManager.setApiKeyEnabled(this, swApiKeyEnabled.isChecked());
        ConfigManager.setStreamEnabled(this, swStreamEnabled.isChecked());
        ConfigManager.setFloatBallEnabled(this, swFloatBallEnabled.isChecked());
        ConfigManager.setChunkedStreamEnabled(this, swChunkedStreamEnabled.isChecked());
        ConfigManager.setAutoWakeEnabled(this, swAutoWakeEnabled.isChecked());
        ConfigManager.setKeepAliveEnabled(this, swKeepAliveEnabled.isChecked());
        ConfigManager.setLogLevel(this, String.valueOf(spLogLevel.getSelectedItem()));

        // 回读校验
        etPort.setText(String.valueOf(ConfigManager.getPort(this)));
        etMaxConcurrency.setText(String.valueOf(ConfigManager.getMaxConcurrency(this)));
        refreshStatus();
        toast("配置已保存（端口 " + ConfigManager.getPort(this)
                + "，并发 " + ConfigManager.getMaxConcurrency(this) + "）");
    }

    // ==================== 关于页 ====================

    private View buildAboutPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        LinearLayout infoCard = card();
        infoCard.addView(sectionTitle("关于"));
        infoCard.addView(infoLine("名称：XiaoBu Bridge"));
        infoCard.addView(infoLine("版本：" + BuildConfig.VERSION_NAME
                + "（" + BuildConfig.VERSION_CODE + "）"));
        infoCard.addView(infoLine("包名：" + getPackageName()));
        infoCard.addView(infoLine("模块作用域：" + TARGET_PACKAGE));
        page.addView(infoCard);

        LinearLayout descCard = card();
        descCard.addView(sectionTitle("说明"));
        descCard.addView(infoLine(
                "本模块 Hook 小布助手的 AI 对话链路，把它的回答桥接为"
                        + " OpenAI 兼容的本地 HTTP 接口，供任意支持自定义 Base URL"
                        + " 的客户端调用。"));
        descCard.addView(infoLine(
                "配置在模块内写入，小布进程通过导出的 ContentProvider 只读获取，"
                        + "两端数据目录互相隔离，无需共享存储。"));
        page.addView(descCard);

        LinearLayout usageCard = card();
        usageCard.addView(sectionTitle("调用示例"));
        usageCard.addView(infoLine(
                "Base URL：http://127.0.0.1:" + ConfigManager.getPort(this) + "/v1"));
        usageCard.addView(infoLine(
                "curl http://127.0.0.1:" + ConfigManager.getPort(this)
                        + "/v1/chat/completions \\\n"
                        + "  -H 'Content-Type: application/json' \\\n"
                        + "  -d '{\"model\":\"xiaobu\",\"messages\":[{\"role\":\"user\","
                        + "\"content\":\"你好\"}]}'"));
        page.addView(usageCard);

        return page;
    }

    // ==================== 通用 UI 辅助 ====================

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        box.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF3F3F6);
        bg.setCornerRadius(dp(12));
        box.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        box.setLayoutParams(lp);
        return box;
    }

    private TextView sectionTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16f);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, 0, 0, dp(6));
        return tv;
    }

    private TextView infoLine(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13f);
        tv.setPadding(0, dp(2), 0, dp(2));
        return tv;
    }

    private LinearLayout.LayoutParams matchWrap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        return lp;
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private static String safe(String value) {
        return (value == null || value.isEmpty()) ? "未知" : value;
    }

    // ==================== 操作 ====================

    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 检查 XiaoBu Bridge Hook 是否已激活。
     *
     * 由于 MainActivity 本身是在 LSPosed 加载模块后才能启动，
     * 所以如果此方法被调用，LSPosed 框架必然已存在且模块已被加载。
     * 因此我们不需要检查目标进程是否运行或 XposedBridge 类是否存在，
     * 直接返回 true 即可。
     */
    private boolean isHookActive() {
        // 如果 MainActivity 能运行，说明 LSPosed 已加载本模块，Hook 框架就绪
        return true;
    }

    private void copyAddress() {
        String address = "http://127.0.0.1:" + ConfigManager.getPort(this) + "/v1";
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("XiaoBuBridge API", address));
            toast("已复制：" + address);
        } else {
            toast(address);
        }
    }

    private void openLsposed() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(LSPOSED_PACKAGE);
        if (intent == null) {
            toast("未找到 LSPosed 管理器");
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void openTargetApp() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(TARGET_PACKAGE);
        if (intent == null) {
            toast("未安装小布助手（" + TARGET_PACKAGE + "）");
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    /** v3.0 悬浮球开关 —— 仅修改配置，UiInjector 在 Hook 侧负责实际注入 */
    private void toggleFloatBall(boolean enable) {
        ConfigManager.setFloatBallEnabled(this, enable);
        if (enable) {
            toast("悬浮球已启用，将在下次打开小布时显示");
        } else {
            toast("悬浮球已禁用");
        }
        swFloatBallEnabled.setText(enable ? "隐藏悬浮球" : "显示悬浮球");
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
