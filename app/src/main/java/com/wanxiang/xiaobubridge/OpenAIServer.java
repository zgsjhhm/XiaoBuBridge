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

    public void start() throws IOException {
        // 先 setReuseAddress 再 bind：进程重启后端口处于 TIME_WAIT 时也能立即复用，
        // 避免偶发的 EADDRINUSE 让 HTTP Server 起不来。
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new java.net.InetSocketAddress(port));
        executor = Executors.newCachedThreadPool();
        running = true;

        int maxConcurrency = ConfigManager.getMaxConcurrencyInTarget();
        concurrencyLimiter = new Semaphore(maxConcurrency);
        logBoth("Concurrency limit = " + maxConcurrency);

        Thread acceptThread = new Thread(() -> {
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
        logBoth("OpenAI-compatible server started on port " + port);
        File lf = getLogFile();
        logBoth("Log file = " + (lf != null ? lf.getAbsolutePath() : "(unavailable, logcat only)"));
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        if (executor != null) executor.shutdownNow();
        logBoth("Server stopped");
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
            } else if ("/v1/chat/completions".equals(uri) && "POST".equalsIgnoreCase(method)) {
                if (!checkApiKey(headers)) {
                    resp = unauthorized();
                } else {
                    resp = handleChatCompletions(body, out);
                    if (resp == null) {
                        // v3.6 真流式：响应头与分片已在持锁期间直接写进 socket，
                        // 这里不能再走 writeResponse，否则会往同一连接写第二份响应。
                        logBoth("RESP 200 " + uri + " (chunked stream written)");
                        return;
                    }
                }
            } else {
                resp = new Response(404, "application/json", "{\"error\":{\"message\":\"Not Found\"}}");
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

    private Response handleModels() {
        try {
            JSONObject model = new JSONObject();
            model.put("id", "xiaobu");
            model.put("object", "model");
            model.put("created", System.currentTimeMillis() / 1000);
            model.put("owned_by", "oppo-xiaobu");

            JSONArray data = new JSONArray();
            data.put(model);

            JSONObject result = new JSONObject();
            result.put("object", "list");
            result.put("data", data);

            return new Response(200, "application/json", result.toString());
        } catch (Exception e) {
            logBoth("handleModels error: " + e.getMessage());
            return new Response(500, "application/json", "{\"error\":{\"message\":\"Internal Error\"}}");
        }
    }

    private Response handleChatCompletions(String body, OutputStream out) {
        JSONObject request;
        try {
            request = new JSONObject(body);
        } catch (Exception e) {
            return new Response(400, "application/json", "{\"error\":{\"message\":\"Invalid JSON\"}}");
        }

        // 并发限流：拿不到令牌直接 429，避免把小布对话链路压垮
        Semaphore limiter = concurrencyLimiter;
        boolean acquired = false;
        if (limiter != null) {
            acquired = limiter.tryAcquire();
            if (!acquired) {
                logBoth("Rejected: concurrency limit reached");
                return new Response(429, "application/json",
                        "{\"error\":{\"message\":\"Too many concurrent requests\",\"type\":\"rate_limit_error\"}}");
            }
        }
        try {
            return doChatCompletions(request, out);
        } finally {
            if (acquired && limiter != null) {
                limiter.release();
            }
        }
    }

    private Response doChatCompletions(JSONObject request, OutputStream out) {
        boolean stream = request.optBoolean("stream", false);
        // v3.6 真流式：只有「stream=true 且客户端要 chunked」时才走直接写 socket 的路径。
        // 其余情况保持旧行为（攒完整报文一次性返回），避免影响既有客户端。
        boolean chunked = stream
                && out != null
                && ConfigManager.isChunkedStreamEnabledInTarget();
        String requestId = UUID.randomUUID().toString().replace("-", "");
        logBoth("Chat request, stream=" + stream + ", chunked=" + chunked + ", id=" + requestId);

        String userMsg = extractLastUserMessage(request);
        if (userMsg != null) {
            logBoth("User msg: " + userMsg.substring(0, Math.min(50, userMsg.length())));
        }

        // v3.6 自动唤醒：小布在后台时注入不报错但零回调，必须先拉到前台。
        // 放在锁外：唤醒期间可能涉及 Activity 启动，没必要占着对话框锁。
        if (userMsg != null && !userMsg.isEmpty() && ConfigManager.isAutoWakeEnabledInTarget()) {
            long wakeStart = System.currentTimeMillis();
            boolean foreground = AutoWaker.ensureForeground();
            logBoth("AutoWake result=" + foreground
                    + ", cost=" + (System.currentTimeMillis() - wakeStart) + "ms");
            if (!foreground) {
                logBoth("AutoWake failed; injection would produce no callback, aborting round");
                // 用 503 而不是 200：这是「暂时不可用、重试可能成功」，客户端网关与
                // OpenAI SDK 都能据此走重试逻辑；塞在 200 里会被当成正常回答内容。
                return new Response(503, "application/json",
                        "{\"error\":{\"message\":\"XiaoBu is not in foreground and could not be woken. "
                                + "Unlock the screen or open XiaoBu once, then retry.\","
                                + "\"type\":\"service_unavailable\"}}");
            }
        }

        // 每个 HTTP 请求都必须先切断上一轮；否则复用 recordId 时会把旧回答当成本轮结果。
        // 小布只有一个对话框、回调不带 requestId，故整轮（建屏障 → 注入 → 消费回答）
        // 都在同一把锁内完成：否则后到的请求会重置前一个请求正在读取的会话对象。
        dialogLock.lock();
        try {
            ConversationSession.beginExternalRound();
            ConversationSession session = runInjectedRound(requestId, userMsg);
            if (session == null) {
                logBoth("No active conversation, returning error body");
                return new Response(200, "application/json",
                        "{\"error\":{\"message\":\"No active conversation. Please ask XiaoBu first.\"}}");
            }
            if (chunked) {
                // 返回 null 表示响应已由本方法直接写入并关闭
                return writeChunkedStreamResponse(out, session, requestId);
            }
            return stream ? buildStreamResponse(session, requestId)
                          : buildNonStreamResponse(session, requestId);
        } finally {
            dialogLock.unlock();
        }
    }

    /**
     * 在对话框串行锁内完成的注入与等待阶段。
     *
     * @return 本轮会话；拿不到返回 null（由调用方转成错误响应）
     */
    private ConversationSession runInjectedRound(String requestId, String userMsg) {
        // 系统提示词：仅拼接到实际注入小布的文本上，不改变返回体
        String injectText = userMsg;
        String systemPrompt = ConfigManager.getSystemPromptInTarget();
        if (userMsg != null && systemPrompt != null && !systemPrompt.trim().isEmpty()) {
            injectText = systemPrompt.trim() + "\n\n" + userMsg;
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
        // 注入后的回调可能受网络和主线程调度影响，最多等待 60 秒。
        int maxWait = waitForInjectedResponse ? 240 : 40;
        for (int i = 0; i < maxWait; i++) {
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
    private Response buildStreamResponse(ConversationSession session, String requestId) {
        StringBuilder sb = new StringBuilder();
        long startTime = System.currentTimeMillis();
        String completionId = "chatcmpl-" + requestId;

        while (true) {
            String content = session.pollContent(POLL_GRANULARITY_MS);
            if (content != null) {
                sb.append("data: ").append(buildChunk(completionId, content, false)).append("\n\n");
                // 队列可能还有积压，本轮继续取，不再额外等待
                continue;
            }

            if (session.isCompleted()) {
                // 收尾：把队列剩余内容全部取空，避免最后几个片段丢失
                String remaining;
                while ((remaining = session.pollContent(0)) != null) {
                    sb.append("data: ").append(buildChunk(completionId, remaining, false)).append("\n\n");
                }
                sb.append("data: ").append(buildChunk(completionId, null, true)).append("\n\n");
                sb.append("data: [DONE]\n\n");
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
    private Response writeChunkedStreamResponse(OutputStream out, ConversationSession session, String requestId) {
        String completionId = "chatcmpl-" + requestId;
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
            head.append("Access-Control-Allow-Origin: *\r\n");
            head.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n");
            head.append("Access-Control-Allow-Headers: Content-Type, Authorization\r\n");
            head.append("\r\n");
            out.write(head.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            logBoth("Chunked stream headers sent for " + requestId);

            while (true) {
                String content = session.pollContent(POLL_GRANULARITY_MS);
                if (content != null) {
                    String sse = "data: " + buildChunk(completionId, content, false) + "\n\n";
                    writeChunk(out, sse);
                    fragmentCount++;
                    // 队列可能还有积压，本轮继续取，不再额外等待
                    continue;
                }

                if (session.isCompleted()) {
                    // 收尾：把队列剩余内容全部取空，避免最后几个片段丢失
                    String remaining;
                    while ((remaining = session.pollContent(0)) != null) {
                        writeChunk(out, "data: " + buildChunk(completionId, remaining, false) + "\n\n");
                        fragmentCount++;
                    }
                    writeChunk(out, "data: " + buildChunk(completionId, null, true) + "\n\n");
                    writeChunk(out, "data: [DONE]\n\n");
                    // 终止块：零长度 chunk
                    out.write("0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    logBoth("Chunked stream done for " + requestId + ", fragments=" + fragmentCount
                            + ", cost=" + (System.currentTimeMillis() - startTime) + "ms");
                    break;
                }

                if (System.currentTimeMillis() - startTime > STREAM_TIMEOUT_MS) {
                    // 超时不能只 break 走人：chunked 流没有终止块时客户端会一直挂等，
                    // 必须把 [DONE] 与终止块补上，让客户端能正常结束。
                    logBoth("Chunked stream timeout for " + requestId + ", fragments=" + fragmentCount);
                    writeChunk(out, "data: [DONE]\n\n");
                    out.write("0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    break;
                }
            }
        } catch (IOException e) {
            // 客户端提前断开（Ctrl-C / 超时取消）会走到这里，属于常见情况，不升级为错误
            logBoth("Chunked stream aborted for " + requestId + ": " + e);
        }
        return null;
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

    private Response buildNonStreamResponse(ConversationSession session, String requestId) {
        long startTime = System.currentTimeMillis();
        // If the session is already completed, skip waiting
        if (!session.isCompleted()) {
            while (!session.isCompleted() && (System.currentTimeMillis() - startTime < STREAM_TIMEOUT_MS)) {
                try { Thread.sleep(100); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        String fullContent = session.getFullContent();
        logBoth("Non-stream done, completed=" + session.isCompleted() + ", contentLen=" + fullContent.length());
        if (fullContent.isEmpty()) {
            return new Response(200, "application/json",
                    "{\"error\":{\"message\":\"Timeout waiting for response\"}}");
        }

        try {
            JSONObject message = new JSONObject();
            message.put("role", "assistant");
            message.put("content", fullContent);

            JSONObject choice = new JSONObject();
            choice.put("index", 0);
            choice.put("message", message);
            choice.put("finish_reason", "stop");

            JSONArray choices = new JSONArray();
            choices.put(choice);

            JSONObject usage = new JSONObject();
            usage.put("prompt_tokens", 0);
            usage.put("completion_tokens", fullContent.length());
            usage.put("total_tokens", fullContent.length());

            JSONObject result = new JSONObject();
            result.put("id", "chatcmpl-" + requestId);
            result.put("object", "chat.completion");
            result.put("created", System.currentTimeMillis() / 1000);
            result.put("model", "xiaobu");
            result.put("choices", choices);
            result.put("usage", usage);

            return new Response(200, "application/json", result.toString());
        } catch (Exception e) {
            logBoth("Non-stream error: " + e.getMessage());
            return new Response(500, "application/json", "{\"error\":{\"message\":\"Internal Error\"}}");
        }
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
                if ("user".equals(msg.optString("role"))) return msg.optString("content", null);
            }
        } catch (Exception e) {
            logBoth("extractLastUserMessage failed: " + e.getMessage());
        }
        return null;
    }

    private void addCorsHeaders(Response resp) {
        resp.headers.put("Access-Control-Allow-Origin", "*");
        resp.headers.put("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        resp.headers.put("Access-Control-Allow-Headers", "Content-Type, Authorization");
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
        switch (code) {
            case 200: return "OK";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 429: return "Too Many Requests";
            case 503: return "Service Unavailable";
            case 500: return "Internal Server Error";
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
