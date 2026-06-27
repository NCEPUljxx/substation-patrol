package com.substation.car;

import com.substation.common.model.CarStatus;
import com.substation.common.model.Point;
import com.substation.common.redis.BlackboardClient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CarMainTest {

    private static final String TEST_HOST = "localhost";
    private static final int TEST_PORT = 6379;

    private BlackboardClient bb;
    private JedisPool pool;

    @BeforeEach
    void setUp() {
        pool = new JedisPool(TEST_HOST, TEST_PORT);
        bb = new BlackboardClient(TEST_HOST, TEST_PORT, 30, 30);
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

    @Test
    void findSpawnPosition_respectsConfiguredMapSize() {
        bb.initTaskConfig(Map.of("mapWidth", "10", "mapHeight", "10"));

        Optional<Point> spawn = CarMain.findSpawnPosition(bb, "Car004", 10, 10);

        assertTrue(spawn.isPresent());
        assertTrue(spawn.get().x() >= 0 && spawn.get().x() < 10);
        assertTrue(spawn.get().y() >= 0 && spawn.get().y() < 10);
    }

    @Test
    void isDynamicAdd_recognizesFlag() {
        assertTrue(CarMain.isDynamicAdd(new String[]{"Car004", "--dynamic"}));
        assertFalse(CarMain.isDynamicAdd(new String[]{"Car001"}));
    }

    @Test
    void findSpawnPosition_prefersUnexploredArea() {
        bb.initTaskConfig(Map.of("mapWidth", "10", "mapHeight", "10"));
        bb.setCarPosition("Car001", new Point(1, 1));
        bb.setCarStatus("Car001", CarStatus.IDLE);
        for (int row = 0; row < 5; row++) {
            for (int col = 0; col < 5; col++) {
                bb.setMapViewBit(row, col, true);
            }
        }

        Optional<Point> spawn = CarMain.findSpawnPosition(bb, "Car002", 10, 10);

        assertTrue(spawn.isPresent());
        assertTrue(spawn.get().x() >= 4 || spawn.get().y() >= 4,
            "应偏向未探索区域，实际: " + spawn.get());
    }

    @Test
    void findSpawnPosition_avoidsOccupiedCells() {
        bb.initTaskConfig(Map.of("mapWidth", "10", "mapHeight", "10"));
        bb.setCarPosition("Car001", new Point(1, 1));
        bb.setCarStatus("Car001", CarStatus.IDLE);

        Optional<Point> spawn = CarMain.findSpawnPosition(bb, "Car002", 10, 10);

        assertTrue(spawn.isPresent());
        assertFalse(spawn.get().equals(new Point(1, 1)));
    }
}
