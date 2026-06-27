package com.substation.common.map;

import com.substation.common.model.Point;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;

/** 纯 BFS 最短路（每格代价 1），供对比测试与基线评估 */
public final class ShortestHopPathFinder {

    private static final int[][] DIRECTIONS = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};

    private ShortestHopPathFinder() {
    }

    public static List<Point> plan(Point start, Point target, boolean[][] blocked,
                                    int width, int height) {
        if (isOutOfBounds(target, width, height) || start.equals(target)) {
            return List.of();
        }

        boolean[][] visited = copyBlocked(blocked, width, height);
        Point[][] parent = new Point[height][width];
        Queue<Point> queue = new ArrayDeque<>();

        visited[start.y()][start.x()] = true;
        queue.add(start);

        while (!queue.isEmpty()) {
            Point current = queue.poll();
            if (current.equals(target)) {
                return reconstructPath(parent, start, target);
            }
            expandNeighbors(current, visited, parent, queue, width, height);
        }
        return List.of();
    }

    private static void expandNeighbors(Point current, boolean[][] visited, Point[][] parent,
                                         Queue<Point> queue, int width, int height) {
        for (int[] direction : DIRECTIONS) {
            int nextX = current.x() + direction[0];
            int nextY = current.y() + direction[1];
            if (!isInBounds(nextX, nextY, width, height) || visited[nextY][nextX]) {
                continue;
            }
            visited[nextY][nextX] = true;
            parent[nextY][nextX] = current;
            queue.add(new Point(nextX, nextY));
        }
    }

    private static List<Point> reconstructPath(Point[][] parent, Point start, Point target) {
        List<Point> path = new ArrayList<>();
        Point current = target;
        while (current != null && !current.equals(start)) {
            path.add(current);
            current = parent[current.y()][current.x()];
        }
        Collections.reverse(path);
        return Collections.unmodifiableList(path);
    }

    private static boolean[][] copyBlocked(boolean[][] blocked, int width, int height) {
        boolean[][] copy = new boolean[height][width];
        for (int row = 0; row < height; row++) {
            System.arraycopy(blocked[row], 0, copy[row], 0, width);
        }
        return copy;
    }

    private static boolean isInBounds(int x, int y, int width, int height) {
        return x >= 0 && x < width && y >= 0 && y < height;
    }

    private static boolean isOutOfBounds(Point point, int width, int height) {
        return point.x() < 0 || point.x() >= width || point.y() < 0 || point.y() >= height;
    }
}
