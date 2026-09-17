package com.wanxiang.xiaobubridge;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed 模块入口类
 *
 * v3.0 关键修正（基于 smali 逆向结论）：
 *   - AIChatDataCenter 的回复数据分发不走静态方法，而是遍历两个静态观察者列表
 *     （字段 i = 主会话、字段 j = 浮窗/副会话），逐个调用接口
 *     com.heytap.speechassist.aichat.AIChatDataCenter$b 的 c~p 回调方法。
 *   - 因此 v2.x 里只 Hook AIChatDataCenter.r(AIChatViewBean) 注定收不到流式回复：
 *     该方法并非分发入口。正确做法是把自身作为一个 observer 注册进静态列表，
 *     由小布自己在每次数据变更（新增 / 流式更新 / 结束）时回调我们。
 *   - 接口 $b 全部为 abstract void 方法，无法直接 Hook，改用 java.lang.reflect.Proxy
 *     在目标 ClassLoader 下生成动态代理并注入列表。
 *
 * v3.1 关键修正：
 *   - 旧注入入口 ConversationManager.sendText 走的是语音引擎的 IConversationHandler
 *     （core/f.b() -> AbstractConversationManager.y() -> core.x0.m），不属于 AI Chat 链路，
 *     实测“注入成功但零回调”。改走 AIChatEngineHelper.t(...) —— 即真实 UI
 *     （AiChatHomeInputController.a4 -> F2 -> E2）最终收敛的 sendTextToServer 静态入口。
 *
 * 分发链路（smali 已核实）：
 *   AIChatDataCenter.W0(roomId, bean, index) -> notifyDataAdd$1.invoke() -> $b.g(bean, index)
 *   AIChatDataCenter.X0(roomId, bean, addFirst) -> notifyDataAdd$2.invoke() -> $b.c(bean, addFirst)
 *   其余 notify/update lambda 同理遍历同一批列表。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "[XiaoBuBridge]";
    private static final String TARGET_PACKAGE = "com.heytap.speechassist";
    private static final String HOOK_CLASS = "com.heytap.speechassist.aichat.AIChatDataCenter";
    private static final String HOOK_METHOD = "r";
    private static final String BEAN_CLASS = "com.heytap.speechassist.aichat.bean.AIChatViewBean";
    private static final String OBSERVER_INTERFACE = "com.heytap.speechassist.aichat.AIChatDataCenter$b";
    private static final String ENGINE_HELPER_CLASS = "com.heytap.speechassist.aichat.floatwindow.AIChatEngineHelper";

    /** 观察者列表静态字段名：i = 主会话，j = 浮窗/副会话 */
    private static final String[] OBSERVER_FIELDS = new String[] { "i", "j" };

    // HTTP Server 单例，确保只启动一次
    private static volatile OpenAIServer httpServer;

    // 目标包（小布）的 ClassLoader，注入用户消息时需要用它在目标进程内反射调用
    private static volatile ClassLoader targetClassLoader;

    // 注入到小布观察者列表里的动态代理，避免重复注册
    private static volatile Object observerProxy;

    // AIChatViewBean.chatType 取值语义（据小布 dex 中 isQuery/isAnswer 实现归纳）：
    //   1 = 用户提问，2 = AI 回答
    private static final int CHAT_TYPE_USER = 1;
    private static final int CHAT_TYPE_ANSWER = 2;

    // AIChatEngineHelper.sendTextToServer 的 inputType 取值：
    //   0x31 为文本输入（据 AiChatHomeInputController.E2 的默认分支归纳）
    private static final int INPUT_TYPE_TEXT = 0x31;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        // 进程名：主进程时 processName 可能为 null 或等于包名
        final String processName = (lpparam.processName != null) ? lpparam.processName : lpparam.packageName;
        final boolean isMainProcess = processName.equals(lpparam.packageName);

        XposedBridge.log(TAG + " Loaded in package=" + lpparam.packageName
                + ", process=" + processName + ", isMain=" + isMainProcess);

        // 保存目标包 ClassLoader，供后续在目标进程内反射调用小布自身 API
        targetClassLoader = lpparam.classLoader;

        try {
            // 加载目标类和 Bean 类
            final Class<?> dataCenterClass = XposedHelpers.findClass(HOOK_CLASS, lpparam.classLoader);
            final Class<?> beanClass = XposedHelpers.findClass(BEAN_CLASS, lpparam.classLoader);

            // ---- 通道 1（保底）：Hook AIChatDataCenter.r(AIChatViewBean) ----
            try {
                XposedHelpers.findAndHookMethod(
                        dataCenterClass,
                        HOOK_METHOD,
                        beanClass,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                try {
                                    Object bean = param.args[0];
                                    if (bean == null) {
                                        XposedBridge.log(TAG + " Hooked method called with null bean");
                                        return;
                                    }
                                    handleBean(bean, "AIChatDataCenter.r");
                                } catch (Throwable t) {
                                    XposedBridge.log(TAG + " Error in afterHookedMethod: " + t.getMessage());
                                    XposedBridge.log(t);
                                }
                            }
                        }
                );
                XposedBridge.log(TAG + " Successfully hooked " + HOOK_CLASS + "." + HOOK_METHOD);
            } catch (Throwable t) {
                XposedBridge.log(TAG + " Hook " + HOOK_CLASS + "." + HOOK_METHOD + " failed: " + t);
            }

            // ---- 通道 2（主链路）：把动态代理注册进观察者列表，接收全部回调 ----
            installObserverProxy(lpparam.classLoader, dataCenterClass, beanClass);

            // 诊断 Hook：确认外部注入的用户文本是否真的到达对话链路，以及落在哪个进程
            try {
                Class<?> cmClass = XposedHelpers.findClass(
                        "com.heytap.speechassist.pluginAdapter.platformAdapterDefine.conversation.ConversationManager",
                        lpparam.classLoader);
                XposedHelpers.findAndHookMethod(cmClass, "sendText", String.class, android.os.Bundle.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                Object t = param.args[0];
                                XposedBridge.log(TAG + " [diag] sendText process=" + processName
                                        + ", text=" + (t == null ? "null" : t.toString()));
                            }
                        });
                XposedBridge.log(TAG + " Diag hook ConversationManager.sendText OK");
            } catch (Throwable t) {
                XposedBridge.log(TAG + " Diag hook sendText failed: " + t);
            }

            // 诊断 Hook：AI Chat 真实发送入口，确认注入是否走到 sendTextToServer
            try {
                Class<?> helperClass = XposedHelpers.findClass(ENGINE_HELPER_CLASS, lpparam.classLoader);
                XposedHelpers.findAndHookMethod(helperClass, "v", String.class, int.class,
                        android.os.Bundle.class, boolean.class, boolean.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                Object t = param.args[0];
                                XposedBridge.log(TAG + " [diag] AIChatEngineHelper.sendTextToServer text="
                                        + (t == null ? "null" : t.toString()));
                            }
                        });
                XposedBridge.log(TAG + " Diag hook AIChatEngineHelper.sendTextToServer OK");
            } catch (Throwable t) {
                XposedBridge.log(TAG + " Diag hook AIChatEngineHelper failed: " + t);
            }

            // ---- 通道 3（v3.6）：拦截小布的空闲自杀，避免请求处理到一半宿主消失 ----
            installKillSelfGuard(lpparam.classLoader);

            // HTTP Server 只在主进程启动：:aiCall / :remote / :downloader 等多进程各自绑定
            // 同一端口会抛 EADDRINUSE，导致真正的服务进程反而起不来。
            if (isMainProcess) {
                startHttpServer();

                // v3.0 悬浮球：钩住 Activity.onResume，将悬浮球注入到小布 App 内部的 decor view。
                // 无需 SYSTEM_ALERT_WINDOW 权限，悬浮球仅在小布 App 内部显示。
                UiInjector.install();
            } else {
                XposedBridge.log(TAG + " Skip HTTP Server in non-main process: " + processName);
            }

        } catch (Throwable t) {
            XposedBridge.log(TAG + " Failed to hook: " + t.getMessage());
            XposedBridge.log(t);
        }
    }

    /**
     * 在目标 ClassLoader 下为 AIChatDataCenter$b 接口生成动态代理，
     * 并注册进 AIChatDataCenter 的静态观察者列表（字段 i / j）。
     *
     * 这样小布每次新增或流式更新对话数据时，都会回调到本代理的 invoke()，
     * 从而拿到 AIChatViewBean（content / isFinal / chatType / recordId）。
     */
    private void installObserverProxy(final ClassLoader cl, final Class<?> dataCenterClass, final Class<?> beanClass) {
        try {
            final Class<?> observerClass = XposedHelpers.findClass(OBSERVER_INTERFACE, cl);

            InvocationHandler handler = new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                    final String name = method.getName();
                    try {
                        // Object 基础方法不走业务逻辑
                        if ("hashCode".equals(name)) {
                            return System.identityHashCode(proxy);
                        }
                        if ("equals".equals(name)) {
                            return args != null && args.length > 0 && proxy == args[0];
                        }
                        if ("toString".equals(name)) {
                            return "XiaoBuBridgeObserver";
                        }

                        // 回调参数里找 AIChatViewBean（有的回调带 List<AIChatViewBean>）
                        if (args != null) {
                            for (Object arg : args) {
                                if (arg instanceof List) {
                                    for (Object item : (List<?>) arg) {
                                        if (item != null && beanClass.isInstance(item)) {
                                            handleBean(item, "observer." + name + ".list");
                                        }
                                    }
                                } else if (arg != null && beanClass.isInstance(arg)) {
                                    handleBean(arg, "observer." + name);
                                }
                            }
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " observer invoke(" + name + ") error: " + t);
                    }
                    // 接口方法全部为 void
                    return null;
                }
            };

            final Object proxy = Proxy.newProxyInstance(cl, new Class<?>[] { observerClass }, handler);
            observerProxy = proxy;

            for (String fieldName : OBSERVER_FIELDS) {
                try {
                    Field f = XposedHelpers.findField(dataCenterClass, fieldName);
                    // Field.get 会触发 AIChatDataCenter 的 <clinit>，保证静态列表已初始化
                    Object listObj = f.get(null);
                    if (!(listObj instanceof List)) {
                        XposedBridge.log(TAG + " Field " + fieldName + " is not a List: " + listObj);
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    List<Object> list = (List<Object>) listObj;
                    if (!list.contains(proxy)) {
                        list.add(proxy);
                    }
                    XposedBridge.log(TAG + " Observer registered into AIChatDataCenter." + fieldName
                            + ", listSize=" + list.size());
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " Register observer to field " + fieldName + " failed: " + t);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " installObserverProxy failed: " + t);
            XposedBridge.log(t);
        }
    }

    /**
     * 统一的 AIChatViewBean 处理逻辑：
     *   - chatType=1（用户提问）→ 登记/重置活跃会话，回灌用户原文
     *   - chatType=2（AI 回答）→ 追加流式片段，isFinal=true 时标记完成
     *
     * @param bean  小布的对话数据对象
     * @param from  来源标记，仅用于日志定位（哪条 Hook 通道先拿到数据）
     */
    private static void handleBean(Object bean, String from) {
        try {
            String content = BeanExtractor.getContent(bean);
            int chatType = BeanExtractor.getChatType(bean);
            String uniqueId = BeanExtractor.getUniqueId(bean);
            String recordId = BeanExtractor.getRecordId(bean);
            Boolean isFinal = BeanExtractor.getIsFinal(bean);
            String reasoningContent = BeanExtractor.getReasoningContent(bean);

            // 用户提问：登记活跃会话供 HTTP 侧对接
            if (chatType == CHAT_TYPE_USER) {
                String userKey = (recordId != null && !recordId.isEmpty()) ? recordId : uniqueId;
                if (userKey != null && !userKey.isEmpty()) {
                    ConversationSession userSession = ConversationSession.beginUserRound(userKey);
                    if (content != null && !content.isEmpty()) {
                        userSession.setLastUserMessage(content);
                    }
                    XposedBridge.log(TAG + " [" + from + "] Registered active session from user message: " + userKey);
                }
                return;
            }

            // 只处理 AI 回答类型
            if (chatType != CHAT_TYPE_ANSWER) {
                XposedBridge.log(TAG + " [" + from + "] Ignored non-answer message, chatType=" + chatType);
                return;
            }

            String sessionKey = (recordId != null && !recordId.isEmpty()) ? recordId : uniqueId;
            if (sessionKey == null || sessionKey.isEmpty()) {
                XposedBridge.log(TAG + " [" + from + "] No valid session key found, skipping");
                return;
            }

            ConversationSession session = ConversationSession.getOrCreateForCurrentRound(sessionKey);
            if (session == null) {
                return;
            }

            if (content != null && !content.isEmpty()) {
                session.offerFragment(uniqueId, content);
                XposedBridge.log(TAG + " [" + from + "] Offered content fragment to session " + sessionKey
                        + ", length=" + content.length());
            }

            if (reasoningContent != null && !reasoningContent.isEmpty()) {
                XposedBridge.log(TAG + " [" + from + "] Reasoning fragment for " + sessionKey
                        + ", length=" + reasoningContent.length());
            }

            if (Boolean.TRUE.equals(isFinal)) {
                session.markCompleted();
                XposedBridge.log(TAG + " [" + from + "] Session " + sessionKey + " completed (isFinal=true)");
                ConversationSession.cleanupExpiredSessions();
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " handleBean(" + from + ") error: " + t);
        }
    }

    /**
     * 向小布注入一条用户消息（供 OpenAIServer 收到外部请求时调用）。
     *
     * v3.1 修正（smali 逆向结论）：
     *   旧实现走 ConversationManager.sendText(String, Bundle)，其链路是
     *     core/f.b() -> AbstractConversationManager.y() -> core.x0.m(text, bundle)
     *   其中 x0 是语音引擎的 IConversationHandler，不属于 AI Chat 对话链路，
     *   实测注入被调用但零会话、零回调。
     *
     *   真实 UI 发送链路 AiChatHomeInputController.a4 -> F2 -> E2 最终收敛到
     *   AIChatEngineHelper 的 sendTextToServer，即 smali 中的静态方法
     *     t(String text, int inputType, Bundle, boolean, boolean) -> v(...)
     *   该方法会做网络、额度、UI 模式校验后把用户提问送进 AI Chat 引擎。
     *   这里优先调用该静态入口，失败时回退旧的 sendText 路径。
     *
     * 必须在目标包 ClassLoader 下执行，否则 findClass 会抛 ClassNotFound。
     *
     * @param text 用户消息原文
     * @return 是否成功投递
     */
    public static boolean injectUserMessage(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        ClassLoader cl = targetClassLoader;
        if (cl == null) {
            XposedBridge.log(TAG + " injectUserMessage: targetClassLoader not ready");
            return false;
        }
        final ClassLoader loader = cl;
        final String msg = text;
        final boolean[] ok = new boolean[] { false };
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        Runnable injectTask = new Runnable() {
            @Override
            public void run() {
                boolean sent = false;

                // 通道 A（主链路）：AIChatEngineHelper.t(text, inputType, bundle, false, false)
                try {
                    Class<?> helper = XposedHelpers.findClass(ENGINE_HELPER_CLASS, loader);
                    android.os.Bundle bundle = new android.os.Bundle();
                    XposedHelpers.callStaticMethod(helper, "t", msg,
                            Integer.valueOf(INPUT_TYPE_TEXT), bundle,
                            Boolean.FALSE, Boolean.FALSE);
                    XposedBridge.log(TAG + " injectUserMessage: AIChatEngineHelper.t sent, length=" + msg.length());
                    sent = true;
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " injectUserMessage via AIChatEngineHelper.t failed: " + t);
                }

                // 通道 B（回退）：ConversationManager.sendText
                if (!sent) {
                    try {
                        Class<?> cm = XposedHelpers.findClass(
                                "com.heytap.speechassist.pluginAdapter.platformAdapterDefine.conversation.ConversationManager",
                                loader);
                        android.os.Bundle bundle = new android.os.Bundle();
                        XposedHelpers.callStaticMethod(cm, "sendText", msg, bundle);
                        XposedBridge.log(TAG + " injectUserMessage: fallback sendText sent, length=" + msg.length());
                        sent = true;
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " injectUserMessage fallback failed: " + t);
                    }
                }

                ok[0] = sent;
                latch.countDown();
            }
        };

        // 小布对话链路对调用线程敏感（HTTP Server 运行在子线程），
        // 统一切到目标进程主线程执行；若本身已在主线程则直接调用，避免自等待死锁。
        try {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                injectTask.run();
            } else {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(injectTask);
                latch.await(3, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " injectUserMessage dispatch failed: " + t);
        }
        return ok[0];
    }

    /**
     * v3.6 保活：拦截小布自身的空闲自杀。
     *
     * <p><b>逆向结论（xiaobu_decoded, com.heytap.speechassist 12.9.9）</b>：
     * 小布用 {@code utils/e4} 实现「空闲自杀」——</p>
     * <ul>
     *   <li>{@code e4.K(Context)} 布防：算出延迟（{@code e4.m()} 返回 10s 或 50s，
     *       实测另有 ~90s 的空闲判定），起 Alarm 广播 {@code action.speechassist.kill_self}，
     *       并把 {@code e4.o = true}；</li>
     *   <li>{@code e4.k(String)} 在自杀前做最后一次状态复核
     *       （{@code e4.J(Context)}：是否正在对话、是否在前台、是否在智能驾驶模式…）；</li>
     *   <li>{@code e4.y(Context)} 真正扣动扳机：回调所有
     *       {@code e4$b} 监听者后调用 {@code utils/t2.x(Context)}；</li>
     *   <li>{@code utils/t2.x(Context)} 内部直接
     *       {@code Process.killProcess(myPid()); System.exit(0);}。</li>
     * </ul>
     *
     * <p>v3.5 真机验证踩的就是这个坑：请求还在等回答，宿主进程被自己杀掉，
     * HTTP 连接随之断开，客户端收到的是 read timeout，看起来像「模块不工作」。</p>
     *
     * <p>本方法只拦截「自杀」这两条路径（布防 + 执行），<b>不去 Hook
     * {@code Process.killProcess} 这类通用出口</b>：小布在 AppApplication 里
     * 还有「资源被篡改 → 自杀」的自保护逻辑，一刀切会连带影响它的正常保护行为。</p>
     *
     * <p>开关在<b>调用时</b>读取，因此 UI 里改完立即生效，不需要重启宿主。</p>
     */
    private void installKillSelfGuard(final ClassLoader cl) {
        // ---- 1) 不布防：跳过 e4.K(Context) ----
        try {
            Class<?> e4 = XposedHelpers.findClass("com.heytap.speechassist.utils.e4", cl);
            XposedHelpers.findAndHookMethod(e4, "K", android.content.Context.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!ConfigManager.isKeepAliveEnabledInTarget()) {
                        return;
                    }
                    XposedBridge.log(TAG + " [keepalive] suppressed kill-self timer arming (e4.K)");
                    param.setResult(null);
                }
            });
            XposedBridge.log(TAG + " KeepAlive hook e4.K OK");

            // ---- 2) 不执行：跳过 e4.y(Context) ----
            XposedHelpers.findAndHookMethod(e4, "y", android.content.Context.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!ConfigManager.isKeepAliveEnabledInTarget()) {
                        return;
                    }
                    XposedBridge.log(TAG + " [keepalive] suppressed e4.y kill-self");
                    param.setResult(null);
                }
            });
            XposedBridge.log(TAG + " KeepAlive hook e4.y OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " KeepAlive hook e4 failed: " + t);
        }

        // ---- 3) 兜底：跳过 t2.x(Context) 的 killProcess(myPid()) ----
        try {
            Class<?> t2 = XposedHelpers.findClass("com.heytap.speechassist.utils.t2", cl);
            XposedHelpers.findAndHookMethod(t2, "x", android.content.Context.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!ConfigManager.isKeepAliveEnabledInTarget()) {
                        return;
                    }
                    XposedBridge.log(TAG + " [keepalive] suppressed t2.x killSelfProcess");
                    param.setResult(null);
                }
            });
            XposedBridge.log(TAG + " KeepAlive hook t2.x OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " KeepAlive hook t2.x failed: " + t);
        }
    }

    /**
     * 启动 OpenAI 兼容 HTTP Server
     * 使用双重检查锁确保单例
     * 
     * v3.2 关键修复：ConfigProvider 在目标进程中可能尚未初始化完成，
     * 导致 isServerEnabledInTarget() 返回默认值 false。
     * 优化逻辑：
     * 1. 等待并重试读取配置（最多 5 次，间隔 200ms）；
     * 2. 如果多次读取都失败，回退到使用模块默认值；
     * 3. 记录详细日志便于排查网关未响应问题。
     */
    private void startHttpServer() {
        if (httpServer == null) {
            synchronized (MainHook.class) {
                if (httpServer == null) {
                    try {
                        boolean serverEnabled = false;
                        int port = ConfigManager.DEFAULT_PORT;

                        // 重试读取目标进程配置，处理 ConfigProvider 初始化延迟
                        for (int attempt = 0; attempt < 5; attempt++) {
                            try {
                                serverEnabled = ConfigManager.isServerEnabledInTarget();
                                port = ConfigManager.getPortInTarget();
                                XposedBridge.log(TAG + " Config read attempt " + (attempt + 1)
                                        + ": serverEnabled=" + serverEnabled + ", port=" + port);
                                if (serverEnabled) {
                                    break;
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " Config read attempt " + (attempt + 1) + " failed: " + t);
                            }
                            try {
                                Thread.sleep(200);
                            } catch (InterruptedException ignored) {
                                break;
                            }
                        }

                        // 如果配置显示禁用，仍然使用默认值启动（用户可以在 UI 中修改）
                        if (!serverEnabled) {
                            XposedBridge.log(TAG + " Server disabled or config unavailable, using default: "
                                    + ConfigManager.DEFAULT_SERVER_ENABLED + ", port: " + ConfigManager.DEFAULT_PORT);
                            serverEnabled = ConfigManager.DEFAULT_SERVER_ENABLED;
                            port = ConfigManager.DEFAULT_PORT;
                        }

                        if (!serverEnabled) {
                            XposedBridge.log(TAG + " Server disabled by config, skip start");
                            return;
                        }

                        httpServer = new OpenAIServer(port);
                        httpServer.start();
                        XposedBridge.log(TAG + " HTTP Server started on port " + port);
                    } catch (Exception e) {
                        XposedBridge.log(TAG + " Failed to start HTTP Server: " + e.getMessage());
                        XposedBridge.log(e);
                    }
                }
            }
        }
    }
}
