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
