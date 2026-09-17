package com.wanxiang.xiaobubridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * 工具调用层与网关链路的接缝（fold_proxy.py 中 do_POST 那段的 Java 移植）。
 *
 * <p>职责边界刻意收窄：本类只做「请求进来时怎么组装注入文本」与
 * 「回答回来后怎么还原成 OpenAI 契约」两件事，<b>不</b>碰小布注入与会话等待 ——
 * 那是 {@link OpenAIServer} 已有的链路（串行锁、自动唤醒、自动重试、流式），
 * 工具调用必须复用它，否则会多出一套并行的会话管理。</p>
 *
 * <p>只有 OpenAI 格式（{@code /v1/chat/completions}）解析 tool_calls：
 * Anthropic 的 {@code tool_use} 与 legacy 的纯文本契约语义不同，本轮不混用，
 * 避免把「半套支持」误当成完整支持。</p>
 */
public final class ToolCallBridge {
    private ToolCallBridge() {
    }

    /** 请求是否声明了工具（空数组等价于没声明）。 */
    public static boolean hasTools(JSONObject request) {
        JSONArray tools = request.optJSONArray("tools");
        return tools != null && tools.length() > 0;
    }

    /**
     * 组装要注入小布的文本：折叠 messages + 注入工具协议 + 预算裁剪。
     *
     * <p>与无工具路径的差别：无工具时 OpenAIServer 只取最后一条 user 文本；
     * 带工具时必须折叠<b>整段</b> messages，因为工具结果（role=tool）与历史调用
     * 是模型继续调用所必需的上文。</p>
     *
     * <p>系统提示词在这里并入而不是在 {@link OpenAIServer#runInjectedRound} 里加：
     * 否则它会在预算算完之后再拼上去，可能把文本顶过小布 5000 字符上限。
     * 调用方据此需要跳过后续的系统提示词拼接（见 {@code alreadyHasSystem}）。</p>
     */
    public static String buildInjectText(JSONObject request, String extraSystemPrompt) {
        JSONArray messages = request.optJSONArray("messages");
        String ctx = ToolCallPrompt.foldMessages(messages, extraSystemPrompt);
        JSONArray tools = request.optJSONArray("tools");
        Object toolChoice = request.opt("tool_choice");
        return ToolCallPrompt.buildPrompt(ctx, tools, toolChoice);
    }

    /**
     * 从模型回答里还原 tool_calls。
     *
     * @return 调用数组；解析不出或本次未给工具时为空数组
     */
    public static JSONArray extractCalls(JSONObject request, String content) {
        if (!hasTools(request)) {
            return new JSONArray();
        }
        List<String> known = ToolCallPrompt.toolNames(request.optJSONArray("tools"));
        JSONArray calls = ToolCallCodec.parseCalls(content, known);
        if (calls.length() > 0
                && ToolCallPrompt.isChoiceNone(request.opt("tool_choice"))) {
            // 契约兜底：tool_choice="none" 时提示词里已不给工具，模型若仍输出调用块，
            // 也不能返回给客户端 —— 那会让「禁止调用」形同虚设。
            return new JSONArray();
        }
        return calls;
    }

    /**
     * 模型输出里用于 {@code content} 的正文部分（信号行之前的一切）。
     *
     * <p>模型有时会先写一句「好的，我来读取」再输出调用块。这句属于正文，
     * 与 tool_calls 可以并存，不该丢。</p>
     */
    public static String headText(String content) {
        if (content == null) {
            return "";
        }
        int i = content.indexOf(ToolCallPrompt.SIGNAL);
        return (i >= 0 ? content.substring(0, i) : content).trim();
    }

    /** 调用名列表，仅用于日志。 */
    public static java.util.List<String> callNames(JSONArray calls) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (calls == null) {
            return out;
        }
        for (int i = 0; i < calls.length(); i++) {
            JSONObject tc = calls.optJSONObject(i);
            JSONObject fn = tc == null ? null : tc.optJSONObject("function");
            if (fn != null) {
                out.add(fn.optString("name", ""));
            }
        }
        return out;
    }

    /** OpenAI 非流式响应：带 tool_calls 的 message + {@code finish_reason=tool_calls}。 */
    public static String openAiToolCallsCompletion(String requestId, String content, JSONArray calls) {
        try {
            JSONObject message = new JSONObject();
            message.put("role", "assistant");
            String head = headText(content);
            message.put("content", head.isEmpty() ? JSONObject.NULL : head);
            message.put("tool_calls", calls);

            JSONObject choice = new JSONObject();
            choice.put("index", 0);
            choice.put("message", message);
            choice.put("finish_reason", "tool_calls");

            JSONArray choices = new JSONArray();
            choices.put(choice);

            JSONObject result = new JSONObject();
            result.put("id", "chatcmpl-" + requestId);
            result.put("object", "chat.completion");
            result.put("created", System.currentTimeMillis() / 1000);
            result.put("model", "xiaobu");
            result.put("choices", choices);
            return result.toString();
        } catch (Exception e) {
            return "{\"error\":{\"message\":\"serialize tool_calls failed\","
                    + "\"type\":\"api_error\"}}";
        }
    }

    /**
     * OpenAI 流式响应里承载 tool_calls 的那一帧。
     *
     * <p>SDK 的解析契约：每个调用在 delta 里带 {@code index}，首帧给
     * id/type/name，参数可以分片；这里一次性给全，客户端拼接结果一致。
     * 收尾帧的 {@code finish_reason} 必须是 {@code tool_calls}，
     * 否则 SDK 会认为这轮是 stop 而不去执行工具。</p>
     */
    public static JSONObject buildToolCallsChunk(String completionId, JSONArray calls) {
        try {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < calls.length(); i++) {
                JSONObject tc = calls.getJSONObject(i);
                JSONObject fn = tc.getJSONObject("function");
                JSONObject item = new JSONObject();
                item.put("index", i);
                item.put("id", tc.getString("id"));
                item.put("type", "function");
                JSONObject f = new JSONObject();
                f.put("name", fn.getString("name"));
                f.put("arguments", fn.optString("arguments", "{}"));
                item.put("function", f);
                arr.put(item);
            }
            JSONObject delta = new JSONObject();
            delta.put("role", "assistant");
            delta.put("tool_calls", arr);

            JSONObject choice = new JSONObject();
            choice.put("index", 0);
            choice.put("delta", delta);
            choice.put("finish_reason", JSONObject.NULL);

            JSONArray choices = new JSONArray();
            choices.put(choice);

            JSONObject chunk = new JSONObject();
            chunk.put("id", completionId);
            chunk.put("object", "chat.completion.chunk");
            chunk.put("created", System.currentTimeMillis() / 1000);
            chunk.put("model", "xiaobu");
            chunk.put("choices", choices);
            return chunk;
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
