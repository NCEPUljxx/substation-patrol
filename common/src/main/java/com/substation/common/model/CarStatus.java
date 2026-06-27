package com.substation.common.model;

/** 小车状态枚举，包含中文名称和前端展示颜色 */
public enum CarStatus {
    /** 空闲 */
    IDLE("空闲", "#9E9E9E"),
    /** 等待路径规划 */
    WAITING_ROUTE("等待路径", "#FF9800"),
    /** 已就绪，等待出发 */
    READY("就绪", "#4CAF50"),
    /** 正在移动中 */
    MOVING("移动中", "#2196F3"),
    /** 路径被阻塞 */
    BLOCKED("受阻", "#F44336");

    /** 状态的中文名称 */
    private final String chineseName;
    /** 状态对应的前端展示颜色（十六进制） */
    private final String color;

    CarStatus(String chineseName, String color) {
        this.chineseName = chineseName;
        this.color = color;
    }

    /** 获取状态的中文名称 */
    public String chineseName() {
        return chineseName;
    }

    /** 获取状态对应的前端展示颜色 */
    public String color() {
        return color;
    }
}
