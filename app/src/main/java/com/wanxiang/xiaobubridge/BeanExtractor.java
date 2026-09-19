package com.wanxiang.xiaobubridge;

import de.robv.android.xposed.XposedBridge;

import java.lang.reflect.Method;

/**
 * 反射工具类：安全提取 AIChatViewBean 字段
 * 防止因混淆或版本变更导致模块崩溃
 */
public class BeanExtractor {

    private static final String TAG = "[XiaoBuBridge]";
    private static final String BEAN_CLASS = "com.heytap.speechassist.aichat.bean.AIChatViewBean";

    /**
     * 获取回复正文内容
     */
    public static String getContent(Object bean) {
        return invokeStringMethod(bean, "getContent");
    }

    /**
     * 获取聊天类型：1=用户提问(QUERY), 2=AI回答(ANSWER)
     */
    public static int getChatType(Object bean) {
        try {
            Method method = bean.getClass().getMethod("getChatType");
            Object result = method.invoke(bean);
            if (result instanceof Integer) {
                return (Integer) result;
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + " getChatType failed: " + e.getMessage());
        }
        return -1; // 未知类型
    }

    /**
     * 获取消息唯一标识
     */
    public static String getUniqueId(Object bean) {
        return invokeStringMethod(bean, "getUniqueId");
    }

    /**
     * 获取会话记录ID
     */
    public static String getRecordId(Object bean) {
        return invokeStringMethod(bean, "getRecordId");
    }

    /**
     * 是否为流式响应的最后一片段
     * 注意：小布 AIChatViewBean 的实际方法名是 isFinal()（无 get 前缀，返回 Boolean），
     * 早期版本曾误写成 getIsFinal()，此处按候选名依次尝试并回退，避免混淆改名后失效。
     */
    public static Boolean getIsFinal(Object bean) {
        if (bean == null) return null;
        String[] candidates = new String[]{"isFinal", "getIsFinal"};
        for (String name : candidates) {
            try {
                Method method = bean.getClass().getMethod(name);
                Object result = method.invoke(bean);
                if (result instanceof Boolean) {
                    return (Boolean) result;
                }
            } catch (NoSuchMethodException ignored) {
                // 继续尝试下一个候选方法名
            } catch (Exception e) {
                XposedBridge.log(TAG + " " + name + " invoke failed: " + e.getMessage());
            }
        }
        XposedBridge.log(TAG + " isFinal/getIsFinal not found in " + bean.getClass().getName());
        return null;
    }

    /**
     * 获取推理/思考过程内容（可能为null）
     */
    public static String getReasoningContent(Object bean) {
        return invokeStringMethod(bean, "getReasoningContent");
    }

    /**
     * 获取 UI 指令载荷 JSON（v3.15，文生图取图用）。
     *
     * <p>小布把本轮回答附带的 UI 指令（卡片、图片等）序列化成 JSON 放在这个字段里，
     * 正文 {@code content} 只有「已生成图片」几个字，真正的图片 URL 在这里。
     * 内容形如 {@code {"skillId":…,"uiDirectives":[{"header":{…},"payload":{…}}]}}。</p>
     *
     * <p>字段来源（小布 12.9.9 dex 已核实）：
     * {@code AIChatViewBean.payload} ← {@code AIChatViewBean$Companion} 里
     * 由 {@code Operation.getDirective()} 经 {@code h3.a()} 序列化后 setPayload；
     * 另在 {@code AIChatViewBeanProvider} 中用于推荐/卡片类 bean。</p>
     */
    public static String getPayload(Object bean) {
        return invokeStringMethod(bean, "getPayload");
    }

    /**
     * 获取 markdown 卡片列表（v3.15，图片载荷的版本兜底通道）。
     *
     * <p>部分小布版本把卡片载荷放在 {@code markdownCardInfos} 而非 {@code payload}，
     * 元素类型为 {@code myai.CardInfo}，其 {@code info} 字段是多态载荷
     * （可能直接是 {@code PictureCard}，也可能是其 JSON/Map 形态）。
     * 这里只做「拿到列表再逐个转 JSON」的粗加工，字段解析交给
     * {@link ImageResultCodec}，避免把版本差异写死在反射层。</p>
     *
     * @return 列表元素 JSON 文本；方法不存在、列表为空或全部无法序列化时返回 null
     */
    public static java.util.List<String> getMarkdownCardInfos(Object bean) {
        if (bean == null) return null;
        Object list;
        try {
            Method method = bean.getClass().getMethod("getMarkdownCardInfos");
            list = method.invoke(bean);
        } catch (NoSuchMethodException e) {
            return null;
        } catch (Exception e) {
            XposedBridge.log(TAG + " getMarkdownCardInfos invoke failed: " + e.getMessage());
            return null;
        }
        if (!(list instanceof java.util.List)) {
            return null;
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Object item : (java.util.List<?>) list) {
            if (item == null) continue;
            String json = toJsonText(item);
            if (json != null && !json.isEmpty()) {
                out.add(json);
            }
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * 尽量把任意对象转成 JSON 文本。
     *
     * <p>优先调用对象自身的 {@code toJson()}（小布的协议类多由此生成 payload），
     * 失败再退回 {@code toString()}——后者在 Kotlin data class 上是
     * {@code CardInfo(height=…, index=…, info=…)} 这类形态，不是 JSON，
     * 因此仅当作"能拿到点文本"的兜底，解析侧对非 JSON 输入必须容错。</p>
     */
    private static String toJsonText(Object obj) {
        for (String name : new String[]{"toJson", "toJSONString", "toJsonString"}) {
            try {
                Method m = obj.getClass().getMethod(name);
                Object r = m.invoke(obj);
                if (r != null) return r.toString();
            } catch (NoSuchMethodException ignored) {
                // 试下一个候选名
            } catch (Exception e) {
                XposedBridge.log(TAG + " " + name + " invoke failed: " + e.getMessage());
            }
        }
        try {
            return String.valueOf(obj);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 通用字符串方法调用封装
     */
    private static String invokeStringMethod(Object bean, String methodName) {
        if (bean == null) return null;
        try {
            Method method = bean.getClass().getMethod(methodName);
            Object result = method.invoke(bean);
            return result != null ? result.toString() : null;
        } catch (NoSuchMethodException e) {
            XposedBridge.log(TAG + " " + methodName + " not found in " + bean.getClass().getName());
        } catch (Exception e) {
            XposedBridge.log(TAG + " " + methodName + " invoke failed: " + e.getMessage());
        }
        return null;
    }
}
