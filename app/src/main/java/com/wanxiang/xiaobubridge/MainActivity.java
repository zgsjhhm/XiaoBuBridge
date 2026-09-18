package com.wanxiang.xiaobubridge;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * XiaoBuBridge 配置面板。
 *
 * <p>界面按参照物 j477si.apk（Qwen AppHook）复刻，结构一一对应：</p>
 * <ul>
 *   <li>顶栏 {@code TopAppBar}：标题 + 右侧「刷新」；</li>
 *   <li>内容区：随选中页切换，卡片式分组；</li>
 *   <li>底部导航 {@code NavigationBar}：主页 / 设置 / 关于，带图标与选中药丸。</li>
 * </ul>
 *
 * <p>三个页面的内容由 {@link Pages} 构建，视觉基元来自 {@link UIKit}，
 * 配色取自 {@code res/values/colors.xml} 的 {@code xb_*} 令牌。</p>
 *
 * <p>运行状态不靠猜：{@code GET /status} 由小布进程内的网关返回，字段包含
 * 请求数、失败数、活跃连接、平均耗时、自动重试等真实运行指标。</p>
 */
public class MainActivity extends AppCompatActivity implements Pages.Actions {

    static final String TARGET_PACKAGE = "com.heytap.speechassist";
    static final String LSPOSED_PACKAGE = "org.lsposed.manager";
    static final String[] LOG_LEVELS = {"DEBUG", "INFO", "WARN", "ERROR"};

    private static final int TAB_HOME = 0;
    private static final int TAB_SETTINGS = 1;
    private static final int TAB_ABOUT = 2;

    private static final String[] TAB_LABELS = {"主页", "设置", "关于"};

    /** v3.9 System Prompt 文本导入的请求码 */
    private static final int REQ_IMPORT_PROMPT = 0x5843;
    /** v3.10 通知权限请求码（Android 13+ 前台服务常驻通知） */
    private static final int REQ_NOTIFICATION_PERMISSION = 0x5845;
    /** 导入上限：够放任意提示词，又不至于把 EditText 与 prefs 撑爆 */
    private static final int MAX_IMPORT_BYTES = 64 * 1024;

    private FrameLayout content;
    private LinearLayout navBar;
    private View[] navPills;
    private ImageView[] navIcons;
    private android.widget.TextView[] navLabels;

    private Pages.HomePage homePage;
    private Pages.SettingsPage settingsPage;
    private Pages.AboutPage aboutPage;
    private View[] tabContents;

    private int currentTab = -1;

    private IConfigService configService;
    private boolean bound;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            configService = IConfigService.Stub.asInterface(service);
            bound = true;
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
        settingsPage.load(this);
        selectTab(TAB_HOME);
        refreshStatus();
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, ConfigService.class), conn, Context.BIND_AUTO_CREATE);

        // v3.10：打开模块界面就把悬浮球服务对齐一次配置。
        // 这是「球消失后重开小布没用」最直接的用户可见修复——小布进程与悬浮球
        // 完全无关（球挂在模块进程的前台服务上），所以恢复入口必须在模块这一侧。
        // 幂等：服务已在跑时只是一次空转，不会重复加窗口。
        OverlayBallService.syncState(this);

        // Android 13+ 常驻前台服务通知需要运行时授权；没通知时 ColorOS 更容易把
        // 该进程当普通后台进程回收，悬浮球随之消失。只在需要时问一次。
        ensureNotificationPermission();
    }

    /**
     * 申请通知权限（Android 13+）。
     *
     * <p>权限本身不是悬浮球能显示的必要条件（悬浮窗是 {@code SYSTEM_ALERT_WINDOW}），
     * 但前台服务的常驻通知不可见时，系统对「这个进程在前台服务」的判定会打折扣，
     * 省电策略回收得更积极。因此这里在用户打开模块界面时顺手申请一次，拒绝了也不影响
     * 其余功能。</p>
     */
    private void ensureNotificationPermission() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                return;
            }
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return;
            }
            requestPermissions(
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATION_PERMISSION);
        } catch (Throwable t) {
            BridgeLog.i("[XiaoBuBridge] requestNotifications failed: " + t);
        }
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

    // ==================== 骨架：顶栏 + 内容 + 底部导航 ====================

    private View buildRootView() {
        LinearLayout root = UIKit.root(this);

        // 顶栏（对应参照物 Scaffold 的 topBar）
        root.addView(UIKit.topBar(this, "XiaoBu Bridge", "刷新",
                        v -> refreshStatus()),
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        // 内容区：用可滚动容器承载，三页共用一个 FrameLayout 槽位
        androidx.core.widget.NestedScrollView scroll =
                new androidx.core.widget.NestedScrollView(this);
        content = new FrameLayout(this);
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 预构建三页
        homePage = new Pages.HomePage(this, this);
        settingsPage = new Pages.SettingsPage(this, this);
        aboutPage = new Pages.AboutPage(this);
        tabContents = new View[]{homePage.root, settingsPage.root, aboutPage.root};

        // 底部导航（对应参照物 NavigationBar）
        navBar = UIKit.navBar(this, TAB_LABELS,
                new int[]{R.drawable.ic_nav_home, R.drawable.ic_nav_settings,
                        R.drawable.ic_nav_about},
                TAB_HOME, this::selectTab);
        collectNavRefs();
        root.addView(navBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        return root;
    }

    /**
     * 从已构建的导航栏里取出药丸/图标/文字引用，用于就地刷新选中态。
     *
     * <p>结构由 {@link UIKit#navBar} 固定为
     * {@code 导航栏 > item(LinearLayout) > [pill(FrameLayout) > icon], label}，
     * 这里按同一约定取引用；取不到就跳过（导航仍可点击，只是不换色）。</p>
     */
    private void collectNavRefs() {
        int n = TAB_LABELS.length;
        navPills = new View[n];
        navIcons = new ImageView[n];
        navLabels = new android.widget.TextView[n];
        for (int i = 0; i < n; i++) {
            View item = navBar.getChildAt(i);
            if (!(item instanceof LinearLayout)) continue;
            LinearLayout itemLl = (LinearLayout) item;
            if (itemLl.getChildCount() < 2) continue;
            View pill = itemLl.getChildAt(0);
            View label = itemLl.getChildAt(1);
            navPills[i] = pill;
            navLabels[i] = (label instanceof android.widget.TextView)
                    ? (android.widget.TextView) label : null;
            if (pill instanceof FrameLayout && ((FrameLayout) pill).getChildCount() > 0) {
                View icon = ((FrameLayout) pill).getChildAt(0);
                navIcons[i] = (icon instanceof ImageView) ? (ImageView) icon : null;
            }
        }
    }

    private void selectTab(int tab) {
        if (tab == currentTab) return;
        currentTab = tab;

        View page = (tab >= 0 && tab < tabContents.length) ? tabContents[tab] : tabContents[0];
        content.removeAllViews();
        content.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (navPills != null) {
            UIKit.applyNavSelection(this, tab, navPills, navIcons, navLabels);
        }

        // 切页后把滚动位置拨回顶部，否则从长页切到短页会停在半空
        View parent = (View) content.getParent();
        if (parent instanceof androidx.core.widget.NestedScrollView) {
            ((androidx.core.widget.NestedScrollView) parent).scrollTo(0, 0);
        }
    }

    // ==================== Actions ====================

    @Override
    public void onRefresh() {
        refreshStatus();
    }

    @Override
    public void onCopyAddress() {
        String address = "http://127.0.0.1:" + ConfigManager.getPort(this) + "/v1";
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("XiaoBuBridge API", address));
            toast("已复制：" + address);
        } else {
            toast(address);
        }
    }

    @Override
    public void onOpenLsposed() {
        launchPackage(LSPOSED_PACKAGE, "未找到 LSPosed 管理器");
    }

    @Override
    public void onOpenTargetApp() {
        launchPackage(TARGET_PACKAGE, "未安装小布助手（" + TARGET_PACKAGE + "）");
    }

    @Override
    public void onSaveConfig() {
        String message = settingsPage.save(this);
        toast(message);
        // 悬浮球/心跳开关改完要立刻按新配置同步服务状态，
        // 否则用户「打开开关但没看到球」只能靠重启 App 解决。
        OverlayBallService.syncState(this);
        refreshStatus();
    }

    // ==================== v3.9 Actions ====================

    @Override
    public void onGenerateApiKey() {
        String key = ConfigManager.generateApiKey();
        ConfigManager.setApiKey(this, key);
        // 生成密钥的唯一目的是用它；不顺手开鉴权只会让人以为「生成了但没生效」
        ConfigManager.setApiKeyEnabled(this, true);
        settingsPage.load(this);
        toast("已生成并启用新密钥");
        refreshStatus();
    }

    @Override
    public void onCopyApiKey() {
        String key = settingsPage.currentApiKeyText();
        if (key == null || key.trim().isEmpty()) {
            toast("尚未设置密钥，请先点「生成新密钥」");
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            toast("剪贴板不可用");
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText("XiaoBuBridge API Key", key.trim()));
        toast("密钥已复制到剪贴板");
    }

    @Override
    public void onImportSystemPrompt() {
        // SAF 而不是直接读路径：Android 10+ 分区存储下应用无法按路径访问
        // 用户自己的文件，必须走系统选择器拿授权 URI。
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            // text/plain 之外还有 .md/.json/.yaml 这类 common MIME 为
            // application/* 的提示词文件，因此再放开一层，避免「文件灰着选不了」。
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES,
                    new String[]{"text/*", "application/json", "application/octet-stream"});
            startActivityForResult(intent, REQ_IMPORT_PROMPT);
        } catch (Throwable t) {
            // 部分精简 ROM 没有 DocumentsUI，退到剪贴板路径，而不是直接失败
            toast("无法打开文件选择器，请改用「从剪贴板导入」");
        }
    }

    @Override
    public void onImportSystemPromptFromClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null
                    || cm.getPrimaryClip().getItemCount() == 0) {
                toast("剪贴板为空");
                return;
            }
            CharSequence text = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            applyImportedSystemPrompt(text == null ? "" : text.toString(), "剪贴板");
        } catch (Throwable t) {
            toast("读取剪贴板失败");
        }
    }

    @Override
    public void onRequestOverlayPermission() {
        if (OverlayBallService.canDrawOverlays(this)) {
            toast("悬浮窗权限已授予");
            OverlayBallService.syncState(this);
            return;
        }
        Toast.makeText(this, "请在系统设置里允许「显示在其他应用上层」", Toast.LENGTH_LONG).show();
        OverlayBallService.requestOverlayPermission(this);
    }

    @Override
    public void onApplyOverlayBall() {
        if (ConfigManager.isOverlayBallEnabled(this)
                && !OverlayBallService.canDrawOverlays(this)) {
            toast("缺少悬浮窗权限，请先授予");
            OverlayBallService.requestOverlayPermission(this);
            return;
        }
        OverlayBallService.syncState(this);
        toast(ConfigManager.isOverlayBallEnabled(this) ? "悬浮球服务已启动" : "悬浮球服务已停止");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT_PROMPT || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        new Thread(() -> {
            String content = null;
            String error = null;
            try {
                content = readTextFile(uri);
            } catch (Throwable t) {
                error = String.valueOf(t.getMessage());
            }
            final String text = content;
            final String err = error;
            mainHandler.post(() -> {
                if (err != null) {
                    toast("读取失败：" + err);
                } else {
                    applyImportedSystemPrompt(text, "文件");
                }
            });
        }, "xiaobu-import-prompt").start();
    }

    /** 读一个 content:// 文本文件；超过上限直接拒绝，避免把编辑框和 prefs 撑爆 */
    private String readTextFile(Uri uri) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new Exception("无法打开该文件");
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (bos.size() + n > MAX_IMPORT_BYTES) {
                    throw new Exception("文件超过 " + (MAX_IMPORT_BYTES / 1024) + "KB 上限");
                }
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 导入结果只填进输入框；落盘由用户点「保存配置」完成 */
    private void applyImportedSystemPrompt(String text, String source) {
        String value = text == null ? "" : text;
        if (value.isEmpty()) {
            toast(source + "内容为空，未导入");
            return;
        }
        settingsPage.setSystemPromptText(value);
        toast("已从" + source + "导入 " + value.length() + " 字，点「保存配置」生效");
        selectTab(TAB_SETTINGS);
    }

    private void launchPackage(String pkg, String missingHint) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent == null) {
            toast(missingHint);
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Throwable t) {
            toast(missingHint);
        }
    }

    // ==================== 状态探测 ====================

    /**
     * 刷新运行状态。
     *
     * <p>两步走，都在后台线程：</p>
     * <ol>
     *   <li>{@code GET /status} —— 拿到真实运行指标。这是判定「网关是否在跑」
     *       的唯一权威依据；</li>
     *   <li>{@code GET /health} 仅在 /status 失败时作为兜底探活，用于区分
     *       「网关活着但 /status 有异常」与「网关完全没起来」。</li>
     * </ol>
     */
    private void refreshStatus() {
        final int port = ConfigManager.getPort(this);
        new Thread(() -> {
            Pages.ProbeResult probe = probeGateway(port);
            boolean lsposedInstalled = isInstalled(LSPOSED_PACKAGE);
            probe.frameworkText = buildFrameworkText(probe, lsposedInstalled);
            mainHandler.post(() -> homePage.render(MainActivity.this, probe));
        }, "xiaobu-status-probe").start();
    }

    /**
     * 组装「运行模式」文案。
     *
     * <p><b>为什么不复刻参照物的「内置模式（未连接 LSPosed）」分支</b>：
     * 参照物的模块 Apk 可以独立安装运行，因此它能区分「框架内 / 框架外」；
     * 本模块的 MainActivity 只有在 LSPosed 已把模块加载起来后才可能被启动，
     * 所以「未连接 LSPosed」这一状态在本模块里无法出现——写出来只会是死代码。
     * 改用对用户真正有用的三态：网关在跑 / 框架已加载待小布启动 / 完全未检测到。</p>
     */
    private String buildFrameworkText(Pages.ProbeResult probe, boolean lsposedInstalled) {
        if (probe.alive) {
            return "LSPosed 框架（已激活，网关运行中）";
        }
        if (lsposedInstalled) {
            return "LSPosed 框架（已加载，等待小布启动）";
        }
        return "未检测到 LSPosed 框架";
    }

    /** 拉取 /status；失败则退到 /health 探活 */
    private Pages.ProbeResult probeGateway(int port) {
        Pages.ProbeResult result = new Pages.ProbeResult();
        result.port = port;

        JSONObject status = getJson("http://127.0.0.1:" + port + "/status");
        if (status != null) {
            result.alive = true;
            result.host = status.optString("host", "127.0.0.1");
            result.port = status.optInt("port", port);
            result.apiFormat = status.optString("api_format", ConfigManager.DEFAULT_API_FORMAT);
            result.apiKeySet = status.optBoolean("api_key_set", false);
            result.systemPromptSet = status.optBoolean("system_prompt_set", false);
            result.maxConcurrency = status.optInt("max_concurrency",
                    ConfigManager.DEFAULT_MAX_CONCURRENCY);
            result.activeConnections = status.optLong("active_connections", 0);
            result.requests = status.optLong("requests", 0);
            result.failed = status.optLong("failed", 0);
            result.autoRetries = status.optLong("auto_retries", 0);
            result.avgLatencyMs = status.optLong("avg_latency_ms", 0);
            // v3.9 工具调用与心跳指标
            result.toolRequests = status.optLong("tool_requests", 0);
            result.toolCalls = status.optLong("tool_calls", 0);
            result.lastToolNames = status.optString("last_tool_names", "");
            result.heartbeatEnabled = status.optBoolean("heartbeat_enabled", false);
            result.heartbeatIntervalMs = status.optInt("heartbeat_interval_ms",
                    ConfigManager.DEFAULT_HEARTBEAT_INTERVAL_MS);
            result.heartbeatTicks = status.optLong("heartbeat_ticks", 0);
            return result;
        }

        // /status 不可用：再看 /health，用于把「网关存活但 /status 异常」区分出来
        if (getJson("http://127.0.0.1:" + port + "/health") != null) {
            result.alive = true;
            return result;
        }

        // 两者都不通：可能是 API Key 已开启（401）或服务刚起，退到「能连上就算活」
        if (tcpReachable(port)) {
            result.alive = true;
        }
        return result;
    }

    /** GET 一个返回 JSON 的端点；任何异常返回 null */
    private JSONObject getJson(String url) {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false);
            // 开启 API Key 鉴权后 /status 也需要凭证，否则 401 → 首页统计全部退化成「—」，
            // 看起来像功能坏了。这里用本机已配置的 key 自证身份。
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

    /**
     * 端口是否有人监听。
     *
     * <p>只做 TCP 连通性判断，不解析 HTTP —— 用于 API Key 开启时
     * {@code /status} 返回 401、无法用状态码判断存活的场景。</p>
     */
    private boolean tcpReachable(int port) {
        for (int attempt = 0; attempt < 2; attempt++) {
            java.net.Socket socket = null;
            try {
                socket = new java.net.Socket();
                socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 1200);
                return true;
            } catch (Throwable t) {
                // 服务可能刚随小布进程启动，短暂等待后重试一次
            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                break;
            }
        }
        return false;
    }

    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
