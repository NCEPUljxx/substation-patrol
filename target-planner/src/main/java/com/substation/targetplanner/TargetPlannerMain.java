package com.substation.targetplanner;

import com.alibaba.fastjson2.JSONObject;
import com.substation.common.model.Point;
import com.substation.common.mq.MessageBuilder;
import com.substation.common.mq.MessageBus;
import com.substation.common.mq.MessageTypes;
import com.substation.common.mq.QueueNames;
import com.substation.common.redis.BlackboardClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;

public class TargetPlannerMain {

    private static final Logger log = LoggerFactory.getLogger(TargetPlannerMain.class);
    private static final int INITIAL_W = BlackboardClient.DEFAULT_WIDTH;
    private static final int INITIAL_H = BlackboardClient.DEFAULT_HEIGHT;
    private static final String MQ_USER = "guest";
    private static final String MQ_PASS = "guest";

    private final String redisHost;
    private final int redisPort;
    private final String mqHost;
    private final int mqPort;
    private BlackboardClient bb;
    private MessageBus messageBus;

    /** 同一 tick 内已分配的目标集合，防止多车被分到同一格子 */
    private final Set<Point> allocatedTargets = new HashSet<>();
    /** 当前处理中的 tick 号 */
    private int currentTick = -1;

    /** 供 Launcher 调用的构造函数 */
    public TargetPlannerMain(String redisHost, int redisPort, String mqHost, int mqPort) {
        this.redisHost = redisHost;
        this.redisPort = redisPort;
        this.mqHost = mqHost;
        this.mqPort = mqPort;
    }

    /** 启动目标分配服务：连接中间件、声明队列、订阅 ASSIGN_TARGET。返回不阻塞 */
    public void start() throws IOException, TimeoutException {
        bb = new BlackboardClient(redisHost, redisPort, INITIAL_W, INITIAL_H);
        messageBus = new MessageBus(mqHost, mqPort, MQ_USER, MQ_PASS);
        messageBus.connect();
        messageBus.declareTargetPlannerQueue();

        GreedyTargetAllocator allocator = new GreedyTargetAllocator();
        log.info("[TargetPlanner] 启动完成，等待目标分配命令...");

        messageBus.subscribe(QueueNames.TARGET_PLANNER_CMD, rawMessage -> {
            JSONObject msg = JSONObject.parseObject(rawMessage);
            String type = msg.getString("type");
            int tick = msg.getIntValue("tick", 0);

            if (!MessageTypes.ASSIGN_TARGET.equals(type)) {
                log.warn("[TargetPlanner] 未知消息类型: {}", type);
                return;
            }

            String carId = msg.getString("carId");
            log.info("[TargetPlanner] 收到 ASSIGN_TARGET carId={} tick={}", carId, tick);

            try {
                handleAssignTarget(allocator, carId, tick);
            } catch (Exception e) {
                log.error("[TargetPlanner] 分配失败 carId={}", carId, e);
                sendFailureReply(carId, tick);
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("[TargetPlanner] 关闭中...");
            if (messageBus != null) messageBus.close();
            if (bb != null) bb.close();
        }));
    }

    /** 独立运行入口 */
    public static void main(String[] args) throws IOException, TimeoutException {
        var infra = com.substation.common.infra.InfraConnectionConfig.resolve(args);
        new TargetPlannerMain(
                infra.redisHost(), infra.redisPort(), infra.mqHost(), infra.mqPort()).start();
        synchronized (TargetPlannerMain.class) {
            try { TargetPlannerMain.class.wait(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    // ==================== 消息处理 ====================

    private void handleAssignTarget(GreedyTargetAllocator allocator, String carId,
                                     int tick) throws IOException {
        if (tick != currentTick) {
            allocatedTargets.clear();
            currentTick = tick;
        }

        Optional<Point> posOpt = bb.getCarPosition(carId);
        if (posOpt.isEmpty()) {
            log.warn("[TargetPlanner] carId={} 位置不存在，跳过分配", carId);
            sendFailureReply(carId, tick);
            return;
        }

        Point currentPos = posOpt.get();
        Optional<Point> target = allocator.allocate(carId, currentPos, bb, allocatedTargets);

        if (target.isPresent()) {
            bb.setCarTarget(carId, target.get());
            log.info("[TargetPlanner] carId={} → 目标({},{})", carId,
                target.get().x(), target.get().y());
            sendSuccessReply(carId, target.get(), tick);
        } else {
            log.info("[TargetPlanner] carId={} 暂无可分配目标", carId);
            sendFailureReply(carId, tick);
        }
    }

    private void sendSuccessReply(String carId, Point target, int tick) throws IOException {
        Map<String, Object> data = Map.of(
            "carId", carId,
            "success", true,
            "target", Map.of("x", target.x(), "y", target.y())
        );
        String reply = MessageBuilder.build(MessageTypes.TARGET_ASSIGNED, tick, carId, data);
        messageBus.publish(QueueNames.CONTROLLER_CMD, reply);
    }

    private void sendFailureReply(String carId, int tick) {
        try {
            Map<String, Object> data = Map.of("carId", carId, "success", false);
            String reply = MessageBuilder.build(MessageTypes.TARGET_ASSIGNED, tick, carId, data);
            messageBus.publish(QueueNames.CONTROLLER_CMD, reply);
        } catch (IOException e) {
            log.error("[TargetPlanner] 发送失败回复出错", e);
        }
    }
}
