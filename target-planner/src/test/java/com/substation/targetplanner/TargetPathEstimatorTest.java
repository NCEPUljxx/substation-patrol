package com.substation.targetplanner;

import com.substation.common.model.Point;
import com.substation.common.redis.BlackboardClient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import static org.junit.jupiter.api.Assertions.*;

class TargetPathEstimatorTest {

    private static final int MAP_SIZE = 10;

    private JedisPool pool;
    private BlackboardClient bb;
    private TargetPathEstimator estimator;

    @BeforeEach
    void setUp() {
        pool = new JedisPool("localhost", 6379);
        bb = new BlackboardClient("localhost", 6379, MAP_SIZE, MAP_SIZE);
        estimator = new TargetPathEstimator();
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
    void findsShortestPathAroundObstacle() {
        for (int row = 1; row <= 8; row++) {
            bb.setBlock(row, 5, true);
        }
        boolean[][] blocked = bb.loadBlockedMapWithCars();
        boolean[][] explored = bb.loadExploredBitmap();

        var path = estimator.planPath(
            new Point(0, 0), new Point(9, 0), blocked, explored, MAP_SIZE, MAP_SIZE);

        assertFalse(path.isEmpty());
        assertEquals(new Point(9, 0), path.get(path.size() - 1));
        assertTrue(path.size() >= 9, "绕障路径应长于直线曼哈顿距离");
    }

    @Test
    void returnsEmptyWhenUnreachable() {
        for (int col = 0; col < MAP_SIZE; col++) {
            bb.setBlock(5, col, true);
        }
        boolean[][] blocked = bb.loadBlockedMapWithCars();
        boolean[][] explored = bb.loadExploredBitmap();

        var path = estimator.planPath(
            new Point(0, 0), new Point(0, 9), blocked, explored, MAP_SIZE, MAP_SIZE);

        assertTrue(path.isEmpty());
    }
}
