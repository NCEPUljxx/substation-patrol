package com.substation.common.mq;

import com.alibaba.fastjson2.JSONObject;

import java.util.Collections;
import java.util.Map;

/**
 * 消息构建器，将类型、tick、车辆ID、数据组装成统一的JSON消息字符串。
 * 所有MQ消息均通过此工具类生成，保证格式一致。
 */
public final class MessageBuilder {

    /** 工具类，禁止实例化 */
    private MessageBuilder() {}

    /**
     * 构建完整消息（包含data字段）。
     *
     * @param type  消息类型，取值见 {@link MessageTypes}
     * @param tick  当前仿真tick号
     * @param carId 小车ID，可为null（系统级消息无需carId）
     * @param data  业务数据，可为null（自动替换为空Map）
     * @return JSON格式的消息字符串
     */
    public static String build(String type, int tick, String carId, Map<String, Object> data) {
        JSONObject msg = new JSONObject();
        msg.put("type", type);
        msg.put("tick", tick);
        if (carId != null) {
            msg.put("carId", carId);
        }
        msg.put("timestamp", System.currentTimeMillis());
        msg.put("data", data != null ? data : Collections.emptyMap());
        return msg.toJSONString();
    }

    /**
     * 构建消息（data为空Map）。
     *
     * @param type  消息类型
     * @param tick  当前tick号
     * @param carId 小车ID
     * @return JSON格式的消息字符串
     */
    public static String build(String type, int tick, String carId) {
        return build(type, tick, carId, null);
    }

    /**
     * 构建系统级消息（无carId，无data）。
     *
     * @param type 消息类型
     * @param tick 当前tick号
     * @return JSON格式的消息字符串
     */
    public static String build(String type, int tick) {
        return build(type, tick, null, null);
    }
}
