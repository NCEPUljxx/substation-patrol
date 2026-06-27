package com.substation.controller;

import com.substation.common.model.CarStatus;
import com.substation.common.model.Point;
import com.substation.common.mq.MessageBus;
import com.substation.common.redis.BlackboardClient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import static org.junit.jupiter.api.Assertions.*;

class StatusDispatcherTest {

    private static final int MAP_SIZE = 30;

    private BlackboardClient bb;
    private MessageBus bus;
    private StatusDispatcher dispatcher;

    @BeforeEach
    void setUp() throws Exception {
        try (Jedis jedis = new JedisPool("localhost", 6379).getResource()) {
            jedis.flushDB();
        }
        bb = new BlackboardClient("localhost", 6379, MAP_SIZE, MAP_SIZE);
        bus = new MessageBus("localhost", 5672, "guest", "guest");
        bus.connect();
        bus.declareTargetPlannerQueue();
        bus.declareNavigatorQueue();
        bus.declareTaskConfigQueue();
        bus.declareFanoutExchange();

        bb.initTaskConfig(Map.of("active", "true", "algorithm", "BFS"));
        dispatcher = new StatusDispatcher(bb, bus);
    }

    @AfterEach
    void tearDown() {
        try (Jedis jedis = new JedisPool("localhost", 6379).getResource()) {
            jedis.flushDB();
        }
        bus.close();
        bb.close();
    }

    @Test
    void idleCarReceivesAssignTarget() {
        registerCar("Car001", new Point(5, 5), CarStatus.IDLE);
        dispatcher.onTaskReady();
        dispatcher.dispatch();

        assertEquals(CarStatus.IDLE, bb.getCarStatus("Car001").orElseThrow());
    }

    @Test
    void blockedTimeoutResetsToIdle() {
        registerCar("Car002", new Point(10, 10), CarStatus.BLOCKED);
        bb.setBlockedTick("Car002", 0);
        dispatcher.onTaskReady();

        dispatcher.dispatch();
        assertEquals(CarStatus.BLOCKED, bb.getCarStatus("Car002").orElseThrow());

        // 随机超时阈值在 [2, 5] tick，循环直到触发或超过上限
        int maxExtraTicks = 6;
        for (int i = 0; i < maxExtraTicks; i++) {
            if (bb.getCarStatus("Car002").orElse(null) == CarStatus.IDLE) {
                break;
            }
            dispatcher.dispatch();
        }
        assertEquals(CarStatus.IDLE, bb.getCarStatus("Car002").orElseThrow());
        assertEquals(-1, bb.getBlockedTick("Car002"));
    }

    @Test
    void movingStuckResetsToReady() {
        registerCar("Car003", new Point(10, 10), CarStatus.MOVING);
        dispatcher.onTaskReady();

        // tick 1: first MOVING
        dispatcher.dispatch();
        assertEquals(CarStatus.MOVING, bb.getCarStatus("Car003").orElseThrow());

        // tick 2: stuck, should reset to READY
        dispatcher.dispatch();
        assertEquals(CarStatus.READY, bb.getCarStatus("Car003").orElseThrow());
    }

    @Test
    void readyCarGetsTickMove() {
        registerCar("Car004", new Point(10, 10), CarStatus.READY);
        dispatcher.onTaskReady();
        dispatcher.dispatch();

        // Car should remain READY (Car module handles the actual move)
        assertEquals(CarStatus.READY, bb.getCarStatus("Car004").orElseThrow());
    }

    @Test
    void notActiveSkipsDispatch() {
        registerCar("Car005", new Point(5, 5), CarStatus.IDLE);
        // Don't call onTaskReady, so taskActive is false
        dispatcher.dispatch();
        assertEquals(CarStatus.IDLE, bb.getCarStatus("Car005").orElseThrow());
    }

    @Test
    void taskCompleteWhenExplorationReachesThreshold() {
        for (int r = 0; r < MAP_SIZE; r++) {
            for (int c = 0; c < MAP_SIZE; c++) {
                bb.setMapViewBit(r, c, true);
            }
        }
        registerCar("Car006", new Point(5, 5), CarStatus.IDLE);
        dispatcher.onTaskReady();
        dispatcher.dispatch();

        assertTrue(bb.isExplorationComplete());
        assertFalse(dispatcher.isActive(), "探索完成后应停止调度");
        assertEquals(CarStatus.IDLE, bb.getCarStatus("Car006").orElseThrow());
        assertTrue(bb.getCarRoute("Car006").isEmpty());
    }

    @Test
    void routePlannedWithSupervisionStaysReadyAndKeepsRoute() {
        registerCar("Car007", new Point(5, 5), CarStatus.WAITING_ROUTE);
        bb.setCarTarget("Car007", new Point(10, 10));
        bb.pushRoute("Car007", java.util.List.of(
            new Point(6, 5), new Point(7, 5), new Point(8, 5)));
        dispatcher.onTaskReady();
        dispatcher.onRoutePlanned("Car007", true);

        assertEquals(CarStatus.READY, bb.getCarStatus("Car007").orElseThrow());
        assertEquals(3, bb.getCarRoute("Car007").size());

        dispatcher.dispatch();
        dispatcher.dispatch();

        assertEquals(3, bb.getCarRoute("Car007").size(),
            "监督等待期间不应重复规划导致路线被清空或替换");
    }

    private void registerCar(String carId, Point pos, CarStatus status) {
        bb.setCarPosition(carId, pos);
        bb.setCarStatus(carId, status);
        bb.setCarSteps(carId, 0);
    }
}
