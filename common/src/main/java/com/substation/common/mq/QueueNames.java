package com.substation.common.mq;

/**
 * 队列/交换机名称常量，定义RabbitMQ中所有队列和交换机的命名。
 */
public final class QueueNames {

    /** 工具类，禁止实例化 */
    private QueueNames() {}

    /** 小车队列名前缀，完整名称为 Car_{carId} */
    public static final String CAR_PREFIX = "Car_";
    /** 导航器指令队列 */
    public static final String NAVIGATOR_CMD = "NavigatorCmd";
    /** 目标规划器指令队列 */
    public static final String TARGET_PLANNER_CMD = "TargetPlannerCmd";
    /** 任务配置指令队列 */
    public static final String TASK_CONFIG_CMD = "TaskConfigCmd";
    /** 控制器指令队列 */
    public static final String CONTROLLER_CMD = "ControllerCmd";
    /** 策略监督器指令队列 */
    public static final String STRATEGY_SUPERVISOR_CMD = "StrategySupervisorCmd";
    /** 视图更新广播交换机（Fanout） */
    public static final String UPDATE_VIEW_EXCHANGE = "UpdateView";

    /**
     * 根据小车ID生成对应的队列名称。
     *
     * @param carId 小车ID
     * @return 形如 Car_{carId} 的队列名
     */
    public static String carQueue(String carId) {
        return CAR_PREFIX + carId;
    }
}
