package com.substation.common.map;

import com.substation.common.model.Point;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/** 按「周围未探索格数量 + 与已有车的距离」选择出生点 */
public final class SpawnPositionSelector {

    private static final int NEIGHBOR_RADIUS = 4;
    private static final int UNEXPLORED_CELL_WEIGHT = 10;
    private static final int CAR_SEPARATION_WEIGHT = 5;

    private SpawnPositionSelector() {
    }

    public static Optional<Point> selectBest(boolean[][] obstacles,
                                              boolean[][] explored,
                                              boolean[][] occupiedByCars,
                                              boolean[][] sealed,
                                              int margin,
                                              Random random) {
        int mapHeight = obstacles.length;
        int mapWidth = obstacles[0].length;
        List<Point> candidates = collectCandidates(
            obstacles, explored, occupiedByCars, sealed, margin, mapWidth, mapHeight);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        int bestScore = Integer.MIN_VALUE;
        List<Point> bestCandidates = new ArrayList<>();
        for (Point candidate : candidates) {
            int score = scoreCandidate(candidate, obstacles, explored, occupiedByCars, sealed);
            if (score > bestScore) {
                bestScore = score;
                bestCandidates.clear();
                bestCandidates.add(candidate);
            } else if (score == bestScore) {
                bestCandidates.add(candidate);
            }
        }
        Point chosen = bestCandidates.get(random.nextInt(bestCandidates.size()));
        return Optional.of(chosen);
    }

    private static List<Point> collectCandidates(boolean[][] obstacles,
                                                boolean[][] explored,
                                                boolean[][] occupiedByCars,
                                                boolean[][] sealed,
                                                int margin,
                                                int mapWidth,
                                                int mapHeight) {
        List<Point> candidates = new ArrayList<>();
        int maxRow = mapHeight - margin;
        int maxCol = mapWidth - margin;
        if (maxRow <= margin || maxCol <= margin) {
            return candidates;
        }
        for (int row = margin; row < maxRow; row++) {
            for (int col = margin; col < maxCol; col++) {
                if (isSpawnable(obstacles, explored, occupiedByCars, sealed, col, row)) {
                    candidates.add(new Point(col, row));
                }
            }
        }
        return candidates;
    }

    private static int scoreCandidate(Point candidate,
                                       boolean[][] obstacles,
                                       boolean[][] explored,
                                       boolean[][] occupiedByCars,
                                       boolean[][] sealed) {
        int unexploredNearby = countUnexploredNearby(
            candidate, obstacles, explored, occupiedByCars, sealed);
        int nearestCarDistance = nearestOccupiedDistance(candidate, occupiedByCars);
        return unexploredNearby * UNEXPLORED_CELL_WEIGHT
            + nearestCarDistance * CAR_SEPARATION_WEIGHT;
    }

    private static int countUnexploredNearby(Point center,
                                              boolean[][] obstacles,
                                              boolean[][] explored,
                                              boolean[][] occupiedByCars,
                                              boolean[][] sealed) {
        int count = 0;
        for (int row = center.y() - NEIGHBOR_RADIUS; row <= center.y() + NEIGHBOR_RADIUS; row++) {
            for (int col = center.x() - NEIGHBOR_RADIUS; col <= center.x() + NEIGHBOR_RADIUS; col++) {
                if (!isInsideMap(col, row, obstacles)) {
                    continue;
                }
                if (!isWalkable(obstacles, sealed, col, row)) {
                    continue;
                }
                if (occupiedByCars[row][col] || explored[row][col]) {
                    continue;
                }
                count++;
            }
        }
        return count;
    }

    private static int nearestOccupiedDistance(Point candidate, boolean[][] occupiedByCars) {
        int mapHeight = occupiedByCars.length;
        int mapWidth = occupiedByCars[0].length;
        int bestDistance = Integer.MAX_VALUE;
        boolean hasOccupied = false;

        for (int row = 0; row < mapHeight; row++) {
            for (int col = 0; col < mapWidth; col++) {
                if (!occupiedByCars[row][col]) {
                    continue;
                }
                hasOccupied = true;
                int distance = manhattanDistance(candidate.x(), candidate.y(), col, row);
                bestDistance = Math.min(bestDistance, distance);
            }
        }
        return hasOccupied ? bestDistance : mapWidth + mapHeight;
    }

    private static boolean isSpawnable(boolean[][] obstacles,
                                        boolean[][] explored,
                                        boolean[][] occupiedByCars,
                                        boolean[][] sealed,
                                        int col,
                                        int row) {
        return isWalkable(obstacles, sealed, col, row)
            && !occupiedByCars[row][col];
    }

    private static boolean isWalkable(boolean[][] obstacles, boolean[][] sealed, int col, int row) {
        return !obstacles[row][col] && !sealed[row][col];
    }

    private static boolean isInsideMap(int col, int row, boolean[][] grid) {
        return col >= 0 && col < grid[0].length && row >= 0 && row < grid.length;
    }

    private static int manhattanDistance(int x1, int y1, int x2, int y2) {
        return Math.abs(x1 - x2) + Math.abs(y1 - y2);
    }
}
