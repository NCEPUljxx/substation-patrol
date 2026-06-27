package com.substation.strategysupervisor;

import com.substation.common.model.CarStatus;
import com.substation.common.model.Point;
import com.substation.common.redis.BlackboardClient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RouteOverlapEvaluatorTest {

    private static final int MAP_SIZE = 30;

    private JedisPool pool;
    private BlackboardClient bb;
    private RouteOverlapEvaluator evaluator;

    @BeforeEach
    void setUp() {
        pool = new JedisPool("localhost", 6379);
        bb = new BlackboardClient("localhost", 6379, MAP_SIZE, MAP_SIZE);
        evaluator = new RouteOverlapEvaluator();
        try (Jedis jedis = pool.getResource()) {
            jedis.flushDB();
        }
        bb.initTaskConfig(java.util.Map.of(
            "mapWidth", String.valueOf(MAP_SIZE),
            "mapHeight", String.valueOf(MAP_SIZE)));
    }

    @AfterEach
    void tearDown() {
        try (Jedis jedis = pool.getResource()) {
            jedis.flushDB();
        }
        bb.close();
        pool.close();
    }

    @Test
    void sharedExploredCorridorDoesNotTriggerOverlap() {
        markExplored(bb, 0, 0, 25, 5);

        List<Point> routeA = List.of(
            new Point(1, 1), new Point(2, 1), new Point(3, 1), new Point(4, 1), new Point(20, 20));
        List<Point> routeB = List.of(
            new Point(1, 1), new Point(2, 1), new Point(3, 1), new Point(4, 1), new Point(22, 22));

        bb.setCarPosition("Car001", new Point(9, 10));
        bb.setCarPosition("Car002", new Point(9, 10));
        bb.setCarStatus("Car001", CarStatus.READY);
        bb.setCarStatus("Car002", CarStatus.READY);
        bb.pushRoute("Car002", routeB);

        assertFalse(evaluator.isHighlyOverlapped("Car001", routeA, bb),
            "仅共享已探索通道时不应判定重合");
    }

    @Test
    void sharedUnexploredCellsTriggerOverlap() {
        List<Point> routeA = List.of(new Point(10, 10), new Point(11, 10));
        List<Point> routeB = List.of(new Point(10, 10), new Point(11, 10), new Point(13, 10));

        bb.setCarPosition("Car001", new Point(9, 10));
        bb.setCarPosition("Car002", new Point(9, 10));
        bb.setCarStatus("Car001", CarStatus.READY);
        bb.setCarStatus("Car002", CarStatus.READY);
        bb.pushRoute("Car002", routeB);

        assertTrue(evaluator.isHighlyOverlapped("Car001", routeA, bb),
            "未探索格高度重合时应触发重分配");
    }

    @Test
    void allExploredRouteNeverTriggersOverlap() {
        markExplored(bb, 0, 0, 10, 10);
        List<Point> routeA = List.of(new Point(1, 1), new Point(2, 1), new Point(3, 1));
        List<Point> routeB = List.of(new Point(1, 1), new Point(2, 1), new Point(4, 1));

        bb.setCarPosition("Car001", new Point(0, 0));
        bb.setCarPosition("Car002", new Point(0, 0));
        bb.setCarStatus("Car001", CarStatus.READY);
        bb.setCarStatus("Car002", CarStatus.READY);
        bb.pushRoute("Car002", routeB);

        assertFalse(evaluator.isHighlyOverlapped("Car001", routeA, bb));
    }

    private static void markExplored(BlackboardClient bb, int x0, int y0, int x1, int y1) {
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                bb.setMapViewBit(y, x, true);
            }
        }
    }
}
