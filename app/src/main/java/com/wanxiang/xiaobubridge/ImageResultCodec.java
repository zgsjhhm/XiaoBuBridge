package com.wanxiang.xiaobubridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从回答 bean 的 UI 指令载荷里抽取图片结果（文生图取图链路）。
 *
 * <p><b>为什么需要单独一层</b>：小布的文生图回答里，正文 {@code content} 只有
 * 「已生成图片」几个字，真正的图片 URL 在 {@code AIChatViewBean.payload} 里。
 * 真机抓到的原样数据（小布 12.9.9）：</p>
 *
 * <pre>{@code
 * {"skillId":17085,"uiDirectives":[{
 *   "header":{"name":"PictureCard","namespace":"MyAI","version":"2.3"},
 *   "payload":{
 *     "picUrl":"https://bot-pubstatic-cn.heytapdownload.com/text2image/online/...jpg",
 *     "aspectRatio":"2048:2048","businessType":"common",
 *     "extend":{"tag":"文生图"},"loadType":2,"stateCode":1,
 *     "roomId":"..."}}]}
 * }</pre>
 *
 * <p>载荷类是 {@code com.heytap.speech.engine.protocol.directive.myai.PictureCard}
 * （{@code @DirectivePayloadKey("MyAI.PictureCard")}，dex 已核实），字段为
 * {@code picUrl / roomId / aspectRatio / businessType / loadType / stateCode / extend}。</p>
 *
 * <p>本类只依赖 {@code org.json}，不碰 Android / Xposed，因此可以在 PC 上直接跑
 * 离线回归（{@code tools/run_image_codec_test.sh}）——小布版本一变、payload 一改，
 * 先在这里炸，而不是等装到设备上才发现抽不出 URL。</p>
 *
 * <p><b>容错策略（刻意保守）</b>：payload 缺失、不是 JSON、结构变了、
 * 图片字段为空，全部只是「抽不到」，绝不抛异常。Hook 跑在小布主进程里，
 * 解析失败带崩宿主是任何收益都不值得的代价。</p>
 */
public final class ImageResultCodec {

    /** 图片卡片的命名空间与名字，来自 {@code @DirectivePayloadKey("MyAI.PictureCard")} */
    public static final String PICTURE_CARD_NAMESPACE = "MyAI";
    public static final String PICTURE_CARD_NAME = "PictureCard";

    /** 解析递归深度上限：JSON 里出现自引用形态的字符串时兜住，避免无限展开 */
    private static final int MAX_DEPTH = 8;

    private ImageResultCodec() {
    }

    /** 一张图片的结果。字段取自 PictureCard，缺省为 null。 */
    public static final class ImageResult {
        public final String url;
        /** 形如 {@code "2048:2048"}，用于推断 size；缺失为 null */
        public final String aspectRatio;
        /** {@code extend.tag}，实测文生图为「文生图」；缺失为 null */
        public final String tag;
        public final String roomId;
        public final String businessType;
        public final Integer loadType;
        public final Integer stateCode;

        ImageResult(String url, String aspectRatio, String tag, String roomId,
                    String businessType, Integer loadType, Integer stateCode) {
            this.url = url;
            this.aspectRatio = aspectRatio;
            this.tag = tag;
            this.roomId = roomId;
            this.businessType = businessType;
            this.loadType = loadType;
            this.stateCode = stateCode;
        }

        @Override
        public String toString() {
            return "ImageResult{url=" + url + ", aspectRatio=" + aspectRatio
                    + ", tag=" + tag + ", loadType=" + loadType + ", stateCode=" + stateCode + "}";
        }
    }

    /**
     * 从 payload JSON 抽图片结果。
     *
     * @param payloadJson {@code AIChatViewBean.getPayload()} 的返回值；可为 null / 非 JSON
     * @return 抽到的图片列表（按出现顺序、按 URL 去重）；抽不到返回空列表，不返回 null
     */
    public static List<ImageResult> extract(String payloadJson) {
        return extractAll(payloadJson, null);
    }

    /**
     * 从 payload 与 markdown 卡片信息两处一起抽（后者是版本兜底通道）。
     *
     * <p>部分小布版本把卡片载荷放在 {@code markdownCardInfos} 而不是 {@code payload}，
     * 元素里 {@code info} 字段才是真正的载荷对象。两处都扫、按 URL 去重，
     * 任一版本都能拿到。</p>
     *
     * @param payloadJson       payload JSON 文本，可 null
     * @param markdownCardInfos 各卡片元素的 JSON 文本，可 null
     */
    public static List<ImageResult> extractAll(String payloadJson, List<String> markdownCardInfos) {
        // 用 LinkedHashMap 保序去重：同一轮里 payload 与 markdownCardInfos 可能给出同一张图
        Map<String, ImageResult> byUrl = new LinkedHashMap<>();
        collectFromText(payloadJson, byUrl, 0);
        if (markdownCardInfos != null) {
            for (String card : markdownCardInfos) {
                collectFromText(card, byUrl, 0);
            }
        }
        return new ArrayList<>(byUrl.values());
    }

    /** 文本 → JSON → 递归收集。非 JSON 文本静默忽略。 */
    private static void collectFromText(String text, Map<String, ImageResult> out, int depth) {
        if (text == null || depth > MAX_DEPTH) {
            return;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        if (trimmed.charAt(0) != '{' && trimmed.charAt(0) != '[') {
            // 结构体 toString() 的形态（CardInfo(height=…, info=…)），不是 JSON，跳过
            return;
        }
        try {
            if (trimmed.charAt(0) == '{') {
                collect(new JSONObject(trimmed), out, depth);
            } else {
                collect(new JSONArray(trimmed), out, depth);
            }
        } catch (Exception ignored) {
            // payload 可能是被截断/非 JSON 的脏数据，按「抽不到」处理
        }
    }

    /**
     * 递归遍历 JSON 树，收集所有「看起来是图片载荷」的对象。
     *
     * <p>判定两路并行，任一命中即收：</p>
     * <ol>
     *   <li><b>命名匹配</b>：{@code header.name == "PictureCard"} 且
     *       {@code header.namespace == "MyAI"}，取其 {@code payload} 里的 picUrl。
     *       这是协议规定的正路。</li>
     *   <li><b>字段匹配</b>：对象自身直接带非空 {@code picUrl}。用于兜住
     *       header 被改名、或载荷被内联在 {@code CardInfo.info} 里的版本。</li>
     * </ol>
     * 不按 {@code skillId} 判定：skillId 是技能编号，文生图换技能实现就会变，
     * 而 picUrl 是图片载荷的稳定特征。
     */
    private static void collect(Object node, Map<String, ImageResult> out, int depth) {
        if (node == null || depth > MAX_DEPTH) {
            return;
        }
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                collect(arr.opt(i), out, depth + 1);
            }
            return;
        }
        if (!(node instanceof JSONObject)) {
            // 字符串形态的嵌套 JSON（CardInfo.info 为 String 的版本）
            if (node instanceof String) {
                collectFromText((String) node, out, depth + 1);
            }
            return;
        }

        JSONObject obj = (JSONObject) node;

        // 路径 1：对象自身就是图片载荷
        ImageResult direct = readPictureCard(obj);
        if (direct != null && direct.url != null && !direct.url.isEmpty()) {
            out.putIfAbsent(direct.url, direct);
        }

        // 路径 2：协议形态，header 指名 + payload 承载
        JSONObject header = obj.optJSONObject("header");
        if (header != null && isPictureCardHeader(header)) {
            JSONObject payload = optObject(obj, "payload");
            if (payload != null) {
                ImageResult fromPayload = readPictureCard(payload);
                // 协议形态下 extend.tag 可能在 payload 里，也可能在外层
                if (fromPayload != null && fromPayload.url != null && !fromPayload.url.isEmpty()) {
                    out.putIfAbsent(fromPayload.url, fromPayload);
                } else {
                    collect(payload, out, depth + 1);
                }
            }
        }

        // 继续下钻：uiDirectives / payload / info / data 等任意层级都可能嵌套
        JSONArray names = obj.names();
        if (names == null) {
            return;
        }
        for (int i = 0; i < names.length(); i++) {
            String key = names.optString(i, "");
            if ("picUrl".equals(key)) {
                continue; // 已在上面的 readPictureCard 里消费
            }
            Object child = obj.opt(key);
            if (child instanceof JSONObject || child instanceof JSONArray || child instanceof String) {
                collect(child, out, depth + 1);
            }
        }
    }

    private static boolean isPictureCardHeader(JSONObject header) {
        String name = header.optString("name", "");
        if (!PICTURE_CARD_NAME.equals(name)) {
            return false;
        }
        String ns = header.optString("namespace", "");
        // namespace 缺失时也接受（按 name 已足够特异），存在则必须匹配
        return ns.isEmpty() || PICTURE_CARD_NAMESPACE.equals(ns);
    }

    /** 从「疑似 PictureCard 的对象」里读字段；没有可用 picUrl 时返回 null */
    private static ImageResult readPictureCard(JSONObject o) {
        String url = optString(o, "picUrl");
        if (url == null || url.isEmpty()) {
            return null;
        }
        String tag = null;
        JSONObject extend = optObject(o, "extend");
        if (extend != null) {
            tag = optString(extend, "tag");
        }
        return new ImageResult(
                url,
                optString(o, "aspectRatio"),
                tag,
                optString(o, "roomId"),
                optString(o, "businessType"),
                optInteger(o, "loadType"),
                optInteger(o, "stateCode"));
    }

    /** 取对象字段，兼容「值本身是 JSON 字符串」的形态 */
    private static JSONObject optObject(JSONObject o, String key) {
        Object v = o.opt(key);
        if (v instanceof JSONObject) {
            return (JSONObject) v;
        }
        if (v instanceof String) {
            String s = ((String) v).trim();
            if (s.startsWith("{")) {
                try {
                    return new JSONObject(s);
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String optString(JSONObject o, String key) {
        Object v = o.opt(key);
        if (v == null || v == JSONObject.NULL) {
            return null;
        }
        String s = String.valueOf(v);
        return s.isEmpty() ? null : s;
    }

    private static Integer optInteger(JSONObject o, String key) {
        Object v = o.opt(key);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (v instanceof String) {
            try {
                return Integer.valueOf(((String) v).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
