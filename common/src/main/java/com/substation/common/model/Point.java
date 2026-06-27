package com.substation.common.model;

import com.alibaba.fastjson2.JSON;

/** 二维坐标记录，支持曼哈顿距离计算和JSON序列化 */
public record Point(int x, int y) {

    /** 计算到另一点的曼哈顿距离 */
    public int manhattanDistance(Point other) {
        return Math.abs(x - other.x) + Math.abs(y - other.y);
    }

    /** 将当前坐标序列化为JSON字符串 */
    public String toJson() {
        return JSON.toJSONString(this);
    }

    /** 从JSON字符串反序列化为Point对象 */
    public static Point fromJson(String json) {
        return JSON.parseObject(json, Point.class);
    }
}
