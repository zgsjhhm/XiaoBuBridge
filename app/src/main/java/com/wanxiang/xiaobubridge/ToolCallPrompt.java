package com.wanxiang.xiaobubridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * 工具调用的注入层（fold_proxy.py 的 Java 移植）。
 *
 * <p>小布不认识 OpenAI function calling，只接受一段自然语言。本类负责正向的一半：
 * 把客户端的 {@code tools} 声明与整段 {@code messages} 折叠成一条小布能吃的
 * user 文本，并在 5000 字符硬上限内分配预算。</p>
 *
 * <p><b>为什么用 XML 协议而不是 fence 围栏</b>：实测两套协议在小布上遵循率相同
 * （10 场景 × 探针全部通过、语义等价），但体积差 3 倍 —— 同 14 个工具，
 * XML 紧凑签名 1225 字符，完整 JSON Schema + fence 要 3601 字符。小布 5000 字符
 * 硬上限下这是决定性的。</p>
 *
 * <p><b>预算分级降级</b>（顺序即优先级）：格式说明 + 工具清单 → 记忆 → 对话上下文。
 * 任何一级都可能仍不够，最后有硬钳位：超限送出去不是「降级」而是「挂死」——
 * 实测超 5000 字符时上游会一路挂到超时，比返回一个残缺的工具清单糟糕得多。</p>
 *
 * <p>本类不依赖 Android / Xposed（日志走 {@link #setLogger}），因此可在 PC 上
 * 离线跑回归。</p>
 */
public final class ToolCallPrompt {

    /** 小布网关硬性输入上限（实测 5100 字符即返回「可输入字数最长5000」） */
    public static final int INPUT_BUDGET = 4800;

    /**
     * 工具数超过此值时提示「参数已压缩」，避免模型误以为还有完整描述可依赖。
     * 与 Qwen2api 的 COMPACT_THRESHOLD 同值，但用途不同：那边是「多于此数就丢弃描述」，
     * 这边是「一直压缩，多于此数额外提示一句」。
     */
    private static final int COMPACT_THRESHOLD = 12;

    /** 记忆独立预算：不让它和对话抢那点净空间 */
    private static final int MEMORY_BUDGET = 800;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALNUM =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();

    /**
     * 随机触发信号。指令要求「需要调用工具时第一行必须是它」，回灌历史调用时也用同一行，
     * 使模型对「调用块」有稳定的形态记忆。随进程随机，避免模型把它当固定模板背下来
     * 而在不该调用时也输出。
     */
    public static final String SIGNAL = genSignal();

    // ── Hermes 热层记忆转运 ──────────────────────────────────────────────
    // 记忆存在 Hermes 侧，本层只负责转运。渲染格式由 Hermes 的 _render_block() 决定：
    //     ═{46}\n{MEMORY (your personal notes) | USER PROFILE (who the user is)} [17% — …]\n═{46}\n{entry}\n§\n{entry}
    // 该块落在 system prompt 的 volatile 段，而原实现的尾部截断会把整个 system prompt
    // 丢掉 —— 记忆永远到不了小布。所以必须先抽出来单独按预算转运。
    private static final String SEP = repeat("═", 46);
    private static final String[] MEM_HEADERS =
            {"MEMORY (your personal notes)", "USER PROFILE (who the user is)"};
    /** 输出侧短标签：原块头每块 146 字符，纯装饰；小布侧压成 6 字符标签，同预算多装 2 条 */
    private static final String MEM_LABEL_MEMORY = "【长期记忆】";
    private static final String MEM_LABEL_USER = "【用户档案】";
    private static final String MEM_TAG = "\n\n==================== 长期记忆（Hermes） ====================\n";
    /** 记忆块之后可能出现的非记忆段：遇到就停，避免把时间戳当成记忆条目 */
    private static final String[] MEM_STOP =
            {"Conversation started:", "# Hermes runtime environment"};
    /** 两块按 Hermes 侧容量比（memory:user = 2200:1375）分预算，否则 MEMORY 会吃光预算把 USER 挤掉 */
    private static final int MEM_WEIGHT_MEMORY = 2200;
    private static final int MEM_WEIGHT_USER = 1375;

    private static final String TAIL = "\n\n==================== 对话上下文 ====================\n";

    /** 日志出口：默认写 stderr；接入模块后由 OpenAIServer 换成落盘日志 */
    public interface Logger {
        void log(String msg);
    }

    private static volatile Logger logger = new Logger() {
        @Override
        public void log(String msg) {
            System.err.println("[toolcall] " + msg);
        }
    };

    private ToolCallPrompt() {
    }

    public static void setLogger(Logger l) {
        if (l != null) {
            logger = l;
        }
    }

    static void log(String msg) {
        logger.log(msg);
    }

    private static String repeat(String unit, int times) {
        StringBuilder sb = new StringBuilder(unit.length() * times);
        for (int i = 0; i < times; i++) {
            sb.append(unit);
        }
        return sb.toString();
    }

    private static String genSignal() {
        StringBuilder sb = new StringBuilder("<Function_");
        for (int i = 0; i < 4; i++) {
            sb.append(ALNUM[RANDOM.nextInt(ALNUM.length)]);
        }
        return sb.append("_Start/>").toString();
    }

    // ==================== 工具清单渲染 ====================

    /**
     * 紧凑渲染：{@code 名字(参数名*:类型=枚举)}，丢弃工具级长描述。
     *
     * <p>34 个工具用此格式约 1.4k 字符，可放进小布预算内。枚举值<b>保留</b>：
     * 它是合法取值约束（用户没说编码时，模型可能编造 "utf8"/"UTF-8"/"auto" 这类
     * 非法值），成本仅约 9 字符/参数。</p>
     */
    public static String renderTools(JSONArray tools) {
        StringBuilder out = new StringBuilder();
        if (tools == null) {
            return "";
        }
        for (int i = 0; i < tools.length(); i++) {
            JSONObject t = tools.optJSONObject(i);
            if (t == null) {
                continue;
            }
            JSONObject fn = t.optJSONObject("function");
            if (fn == null) {
                continue;
            }
            String name = fn.optString("name", "");
            if (name.isEmpty()) {
                continue;
            }
            List<String> req = strList(fn.optJSONObject("parameters"), "required");
            JSONObject props = null;
            JSONObject params = fn.optJSONObject("parameters");
            if (params != null) {
                props = params.optJSONObject("properties");
            }
            List<String> parts = new ArrayList<>();
            if (props != null) {
                for (java.util.Iterator<String> it = props.keys(); it.hasNext(); ) {
                    String k = it.next();
                    JSONObject v = props.optJSONObject(k);
                    if (v == null) {
                        v = new JSONObject();
                    }
                    String type = v.optString("type", "any");
                    if (type.length() > 3) {
                        type = type.substring(0, 3);
                    }
                    StringBuilder s = new StringBuilder(k)
                            .append(req.contains(k) ? "*" : "").append(':').append(type);
                    JSONArray en = v.optJSONArray("enum");
                    if (en != null && en.length() > 0) {
                        s.append('=');
                        int lim = Math.min(en.length(), 8);
                        for (int e = 0; e < lim; e++) {
                            if (e > 0) {
                                s.append('|');
                            }
                            s.append(String.valueOf(en.opt(e)));
                        }
                    }
                    parts.add(s.toString());
                }
            }
            out.append("- ").append(name).append('(');
            if (parts.isEmpty()) {
                out.append('-');
            } else {
                for (int p = 0; p < parts.size(); p++) {
                    if (p > 0) {
                        out.append(',');
                    }
                    out.append(parts.get(p));
                }
            }
            out.append(")\n");
        }
        return out.toString();
    }

    /** 取客户端注册的工具名列表（用于名字校正）。 */
    public static List<String> toolNames(JSONArray tools) {
        List<String> names = new ArrayList<>();
        if (tools == null) {
            return names;
        }
        for (int i = 0; i < tools.length(); i++) {
            JSONObject t = tools.optJSONObject(i);
            if (t == null) {
                continue;
            }
            JSONObject fn = t.optJSONObject("function");
            if (fn == null) {
                continue;
            }
            String n = fn.optString("name", "");
            if (!n.isEmpty()) {
                names.add(n);
            }
        }
        return names;
    }

    private static List<String> strList(JSONObject obj, String key) {
        List<String> out = new ArrayList<>();
        if (obj == null) {
            return out;
        }
        JSONArray arr = obj.optJSONArray(key);
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            Object v = arr.opt(i);
            if (v != null) {
                out.add(String.valueOf(v));
            }
        }
        return out;
    }

    // ==================== tool_choice 契约 ====================

    /** {@code tool_choice} 对象形式里点名的函数名；不是对象形式返回 null。 */
    public static String forcedFunctionName(Object toolChoice) {
        if (!(toolChoice instanceof JSONObject)) {
            return null;
        }
        JSONObject tc = (JSONObject) toolChoice;
        if (!"function".equals(tc.optString("type"))) {
            return null;
        }
        JSONObject fn = tc.optJSONObject("function");
        if (fn == null) {
            return null;
        }
        String name = fn.optString("name", "");
        return name.isEmpty() ? null : name;
    }

    /** {@code tool_choice == "required"}：必须调用某个工具，但不指定哪一个。 */
    public static boolean isChoiceRequired(Object toolChoice) {
        return toolChoice instanceof String
                && "required".equalsIgnoreCase(((String) toolChoice).trim());
    }

    /** {@code tool_choice == "none"}：禁止调用工具。 */
    public static boolean isChoiceNone(Object toolChoice) {
        return toolChoice instanceof String
                && "none".equalsIgnoreCase(((String) toolChoice).trim());
    }

    // ==================== prompt 组装 ====================

    /**
     * 组装最终注入小布的文本，并保证不超过 {@link #INPUT_BUDGET}。
     *
     * <p>无工具请求也必须走这里 —— 直接把原始上下文送出是另一个坑：它可能上万字符，
     * 超过小布上限后上游会挂起直到超时（实测卡满 120 秒）。</p>
     *
     * @param ctx        已折叠的对话上下文（{@link #foldMessages}）
     * @param tools      客户端的工具声明；null / 空数组 / tool_choice="none" 时按无工具处理
     * @param toolChoice 原始 tool_choice
     */
    public static String buildPrompt(String ctx, JSONArray tools, Object toolChoice) {
        if (isChoiceNone(toolChoice)) {
            // "none" 等价于「本次不给工具」——但仍要走折叠流程，不能把原始 ctx 直接送出
            tools = null;
        }
        if (tools != null && tools.length() == 0) {
            tools = null;
        }
        if (ctx == null) {
            ctx = "";
        }

        String head = "";
        String toolsBlock = "";
        if (tools != null) {
            String forced = forcedFunctionName(toolChoice);
            List<String> names = toolNames(tools);
            StringBuilder h = new StringBuilder();
            h.append("你是助手，可使用下列工具（* 表示必填）。需要调用工具时，")
                    .append("第一行必须且只能是 ").append(SIGNAL)
                    .append("，第二行起为 <function_calls> 块。")
                    .append("不需要工具时用自然语言直接回答，不要输出该标记。严格格式示例：\n")
                    .append(SIGNAL).append("\n<function_calls>\n<function_call>\n<tool>read_file</tool>\n")
                    .append("<args_json><![CDATA[{\"path\": \"/tmp/a.txt\"}]]></args_json>\n")
                    .append("</function_call>\n</function_calls>\n\n");
            // tool_choice 必须真正生效：客户端用它表达「必须调用」，
            // 忽略它会让自动化流程拿到一段自然语言回答而误判成功。
            if (forced != null && names.contains(forced)) {
                h.append("用户要求必须调用工具 ").append(forced)
                        .append("，“必须”意味着你要真的发起调用，不要只做说明。\n\n");
            } else if (isChoiceRequired(toolChoice)) {
                h.append("用户要求必须调用上面某个工具（选最合适的一个），")
                        .append("不要直接用自然语言回答。\n\n");
            }
            if (tools.length() > COMPACT_THRESHOLD) {
                h.append("工具较多，参数已压缩为 名称:类型 形式。\n\n");
            }
            head = h.toString();
            toolsBlock = "可用工具：\n" + renderTools(tools);
        }

        // 记忆单独记账：不参与 ctx 的尾部截断，否则会被对话挤掉
        String[] memAndCtx = extractMemoryBlock(ctx);
        String memText = memAndCtx[0];
        ctx = memAndCtx[1];
        int dropped = 0;
        String[] fitted = fitMemory(memText, MEMORY_BUDGET);
        memText = fitted[0];
        try {
            dropped = Integer.parseInt(fitted[1]);
        } catch (Exception ignored) {
        }
        String memSection = memText.isEmpty() ? "" : (MEM_TAG + memText);

        // 预算：head + tools + memory 优先保证，剩余给上下文
        int remain = INPUT_BUDGET - head.length() - toolsBlock.length()
                - memSection.length() - TAIL.length();
        if (remain < 0 && tools != null) {
            // 连工具清单都放不下，则退化为仅工具名
            toolsBlock = String.join(", ", toolNames(tools));
            remain = INPUT_BUDGET - head.length() - toolsBlock.length()
                    - memSection.length() - TAIL.length();
        }
        if (remain < 0 && tools != null) {
            // 连工具名都放不下：**必须**丢掉格式说明 head，否则整段超限。
            // 只丢工具清单不够 —— head 里的格式示例本身就有 200+ 字符。
            head = "";
            remain = INPUT_BUDGET - toolsBlock.length() - memSection.length() - TAIL.length();
        }
        if (remain < 0 && !memSection.isEmpty()) {
            // 仍放不下：牺牲记忆，保住工具与对话
            log("memory dropped entirely: over budget");
            memSection = "";
            remain = INPUT_BUDGET - head.length() - toolsBlock.length() - TAIL.length();
        }
        int fixed = head.length() + toolsBlock.length() + memSection.length() + TAIL.length();
        if (fixed + remain > INPUT_BUDGET) {
            remain = Math.max(INPUT_BUDGET - fixed, 0);
        }
        while (fixed > INPUT_BUDGET) {
            int excess = fixed - INPUT_BUDGET;
            if (!toolsBlock.isEmpty()) {
                toolsBlock = toolsBlock.substring(0, Math.max(toolsBlock.length() - excess, 0));
                log("工具清单被硬截断（工具集过大，已无法完整表达）");
            } else if (!head.isEmpty()) {
                head = head.substring(0, Math.max(head.length() - excess, 0));
                log("格式说明被硬截断");
            } else {
                break;
            }
            fixed = head.length() + toolsBlock.length() + memSection.length() + TAIL.length();
        }

        // 取尾部截断：小布的对话上下文里最新的内容在尾部
        String ctxTrimmed = remain > 0 ? tail(ctx, remain) : "";
        log("memory: " + memText.length() + " chars into prompt, " + dropped
                + " entries dropped, ctx budget " + Math.max(remain, 0)
                + ", ctx " + ctx.length() + " -> " + ctxTrimmed.length());

        String out = head + toolsBlock + memSection + TAIL + ctxTrimmed;
        if (out.length() > INPUT_BUDGET) {
            // 不该发生；真发生说明降级链有洞，截断并留下证据而不是把超限文本送上游
            log("BUG: prompt " + out.length() + "c 仍超预算 " + INPUT_BUDGET + "c，强制截断");
            out = out.substring(0, INPUT_BUDGET);
        }
        return out;
    }

    private static String tail(String s, int n) {
        return s.length() <= n ? s : s.substring(s.length() - n);
    }

    // ==================== messages 折叠 ====================

    /**
     * 把整段 {@code messages} 折叠进一条 user 文本（小布网关只认最后一条 user）。
     *
     * <p>历史里的 assistant tool_calls 要按<b>原协议形态</b>还原，让模型对
     * 「发起调用长什么样」有持续的格式记忆；role=tool 的结果走
     * {@link ToolCallCodec#neutralizeToolError} 中性化。</p>
     *
     * @param extraSystemPrompt 设置页配置的系统提示词；非空时作为最前面的系统设定并入，
     *                          这样它同样受预算约束（不并入的话会在预算算完之后再加，
     *                          可能把文本顶过小布上限）
     */
    public static String foldMessages(JSONArray messages, String extraSystemPrompt) {
        List<String> parts = new ArrayList<>();
        if (extraSystemPrompt != null && !extraSystemPrompt.trim().isEmpty()) {
            parts.add("[系统设定]\n" + extraSystemPrompt.trim());
        }
        if (messages != null) {
            for (int i = 0; i < messages.length(); i++) {
                JSONObject m = messages.optJSONObject(i);
                if (m == null) {
                    continue;
                }
                String role = m.optString("role", "");
                String content = contentText(m.opt("content"));
                if ("system".equals(role)) {
                    parts.add("[系统设定]\n" + content);
                } else if ("tool".equals(role)) {
                    parts.add(ToolCallCodec.neutralizeToolError(content));
                } else if ("assistant".equals(role)) {
                    JSONArray tcs = m.optJSONArray("tool_calls");
                    if (tcs != null && tcs.length() > 0) {
                        StringBuilder xml = new StringBuilder();
                        for (int c = 0; c < tcs.length(); c++) {
                            JSONObject tc = tcs.optJSONObject(c);
                            if (tc == null) {
                                continue;
                            }
                            JSONObject fn = tc.optJSONObject("function");
                            if (fn == null) {
                                continue;
                            }
                            if (xml.length() > 0) {
                                xml.append('\n');
                            }
                            xml.append("<function_call>\n<tool>").append(fn.optString("name", ""))
                                    .append("</tool>\n<args_json><![CDATA[")
                                    .append(fn.optString("arguments", "{}"))
                                    .append("]]></args_json>\n</function_call>");
                        }
                        parts.add("[助手发起工具调用]\n" + SIGNAL + "\n<function_calls>\n"
                                + xml + "\n</function_calls>");
                    } else if (!content.isEmpty()) {
                        parts.add("[助手]\n" + content);
                    }
                } else if ("user".equals(role)) {
                    parts.add("[用户]\n" + content);
                }
            }
        }
        return String.join("\n\n", parts);
    }

    /** content 可能是字符串，也可能是 Anthropic 风格的内容块数组。 */
    static String contentText(Object content) {
        if (content == null) {
            return "";
        }
        if (content instanceof String) {
            return (String) content;
        }
        if (content instanceof JSONArray) {
            JSONArray arr = (JSONArray) content;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject b = arr.optJSONObject(i);
                if (b == null) {
                    continue;
                }
                String t = b.optString("text", "");
                if (!t.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(t);
                }
            }
            return sb.toString();
        }
        return String.valueOf(content);
    }

    // ==================== 记忆块抽取与裁剪 ====================

    /** 判断 head 行是否记忆块头，返回 {@code [原始标题, 块体起始行]}；不是则 null。 */
    private static String[] blockHeadAt(List<String> lines, int i) {
        if (i >= lines.size()) {
            return null;
        }
        String head = lines.get(i).trim();
        if (MEM_LABEL_MEMORY.equals(head)) {
            return new String[]{MEM_HEADERS[0], String.valueOf(i + 1)};
        }
        if (MEM_LABEL_USER.equals(head)) {
            return new String[]{MEM_HEADERS[1], String.valueOf(i + 1)};
        }
        // Hermes 原格式（三行）：分隔线 / 标题 / 分隔线。
        // 两种形态都认，是为了让 extract→fit→回灌 可重入：本层送出的文本若再被
        // 当作原块读入（或将来上游改了块头），解析不会静默失效。
        if (SEP.equals(head) && i + 2 < lines.size()) {
            String title = null;
            for (String h : MEM_HEADERS) {
                if (lines.get(i + 1).startsWith(h)) {
                    title = h;
                    break;
                }
            }
            if (title != null && SEP.equals(lines.get(i + 2).trim())) {
                return new String[]{title, String.valueOf(i + 3)};
            }
        }
        return null;
    }

    private static boolean isMemStop(String line) {
        String t = line.trim();
        for (String s : MEM_STOP) {
            if (t.startsWith(s)) {
                return true;
            }
        }
        return false;
    }

    /** 定位记忆块的行区间：{@code [start, end, title]}。 */
    private static List<Object[]> memorySpans(List<String> lines) {
        List<Object[]> spans = new ArrayList<>();
        int i = 0;
        int n = lines.size();
        while (i < n) {
            String[] hit = blockHeadAt(lines, i);
            if (hit != null) {
                int body = Integer.parseInt(hit[1]);
                int j = body;
                while (j < n && blockHeadAt(lines, j) == null && !isMemStop(lines.get(j))) {
                    j++;
                }
                int end = j;
                while (end > body && lines.get(end - 1).trim().isEmpty()) {
                    end--;      // 尾部空行留给 ctx，不吞进记忆块
                }
                spans.add(new Object[]{i, end, hit[0]});
                i = Math.max(end, i + 1);
                continue;
            }
            i++;
        }
        return spans;
    }

    /**
     * 把记忆块从折叠后的 ctx 里抽出，返回 {@code [记忆文本, 去掉记忆的 ctx]}。
     *
     * <p>为什么必须抽：记忆块在 system prompt 的 volatile 段（约 97.8% 处），
     * 而折叠后 ctx 的截断取尾部 —— system prompt 整体位于头部会被丢弃，
     * 记忆根本到不了小布。抽出来单独按预算转运，才不依赖截断的副作用。</p>
     */
    static String[] extractMemoryBlock(String ctx) {
        List<String> lines = new ArrayList<>();
        for (String s : ctx.split("\n", -1)) {
            lines.add(s);
        }
        List<Object[]> spans = memorySpans(lines);
        if (spans.isEmpty()) {
            return new String[]{"", ctx};
        }
        List<String> memParts = new ArrayList<>();
        for (Object[] sp : spans) {
            int a = (Integer) sp[0];
            int b = (Integer) sp[1];
            memParts.add(joinRange(lines, a, b).trim());
        }
        StringBuilder keep = new StringBuilder();
        int prev = 0;
        for (Object[] sp : spans) {
            int a = (Integer) sp[0];
            int b = (Integer) sp[1];
            for (int k = prev; k < a; k++) {
                keep.append(lines.get(k)).append('\n');
            }
            prev = b;
        }
        for (int k = prev; k < lines.size(); k++) {
            keep.append(lines.get(k)).append('\n');
        }
        return new String[]{String.join("\n\n", memParts).trim(), keep.toString().trim()};
    }

    private static String joinRange(List<String> lines, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (i > from) {
                sb.append('\n');
            }
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    /**
     * 按整条条目裁剪到预算内（切半条会破坏 {@code \n§\n} 结构，写回时解析不了）。
     *
     * @return {@code [裁剪后文本, 丢弃条目数]}
     */
    static String[] fitMemory(String memText, int budget) {
        if (memText == null || memText.isEmpty()) {
            return new String[]{memText == null ? "" : memText, "0"};
        }
        List<String> lines = new ArrayList<>();
        for (String s : memText.split("\n", -1)) {
            lines.add(s);
        }
        List<Object[]> blocks = new ArrayList<>();   // [title, body]
        for (Object[] sp : memorySpans(lines)) {
            int a = (Integer) sp[0];
            int b = (Integer) sp[1];
            String[] hit = blockHeadAt(lines, a);
            if (hit == null) {
                continue;
            }
            blocks.add(new Object[]{hit[0], joinRange(lines, Integer.parseInt(hit[1]), b)});
        }
        if (blocks.isEmpty()) {
            // 认不出块头就当普通文本处理，超预算也不截断（截断会破坏条目结构，
            // 写回时解析不了），交由上游的整块丢弃分支兜底。
            return new String[]{memText, "0"};
        }
        if (memText.length() <= budget) {
            // 未超预算也要瘦身：块头是纯装饰，压掉就是净赚
            List<String> keptAll = new ArrayList<>();
            for (Object[] blk : blocks) {
                String body = (String) blk[1];
                if (!body.trim().isEmpty()) {
                    keptAll.add(labelOf((String) blk[0]) + "\n" + body);
                }
            }
            return new String[]{String.join("\n\n", keptAll), "0"};
        }

        int totalW = 0;
        for (Object[] blk : blocks) {
            totalW += weightOf((String) blk[0]);
        }
        if (totalW <= 0) {
            totalW = 1;
        }
        List<String> kept = new ArrayList<>();
        int dropped = 0;
        int used = 0;
        for (int idx = 0; idx < blocks.size(); idx++) {
            String title = (String) blocks.get(idx)[0];
            String body = (String) blocks.get(idx)[1];
            // 让最后一个块吃掉余量，避免整除误差把尾块饿死
            int share = (idx == blocks.size() - 1)
                    ? (budget - used)
                    : (int) ((long) budget * weightOf(title) / totalW);
            String label = labelOf(title);
            int cost = label.length() + 1;
            List<String> accepted = new ArrayList<>();
            for (String e : body.split("\n§\n", -1)) {
                String es = e.trim();
                if (es.isEmpty()) {
                    continue;
                }
                int add = es.length() + (accepted.isEmpty() ? 0 : 3);
                if (cost + add > share) {
                    dropped++;
                    continue;
                }
                accepted.add(es);
                cost += add;
            }
            if (!accepted.isEmpty()) {
                kept.add(label + "\n" + String.join("\n§\n", accepted));
                used += cost + 2;
            }
        }
        return new String[]{String.join("\n\n", kept), String.valueOf(dropped)};
    }

    private static String labelOf(String title) {
        return MEM_HEADERS[1].equals(title) ? MEM_LABEL_USER : MEM_LABEL_MEMORY;
    }

    private static int weightOf(String title) {
        return MEM_HEADERS[1].equals(title) ? MEM_WEIGHT_USER : MEM_WEIGHT_MEMORY;
    }
}
