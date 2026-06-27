package com.substation.common.map;

import com.substation.common.model.Point;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnPositionSelectorTest {

    private static final int MAP_SIZE = 10;

    @Test
    void prefersUnexploredRegionOverExploredCluster() {
        boolean[][] obstacles = emptyGrid(MAP_SIZE);
        boolean[][] explored = emptyGrid(MAP_SIZE);
        boolean[][] occupied = emptyGrid(MAP_SIZE);
        boolean[][] sealed = emptyGrid(MAP_SIZE);

        for (int row = 1; row <= 4; row++) {
            for (int col = 1; col <= 4; col++) {
                explored[row][col] = true;
            }
        }

        Optional<Point> spawn = SpawnPositionSelector.selectBest(
            obstacles, explored, occupied, sealed, 1, new Random(7));

        assertTrue(spawn.isPresent());
        assertTrue(spawn.get().x() >= 5 || spawn.get().y() >= 5,
            "应偏向未探索区域，实际: " + spawn.get());
    }

    @Test
    void keepsDistanceFromExistingCars() {
        boolean[][] obstacles = emptyGrid(MAP_SIZE);
        boolean[][] explored = emptyGrid(MAP_SIZE);
        boolean[][] occupied = emptyGrid(MAP_SIZE);
        boolean[][] sealed = emptyGrid(MAP_SIZE);
        occupied[1][1] = true;

        Optional<Point> spawn = SpawnPositionSelector.selectBest(
            obstacles, explored, occupied, sealed, 1, new Random(3));

        assertTrue(spawn.isPresent());
        int distance = Math.abs(spawn.get().x() - 1) + Math.abs(spawn.get().y() - 1);
        assertTrue(distance >= 3, "应与已有车保持距离，实际距离=" + distance);
    }

    @Test
    void sequentialSpawnsUseDifferentCells() {
        boolean[][] obstacles = emptyGrid(MAP_SIZE);
        boolean[][] explored = emptyGrid(MAP_SIZE);
        boolean[][] occupied = emptyGrid(MAP_SIZE);
        boolean[][] sealed = emptyGrid(MAP_SIZE);
        Random random = new Random(11);

        Point first = SpawnPositionSelector.selectBest(
            obstacles, explored, occupied, sealed, 1, random).orElseThrow();
        occupied[first.y()][first.x()] = true;

        Point second = SpawnPositionSelector.selectBest(
            obstacles, explored, occupied, sealed, 1, random).orElseThrow();

        assertNotEquals(first, second);
    }

    @Test
    void returnsEmptyWhenNoWalkableCell() {
        boolean[][] obstacles = filledGrid(MAP_SIZE);
        boolean[][] explored = emptyGrid(MAP_SIZE);
        boolean[][] occupied = emptyGrid(MAP_SIZE);
        boolean[][] sealed = emptyGrid(MAP_SIZE);

        Optional<Point> spawn = SpawnPositionSelector.selectBest(
            obstacles, explored, occupied, sealed, 1, new Random(1));

        assertTrue(spawn.isEmpty());
    }

    private static boolean[][] emptyGrid(int size) {
        return new boolean[size][size];
    }

    private static boolean[][] filledGrid(int size) {
        boolean[][] grid = new boolean[size][size];
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                grid[row][col] = true;
            }
        }
        return grid;
    }
}
