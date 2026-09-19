package com.wanxiang.xiaobubridge;

import de.robv.android.xposed.XposedBridge;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 会话管理类：维护活跃的 AI 对话会话
 * 线程安全，支持流式消息缓冲与自动过期清理
 */
public class ConversationSession {

    private static final String TAG = "[XiaoBuBridge]";
    // 会话过期时间：5分钟无活动自动清理
    private static final long SESSION_TIMEOUT_MS = 5 * 60 * 1000L;
    // poll 超时时间：60秒（与 HTTP 请求超时对齐）
    private static final long POLL_TIMEOUT_MS = 60 * 1000L;

    // 全局会话存储，key 为 recordId
    private static final ConcurrentHashMap<String, ConversationSession> sessions = new ConcurrentHashMap<>();

    // 最近一次被 Hook 侧触达的会话。
    // HTTP 侧（OpenAIServer）收到请求时并不知道小布内部的 recordId，
    // 若自己造 key 去 getOrCreate，会建出一个永远收不到内容的空会话。
    // 因此统一改为：Hook 侧登记活跃会话，HTTP 侧取最近活跃的那个。
    private static volatile ConversationSession activeSession;
    // 活跃会话登记时间戳
    private static volatile long activeSessionAt = 0L;

    /**
     * 本轮请求屏障时间戳（v3.3）。
     *
     * 旧实现下 HTTP 侧只认「最近活跃会话」，只要上一轮在 5 分钟窗口内，
     * 就会把上一轮的残留回答当成本轮结果返回。这里在每次外部请求开始时
     * 立一道屏障：早于屏障登记的会话一律判定为上一轮残留，直接剔除。
     */
    private static volatile long roundBarrierAt = 0L;

    // 流式内容队列
    private final LinkedBlockingQueue<String> contentQueue = new LinkedBlockingQueue<>();

    /**
     * 图片结果队列（v3.15，文生图取图用）。
     *
     * <p>与文本 {@link #contentQueue} 并列：文本仍按流式片段投递，图片是
     * 「一轮一张（或几张）」的最终产物，不参与流式拼接。载体是
     * {@link ImageResultCodec.ImageResult}，来源见
     * {@code MainHook.handleBean} 对 {@code AIChatViewBean.payload} 的解析。</p>
     */
    private final LinkedBlockingQueue<ImageResultCodec.ImageResult> imageQueue =
            new LinkedBlockingQueue<>();

    /**
     * 已入队图片的 URL 集合，用于跨通道去重。
     *
     * <p>同一个 bean 会同时从 {@code AIChatDataCenter.r} Hook 与 observer 代理
     * 投递进来，图片与文本一样会被重复上报；URL 相同即同一张图，直接丢弃。</p>
     */
    private final java.util.Set<String> seenImageUrls = java.util.Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    // 是否已完成（收到 isFinal=true）
    private volatile boolean completed = false;
    // 最后活动时间戳
    private volatile long lastActiveTime = System.currentTimeMillis();
    // 本会话对象最近一次被纳入请求轮次的时间。用于拒绝屏障之前的旧回调。
    private volatile long roundStartedAt = System.currentTimeMillis();
    // 最近一次用户提问内容（由 Hook 侧在 chatType=1 时登记，供上下文与注入使用）
    private volatile String lastUserMessage = null;
    // 累积的完整回复（用于非流式返回）
    private final StringBuilder fullContent = new StringBuilder();

    /**
     * 上一片片段的 uniqueId / 原文 / 到达时刻（v3.3）。
     *
     * 用于解决「多通道重复上报」：
     *   - 同一个 bean 会同时从 AIChatDataCenter.r Hook 与 observer 代理（i/j 两个列表）
     *     投递进来，旧实现无条件 append，导致同一片段被重复计入；
     *   - 小布流式回调给出的 content 是「截至目前的累积全文」而非增量，
     *     旧实现按增量拼接，实测产出 "我我我在的。\n需要我在的。..." 这类指数膨胀文本。
     */
    private volatile String lastFragmentId = null;
    private volatile String lastFragmentContent = null;
    private volatile long lastFragmentAt = 0L;

    /** 同一内容在该窗口内重复到达，判为多通道重复投递 */
    private static final long DUPLICATE_WINDOW_MS = 300L;

    /**
     * 获取或创建会话实例
     */
    private static String normalizeKey(String recordId) {
        if (recordId == null || recordId.isEmpty()) {
            XposedBridge.log(TAG + " Warning: recordId is null/empty, using fallback key");
            return "fallback_" + System.currentTimeMillis();
        }
        return recordId;
    }

    public static ConversationSession getOrCreate(String recordId) {
        String key = normalizeKey(recordId);
        ConversationSession session = sessions.computeIfAbsent(key, k -> {
            XposedBridge.log(TAG + " Created new session: " + k);
            return new ConversationSession();
        });
        markActive(session);
        return session;
    }

    /**
     * 处理用户回调并开启/复用一轮会话。重复观察者回调不会再次清空会话。
     */
    public static ConversationSession beginUserRound(String recordId) {
        String key = normalizeKey(recordId);
        ConversationSession session = sessions.computeIfAbsent(key, k -> {
            XposedBridge.log(TAG + " Created new user session: " + k);
            return new ConversationSession();
        });
        if (session.isCompleted() || !session.isEligibleForRound(roundBarrierAt)) {
            session.prepareForNewRound();
        }
        markActive(session);
        return session;
    }

    /**
     * 获取回答回调对应的当前轮会话。旧 recordId 在请求屏障前创建的对象不能重新激活。
     */
    public static ConversationSession getOrCreateForCurrentRound(String recordId) {
        String key = normalizeKey(recordId);
        ConversationSession session = sessions.computeIfAbsent(key, k -> {
            XposedBridge.log(TAG + " Created new current-round session: " + k);
            return new ConversationSession();
        });
        if (!session.isEligibleForRound(roundBarrierAt)) {
            XposedBridge.log(TAG + " Ignored stale answer callback for session " + key);
            return null;
        }
        markActive(session);
        return session;
    }

    /**
     * 为外部 HTTP 请求开始一轮全新的会话。
     *
     * <p><b>调用前提</b>：必须持有 {@code OpenAIServer} 的 dialog 串行锁。
     * 小布只有一个对话框，回调里也没有 requestId 可用于把回答对应回请求，
     * 因此同一时刻只允许一个请求处在「建屏障 → 注入 → 等待回答」阶段。
     * 若两个请求并发注入，A 的回答会被 B 认领（实测 A 问「苹果」、B 问「香蕉」
     * 时两者都返回「香蕉」），故改为上游串行化后再调用本方法。</p>
     */
    public static void beginExternalRound() {
        long now = System.currentTimeMillis();
        roundBarrierAt = now;
        activeSession = null;
        activeSessionAt = 0L;
        for (ConversationSession session : sessions.values()) {
            session.prepareForNewRound(false);
        }
        XposedBridge.log(TAG + " External round started, barrier=" + now);
    }

    /**
     * 登记最近活跃会话（由 Hook 侧在收到数据时调用）
     */
    public static void markActive(ConversationSession session) {
        if (session == null) return;
        activeSession = session;
        activeSessionAt = System.currentTimeMillis();
    }

    /**
     * 取最近活跃的会话，供 HTTP 请求侧对接。
     *
     * @param maxIdleMs 允许的空闲窗口（毫秒）；超过该时长未活动则视为无可用会话，传 &lt;=0 表示不限制
     * @return 最近活跃会话，无可用时返回 null
     */
    public static ConversationSession getMostRecent(long maxIdleMs) {
        ConversationSession session = activeSession;
        if (session == null) return null;
        long now = System.currentTimeMillis();
        // 以「登记时间」为准判断会话是否仍然新鲜：
        // lastActiveTime 会随内容产出不断刷新，旧会话只要还在被写入就会一直被判定为有效；
        // activeSessionAt 只在 Hook 侧登记活跃会话时更新，更贴合"本轮对话"的语义。
        long registeredAt = activeSessionAt > 0 ? activeSessionAt : session.lastActiveTime;
        if (maxIdleMs > 0 && (now - registeredAt) > maxIdleMs) {
            return null;
        }
        return session;
    }

    /**
     * 立起新一轮屏障（v3.3）：清空「最近活跃会话」登记，并记录屏障时刻。
     *
     * <p>必须在注入用户消息<b>之前</b>调用。这样上一轮遗留的会话不会再被
     * {@link #getCurrentRound(long)} 选中，只有屏障之后由小布回调
     * {@link #getOrCreate(String)} 新登记的会话才算本轮。</p>
     */
    public static void armRoundBarrier() {
        beginExternalRound();
    }

    /**
     * 取「本轮」登记的活跃会话（v3.3）。
     *
     * <p>与 {@link #getMostRecent(long)} 的区别：早于本轮屏障登记的会话
     * 一律返回 null，从根上杜绝上一轮残留内容污染本轮响应。</p>
     *
     * @param maxIdleMs 允许的空闲窗口（毫秒），&lt;=0 表示不限制
     */
    public static ConversationSession getCurrentRound(long maxIdleMs) {
        ConversationSession session = activeSession;
        if (session == null) {
            return null;
        }
        // 屏障之前的登记 = 上一轮残留，直接拒绝
        if (activeSessionAt < roundBarrierAt) {
            return null;
        }
        if (maxIdleMs > 0 && (System.currentTimeMillis() - activeSessionAt) > maxIdleMs) {
            return null;
        }
        return session;
    }

    /** 当前屏障时刻，仅供日志与诊断使用 */
    public static long getRoundBarrierAt() {
        return roundBarrierAt;
    }

    /**
     * 当前是否已有内容产出（用于判断会话是否真正可用）
     */
    public boolean hasContent() {
        synchronized (fullContent) {
            return fullContent.length() > 0;
        }
    }

    /**
     * 本轮是否已有可交付的产出（文本或图片）（v3.15）。
     *
     * <p>文生图轮次的 {@code content} 只有「已生成图片」几个字，图片在
     * {@code payload} 里异步解析入队，两者到达顺序不保证。等待逻辑用
     * <b>本方法</b>而不是 {@link #hasContent()} 判「可以收工了」，
     * 否则图片先到、文本后到（或反之）都可能被判成还没产出。</p>
     */
    public boolean hasAnyOutput() {
        return hasContent() || hasImages();
    }

    /**
     * 记录最近一次用户提问内容（Hook 侧在 chatType=1 时调用）
     */
    public void setLastUserMessage(String message) {
        this.lastUserMessage = message;
        touch();
    }

    /**
     * 取最近一次用户提问内容
     */
    public String getLastUserMessage() {
        return lastUserMessage;
    }

    /**
     * 向会话中追加内容片段（无 uniqueId 版本，保留给内部/兼容调用）
     */
    public void offerContent(String content) {
        offerFragment(null, content);
    }

    /**
     * 追加一个回复片段，并对「多通道重复上报」做去重（v3.3）。
     *
     * <p>三重防护：</p>
     * <ol>
     *   <li><b>同 id 同内容</b>：同一个 uniqueId 携带相同内容再次到达 → 丢弃；</li>
     *   <li><b>累积全文识别</b>：小布回调的 content 是「累积全文」，
     *       新片段若以上一轮累积结果为前缀，则改为整体替换而非追加；</li>
     *   <li><b>短时重复</b>：无 uniqueId 时，同一内容在 300ms 内重复到达 → 丢弃。</li>
     * </ol>
     *
     * @param uniqueId 片段唯一标识，可为 null
     * @param content  片段内容
     * @return 是否真正写入了新内容
     */
    public boolean offerFragment(String uniqueId, String content) {
        touch();
        if (content == null || content.isEmpty()) {
            return false;
        }

        final long now = System.currentTimeMillis();
        synchronized (fullContent) {
            String prev = fullContent.toString();

            // 防护 1：同 id 同内容 —— 多通道投递同一 bean
            if (uniqueId != null && uniqueId.equals(lastFragmentId)
                    && content.equals(lastFragmentContent)) {
                XposedBridge.log(TAG + " Dedup: same id+content dropped, id=" + uniqueId);
                return false;
            }

            // 多通道回调可能为同一 bean 生成不同的包装 id，因此不能只依赖 id。
            // 同一内容在极短窗口内再次到达时统一视为重复投递。
            if (content.equals(lastFragmentContent)
                    && (now - lastFragmentAt) <= DUPLICATE_WINDOW_MS) {
                XposedBridge.log(TAG + " Dedup: duplicate content within window dropped");
                return false;
            }

            lastFragmentId = uniqueId;
            lastFragmentContent = content;
            lastFragmentAt = now;

            // 防护 2：累积全文 —— 新内容是旧内容的超集，只投递新增 delta。
            // 非流式返回始终使用 fullContent；流式响应则不能把完整累积全文重复发送。
            if (!prev.isEmpty() && content.length() >= prev.length() && content.startsWith(prev)) {
                String delta = content.substring(prev.length());
                fullContent.setLength(0);
                fullContent.append(content);
                if (!delta.isEmpty()) {
                    contentQueue.offer(delta);
                }
                XposedBridge.log(TAG + " Cumulative fragment delta queued, deltaLen=" + delta.length()
                        + ", fullLen=" + content.length());
                return !delta.isEmpty();
            }

            // 旧内容已是新内容的超集（乱序 / 迟到片段）→ 忽略
            if (!prev.isEmpty() && prev.startsWith(content)) {
                XposedBridge.log(TAG + " Stale fragment ignored, len=" + content.length());
                return false;
            }

            contentQueue.offer(content);
            fullContent.append(content);
            return true;
        }
    }

    /**
     * 兼容旧签名：追加内容片段（无 uniqueId，走短时重复去重）
     */
    public void offerContentLegacy(String content) {
        offerFragment(null, content);
    }

    /**
     * 投递一批图片结果（v3.15，由 {@code MainHook.handleBean} 在解析 payload 后调用）。
     *
     * <p>URL 去重在本方法内完成：多通道重复上报与「payload + markdownCardInfos
     * 同时命中同一张图」都会在这里被收敛成一条。空 URL 直接丢弃。</p>
     *
     * @param images 已抽取的图片结果；可为 null / 空
     * @return 真正入队的张数
     */
    public int offerImages(java.util.List<ImageResultCodec.ImageResult> images) {
        if (images == null || images.isEmpty()) {
            return 0;
        }
        int added = 0;
        for (ImageResultCodec.ImageResult img : images) {
            if (img == null || img.url == null || img.url.isEmpty()) {
                continue;
            }
            if (!seenImageUrls.add(img.url)) {
                XposedBridge.log(TAG + " Dedup: duplicate image dropped, url=" + img.url);
                continue;
            }
            imageQueue.offer(img);
            added++;
        }
        if (added > 0) {
            touch();
            XposedBridge.log(TAG + " Offered " + added + " image(s), queued=" + imageQueue.size());
        }
        return added;
    }

    /**
     * 非阻塞取出一张图片结果（v3.15）。
     *
     * @return 队首图片；队列为空返回 null
     */
    public ImageResultCodec.ImageResult pollImage() {
        ImageResultCodec.ImageResult img = imageQueue.poll();
        if (img != null) {
            touch();
        }
        return img;
    }

    /**
     * 取走当前已入队的全部图片（v3.15）。
     *
     * <p>文生图的图片是「一轮的最终产物」而非流式片段，调用方一次拿完即可，
     * 因此提供批量取用而不是逐个 poll。</p>
     *
     * @return 图片列表；无图片返回空列表
     */
    public java.util.List<ImageResultCodec.ImageResult> drainImages() {
        java.util.List<ImageResultCodec.ImageResult> out = new java.util.ArrayList<>();
        ImageResultCodec.ImageResult img;
        while ((img = imageQueue.poll()) != null) {
            out.add(img);
        }
        if (!out.isEmpty()) {
            touch();
        }
        return out;
    }

    /**
     * 当前是否已有图片入队（v3.15）。
     * 供等待逻辑判断「本轮要不要继续等图」，同时不影响文本内容的判定。
     */
    public boolean hasImages() {
        return !imageQueue.isEmpty();
    }

    /**
     * 阻塞等待下一个内容片段
     * @return 内容片段，超时返回 null
     */
    public String pollContent() {
        try {
            String content = contentQueue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (content != null) {
                touch();
            }
            return content;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            XposedBridge.log(TAG + " pollContent interrupted");
            return null;
        }
    }

    /**
     * 带超时地等待下一个内容片段（v3.4）。
     *
     * <p>旧实现只暴露 60 秒阻塞版，流式响应与「响应等待上限」都调用它，
     * 于是一旦队列短暂为空，调用方就被按住最多 60 秒：SSE 早已攒好的
     * chunk 送不出去，客户端只能等到超时收摊，表现为「等 1~2 分钟一次性
     * 收到全部内容」。改为可按几十毫秒粒度轮询后，调用方自己掌握节奏。</p>
     *
     * @param timeoutMs 最长等待时间；&lt;=0 表示非阻塞取一次
     * @return 内容片段，无内容时返回 null
     */
    public String pollContent(long timeoutMs) {
        try {
            String content = timeoutMs > 0
                    ? contentQueue.poll(timeoutMs, TimeUnit.MILLISECONDS)
                    : contentQueue.poll();
            if (content != null) {
                touch();
            }
            return content;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            XposedBridge.log(TAG + " pollContent interrupted");
            return null;
        }
    }

    /**
     * 标记会话完成（收到 isFinal=true）
     */
    public void markCompleted() {
        this.completed = true;
        touch();
        XposedBridge.log(TAG + " Session marked as completed");
    }

    /**
     * 是否已完成
     */
    public boolean isCompleted() {
        return completed;
    }

    /**
     * 新一轮对话开始时重置会话状态。
     *
     * 小布可能复用同一个 recordId：此时 getOrCreate 取回的仍是上一轮的会话对象，
     * 其 completed 标志为 true、队列里还残留上一轮回答。若不重置，HTTP 侧
     * 的 waitForActiveSession 会因 isCompleted() 直接跳过它，导致注入后取不到本轮回答。
     */
    public void resetForNewRound() {
        prepareForNewRound();
        markActive(this);
        XposedBridge.log(TAG + " Session reset for new round");
    }

    /** 仅清理对象状态；不会把旧对象重新登记为当前请求会话。 */
    private void prepareForNewRound() {
        prepareForNewRound(true);
    }

    private void prepareForNewRound(boolean updateRoundStart) {
        contentQueue.clear();
        // 图片槽位与文本队列同生命周期：新一轮开始时必须一起清空，
        // 否则上一轮的图会被本轮当作结果返回（recordId 复用时会真的发生）。
        imageQueue.clear();
        seenImageUrls.clear();
        synchronized (fullContent) {
            fullContent.setLength(0);
        }
        lastUserMessage = null;
        completed = false;
        lastFragmentId = null;
        lastFragmentContent = null;
        lastFragmentAt = 0L;
        if (updateRoundStart) {
            roundStartedAt = System.currentTimeMillis();
        }
        touch();
    }

    /** 回调只有在本轮屏障之后才允许进入当前会话。 */
    public boolean isEligibleForRound(long barrierAt) {
        return roundStartedAt >= barrierAt;
    }

    /**
     * 是否有待处理或活跃的内容（队列非空或未完成）
     */
    public boolean hasPendingOrActive() {
        return !completed || !contentQueue.isEmpty();
    }

    /**
     * 获取累积的完整回复内容（用于非流式模式）
     */
    public String getFullContent() {
        synchronized (fullContent) {
            return fullContent.toString();
        }
    }

    /**
     * 更新最后活动时间
     */
    private void touch() {
        this.lastActiveTime = System.currentTimeMillis();
    }

    /**
     * 清理过期会话，应由定时任务或每次操作时调用
     */
    public static void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, ConversationSession>> it = sessions.entrySet().iterator();
        int removed = 0;
        while (it.hasNext()) {
            Map.Entry<String, ConversationSession> entry = it.next();
            if (now - entry.getValue().lastActiveTime > SESSION_TIMEOUT_MS) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            XposedBridge.log(TAG + " Cleaned up " + removed + " expired sessions");
        }
    }

    /**
     * 移除指定会话
     */
    public static void removeSession(String recordId) {
        if (recordId != null) {
            sessions.remove(recordId);
        }
    }
}
