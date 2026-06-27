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

class BfsPathFinderTest {

    private static final String TEST_HOST = "localhost";
    private static final int TEST_PORT = 6379;
    private static final int MAP_SIZE = 30;

    private BlackboardClient bb;
    private JedisPool pool;
    private BfsPathFinder finder;

    @BeforeEach
    void setUp() {
        pool = new JedisPool(TEST_HOST, TEST_PORT);
        bb = new BlackboardClient(TEST_HOST, TEST_PORT, MAP_SIZE, MAP_SIZE);
        finder = new BfsPathFinder();
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

    // ==================== 正常路径 ====================

    @Test
    void straightPathNoObstacles() {
        Point start = new Point(0, 0);
        Point target = new Point(5, 0);
        List<Point> path = finder.plan(start, target, bb);

        assertFalse(path.isEmpty(), "无障碍时应有路径");
        assertEquals(5, path.size(), "从(0,0)→(5,0) 应 5 步");
        assertEquals(target, path.get(path.size() - 1), "路径终点应为 target");
        assertFalse(path.contains(start), "路径不应含 start");
    }

    @Test
    void diagonalPath() {
        Point start = new Point(2, 2);
        Point target = new Point(5, 5);
        List<Point> path = finder.plan(start, target, bb);

        assertFalse(path.isEmpty());
        // 曼哈顿距离 = |5-2|+|5-2| = 6，所以路径长度 = 6
        assertEquals(6, path.size(),
            "对角线路径曼哈顿距离=6，应6步，实际=" + path.size());
    }

    @Test
    void pathContainsOnlyTargetNotStart() {
        Point start = new Point(0, 0);
        Point target = new Point(3, 0);

        List<Point> path = finder.plan(start, target, bb);

        assertFalse(path.contains(start), "路径不应包含起点");
        assertTrue(path.contains(target), "路径应包含终点");
        assertEquals(target, path.get(path.size() - 1), "终点在末尾");
    }

    // ==================== 障碍物 ====================

    @Test
    void pathDetoursAroundObstacle() {
        // 在 (2,0) 放障碍物，迫使路径绕行
        bb.setBlock(0, 2, true);

        Point start = new Point(0, 0);
        Point target = new Point(4, 0);
        List<Point> path = finder.plan(start, target, bb);

        assertFalse(path.isEmpty(), "应能绕过障碍物");
        // 路径不应包含障碍物位置
        assertFalse(path.contains(new Point(2, 0)), "路径不应经过障碍物(" + 2 + "," + 0 + ")");
        // 绕行后路径长度 > 曼哈顿距离
        assertTrue(path.size() > 4,
            "绕行路径长度(" + path.size() + ")应大于曼哈顿距离(4)");
    }

    @Test
    void noPathWhenCompletelyBlocked() {
        // 起点 (0,0)，堵塞右侧 (1,0) 和下方 (0,1)（起点在角落只有这两个出口）
        bb.setBlock(0, 1, true);
        bb.setBlock(1, 0, true);

        Point start = new Point(0, 0);
        Point target = new Point(5, 5);
        List<Point> path = finder.plan(start, target, bb);

        assertTrue(path.isEmpty(),
            "被围时应无路径，实际找到=" + path.size() + "步");
    }

    @Test
    void blockedCellsNotInPath() {
        // 在直线上放多个障碍，确保路径完全避开
        for (int i = 1; i <= 3; i++) {
            bb.setBlock(0, i, true); // 堵塞 (1,0) (2,0) (3,0)
        }

        Point start = new Point(0, 0);
        Point target = new Point(4, 0);
        List<Point> path = finder.plan(start, target, bb);

        assertFalse(path.isEmpty());
        for (Point p : path) {
            assertFalse(bb.isBlocked(p.y(), p.x()),
                "路径点(" + p.x() + "," + p.y() + ")不应在障碍物上");
        }
    }

    // ==================== 边界 ====================

    @Test
    void targetOutOfBoundsReturnsEmpty() {
        Point start = new Point(0, 0);
        Point target = new Point(35, 35);

        List<Point> path = finder.plan(start, target, bb);
        assertTrue(path.isEmpty(), "目标越界应返回空");
    }

    @Test
    void pathRespectsMapBoundaries() {
        Point start = new Point(0, 0);
        Point target = new Point(29, 29);
        List<Point> path = finder.plan(start, target, bb);

        assertFalse(path.isEmpty());
        for (Point p : path) {
            assertTrue(p.x() >= 0 && p.x() < MAP_SIZE,
                "x=" + p.x() + "越界");
            assertTrue(p.y() >= 0 && p.y() < MAP_SIZE,
                "y=" + p.y() + "越界");
        }
    }

    // ==================== 稳定性 ====================

    @Test
    void bfsProducesConsistentResults() {
        Point start = new Point(0, 0);
        Point target = new Point(29, 29);

        List<Point> first = finder.plan(start, target, bb);
        for (int i = 0; i < 20; i++) {
            List<Point> result = finder.plan(start, target, bb);
            assertEquals(first.size(), result.size(),
                "多次运行应得到相同长度的路径");
        }
    }

    @Test
    void prefersUnexploredDetourOverExploredCorridor() {
        int width = 7;
        int height = 3;
        bb.initTaskConfig(java.util.Map.of("mapWidth", String.valueOf(width), "mapHeight", String.valueOf(height)));

        for (int col = 0; col < width; col++) {
            bb.setMapViewBit(0, col, true);
            bb.setMapViewBit(1, col, col < width - 1);
        }

        Point start = new Point(0, 1);
        Point target = new Point(6, 1);
        List<Point> path = finder.plan(start, target, bb);

        assertFalse(path.contains(new Point(3, 1)),
            "应绕开已探索走廊，实际路径=" + path);
        assertTrue(path.stream().anyMatch(p -> p.y() == 2),
            "应经过未探索底行");
    }
}
