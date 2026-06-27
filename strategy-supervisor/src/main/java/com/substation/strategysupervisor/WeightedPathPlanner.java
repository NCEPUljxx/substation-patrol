package com.substation.strategysupervisor;

import com.substation.common.model.Point;
import com.substation.common.redis.BlackboardClient;

import java.util.*;

/**
 * 加权路径搜索器：已探索格子代价=3，未探索代价=1。
 * 使用加权Dijkstra（PriorityQueue按总代价排序），主动偏好未探索区域。
 */
final class WeightedPathPlanner implements Comparator<int[]> {

    private static final int[][] DIRECTIONS = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
    /** 已探索格子的通行代价系数 */
    static final int EXPLORED_PENALTY = 3;
    /** 未探索格子的通行代价 */
    private static final int UNEXPLORED_COST = 1;

    @Override
    public int compare(int[] a, int[] b) {
        return Integer.compare(a[2], b[2]);
    }

    List<Point> plan(Point start, Point target, BlackboardClient bb, List<Point> currentRoute) {
        int width = bb.getMapWidth();
        int height = bb.getMapHeight();

        if (isOutOfBounds(start, width, height) || isOutOfBounds(target, width, height)) {
            return currentRoute;
        }

        boolean[][] blocked = bb.loadBlockedMapWithCars();
        boolean[][] explored = bb.loadExploredBitmap();
        return planOnBitmap(start, target, blocked, explored, width, height, currentRoute);
    }

    List<Point> planOnBitmap(Point start, Point target, boolean[][] blocked,
                              boolean[][] explored, int width, int height,
                              List<Point> currentRoute) {
        int[][] minCost = new int[height][width];
        for (int[] row : minCost) {
            Arrays.fill(row, Integer.MAX_VALUE);
        }
        Point[][] parent = new Point[height][width];

        PriorityQueue<int[]> openSet = new PriorityQueue<>(this);
        minCost[start.y()][start.x()] = 0;
        openSet.add(new int[]{start.x(), start.y(), manhattan(start, target)});

        while (!openSet.isEmpty()) {
            int[] cur = openSet.poll();
            int cx = cur[0];
            int cy = cur[1];
            Point current = new Point(cx, cy);

            if (current.equals(target)) {
                return reconstructPath(parent, start, target);
            }

            for (int[] dir : DIRECTIONS) {
                int nx = cx + dir[0];
                int ny = cy + dir[1];
                if (!isInBounds(nx, ny, width, height) || blocked[ny][nx]) {
                    continue;
                }

                int stepCost = explored[ny][nx] ? EXPLORED_PENALTY : UNEXPLORED_COST;
                int newCost = minCost[cy][cx] + stepCost;
                if (newCost < minCost[ny][nx]) {
                    minCost[ny][nx] = newCost;
                    parent[ny][nx] = current;
                    openSet.add(new int[]{nx, ny, newCost + manhattan(new Point(nx, ny), target)});
                }
            }
        }
        return currentRoute;
    }

    private List<Point> reconstructPath(Point[][] parent, Point start, Point target) {
        List<Point> path = new ArrayList<>();
        Point current = target;
        while (current != null && !current.equals(start)) {
            path.add(current);
            current = parent[current.y()][current.x()];
        }
        Collections.reverse(path);
        return Collections.unmodifiableList(path);
    }

    private static int manhattan(Point a, Point b) {
        return Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y());
    }

    private static boolean isInBounds(int x, int y, int w, int h) {
        return x >= 0 && x < w && y >= 0 && y < h;
    }

    private static boolean isOutOfBounds(Point p, int w, int h) {
        return p.x() < 0 || p.x() >= w || p.y() < 0 || p.y() >= h;
    }
}
