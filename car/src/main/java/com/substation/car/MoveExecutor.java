package com.substation.car;

import com.substation.common.model.CarStatus;
import com.substation.common.model.Point;
import com.substation.common.mq.MessageBuilder;
import com.substation.common.mq.MessageTypes;
import com.substation.common.mq.MessageBus;
import com.substation.common.mq.QueueNames;
import com.substation.common.redis.BlackboardClient;
import com.substation.common.redis.DistributedLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;

import java.util.Map;
import java.util.Optional;

/**
 * 小车移动执行器 —— 负责原子移动操作。
 *
 * <p>核心流程：
 * <ol>
 *   <li>分布式锁加锁</li>
 *   <li>检查状态是否为 READY</li>
 *   <li>状态切换为 MOVING（心跳）</li>
 *   <li>peek 下一步位置</li>
 *   <li>位置预约锁（防重叠，SET NX EX 原子）</li>
 *   <li>障碍物检查</li>
 *   <li>pop 消费该步</li>
 *   <li>清除旧位置 + 更新新位置</li>
 *   <li>新位置 mapBlock 标记</li>
 *   <li>释放位置预约锁</li>
 *   <li>点亮当前格 + 热力图 + 步数 + History</li>
 *   <li>路径状态判定（IDLE / READY）</li>
 *   <li>分布式锁释放</li>
 * </ol>
 */
public class MoveExecutor {

    private static final Logger log = LoggerFactory.getLogger(MoveExecutor.class);
    private static final int MAX_RESERVE_RETRIES = 3;

    private final String carId;
    private final BlackboardClient bb;
    private final MessageBus mb;
    private final JedisPool pool;
    /** 上次无法移动的目标格及连续失败次数（含预约失败和占据检测） */
    private int stuckX = -1;
    private int stuckY = -1;
    private int stuckCount;

    public MoveExecutor(String carId, BlackboardClient bb, MessageBus mb, JedisPool pool) {
        this.carId = carId;
        this.bb = bb;
        this.mb = mb;
        this.pool = pool;
    }

    /**
     * 执行一次节拍移动。获取锁失败时跳过本拍，等下一节拍重试。
     */
    public void executeMove(int tick) {
        DistributedLock lock = new DistributedLock(pool, carId);
        if (!lock.tryLock()) {
            log.warn("[{}] 获取分布式锁失败，跳过 tick={}", carId, tick);
            ackMoveDeferred(tick);
            return;
        }
        try {
            doMove(tick);
        } catch (Exception e) {
            log.error("[{}] 移动异常 tick={}: {}", carId, tick, e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }

    // ==================== 主流程 ====================

    private void doMove(int tick) {
        Optional<CarStatus> statusOpt = bb.getCarStatus(carId);
        if (statusOpt.isEmpty() || statusOpt.get() != CarStatus.READY) {
            return;
        }
        bb.setCarStatus(carId, CarStatus.MOVING);

        Optional<Point> nextStep = bb.peekNextRouteStep(carId);
        if (nextStep.isEmpty()) {
            log.warn("[{}] 状态为 READY 但 RouteList 为空", carId);
            bb.setCarStatus(carId, CarStatus.IDLE);
            return;
        }
        Point nextPos = nextStep.get();
        int nx = nextPos.x();
        int ny = nextPos.y();

        // 更新同位置卡住计数
        if (nx == stuckX && ny == stuckY) {
            stuckCount++;
        } else {
            stuckCount = 1;
            stuckX = nx;
            stuckY = ny;
        }

        // 位置预约锁
        if (!bb.tryReservePosition(nx, ny, carId)) {
            log.warn("[{}] tick={} 预约失败({},{}) 第{}次，退回READY",
                carId, tick, nx, ny, stuckCount);
            handleStuckRetryOrReplan(tick, nx, ny);
            return;
        }
        log.info("[{}] tick={} 预约成功({},{})", carId, tick, nx, ny);

        try {
            if (bb.isBlocked(ny, nx)) {
                log.warn("[{}] tick={} 目标({},{})是障碍物→BLOCKED", carId, tick, nx, ny);
                bb.clearRoute(carId);
                bb.clearCarTarget(carId);
                bb.setCarStatus(carId, CarStatus.BLOCKED);
                bb.setBlockedTick(carId, tick);
                stuckCount = 0;
                sendBlocked(tick, nextPos);
                ackMoveDeferred(tick);
                return;
            }
            if (isOccupiedByOtherCar(nx, ny)) {
                log.warn("[{}] tick={} 目标({},{})被占据 第{}次，退回READY",
                    carId, tick, nx, ny, stuckCount);
                handleStuckRetryOrReplan(tick, nx, ny);
                return;
            }
            executeStep(nextPos, tick);
        } finally {
            bb.releaseReservePosition(nx, ny, carId);
        }
    }

    private boolean isOccupiedByOtherCar(int x, int y) {
        for (String otherId : bb.discoverCarIds()) {
            if (otherId.equals(carId)) {
                continue;
            }
            Optional<Point> pos = bb.getCarPosition(otherId);
            if (pos.isPresent() && pos.get().x() == x && pos.get().y() == y) {
                return true;
            }
        }
        return false;
    }

    private void handleStuckRetryOrReplan(int tick, int nx, int ny) {
        if (stuckCount >= MAX_RESERVE_RETRIES) {
            log.warn("[{}] tick={} 目标({},{})连续卡住{}次，切BLOCKED",
                carId, tick, nx, ny, stuckCount);
            stuckCount = 0;
            bb.clearRoute(carId);
            bb.clearCarTarget(carId);
            bb.setCarStatus(carId, CarStatus.BLOCKED);
            bb.setBlockedTick(carId, tick);
            sendBlocked(tick, new Point(nx, ny));
        } else {
            bb.setCarStatus(carId, CarStatus.READY);
        }
        ackMoveDeferred(tick);
    }

    private void executeStep(Point nextPos, int tick) {
        stuckCount = 0;
        bb.popNextRouteStep(carId);
        bb.setCarPosition(carId, nextPos);
        if (illuminateAndHeat(nextPos, tick)) {
            bb.incrementCarEffectiveSteps(carId);
        }
        bb.incrementCarSteps(carId);
        bb.appendCarHistory(carId, nextPos, tick);
        finalizeMove(tick, nextPos);
    }

    private void finalizeMove(int tick, Point pos) {
        if (bb.getCarRoute(carId).isEmpty()) {
            bb.setCarStatus(carId, CarStatus.IDLE);
            sendRouteDone(tick, pos);
            log.info("[{}] 路径走完，最终位置({},{})，tick={}", carId, pos.x(), pos.y(), tick);
        } else {
            bb.setCarStatus(carId, CarStatus.READY);
            sendMoved(tick, pos);
            log.info("[{}] 移动到({},{})，tick={}", carId, pos.x(), pos.y(), tick);
        }
    }

    // ==================== 辅助方法 ====================

    private void handleObstacleDetected(int tick, Point blockedPos) {
        bb.clearCarTarget(carId);
        bb.setCarStatus(carId, CarStatus.IDLE);
        log.warn("[{}] 下一步位置({},{})有障碍/被占，清路径+目标回IDLE重分配，tick={}",
                carId, blockedPos.x(), blockedPos.y(), tick);
    }

    private boolean illuminateAndHeat(Point center, int tick) {
        int row = center.y();
        int col = center.x();
        boolean newlyExplored = bb.recordExploration(tick, row, col);
        bb.incrementMapHeat(row, col);
        return newlyExplored;
    }

    /** 移动失败或让出节拍时通知 Controller 释放 TICK_MOVE 槽位 */
    private void ackMoveDeferred(int tick) {
        try {
            Optional<Point> pos = bb.getCarPosition(carId);
            if (pos.isEmpty()) {
                return;
            }
            Point current = pos.get();
            Map<String, Object> data = Map.of(
                    "newPosition", Map.of("x", current.x(), "y", current.y()),
                    "deferred", true);
            String msg = MessageBuilder.build(MessageTypes.MOVED, tick, carId, data);
            mb.publish(QueueNames.CONTROLLER_CMD, msg);
        } catch (Exception e) {
            log.error("[{}] 发送移动让出确认失败", carId, e);
        }
    }

    private void sendMoved(int tick, Point newPos) {
        try {
            Map<String, Object> data = Map.of(
                    "newPosition", Map.of("x", newPos.x(), "y", newPos.y()),
                    "routeRemaining", bb.getCarRoute(carId).size());
            String msg = MessageBuilder.build(MessageTypes.MOVED, tick, carId, data);
            mb.publish(QueueNames.CONTROLLER_CMD, msg);
        } catch (Exception e) {
            log.error("[{}] 发送 MOVED 消息失败", carId, e);
        }
    }

    private void sendRouteDone(int tick, Point finalPos) {
        try {
            Map<String, Object> data = Map.of(
                    "finalPosition", Map.of("x", finalPos.x(), "y", finalPos.y()));
            String msg = MessageBuilder.build(MessageTypes.ROUTE_DONE, tick, carId, data);
            mb.publish(QueueNames.CONTROLLER_CMD, msg);
        } catch (Exception e) {
            log.error("[{}] 发送 ROUTE_DONE 消息失败", carId, e);
        }
    }

    private void sendBlocked(int tick, Point blockedPos) {
        try {
            Map<String, Object> data = Map.of(
                    "blockedPosition", Map.of("x", blockedPos.x(), "y", blockedPos.y()),
                    "blockedTick", tick);
            String msg = MessageBuilder.build(MessageTypes.BLOCKED, tick, carId, data);
            mb.publish(QueueNames.CONTROLLER_CMD, msg);
        } catch (Exception e) {
            log.error("[{}] 发送 BLOCKED 消息失败", carId, e);
        }
    }
}
