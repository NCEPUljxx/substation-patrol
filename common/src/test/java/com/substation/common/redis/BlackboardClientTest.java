package com.substation.common.redis;

import com.substation.common.model.CarStatus;
import com.substation.common.model.Point;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import static org.junit.jupiter.api.Assertions.*;

class BlackboardClientTest {

    private static final String TEST_HOST = "localhost";
    private static final int TEST_PORT = 6379;
    private static final int MAP_SIZE = 30;

    private BlackboardClient bb;
    private JedisPool pool;

    @BeforeEach
    void setUp() {
        pool = new JedisPool(TEST_HOST, TEST_PORT);
        bb = new BlackboardClient(TEST_HOST, TEST_PORT, MAP_SIZE, MAP_SIZE);
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
    }

    @Test
    void mapViewBit() {
        assertFalse(bb.getMapViewBit(5, 10));
        bb.setMapViewBit(5, 10, true);
        assertTrue(bb.getMapViewBit(5, 10));
        bb.setMapViewBit(5, 10, false);
        assertFalse(bb.getMapViewBit(5, 10));
    }

    @Test
    void mapBlockBit() {
        assertFalse(bb.isBlocked(3, 7));
        bb.setBlock(3, 7, true);
        assertTrue(bb.isBlocked(3,  7));
    }

    @Test
    void writeBlockBitmap_roundTripsWithLoadObstacleBitmap() {
        bb.initTaskConfig(Map.of("mapWidth", "20", "mapHeight", "15"));
        boolean[][] blocked = new boolean[15][20];
        blocked[3][7] = true;
        blocked[10][18] = true;
        blocked[14][0] = true;

        bb.writeBlockBitmap(blocked, 20);

        boolean[][] loaded = bb.loadObstacleBitmap();
        assertTrue(loaded[3][7]);
        assertTrue(loaded[10][18]);
        assertTrue(loaded[14][0]);
        assertFalse(loaded[0][0]);
    }

    @Test
    void bitmapToBytes_matchesBytesToBitmap() {
        int width = 17;
        int height = 13;
        boolean[][] source = new boolean[height][width];
        source[2][5] = true;
        source[12][16] = true;

        byte[] encoded = BlackboardClient.bitmapToBytes(source, width);
        boolean[][] decoded = BlackboardClient.bytesToBitmap(encoded, width, height);

        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                assertEquals(source[row][col], decoded[row][col], "cell " + row + "," + col);
            }
        }
    }

    @Test
    void carPosition() {
        assertTrue(bb.getCarPosition("Car001").isEmpty());

        bb.setCarPosition("Car001", new Point(5, 10));
        Optional<Point> pos = bb.getCarPosition("Car001");
        assertTrue(pos.isPresent());
        assertEquals(new Point(5, 10), pos.get());

        bb.clearCarPosition("Car001");
        assertTrue(bb.getCarPosition("Car001").isEmpty());
    }

    @Test
    void carTarget() {
        assertFalse(bb.hasTarget("Car001"));
        bb.setCarTarget("Car001", new Point(20, 15));
        assertTrue(bb.hasTarget("Car001"));

        Optional<Point> target = bb.getCarTarget("Car001");
        assertTrue(target.isPresent());
        assertEquals(new Point(20, 15), target.get());

        bb.clearCarTarget("Car001");
        assertFalse(bb.hasTarget("Car001"));
    }

    @Test
    void carRoute() {
        List<Point> route = List.of(new Point(5, 10), new Point(6, 10), new Point(7, 10));
        bb.pushRoute("Car001", route);

        List<Point> stored = bb.getCarRoute("Car001");
        assertEquals(3, stored.size());

        Optional<Point> next = bb.peekNextRouteStep("Car001");
        assertTrue(next.isPresent());
        assertEquals(new Point(5, 10), next.get());

        Optional<Point> popped = bb.popNextRouteStep("Car001");
        assertTrue(popped.isPresent());
        assertEquals(new Point(5, 10), popped.get());

        assertEquals(2, bb.getCarRoute("Car001").size());
    }

    @Test
    void carStatus() {
        assertTrue(bb.getCarStatus("Car001").isEmpty());

        bb.setCarStatus("Car001", CarStatus.READY);
        assertEquals(CarStatus.READY, bb.getCarStatus("Car001").orElseThrow());

        bb.setCarStatus("Car001", CarStatus.BLOCKED);
        assertEquals(CarStatus.BLOCKED, bb.getCarStatus("Car001").orElseThrow());
    }

    @Test
    void carSteps() {
        assertEquals(0, bb.getCarSteps("Car001"));

        bb.setCarSteps("Car001", 10);
        assertEquals(10, bb.getCarSteps("Car001"));

        bb.incrementCarSteps("Car001");
        assertEquals(11, bb.getCarSteps("Car001"));
    }

    @Test
    void carEffectiveSteps() {
        assertEquals(0, bb.getCarEffectiveSteps("Car001"));

        bb.setCarEffectiveSteps("Car001", 7);
        assertEquals(7, bb.getCarEffectiveSteps("Car001"));

        bb.incrementCarEffectiveSteps("Car001");
        assertEquals(8, bb.getCarEffectiveSteps("Car001"));
    }

    @Test
    void blockedTick() {
        assertEquals(-1, bb.getBlockedTick("Car001"));

        bb.setBlockedTick("Car001", 42);
        assertEquals(42, bb.getBlockedTick("Car001"));

        bb.clearBlockedTick("Car001");
        assertEquals(-1, bb.getBlockedTick("Car001"));
    }

    @Test
    void controllerLock() {
        assertTrue(bb.acquireControllerLock());
        assertFalse(bb.acquireControllerLock());
        bb.releaseControllerLock();
        assertTrue(bb.acquireControllerLock());
    }

    @Test
    void taskConfig() {
        Map<String, String> config = Map.of(
            "mapWidth", "30",
            "mapHeight", "30",
            "carCount", "5",
            "algorithm", "BFS",
            "active", "true"
        );
        bb.initTaskConfig(config);

        assertTrue(bb.isTaskActive());
        assertEquals(30, bb.getMapWidth());
        assertEquals(30, bb.getMapHeight());
        assertEquals(5, bb.getCarCount());
        assertEquals("BFS", bb.getAlgorithm());

        bb.setTaskActive(false);
        assertFalse(bb.isTaskActive());
    }

    @Test
    void setTickInterval_updatesTaskConfig() {
        bb.initTaskConfig(Map.of("tickInterval", "500"));
        bb.setTickInterval(300);
        assertEquals(300, bb.getTickInterval());
    }

    @Test
    void mapHeat() {
        bb.incrementMapHeat(5, 10);
        bb.incrementMapHeat(5, 10);
        bb.incrementMapHeat(3, 7);

        Map<String, String> heat = bb.getMapHeat();
        assertEquals("2", heat.get("5,10"));
        assertEquals("1", heat.get("3,7"));
    }

    @Test
    void explorationRate_noBlocked() {
        bb.setMapViewBit(0, 0, true);
        bb.setMapViewBit(0, 1, true);
        int rate = bb.getExplorationRate();
        assertTrue(rate >= 0);
    }

    @Test
    void explorationRate_excludesSealedCells() {
        bb.initTaskConfig(Map.of("mapWidth", "5", "mapHeight", "5"));
        boolean[][] obstacles = {
            {false, false, false, false, false},
            {false, true, true, true, false},
            {false, true, false, true, false},
            {false, true, true, true, false},
            {false, false, false, false, false}
        };
        for (int row = 0; row < 5; row++) {
            for (int col = 0; col < 5; col++) {
                if (obstacles[row][col]) {
                    bb.setBlock(row, col, true);
                }
            }
        }
        bb.writeSealedBitmap(new boolean[][]{
            {false, false, false, false, false},
            {false, false, false, false, false},
            {false, false, true, false, false},
            {false, false, false, false, false},
            {false, false, false, false, false}
        }, 5);

        for (int row = 0; row < 5; row++) {
            for (int col = 0; col < 5; col++) {
                if (!obstacles[row][col] && !(row == 2 && col == 2)) {
                    bb.setMapViewBit(row, col, true);
                }
            }
        }

        assertEquals(100, bb.getExplorationRate());
        assertTrue(bb.isExplorationComplete());
    }

    @Test
    void isExplorationComplete_requiresFullExplorableCoverage() {
        bb.writeSealedBitmap(new boolean[30][30], 30);
        for (int r = 0; r < 29; r++) {
            for (int c = 0; c < 30; c++) {
                bb.setMapViewBit(r, c, true);
            }
        }
        assertTrue(bb.hasUnexploredExplorableCells());
        assertFalse(bb.isExplorationComplete());
        assertTrue(bb.getExplorationRate() < 100);

        bb.setMapViewBit(29, 0, true);
        for (int c = 1; c < 30; c++) {
            bb.setMapViewBit(29, c, true);
        }
        assertFalse(bb.hasUnexploredExplorableCells());
        assertEquals(100, bb.getExplorationRate());
        assertTrue(bb.isExplorationComplete());
    }

    @Test
    void isExplorationComplete_whenNoUnexploredCells() {
        bb.writeSealedBitmap(new boolean[30][30], 30);
        for (int r = 0; r < 30; r++) {
            for (int c = 0; c < 30; c++) {
                bb.setMapViewBit(r, c, true);
            }
        }
        assertFalse(bb.hasUnexploredExplorableCells());
        assertEquals(100, bb.getExplorationRate());
        assertTrue(bb.isExplorationComplete());
    }

    @Test
    void explorationRate_reaches100WhenAllReachableExplored_onLargeMap() {
        int size = 100;
        bb.initTaskConfig(Map.of("mapWidth", String.valueOf(size), "mapHeight", String.valueOf(size)));

        boolean[][] obstacles = new boolean[size][size];
        for (int row = 40; row < 60; row++) {
            for (int col = 0; col < size; col++) {
                obstacles[row][col] = true;
                bb.setBlock(row, col, true);
            }
        }

        List<Point> starts = List.of(new Point(1, 1), new Point(98, 98));
        boolean[][] sealed = com.substation.common.map.ReachabilityAnalyzer
            .findSealedFreeCells(obstacles, starts);
        bb.writeSealedBitmap(sealed, size);

        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                if (!obstacles[row][col] && !sealed[row][col]) {
                    bb.setMapViewBit(row, col, true);
                }
            }
        }

        assertEquals(100, bb.getExplorationRate());
        assertFalse(bb.hasUnexploredExplorableCells());
        assertTrue(bb.isExplorationComplete());
    }

    @Test
    void sealedBitmapRoundTrip_usesExplicitMapWidth() {
        int size = 100;
        bb.initTaskConfig(Map.of("mapWidth", String.valueOf(size), "mapHeight", String.valueOf(size)));

        boolean[][] sealed = new boolean[size][size];
        sealed[10][50] = true;
        sealed[80][80] = true;
        bb.writeSealedBitmap(sealed, size);

        boolean[][] loaded = bb.loadSealedBitmap();
        assertTrue(loaded[10][50]);
        assertTrue(loaded[80][80]);
        assertFalse(loaded[0][0]);
    }

    @Test
    void clearSimulationState_preservesSimRunStarter() {
        bb.beginSimRun("admin");
        bb.clearSimulationState();
        assertEquals("admin", bb.getSimRunStartedBy().orElse(""));
    }

    @Test
    void clearSimRunMetadata_removesSimRunStarter() {
        bb.beginSimRun("admin");
        bb.clearSimRunMetadata();
        assertTrue(bb.getSimRunStartedBy().isEmpty());
    }

    @Test
    void clearSimulationState_preservesAuthSession() {
        try (Jedis jedis = pool.getResource()) {
            jedis.setex("auth:session:test-token", 1800, "{\"username\":\"admin\"}");
        }
        bb.setCarStatus("Car001", CarStatus.IDLE);
        bb.setMapViewBit(1, 1, true);

        bb.clearSimulationState();

        try (Jedis jedis = pool.getResource()) {
            assertNotNull(jedis.get("auth:session:test-token"));
            assertNull(jedis.get("Car001:Status"));
            assertFalse(bb.getMapViewBit(1, 1));
        }
    }
}
