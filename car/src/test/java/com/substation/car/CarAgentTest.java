package com.substation.car;

import com.substation.common.model.CarStatus;
import com.substation.common.model.Point;
import com.substation.common.mq.MessageBuilder;
import com.substation.common.mq.MessageBus;
import com.substation.common.mq.MessageTypes;
import com.substation.common.redis.BlackboardClient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CarAgent 单元测试。
 */
class CarAgentTest {

    private static final String REDIS_HOST = "localhost";
    private static final int REDIS_PORT = 6379;
    private static final String RABBIT_HOST = "localhost";
    private static final int RABBIT_PORT = 5672;
    private static final int MAP_SIZE = 30;
    private static final String TEST_CAR = "CarAgent";

    private JedisPool pool;
    private BlackboardClient bb;
    private MessageBus mb;
    private CarAgent agent;

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
        MoveExecutor executor = new MoveExecutor(TEST_CAR, bb, mb, pool);
        agent = new CarAgent(TEST_CAR, bb, executor);
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

    // ==================== TICK_MOVE 消息 ====================

    @Test
    void handleTickMove_executesMove() {
        bb.setCarPosition(TEST_CAR, new Point(5, 5));
        bb.setCarStatus(TEST_CAR, CarStatus.READY);
        bb.pushRoute(TEST_CAR, java.util.List.of(new Point(5, 6), new Point(5, 7)));

        String msg = MessageBuilder.build(MessageTypes.TICK_MOVE, 1, TEST_CAR);
        agent.handleMessage(msg);

        assertEquals(new Point(5, 6), bb.getCarPosition(TEST_CAR).orElseThrow());
        assertEquals(CarStatus.READY, bb.getCarStatus(TEST_CAR).orElseThrow());
        assertEquals(1, bb.getCarSteps(TEST_CAR));
    }

    @Test
    void handleTickMove_routeDone() {
        bb.setCarPosition(TEST_CAR, new Point(0, 0));
        bb.setCarStatus(TEST_CAR, CarStatus.READY);
        bb.pushRoute(TEST_CAR, java.util.List.of(new Point(0, 1)));

        String msg = MessageBuilder.build(MessageTypes.TICK_MOVE, 2, TEST_CAR);
        agent.handleMessage(msg);

        assertEquals(CarStatus.IDLE, bb.getCarStatus(TEST_CAR).orElseThrow());
        assertEquals(new Point(0, 1), bb.getCarPosition(TEST_CAR).orElseThrow());
    }

    // ==================== BLOCKED_TIMEOUT 消息 ====================

    @Test
    void handleBlockedTimeout_whenIdle() {
        bb.setCarStatus(TEST_CAR, CarStatus.IDLE);

        String msg = MessageBuilder.build(MessageTypes.BLOCKED_TIMEOUT, 5, TEST_CAR);
        agent.handleMessage(msg);

        assertEquals(CarStatus.IDLE, bb.getCarStatus(TEST_CAR).orElseThrow());
    }

    @Test
    void handleBlockedTimeout_whenBlocked() {
        bb.setCarStatus(TEST_CAR, CarStatus.BLOCKED);

        String msg = MessageBuilder.build(MessageTypes.BLOCKED_TIMEOUT, 5, TEST_CAR);
        agent.handleMessage(msg);

        // CarAgent 不应修改状态
        assertEquals(CarStatus.BLOCKED, bb.getCarStatus(TEST_CAR).orElseThrow());
    }

    // ==================== 非法消息 ====================

    @Test
    void handleInvalidJson_doesNotCrash() {
        agent.handleMessage("{ invalid json }");
        assertTrue(bb.getCarStatus(TEST_CAR).isEmpty());
    }

    @Test
    void handleUnknownType_doesNotCrash() {
        String msg = MessageBuilder.build("UNKNOWN_TYPE", 1, TEST_CAR);
        agent.handleMessage(msg);
        assertEquals(0, bb.getCarSteps(TEST_CAR));
    }
}
