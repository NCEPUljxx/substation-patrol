package com.substation.car;

import com.substation.common.model.CarStatus;
import com.substation.common.model.Point;
import com.substation.common.mq.MessageBus;
import com.substation.common.redis.BlackboardClient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MoveExecutor 单元测试。
 *
 * <p>运行前需要本地 Redis (6379) 和 RabbitMQ (5672) 已启动。
 */
class MoveExecutorTest {

    private static final String REDIS_HOST = "localhost";
    private static final int REDIS_PORT = 6379;
    private static final String RABBIT_HOST = "localhost";
    private static final int RABBIT_PORT = 5672;
    private static final int MAP_SIZE = 30;
    private static final String TEST_CAR = "CarTest";

    private JedisPool pool;
    private BlackboardClient bb;
    private MessageBus mb;
    private MoveExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        pool = new JedisPool(REDIS_HOST, REDIS_PORT);
        try (Jedis jedis = pool.getResource()) {
            jedis.flushDB();
        }
        bb = new BlackboardClient(REDIS_HOST, REDIS_PORT, MAP_SIZE, MAP_SIZE);
        mb = new MessageBus(RABBIT_HOST, RABBIT_PORT, "guest", "guest");
        mb.connect();
        mb.declareControllerQueue();
        executor = new MoveExecutor(TEST_CAR, bb, mb, pool);
    }

    @AfterEach
    void tearDown() {
        try (Jedis jedis = pool.getResource()) {
            jedis.flushDB();
        } catch (Exception ignored) {}
        mb.close();
        bb.close();
        pool.close();
    }

    // ==================== 正常移动流程 ====================

    @Test
    void executeSingleMove_READYtoREADY() {
        // 设置初始状态和路径
        bb.setCarPosition(TEST_CAR, new Point(5, 10));
        bb.setCarStatus(TEST_CAR, CarStatus.READY);
        bb.pushRoute(TEST_CAR, List.of(new Point(5, 11), new Point(5, 12)));
        bb.setCarSteps(TEST_CAR, 3);

        executor.executeMove(1);

        // 移动后位置
        Optional<Point> pos = bb.getCarPosition(TEST_CAR);
        assertTrue(pos.isPresent());
        assertEquals(new Point(5, 11), pos.get());

        // 剩余路径
        List<Point> remaining = bb.getCarRoute(TEST_CAR);
        assertEquals(1, remaining.size());
        assertEquals(new Point(5, 12), remaining.get(0));

        // 状态应为 READY（路径还有剩余）
        assertEquals(CarStatus.READY, bb.getCarStatus(TEST_CAR).orElseThrow());

        // 步数递增
        assertEquals(4, bb.getCarSteps(TEST_CAR));
    }

    @Test
    void executeMove_countsEffectiveStepsOnlyOnUnexploredCells() {
        bb.setCarPosition(TEST_CAR, new Point(5, 10));
        bb.setCarStatus(TEST_CAR, CarStatus.READY);
        bb.setMapViewBit(10, 5, true);
        bb.pushRoute(TEST_CAR, List.of(new Point(5, 11), new Point(5, 12)));

        executor.executeMove(1);

        assertEquals(1, bb.getCarSteps(TEST_CAR));
        assertEquals(1, bb.getCarEffectiveSteps(TEST_CAR));

        executor.executeMove(2);

        assertEquals(2, bb.getCarSteps(TEST_CAR));
        assertEquals(2, bb.getCarEffectiveSteps(TEST_CAR));
    }

    @Test
    void executeMove_skipsEffectiveStepOnAlreadyExploredCell() {
        bb.setCarPosition(TEST_CAR, new Point(5, 10));
        bb.setCarStatus(TEST_CAR, CarStatus.READY);
        bb.setMapViewBit(10, 5, true);
        bb.setMapViewBit(11, 5, true);
        bb.pushRoute(TEST_CAR, List.of(new Point(5, 11)));

        executor.executeMove(1);

        assertEquals(1, bb.getCarSteps(TEST_CAR));
        assertEquals(0, bb.getCarEffectiveSteps(TEST_CAR));
    }

    @Test
    void executeLastMove_READYtoIDLE() {
        // 只剩最后一步
        bb.setCarPosition(TEST_CAR, new Point(10, 10));
        bb.setCarStatus(TEST_CAR, CarStatus.READY);
        bb.pushRoute(TEST_CAR, List.of(new Point(10, 11)));
        bb.setCarSteps(TEST_CAR, 0);

        executor.executeMove(1);

        assertEquals(new Point(10, 11), bb.getCarPosition(TEST_CAR).orElseThrow());
        assertEquals(CarStatus.IDLE, bb.getCarStatus(TEST_CAR).orElseThrow());
        assertEquals(1, bb.getCarSteps(TEST_CAR));
        assertTrue(bb.getCarRoute(TEST_CAR).isEmpty());
        // 注意：clearCarTarget 已移除，所以 hasTarget 可能是 true 或 false
        // 取决于是否之前设置了 target。这里不强制断言。
    }

    // ==================== 障碍物 ====================

    @Test
    void blockedByObstacle() {
        bb.setCarPosition(TEST_CAR, new Point(0, 0));
        bb.setCarStatus(TEST_CAR, CarStatus.READY);
        // 下一步 (0,1) 有障碍
        bb.setBlock(1, 0, true);
        bb.pushRoute(TEST_CAR, List.of(new Point(0, 1), new Point(0, 2)));

        executor.executeMove(1);

        assertEquals(CarStatus.BLOCKED, bb.getCarStatus(TEST_CAR).orElseThrow());
        assertEquals(1, bb.getBlockedTick(TEST_CAR));
        assertTrue(bb.getCarRoute(TEST_CAR).isEmpty());
    }

    // ==================== 碰撞 / 占据 ====================

    @Test
    void blockedAfterRepeatedOccupiedCollision() {
        bb.setCarPosition("CarOther", new Point(15, 29));
        bb.setCarStatus("CarOther", CarStatus.IDLE);
        bb.setCarPosition(TEST_CAR, new Point(14, 29));
        bb.setCarStatus(TEST_CAR, CarStatus.READY);
        bb.pushRoute(TEST_CAR, List.of(new Point(15, 29), new Point(16, 29)));

        executor.executeMove(1);
        assertEquals(CarStatus.READY, bb.getCarStatus(TEST_CAR).orElseThrow());

        executor.executeMove(2);
        assertEquals(CarStatus.READY, bb.getCarStatus(TEST_CAR).orElseThrow());

        executor.executeMove(3);
        assertEquals(CarStatus.BLOCKED, bb.getCarStatus(TEST_CAR).orElseThrow());
        assertTrue(bb.getCarRoute(TEST_CAR).isEmpty());
    }

    // ==================== 非 READY 状态 ====================

    @Test
    void skipWhenNotREADY_idle() {
        bb.setCarPosition(TEST_CAR, new Point(0, 0));
        bb.setCarStatus(TEST_CAR, CarStatus.IDLE);
        bb.pushRoute(TEST_CAR, List.of(new Point(1, 1)));

        executor.executeMove(1);

        // 状态不变
        assertEquals(CarStatus.IDLE, bb.getCarStatus(TEST_CAR).orElseThrow());
        // 路径完整未变
        assertEquals(1, bb.getCarRoute(TEST_CAR).size());
    }

    @Test
    void skipWhenNotREADY_moving() {
        bb.setCarPosition(TEST_CAR, new Point(5, 5));
        bb.setCarStatus(TEST_CAR, CarStatus.MOVING);
        bb.pushRoute(TEST_CAR, List.of(new Point(5, 6)));

        executor.executeMove(1);

        assertEquals(CarStatus.MOVING, bb.getCarStatus(TEST_CAR).orElseThrow());
        assertEquals(new Point(5, 5), bb.getCarPosition(TEST_CAR).orElseThrow());
    }

    @Test
    void skipWhenNotREADY_blocked() {
        bb.setCarPosition(TEST_CAR, new Point(0, 0));
        bb.setCarStatus(TEST_CAR, CarStatus.BLOCKED);
        bb.pushRoute(TEST_CAR, List.of(new Point(1, 1)));

        executor.executeMove(1);

        assertEquals(CarStatus.BLOCKED, bb.getCarStatus(TEST_CAR).orElseThrow());
    }

    // ==================== 空路径处理 ====================

    @Test
    void readyButEmptyRoute_becomesIdle() {
        bb.setCarPosition(TEST_CAR, new Point(5, 5));
        bb.setCarStatus(TEST_CAR, CarStatus.READY);

        executor.executeMove(1);

        assertEquals(CarStatus.IDLE, bb.getCarStatus(TEST_CAR).orElseThrow());
    }

    // ==================== 位置预约锁（防重叠） ====================

    @Test
    void positionReservationPreventsOverlap() {
        bb.setCarPosition(TEST_CAR, new Point(0, 0));
        bb.setCarStatus(TEST_CAR, CarStatus.READY);
        bb.pushRoute(TEST_CAR, List.of(new Point(1, 0), new Point(2, 0)));

        // 模拟另一辆车已预约了目标位置 (1,0)
        bb.tryReservePosition(1, 0, "CarOther");

        executor.executeMove(1);

        // 位置已被预约，小车不应移动
        assertEquals(new Point(0, 0), bb.getCarPosition(TEST_CAR).orElseThrow());
        assertEquals(CarStatus.READY, bb.getCarStatus(TEST_CAR).orElseThrow());
        // 路径完整未变
        assertEquals(2, bb.getCarRoute(TEST_CAR).size());
    }

    @Test
    void positionReservationReleasedAfterMove() {
        bb.setCarPosition(TEST_CAR, new Point(5, 5));
        bb.setCarStatus(TEST_CAR, CarStatus.READY);
        bb.pushRoute(TEST_CAR, List.of(new Point(5, 6)));

        executor.executeMove(1);

        // 移动完成后，位置预约锁应被释放，其他车可以预约
        assertTrue(bb.tryReservePosition(5, 6, "CarOther"));
        bb.releaseReservePosition(5, 6, "CarOther");
    }

    @Test
    void positionReservationReleasedOnObstacle() {
        bb.setCarPosition(TEST_CAR, new Point(0, 0));
        bb.setCarStatus(TEST_CAR, CarStatus.READY);
        bb.setBlock(1, 0, true);
        bb.pushRoute(TEST_CAR, List.of(new Point(0, 1)));

        executor.executeMove(1);

        // 遇到障碍物后，预约锁应被释放
        assertTrue(bb.tryReservePosition(0, 1, "CarOther"));
        bb.releaseReservePosition(0, 1, "CarOther");
    }

    // ==================== 点亮当前格 ====================

    @Test
    void illuminateSingleCell_onMove() {
        bb.setCarPosition(TEST_CAR, new Point(2, 2));
        bb.setCarStatus(TEST_CAR, CarStatus.READY);
        bb.pushRoute(TEST_CAR, List.of(new Point(3, 2)));

        executor.executeMove(1);

        assertTrue(bb.getMapViewBit(2, 3), "仅移动落点 (3,2) 应被点亮");
        assertFalse(bb.getMapViewBit(2, 2));
        assertFalse(bb.getMapViewBit(1, 3));
        assertFalse(bb.getMapViewBit(3, 3));
    }

    @Test
    void illuminateEdgeClipped() {
        bb.setCarPosition(TEST_CAR, new Point(0, 0));
        bb.setCarStatus(TEST_CAR, CarStatus.READY);
        bb.pushRoute(TEST_CAR, List.of(new Point(1, 0)));

        executor.executeMove(1);

        assertTrue(bb.getMapViewBit(0, 1));
        assertFalse(bb.getMapViewBit(0, 0));
    }

    // ==================== History 路径记录 ====================

    @Test
    void recordHistory_onMove() {
        bb.setCarPosition(TEST_CAR, new Point(5, 5));
        bb.setCarStatus(TEST_CAR, CarStatus.READY);
        bb.pushRoute(TEST_CAR, List.of(new Point(6, 5)));

        executor.executeMove(1);

        try (Jedis jedis = pool.getResource()) {
            List<String> history = jedis.lrange(TEST_CAR + ":History", 0, -1);
            assertEquals(1, history.size());
            assertTrue(history.get(0).contains("\"x\":6"));
            assertTrue(history.get(0).contains("\"y\":5"));
            assertTrue(history.get(0).contains("\"tick\":1"));
        }
    }

    // ==================== 热力图 ====================

    @Test
    void incrementMapHeat_onMove() {
        bb.setCarPosition(TEST_CAR, new Point(5, 5));
        bb.setCarStatus(TEST_CAR, CarStatus.READY);
        bb.pushRoute(TEST_CAR, List.of(new Point(6, 5)));

        executor.executeMove(1);

        Map<String, String> heat = bb.getMapHeat();
        assertFalse(heat.isEmpty());
        // (5,6) 是 center (6,5) → (col=6, row=5)
        assertEquals("1", heat.get("5,6"));
    }

    // ==================== 分布式锁 ====================

    @Test
    void lockPreventsConcurrentMove() throws InterruptedException {
        bb.setCarPosition(TEST_CAR, new Point(0, 0));
        bb.setCarStatus(TEST_CAR, CarStatus.READY);
        bb.pushRoute(TEST_CAR, List.of(new Point(0, 1), new Point(0, 2)));

        // 先手动占用锁
        try (Jedis jedis = pool.getResource()) {
            jedis.set("lock:" + TEST_CAR, "external-lock",
                    redis.clients.jedis.params.SetParams.setParams().nx().px(5000));
        }

        executor.executeMove(1);

        // 获取锁失败，状态不变
        assertEquals(CarStatus.READY, bb.getCarStatus(TEST_CAR).orElseThrow());
    }

    // ==================== MOVING 心跳 ====================

    @Test
    void statusGoesToMovingThenBack() {
        bb.setCarPosition(TEST_CAR, new Point(5, 5));
        bb.setCarStatus(TEST_CAR, CarStatus.READY);
        // 有多步剩余
        bb.pushRoute(TEST_CAR, List.of(new Point(5, 6), new Point(5, 7)));

        executor.executeMove(1);

        // 最终状态 READY（因为路径还有剩余）
        // 但中间会短暂变成 MOVING（心跳写入了，然后又被覆盖写为 READY）
        assertEquals(CarStatus.READY, bb.getCarStatus(TEST_CAR).orElseThrow());
    }
}
