package com.substation.navigator;

import com.substation.common.model.Point;
import com.substation.common.redis.BlackboardClient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AStarPathFinderTest {

    private static final String TEST_HOST = "localhost";
    private static final int TEST_PORT = 6379;
    private static final int MAP_SIZE = 30;

    private BlackboardClient bb;
    private JedisPool pool;
    private AStarPathFinder finder;
    private BfsPathFinder bfsFinder;

    @BeforeEach
    void setUp() {
        pool = new JedisPool(TEST_HOST, TEST_PORT);
        bb = new BlackboardClient(TEST_HOST, TEST_PORT, MAP_SIZE, MAP_SIZE);
        finder = new AStarPathFinder();
        bfsFinder = new BfsPathFinder();
        try (Jedis jedis = pool.getResource()) {
            jedis.flushDB();
        }
        bb.initTaskConfig(java.util.Map.of("mapWidth", String.valueOf(MAP_SIZE),
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

    // ==================== 正确性 ====================

    @Test
    void straightPathMatchesBfs() {
        Point start = new Point(0, 0);
        Point target = new Point(10, 0);

        List<Point> aStarPath = finder.plan(start, target, bb);
        List<Point> bfsPath = bfsFinder.plan(start, target, bb);

        assertEquals(bfsPath.size(), aStarPath.size(),
            "A*应与BFS路径长度相同");
    }

    @Test
    void detourPathMatchesBfsLength() {
        // 在直线上放障碍
        for (int i = 4; i <= 6; i++) {
            bb.setBlock(0, i, true);
        }

        Point start = new Point(0, 3);
        Point target = new Point(10, 3);

        List<Point> aStarPath = finder.plan(start, target, bb);
        List<Point> bfsPath = bfsFinder.plan(start, target, bb);

        assertFalse(aStarPath.isEmpty());
        assertEquals(bfsPath.size(), aStarPath.size(),
            "绕行时A*应与BFS路径长度相同");
    }

    @Test
    void aStarFindsShortestPath() {
        // 在 (2,0) 放障碍
        bb.setBlock(0, 2, true);

        Point start = new Point(0, 0);
        Point target = new Point(4, 0);

        List<Point> path = finder.plan(start, target, bb);

        assertFalse(path.isEmpty());
        // 绕行: (0,0)→(0,1)→(1,1)→(2,1)→(3,1)→(4,1)→(4,0) = 6步
        // 或: (0,0)→(1,0)→(1,1)→...(4,1)→(4,0)
        // 最短路径长度 = 6
        assertEquals(6, path.size(),
            "A*应找最短路径，实际=" + path.size());
    }

    // ==================== 特殊情况 ====================

    @Test
    void noPathWhenBlocked() {
        bb.setBlock(0, 1, true);
        bb.setBlock(1, 0, true);

        Point start = new Point(0, 0);
        Point target = new Point(5, 5);
        List<Point> path = finder.plan(start, target, bb);

        assertTrue(path.isEmpty());
    }

    @Test
    void targetOutOfBoundsReturnsEmpty() {
        Point start = new Point(5, 5);
        Point target = new Point(30, 30);
        List<Point> path = finder.plan(start, target, bb);

        assertTrue(path.isEmpty());
    }

    @Test
    void pathRespectsBoundaries() {
        Point start = new Point(0, 0);
        Point target = new Point(29, 29);
        List<Point> path = finder.plan(start, target, bb);

        assertFalse(path.isEmpty());
        for (Point p : path) {
            assertTrue(p.x() >= 0 && p.x() < MAP_SIZE);
            assertTrue(p.y() >= 0 && p.y() < MAP_SIZE);
        }
    }

    // ==================== 稳定性 ====================

    @Test
    void aStarProducesConsistentResults() {
        Point start = new Point(0, 0);
        Point target = new Point(29, 29);

        List<Point> first = finder.plan(start, target, bb);
        for (int i = 0; i < 20; i++) {
            List<Point> result = finder.plan(start, target, bb);
            assertEquals(first.size(), result.size(),
                "多次运行应得到相同长度的路径");
        }
    }
}
