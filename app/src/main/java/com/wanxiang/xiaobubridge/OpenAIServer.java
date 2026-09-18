package com.wanxiang.xiaobubridge;

import de.robv.android.xposed.XposedBridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * 纯 Socket 实现的轻量级 OpenAI 兼容 HTTP Server
 *
 * <p>提供 /v1/models 与 /v1/chat/completions 接口，零外部依赖。
 * v2.0 起从 {@link ConfigManager} 的跨进程配置通道读取三项运行期参数：
 * <ul>
 *   <li>API Key 鉴权开关与密钥（Authorization: Bearer &lt;key&gt;）；</li>
 *   <li>并发上限（Semaphore 限流，超出返回 429）；</li>
 *   <li>系统提示词（拼接到注入小布的用户消息前）。</li>
 * </ul></p>
 *
 * <p>v3.2 关键修正：
 * <ul>
 *   <li>小布进程的 logcat 输出量极大，LSPosed 日志在环形缓冲区里几乎瞬间被冲掉，
 *       导致“请求是否到达 / 卡在哪一步”无法事后追溯。改为把 HTTP 关键节点
 *       同时追加落盘到小布应用私有目录 files/xiaobubridge_http.log，
 *       不再依赖 logcat 缓冲区。</li>
 *   <li>请求读取与响应等待分设超时：读请求头/体只给 20 秒，避免异常连接
 *       以 60 秒长超时占住工作线程，出现“Empty reply / Read timed out”后难以定位。</li>
 * </ul></p>
 */
public class OpenAIServer {

    private static final String TAG = "[XiaoBuBridge]";
    /** 响应等待超时（会话轮询 / 非流式等待上限） */
    private static final long STREAM_TIMEOUT_MS = 60 * 1000L;

    /**
     * 自动重试前的退避等待。
     *
     * <p>注入零回调往往是小布上一轮的回答回调尚未彻底收尾；立刻重试容易
     * 撞上同一个状态，等一小段再试成功率明显更高。</p>
     */
    private static final long RETRY_BACKOFF_MS = 800L;
    /** 请求读取阶段超时：正常客户端应瞬时发完请求，超时即视为异常连接 */
    private static final long REQUEST_READ_TIMEOUT_MS = 20 * 1000L;
    /**
     * 流式/非流式等待内容时的轮询粒度（v3.4）。
     * 会话队列必须按这个粒度取，不能用 ConversationSession 内部 60 秒的阻塞轮询，
     * 否则队列短暂为空就会把整个响应按住一分钟。
     */
    private static final long POLL_GRANULARITY_MS = 50L;
    /** 落盘日志文件名（写在小布应用私有目录，root 可读） */
    private static final String LOG_FILE_NAME = "xiaobubridge_http.log";

    private static volatile File logFile;

    private final int port;
    private ServerSocket serverSocket;
    private ExecutorService executor;
    private volatile boolean running;

    /**
     * v3.9 运行期启停。
     *
     * <p>悬浮球面板上的「网关开关」要在<b>不重启小布</b>的前提下立刻生效。
     * 旧实现只有构造时 {@code start()} 一次，开关只能靠改配置 + 杀进程重启，
     * 而「保活」恰恰又拦着不让小布自杀——两者叠加就变成了「关不掉」。
     * 现在把 accept 线程做成可重新拉起/可中断的，{@code setEnabled} 直接
     * 起停监听。</p>
     */
    private volatile Thread acceptThread;

    /** 并发闸门，容量取自配置的 max_concurrency */
    private volatile Semaphore concurrencyLimiter;

    /**
     * 小布对话框串行锁（v3.5）。
     *
     * <p>小布进程里只有一个对话框，回答回调也不携带 requestId，无法把回答对应回请求。
     * 因此「建屏障 → 注入 → 等待回答」必须是临界区：并发注入时后一个请求会清掉
     * 前一个的会话登记，导致两个请求认领同一份回答（实测两请求都拿到「香蕉」）。
     * 这里用公平锁把该阶段串行化，锁外仍可并发处理请求解析与鉴权。</p>
     */
    private final java.util.concurrent.locks.ReentrantLock dialogLock =
            new java.util.concurrent.locks.ReentrantLock(true);

    public OpenAIServer(int port) {
        this.port = port;
    }

    /**
     * 获取落盘日志文件（v3.5）。
     *
     * <p>旧实现先认定「应用私有 files 目录」再回退 {@code /data/local/tmp}，且
     * 回退路径不校验可写。实测小布进程以 u0_a94 运行、SELinux 虽为 Permissive，
     * 但对 /data/local/tmp 的写入依然失败，而旧实现连异常都被静默吞掉，
     * 结果「日志文件超时清理」这类关键信息彻底丢失，排查时只能翻被冲刷的 logcat。</p>
     *
     * <p>现在按候选目录顺序做<b>真实写入探测</b>（能建能写才采用），全部失败时
     * 返回 null，由 {@link #logBoth} 退化为只写 Xposed 日志，绝不假装写入成功。</p>
     */
    private static File getLogFile() {
        File f = logFile;
        if (f != null) {
            return f;
        }
        synchronized (OpenAIServer.class) {
            if (logFile != null) {
                return logFile;
            }
            logFile = resolveWritableLogFile();
            if (logFile == null) {
                XposedBridge.log(TAG + " Warning: no writable log file, falling back to logcat only");
            }
            return logFile;
        }
    }

    /** 按优先级挑选第一个真正可写的日志目录；都不行返回 null */
    private static File resolveWritableLogFile() {
        java.util.List<File> candidates = new java.util.ArrayList<>();

        // 1) 目标应用私有 files 目录：跨进程与 root 均可读
        File filesDir = appFilesDir();
        if (filesDir != null) {
            candidates.add(filesDir);
        }
        // 2) 外部私有目录（shell/root 可直接 adb pull，调试最方便）
        String pkg = currentPackageName();
        if (pkg != null) {
            candidates.add(new File("/sdcard/Android/data/" + pkg + "/files"));
            // 3) 从 uid 反推私有目录，兜住 ActivityThread 取不到 Context 的时机
            candidates.add(new File("/data/user/0/" + pkg + "/files"));
        }
        // 4) 最后的公共落点
        candidates.add(new File("/data/local/tmp"));

        for (File dir : candidates) {
            File target = probeWritable(dir);
            if (target != null) {
                return target;
            }
        }
        return null;
    }

    /** 在给定目录里做一次真实写入探测，成功返回日志文件，失败返回 null */
    private static File probeWritable(File dir) {
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return null;
            }
            File target = new File(dir, LOG_FILE_NAME);
            // 真实打开一次追加流：canWrite() 在 SELinux 下会给出误导性的 true
            FileOutputStream probe = new FileOutputStream(target, true);
            probe.close();
            return target;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 取目标进程的 files 目录，取不到返回 null */
    private static File appFilesDir() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof android.content.Context) {
                return ((android.content.Context) app).getFilesDir();
            }
        } catch (Throwable ignored) {
            // hook 阶段可能早于 Application 创建，交给后续候选目录兜底
        }
        return null;
    }

    /** 取当前进程包名，取不到返回 null */
    private static String currentPackageName() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object pkg = at.getMethod("currentPackageName").invoke(null);
            if (pkg instanceof String && !((String) pkg).isEmpty()) {
                return (String) pkg;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 关键链路日志：同时写 Xposed 日志与落盘文件，避免被 logcat 缓冲区冲掉 */
    private static void logBoth(String msg) {
        XposedBridge.log(TAG + " " + msg);
        File target = getLogFile();
        // 无可用落盘通道时直接返回：连建文件都会失败的话，这里再试一次也只是白费
        if (target == null) {
            return;
        }
        try {
            FileOutputStream fos = new FileOutputStream(target, true);
            String line = System.currentTimeMillis() + " " + msg + "\n";
            fos.write(line.getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Throwable t) {
            // 落盘失败不影响 HTTP 主流程；但必须让失败可见，否则磁盘 I/O 异常会被彻底掩盖
            XposedBridge.log(TAG + " Log file write failed: " + t);
        }
    }

    /**
     * v3.9 保留的薄封装：等价于 {@code setEnabled(true)}。
     *
     * <p>存在的意义是兼容 {@code MainHook} 里可能仍以「构建后立即启动」语义
     * 调用它的路径，同时把「监听逻辑」收口到 {@link #setEnabled(boolean)} 一处，
     * 避免两套启动代码各自漏掉某项（旧实现里真的漏过
     * {@code GatewayStats.markListening}）。</p>
     */
    public void start() throws IOException {
        if (!setEnabled(true)) {
            throw new IOException("bind failed on port " + port);
        }
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        if (executor != null) executor.shutdownNow();
        acceptThread = null;
        logBoth("Server stopped");
    }

    // ==================== v3.9 运行期启停 ====================

    /** 监听是否正在运行 */
    public boolean isRunning() {
        return running;
    }

    /**
     * 运行期开关网关（悬浮球面板与设置页的「启用本地 API 服务」共用它）。
     *
     * <p>语义是<b>幂等</b>：已经开着再开、已经关着再关都是空操作。
     * 关掉时连并发闸门一起置空——留着旧容量会让「关网关」在面板上看起来生效、
     * 实际下一轮请求仍能拿到令牌。</p>
     *
     * @return 操作后是否处于运行状态
     */
    public synchronized boolean setEnabled(boolean enabled) {
        if (enabled == running) {
            return running;
        }
        if (enabled) {
            try {
                // 端口被上一轮 stop 占在 TIME_WAIT 时，setReuseAddress 让 bind 立刻成功
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new java.net.InetSocketAddress(port));
                executor = Executors.newCachedThreadPool();
                running = true;

                // 并发容量在每次「启动」时按当前配置求值：用户在悬浮球面板上把
                // 并发从 3 调到 8 之后，重新开关一次网关就该按 8 生效。
                int maxConcurrency = ConfigManager.getMaxConcurrencyInTarget();
                concurrencyLimiter = new Semaphore(maxConcurrency);

                acceptThread = new Thread(() -> {
                    while (running) {
                        try {
                            Socket client = serverSocket.accept();
                            executor.submit(() -> handleClient(client));
                        } catch (IOException e) {
                            if (running) logBoth("Accept error: " + e.getMessage());
                        }
                    }
                });
                acceptThread.setDaemon(true);
                acceptThread.start();
                GatewayStats.reset();
                GatewayStats.markListening("127.0.0.1", port);
                logBoth("Gateway enabled on port " + port
                        + " (concurrency=" + maxConcurrency + ")");
                File lf = getLogFile();
                logBoth("Log file = " + (lf != null ? lf.getAbsolutePath()
                        : "(unavailable, logcat only)"));
            } catch (Throwable t) {
                running = false;
                logBoth("Failed to enable gateway: " + t);
            }
        } else {
            stop();
        }
        return running;
    }

    // ==================== v3.9 心跳保活支持 ====================

    /**
     * 监听存活自检：由 {@link GatewayWatchdog} 每次心跳调用。
     *
     * <p>专门抓一类只有主动检查才能发现的「僵尸网关」：accept 线程因异常退出了，
     * 但 {@code running} 仍是 true —— 此时 {@code /status} 还能被前一轮已建立的
     * 连接应答，端口上却已经没人 accept 新连接，表现成客户端随机连不上。</p>
     *
     * <p><b>不</b>在这里计心跳数：心跳次数的语义是「看门狗跳了几次」，
     * 计入本类会在网关停用时停止累计，面板上就分不清「心跳关着」和
     * 「心跳开着但网关停着」。计数统一由看门狗记。</p>
     */
    public void ensureListenerAlive() {
        if (!running) {
            return;
        }
        if (acceptThread == null || !acceptThread.isAlive()) {
            logBoth("[heartbeat] accept thread is gone, restarting listener");
            setEnabled(false);
            setEnabled(true);
        }
    }

    /** 监听端口 */
    public int getPort() {
        return port;
    }

    private void handleClient(Socket socket) {
        OutputStream out = null;
        try {
            // 读取阶段用较短超时，读完再放宽到响应等待超时
            socket.setSoTimeout((int) REQUEST_READ_TIMEOUT_MS);
            java.io.InputStream rawIn = socket.getInputStream();
            out = socket.getOutputStream();

            // Read request line (readHttpLine reads raw bytes from InputStream,
            // compatible with the byte-exact handling needed for Content-Length)
            String requestLine = readHttpLine(rawIn);
            if (requestLine == null || requestLine.isEmpty()) {
                logBoth("Empty request line, closing");
                socket.close();
                return;
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                sendResponse(out, 400, "application/json", "{\"error\":{\"message\":\"Bad Request\"}}");
                socket.close();
                return;
            }
            String method = parts[0];
            String uri = parts[1];

            // Read headers
            Map<String, String> headers = new HashMap<>();
            String line;
            int contentLength = 0;
            while ((line = readHttpLine(rawIn)) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    String key = line.substring(0, idx).trim().toLowerCase();
                    String val = line.substring(idx + 1).trim();
                    headers.put(key, val);
                    if (key.equals("content-length")) {
                        try { contentLength = Integer.parseInt(val); } catch (Exception ignored) {}
                    }
                }
            }

            // Read body if present
            // 关键修复：Content-Length 是字节数，必须按字节读取后整体 UTF-8 解码。
            // 旧实现用 char[] 读同样数量的字符，请求含中文/emoji 时字节数 > 字符数，
            // 循环会一直阻塞等待永不到来的后续数据，读超时后以 Empty reply 收场。
            String body = "";
            if (contentLength > 0) {
                byte[] bodyBytes = new byte[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int r = rawIn.read(bodyBytes, read, contentLength - read);
                    if (r == -1) break;
                    read += r;
                }
                body = new String(bodyBytes, 0, read, StandardCharsets.UTF_8);
            }

            logBoth("REQ " + method + " " + uri + " len=" + contentLength + " bodyRead=" + body.length());

            // 请求已完整读完，切到响应等待超时
            socket.setSoTimeout((int) STREAM_TIMEOUT_MS);

            // CORS preflight
            if ("OPTIONS".equalsIgnoreCase(method)) {
                Response resp = new Response(204, "text/plain", "");
                addCorsHeaders(resp);
                writeResponse(out, resp);
                socket.close();
                return;
            }

            Response resp;
            if ("/v1/models".equals(uri) && "GET".equalsIgnoreCase(method)) {
                if (!checkApiKey(headers)) {
                    resp = unauthorized();
                } else {
                    resp = handleModels();
                }
            } else if ("/health".equals(uri) && "GET".equalsIgnoreCase(method)) {
                // 探活故意不鉴权：否则 API Key 开启后，任何探活都会得到 401，
                // 调用方没法把「服务活着但要鉴权」和「服务根本没起来」区分开。
                // 响应体不含任何配置信息。
                resp = new Response(200, "application/json", GatewayStats.health().toString());
            } else if ("/status".equals(uri) && "GET".equalsIgnoreCase(method)) {
                if (!checkApiKey(headers)) {
                    resp = unauthorized();
                } else {
                    resp = handleStatus();
                }
            } else if ("/v1/chat/completions".equals(uri) && "POST".equalsIgnoreCase(method)) {
                if (!openAiFormatEnabled()) {
                    resp = formatDisabled("OpenAI");
                } else if (!checkApiKey(headers)) {
                    resp = unauthorized();
                } else {
                    resp = handleChatCompletions(body, out, null);
                    if (resp == null) {
                        // v3.6 真流式：响应头与分片已在持锁期间直接写进 socket，
                        // 这里不能再走 writeResponse，否则会往同一连接写第二份响应。
                        logBoth("RESP 200 " + uri + " (chunked stream written)");
                        return;
                    }
                }
            } else if ("/v1/messages".equals(uri) && "POST".equalsIgnoreCase(method)) {
                if (!anthropicFormatEnabled()) {
                    resp = formatDisabled("Anthropic");
                } else if (!checkApiKey(headers)) {
                    resp = unauthorized();
                } else {
                    // Anthropic 走同一个注入链路，只是请求/响应体格式不同；
                    // 复用 OpenAI 分支的解析与重试逻辑，仅在出入参上做转换。
                    resp = handleChatCompletions(body, out, "anthropic");
                    if (resp == null) {
                        logBoth("RESP 200 " + uri + " (anthropic chunked stream written)");
                        return;
                    }
                }
            } else if ("/v1/completions".equals(uri) && "POST".equalsIgnoreCase(method)) {
                if (!openAiFormatEnabled()) {
                    resp = formatDisabled("OpenAI");
                } else if (!checkApiKey(headers)) {
                    resp = unauthorized();
                } else {
                    resp = handleChatCompletions(body, out, "legacy");
                    if (resp == null) {
                        logBoth("RESP 200 " + uri + " (legacy chunked stream written)");
                        return;
                    }
                }
            } else {
                resp = new Response(404, "application/json",
                        "{\"error\":{\"message\":\"Not Found\",\"type\":\"invalid_request_error\"}}");
            }
            addCorsHeaders(resp);
            writeResponse(out, resp);
            logBoth("RESP " + resp.code + " " + uri + " bodyLen=" + resp.body.length());
        } catch (Exception e) {
            logBoth("Client error: " + e);
            // 关键修复：异常时发送错误响应，避免客户端收到 Empty reply
            if (out != null) {
                try {
                    sendResponse(out, 500, "application/json",
                            "{\"error\":{\"message\":\"Internal server error: " + e.getMessage() + "\"}}");
                } catch (Exception se) {
                    logBoth("Failed to send error response: " + se);
                }
            }
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * 按字节读取一行 HTTP 头（以 \n 结尾，兼容 \r\n），返回不含行尾的字符串；流结束返回 null。
     * 走原始 InputStream 而非 BufferedReader，避免其内部预读把请求体字节提前吞掉，
     * 也让后续请求体可以严格按 Content-Length 字节数读取。
     */
    private static String readHttpLine(java.io.InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b == '\r') continue;
            bos.write(b);
        }
        if (b == -1 && bos.size() == 0) return null;
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 配置开启鉴权时校验 Authorization: Bearer <key>；关闭时直接放行 */
    private boolean checkApiKey(Map<String, String> headers) {
        if (!ConfigManager.isApiKeyEnabledInTarget()) {
            return true;
        }
        String expected = ConfigManager.getApiKeyInTarget();
        if (expected == null || expected.trim().isEmpty()) {
            // 开关打开但未设置密钥：视为不校验，避免把服务锁死。
            return true;
        }
        String auth = headers.get("authorization");
        if (auth == null) {
            return false;
        }
        String normalizedAuth = auth.trim();
        String token = normalizedAuth;
        if (normalizedAuth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = normalizedAuth.substring(7).trim();
        }
        String normalizedExpected = expected.trim();
        if (token.isEmpty() || token.length() != normalizedExpected.length()) {
            return false;
        }
        // 只去除首尾空白，不改变 key 中间的字符；使用恒定时间比较。
        int diff = 0;
        for (int i = 0; i < normalizedExpected.length(); i++) {
            diff |= token.charAt(i) ^ normalizedExpected.charAt(i);
        }
        return diff == 0;
    }

    private Response unauthorized() {
        return new Response(401, "application/json",
                "{\"error\":{\"message\":\"Invalid API key\",\"type\":\"invalid_request_error\"}}");
    }

    /** 该格式被 API 格式设置关闭时的响应 */
    private Response formatDisabled(String which) {
        GatewayStats.countError("format_disabled");
        return new Response(404, "application/json",
                "{\"error\":{\"message\":\"" + which
                        + " 端点已关闭：请在设置页把 API 格式改为对应选项。\","
                        + "\"type\":\"invalid_request_error\"}}");
    }

    private boolean openAiFormatEnabled() {
        String f = ConfigManager.getApiFormatInTarget();
        return ConfigManager.API_FORMAT_OPENAI.equals(f) || ConfigManager.API_FORMAT_BOTH.equals(f);
    }

    private boolean anthropicFormatEnabled() {
        String f = ConfigManager.getApiFormatInTarget();
        return ConfigManager.API_FORMAT_ANTHROPIC.equals(f)
                || ConfigManager.API_FORMAT_BOTH.equals(f);
    }

    /** 运行统计（对应参照物的 GET /status） */
    private Response handleStatus() {
        return new Response(200, "application/json", GatewayStats.snapshot().toString());
    }

    private Response handleModels() {
        try {
            JSONArray data = new JSONArray();

            // 两个模型 id：xiaobu 走 OpenAI 协议，xiaobu-anthropic 显式指向
            // Anthropic 协议。客户端只需换 model 名即可，无需改 Base URL。
            data.put(modelEntry("xiaobu", "oppo-xiaobu"));
            data.put(modelEntry("xiaobu-anthropic", "oppo-xiaobu"));

            JSONObject result = new JSONObject();
            result.put("object", "list");
            result.put("data", data);

            return new Response(200, "application/json", result.toString());
        } catch (Exception e) {
            logBoth("handleModels error: " + e.getMessage());
            return new Response(500, "application/json",
                    "{\"error\":{\"message\":\"Internal Error\",\"type\":\"api_error\"}}");
        }
    }

    private JSONObject modelEntry(String id, String owner) throws Exception {
        JSONObject model = new JSONObject();
        model.put("id", id);
        model.put("object", "model");
        model.put("created", System.currentTimeMillis() / 1000);
        model.put("owned_by", owner);
        return model;
    }

    /**
     * 对话主入口。
     *
     * @param format {@code null}=OpenAI（默认）、{@code "anthropic"}、{@code "legacy"}
     */
    private Response handleChatCompletions(String body, OutputStream out, String format) {
        JSONObject request;
        try {
            request = new JSONObject(body);
        } catch (Exception e) {
            GatewayStats.countError("invalid_json");
            return new Response(400, "application/json",
                    "{\"error\":{\"message\":\"Invalid JSON\",\"type\":\"invalid_request_error\"}}");
        }

        GatewayStats.incrRequests();
        GatewayStats.incrActive();
        long startedAt = System.currentTimeMillis();

        // 工具调用层的日志与网关同源：排查时要能在同一份落盘日志里对着看
        // 「注入文本多长 / 解析出几个调用」，否则两处日志互相对不上时间轴。
        ToolCallPrompt.setLogger(new ToolCallPrompt.Logger() {
            @Override
            public void log(String msg) {
                logBoth("[toolcall] " + msg);
            }
        });

        // 并发限流：拿不到令牌直接 429，避免把小布对话链路压垮
        Semaphore limiter = concurrencyLimiter;
        boolean acquired = false;
        if (limiter != null) {
            acquired = limiter.tryAcquire();
            if (!acquired) {
                GatewayStats.decrActive();
                GatewayStats.incrFailed();
                GatewayStats.countError("concurrency_limit");
                logBoth("Rejected: concurrency limit reached");
                return new Response(429, "application/json",
                        "{\"error\":{\"message\":\"Too many concurrent requests\","
                                + "\"type\":\"rate_limit_error\"}}");
            }
        }
        try {
            Response resp = doChatCompletions(request, out, format);
            if (resp != null && resp.code >= 400) {
                GatewayStats.incrFailed();
            }
            return resp;
        } catch (Throwable t) {
            GatewayStats.incrFailed();
            GatewayStats.countError("internal");
            GatewayStats.setLastError(String.valueOf(t.getMessage()));
            logBoth("handleChatCompletions error: " + t);
            return new Response(500, "application/json",
                    "{\"error\":{\"message\":\"Internal Error\",\"type\":\"api_error\"}}");
        } finally {
            if (acquired && limiter != null) {
                limiter.release();
            }
            GatewayStats.decrActive();
            GatewayStats.addLatency(System.currentTimeMillis() - startedAt);
        }
    }

    /**
     * 从请求体里取用户消息文本。
     *
     * <p>三种格式的取法不同：</p>
     * <ul>
     *   <li>OpenAI / Anthropic：最后一条 {@code role=user} 的 {@code content}；
     *       Anthropic 的 content 还可能是 {@code [{type:text,text:...}]} 数组；</li>
     *   <li>Legacy /v1/completions：{@code prompt} 字段，可以是字符串也可能是字符串数组。</li>
     * </ul>
     */
    private String extractUserMessage(JSONObject request, String format) {
        if ("legacy".equals(format)) {
            Object prompt = request.opt("prompt");
            if (prompt instanceof JSONArray) {
                JSONArray arr = (JSONArray) prompt;
                if (arr.length() > 0) {
                    return String.valueOf(arr.opt(0));
                }
                return null;
            }
            if (prompt != null) {
                return String.valueOf(prompt);
            }
            // 兼容部分客户端会用 messages 调 legacy 端点
            return extractLastUserMessage(request);
        }
        return extractLastUserMessage(request);
    }

    private Response doChatCompletions(JSONObject request, OutputStream out, String format) {
        boolean stream = request.optBoolean("stream", false);
        // v3.6 真流式：只有「stream=true 且客户端要 chunked」时才走直接写 socket 的路径。
        // 其余情况保持旧行为（攒完整报文一次性返回），避免影响既有客户端。
        boolean chunked = stream
                && out != null
                && ConfigManager.isChunkedStreamEnabledInTarget();
        String requestId = UUID.randomUUID().toString().replace("-", "");
        logBoth("Chat request, format=" + (format == null ? "openai" : format)
                + ", stream=" + stream + ", chunked=" + chunked + ", id=" + requestId);

        String userMsg = extractUserMessage(request, format);
        if (userMsg != null) {
            logBoth("User msg: " + userMsg.substring(0, Math.min(50, userMsg.length())));
        }

        // v3.8 工具调用：仅 OpenAI 格式（Anthropic 的 tool_use 与 legacy 契约语义不同，
        // 本轮不混用，避免把「半套支持」当成完整支持）。
        // 带工具时必须折叠**整段** messages —— 工具结果（role=tool）与历史调用是
        // 模型继续调用所必需的上文；无工具路径只取最后一条 user，够用且更省预算。
        boolean openAiFormat = (format == null || "openai".equals(format));
        JSONArray tools = openAiFormat ? request.optJSONArray("tools") : null;
        boolean toolsRequest = tools != null && tools.length() > 0;
        String toolInjectText = null;
        if (toolsRequest) {
            // v3.9 面板指标：工具调用请求单独计数，且必须在注入前记，
            // 否则「注入失败但确实带了 tools」的请求会从计数里消失
            GatewayStats.incrToolRequests();
            toolInjectText = ToolCallBridge.buildInjectText(
                    request, ConfigManager.getSystemPromptInTarget());
            logBoth("Tool request: tools=" + tools.length()
                    + ", tool_choice=" + request.opt("tool_choice")
                    + ", injectText=" + toolInjectText.length() + "c");
        }

        // v3.13 后台静默：这里不再做「请求前无条件唤醒」。
        // v3.6 的原假设「小布在后台 → 注入零回调」经真机取证不成立（见 runInjectedRound
        // 之外的降级逻辑）：后台注入在锁屏 / 流式 / 多轮 / tools 场景下均能拿到回调。
        // 唤醒改为「零回调时降级」，放在对话框锁内按轮次决定，避免每次都把小布拽到前台。

        // 每个 HTTP 请求都必须先切断上一轮；否则复用 recordId 时会把旧回答当成本轮结果。
        // 小布只有一个对话框、回调不带 requestId，故整轮（建屏障 → 注入 → 消费回答）
        // 都在同一把锁内完成：否则后到的请求会重置前一个请求正在读取的会话对象。
        dialogLock.lock();
        try {
            int maxRetry = ConfigManager.isAutoRetryEnabledInTarget()
                    ? ConfigManager.getAutoRetryMaxInTarget() : 0;
            ConversationSession session = null;

            // v3.13 后台静默：唤醒从「请求前无条件执行」改为「零回调后降级执行」。
            // 后台注入（引擎已就绪时）实测可直接拿到回调，无需把小布拽到前台；
            // 真正的失败场景是「小布进程刚起、对话引擎尚未预热」——此时后台首轮
            // 必然零回调。故降级放在**首轮**零回调之后，而不是等所有退避重试耗尽
            // （默认 maxRetry=3 × 60s 超时会让冷启动请求白等 4 分钟才唤起）。
            boolean hasText = userMsg != null && !userMsg.isEmpty();
            boolean allowWake = hasText && ConfigManager.isAutoWakeEnabledInTarget();
            boolean wakeAttempted = false;
            boolean wakeFailed = false;

            // 重试语义：只有「注入成功但零回调」才重试。这类失败是小布的偶发丢回调，
            // 重来一轮通常能成。会话已产生内容但未 completed 属于正在生成，不能重试
            // ——那会把半截回答丢掉并浪费一次完整的等待窗口。
            for (int attempt = 0; attempt <= maxRetry; attempt++) {
                ConversationSession.beginExternalRound();
                session = runInjectedRound(requestId, userMsg, toolInjectText);
                if (session != null) {
                    if (attempt > 0) {
                        GatewayStats.incrAutoRetries();
                    }
                    break;
                }

                // 首轮零回调即降级：拉前台后立刻重试一次（这是冷启动/引擎未就绪的
                // 典型形态，继续在后台空耗退避窗口没有意义）。锁屏下唤醒会失败，
                // 此时直接放弃并返回 503，不做无意义的多轮重试。
                if (allowWake && !wakeAttempted) {
                    wakeAttempted = true;
                    long wakeStart = System.currentTimeMillis();
                    boolean foreground = AutoWaker.ensureForeground();
                    logBoth("No callback in background round; fallback AutoWake result=" + foreground
                            + ", cost=" + (System.currentTimeMillis() - wakeStart) + "ms");
                    if (!foreground) {
                        GatewayStats.countError("not_foreground");
                        wakeFailed = true;
                        break;
                    }
                    GatewayStats.incrAutoRetries();
                    ConversationSession.beginExternalRound();
                    session = runInjectedRound(requestId, userMsg, toolInjectText);
                    if (session != null) {
                        break;
                    }
                }

                if (attempt < maxRetry) {
                    logBoth("No callback on attempt " + (attempt + 1) + "/" + (maxRetry + 1)
                            + ", retrying after backoff");
                    try {
                        Thread.sleep(RETRY_BACKOFF_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            if (session == null) {
                if (wakeFailed) {
                    // 唤醒也失败了（多为锁屏 / 系统拦截后台拉起）。返回 503 而不是 200：
                    // 这是「暂时不可用、重试可能成功」，客户端与 OpenAI SDK 能据此走重试，
                    // 塞在 200 里会被当成正常回答内容。
                    logBoth("No callback and AutoWake failed; returning 503 not_foreground");
                    return new Response(503, "application/json",
                            "{\"error\":{\"message\":\"XiaoBu is not in foreground and could not be woken. "
                                    + "Unlock the screen or open XiaoBu once, then retry.\","
                                    + "\"type\":\"service_unavailable\"}}");
                }
                GatewayStats.countError("no_callback");
                logBoth("No active conversation after retries, returning error body");
                // 保留 200 + error 体：与旧版行为一致，避免破坏既有客户端；
                // 调用方可据 error 字段判断。
                return new Response(200, "application/json",
                        "{\"error\":{\"message\":\"No active conversation after "
                                + (maxRetry + 1) + " attempt(s). XiaoBu produced no callback.\","
                                + "\"type\":\"upstream_error\"}}");
            }

            GatewayStats.incrInjectedRounds();

            if (chunked) {
                // 返回 null 表示响应已由本方法直接写入并关闭
                return writeChunkedStreamResponse(out, session, requestId, format,
                        request, toolsRequest);
            }
            if (stream) {
                return buildStreamResponse(session, requestId, format, request, toolsRequest);
            }
            return buildNonStreamResponse(session, requestId, format, request, toolsRequest);
        } finally {
            dialogLock.unlock();
        }
    }

    /**
     * 在对话框串行锁内完成的注入与等待阶段。
     *
     * @param toolInjectText 带工具请求时已组装好的注入文本（含系统提示词与工具协议）；
     *                       非空时优先使用，且<b>不再</b>追加系统提示词 —— 它已经
     *                       并进折叠文本并参与了预算计算，再拼一次会顶过小布上限。
     * @return 本轮会话；拿不到返回 null（由调用方转成错误响应）
     */
    private ConversationSession runInjectedRound(String requestId, String userMsg,
                                                 String toolInjectText) {
        String injectText;
        if (toolInjectText != null && !toolInjectText.isEmpty()) {
            injectText = toolInjectText;
        } else {
            // 系统提示词：仅拼接到实际注入小布的文本上，不改变返回体
            injectText = userMsg;
            String systemPrompt = ConfigManager.getSystemPromptInTarget();
            if (userMsg != null && systemPrompt != null && !systemPrompt.trim().isEmpty()) {
                injectText = systemPrompt.trim() + "\n\n" + userMsg;
            }
        }

        boolean injected = false;
        ConversationSession session = null;
        if (injectText != null && !injectText.isEmpty()) {
            // 有待注入文本时不要先等待旧会话。先注入，再只等待本轮回调。
            logBoth("Starting new round and injecting user message into XiaoBu");
            injected = MainHook.injectUserMessage(injectText);
            logBoth("injectUserMessage result=" + injected);
        } else {
            // 兼容没有 user message 的请求，但只接受屏障之后登记的会话。
            session = waitForActiveSession(requestId, false);
        }
        if (session == null && injected) {
            // 注入成功后，等待本轮会话被 Hook 登记并收到完整回复
            logBoth("Waiting for response after injection...");
            session = waitForActiveSession(requestId, true);
        }
        return session;
    }

    private ConversationSession waitForActiveSession(String requestId, boolean waitForInjectedResponse) {
        // 只接受本轮屏障之后登记的会话，旧轮次即使仍在内存中也不可见。
        ConversationSession s = null;
        // 注入后的回调可能受网络和主线程调度影响，等待上限由设置页的
        // 「请求超时」控制（默认 60 秒），而非写死的 60 秒。
        long deadline = System.currentTimeMillis()
                + ConfigManager.getRequestTimeoutMsInTarget();
        while (System.currentTimeMillis() < deadline) {
            ConversationSession candidate = ConversationSession.getCurrentRound(5 * 60 * 1000L);
            if (candidate != null) {
                if (waitForInjectedResponse) {
                    if (candidate.isCompleted() || candidate.hasContent()) {
                        s = candidate;
                        break;
                    }
                } else if (!candidate.isCompleted()) {
                    s = candidate;
                    break;
                }
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (s == null) {
            logBoth("No current-round session within wait window, request=" + requestId);
            return null;
        }
        logBoth("Session ready for request " + requestId + ", fullLen=" + s.getFullContent().length());
        return s;
    }

    /**
     * SSE 流式响应：把所有 chunk 收集为完整 SSE 报文后一次性返回。
     *
     * <p>v3.4 关键修正：旧实现用 {@code session.pollContent()}（内部固定阻塞 60 秒）
     * 取片段，队列一旦短暂为空就被按住整分钟——此时小布的回答往往已经产出完毕，
     * 但 chunk 全卡在本地，客户端只能等到超时。表现为「等 1~2 分钟才一次性收到
     * 全部内容」。现在改为 50ms 粒度轮询，由本方法自行掌握超时。</p>
     */
    private Response buildStreamResponse(ConversationSession session, String requestId,
                                         String format, JSONObject request,
                                         boolean toolsRequest) {
        if (toolsRequest) {
            // 带工具时不能边收边发：整段回答在收完之前无法判断它是「工具调用块」
            // 还是「普通正文」，把调用块的 XML 原样流给客户端等于给了一个坏响应。
            // 调用块本身很短，缓冲的代价可忽略。
            return buildBufferedToolStreamResponse(session, requestId, format, request);
        }
        StringBuilder sb = new StringBuilder();
        long startTime = System.currentTimeMillis();
        String completionId = "chatcmpl-" + requestId;
        boolean anthropic = "anthropic".equals(format);

        while (true) {
            String content = session.pollContent(POLL_GRANULARITY_MS);
            if (content != null) {
                sb.append(anthropic
                        ? anthropicEvent("content_block_delta", requestId, content, null)
                        : "data: " + buildChunk(completionId, content, false) + "\n\n");
                // 队列可能还有积压，本轮继续取，不再额外等待
                continue;
            }

            if (session.isCompleted()) {
                // 收尾：把队列剩余内容全部取空，避免最后几个片段丢失
                String remaining;
                while ((remaining = session.pollContent(0)) != null) {
                    sb.append(anthropic
                            ? anthropicEvent("content_block_delta", requestId, remaining, null)
                            : "data: " + buildChunk(completionId, remaining, false) + "\n\n");
                }
                if (anthropic) {
                    sb.append(anthropicEvent("content_block_stop", requestId, null, null));
                    sb.append(anthropicEvent("message_delta", requestId, null, "end_turn"));
                    sb.append(anthropicEvent("message_stop", requestId, null, null));
                } else {
                    sb.append("data: ").append(buildChunk(completionId, null, true)).append("\n\n");
                    sb.append("data: [DONE]\n\n");
                }
                break;
            }

            if (System.currentTimeMillis() - startTime > STREAM_TIMEOUT_MS) {
                logBoth("Stream timeout for " + requestId);
                break;
            }
        }

        Response resp = new Response(200, "text/event-stream", sb.toString());
        resp.headers.put("Cache-Control", "no-cache");
        resp.headers.put("Connection", "keep-alive");
        return resp;
    }

    /**
     * 带工具请求的（非 chunked）流式响应：缓冲到完成再重放为 SSE。
     *
     * <p>为什么不能边收边发：小布的输出里，工具调用是一段 XML 块。在整段回答
     * 收完之前无法判断这次是「调用工具」还是「普通回答」，而两种情况的
     * {@code finish_reason} 与 delta 形状完全不同（{@code tool_calls} vs {@code stop}）。
     * 把调用块的 XML 原样流给客户端，对方会把它当正文渲染出来。</p>
     *
     * <p>缓冲的代价可接受：调用块本身只有一两百字符，且客户端此时本来就要等
     * 工具结果，不差这一小段时序差。真流式的价值在长文本回答上，那条路径
     * （无工具请求）保持原样未动。</p>
     */
    /**
     * v3.9 把「本轮回吐了几个 tool_calls、叫什么名字」记进运行统计。
     *
     * <p>三个响应路径（非流式 / 缓冲流式 / chunked）都会调用它，因此收口在这里，
     * 避免出现「面板上某个路径的调用数永远为 0」这类只有特定客户端才暴露的偏差。</p>
     */
    private static void recordToolCalls(JSONArray calls) {
        if (calls == null || calls.length() == 0) {
            return;
        }
        GatewayStats.incrToolCalls(calls.length());
        java.util.List<String> names = ToolCallBridge.callNames(calls);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(names.get(i));
        }
        GatewayStats.setLastToolNames(sb.toString());
    }

    private Response buildBufferedToolStreamResponse(ConversationSession session, String requestId,
                                                     String format, JSONObject request) {
        if (!session.isCompleted()) {
            long deadline = System.currentTimeMillis() + ConfigManager.getRequestTimeoutMsInTarget();
            while (!session.isCompleted() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        String fullContent = session.getFullContent();
        String completionId = "chatcmpl-" + requestId;
        JSONArray calls = ToolCallBridge.extractCalls(request, fullContent);
        recordToolCalls(calls);
        StringBuilder sb = new StringBuilder();

        // 首帧：声明角色（SDK 按它初始化 assistant 消息容器）
        try {
            JSONObject first = buildChunk(completionId, null, false);
            first.getJSONArray("choices").getJSONObject(0).getJSONObject("delta")
                    .put("role", "assistant");
            sb.append("data: ").append(first).append("\n\n");
        } catch (Exception ignored) {
        }

        if (calls.length() > 0) {
            logBoth("Tool calls parsed (stream): " + calls.length()
                    + ", names=" + ToolCallBridge.callNames(calls));
            String head = ToolCallBridge.headText(fullContent);
            if (!head.isEmpty()) {
                sb.append("data: ").append(buildChunk(completionId, head, false)).append("\n\n");
            }
            sb.append("data: ").append(ToolCallBridge.buildToolCallsChunk(completionId, calls))
                    .append("\n\n");
            JSONObject fin = buildChunk(completionId, null, true);
            try {
                fin.getJSONArray("choices").getJSONObject(0).put("finish_reason", "tool_calls");
            } catch (Exception ignored) {
            }
            sb.append("data: ").append(fin).append("\n\n");
        } else {
            if (ToolCallCodec.looksLikeMissedCall(fullContent)) {
                logBoth("Suspected missed tool call (unparsable, stream), returned as plain answer");
            }
            sb.append("data: ").append(buildChunk(completionId, fullContent, false)).append("\n\n");
            sb.append("data: ").append(buildChunk(completionId, null, true)).append("\n\n");
        }
        sb.append("data: [DONE]\n\n");

        Response resp = new Response(200, "text/event-stream", sb.toString());
        resp.headers.put("Cache-Control", "no-cache");
        resp.headers.put("Connection", "keep-alive");
        return resp;
    }

    /**
     * v3.6 真流式：用 {@code Transfer-Encoding: chunked} 分片写回，边收边发。
     *
     * <p><b>解决什么</b>：v3.5 的做法是把所有 SSE 报文先攒进 StringBuilder，
     * 等会话完成才一次性 {@code writeResponse}。HTTP 层面没有分片 flush，
     * 所以哪怕小布早就吐出了首字，客户端也要等整段回答结束才开始显示，
     * 「流式」只体现在响应体格式上，不体现在时序上。</p>
     *
     * <p>本方法把响应头先发出去，之后每取到一个片段就写一个 HTTP chunk 并立即
     * flush，客户端可以真正的边收边渲染。</p>
     *
     * <p><b>为什么在持锁期间写</b>：小布只有一个对话框，回答回调也不带 requestId，
     * 串行锁保护的是「建屏障 → 注入 → 消费回答」整段；若在锁外消费队列，
     * 下一个请求的 {@code beginExternalRound()} 会清空队列并重置会话，
     * 本请求的剩余片段就丢了。代价是慢客户端会占住对话框锁——但该锁本来
     * 就是为「一次只服务一个外部请求」而设的，行为与 v3.5 一致。</p>
     *
     * <p><b>异常处理</b>：一旦响应头已经发出，就不能再改状态码了；中途写失败
     * （客户端断开）只能记日志并结束，不能回写 JSON 错误体，否则会在同一个
     * 连接上追加格式错乱的数据。</p>
     *
     * @return 恒为 null —— 表示响应已由本方法直接写进 socket，调用方不得再写第二次
     */
    private Response writeChunkedStreamResponse(OutputStream out, ConversationSession session,
                                                String requestId, String format,
                                                JSONObject request, boolean toolsRequest) {
        String completionId = "chatcmpl-" + requestId;
        boolean anthropic = "anthropic".equals(format);
        long startTime = System.currentTimeMillis();
        int fragmentCount = 0;

        try {
            // 先发响应头。注意：不写 Content-Length，改用 chunked 分帧。
            StringBuilder head = new StringBuilder();
            head.append("HTTP/1.1 200 OK\r\n");
            head.append("Content-Type: text/event-stream\r\n");
            head.append("Transfer-Encoding: chunked\r\n");
            head.append("Cache-Control: no-cache\r\n");
            head.append("Connection: keep-alive\r\n");
            if (ConfigManager.isCorsEnabledInTarget()) {
                head.append("Access-Control-Allow-Origin: *\r\n");
                head.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n");
                head.append("Access-Control-Allow-Headers: Content-Type, Authorization, x-api-key, "
                        + "anthropic-version\r\n");
            }
            head.append("\r\n");
            out.write(head.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            logBoth("Chunked stream headers sent for " + requestId);

            // Anthropic 协议要求先发 message_start / content_block_start，
            // 否则 SDK 解析首片 delta 时会因为缺少 message 容器而报错。
            if (anthropic) {
                writeChunk(out, anthropicEvent("message_start", requestId, null, null));
                writeChunk(out, anthropicEvent("content_block_start", requestId, null, null));
            }

            if (toolsRequest) {
                // 带工具时不能边收边发：整段回答收完之前无法判断这次是「调用工具」
                // 还是「普通回答」，而两者的 finish_reason 与 delta 形状完全不同。
                // 把调用块的 XML 原样流给客户端，对方会把它当正文渲染。
                while (!session.isCompleted()
                        && System.currentTimeMillis() - startTime < STREAM_TIMEOUT_MS) {
                    try {
                        Thread.sleep(POLL_GRANULARITY_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                String full = session.getFullContent();
                JSONArray calls = ToolCallBridge.extractCalls(request, full);
                recordToolCalls(calls);
                if (calls.length() > 0) {
                    logBoth("Tool calls parsed (chunked): " + calls.length()
                            + ", names=" + ToolCallBridge.callNames(calls));
                    String headText = ToolCallBridge.headText(full);
                    if (!headText.isEmpty()) {
                        writeChunk(out, "data: " + buildChunk(completionId, headText, false) + "\n\n");
                    }
                    writeChunk(out, "data: " + ToolCallBridge.buildToolCallsChunk(completionId, calls)
                            + "\n\n");
                    JSONObject fin = buildChunk(completionId, null, true);
                    try {
                        fin.getJSONArray("choices").getJSONObject(0)
                                .put("finish_reason", "tool_calls");
                    } catch (Exception ignored) {
                    }
                    writeChunk(out, "data: " + fin + "\n\n");
                } else {
                    if (ToolCallCodec.looksLikeMissedCall(full)) {
                        logBoth("Suspected missed tool call (unparsable, chunked)");
                    }
                    if (!full.isEmpty()) {
                        writeChunk(out, "data: " + buildChunk(completionId, full, false) + "\n\n");
                    }
                    writeChunk(out, "data: " + buildChunk(completionId, null, true) + "\n\n");
                }
                writeChunk(out, "data: [DONE]\n\n");
                out.write("0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                logBoth("Tool-call chunked stream done for " + requestId
                        + ", calls=" + calls.length()
                        + ", cost=" + (System.currentTimeMillis() - startTime) + "ms");
                return null;
            }

            while (true) {
                String content = session.pollContent(POLL_GRANULARITY_MS);
                if (content != null) {
                    writeChunk(out, anthropic
                            ? anthropicEvent("content_block_delta", requestId, content, null)
                            : "data: " + buildChunk(completionId, content, false) + "\n\n");
                    fragmentCount++;
                    // 队列可能还有积压，本轮继续取，不再额外等待
                    continue;
                }

                if (session.isCompleted()) {
                    // 收尾：把队列剩余内容全部取空，避免最后几个片段丢失
                    String remaining;
                    while ((remaining = session.pollContent(0)) != null) {
                        writeChunk(out, anthropic
                                ? anthropicEvent("content_block_delta", requestId, remaining, null)
                                : "data: " + buildChunk(completionId, remaining, false) + "\n\n");
                        fragmentCount++;
                    }
                    if (anthropic) {
                        writeChunk(out, anthropicEvent("content_block_stop", requestId, null, null));
                        writeChunk(out, anthropicEvent("message_delta", requestId, null, "end_turn"));
                        writeChunk(out, anthropicEvent("message_stop", requestId, null, null));
                    } else {
                        writeChunk(out, "data: " + buildChunk(completionId, null, true) + "\n\n");
                        writeChunk(out, "data: [DONE]\n\n");
                    }
                    // 终止块：零长度 chunk
                    out.write("0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    logBoth("Chunked stream done for " + requestId + ", fragments=" + fragmentCount
                            + ", cost=" + (System.currentTimeMillis() - startTime) + "ms");
                    break;
                }

                if (System.currentTimeMillis() - startTime > STREAM_TIMEOUT_MS) {
                    // 超时不能只 break 走人：chunked 流没有终止块时客户端会一直挂等，
                    // 必须把结束事件与终止块补上，让客户端能正常结束。
                    logBoth("Chunked stream timeout for " + requestId + ", fragments=" + fragmentCount);
                    GatewayStats.countError("stream_timeout");
                    if (anthropic) {
                        writeChunk(out, anthropicEvent("message_stop", requestId, null, null));
                    } else {
                        writeChunk(out, "data: [DONE]\n\n");
                    }
                    out.write("0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    break;
                }
            }
        } catch (IOException e) {
            // 客户端提前断开（Ctrl-C / 超时取消）会走到这里，属于常见情况，不升级为错误
            GatewayStats.incrClientAborted();
            logBoth("Chunked stream aborted for " + requestId + ": " + e);
        }
        return null;
    }

    /**
     * Anthropic SSE 事件编码。
     *
     * <p>Anthropic 的流式协议与 OpenAI 不同：每个事件是
     * {@code event: <type>\ndata: <json>\n\n}，且 message 内容分散在
     * message_start / content_block_delta / message_stop 等事件里。</p>
     */
    private String anthropicEvent(String type, String requestId, String text, String stopReason) {
        try {
            JSONObject data = new JSONObject();
            data.put("type", type);

            if ("message_start".equals(type)) {
                JSONObject message = new JSONObject();
                message.put("id", "msg_" + requestId);
                message.put("type", "message");
                message.put("role", "assistant");
                message.put("model", "xiaobu");
                message.put("content", new JSONArray());
                message.put("stop_reason", JSONObject.NULL);
                message.put("usage", new JSONObject()
                        .put("input_tokens", 0).put("output_tokens", 0));
                data.put("message", message);
            } else if ("content_block_start".equals(type)) {
                data.put("index", 0);
                data.put("content_block", new JSONObject().put("type", "text").put("text", ""));
            } else if ("content_block_delta".equals(type)) {
                data.put("index", 0);
                data.put("delta", new JSONObject()
                        .put("type", "text_delta")
                        .put("text", text == null ? "" : text));
            } else if ("content_block_stop".equals(type)) {
                data.put("index", 0);
            } else if ("message_delta".equals(type)) {
                data.put("delta", new JSONObject()
                        .put("stop_reason", stopReason == null ? "end_turn" : stopReason));
                data.put("usage", new JSONObject().put("output_tokens", 0));
            }

            return "event: " + type + "\ndata: " + data + "\n\n";
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 写一个 HTTP/1.1 chunk：十六进制长度 + CRLF + 数据 + CRLF，并立即 flush。
     *
     * <p>长度必须按 <b>UTF-8 字节数</b> 计算，不能用 String.length()：
     * 中文一个字 3 字节，用字符数当长度会让客户端提前截断、之后的字节全部错位。</p>
     */
    private void writeChunk(OutputStream out, String text) throws IOException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        out.write((Integer.toHexString(data.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(data);
        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private Response buildNonStreamResponse(ConversationSession session, String requestId,
                                            String format, JSONObject request,
                                            boolean toolsRequest) {
        long startTime = System.currentTimeMillis();
        // If the session is already completed, skip waiting
        if (!session.isCompleted()) {
            long deadline = startTime + ConfigManager.getRequestTimeoutMsInTarget();
            while (!session.isCompleted() && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(100); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        String fullContent = session.getFullContent();
        logBoth("Non-stream done, format=" + format + ", completed=" + session.isCompleted()
                + ", contentLen=" + fullContent.length());
        if (fullContent.isEmpty()) {
            GatewayStats.countError("empty_content");
            return new Response(200, "application/json",
                    "{\"error\":{\"message\":\"Timeout waiting for response\","
                            + "\"type\":\"upstream_error\"}}");
        }

        try {
            // v3.8 工具调用：回答里有调用块时返回标准 tool_calls 契约。
            // 只对 OpenAI 格式生效（见 handleChatCompletions 的说明）。
            if (toolsRequest && !"anthropic".equals(format) && !"legacy".equals(format)) {
                JSONArray calls = ToolCallBridge.extractCalls(request, fullContent);
                recordToolCalls(calls);
                if (calls.length() > 0) {
                    logBoth("Tool calls parsed: " + calls.length()
                            + ", names=" + ToolCallBridge.callNames(calls));
                    return new Response(200, "application/json",
                            ToolCallBridge.openAiToolCallsCompletion(requestId, fullContent, calls));
                }
                if (ToolCallCodec.looksLikeMissedCall(fullContent)) {
                    // 模型想调但格式跑偏且兜底也没捞到完整参数：记日志便于定位，
                    // 不自动重试（实测遵循率极高，重试的代价大于收益）。
                    logBoth("Suspected missed tool call (unparsable), returned as plain answer");
                }
            }
            if ("anthropic".equals(format)) {
                return new Response(200, "application/json",
                        anthropicMessage(requestId, fullContent));
            }
            if ("legacy".equals(format)) {
                return new Response(200, "application/json",
                        legacyCompletion(requestId, fullContent));
            }
            return new Response(200, "application/json",
                    openAiCompletion(requestId, fullContent));
        } catch (Exception e) {
            GatewayStats.countError("serialize");
            logBoth("Non-stream error: " + e.getMessage());
            return new Response(500, "application/json",
                    "{\"error\":{\"message\":\"Internal Error\",\"type\":\"api_error\"}}");
        }
    }

    /** OpenAI /v1/chat/completions 响应体 */
    private String openAiCompletion(String requestId, String content) throws Exception {
        JSONObject message = new JSONObject();
        message.put("role", "assistant");
        message.put("content", content);

        JSONObject choice = new JSONObject();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", "stop");

        JSONArray choices = new JSONArray();
        choices.put(choice);

        JSONObject usage = new JSONObject();
        usage.put("prompt_tokens", 0);
        usage.put("completion_tokens", content.length());
        usage.put("total_tokens", content.length());

        JSONObject result = new JSONObject();
        result.put("id", "chatcmpl-" + requestId);
        result.put("object", "chat.completion");
        result.put("created", System.currentTimeMillis() / 1000);
        result.put("model", "xiaobu");
        result.put("choices", choices);
        result.put("usage", usage);
        return result.toString();
    }

    /** Anthropic /v1/messages 非流式响应体 */
    private String anthropicMessage(String requestId, String content) throws Exception {
        JSONObject textBlock = new JSONObject();
        textBlock.put("type", "text");
        textBlock.put("text", content);

        JSONArray blocks = new JSONArray();
        blocks.put(textBlock);

        JSONObject usage = new JSONObject();
        usage.put("input_tokens", 0);
        usage.put("output_tokens", content.length());

        JSONObject result = new JSONObject();
        result.put("id", "msg_" + requestId);
        result.put("type", "message");
        result.put("role", "assistant");
        result.put("model", "xiaobu");
        result.put("content", blocks);
        result.put("stop_reason", "end_turn");
        result.put("stop_sequence", JSONObject.NULL);
        result.put("usage", usage);
        return result.toString();
    }

    /** Legacy /v1/completions 响应体（text 数组，非 choices/message） */
    private String legacyCompletion(String requestId, String content) throws Exception {
        JSONObject choice = new JSONObject();
        choice.put("text", content);
        choice.put("index", 0);
        choice.put("logprobs", JSONObject.NULL);
        choice.put("finish_reason", "stop");

        JSONArray choices = new JSONArray();
        choices.put(choice);

        JSONObject usage = new JSONObject();
        usage.put("prompt_tokens", 0);
        usage.put("completion_tokens", content.length());
        usage.put("total_tokens", content.length());

        JSONObject result = new JSONObject();
        result.put("id", "cmpl-" + requestId);
        result.put("object", "text_completion");
        result.put("created", System.currentTimeMillis() / 1000);
        result.put("model", "xiaobu");
        result.put("choices", choices);
        result.put("usage", usage);
        return result.toString();
    }

    private JSONObject buildChunk(String id, String content, boolean finished) {
        try {
            JSONObject delta = new JSONObject();
            if (content != null) delta.put("content", content);

            JSONObject choice = new JSONObject();
            choice.put("index", 0);
            choice.put("delta", delta);
            choice.put("finish_reason", finished ? "stop" : JSONObject.NULL);

            JSONArray choices = new JSONArray();
            choices.put(choice);

            JSONObject chunk = new JSONObject();
            chunk.put("id", id);
            chunk.put("object", "chat.completion.chunk");
            chunk.put("created", System.currentTimeMillis() / 1000);
            chunk.put("model", "xiaobu");
            chunk.put("choices", choices);
            return chunk;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private String extractLastUserMessage(JSONObject request) {
        try {
            JSONArray messages = request.getJSONArray("messages");
            for (int i = messages.length() - 1; i >= 0; i--) {
                JSONObject msg = messages.getJSONObject(i);
                if (!"user".equals(msg.optString("role"))) continue;

                // content 可能是字符串，也可能是 Anthropic 风格的内容块数组
                Object content = msg.opt("content");
                if (content instanceof String) {
                    return (String) content;
                }
                if (content instanceof JSONArray) {
                    StringBuilder sb = new StringBuilder();
                    JSONArray blocks = (JSONArray) content;
                    for (int j = 0; j < blocks.length(); j++) {
                        JSONObject block = blocks.optJSONObject(j);
                        if (block == null) continue;
                        if ("text".equals(block.optString("type"))) {
                            sb.append(block.optString("text", ""));
                        }
                    }
                    if (sb.length() > 0) return sb.toString();
                }
            }
        } catch (Exception e) {
            logBoth("extractLastUserMessage failed: " + e.getMessage());
        }
        return null;
    }

    /** CORS 响应头：受设置页开关控制（对应参照物的「CORS 允许跨域」） */
    private void addCorsHeaders(Response resp) {
        if (!ConfigManager.isCorsEnabledInTarget()) {
            return;
        }
        resp.headers.put("Access-Control-Allow-Origin", "*");
        resp.headers.put("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        resp.headers.put("Access-Control-Allow-Headers",
                "Content-Type, Authorization, x-api-key, anthropic-version");
    }

    private void sendResponse(OutputStream out, int code, String contentType, String body) throws IOException {
        Response resp = new Response(code, contentType, body);
        writeResponse(out, resp);
    }

    private void writeResponse(OutputStream out, Response resp) throws IOException {
        byte[] bodyBytes = resp.body.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(resp.code).append(" ").append(reasonPhrase(resp.code)).append("\r\n");
        sb.append("Content-Type: ").append(resp.contentType).append("\r\n");
        sb.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        for (Map.Entry<String, String> h : resp.headers.entrySet()) {
            sb.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.write(bodyBytes);
        out.flush();
    }

    /**
     * 状态码对应的原因短语。
     * 旧实现把状态行硬编码成 "200 OK"，限流返回 429 时会写出
     * "HTTP/1.1 429 OK"，语义自相矛盾，部分 HTTP 客户端与网关会据此误判。
     */
    private static String reasonPhrase(int code) {
        // 旧实现把状态行硬编码成 "200 OK"，限流返回 429 时会写出
        // "HTTP/1.1 429 OK"，语义自相矛盾，部分 HTTP 客户端与网关会据此误判。
        switch (code) {
            case 200: return "OK";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 422: return "Unprocessable Entity";
            case 429: return "Too Many Requests";
            case 500: return "Internal Server Error";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            case 504: return "Gateway Timeout";
            default:  return "Unknown";
        }
    }

    private static class Response {
        int code;
        String contentType;
        String body;
        Map<String, String> headers = new HashMap<>();

        Response(int code, String contentType, String body) {
            this.code = code;
            this.contentType = contentType;
            this.body = body != null ? body : "";
        }
    }
}
