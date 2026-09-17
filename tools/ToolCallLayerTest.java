import com.wanxiang.xiaobubridge.ToolCallBridge;
import com.wanxiang.xiaobubridge.ToolCallCodec;
import com.wanxiang.xiaobubridge.ToolCallPrompt;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;

/**
 * 工具调用层离线回归（不摸小布、不依赖 Android）。
 *
 * <p>对应 fold_proxy 的 verify_toolcall.py：每个断言都先有一个「确定性缺陷」，
 * 修好后必须通过。跑法见 tools/run_toolcall_test.sh。</p>
 */
public class ToolCallLayerTest {

    static int fails = 0;

    static void check(boolean cond, String label) {
        check(cond, label, "");
    }

    static void check(boolean cond, String label, String detail) {
        System.out.println((cond ? "  PASS  " : "  FAIL  ") + label
                + (detail.isEmpty() ? "" : "   " + detail));
        if (!cond) {
            fails++;
        }
    }

    static JSONObject tool(String name, String paramsJson) {
        try {
            JSONObject fn = new JSONObject();
            fn.put("name", name);
            if (paramsJson != null) {
                fn.put("parameters", new JSONObject(paramsJson));
            }
            JSONObject t = new JSONObject();
            t.put("type", "function");
            t.put("function", fn);
            return t;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── D1 裸 JSON 形态的调用被整条丢弃 ──────────────────────────────────
    static void t1BareJson() {
        System.out.println("\n[D1] 裸 JSON 形态的调用必须能被捞到");
        String bare = "我需要读取文件。\n"
                + "{\"name\": \"read_file\", \"arguments\": {\"path\": \"/tmp/a.txt\"}}";
        JSONArray c = ToolCallCodec.parseCalls(bare, Arrays.asList("read_file", "list_dir"));
        check(c.length() == 1 && "read_file".equals(c.optJSONObject(0)
                .optJSONObject("function").optString("name")), "裸 JSON 被解析出来");
        check(c.length() == 1 && new JSONObject(c.optJSONObject(0).optJSONObject("function")
                .optString("arguments")).optString("path").equals("/tmp/a.txt"),
                "裸 JSON 参数完整");

        String plain = "结果是 {\"count\": 3, \"items\": [1,2]} 仅供参考。";
        check(ToolCallCodec.parseCalls(plain, Arrays.asList("read_file")).length() == 0,
                "普通 JSON 不被误判为调用");

        String nest = "{\"name\":\"write_file\",\"arguments\":"
                + "{\"path\":\"/a\",\"content\":\"x {y} z\"}}";
        JSONArray c2 = ToolCallCodec.parseCalls(nest, Arrays.asList("write_file"));
        check(c2.length() == 1 && new JSONObject(c2.optJSONObject(0).optJSONObject("function")
                .optString("arguments")).optString("content").equals("x {y} z"),
                "含花括号字符串的参数不被截断");
    }

    // ── D2 模型改写的工具名直接透传 ──────────────────────────────────────
    static void t2ResolveName() {
        System.out.println("\n[D2] 工具名四级校正，且歧义时丢弃");
        List<String> k = Arrays.asList("read_file", "list_dir", "mcp__MTmcp__mt_file_list",
                "read_files");
        check("read_file".equals(ToolCallCodec.resolveName("read_file", k)), "1 精确");
        check("read_file".equals(ToolCallCodec.resolveName("Read_File", k)), "2 忽略大小写");
        check("mcp__MTmcp__mt_file_list".equals(
                ToolCallCodec.resolveName("mt_file_list", k)), "3 去 mcp__ 前缀");
        check("read_file".equals(ToolCallCodec.resolveName("read_fil" + "e", k)),
                "4 精确优先于包含");
        check(ToolCallCodec.resolveName("不存在的工具", k) == null, "无匹配时丢弃");
        check(ToolCallCodec.resolveName("read",
                Arrays.asList("read_file", "read_files")) == null, "歧义时丢弃（不猜）");

        String blk = "<function_calls><function_call><tool>MT_FILE_LIST</tool>"
                + "<args_json><![CDATA[{\"path\":\"/x\"}]]></args_json>"
                + "</function_call></function_calls>";
        JSONArray c = ToolCallCodec.parseCalls(blk, k);
        check(c.length() == 1 && "mcp__MTmcp__mt_file_list".equals(
                c.optJSONObject(0).optJSONObject("function").optString("name")),
                "名字校正贯通到 parseCalls");
    }

    // ── D3 arguments 非法 JSON 被静默清空 ───────────────────────────────
    static void t3Args() {
        System.out.println("\n[D3] arguments 非法时保留原文，不静默清空");
        check("{\"a\":1}".equals(ToolCallCodec.normArgs("{\"a\":1}")), "合法 JSON 原样");
        check("{}".equals(ToolCallCodec.normArgs("")), "空 -> {}");
        check("{\"a\":1}".equals(ToolCallCodec.normArgs("<![CDATA[{\"a\":1}]]>")), "剥 CDATA");
        String bad = "{path: /a.txt 这不是JSON}";
        check(bad.equals(ToolCallCodec.normArgs(bad)), "非法 JSON 保留原文");
        String blk = "<function_calls><function_call><tool>read_file</tool>"
                + "<args_json><![CDATA[{path: /a.txt}]]></args_json>"
                + "</function_call></function_calls>";
        JSONArray c = ToolCallCodec.parseCalls(blk, Arrays.asList("read_file"));
        check(c.length() == 1 && "{path: /a.txt}".equals(
                c.optJSONObject(0).optJSONObject("function").optString("arguments")),
                "贯通到 parseCalls：参数可见");
    }

    // ── D4 tool_choice 完全不生效 ───────────────────────────────────────
    static void t4ToolChoice() {
        System.out.println("\n[D4] tool_choice 契约");
        JSONArray t = new JSONArray();
        t.put(tool("read_file", "{\"type\":\"object\",\"properties\":"
                + "{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}"));

        String pNone = ToolCallPrompt.buildPrompt("[用户]\nhi", t, "none");
        check(!pNone.contains("可用工具"), "none：不注入工具说明");
        check(pNone.length() < 200, "none：不残留悬空的工具段", pNone.length() + "c");

        check(ToolCallPrompt.buildPrompt("[用户]\nhi", t, "required").contains("必须调用"),
                "required：要求必须调用");

        JSONObject forced = new JSONObject();
        try {
            JSONObject fn = new JSONObject().put("name", "read_file");
            forced.put("type", "function").put("function", fn);
        } catch (Exception ignored) {
        }
        String pForced = ToolCallPrompt.buildPrompt("[用户]\nhi", t, forced);
        check(pForced.contains("read_file") && pForced.contains("必须调用"),
                "对象形式：点名强制");
        try {
            JSONObject unknown = new JSONObject().put("type", "function")
                    .put("function", new JSONObject().put("name", "nope"));
            check(!ToolCallPrompt.buildPrompt("[用户]\nhi", t, unknown).contains("nope"),
                    "对象形式但名字不在清单里时不乱点名");
        } catch (Exception e) {
            check(false, "对象形式但名字不在清单里时不乱点名");
        }
        check(!ToolCallPrompt.buildPrompt("[用户]\nhi", t, "auto").contains("必须调用"),
                "auto：不加强制语句");
        check(ToolCallPrompt.buildPrompt("[用户]\nhi", t, null) != null,
                "旧调用方式仍兼容（缺省 null）");
        check(!ToolCallPrompt.buildPrompt("[用户]\nx", new JSONArray(), null).contains("可用工具"),
                "空 tools 数组不注入工具段");
    }

    // ── D5 客户端错误原样回灌 ───────────────────────────────────────────
    static void t5Error() {
        System.out.println("\n[D5] 客户端错误的中性化与结构化还原");
        String raw = "Tool \"read_files\" does not exists. Please check the tool name.";
        String out = ToolCallCodec.neutralizeToolError(raw);
        check(!out.contains("does not exists"), "抹掉「不存在」的原始措辞");
        check(out.contains("read_files") || out.contains("该名称"), "保留工具名便于定位");
        check(out.contains("重新调用") || out.contains("重试"), "给出重试指引");

        String structBad = "{\"ok\":false,\"error\":{\"code\":\"invalid_params\","
                + "\"message\":\"missing required parameter: path\"}}";
        String out2 = ToolCallCodec.neutralizeToolError(structBad);
        check(out2.contains("path") && !out2.contains("{"), "结构化错误还原为「缺哪个参数」");

        String ok = "{\"ok\":true,\"result\":\"file content here\"}";
        check(ok.equals(ToolCallCodec.neutralizeToolError(ok)), "成功结果不动");
        check("普通文件内容".equals(ToolCallCodec.neutralizeToolError("普通文件内容")), "普通内容不动");
        check("".equals(ToolCallCodec.neutralizeToolError("")), "空串安全");

        JSONArray msgs = new JSONArray();
        try {
            msgs.put(new JSONObject().put("role", "user").put("content", "读文件"));
            JSONObject asst = new JSONObject().put("role", "assistant");
            asst.put("content", JSONObject.NULL);
            JSONArray tcs = new JSONArray();
            JSONObject tc = new JSONObject().put("id", "call_1").put("type", "function");
            tc.put("function", new JSONObject().put("name", "read_file")
                    .put("arguments", "{\"path\":\"/a\"}"));
            tcs.put(tc);
            asst.put("tool_calls", tcs);
            msgs.put(asst);
            msgs.put(new JSONObject().put("role", "tool").put("tool_call_id", "call_1")
                    .put("content", raw));
        } catch (Exception ignored) {
        }
        String folded = ToolCallPrompt.foldMessages(msgs, null);
        check(!folded.contains("does not exists"), "折进 messages 后错误已被中性化");
        check(folded.contains("<function_call>") && folded.contains("<tool>read_file</tool>"),
                "助手的历史调用按原协议形态还原（保持格式记忆）");
    }

    // ── D6 预算溢出时未丢格式说明，仍超限 ───────────────────────────────
    static void t6Budget() {
        System.out.println("\n[D6] 预算分级降级：任何输入都不得超硬上限");
        JSONArray t = new JSONArray();
        for (int i = 0; i < 30; i++) {
            StringBuilder desc = new StringBuilder();
            for (int j = 0; j < 300; j++) {
                desc.append('y');
            }
            t.put(tool("tool_" + i, "{\"type\":\"object\",\"properties\":"
                    + "{\"p\":{\"type\":\"string\",\"description\":\"" + desc + "\"}},"
                    + "\"required\":[\"p\"]}"));
        }
        StringBuilder bigCtx = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            bigCtx.append("[用户]\n第").append(i).append("轮问题\n");
        }
        String ctx = bigCtx.toString();

        String p = ToolCallPrompt.buildPrompt(ctx, t, null);
        check(p.length() <= ToolCallPrompt.INPUT_BUDGET, "正常输入不超预算",
                p.length() + "c / " + ToolCallPrompt.INPUT_BUDGET + "c");
        check(p.contains("tool_0"), "工具名仍在（优先级最高）");

        JSONArray t2 = new JSONArray();
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            longName.append('z');
        }
        for (int i = 0; i < 30; i++) {
            t2.put(tool(longName.toString(), "{\"type\":\"object\",\"properties\":{}}"));
        }
        String p2 = ToolCallPrompt.buildPrompt(ctx, t2, null);
        check(p2.length() <= ToolCallPrompt.INPUT_BUDGET, "工具名撑爆时也不超限",
                p2.length() + "c");

        String p3 = ToolCallPrompt.buildPrompt(ctx, null, null);
        check(p3.length() <= ToolCallPrompt.INPUT_BUDGET, "无工具请求也受预算约束",
                p3.length() + "c");
        check(p3.contains("第1999轮问题"), "尾部上下文被保留（取尾部截断）");

        String mem = ToolCallPrompt.SIGNAL.replaceAll(".", "═");
        StringBuilder memBlock = new StringBuilder();
        for (int i = 0; i < 46; i++) {
            memBlock.append('═');
        }
        String sep = memBlock.toString();
        String memCtx = sep + "\nMEMORY (your personal notes) [10%]\n" + sep
                + "\n关键记忆条目ABC\n§\n用户偏好XYZ\n" + ctx;
        String p4 = ToolCallPrompt.buildPrompt(memCtx, t, null);
        check(p4.contains("【长期记忆】") && p4.contains("关键记忆条目ABC"),
                "记忆在极端预算下仍被转运", p4.length() + "c");
        check(p4.length() <= ToolCallPrompt.INPUT_BUDGET, "带记忆也不超限", p4.length() + "c");
    }

    // ── D7 体积与形态护栏 ───────────────────────────────────────────────
    static void t7Regress() {
        System.out.println("\n[D7] 体积与形态护栏");
        JSONArray t = new JSONArray();
        t.put(tool("read_file", "{\"type\":\"object\",\"properties\":{"
                + "\"path\":{\"type\":\"string\",\"description\":\"文件绝对路径\"},"
                + "\"enc\":{\"type\":\"string\",\"enum\":[\"utf-8\",\"gbk\"],"
                + "\"description\":\"默认为 utf-8\"}},\"required\":[\"path\"]}"));

        String p = ToolCallPrompt.buildPrompt("[用户]\n读文件", t, null);
        check(p.contains(ToolCallPrompt.SIGNAL), "信号行仍在指令里");
        check(p.contains("<args_json><![CDATA["), "格式示例仍是 XML+CDATA（不换协议）");
        check(p.contains("read_file(path*:str"), "紧凑签名：必填带 *");
        check(p.contains("utf-8|gbk"), "枚举值保留（合法取值约束，成本极低）");
        check(p.contains("- read_file("), "工具清单逐行渲染");

        // 14 工具时体积护栏：超过 2500 字符就会挤掉对话预算
        JSONArray t14 = new JSONArray();
        for (int i = 0; i < t.length(); i++) {
            t14.put(t.opt(i));
        }
        for (int i = 0; i < 13; i++) {
            t14.put(tool("apk_tool_" + i, "{\"type\":\"object\",\"properties\":{"
                    + "\"target\":{\"type\":\"string\"},"
                    + "\"deep\":{\"type\":\"boolean\"}},\"required\":[\"target\"]}"));
        }
        String p14 = ToolCallPrompt.buildPrompt("[用户]\n分析 apk", t14, null);
        check(p14.length() < 2500, "14 工具下 prompt 仍精简", p14.length() + "c");
        check(p14.contains("工具较多"), "超过阈值时提示参数已压缩");

        JSONArray tmin = new JSONArray();
        tmin.put(tool("t", null));
        check(ToolCallPrompt.buildPrompt("[用户]\nx", tmin, null).contains("t(-)"),
                "无参工具渲染为 t(-)");
    }

    // ── D8 响应契约 ─────────────────────────────────────────────────────
    static void t8Response() {
        System.out.println("\n[D8] 响应契约：tool_calls 的 OpenAI 形态");
        JSONArray calls = ToolCallCodec.parseCalls(
                "<function_calls><function_call><tool>read_file</tool>"
                        + "<args_json><![CDATA[{\"path\":\"/a\"}]]></args_json>"
                        + "</function_call></function_calls>", Arrays.asList("read_file"));
        check(calls.length() == 1 && calls.optJSONObject(0).optString("id").startsWith("call_"),
                "id 前缀为 call_（SDK 会校验）");
        check("function".equals(calls.optJSONObject(0).optString("type")), "type=function");

        JSONObject req = new JSONObject();
        try {
            req.put("tools", new JSONArray().put(tool("read_file", null)));
        } catch (Exception ignored) {
        }
        String withHead = ToolCallPrompt.SIGNAL
                + "\n<function_calls><function_call><tool>read_file</tool>"
                + "<args_json><![CDATA[{\"path\":\"/a\"}]]></args_json>"
                + "</function_call></function_calls>";
        String body = ToolCallBridge.openAiToolCallsCompletion("abc", "好的，我来读取。" + withHead,
                ToolCallBridge.extractCalls(req, "好的，我来读取。" + withHead));
        try {
            JSONObject o = new JSONObject(body);
            JSONObject choice = o.getJSONArray("choices").getJSONObject(0);
            check("tool_calls".equals(choice.optString("finish_reason")), "finish_reason=tool_calls");
            JSONObject msg = choice.getJSONObject("message");
            check("好的，我来读取。".equals(msg.optString("content")),
                    "信号行前的正文保留为 content");
            JSONArray got = msg.getJSONArray("tool_calls");
            check(got.length() == 1 && "read_file".equals(got.getJSONObject(0)
                    .getJSONObject("function").optString("name")), "message.tool_calls 正确");
            check(new JSONObject(got.getJSONObject(0).getJSONObject("function")
                    .optString("arguments")).optString("path").equals("/a"),
                    "arguments 是可解析的 JSON 字符串");
        } catch (Exception e) {
            check(false, "非流式响应体结构", e.toString());
        }

        // 流式帧：delta 里带 index，收尾 finish_reason=tool_calls
        JSONObject chunk = ToolCallBridge.buildToolCallsChunk("cmpl-1", calls);
        try {
            JSONObject delta = chunk.getJSONArray("choices").getJSONObject(0).getJSONObject("delta");
            check(delta.has("tool_calls"), "流式 delta 带 tool_calls");
            check(delta.getJSONArray("tool_calls").getJSONObject(0).optInt("index", -1) == 0,
                    "分片带 index（OpenAI 规范）");
            check("assistant".equals(delta.optString("role")), "delta.role=assistant");
        } catch (Exception e) {
            check(false, "流式帧结构", e.toString());
        }

        // tool_choice="none" 时不应回吐调用
        JSONObject noneReq = new JSONObject();
        try {
            noneReq.put("tools", new JSONArray().put(tool("read_file", null)));
            noneReq.put("tool_choice", "none");
        } catch (Exception ignored) {
        }
        check(ToolCallBridge.extractCalls(noneReq, withHead).length() == 0,
                "tool_choice=none 时不回吐调用（契约兜底）");
        check(ToolCallBridge.extractCalls(req, "没有调用，只是普通回答").length() == 0,
                "普通回答不产生 tool_calls");
    }

    // ── D9 带工具时的注入文本组装 ───────────────────────────────────────
    static void t9InjectText() {
        System.out.println("\n[D9] 折叠 + 注入：整段 messages 而非仅最后一条 user");
        JSONObject req = new JSONObject();
        try {
            JSONArray msgs = new JSONArray();
            msgs.put(new JSONObject().put("role", "system").put("content", "你是助手"));
            msgs.put(new JSONObject().put("role", "user").put("content", "读文件"));
            JSONObject asst = new JSONObject().put("role", "assistant");
            JSONArray tcs = new JSONArray();
            tcs.put(new JSONObject().put("id", "call_1").put("type", "function")
                    .put("function", new JSONObject().put("name", "read_file")
                            .put("arguments", "{\"path\":\"/a\"}")));
            asst.put("tool_calls", tcs);
            msgs.put(asst);
            msgs.put(new JSONObject().put("role", "tool").put("tool_call_id", "call_1")
                    .put("content", "文件内容：fastapi"));
            msgs.put(new JSONObject().put("role", "user").put("content", "第一行是什么？"));
            req.put("messages", msgs);
            req.put("tools", new JSONArray().put(tool("read_file", null)));
        } catch (Exception ignored) {
        }
        String txt = ToolCallBridge.buildInjectText(req, "系统提示词ABC");
        check(ToolCallPrompt.SIGNAL != null && txt.contains(ToolCallPrompt.SIGNAL),
                "注入文本带信号行（协议说明）");
        check(txt.contains("可用工具"), "带可用工具清单");
        check(txt.contains("文件内容：fastapi"), "工具结果被折进上下文（模型需要它续答）");
        check(txt.contains("[用户]\n读文件"), "历史 user 消息保留");
        check(txt.contains("系统提示词ABC"), "设置页系统提示词并入");
        check(txt.length() <= ToolCallPrompt.INPUT_BUDGET, "注入文本不超预算",
                txt.length() + "c");

        String noSys = ToolCallBridge.buildInjectText(req, "");
        check(!noSys.contains("系统提示词ABC"), "系统提示词为空时不残留");
    }

    public static void main(String[] args) {
        System.out.println("======================================================");
        System.out.println("工具调用层离线回归（Java 移植）  INPUT_BUDGET="
                + ToolCallPrompt.INPUT_BUDGET);
        System.out.println("======================================================");
        t1BareJson();
        t2ResolveName();
        t3Args();
        t4ToolChoice();
        t5Error();
        t6Budget();
        t7Regress();
        t8Response();
        t9InjectText();
        System.out.println("\n======================================================");
        if (fails > 0) {
            System.out.println("失败 " + fails + " 项");
            System.exit(1);
        }
        System.out.println("全部通过。");
    }
}
