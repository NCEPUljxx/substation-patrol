package com.substation.targetplanner;

import com.substation.common.model.CarStatus;
import com.substation.common.model.Point;
import com.substation.common.redis.BlackboardClient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GreedyTargetAllocatorTest {

    private static final String TEST_HOST = "localhost";
    private static final int TEST_PORT = 6379;
    private static final int MAP_SIZE = 30;
    private static final String TEST_CAR = "CarTest";

    private BlackboardClient bb;
    private JedisPool pool;
    private GreedyTargetAllocator allocator;
    private Set<Point> allocated;

    @BeforeEach
    void setUp() {
        pool = new JedisPool(TEST_HOST, TEST_PORT);
        bb = new BlackboardClient(TEST_HOST, TEST_PORT, MAP_SIZE, MAP_SIZE);
        allocator = new GreedyTargetAllocator();
        allocated = new HashSet<>();
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

    @Test
    void allocateReturnsTargetWhenUnexploredCellsExist() {
        Point carPos = new Point(15, 15);
        Optional<Point> target = allocator.allocate(TEST_CAR, carPos, bb, allocated);

        assertTrue(target.isPresent(), "有空闲格子时应该分配目标");
    }

    @Test
    void allocatedTargetIsNotBlocked() {
        bb.setBlock(10, 10, true);

        Point carPos = new Point(15, 15);
        Optional<Point> target = allocator.allocate(TEST_CAR, carPos, bb, allocated);

        assertTrue(target.isPresent());
        Point t = target.get();
        assertFalse(bb.isBlocked(t.y(), t.x()), "目标不应在障碍物上");
    }

    @Test
    void allocatedTargetIsNotExplored() {
        markAllExcept(bb, new Point(20, 20));

        Point carPos = new Point(1, 1);
        Optional<Point> target = allocator.allocate(TEST_CAR, carPos, bb, allocated);

        assertTrue(target.isPresent());
        assertEquals(new Point(20, 20), target.get(), "应分配唯一未探索的格子");
    }

    @Test
    void returnsEmptyWhenAllExplored() {
        markAllExplored(bb, MAP_SIZE, MAP_SIZE);

        Point carPos = new Point(15, 15);
        Optional<Point> target = allocator.allocate(TEST_CAR, carPos, bb, allocated);

        assertTrue(target.isEmpty(), "全部探索完时应无目标");
    }

    @Test
    void returnsEmptyWhenAllCellsBlockedOrExplored() {
        for (int r = 0; r < MAP_SIZE; r++) {
            for (int c = 0; c < MAP_SIZE; c++) {
                bb.setBlock(r, c, true);
            }
        }
        Point carPos = new Point(15, 15);
        Optional<Point> target = allocator.allocate(TEST_CAR, carPos, bb, allocated);

        assertTrue(target.isEmpty());
    }

    @Test
    void pathAwareAssignsReachableTarget() {
        Point carPos = new Point(15, 15);
        Optional<Point> target = allocator.allocate(TEST_CAR, carPos, bb, allocated);

        assertTrue(target.isPresent());
        assertTrue(carPos.manhattanDistance(target.get()) > 0, "应分配与当前位置不同的目标");
    }

    @Test
    void lastCellNoDistanceLimit() {
        markAllExcept(bb, new Point(16, 15));

        Point carPos = new Point(15, 15);
        Optional<Point> target = allocator.allocate(TEST_CAR, carPos, bb, allocated);

        assertTrue(target.isPresent());
        assertEquals(new Point(16, 15), target.get(),
            "最后一个格子应无视距离限制");
    }

    @Test
    void alreadyAllocatedTargetsAreExcluded() {
        Set<Point> preAllocated = new HashSet<>();
        preAllocated.add(new Point(16, 15));

        Point carPos = new Point(15, 15);
        Optional<Point> target = allocator.allocate(TEST_CAR, carPos, bb, preAllocated);

        assertTrue(target.isPresent());
        assertNotEquals(new Point(16, 15), target.get(),
            "不应分配到已被占用的 (16,15)");
    }

    @Test
    void prefersTargetWithLessRouteOverlap() {
        for (int row = 0; row < MAP_SIZE; row++) {
            for (int col = 0; col < MAP_SIZE; col++) {
                bb.setMapViewBit(row, col, true);
            }
        }
        bb.setMapViewBit(15, 15, false);
        bb.setMapViewBit(0, 20, false);

        bb.setCarPosition("Car001", new Point(0, 0));
        bb.setCarStatus("Car001", CarStatus.READY);
        bb.pushRoute("Car001", List.of(
            new Point(1, 0), new Point(2, 0), new Point(3, 0),
            new Point(4, 0), new Point(5, 0), new Point(6, 0),
            new Point(7, 0), new Point(8, 0), new Point(9, 0),
            new Point(10, 0), new Point(11, 0), new Point(12, 0),
            new Point(13, 0), new Point(14, 0), new Point(15, 0),
            new Point(16, 0), new Point(17, 0), new Point(18, 0),
            new Point(19, 0), new Point(20, 0)));

        bb.setCarPosition(TEST_CAR, new Point(0, 29));
        bb.setCarStatus(TEST_CAR, CarStatus.IDLE);

        Optional<Point> target = allocator.allocate(TEST_CAR, new Point(0, 29), bb, allocated);

        assertTrue(target.isPresent());
        assertEquals(new Point(15, 15), target.get(),
            "应避开与 Car001 底行路线重合的目标 (20,0)，优先选 (15,15)");
    }

    @Test
    void highExplorationRatePrefersNearbyClusterOverDistantSingleCell() {
        markAllExplored(bb, MAP_SIZE, MAP_SIZE);

        Point[] clusterCells = {
            new Point(15, 15), new Point(16, 15), new Point(17, 15),
            new Point(15, 16), new Point(16, 16)
        };
        for (Point cell : clusterCells) {
            bb.setMapViewBit(cell.y(), cell.x(), false);
        }
        bb.setMapViewBit(28, 28, false);

        Point carPos = new Point(14, 15);
        Optional<Point> target = allocator.allocate(TEST_CAR, carPos, bb, allocated);

        assertTrue(target.isPresent());
        assertTrue(java.util.Arrays.asList(clusterCells).contains(target.get()),
            "高探索率时应优先分配附近连续未探索区域，而非远处单格");
    }

    @Test
    void prefersInteriorUnexploredOverPerimeterSweep() {
        markAllExplored(bb, MAP_SIZE, MAP_SIZE);
        for (int col = 1; col < MAP_SIZE - 1; col++) {
            bb.setMapViewBit(1, col, false);
        }
        bb.setMapViewBit(15, 15, false);
        bb.setMapViewBit(16, 15, false);
        bb.setMapViewBit(15, 16, false);

        Point carPos = new Point(1, 1);
        bb.setMapViewBit(carPos.y(), carPos.x(), true);
        Optional<Point> target = allocator.allocate(TEST_CAR, carPos, bb, allocated);

        assertTrue(target.isPresent());
        assertTrue(target.get().x() >= 14 && target.get().y() >= 14,
            "高障碍场景应优先深入内部未探索区，而非沿外围兜圈，实际目标=" + target.get());
    }

    @Test
    void prefersNearestFrontierOverDistantPocketEntry() {
        markAllExplored(bb, MAP_SIZE, MAP_SIZE);

        Point nearFrontier = new Point(16, 15);
        Point pocketEntry = new Point(19, 19);
        Point pocketInterior = new Point(21, 20);
        bb.setMapViewBit(nearFrontier.y(), nearFrontier.x(), false);
        bb.setMapViewBit(pocketEntry.y(), pocketEntry.x(), false);
        bb.setMapViewBit(pocketInterior.y(), pocketInterior.x(), false);
        bb.setMapViewBit(20, 19, false);
        bb.setMapViewBit(21, 19, false);
        bb.setMapViewBit(20, 20, false);

        Point carPos = new Point(15, 15);
        bb.setMapViewBit(carPos.y(), carPos.x(), true);
        Optional<Point> target = allocator.allocate(TEST_CAR, carPos, bb, allocated);

        assertTrue(target.isPresent());
        assertEquals(nearFrontier, target.get(),
            "应优先分配最近前沿格，而非远处口袋内部");
    }

    private void markAllExplored(BlackboardClient bb, int width, int height) {
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                bb.setMapViewBit(r, c, true);
            }
        }
    }

    private void markAllExcept(BlackboardClient bb, Point exception) {
        markAllExplored(bb, MAP_SIZE, MAP_SIZE);
        bb.setMapViewBit(exception.y(), exception.x(), false);
    }
}
