package com.substation.common.map;

import com.substation.common.model.Point;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * 偏好未探索区域的加权路径搜索：已探索格代价更高，减少在已知走廊空跑。
 */
public final class ExplorationWeightedPathFinder {

    public enum SearchMode {
        WEIGHTED_DIJKSTRA,
        WEIGHTED_ASTAR
    }

    private static final int[][] DIRECTIONS = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};

    private ExplorationWeightedPathFinder() {
    }

    public static List<Point> plan(Point start, Point target, boolean[][] blocked,
                                    boolean[][] explored, int width, int height,
                                    SearchMode mode) {
        if (isOutOfBounds(target, width, height) || start.equals(target)) {
            return List.of();
        }

        int[][] minCost = new int[height][width];
        for (int[] row : minCost) {
            Arrays.fill(row, Integer.MAX_VALUE);
        }
        Point[][] parent = new Point[height][width];
        PriorityQueue<SearchNode> openSet = new PriorityQueue<>(
            Comparator.comparingInt(node -> node.priority(mode, target)));

        minCost[start.y()][start.x()] = 0;
        openSet.add(new SearchNode(start.x(), start.y(), 0));

        while (!openSet.isEmpty()) {
            SearchNode node = openSet.poll();
            if (node.gCost() > minCost[node.row()][node.col()]) {
                continue;
            }

            Point current = new Point(node.col(), node.row());
            if (current.equals(target)) {
                return reconstructPath(parent, start, target);
            }

            expandNeighbors(current, blocked, explored, minCost, parent, openSet, mode, width, height);
        }
        return List.of();
    }

    private static void expandNeighbors(Point current, boolean[][] blocked, boolean[][] explored,
                                         int[][] minCost, Point[][] parent,
                                         PriorityQueue<SearchNode> openSet, SearchMode mode,
                                         int width, int height) {
        int col = current.x();
        int row = current.y();
        for (int[] direction : DIRECTIONS) {
            int nextX = col + direction[0];
            int nextY = row + direction[1];
            if (!isInBounds(nextX, nextY, width, height) || blocked[nextY][nextX]) {
                continue;
            }

            int stepCost = ExplorationPathCosts.stepCost(explored, nextX, nextY);
            int newCost = minCost[row][col] + stepCost;
            if (newCost < minCost[nextY][nextX]) {
                minCost[nextY][nextX] = newCost;
                parent[nextY][nextX] = current;
                openSet.add(new SearchNode(nextX, nextY, newCost));
            }
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

    private static int manhattan(Point a, Point b) {
        return Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y());
    }

    private static boolean isInBounds(int x, int y, int width, int height) {
        return x >= 0 && x < width && y >= 0 && y < height;
    }

    private static boolean isOutOfBounds(Point point, int width, int height) {
        return point.x() < 0 || point.x() >= width || point.y() < 0 || point.y() >= height;
    }

    private record SearchNode(int col, int row, int gCost) {

        int priority(SearchMode mode, Point target) {
            if (mode == SearchMode.WEIGHTED_ASTAR) {
                return gCost + manhattan(new Point(col, row), target);
            }
            return gCost;
        }
    }
}
