package com.substation.targetplanner;

import com.substation.common.map.ExplorationWeightedPathFinder;
import com.substation.common.map.ShortestHopPathFinder;
import com.substation.common.model.Point;
import com.substation.common.redis.BlackboardClient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A+B 联合对比：目标分配走前沿 + 路径规划走加权，
 * 相对「任意未探索目标 + hop 最短路」减少路径上的已探索步数。
 */
class ExplorationEfficiencyComparisonTest {

    private static final int MAP_WIDTH = 12;
    private static final int MAP_HEIGHT = 5;

    private JedisPool pool;
    private BlackboardClient bb;
    private GreedyTargetAllocator allocator;
    private TargetPathEstimator pathEstimator;

    @BeforeEach
    void setUp() {
        pool = new JedisPool("localhost", 6379);
        bb = new BlackboardClient("localhost", 6379, MAP_WIDTH, MAP_HEIGHT);
        allocator = new GreedyTargetAllocator();
        pathEstimator = new TargetPathEstimator();
        try (Jedis jedis = pool.getResource()) {
            jedis.flushDB();
        }
        bb.initTaskConfig(java.util.Map.of(
            "mapWidth", String.valueOf(MAP_WIDTH),
            "mapHeight", String.valueOf(MAP_HEIGHT)));
        paintCorridorMap(bb);
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
    void frontierTargetAndWeightedPathReduceExploredTransit() {
        Point carPos = new Point(0, 1);
        bb.setCarPosition("CarCmp", carPos);
        bb.setMapViewBit(carPos.y(), carPos.x(), true);

        var allocated = new java.util.HashSet<Point>();
        var frontierTarget = allocator.allocate("CarCmp", carPos, bb, allocated);
        assertTrue(frontierTarget.isPresent());

        boolean[][] blocked = bb.loadBlockedMapWithCars();
        boolean[][] explored = bb.loadExploredBitmap();

        List<Point> weightedPath = pathEstimator.planPath(
            carPos, frontierTarget.get(), blocked, explored, MAP_WIDTH, MAP_HEIGHT);
        List<Point> hopPath = ShortestHopPathFinder.plan(
            carPos, frontierTarget.get(), blocked, MAP_WIDTH, MAP_HEIGHT);

        int weightedExplored = countExploredOnPath(weightedPath, explored);
        int hopExplored = countExploredOnPath(hopPath, explored);

        assertTrue(weightedExplored <= hopExplored,
            "加权路径已探索步数应不多于 hop 最短路: weighted="
                + weightedExplored + ", hop=" + hopExplored);
        assertTrue(com.substation.common.map.FrontierCellFinder.isFrontier(
            frontierTarget.get().x(), frontierTarget.get().y(), explored,
            bb.loadObstacleBitmap(), bb.loadSealedBitmap(), MAP_WIDTH, MAP_HEIGHT),
            "分配目标应为前沿格");
    }

    @Test
    void weightedPathBeatsHopPathOnSameTarget() {
        Point start = new Point(0, 1);
        Point target = new Point(6, 1);
        boolean[][] blocked = bb.loadBlockedMapWithCars();
        boolean[][] explored = bb.loadExploredBitmap();

        List<Point> hopPath = ShortestHopPathFinder.plan(start, target, blocked, MAP_WIDTH, MAP_HEIGHT);
        List<Point> weightedPath = ExplorationWeightedPathFinder.plan(
            start, target, blocked, explored, MAP_WIDTH, MAP_HEIGHT,
            ExplorationWeightedPathFinder.SearchMode.WEIGHTED_DIJKSTRA);

        assertTrue(countExploredOnPath(hopPath, explored) > countExploredOnPath(weightedPath, explored));
    }

    private static void paintCorridorMap(BlackboardClient bb) {
        for (int col = 0; col < MAP_WIDTH; col++) {
            bb.setMapViewBit(0, col, true);
            bb.setMapViewBit(1, col, col < MAP_WIDTH - 1);
        }
    }

    private static int countExploredOnPath(List<Point> path, boolean[][] explored) {
        int count = 0;
        for (Point step : path) {
            if (explored[step.y()][step.x()]) {
                count++;
            }
        }
        return count;
    }
}
