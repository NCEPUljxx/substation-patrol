package com.substation.controller;

import com.substation.common.DynamicObstacleUtil;
import com.substation.common.model.AlgorithmType;
import com.substation.common.model.CarStatus;
import com.substation.common.model.Point;
import com.substation.common.mq.MessageBuilder;
import com.substation.common.mq.MessageTypes;
import com.substation.common.mq.MessageBus;
import com.substation.common.mq.QueueNames;
import com.substation.common.redis.BlackboardClient;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 状态分派器：每节拍发现车辆、按状态分发处理、发送移动指令、广播刷新 */
public class StatusDispatcher {

    /** 探索完成阈值（百分比），与前端「100% 任务完成」一致 */
    private static final int EXPLORATION_COMPLETE = 100;
    /** 所有车 IDLE 且无事可做的连续 tick 数，超时强制完成 */
    private static final int ALL_IDLE_COMPLETE_TICKS = 30;
    /** 阻塞随机超时范围（打破死锁） */
    private static final int BLOCKED_TIMEOUT_MIN = 2;
    private static final int BLOCKED_TIMEOUT_MAX = 5;
    /** 移动卡住节拍数，连续MOVING超此时长则强制切为READY */
    private static final int MOVING_STUCK_TICKS = 2;
    /** 等待路径超时节拍数，超时后移除锁并退回IDLE重新分配 */
    private static final int WAITING_ROUTE_TIMEOUT_TICKS = 5;
    /** 全局探索率超此阈值则跳过策略监督 */
    private static final int SUPERVISE_RATE_THRESHOLD = 85;
    /** 同一辆车两次监督间的最小tick间隔 */
    private static final int SUPERVISE_COOLDOWN_TICKS = 15;
    /** 消息字段名：车辆ID */
    private static final String FIELD_CAR_ID = "carId";
    /** 消息字段名：路径起点 */
    private static final String FIELD_SUB_START = "start";
    /** 消息字段名：路径终点 */
    private static final String FIELD_SUB_TARGET = "target";
    /** 消息字段名：寻路算法 */
    private static final String FIELD_ALGORITHM = "algorithm";
    /** 消息字段名：是否由监督器触发 */
    private static final String FIELD_SUPERVISED = "supervised";
    /** 消息字段名：当前节拍号 */
    private static final String FIELD_TICK = "tick";
    /** 消息字段名：任务耗时（秒） */
    private static final String FIELD_ELAPSED_SECONDS = "elapsedSeconds";

    /** 黑板客户端（Redis 共享状态） */
    private final BlackboardClient bb;
    /** 消息总线（RabbitMQ） */
    private final MessageBus bus;
    /** 待响应的目标分配请求集合（线程安全） */
    private final Set<String> pendingTargetRequests;
    /** 待响应的路径规划请求集合（线程安全），防止同车重复发 PLAN_ROUTE */
    private final Set<String> pendingPlanRequests;
    /** 车辆连续处于MOVING状态的节拍计数 */
    private final Map<String, Integer> movingTickCounts;
    /** 车辆连续处于WAITING_ROUTE状态的节拍计数（超时兜底） */
    private final Map<String, Integer> waitingRouteTickCounts;
    /** 已发送TICK_MOVE等待车响应的集合（线程安全），防跳格 */
    private final Set<String> pendingMoveRequests;
    /** 每车上次被监督的tick号（用于冷却控制） */
    private final Map<String, Integer> lastSupervisedTick;
    /** 被监督器标记需优化的车辆集合 */
    private final Set<String> supervisedFlags;
    /** 已发监督请求、等待监督结果后再切 READY 的车辆 */
    private final Set<String> awaitingSupervision;
    /** 已声明 MQ 队列的小车，避免重复 declare */
    private final Set<String> declaredCarQueues = ConcurrentHashMap.newKeySet();
    /** 随机数生成器 */
    private final Random random = new Random();
    /** 每车随机阻塞超时阈值（打破多车互堵死锁） */
    private final Map<String, Integer> blockedTimeoutTicks;
    /** 当前节拍号 */
    private volatile int tick;
    /** 任务是否活跃 */
    private volatile boolean taskActive;
    /** 动态障碍物生成间隔（每N个tick触发一次） */
    private static final int DYNAMIC_OBSTACLE_INTERVAL = 20;
    /** 连续所有车 IDLE 且无 pending 请求的 tick 数 */
    private int allIdleTicks;
    /** 任务开始时间戳（毫秒） */
    private volatile long taskStartTime;
    /** 动态障碍物工具 */
    private final DynamicObstacleUtil dynamicObstacleUtil = new DynamicObstacleUtil();

    /** 创建状态分派器，初始化并发集合 */
    public StatusDispatcher(BlackboardClient bb, MessageBus bus) {
        this.bb = bb;
        this.bus = bus;
        this.pendingTargetRequests = ConcurrentHashMap.newKeySet();
        this.pendingPlanRequests = ConcurrentHashMap.newKeySet();
        this.movingTickCounts = new ConcurrentHashMap<>();
        this.waitingRouteTickCounts = new ConcurrentHashMap<>();
        this.pendingMoveRequests = ConcurrentHashMap.newKeySet();
        this.lastSupervisedTick = new ConcurrentHashMap<>();
        this.supervisedFlags = ConcurrentHashMap.newKeySet();
        this.awaitingSupervision = ConcurrentHashMap.newKeySet();
        this.blockedTimeoutTicks = new ConcurrentHashMap<>();
    }

    // ==================== dispatch ====================

    /** 执行一次节拍调度：发现车辆 → 按状态分发 → 发送移动指令 → 广播刷新 */
    public void dispatch() {
        if (!taskActive) {
            return;
        }
        tick++;

        if (isExplorationComplete()) {
            completeTask();
            return;
        }

        Set<String> carIds = bb.discoverCarIds();
        if (carIds.isEmpty()) {
            return;
        }

        ensureCarQueuesDeclared(carIds);

        for (String carId : carIds) {
            bb.getCarStatus(carId).ifPresent(status -> dispatchCar(carId, status));
        }

        // 兜底：Navigator 已把 route 写入 Redis 但 ROUTE_PLANNED 消息丢失 → 直接切 READY
        for (String carId : carIds) {
            if (pendingPlanRequests.contains(carId) && !bb.getCarRoute(carId).isEmpty()) {
                pendingPlanRequests.remove(carId);
                waitingRouteTickCounts.remove(carId);
                bb.setCarStatus(carId, CarStatus.READY);
            }
        }

        int movesSent = sendReadyCarMoves(carIds);
        if (movesSent == 0) {
            broadcastRefresh();
        }

        // 辅助判定：全部车 IDLE 且无待处理请求持续 N tick → 强制完成
        boolean allIdle = carIds.stream().allMatch(cid ->
            bb.getCarStatus(cid).orElse(null) == CarStatus.IDLE);
        if (allIdle && pendingTargetRequests.isEmpty() && pendingPlanRequests.isEmpty()) {
            allIdleTicks++;
            if (allIdleTicks >= ALL_IDLE_COMPLETE_TICKS && isExplorationComplete()) {
                completeTask();
                return;
            }
        } else {
            allIdleTicks = 0;
        }

        if (taskActive && isExplorationComplete()) {
            completeTask();
        }
    }

    // ==================== callbacks for CommandHandler ====================

    /** 目标分配结果回调：成功则车辆进入等待路径状态，失败则移除待响应记录 */
    public void onTargetAssigned(String carId, boolean success) {
        pendingTargetRequests.remove(carId);
        if (!taskActive) {
            return;
        }
        if (success) {
            if (isExplorationComplete()) {
                completeTask();
                return;
            }
            bb.setCarStatus(carId, CarStatus.WAITING_ROUTE);
            return;
        }
        tryCompleteIfExplorationDone();
    }

    /** 路径规划结果回调：成功则车辆就绪等待移动并触发策略监督，失败则回到待机状态 */
    public void onRoutePlanned(String carId, boolean routeFound) {
        pendingPlanRequests.remove(carId);
        if (!taskActive) {
            return;
        }
        if (routeFound) {
            if (isExplorationComplete()) {
                completeTask();
                return;
            }
            if (shouldSupervise(carId)) {
                lastSupervisedTick.put(carId, tick);
                awaitingSupervision.add(carId);
                bb.setCarStatus(carId, CarStatus.READY);
                sendSuperviseRoute(carId);
                return;
            }
            bb.setCarStatus(carId, CarStatus.READY);
        } else {
            bb.clearCarTarget(carId);
            bb.setCarStatus(carId, CarStatus.IDLE);
            tryCompleteIfExplorationDone();
        }
    }

    /** 监督器完成（含跳过优化），将车辆切为 READY */
    public void onRouteSupervisionFinished(String carId) {
        awaitingSupervision.remove(carId);
        if (!taskActive) {
            return;
        }
        if (isExplorationComplete()) {
            completeTask();
            return;
        }
        bb.setCarStatus(carId, CarStatus.READY);
    }

    /** 监督器判定路线重合，请求清除状态并重新分配目标 */
    public void onRouteOverlapReassign(String carId) {
        awaitingSupervision.remove(carId);
        bb.clearRoute(carId);
        bb.clearCarTarget(carId);
        bb.setCarStatus(carId, CarStatus.IDLE);
        pendingPlanRequests.remove(carId);
        pendingMoveRequests.remove(carId);
        waitingRouteTickCounts.remove(carId);
        movingTickCounts.remove(carId);
    }

    /** 判断是否应对该车触发策略监督：全局探索率<85% 且冷却已过 */
    private boolean shouldSupervise(String carId) {
        if (bb.getExplorationRate() >= SUPERVISE_RATE_THRESHOLD) {
            return false;
        }
        Integer last = lastSupervisedTick.get(carId);
        return last == null || tick - last >= SUPERVISE_COOLDOWN_TICKS;
    }

    /** 车辆移动完成回调：清除 TICK_MOVE 发送标记，全部车到位后刷新 Display */
    public void onMoveAcknowledged(String carId) {
        if (!taskActive) {
            return;
        }
        pendingMoveRequests.remove(carId);
        if (pendingMoveRequests.isEmpty()) {
            broadcastRefresh();
        }
        tryCompleteIfExplorationDone();
    }

    /** 监督器建议优化路线，标记该车 */
    public void markSupervised(String carId) {
        supervisedFlags.add(carId);
    }

    /** 切换指定格子的障碍物状态（右键菜单触发） */
    public void toggleObstacle(int row, int col) {
        boolean current = bb.isBlocked(row, col);
        bb.setBlock(row, col, !current);
        System.out.println("[Controller] 障碍物 " + (!current ? "新增" : "移除") + "(" + col + "," + row + ")");
    }

    /** SET_CONFIG 到达、TASK_READY 之前：停止调度并清空 pending，避免沿用上轮状态 */
    public void prepareForNewConfig() {
        taskActive = false;
        clearPendingState();
    }

    /** 任务就绪回调：激活调度、记录开始时间、重置节拍计数与待响应状态 */
    public void onTaskReady() {
        clearPendingState();
        taskActive = true;
        taskStartTime = System.currentTimeMillis();
        tick = 0;
        allIdleTicks = 0;
        declareCarQueues();
        applyTickIntervalFromConfig();
        Set<String> carIds = bb.discoverCarIds();
        System.out.println("[Controller] 任务就绪，黑板车辆: " + carIds
            + "，节拍间隔=" + bb.getTickInterval() + "ms");
        if (carIds.isEmpty()) {
            System.out.println("[Controller] 警告: 黑板上无车辆，请确认 TaskConfigurator 已初始化且小车进程已启动");
        }
        if (bb.isExplorationComplete()) {
            System.err.println("[Controller] 警告: 黑板已无未探索格，请确认 TaskConfigurator 已启动并执行了 flushDB");
        }
        broadcastRefresh();
    }

    /** 清空上一轮任务残留的待响应集合，避免阻塞 ASSIGN_TARGET / PLAN_ROUTE */
    public void clearPendingState() {
        pendingTargetRequests.clear();
        pendingPlanRequests.clear();
        movingTickCounts.clear();
        waitingRouteTickCounts.clear();
        pendingMoveRequests.clear();
        lastSupervisedTick.clear();
        supervisedFlags.clear();
        awaitingSupervision.clear();
        blockedTimeoutTicks.clear();
        declaredCarQueues.clear();
    }

    private void ensureCarQueuesDeclared(Set<String> carIds) {
        for (String carId : carIds) {
            if (!declaredCarQueues.add(carId)) {
                continue;
            }
            try {
                bus.declareCarQueue(carId);
            } catch (IOException e) {
                declaredCarQueues.remove(carId);
                System.err.println("[Controller] 声明小车队列失败 " + carId + ": " + e.getMessage());
            }
        }
    }

    private void declareCarQueues() {
        ensureCarQueuesDeclared(bb.discoverCarIds());
    }

    private void applyTickIntervalFromConfig() {
        if (scheduler == null) {
            return;
        }
        int intervalMs = bb.getTickInterval();
        if (intervalMs >= 100) {
            scheduler.setInterval(intervalMs);
        }
    }

    /** 持久化节拍间隔并立即广播，使所有观众页滑块同步 */
    public void applyTickInterval(int intervalMs) {
        bb.setTickInterval(intervalMs);
        broadcastRefresh();
    }

    /** 转发配置消息到任务配置队列 */
    public void forwardConfig(Map<String, Object> config) {
        try {
            String msg = MessageBuilder.build(MessageTypes.FORWARD_CONFIG, tick, null, config);
            bus.publish(QueueNames.TASK_CONFIG_CMD, msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 查询调度是否活跃（reset 窗口返回 false，阻断旧消息） */
    public boolean isActive() {
        return taskActive;
    }

    /** 绑定 TickScheduler，供重置时停止节拍 */
    private TickScheduler scheduler;

    public void setScheduler(TickScheduler scheduler) {
        this.scheduler = scheduler;
    }

    /** 转发重置消息并清理本地状态（待响应请求、移动计数、任务活跃标志） */
    public void forwardReset() {
        System.out.println("[Controller] forwardReset called, taskActive=" + taskActive + " tick=" + tick);
        if (scheduler != null) {
            scheduler.stop();
            scheduler.resetPaused();
            System.out.println("[Controller] scheduler stopped, paused=" + scheduler.isPaused());
        }
        try {
            String msg = MessageBuilder.build(MessageTypes.FORWARD_RESET, tick);
            bus.publish(QueueNames.TASK_CONFIG_CMD, msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
        clearPendingState();
        taskActive = false;
    }

    // ==================== private dispatch helpers ====================

    /** 按车辆当前状态分派处理：IDLE分配目标、WAITING_ROUTE规划路径、MOVING检测卡住、BLOCKED检测超时 */
    private void dispatchCar(String carId, CarStatus status) {
        if (status != CarStatus.MOVING) {
            movingTickCounts.remove(carId);
        }
        if (status != CarStatus.WAITING_ROUTE) {
            waitingRouteTickCounts.remove(carId);
        }
        if (status != CarStatus.READY && status != CarStatus.MOVING) {
            pendingMoveRequests.remove(carId);
        }
        switch (status) {
            case IDLE -> {
                if (!isExplorationComplete()) {
                    sendAssignTarget(carId);
                }
            }
            case WAITING_ROUTE -> checkAndPlanRoute(carId);
            case MOVING -> checkMovingStuck(carId);
            case BLOCKED -> checkBlockedTimeout(carId);
            case READY -> {} // 在第二轮循环中统一处理
        }
    }

    /** 发送目标分配请求到目标规划器 */
    private void sendAssignTarget(String carId) {
        if (!pendingTargetRequests.add(carId)) {
            return;
        }
        Map<String, Object> data = Map.of(FIELD_CAR_ID, carId);
        try {
            String msg = MessageBuilder.build(MessageTypes.ASSIGN_TARGET, tick, carId, data);
            bus.publish(QueueNames.TARGET_PLANNER_CMD, msg);
            System.out.println("[Controller] 发送 ASSIGN_TARGET carId=" + carId + " tick=" + tick);
        } catch (Exception e) {
            pendingTargetRequests.remove(carId);
            e.printStackTrace();
        }
    }

    /** 发送路径规划请求到导航器，带缺失保护与超时兜底 */
    private void checkAndPlanRoute(String carId) {
        if (awaitingSupervision.contains(carId)) {
            return;
        }
        if (!bb.getCarRoute(carId).isEmpty()) {
            waitingRouteTickCounts.remove(carId);
            pendingPlanRequests.remove(carId);
            bb.setCarStatus(carId, CarStatus.READY);
            return;
        }

        int waitingTicks = waitingRouteTickCounts.getOrDefault(carId, 0) + 1;
        waitingRouteTickCounts.put(carId, waitingTicks);

        if (waitingTicks >= WAITING_ROUTE_TIMEOUT_TICKS) {
            waitingRouteTickCounts.remove(carId);
            pendingPlanRequests.remove(carId);
            bb.clearCarTarget(carId);
            bb.setCarStatus(carId, CarStatus.IDLE);
            return;
        }

        if (!pendingPlanRequests.add(carId)) {
            return;
        }

        var targetOpt = bb.getCarTarget(carId);
        var posOpt = bb.getCarPosition(carId);
        if (targetOpt.isEmpty() || posOpt.isEmpty()) {
            pendingPlanRequests.remove(carId);
            bb.setCarStatus(carId, CarStatus.IDLE);
            return;
        }

        Point target = targetOpt.get();
        Point pos = posOpt.get();
        String algorithm = bb.getAlgorithm();
        boolean supervised = supervisedFlags.remove(carId);
        Map<String, Object> data = Map.of(
            FIELD_CAR_ID, carId,
            FIELD_SUB_START, Map.of("x", pos.x(), "y", pos.y()),
            FIELD_SUB_TARGET, Map.of("x", target.x(), "y", target.y()),
            FIELD_ALGORITHM, algorithm != null ? algorithm : AlgorithmType.BFS.name(),
            FIELD_SUPERVISED, supervised
        );
        try {
            String msg = MessageBuilder.build(MessageTypes.PLAN_ROUTE, tick, carId, data);
            bus.publish(QueueNames.NAVIGATOR_CMD, msg);
            System.out.println("[Controller] 发送 PLAN_ROUTE carId=" + carId + " tick=" + tick);
        } catch (Exception e) {
            pendingPlanRequests.remove(carId);
            bb.setCarStatus(carId, CarStatus.IDLE);
            e.printStackTrace();
        }
    }

    /** 检测车辆移动卡住：连续MOVING超过阈值则强制切为READY以重新触发移动 */
    private void checkMovingStuck(String carId) {
        int count = movingTickCounts.getOrDefault(carId, 0) + 1;
        if (count >= MOVING_STUCK_TICKS) {
            movingTickCounts.remove(carId);
            bb.setCarStatus(carId, CarStatus.READY);
        } else {
            movingTickCounts.put(carId, count);
        }
    }

    /** 检测阻塞超时：随机阈值打破死锁，超时后清除路径与目标 */
    private void checkBlockedTimeout(String carId) {
        int threshold = blockedTimeoutTicks.computeIfAbsent(carId,
            k -> BLOCKED_TIMEOUT_MIN + random.nextInt(BLOCKED_TIMEOUT_MAX - BLOCKED_TIMEOUT_MIN + 1));
        if (tick - bb.getBlockedTick(carId) >= threshold) {
            bb.clearRoute(carId);
            bb.clearCarTarget(carId);
            bb.clearBlockedTick(carId);
            bb.setCarStatus(carId, CarStatus.IDLE);
            blockedTimeoutTicks.remove(carId);
            sendBlockedTimeout(carId);
        }
    }

    /** 发送路线优化请求到策略监督器 */
    private void sendSuperviseRoute(String carId) {
        Map<String, Object> data = Map.of(FIELD_CAR_ID, carId);
        try {
            String msg = MessageBuilder.build(MessageTypes.SUPERVISE_ROUTE, tick, carId, data);
            bus.publish(QueueNames.STRATEGY_SUPERVISOR_CMD, msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 对本 tick 所有 READY 车辆发送移动指令，返回实际发出的数量 */
    private int sendReadyCarMoves(Set<String> carIds) {
        int sent = 0;
        for (String carId : carIds) {
            if (!isReadyToMove(carId)) {
                continue;
            }
            if (trySendTickMove(carId)) {
                sent++;
            }
        }
        return sent;
    }

    private boolean isReadyToMove(String carId) {
        if (awaitingSupervision.contains(carId)) {
            return false;
        }
        return bb.getCarStatus(carId).orElse(CarStatus.IDLE) == CarStatus.READY;
    }

    /** 发送移动指令给指定车辆；已在等待 ack 时返回 false */
    private boolean trySendTickMove(String carId) {
        if (!pendingMoveRequests.add(carId)) {
            return false;
        }
        Map<String, Object> data = Map.of(FIELD_TICK, tick);
        try {
            String msg = MessageBuilder.build(MessageTypes.TICK_MOVE, tick, carId, data);
            bus.publish(QueueNames.carQueue(carId), msg);
            return true;
        } catch (Exception e) {
            pendingMoveRequests.remove(carId);
            e.printStackTrace();
            return false;
        }
    }

    /** 发送阻塞超时通知给指定车辆 */
    private void sendBlockedTimeout(String carId) {
        Map<String, Object> data = Map.of(FIELD_CAR_ID, carId);
        try {
            String msg = MessageBuilder.build(MessageTypes.BLOCKED_TIMEOUT, tick, carId, data);
            bus.publish(QueueNames.carQueue(carId), msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 广播探索进度刷新消息给前端（fanout） */
    private void broadcastRefresh() {
        if (!taskActive) {
            return;
        }
        int rate = bb.getExplorationRate();
        if (bb.isExplorationComplete()) {
            rate = EXPLORATION_COMPLETE;
        }
        Map<String, Object> data = Map.of("explorationRate", rate);
        try {
            String msg = MessageBuilder.build(MessageTypes.REFRESH_ALL, tick, null, data);
            bus.publishFanout(QueueNames.UPDATE_VIEW_EXCHANGE, msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 完成任务：停止 tick、冻结车辆、广播最终状态 */
    private void completeTask() {
        if (!taskActive) {
            return;
        }
        if (scheduler != null) {
            scheduler.stop();
        }
        taskActive = false;
        freezeAllCars();
        clearPendingState();
        bb.setTaskActive(false);
        long elapsed = (System.currentTimeMillis() - taskStartTime) / 1000;
        bb.setElapsedSeconds(elapsed);
        int rate = bb.isExplorationComplete() ? EXPLORATION_COMPLETE : bb.getExplorationRate();
        System.out.println("[Controller] 探索完成，任务结束 tick=" + tick
            + " 探索率=" + rate + "% 耗时=" + elapsed + "s");
        Map<String, Object> data = Map.of("explorationRate", rate);
        try {
            String msg = MessageBuilder.build(MessageTypes.REFRESH_ALL, tick, null, data);
            bus.publishFanout(QueueNames.UPDATE_VIEW_EXCHANGE, msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void freezeAllCars() {
        for (String carId : bb.discoverCarIds()) {
            bb.clearRoute(carId);
            bb.clearCarTarget(carId);
            bb.clearBlockedTick(carId);
            bb.setCarStatus(carId, CarStatus.IDLE);
        }
    }

    private boolean isExplorationComplete() {
        return bb.isExplorationComplete();
    }

    private void tryCompleteIfExplorationDone() {
        if (isExplorationComplete()) {
            completeTask();
        }
    }
}
