package com.wanxiang.xiaobubridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具调用的解析与校正层（fold_proxy.py 的 Java 移植）。
 *
 * <p>小布本身不认识 OpenAI function calling：它只会收到一段自然语言并被要求
 * 「按格式输出调用」。本类负责反向的一半 —— 把模型吐出来的文本还原成客户端
 * 能用的标准 {@code tool_calls}，并在还原过程中修掉四类确定性缺陷：</p>
 *
 * <ol>
 *   <li><b>裸 JSON</b>：模型偶尔不套 {@code <function_calls>} 直接在正文里甩
 *       {@code {"name":...,"arguments":{...}}}。只认 XML 的老实现会把这条整丢，
 *       而模型此时其实已经选对工具名和参数，白白浪费一轮往返。</li>
 *   <li><b>工具名被改写</b>：客户端注册名是 {@code mcp__MTmcp__mt_file_list}，
 *       模型只会写 {@code mt_file_list}；大小写也会变。直接透传客户端会回
 *       「工具不存在」。四级匹配只在唯一命中时接受，歧义一律丢弃 ——
 *       宁可丢掉这次调用（模型会重试），也不能调错工具（可能造成破坏性副作用）。</li>
 *   <li><b>arguments 非法 JSON 被静默清空</b>：参数凭空消失却在日志里看不出来，
 *       排查时只能看到客户端报「缺少必填参数」。保留原文，让下游看得见模型
 *       到底写了什么。</li>
 *   <li><b>客户端错误原样回灌</b>：面向开发者的错误文本（"Tool xxx does not exists"）
 *       喂给模型会被当成对话事实。改写为中性重试提示，并还原 MCP 的结构化错误。</li>
 * </ol>
 *
 * <p>本类只依赖 {@code org.json}，不碰 Android / Xposed，因此可以在 PC 上用
 * {@code tools/ToolCallLayerTest.java} 直接离线跑回归。</p>
 */
public final class ToolCallCodec {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Pattern CDATA_HEAD = Pattern.compile("^<!\\[CDATA\\[");
    private static final Pattern CDATA_TAIL = Pattern.compile("\\]\\]>$");
    private static final Pattern FUNCTION_CALLS =
            Pattern.compile("<function_calls>(.*?)</function_calls>", Pattern.DOTALL);
    private static final Pattern FUNCTION_CALL =
            Pattern.compile("<function_call>(.*?)</function_call>", Pattern.DOTALL);
    private static final Pattern TOOL_TAG =
            Pattern.compile("<tool>(.*?)</tool>", Pattern.DOTALL);
    private static final Pattern ARGS_TAG =
            Pattern.compile("<args_json>(.*?)</args_json>", Pattern.DOTALL);

    /**
     * 裸 JSON 通路的标记形态。只认 {@code {"name":…,"arguments":…}} 这一种键组合，
     * 不解析任意 JSON —— 否则正文里的普通 JSON（如工具返回的数据）会被误当成调用。
     */
    private static final Pattern BARE_JSON = Pattern.compile(
            "\\{\\s*\"(?:name|tool)\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"(?:arguments|parameters|args)\"\\s*:",
            Pattern.DOTALL);

    private static final Pattern TOOL_NAME_CANDIDATE =
            Pattern.compile("[\"']?([A-Za-z0-9_]{2,64})[\"']?");

    private ToolCallCodec() {
    }

    /**
     * 把模型给的参数归一化成 JSON 字符串。
     *
     * <p>旧实现（Python 版）在 {@code json.loads} 失败时把参数替换成 {@code "{}"}，
     * 参数<b>凭空消失</b>且日志里看不出来。这里刻意<b>不做</b>校验后重序列化：
     * 合法就原样返回，非法也原样返回，让下游（与日志）能看到模型的真实输出。</p>
     */
    public static String normArgs(String a) {
        String s = a == null ? "" : a.trim();
        s = CDATA_HEAD.matcher(s).replaceFirst("");
        s = CDATA_TAIL.matcher(s).replaceFirst("");
        s = s.trim();
        return s.isEmpty() ? "{}" : s;
    }

    /** 去掉 {@code mcp__Xxx__} 前缀，取出尾部的真实工具名。 */
    static String stripMcp(String name) {
        int i = name.lastIndexOf("__");
        return i >= 0 ? name.substring(i + 2) : name;
    }

    /**
     * 把模型写的工具名对齐到客户端注册的真实名字。
     *
     * <p>四级匹配，<b>只在唯一命中时接受</b>，歧义返回 null 丢弃：</p>
     * <ol>
     *   <li>精确匹配</li>
     *   <li>忽略大小写</li>
     *   <li>去 {@code mcp__} 前缀后比对尾部</li>
     *   <li>双向包含（模型可能多写/少写后缀）</li>
     * </ol>
     *
     * @param known 客户端注册的工具名；为 null 或空表示「无从校正」，原样返回
     */
    public static String resolveName(String raw, List<String> known) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        if (known == null || known.isEmpty()) {
            return raw;
        }
        if (known.contains(raw)) {
            return raw;
        }
        String low = raw.toLowerCase();
        List<String> hit = new ArrayList<>();
        for (String k : known) {
            if (k.toLowerCase().equals(low)) {
                hit.add(k);
            }
        }
        if (hit.size() == 1) {
            return hit.get(0);
        }
        String tail = stripMcp(raw).toLowerCase();
        hit.clear();
        for (String k : known) {
            if (stripMcp(k).toLowerCase().equals(tail)) {
                hit.add(k);
            }
        }
        if (hit.size() == 1) {
            return hit.get(0);
        }
        hit.clear();
        for (String k : known) {
            String kl = k.toLowerCase();
            if (kl.contains(low) || low.contains(kl)) {
                hit.add(k);
            }
        }
        if (hit.size() == 1) {
            return hit.get(0);
        }
        return null;
    }

    /**
     * 从模型输出里解析工具调用，产出 OpenAI 规范的 {@code tool_calls} 数组。
     *
     * <p>两条通路都要认，因为两条都真实存在：标准 XML 形态（指令要求的主格式）
     * 与裸 JSON 兜底（指令遵循偶尔失效时）。</p>
     *
     * @param knownNames 客户端注册的工具名，用于校正；可为 null
     * @return 调用数组；解析不出时返回空数组（不是 null）
     */
    public static JSONArray parseCalls(String text, List<String> knownNames) {
        List<String[]> raw = new ArrayList<>();
        if (text != null && !text.isEmpty()) {
            Matcher bm = FUNCTION_CALLS.matcher(text);
            while (bm.find()) {
                Matcher fm = FUNCTION_CALL.matcher(bm.group(1));
                while (fm.find()) {
                    String fc = fm.group(1);
                    Matcher nm = TOOL_TAG.matcher(fc);
                    if (!nm.find()) {
                        continue;
                    }
                    Matcher am = ARGS_TAG.matcher(fc);
                    raw.add(new String[]{nm.group(1).trim(),
                            am.find() ? normArgs(am.group(1)) : "{}"});
                }
            }
            if (raw.isEmpty()) {
                raw.addAll(parseBareJson(text));
            }
        }

        JSONArray calls = new JSONArray();
        for (String[] pair : raw) {
            String name = resolveName(pair[0], knownNames);
            if (name == null) {
                ToolCallPrompt.log("tool name 无法对齐，丢弃调用: " + pair[0]
                        + "（候选=" + knownNames + "）");
                continue;
            }
            try {
                JSONObject fn = new JSONObject();
                fn.put("name", name);
                fn.put("arguments", pair[1]);
                JSONObject call = new JSONObject();
                call.put("id", newCallId());
                call.put("type", "function");
                call.put("function", fn);
                calls.put(call);
            } catch (Exception e) {
                // Android 的 org.json 把 put 声明为受检 JSONException，PC 版是运行时异常；
                // 同一个 catch 在两处都成立。构造调用体不会真的失败，兜住只为可编译。
                ToolCallPrompt.log("构造 tool_call 失败: " + e);
            }
        }
        return calls;
    }

    /** 裸 JSON 兜底通路：只取被 {@code {"name":…,"arguments":}} 形态标记的那一段。 */
    private static List<String[]> parseBareJson(String text) {
        List<String[]> out = new ArrayList<>();
        Matcher m = BARE_JSON.matcher(text);
        while (m.find()) {
            String name = m.group(1).trim();
            // 匹配到 "arguments": 的冒号为止，真正的对象从冒号后第一个 { 开始
            int start = text.indexOf('{', m.end());
            if (start < 0) {
                continue;
            }
            int depth = 0;
            int i = start;
            boolean inStr = false;
            boolean esc = false;
            for (; i < text.length(); i++) {
                char c = text.charAt(i);
                if (inStr) {
                    if (esc) {
                        esc = false;
                    } else if (c == '\\') {
                        esc = true;
                    } else if (c == '"') {
                        inStr = false;
                    }
                } else if (c == '"') {
                    inStr = true;
                } else if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                }
            }
            out.add(new String[]{name, i < text.length() ? text.substring(start, i + 1) : "{}"});
        }
        return out;
    }

    /**
     * 判断正文是否是「本应调用工具但格式跑偏」的高置信迹象。
     *
     * <p>只在高置信时返回 true。小布实测格式遵循率 12/12，重试代价却很大
     * （上游串行，一次重试就是额外的等待），所以调用方<b>不据此重试</b>，
     * 只用来区分「模型拒绝调用」与「模型想调但格式坏了」，便于记日志。</p>
     */
    public static boolean looksLikeMissedCall(String text) {
        return text != null && !text.isEmpty() && BARE_JSON.matcher(text).find();
    }

    /**
     * 把客户端报过来的「工具不存在」类错误改写成中性重试提示。
     *
     * <p>为什么要拦：客户端报错文本是<b>面向开发者</b>的，原样喂给模型会让它
     * 把这句当成对话事实。小布实测没有出现这种污染（后续轮次仍正常调用工具），
     * 所以本函数定位是<b>防御性加固</b>而非修 bug —— 换模型/换客户端后行为可能
     * 变化，成本只有几行字符串处理，留着比删掉安全。</p>
     *
     * <p>另外还原 MCP 的结构化错误 {@code {"ok":false,"error":{...}}}：客户端常把
     * 它压扁成一句「工具不存在」，导致模型看不到真正缺的是哪个参数。</p>
     */
    public static String neutralizeToolError(String content) {
        if (content == null) {
            return null;
        }
        String s = content.trim();
        if (s.isEmpty()) {
            return content;
        }
        String low = s.toLowerCase();
        boolean notFound = low.contains("does not exist") || low.contains("not exists")
                || s.contains("工具不存在") || low.contains("unknown tool")
                || low.contains("tool not found");
        if (notFound) {
            Matcher m = TOOL_NAME_CANDIDATE.matcher(s);
            String who = m.find() ? m.group(1) : "该工具";
            return "[工具执行结果]\n调用 " + who + " 未成功：该名称在当前客户端未注册。"
                    + "请核对可用工具清单后换用正确的名称重新调用；"
                    + "若已有足够信息，也可以直接回答用户。";
        }

        if (s.startsWith("{") && s.contains("\"ok\"") && low.contains("false")) {
            try {
                JSONObject o = new JSONObject(s);
                Object errObj = o.opt("error");
                String code = "unknown";
                String msg;
                if (errObj instanceof JSONObject) {
                    JSONObject err = (JSONObject) errObj;
                    code = err.optString("code", "unknown");
                    msg = err.optString("message", err.toString());
                } else {
                    msg = errObj == null ? "unknown" : String.valueOf(errObj);
                }
                String mlow = msg.toLowerCase();
                if (mlow.contains("missing") || mlow.contains("required")
                        || "invalid_params".equals(code)) {
                    return "[工具执行结果]\n工具调用被拒绝，原因：" + msg + "。"
                            + "请补齐缺失的必填参数后用**不同的**参数重新调用，不要原样重试。";
                }
                return "[工具执行结果]\n工具调用失败（" + code + "）：" + msg + "。"
                        + "请调整参数后重试，不要原样重复。";
            } catch (Exception e) {
                return content;
            }
        }
        return content;
    }

    /** 生成 {@code call_} 前缀的调用 id（OpenAI SDK 会校验该前缀）。 */
    static String newCallId() {
        byte[] b = new byte[8];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder("call_");
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }
}
