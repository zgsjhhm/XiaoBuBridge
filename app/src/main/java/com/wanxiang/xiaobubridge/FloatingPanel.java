package com.wanxiang.xiaobubridge;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import de.robv.android.xposed.XposedBridge;

/**
 * 应用内控制面板 —— 复刻参照物 j477si.apk（Qwen AppHook）的 {@code FloatingPanel}。
 *
 * <p>结构与参照物对齐：</p>
 * <ul>
 *   <li>顶部：标题 + 关闭按钮（对应参照物的 {@code buildRoot} 头部）；</li>
 *   <li>标签栏：主页 / API / 设置（对应参照物的 {@code buildTabBar} + 三个 tab）；</li>
 *   <li>内容区：卡片式分组，配色取自 {@link UIKit}；</li>
 *   <li>显示期间按固定间隔刷新运行统计（对应参照物的 {@code startStatsRefresh}）。</li>
 * </ul>
 *
 * <p><b>与参照物的实现差异（有意为之）</b>：参照物用 {@code Dialog} 承载面板，
 * 本模块把面板作为子视图挂进目标 Activity 的 decorView。原因见 {@link UiInjector}
 * 的说明——小布进程内起 {@code Dialog} 会因 window token 缺失而失败。</p>
 *
 * <p>面板挂在 decorView 上，随 Activity 销毁自动消失，因此每次打开时都重新构建，
 * 无需持久化实例。</p>
 */
public class FloatingPanel {

    private static final String TAG = "[XiaoBuBridge]";
    private static final String PANEL_TAG = "xiaobu_floating_panel";
    private static final String BG_TAG = "xiaobu_floating_panel_bg";
    private static final String BALL_TAG = "xiaobu_floating_ball";

    /** 统计刷新间隔（对应参照物的 statsRefresh 节奏） */
    private static final long STATS_INTERVAL_MS = 2000L;

    private static final int TAB_HOME = 0;
    private static final int TAB_API = 1;
    private static final int TAB_SETTINGS = 2;

    private final Activity activity;
    private final ViewGroup rootView;
    private final View panelView;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final View[] tabContents = new View[3];
    private final TextView[] tabButtons = new TextView[3];
    private int currentTab;

    // ---- 主页 ----
    private TextView tvGatewayChip;
    private TextView tvAddress;
    private TextView tvFormat;
    private TextView tvAuth;
    private TextView tvActive;
    private TextView tvRequests;
    private TextView tvFailed;
    private TextView tvRetries;
    private TextView tvLatency;
    private TextView tvSession;

    private volatile boolean statsRunning;

    private static FloatingPanel currentInstance;

    private final Runnable statsRunnable = new Runnable() {
        @Override
        public void run() {
            if (!statsRunning) return;
            refreshStats();
            handler.postDelayed(this, STATS_INTERVAL_MS);
        }
    };

    // ==================== 入口 ====================

    /**
     * 在目标 Activity 内显示控制面板。
     *
     * <p>幂等：若已有实例在显示，先关闭再重建，避免出现两层面板。</p>
     */
    static void show(@NonNull Activity activity) {
        try {
            View decorView = activity.getWindow().getDecorView();
            if (!(decorView instanceof ViewGroup)) {
                XposedBridge.log(TAG + " FloatingPanel: decorView is not a ViewGroup");
                return;
            }
            ViewGroup rootView = (ViewGroup) decorView;

            if (currentInstance != null) {
                currentInstance.hide();
            }

            FloatingPanel fp = new FloatingPanel(activity, rootView);
            fp.show();
            currentInstance = fp;
            XposedBridge.log(TAG + " FloatingPanel: shown");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " FloatingPanel show failed: " + t);
            XposedBridge.log(t);
        }
    }

    private FloatingPanel(@NonNull Activity activity, @NonNull ViewGroup rootView) {
        this.activity = activity;
        this.rootView = rootView;
        this.panelView = buildRoot();
    }

    // ==================== 根视图 ====================

    private View buildRoot() {
        Context ctx = activity;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setTag(PANEL_TAG);

        // 面板外形：白底 + 1px 灰边 + 12dp 圆角（对应参照物 panel 的 surface 风格）
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(UIKit.dp(ctx, UIKit.R_CARD));
        bg.setColor(UIKit.SURFACE);
        bg.setStroke(Math.max(1, UIKit.dp(ctx, 1)), UIKit.SURFACE_BORDER);
        root.setBackground(bg);
        root.setElevation(UIKit.dp(ctx, 12));
        root.setClickable(true);
        root.setFocusable(true);

        root.addView(buildHeader(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(buildTabBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tabContents[TAB_HOME] = buildHomeTab();
        tabContents[TAB_API] = buildApiTab();
        tabContents[TAB_SETTINGS] = buildSettingsTab();

        // 固定宽度，避免内容长度变化时面板来回抖动
        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        for (View content : tabContents) {
            body.addView(content, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        root.addView(body, new LinearLayout.LayoutParams(
                UIKit.dp(ctx, 300), ViewGroup.LayoutParams.WRAP_CONTENT));

        switchTab(TAB_HOME);

        // 面板整体限宽，超出屏幕时由父容器裁切
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                UIKit.dp(ctx, 300), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END | Gravity.TOP;
        lp.setMargins(0, UIKit.dp(ctx, 80), UIKit.dp(ctx, 12), 0);
        root.setLayoutParams(lp);

        return root;
    }

    /** 头部：标题 + 关闭（对应参照物 buildRoot 的 LinearLayout header） */
    private View buildHeader() {
        Context ctx = activity;

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(UIKit.SURFACE);
        header.setPadding(UIKit.dp(ctx, 16), UIKit.dp(ctx, 12),
                UIKit.dp(ctx, 16), UIKit.dp(ctx, 12));

        TextView title = new TextView(ctx);
        title.setText("XiaoBu Bridge");
        title.setTextSize(16f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(UIKit.TEXT_PRIMARY);
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(ctx);
        close.setText("✕");
        close.setTextSize(15f);
        close.setTextColor(UIKit.TEXT_SECONDARY);
        close.setPadding(UIKit.dp(ctx, 10), UIKit.dp(ctx, 2),
                UIKit.dp(ctx, 4), UIKit.dp(ctx, 2));
        close.setOnClickListener(v -> hide());
        header.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout wrapper = new LinearLayout(ctx);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View line = new View(ctx);
        line.setBackgroundColor(UIKit.DIVIDER);
        wrapper.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, UIKit.dp(ctx, 0.5f))));
        return wrapper;
    }

    /** 标签栏（对应参照物 buildTabBar） */
    private View buildTabBar() {
        Context ctx = activity;

        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(UIKit.SURFACE);
        bar.setPadding(UIKit.dp(ctx, 8), UIKit.dp(ctx, 6),
                UIKit.dp(ctx, 8), UIKit.dp(ctx, 6));

        String[] labels = {"主页", "API", "设置"};
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView tab = new TextView(ctx);
            tab.setText(labels[i]);
            tab.setTextSize(UIKit.SP_BODY);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(0, UIKit.dp(ctx, 8), 0, UIKit.dp(ctx, 8));
            tab.setOnClickListener(v -> switchTab(index));
            tabButtons[i] = tab;
            bar.addView(tab, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        applyTabStyles(ctx);
        return bar;
    }

    private void switchTab(int index) {
        currentTab = index;
        for (int i = 0; i < tabContents.length; i++) {
            if (tabContents[i] != null) {
                tabContents[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
            }
        }
        applyTabStyles(activity);
        if (index == TAB_HOME) {
            refreshStats();
        }
    }

    private void applyTabStyles(Context ctx) {
        for (int i = 0; i < tabButtons.length; i++) {
            if (tabButtons[i] == null) continue;
            boolean on = (i == currentTab);
            tabButtons[i].setTextColor(on ? UIKit.ACCENT : UIKit.TEXT_SECONDARY);
            tabButtons[i].setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(UIKit.dp(ctx, UIKit.R_CONTROL));
            bg.setColor(on ? UIKit.ACCENT_DIM : 0x00000000);
            tabButtons[i].setBackground(bg);
        }
    }

    // ==================== 主页标签 ====================

    private View buildHomeTab() {
        Context ctx = activity;

        LinearLayout page = new LinearLayout(ctx);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(UIKit.BG);
        page.setPadding(UIKit.dp(ctx, 12), UIKit.dp(ctx, 12),
                UIKit.dp(ctx, 12), UIKit.dp(ctx, 12));

        // ---- 运行状态 ----
        LinearLayout statusCard = UIKit.card(ctx);
        statusCard.addView(UIKit.sectionTitle(ctx, "运行状态"));

        LinearLayout gwRow = new LinearLayout(ctx);
        gwRow.setOrientation(LinearLayout.HORIZONTAL);
        gwRow.setGravity(Gravity.CENTER_VERTICAL);
        gwRow.setPadding(0, UIKit.dp(ctx, 2), 0, UIKit.dp(ctx, 2));
        TextView gwLabel = new TextView(ctx);
        gwLabel.setText("网关");
        gwLabel.setTextSize(UIKit.SP_BODY);
        gwLabel.setTextColor(UIKit.TEXT_SECONDARY);
        gwRow.addView(gwLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tvGatewayChip = UIKit.statusChip(ctx, "检测中", UIKit.YELLOW);
        gwRow.addView(tvGatewayChip);
        statusCard.addView(gwRow, UIKit.matchWrap(ctx, 0f));

        tvAddress = UIKit.infoRow(statusCard, ctx, "地址", "—");
        tvFormat = UIKit.infoRow(statusCard, ctx, "格式", "—");
        tvAuth = UIKit.infoRow(statusCard, ctx, "鉴权", "—");
        UIKit.divider(statusCard, ctx);
        tvActive = UIKit.infoRow(statusCard, ctx, "活跃连接", "—");
        tvRequests = UIKit.infoRow(statusCard, ctx, "累计请求", "—");
        tvFailed = UIKit.infoRow(statusCard, ctx, "失败", "—");
        tvRetries = UIKit.infoRow(statusCard, ctx, "自动重试", "—");
        tvLatency = UIKit.infoRow(statusCard, ctx, "平均耗时", "—");
        UIKit.divider(statusCard, ctx);
        tvSession = UIKit.infoRow(statusCard, ctx, "当前会话", "—");
        page.addView(statusCard);

        // ---- 操作 ----
        LinearLayout actionCard = UIKit.card(ctx);
        actionCard.addView(UIKit.sectionTitle(ctx, "操作"));
        actionCard.addView(UIKit.outlineButton(ctx, "打开设置", v -> openModuleSettings()),
                UIKit.matchWrap(ctx, 6f));
        actionCard.addView(UIKit.outlineButton(ctx, "复制 API 地址", v -> copyAddress()),
                UIKit.matchWrap(ctx, 6f));
        actionCard.addView(UIKit.outlineButton(ctx, "隐藏悬浮球", v -> hideBall()),
                UIKit.matchWrap(ctx, 6f));
        page.addView(actionCard);

        return page;
    }

    // ==================== API 标签 ====================

    private View buildApiTab() {
        Context ctx = activity;

        LinearLayout page = new LinearLayout(ctx);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(UIKit.BG);
        page.setPadding(UIKit.dp(ctx, 12), UIKit.dp(ctx, 12),
                UIKit.dp(ctx, 12), UIKit.dp(ctx, 12));

        int port = ConfigManager.getPortInTarget();
        String base = "http://127.0.0.1:" + port;

        LinearLayout conn = UIKit.card(ctx);
        conn.addView(UIKit.sectionTitle(ctx, "连接配置"));
        UIKit.infoRow(conn, ctx, "Base URL", base + "/v1");
        UIKit.infoRow(conn, ctx, "局域网", "http://" + localIp() + ":" + port + "/v1");
        UIKit.infoRow(conn, ctx, "API 格式",
                "OpenAI + Anthropic".equals(formatLabel())
                        ? "两者" : formatLabel());
        page.addView(conn);

        LinearLayout routes = UIKit.card(ctx);
        routes.addView(UIKit.sectionTitle(ctx, "端点"));
        UIKit.infoRow(routes, ctx, "GET /v1/models", "模型列表");
        UIKit.infoRow(routes, ctx, "POST /v1/chat/completions", "OpenAI");
        UIKit.infoRow(routes, ctx, "POST /v1/messages", "Anthropic");
        UIKit.infoRow(routes, ctx, "POST /v1/completions", "Legacy");
        UIKit.infoRow(routes, ctx, "GET /status", "运行统计");
        UIKit.infoRow(routes, ctx, "GET /health", "探活");
        page.addView(routes);

        LinearLayout sample = UIKit.card(ctx);
        sample.addView(UIKit.sectionTitle(ctx, "调用示例"));
        UIKit.mono(sample, ctx,
                "curl " + base + "/v1/chat/completions \\\n"
                        + "  -H 'Content-Type: application/json' \\\n"
                        + "  -d '{\"model\":\"xiaobu\",\n"
                        + "       \"messages\":[{\"role\":\"user\",\n"
                        + "       \"content\":\"你好\"}]}'");
        page.addView(sample);

        return page;
    }

    // ==================== 设置标签 ====================

    private View buildSettingsTab() {
        Context ctx = activity;

        LinearLayout page = new LinearLayout(ctx);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(UIKit.BG);
        page.setPadding(UIKit.dp(ctx, 12), UIKit.dp(ctx, 12),
                UIKit.dp(ctx, 12), UIKit.dp(ctx, 12));

        LinearLayout card = UIKit.card(ctx);
        card.addView(UIKit.sectionTitle(ctx, "快速开关"));

        // 这些开关直接写回模块配置，与模块 App 设置页共享同一份数据
        UIKit.switchRow(card, ctx, "本地 API 服务",
                "关闭后小布进程不再监听端口",
                ConfigManager.isServerEnabledInTarget())
                .setOnCheckedChangeListener(writeBack(ConfigManager.KEY_SERVER_ENABLED));

        UIKit.switchRow(card, ctx, "SSE 流式响应",
                "响应体按 text/event-stream 组织",
                ConfigManager.isStreamEnabledInTarget())
                .setOnCheckedChangeListener(writeBack(ConfigManager.KEY_STREAM_ENABLED));

        UIKit.switchRow(card, ctx, "真流式（chunked）",
                "边收边发，客户端逐字显示",
                ConfigManager.isChunkedStreamEnabledInTarget())
                .setOnCheckedChangeListener(writeBack(ConfigManager.KEY_CHUNKED_STREAM_ENABLED));

        UIKit.switchRow(card, ctx, "自动唤醒小布",
                "请求到达时把后台的小布拉到前台",
                ConfigManager.isAutoWakeEnabledInTarget())
                .setOnCheckedChangeListener(writeBack(ConfigManager.KEY_AUTO_WAKE_ENABLED));

        UIKit.switchRow(card, ctx, "保活",
                "拦截小布的自动退出",
                ConfigManager.isKeepAliveEnabledInTarget())
                .setOnCheckedChangeListener(writeBack(ConfigManager.KEY_KEEP_ALIVE_ENABLED));

        UIKit.switchRow(card, ctx, "CORS 跨域",
                "下发 Access-Control-Allow-* 头",
                ConfigManager.isCorsEnabledInTarget())
                .setOnCheckedChangeListener(writeBack(ConfigManager.KEY_CORS_ENABLED));

        UIKit.switchRow(card, ctx, "自动重试",
                "注入零回调时重试一轮",
                ConfigManager.isAutoRetryEnabledInTarget())
                .setOnCheckedChangeListener(writeBack(ConfigManager.KEY_AUTO_RETRY_ENABLED));

        page.addView(card);

        LinearLayout note = UIKit.card(ctx);
        note.addView(UIKit.sectionTitle(ctx, "说明"));
        UIKit.hint(note, ctx,
                "改动即时生效并写回模块配置。端口、API 格式、鉴权密钥等需要在"
                        + "「打开设置」里调整。", 0);
        page.addView(note);

        return page;
    }

    /** 开关变更 → 写回模块配置（Hook 侧经 ContentProvider 跨进程写入） */
    private CompoundButton.OnCheckedChangeListener writeBack(String key) {
        return (buttonView, isChecked) -> {
            boolean ok = ConfigManager.setBooleanInTarget(key, isChecked);
            if (!ok) {
                Toast.makeText(activity, "写入失败，请检查小布进程是否被冻结",
                        Toast.LENGTH_SHORT).show();
            }
        };
    }

    // ==================== 显示 / 隐藏 ====================

    private void show() {
        View ball = rootView.findViewWithTag(BALL_TAG);
        if (ball != null) {
            ball.setVisibility(View.GONE);
        }

        // 点击面板外部关闭：铺一层透明全屏视图在面板下层
        View bg = new View(activity);
        bg.setTag(BG_TAG);
        bg.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        bg.setBackgroundColor(0x00000000);
        bg.setOnClickListener(v -> hide());

        rootView.addView(bg);
        rootView.addView(panelView);

        refreshStats();
        statsRunning = true;
        handler.postDelayed(statsRunnable, STATS_INTERVAL_MS);
    }

    /** 隐藏面板并恢复悬浮球 */
    void hide() {
        statsRunning = false;
        handler.removeCallbacks(statsRunnable);

        View bgView = rootView.findViewWithTag(BG_TAG);
        if (bgView != null) {
            try {
                rootView.removeView(bgView);
            } catch (Throwable ignored) {
            }
        }
        try {
            rootView.removeView(panelView);
        } catch (Throwable ignored) {
        }

        View ball = rootView.findViewWithTag(BALL_TAG);
        if (ball != null) {
            ball.setVisibility(View.VISIBLE);
        }
        if (currentInstance == this) {
            currentInstance = null;
        }
    }

    // ==================== 状态刷新 ====================

    /**
     * 刷新主页统计。
     *
     * <p>{@code /status} 由本进程内的网关提供，走回环请求即可；请求在后台线程发起，
     * 结果回主线程渲染。</p>
     */
    private void refreshStats() {
        final int port = ConfigManager.getPortInTarget();
        new Thread(() -> {
            JSONObject status = getJson("http://127.0.0.1:" + port + "/status");
            handler.post(() -> renderStats(status, port));
        }, "xiaobu-panel-stats").start();
    }

    private void renderStats(JSONObject status, int port) {
        Context ctx = activity;
        if (status == null) {
            tvGatewayChip.setText("未响应");
            UIKit.tintChip(tvGatewayChip, UIKit.RED);
            tvAddress.setText("127.0.0.1:" + port);
            tvFormat.setText(formatLabel());
            tvAuth.setText(ConfigManager.isApiKeyEnabledInTarget() ? "已开启" : "未开启");
            tvSession.setText("—");
            return;
        }

        tvGatewayChip.setText("运行中");
        UIKit.tintChip(tvGatewayChip, UIKit.GREEN);

        tvAddress.setText(status.optString("host", "127.0.0.1")
                + ":" + status.optInt("port", port));
        tvFormat.setText(formatLabel());
        tvAuth.setText(status.optBoolean("api_key_set", false) ? "已开启" : "未开启");
        tvActive.setText(String.valueOf(status.optLong("active_connections", 0)));
        tvRequests.setText(String.valueOf(status.optLong("requests", 0)));
        tvFailed.setText(String.valueOf(status.optLong("failed", 0)));
        tvRetries.setText(String.valueOf(status.optLong("auto_retries", 0)));
        tvLatency.setText(status.optLong("avg_latency_ms", 0) + " ms");

        // 会话状态直接读 Hook 侧内存，比 /status 更实时
        ConversationSession session = ConversationSession.getMostRecent(0);
        if (session == null) {
            tvSession.setText("空闲");
        } else if (session.isCompleted()) {
            tvSession.setText("已完成（" + session.getFullContent().length() + " 字）");
        } else {
            tvSession.setText("活跃中");
        }
    }

    private String formatLabel() {
        String f = ConfigManager.getApiFormatInTarget();
        if (ConfigManager.API_FORMAT_OPENAI.equals(f)) return "OpenAI";
        if (ConfigManager.API_FORMAT_ANTHROPIC.equals(f)) return "Anthropic";
        return "OpenAI + Anthropic";
    }

    private JSONObject getJson(String url) {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(2000);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false);
            // 鉴权开启时 /status 需要凭证，否则面板统计全部显示为「—」
            String key = ConfigManager.getApiKeyInTarget();
            if (ConfigManager.isApiKeyEnabledInTarget() && key != null && !key.trim().isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + key.trim());
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
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

    // ==================== 操作 ====================

    /** 跳转模块自身的设置页（对应参照物的「打开设置」） */
    private void openModuleSettings() {
        try {
            Intent intent = new Intent(activity, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
            hide();
        } catch (Throwable t) {
            Toast.makeText(activity, "无法打开设置页", Toast.LENGTH_SHORT).show();
            XposedBridge.log(TAG + " FloatingPanel: open settings failed: " + t);
        }
    }

    private void copyAddress() {
        try {
            String address = "http://127.0.0.1:" + ConfigManager.getPortInTarget() + "/v1";
            ClipboardManager cm =
                    (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("XiaoBuBridge API", address));
                Toast.makeText(activity, "已复制：" + address, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " FloatingPanel: copy failed: " + t);
        }
        hide();
    }

    /** 隐藏悬浮球并落盘开关，避免用户下次启动又看到它 */
    private void hideBall() {
        View ball = rootView.findViewWithTag(BALL_TAG);
        if (ball != null) {
            ball.setVisibility(View.GONE);
        }
        boolean ok = ConfigManager.setFloatBallEnabledInTarget(false);
        Toast.makeText(activity,
                ok ? "悬浮球已隐藏（可在模块设置页重新开启）" : "悬浮球已隐藏，但开关写入失败",
                Toast.LENGTH_SHORT).show();
        hide();
    }

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
