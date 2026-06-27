package com.substation.common.map;

import com.substation.common.model.Point;

import java.util.ArrayList;
import java.util.List;

/** 查找与已探索区相邻的未探索前沿格（frontier） */
public final class FrontierCellFinder {

    private static final int[][] DIRECTIONS = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};

    private FrontierCellFinder() {
    }

    public static List<Point> findFrontierCells(boolean[][] explored, boolean[][] obstacles,
                                                 boolean[][] sealed, int width, int height) {
        List<Point> frontiers = new ArrayList<>();
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                if (!isFrontierCandidate(col, row, explored, obstacles, sealed)) {
                    continue;
                }
                if (hasExploredNeighbor(col, row, explored, obstacles, width, height)) {
                    frontiers.add(new Point(col, row));
                }
            }
        }
        return frontiers;
    }

    public static boolean isFrontier(int col, int row, boolean[][] explored, boolean[][] obstacles,
                                      boolean[][] sealed, int width, int height) {
        return isFrontierCandidate(col, row, explored, obstacles, sealed)
            && hasExploredNeighbor(col, row, explored, obstacles, width, height);
    }

    private static boolean isFrontierCandidate(int col, int row, boolean[][] explored,
                                                boolean[][] obstacles, boolean[][] sealed) {
        return !explored[row][col] && !obstacles[row][col] && !sealed[row][col];
    }

    private static boolean hasExploredNeighbor(int col, int row, boolean[][] explored,
                                                boolean[][] obstacles, int width, int height) {
        for (int[] direction : DIRECTIONS) {
            int nextX = col + direction[0];
            int nextY = row + direction[1];
            if (!isInBounds(nextX, nextY, width, height) || obstacles[nextY][nextX]) {
                continue;
            }
            if (explored[nextY][nextX]) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInBounds(int x, int y, int width, int height) {
        return x >= 0 && x < width && y >= 0 && y < height;
    }
}
