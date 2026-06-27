package com.substation.common;

import com.substation.common.model.Point;
import com.substation.common.redis.BlackboardClient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DynamicObstacleUtilTest {

    private static final String TEST_HOST = "localhost";
    private static final int TEST_PORT = 6379;
    private static final int MAP_SIZE = 30;

    private BlackboardClient bb;
    private JedisPool pool;
    private DynamicObstacleUtil util;

    @BeforeEach
    void setUp() {
        pool = new JedisPool(TEST_HOST, TEST_PORT);
        bb = new BlackboardClient(TEST_HOST, TEST_PORT, MAP_SIZE, MAP_SIZE);
        util = new DynamicObstacleUtil();
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
    void addsAndRemovesObstacles() {
        // 先放几个障碍物
        bb.setBlock(5, 5, true);
        bb.setBlock(6, 6, true);
        bb.setBlock(7, 7, true);

        int before = countBlocked();
        List<String> changes = util.generate(bb, MAP_SIZE, MAP_SIZE, Set.of());
        int after = countBlocked();

        assertFalse(changes.isEmpty(), "应该有变更日志");
        // 新增 ≤ 2，移除 ≤ 2，净变化在 [-2, +2] 之间
        int delta = after - before;
        assertTrue(delta >= -2 && delta <= 2,
            "净变化应在[-2,+2]之间，实际=" + delta);
    }

    @Test
    void neverPlacesObstacleOnCarPosition() {
        Point car1 = new Point(10, 10);
        Point car2 = new Point(20, 20);
        Set<Point> carPositions = Set.of(car1, car2);

        // 多次运行确保不覆盖小车站位
        for (int i = 0; i < 30; i++) {
            util.generate(bb, MAP_SIZE, MAP_SIZE, carPositions);
            assertFalse(bb.isBlocked(car1.y(), car1.x()),
                "不应在 Car1(" + car1.x() + "," + car1.y() + ")放障碍");
            assertFalse(bb.isBlocked(car2.y(), car2.x()),
                "不应在 Car2(" + car2.x() + "," + car2.y() + ")放障碍");
        }
    }

    @Test
    void eachCallAddsAtMostTwoObstacles() {
        int before = countBlocked();
        util.generate(bb, MAP_SIZE, MAP_SIZE, Set.of());
        int after = countBlocked();

        int added = Math.max(0, after - before);
        assertTrue(added <= 2, "每次新增 ≤ 2，实际新增=" + added);
    }

    @Test
    void eachCallRemovesAtMostTwoObstacles() {
        // 放 10 个障碍物
        for (int i = 0; i < 10; i++) {
            bb.setBlock(i, i, true);
        }

        int before = countBlocked();
        util.generate(bb, MAP_SIZE, MAP_SIZE, Set.of());
        int after = countBlocked();

        int removed = Math.max(0, before - after);
        assertTrue(removed <= 2, "每次移除 ≤ 2，实际移除=" + removed);
    }

    // ==================== 边界 ====================

    @Test
    void handlesEmptyMapGracefully() {
        // 地图空无障碍物时不应崩溃
        List<String> changes = util.generate(bb, MAP_SIZE, MAP_SIZE, Set.of());
        assertNotNull(changes, "空地图时不应返回 null");
    }

    @Test
    void handlesFullMapGracefully() {
        // 把地图填满一半
        for (int r = 0; r < MAP_SIZE; r++) {
            for (int c = 0; c < MAP_SIZE / 2; c++) {
                bb.setBlock(r, c, true);
            }
        }

        List<String> changes = util.generate(bb, MAP_SIZE, MAP_SIZE, Set.of());
        assertNotNull(changes, "满地图时不应返回 null");
        assertFalse(changes.isEmpty(), "满地图时应有变更");
    }

    @Test
    void changeLogHasExpectedFormat() {
        bb.setBlock(5, 5, true);

        List<String> changes = util.generate(bb, MAP_SIZE, MAP_SIZE, Set.of());
        for (String change : changes) {
            assertTrue(
                change.startsWith("新增(") || change.startsWith("移除("),
                "日志格式应为'新增(x,y)'或'移除(x,y)'，实际=" + change);
        }
    }

    // ==================== 辅助方法 ====================

    private int countBlocked() {
        int count = 0;
        for (int r = 0; r < MAP_SIZE; r++) {
            for (int c = 0; c < MAP_SIZE; c++) {
                if (bb.isBlocked(r, c)) {
                    count++;
                }
            }
        }
        return count;
    }
}
