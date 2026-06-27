package com.substation.controller;

import com.alibaba.fastjson2.JSONObject;
import com.substation.common.mq.MessageBus;
import com.substation.common.mq.MessageTypes;

import java.util.Map;

/** 命令处理器：解析入站消息并按消息类型路由到调度器/分派器/配置模块 */
public class CommandHandler {

    /** 最小时调节拍间隔（毫秒），防止过于频繁的调度 */
    private static final int MIN_TICK_INTERVAL_MS = 100;
    /** 最大时调节拍间隔（毫秒），防止间隔过大导致响应迟钝 */
    private static final int MAX_TICK_INTERVAL_MS = 2000;

    /** 消息总线 */
    private final MessageBus bus;
    /** 状态分派器 */
    private final StatusDispatcher dispatcher;
    /** 节拍调度器 */
    private final TickScheduler scheduler;

    /** 创建命令处理器，绑定消息总线、状态分派器和节拍调度器 */
    public CommandHandler(MessageBus bus, StatusDispatcher dispatcher, TickScheduler scheduler) {
        this.bus = bus;
        this.dispatcher = dispatcher;
        this.scheduler = scheduler;
    }

    /** 处理入站消息：解析JSON并按消息类型分发到对应组件 */
    public void handle(String raw) {
        try {
            JSONObject msg = JSONObject.parse(raw);
            String type = msg.getString("type");
            String carId = msg.getString("carId");
            JSONObject data = msg.getJSONObject("data");

            switch (type) {
                case MessageTypes.TASK_READY -> {
                    System.out.println("[Controller] 收到 TASK_READY，启动调度");
                    dispatcher.onTaskReady();
                    scheduler.stop();
                    scheduler.resetPaused();
                    scheduler.start();
                }
                case MessageTypes.TARGET_ASSIGNED -> {
                    if (scheduler.isPaused() || !dispatcher.isActive()) break;
                    boolean success = data != null && data.getBooleanValue("success", false);
                    dispatcher.onTargetAssigned(carId, success);
                }
                case MessageTypes.ROUTE_PLANNED -> {
                    if (scheduler.isPaused() || !dispatcher.isActive()) break;
                    boolean routeFound = data != null && data.getBooleanValue("routeFound", false);
                    dispatcher.onRoutePlanned(carId, routeFound);
                }
                case MessageTypes.MOVED, MessageTypes.ROUTE_DONE -> {
                    if (carId != null) dispatcher.onMoveAcknowledged(carId);
                }
                case MessageTypes.BLOCKED -> {
                    if (carId != null) {
                        dispatcher.onMoveAcknowledged(carId);
                    }
                }
                case MessageTypes.ROUTE_OPTIMIZED -> {
                    if (scheduler.isPaused() || !dispatcher.isActive()) {
                        break;
                    }
                    if (carId == null) {
                        break;
                    }
                    boolean overlapReassign = data != null
                        && data.getBooleanValue("overlapReassign", false);
                    if (overlapReassign) {
                        dispatcher.onRouteOverlapReassign(carId);
                    } else {
                        if (data != null && data.getBooleanValue("optimized", false)) {
                            dispatcher.markSupervised(carId);
                        }
                        dispatcher.onRouteSupervisionFinished(carId);
                    }
                }
                case MessageTypes.SET_CONFIG -> {
                    if (data != null) {
                        scheduler.stop();
                        dispatcher.prepareForNewConfig();
                        dispatcher.forwardConfig(data);
                        System.out.println("[Controller] 收到 SET_CONFIG，等待 TaskConfigurator 初始化...");
                    }
                }
                case MessageTypes.RESET -> {
                    System.out.println("[Controller] 收到 RESET 消息");
                    dispatcher.forwardReset();
                }
                case MessageTypes.TOGGLE_PAUSE -> {
                    System.out.println("[Controller] 收到 TOGGLE_PAUSE, paused=" + scheduler.isPaused());
                    scheduler.togglePause();
                    System.out.println("[Controller] toggle后 paused=" + scheduler.isPaused());
                }
                case MessageTypes.SET_TICK_INTERVAL -> {
                    if (data != null) {
                        int interval = data.getIntValue("interval");
                        if (interval >= MIN_TICK_INTERVAL_MS && interval <= MAX_TICK_INTERVAL_MS) {
                            scheduler.setInterval(interval);
                            dispatcher.applyTickInterval(interval);
                        }
                    }
                }
                case "TOGGLE_OBSTACLE" -> {
                    if (data != null) {
                        int row = data.getIntValue("row");
                        int col = data.getIntValue("col");
                        dispatcher.toggleObstacle(row, col);
                    }
                }
                default -> System.out.println("[Controller] 未知消息类型: " + type);
            }
        } catch (Exception e) {
            System.err.println("[Controller] 消息处理异常: " + raw);
            e.printStackTrace();
        }
    }
}
