import com.wanxiang.xiaobubridge.ImageResultCodec;
import com.wanxiang.xiaobubridge.ImageResultCodec.ImageResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ImageResultCodec 离线回归（不摸小布、不依赖 Android）。
 *
 * <p>夹具不是编造的：{@code FIXTURE_REAL} 是真机抓到的 payload 原样数据
 * （小布 12.9.9，文生图一轮）。其余各例对应一类「会让抽图失败」的具体缺陷。</p>
 */
public class ImageCodecTest {

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

    /** 真机原样 payload（唯一被裁剪的是 roomId 与 URL 的随机段，结构一字未改） */
    static final String FIXTURE_REAL = "{"
            + "\"skillId\":17085,"
            + "\"uiDirectives\":[{"
            + "  \"header\":{\"name\":\"PictureCard\",\"namespace\":\"MyAI\",\"version\":\"2.3\"},"
            + "  \"payload\":{"
            + "    \"picUrl\":\"https://bot-pubstatic-cn.heytapdownload.com/text2image/online/1789736259116__647947364.jpg\","
            + "    \"aspectRatio\":\"2048:2048\",\"businessType\":\"common\","
            + "    \"extend\":{\"tag\":\"文生图\"},\"loadType\":2,\"stateCode\":1,"
            + "    \"roomId\":\"6e56ea9d814aba57967339fda73ad199-1789735933550-default\""
            + "  }}]}";

    // ── D1 正路：协议形态（header 指名 + payload 承载）必须抽得到 ─────────
    static void t1RealPayload() {
        System.out.println("\n[D1] 真机 payload 必须抽出 1 张图，且字段完整");
        List<ImageResult> r = ImageResultCodec.extract(FIXTURE_REAL);
        check(r.size() == 1, "抽出 1 张", "size=" + r.size());
        if (r.size() != 1) return;
        ImageResult i = r.get(0);
        check(i.url != null && i.url.endsWith(".jpg"), "URL 抽到", String.valueOf(i.url));
        check("2048:2048".equals(i.aspectRatio), "aspectRatio", String.valueOf(i.aspectRatio));
        check("文生图".equals(i.tag), "extend.tag", String.valueOf(i.tag));
        check(Integer.valueOf(2).equals(i.loadType), "loadType");
        check(Integer.valueOf(1).equals(i.stateCode), "stateCode");
        check("common".equals(i.businessType), "businessType");
    }

    // ── D2 header 被改名时，字段匹配仍要捞出 picUrl ──────────────────────
    static void t2HeaderRenamed() {
        System.out.println("\n[D2] header 改名/缺失时按 picUrl 字段兜住");
        String renamed = "{\"uiDirectives\":[{\"header\":{\"name\":\"ImageCard\","
                + "\"namespace\":\"MyAI\"},\"payload\":{\"picUrl\":\"https://x/a.png\"}}]}";
        check(ImageResultCodec.extract(renamed).size() == 1, "header.name 变了仍能抽到");

        String noHeader = "{\"uiDirectives\":[{\"payload\":{\"picUrl\":\"https://x/b.png\","
                + "\"aspectRatio\":\"1:1\"}}]}";
        check(ImageResultCodec.extract(noHeader).size() == 1, "完全没有 header 也能抽到");

        // namespace 不匹配但 name 匹配：接受（按 name 已足够特异），仍应抽到
        String otherNs = "{\"header\":{\"name\":\"PictureCard\",\"namespace\":\"Other\"},"
                + "\"payload\":{\"picUrl\":\"https://x/c.png\"}}";
        check(ImageResultCodec.extract(otherNs).size() == 1, "namespace 不匹配时按 name 接受");
    }

    // ── D3 markdownCardInfos 兜底通道（info 内联载荷） ───────────────────
    static void t3MarkdownCards() {
        System.out.println("\n[D3] markdownCardInfos 通道（info 内联 / info 为字符串）");
        String cardInline = "{\"type\":\"picture\",\"index\":0,"
                + "\"info\":{\"picUrl\":\"https://x/m1.png\",\"aspectRatio\":\"1:1\"}}";
        List<ImageResult> a = ImageResultCodec.extractAll(null, Arrays.asList(cardInline));
        check(a.size() == 1 && "https://x/m1.png".equals(a.get(0).url), "info 为对象时抽到");

        String cardString = "{\"type\":\"picture\","
                + "\"info\":\"{\\\"picUrl\\\":\\\"https://x/m2.png\\\"}\"}";
        List<ImageResult> b = ImageResultCodec.extractAll(null, Arrays.asList(cardString));
        check(b.size() == 1 && "https://x/m2.png".equals(b.get(0).url), "info 为 JSON 字符串时抽到");

        String notJson = "CardInfo(height=100, index=0, info=null, type=picture)";
        check(ImageResultCodec.extractAll(null, Arrays.asList(notJson)).isEmpty(),
                "结构体 toString() 不误判（非 JSON）");

        // 两条通道给出同一张图：按 URL 去重
        List<ImageResult> both = ImageResultCodec.extractAll(FIXTURE_REAL, Arrays.asList(
                "{\"info\":{\"picUrl\":\"https://bot-pubstatic-cn.heytapdownload.com/"
                        + "text2image/online/1789736259116__647947364.jpg\"}}"));
        check(both.size() == 1, "两通道同图按 URL 去重", "size=" + both.size());
    }

    // ── D4 抽不到时必须是「空列表」，绝不能抛 ────────────────────────────
    static void t4NoThrow() {
        System.out.println("\n[D4] 异常输入一律返回空列表，不抛异常");
        List<String> bad = new ArrayList<>();
        bad.add(null);
        bad.add("");
        bad.add("   ");
        bad.add("not json at all");
        bad.add("{ truncated");
        bad.add("{}");
        bad.add("{\"uiDirectives\":[]}");
        bad.add("{\"uiDirectives\":[{\"header\":{\"name\":\"PictureCard\"}}]}");
        bad.add("{\"uiDirectives\":[{\"header\":{\"name\":\"PictureCard\"},"
                + "\"payload\":{\"picUrl\":\"\"}}]}");
        bad.add("{\"uiDirectives\":[{\"header\":{\"name\":\"PictureCard\"},"
                + "\"payload\":{\"picUrl\":null}}]}");
        bad.add("[1,2,3]");
        bad.add("\"a string\"");
        for (String s : bad) {
            List<ImageResult> r = ImageResultCodec.extract(s);
            if (!r.isEmpty()) {
                check(false, "空/坏输入应无结果", "input=" + s);
                return;
            }
        }
        check(true, "12 种空/坏输入均返回空列表且未抛异常");

        check(ImageResultCodec.extract(null).isEmpty(), "payload 为 null");
        check(ImageResultCodec.extractAll(null, null).isEmpty(), "两参数均为 null");
        List<String> withNulls = new ArrayList<>();
        withNulls.add(null);
        withNulls.add("{}");
        check(ImageResultCodec.extractAll(null, withNulls).isEmpty(), "列表内含 null 元素");
    }

    // ── D5 非图片卡片不能被当成图片 ──────────────────────────────────────
    static void t5NoFalsePositive() {
        System.out.println("\n[D5] 普通卡片/正文 JSON 不得误判为图片结果");
        String textCard = "{\"uiDirectives\":[{\"header\":{\"name\":\"TextCard\","
                + "\"namespace\":\"MyAI\"},\"payload\":{\"title\":\"今天的天气\",\"content\":\"晴\"}}]}";
        check(ImageResultCodec.extract(textCard).isEmpty(), "TextCard 不产出图片");

        String nested = "{\"data\":{\"list\":[{\"items\":[{\"name\":\"x\",\"count\":1}]}]}}";
        check(ImageResultCodec.extract(nested).isEmpty(), "普通嵌套 JSON 不产出图片");

        String recommend = "{\"uiDirectives\":[{\"header\":{\"name\":\"RecommendCard\","
                + "\"namespace\":\"MyAI\"},\"payload\":{\"iconUrl\":\"https://x/icon.png\","
                + "\"items\":[]}}]}";
        check(ImageResultCodec.extract(recommend).isEmpty(),
                "iconUrl 不算图片结果（只认 picUrl）");
    }

    // ── D6 多图按出现顺序返回 ───────────────────────────────────────────
    static void t6Multiple() {
        System.out.println("\n[D6] 一轮出多张图时按出现顺序返回");
        String multi = "{\"uiDirectives\":[{\"header\":{\"name\":\"PictureCard\","
                + "\"namespace\":\"MyAI\"},\"payload\":{\"picUrl\":\"https://x/1.png\"}},"
                + "{\"header\":{\"name\":\"PictureCard\",\"namespace\":\"MyAI\"},"
                + "\"payload\":{\"picUrl\":\"https://x/2.png\"}}]}";
        List<ImageResult> r = ImageResultCodec.extract(multi);
        check(r.size() == 2, "抽出 2 张", "size=" + r.size());
        if (r.size() == 2) {
            check("https://x/1.png".equals(r.get(0).url) && "https://x/2.png".equals(r.get(1).url),
                    "顺序与出现顺序一致");
        }
    }

    // ── D7 深嵌套与 JSON 字符串载荷 ─────────────────────────────────────
    static void t7DeepNesting() {
        System.out.println("\n[D7] 深嵌套 / 序列化字符串形态");
        String deep = "{\"a\":{\"b\":{\"c\":[{\"d\":{\"header\":{\"name\":\"PictureCard\","
                + "\"namespace\":\"MyAI\"},\"payload\":{\"picUrl\":\"https://x/deep.png\","
                + "\"loadType\":\"2\",\"stateCode\":\"1\"}}}]}}}";
        List<ImageResult> r = ImageResultCodec.extract(deep);
        check(r.size() == 1 && "https://x/deep.png".equals(r.get(0).url), "深层嵌套仍能抽到");
        if (r.size() == 1) {
            check(Integer.valueOf(2).equals(r.get(0).loadType), "字符串型 loadType 转成整数");
            check(Integer.valueOf(1).equals(r.get(0).stateCode), "字符串型 stateCode 转成整数");
        }

        String payloadAsString = "{\"uiDirectives\":\"[{\\\"header\\\":{\\\"name\\\":"
                + "\\\"PictureCard\\\",\\\"namespace\\\":\\\"MyAI\\\"},\\\"payload\\\":"
                + "{\\\"picUrl\\\":\\\"https://x/str.png\\\"}}]\"}";
        check(ImageResultCodec.extract(payloadAsString).size() == 1, "嵌套 JSON 以字符串给出时抽到");
    }

    public static void main(String[] args) {
        t1RealPayload();
        t2HeaderRenamed();
        t3MarkdownCards();
        t4NoThrow();
        t5NoFalsePositive();
        t6Multiple();
        t7DeepNesting();
        System.out.println("\n" + (fails == 0 ? "全部通过" : fails + " 项失败"));
        System.exit(fails == 0 ? 0 : 1);
    }
}
