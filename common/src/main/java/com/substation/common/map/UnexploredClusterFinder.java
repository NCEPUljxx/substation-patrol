package com.substation.common.map;

import com.substation.common.model.Point;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/** 将未探索格子按四邻接连通性划分为若干区域块 */
public final class UnexploredClusterFinder {

    private static final int[][] CARDINAL_DELTAS = {
        {0, -1}, {0, 1}, {-1, 0}, {1, 0}
    };

    private UnexploredClusterFinder() {
    }

    public static List<UnexploredCluster> findClusters(boolean[][] explored,
                                                        boolean[][] obstacles,
                                                        boolean[][] sealed) {
        int height = explored.length;
        int width = explored[0].length;
        boolean[][] visited = new boolean[height][width];
        List<UnexploredCluster> clusters = new ArrayList<>();

        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                if (!isUnexplored(explored, obstacles, sealed, row, col) || visited[row][col]) {
                    continue;
                }
                clusters.add(floodFillCluster(explored, obstacles, sealed, visited, col, row));
            }
        }
        return clusters;
    }

    private static UnexploredCluster floodFillCluster(boolean[][] explored, boolean[][] obstacles,
                                                     boolean[][] sealed, boolean[][] visited,
                                                     int startCol, int startRow) {
        List<Point> cells = new ArrayList<>();
        Queue<int[]> queue = new ArrayDeque<>();
        visited[startRow][startCol] = true;
        queue.add(new int[]{startCol, startRow});

        while (!queue.isEmpty()) {
            int[] cell = queue.poll();
            cells.add(new Point(cell[0], cell[1]));
            for (int[] delta : CARDINAL_DELTAS) {
                int nextCol = cell[0] + delta[0];
                int nextRow = cell[1] + delta[1];
                if (!isInside(nextCol, nextRow, explored)
                        || visited[nextRow][nextCol]
                        || !isUnexplored(explored, obstacles, sealed, nextRow, nextCol)) {
                    continue;
                }
                visited[nextRow][nextCol] = true;
                queue.add(new int[]{nextCol, nextRow});
            }
        }
        return new UnexploredCluster(cells);
    }

    private static boolean isUnexplored(boolean[][] explored, boolean[][] obstacles,
                                         boolean[][] sealed, int row, int col) {
        return !explored[row][col] && !obstacles[row][col] && !sealed[row][col];
    }

    private static boolean isInside(int col, int row, boolean[][] grid) {
        return col >= 0 && col < grid[0].length && row >= 0 && row < grid.length;
    }
}
