package com.substation.common.map;

import com.substation.common.model.Point;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

/** 从车辆起点 BFS，标记障碍物包围、小车永远无法进入的格子 */
public final class ReachabilityAnalyzer {

    private static final int[][] CARDINAL_DELTAS = {
        {0, -1}, {0, 1}, {-1, 0}, {1, 0}
    };

    private ReachabilityAnalyzer() {
    }

    /**
     * @param obstacles 障碍位图，true 表示不可通行
     * @param startPoints 车辆初始位置（col=x, row=y）
     * @return sealed[row][col]，true 表示被障碍物包裹的不可达空格
     */
    public static boolean[][] findSealedFreeCells(boolean[][] obstacles,
                                                   List<Point> startPoints) {
        int height = obstacles.length;
        int width = obstacles[0].length;
        boolean[][] reachable = floodFillReachable(obstacles, startPoints, width, height);
        return markSealedFreeCells(obstacles, reachable, width, height);
    }

    private static boolean[][] floodFillReachable(boolean[][] obstacles,
                                                   List<Point> startPoints,
                                                   int width, int height) {
        boolean[][] reachable = new boolean[height][width];
        Queue<int[]> queue = new ArrayDeque<>();

        for (Point start : startPoints) {
            enqueueIfWalkable(start.x(), start.y(), obstacles, reachable, queue);
        }

        while (!queue.isEmpty()) {
            int[] cell = queue.poll();
            for (int[] delta : CARDINAL_DELTAS) {
                enqueueIfWalkable(
                    cell[0] + delta[0], cell[1] + delta[1],
                    obstacles, reachable, queue);
            }
        }
        return reachable;
    }

    private static void enqueueIfWalkable(int col, int row, boolean[][] obstacles,
                                           boolean[][] reachable, Queue<int[]> queue) {
        if (!isInsideMap(col, row, obstacles[0].length, obstacles.length)) {
            return;
        }
        if (obstacles[row][col] || reachable[row][col]) {
            return;
        }
        reachable[row][col] = true;
        queue.add(new int[]{col, row});
    }

    private static boolean[][] markSealedFreeCells(boolean[][] obstacles,
                                                    boolean[][] reachable,
                                                    int width, int height) {
        boolean[][] sealed = new boolean[height][width];
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                if (!obstacles[row][col] && !reachable[row][col]) {
                    sealed[row][col] = true;
                }
            }
        }
        return sealed;
    }

    private static boolean isInsideMap(int col, int row, int width, int height) {
        return col >= 0 && col < width && row >= 0 && row < height;
    }
}
