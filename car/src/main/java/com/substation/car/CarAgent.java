package com.substation.car;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import com.substation.common.model.CarStatus;
import com.substation.common.mq.MessageTypes;
import com.substation.common.redis.BlackboardClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 小车代理 —— 消息分发与状态机入口。
 *
 * <p>负责：
 * <ul>
 *   <li>接收 Car_CarID 队列的消息</li>
 *   <li>TICK_MOVE → 调用 MoveExecutor 执行移动</li>
 *   <li>BLOCKED_TIMEOUT → 检查 Controller 清理状态</li>
 *   <li>非法消息 → 日志警告并丢弃</li>
 * </ul>
 */
public class CarAgent {

    private static final Logger log = LoggerFactory.getLogger(CarAgent.class);

    private final String carId;
    private final BlackboardClient bb;
    private final MoveExecutor moveExecutor;

    public CarAgent(String carId, BlackboardClient bb, MoveExecutor moveExecutor) {
        this.carId = carId;
        this.bb = bb;
        this.moveExecutor = moveExecutor;
    }

    /**
     * 消息分发入口。由 MessageBus 回调调用。
     */
    public void handleMessage(String json) {
        try {
            JSONObject msg = JSON.parseObject(json);
            String type = msg.getString("type");
            int tick = msg.getIntValue("tick");

            log.debug("[{}] 收到消息 type={}, tick={}", carId, type, tick);

            if (MessageTypes.TICK_MOVE.equals(type)) {
                moveExecutor.executeMove(tick);
            } else if (MessageTypes.BLOCKED_TIMEOUT.equals(type)) {
                handleBlockedTimeout();
            } else {
                log.warn("[{}] 收到未知消息类型: {}", carId, type);
            }
        } catch (JSONException e) {
            log.warn("[{}] 收到非法 JSON 消息: {} — {}", carId, json, e.getMessage());
        }
    }

    /**
     * 处理 BLOCKED_TIMEOUT：
     * <ol>
     *   <li>日志 WARN 记录</li>
     *   <li>从 Redis 读取自身 Status，确认 Controller 已完成清理</li>
     *   <li>不做任何黑板写入</li>
     * </ol>
     */
    private void handleBlockedTimeout() {
        log.warn("[{}] 收到 BLOCKED_TIMEOUT，阻塞超时已由 Controller 处理", carId);
        Optional<CarStatus> status = bb.getCarStatus(carId);
        status.ifPresentOrElse(
                s -> log.info("[{}] 当前状态: {}，等待重新分配目标", carId, s.chineseName()),
                () -> log.warn("[{}] 状态 Key 不存在", carId));
    }
}
