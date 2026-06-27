package com.substation.common.mq;

/**
 * 消息类型常量，定义系统中所有MQ消息的type字段取值。
 * 消息类型决定了消息的语义和处理流程。
 */
public final class MessageTypes {

    /** 工具类，禁止实例化 */
    private MessageTypes() {}

    /** 控制器请求给小车分配目标点 */
    public static final String ASSIGN_TARGET = "ASSIGN_TARGET";
    /** 目标规划器返回分配结果（给小车） */
    public static final String TARGET_ASSIGNED = "TARGET_ASSIGNED";
    /** 控制器请求小车规划路径 */
    public static final String PLAN_ROUTE = "PLAN_ROUTE";
    /** 导航器返回规划完成的路径（给小车） */
    public static final String ROUTE_PLANNED = "ROUTE_PLANNED";
    /** 控制器触发所有小车执行一步移动 */
    public static final String TICK_MOVE = "TICK_MOVE";
    /** 小车返回移动结果（步数、位置等） */
    public static final String MOVED = "MOVED";
    /** 小车确认路径走完 */
    public static final String ROUTE_DONE = "ROUTE_DONE";
    /** 小车报告被障碍物阻塞 */
    public static final String BLOCKED = "BLOCKED";
    /** 控制器判定阻塞超时，下发重新规划指令 */
    public static final String BLOCKED_TIMEOUT = "BLOCKED_TIMEOUT";
    /** 请求UI刷新地图、状态等全部视图 */
    public static final String REFRESH_ALL = "REFRESH_ALL";
    /** 前端下发任务参数配置 */
    public static final String SET_CONFIG = "SET_CONFIG";
    /** 控制器收到配置后转发给目标规划器和导航器 */
    public static final String FORWARD_CONFIG = "FORWARD_CONFIG";
    /** 前端下注重置指令 */
    public static final String RESET = "RESET";
    /** 控制器收到重置后转发给目标规划器和导航器 */
    public static final String FORWARD_RESET = "FORWARD_RESET";
    /** 控制器通知前端任务准备就绪 */
    public static final String TASK_READY = "TASK_READY";
    /** 前端请求切换暂停/继续状态 */
    public static final String TOGGLE_PAUSE = "TOGGLE_PAUSE";
    /** 前端请求修改tick间隔时间 */
    public static final String SET_TICK_INTERVAL = "SET_TICK_INTERVAL";
    /** 策略监督：Controller请求审查路线优化 */
    public static final String SUPERVISE_ROUTE = "SUPERVISE_ROUTE";
    /** 策略监督：路线优化结果回复 */
    public static final String ROUTE_OPTIMIZED = "ROUTE_OPTIMIZED";
}
