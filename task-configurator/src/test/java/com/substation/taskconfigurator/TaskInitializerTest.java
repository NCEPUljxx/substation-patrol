package com.substation.taskconfigurator;

import com.substation.common.model.CarStatus;
import com.substation.common.model.Point;
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

class TaskInitializerTest {

    private static final String TEST_HOST = "localhost";
    private static final int TEST_PORT = 6379;
    private static final int MAP_SIZE = 30;
    private static final int DEFAULT_CAR_COUNT = 5;
    private static final double DEFAULT_OBSTACLE_RATIO = 0.15;

    private BlackboardClient bb;
    private JedisPool pool;
    private TaskInitializer initializer;

    @BeforeEach
    void setUp() {
        pool = new JedisPool(TEST_HOST, TEST_PORT);
        bb = new BlackboardClient(TEST_HOST, TEST_PORT, MAP_SIZE, MAP_SIZE);
        initializer = new TaskInitializer();
        try (Jedis jedis = pool.getResource()) {
            jedis.flushDB();
        }
    }

    @AfterEach
    void tearDown() {
        try (Jedis jedis = pool.getResource()) {
            jedis.flushDB();
        }
        bb.close();
        pool.close();
    }

    // ==================== 正常路径测试 ====================

    @Test
    void initializedCarsHaveCorrectStatus() {
        initializer.initialize(bb, Map.of());

        for (int i = 1; i <= DEFAULT_CAR_COUNT; i++) {
            String carId = String.format("Car%03d", i);
            Optional<CarStatus> status = bb.getCarStatus(carId);
            assertTrue(status.isPresent(), carId + " 状态缺失");
            assertEquals(CarStatus.IDLE, status.get(), carId + " 状态应为 IDLE");
        }
    }

    @Test
    void initializedCarsHavePositionSet() {
        initializer.initialize(bb, Map.of());

        for (int i = 1; i <= DEFAULT_CAR_COUNT; i++) {
            String carId = String.format("Car%03d", i);
            Optional<Point> pos = bb.getCarPosition(carId);
            assertTrue(pos.isPresent(), carId + " 位置缺失");
        }
    }

    @Test
    void initializedCarsHaveZeroSteps() {
        initializer.initialize(bb, Map.of());

        for (int i = 1; i <= DEFAULT_CAR_COUNT; i++) {
            String carId = String.format("Car%03d", i);
            assertEquals(0, bb.getCarSteps(carId), carId + " 步数应为 0");
        }
    }

    @Test
    void spawnCellsRecordExplorationEventsForReplay() {
        initializer.initialize(bb, Map.of("carCount", 3));

        assertEquals(3, bb.getExplorationEvents().size(),
            "每台车出生格应写入 tick=0 的探索事件");
    }

    @Test
    void carsPlacedAtCornersAndCenterWhenCountAtMostFive() {
        initializer.initialize(bb, Map.of("obstacleRatio", 0));

        Point[] expected = {
            new Point(1, 1),
            new Point(28, 1),
            new Point(1, 28),
            new Point(28, 28),
            new Point(15, 15)
        };
        for (int i = 0; i < expected.length; i++) {
            String carId = String.format("Car%03d", i + 1);
            Optional<Point> pos = bb.getCarPosition(carId);
            assertTrue(pos.isPresent());
            assertEquals(expected[i], pos.get(), carId + " 位置错误");
        }
    }

    @Test
    void taskConfigContainsAllFields() {
        initializer.initialize(bb, Map.of());

        Map<String, String> config = bb.getTaskConfig();
        assertEquals("30", config.get("mapWidth"));
        assertEquals("30", config.get("mapHeight"));
        assertEquals("5", config.get("carCount"));
        assertEquals("BFS", config.get("algorithm"));
        assertEquals("500", config.get("tickInterval"));
        assertEquals("true", config.get("active"));
    }

    @Test
    void obstaclesPlacedWithinExpectedRange() {
        initializer.initialize(bb, Map.of());

        int blockedCount = countBlockedCells();
        int interiorCells = (MAP_SIZE - 2) * (MAP_SIZE - 2); // 28*28 = 784
        int expected = (int) (interiorCells * DEFAULT_OBSTACLE_RATIO);

        // 允许 ±20% 偏差（随机性）
        double tolerance = expected * 0.20;
        assertEquals(expected, blockedCount, tolerance, "障碍物+车位数量偏差过大");
    }

    @Test
    void carSpawnCellsAreExploredNotObstacles() {
        initializer.initialize(bb, Map.of());

        for (int i = 1; i <= DEFAULT_CAR_COUNT; i++) {
            String carId = String.format("Car%03d", i);
            Point pos = bb.getCarPosition(carId).orElseThrow();
            assertTrue(bb.getMapViewBit(pos.y(), pos.x()),
                carId + " 出生格应为已探索");
            assertFalse(bb.isBlocked(pos.y(), pos.x()),
                carId + " 出生格不应写入 mapBlock");
        }
    }

    @Test
    void carSpawnCellIsIlluminated() {
        initializer.initialize(bb, Map.of());

        Point pos = bb.getCarPosition("Car001").orElseThrow();
        assertTrue(bb.getMapViewBit(pos.y(), pos.x()),
            "初始车位应单格点亮");
        if (pos.x() + 1 < MAP_SIZE) {
            assertFalse(bb.getMapViewBit(pos.y(), pos.x() + 1),
                "相邻格不应被点亮");
        }
    }

    @Test
    void secondInitializationClearsStaleData() {
        initializer.initialize(bb, Map.of());

        // 第二次初始化，只放 3 辆车
        Map<String, Object> config = Map.of("carCount", 3);
        try (Jedis jedis = pool.getResource()) {
            jedis.flushDB();
        }
        initializer.initialize(bb, config);

        // Car004 和 Car005 不应存在（第一次初始化的残留）
        assertFalse(bb.getCarPosition("Car004").isPresent());
        assertFalse(bb.getCarPosition("Car005").isPresent());

        // Car001~003 应正常
        assertEquals(0, bb.getCarSteps("Car001"));
        assertEquals(CarStatus.IDLE, bb.getCarStatus("Car001").orElseThrow());
    }

    // ==================== 参数化测试 ====================

    @Test
    void customCarCountRespected() {
        int customCount = 3;
        initializer.initialize(bb, Map.of("carCount", customCount));

        for (int i = 1; i <= customCount; i++) {
            assertTrue(bb.getCarPosition(String.format("Car%03d", i)).isPresent());
        }
        assertFalse(bb.getCarPosition("Car004").isPresent());
    }

    @Test
    void tenByTenMapKeepsCarsInsideBounds() {
        initializer.initialize(bb, Map.of("mapWidth", 10, "mapHeight", 10, "carCount", 3));

        Map<String, String> config = bb.getTaskConfig();
        assertEquals("10", config.get("mapWidth"));
        assertEquals("10", config.get("mapHeight"));

        for (int i = 1; i <= 3; i++) {
            Point pos = bb.getCarPosition(String.format("Car%03d", i)).orElseThrow();
            assertTrue(pos.x() >= 0 && pos.x() < 10, "x 越界: " + pos);
            assertTrue(pos.y() >= 0 && pos.y() < 10, "y 越界: " + pos);
        }
    }

    @Test
    void customMapSizeRespected() {
        initializer.initialize(bb, Map.of("mapWidth", 20, "mapHeight", 20));

        Map<String, String> config = bb.getTaskConfig();
        assertEquals("20", config.get("mapWidth"));
        assertEquals("20", config.get("mapHeight"));
    }

    @Test
    void customAlgorithmRespected() {
        initializer.initialize(bb, Map.of("algorithm", "ASTAR"));

        assertEquals("ASTAR", bb.getAlgorithm());
    }

    @Test
    void zeroObstacleRatioPlacesNoExtraObstacles() {
        initializer.initialize(bb, Map.of("obstacleRatio", 0.0));

        // 比例 0 时不应有随机障碍物（车位也不再写入 mapBlock）
        assertEquals(0, countBlockedCells(),
            "比例 0 时不应有 mapBlock 障碍物");
    }

    @Test
    void sealedBitmapWrittenOnInit() {
        initializer.initialize(bb, Map.of());

        boolean[][] sealed = bb.loadSealedBitmap();
        assertEquals(MAP_SIZE, sealed.length);
        assertEquals(MAP_SIZE, sealed[0].length);
    }

    @Test
    void carSpawnCellsAreNeverSealed() {
        initializer.initialize(bb, Map.of());

        for (int i = 1; i <= DEFAULT_CAR_COUNT; i++) {
            String carId = String.format("Car%03d", i);
            Point pos = bb.getCarPosition(carId).orElseThrow();
            boolean[][] sealed = bb.loadSealedBitmap();
            assertFalse(sealed[pos.y()][pos.x()], carId + " 出生格不应为密封区");
        }
    }

    // ==================== 辅助方法 ====================

    private int countBlockedCells() {
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
