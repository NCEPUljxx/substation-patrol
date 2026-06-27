package com.substation.common.map;

import com.substation.common.model.Point;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnexploredClusterFinderTest {

    @Test
    void findsSeparateClusters() {
        boolean[][] explored = {
            {true, true, true, true, true},
            {true, false, true, false, true},
            {true, true, true, true, true},
            {true, false, true, false, true},
            {true, true, true, true, true}
        };
        boolean[][] obstacles = new boolean[5][5];
        boolean[][] sealed = new boolean[5][5];

        var clusters = UnexploredClusterFinder.findClusters(explored, obstacles, sealed);

        assertEquals(4, clusters.size());
        assertTrue(clusters.stream().allMatch(cluster -> cluster.cellCount() == 1));
    }

    @Test
    void mergesConnectedUnexploredCells() {
        boolean[][] explored = {
            {true, true, true, true, true},
            {true, false, false, false, true},
            {true, false, false, false, true},
            {true, false, false, false, true},
            {true, true, true, true, true}
        };
        boolean[][] obstacles = new boolean[5][5];
        boolean[][] sealed = new boolean[5][5];

        var clusters = UnexploredClusterFinder.findClusters(explored, obstacles, sealed);

        assertEquals(1, clusters.size());
        assertEquals(9, clusters.get(0).cellCount());
    }

    @Test
    void excludesObstaclesAndSealedCells() {
        boolean[][] explored = {
            {true, false},
            {false, false}
        };
        boolean[][] obstacles = {
            {false, true},
            {false, false}
        };
        boolean[][] sealed = {
            {false, false},
            {true, false}
        };

        var clusters = UnexploredClusterFinder.findClusters(explored, obstacles, sealed);

        assertEquals(1, clusters.size());
        assertEquals(new Point(1, 1), clusters.get(0).cells().get(0));
    }
}
