package com.substation.targetplanner;

import com.substation.common.map.FrontierCellFinder;
import com.substation.common.map.UnexploredCluster;
import com.substation.common.map.UnexploredClusterFinder;
import com.substation.common.model.Point;
import com.substation.common.redis.BlackboardClient;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class GreedyTargetAllocator {

    /** 探索率不低于此值时按未探索区域块分配目标 */
    private static final int CLUSTER_MODE_RATE_THRESHOLD = 85;
    /** 路径与他人路线每重合一格的惩罚步数 */
    private static final int OVERLAP_PENALTY = 6;
    /** 区域块内每格的收益权重 */
    private static final int CLUSTER_SIZE_WEIGHT = 4;
    /** 路径上每经过一个未探索格的收益权重 */
    private static final int UNEXPLORED_PATH_WEIGHT = 2;
    /** 目标距地图边缘越远，分配得分越低（鼓励深入内部） */
    private static final int INTERIOR_DEPTH_WEIGHT = 4;
    /** 贴边目标额外惩罚，避免沿外围兜圈 */
    private static final int PERIMETER_TARGET_PENALTY = 20;
    /** 路径未探索收益只统计距边缘至少此深度的格子 */
    private static final int MIN_INTERIOR_DEPTH_FOR_PATH_BONUS = 2;
    /** 候选过多时，按 BFS 可达距离保留的前 N 个再精细评分 */
    private static final int MAX_CANDIDATES_TO_SCORE = 40;

    private final TargetPathEstimator pathEstimator = new TargetPathEstimator();

    Optional<Point> allocate(String carId, Point currentPosition, BlackboardClient bb,
                             Set<Point> alreadyAllocated) {
        if (bb.getExplorationRate() >= CLUSTER_MODE_RATE_THRESHOLD) {
            return allocateByCluster(carId, currentPosition, bb, alreadyAllocated);
        }
        return allocateByCell(carId, currentPosition, bb, alreadyAllocated);
    }

    private Optional<Point> allocateByCluster(String carId, Point currentPos, BlackboardClient bb,
                                               Set<Point> alreadyAllocated) {
        boolean[][] explored = bb.loadExploredBitmap();
        boolean[][] obstacles = bb.loadObstacleBitmap();
        boolean[][] sealed = bb.loadSealedBitmap();
        boolean[][] blocked = bb.loadBlockedMapWithCars();
        int width = bb.getMapWidth();
        int height = bb.getMapHeight();

        List<UnexploredCluster> clusters = UnexploredClusterFinder.findClusters(
            explored, obstacles, sealed);
        Set<Point> overlapCells = collectOverlapCells(carId, bb, alreadyAllocated);

        Point bestTarget = null;
        int bestScore = Integer.MAX_VALUE;

        for (UnexploredCluster cluster : clusters) {
            Optional<UnexploredCluster> available = cluster.withoutAllocated(alreadyAllocated);
            if (available.isEmpty()) {
                continue;
            }
            ClusterCandidate candidate = scoreCluster(
                currentPos, available.get(), blocked, explored, obstacles, sealed, width, height, overlapCells);
            if (candidate != null && candidate.score() < bestScore) {
                bestScore = candidate.score();
                bestTarget = candidate.target();
            }
        }

        if (bestTarget == null) {
            return Optional.empty();
        }
        alreadyAllocated.add(bestTarget);
        return Optional.of(bestTarget);
    }

    private ClusterCandidate scoreCluster(Point currentPos, UnexploredCluster cluster,
                                           boolean[][] blocked, boolean[][] explored,
                                           boolean[][] obstacles, boolean[][] sealed,
                                           int width, int height, Set<Point> overlapCells) {
        int valuableClusterSize = countInteriorCellsInCluster(
            cluster, explored, obstacles, sealed, width, height);
        Point bestEntry = null;
        int bestEntryScore = Integer.MAX_VALUE;

        for (Point cell : selectClusterEntryCandidates(cluster, explored, obstacles, sealed, width, height)) {
            int score = scoreTargetCell(
                currentPos, cell, valuableClusterSize, blocked, explored, width, height, overlapCells);
            if (score < bestEntryScore) {
                bestEntryScore = score;
                bestEntry = cell;
            }
        }

        if (bestEntry == null || bestEntryScore == Integer.MAX_VALUE) {
            return null;
        }
        return new ClusterCandidate(bestEntry, bestEntryScore);
    }

    private int scoreTargetCell(Point currentPos, Point target, int clusterSize,
                                 boolean[][] blocked, boolean[][] explored,
                                 int width, int height, Set<Point> overlapCells) {
        List<Point> path = pathEstimator.planPath(
            currentPos, target, blocked, explored, width, height);
        if (path.isEmpty() && !currentPos.equals(target)) {
            return Integer.MAX_VALUE;
        }
        int overlap = countSharedCells(path, overlapCells);
        int unexploredOnPath = countInteriorUnexploredOnPath(path, explored, width, height);
        int interiorDepth = depthFromMapEdge(target, width, height);
        int perimeterPenalty = interiorDepth <= 1 ? PERIMETER_TARGET_PENALTY : 0;
        return path.size()
            + OVERLAP_PENALTY * overlap
            + perimeterPenalty
            - CLUSTER_SIZE_WEIGHT * clusterSize
            - UNEXPLORED_PATH_WEIGHT * unexploredOnPath
            - INTERIOR_DEPTH_WEIGHT * interiorDepth;
    }

    private Optional<Point> allocateByCell(String carId, Point currentPosition, BlackboardClient bb,
                                            Set<Point> alreadyAllocated) {
        int mapWidth = bb.getMapWidth();
        int mapHeight = bb.getMapHeight();

        List<Point> candidates = collectFrontierFirstCandidates(bb, mapWidth, mapHeight);
        candidates.removeAll(alreadyAllocated);
        candidates.removeIf(cell -> cell.equals(currentPosition));

        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        Optional<Point> chosen = selectByPathOverlapScore(
            carId, currentPosition, candidates, bb, alreadyAllocated);
        chosen.ifPresent(alreadyAllocated::add);
        return chosen;
    }

    private List<Point> collectFrontierFirstCandidates(BlackboardClient bb, int width, int height) {
        boolean[][] explored = bb.loadExploredBitmap();
        boolean[][] obstacles = bb.loadObstacleBitmap();
        boolean[][] sealed = bb.loadSealedBitmap();
        List<Point> frontiers = FrontierCellFinder.findFrontierCells(
            explored, obstacles, sealed, width, height);
        if (!frontiers.isEmpty()) {
            return frontiers;
        }
        return collectUnexploredCells(explored, obstacles, sealed, width, height);
    }

    private List<Point> collectUnexploredCells(boolean[][] explored, boolean[][] obstacles,
                                                boolean[][] sealed, int width, int height) {
        List<Point> cells = new ArrayList<>();
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                if (!explored[row][col] && !obstacles[row][col] && !sealed[row][col]) {
                    cells.add(new Point(col, row));
                }
            }
        }
        return cells;
    }

    private Optional<Point> selectByPathOverlapScore(String carId, Point currentPos,
                                                      List<Point> candidates,
                                                      BlackboardClient bb,
                                                      Set<Point> alreadyAllocated) {
        int width = bb.getMapWidth();
        int height = bb.getMapHeight();
        boolean[][] blocked = bb.loadBlockedMapWithCars();
        boolean[][] explored = bb.loadExploredBitmap();
        boolean[][] obstacles = bb.loadObstacleBitmap();
        boolean[][] sealed = bb.loadSealedBitmap();
        Set<Point> overlapCells = collectOverlapCells(carId, bb, alreadyAllocated);
        List<Point> pool = narrowCandidatePool(
            currentPos, candidates, blocked, explored, width, height);

        Point bestTarget = null;
        int bestScore = Integer.MAX_VALUE;

        for (Point candidate : pool) {
            int clusterSize = countInteriorConnectedUnexplored(
                candidate, explored, obstacles, sealed, width, height);
            int score = scoreTargetCell(
                currentPos, candidate, clusterSize, blocked, explored, width, height, overlapCells);
            if (score < bestScore) {
                bestScore = score;
                bestTarget = candidate;
            }
        }

        if (bestTarget != null) {
            return Optional.of(bestTarget);
        }
        return fallbackFarthestReachable(currentPos, candidates, blocked, explored, width, height);
    }

    private Set<Point> collectOverlapCells(String carId, BlackboardClient bb,
                                            Set<Point> alreadyAllocated) {
        Set<Point> cells = new HashSet<>(alreadyAllocated);
        for (String otherId : bb.discoverCarIds()) {
            if (otherId.equals(carId)) {
                continue;
            }
            bb.getCarRoute(otherId).forEach(cells::add);
            bb.getCarTarget(otherId).ifPresent(cells::add);
        }
        return cells;
    }

    private List<Point> narrowCandidatePool(Point currentPos, List<Point> candidates,
                                             boolean[][] blocked, boolean[][] explored,
                                             int width, int height) {
        if (candidates.size() <= MAX_CANDIDATES_TO_SCORE) {
            return candidates;
        }
        List<Point> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingInt((Point candidate) ->
            reachablePathLength(currentPos, candidate, blocked, explored, width, height)).reversed());
        return sorted.subList(0, MAX_CANDIDATES_TO_SCORE);
    }

    private int reachablePathLength(Point start, Point target, boolean[][] blocked,
                                     boolean[][] explored, int width, int height) {
        List<Point> path = pathEstimator.planPath(start, target, blocked, explored, width, height);
        return path.isEmpty() ? -1 : path.size();
    }

    private static int countSharedCells(List<Point> path, Set<Point> overlapCells) {
        int shared = 0;
        for (Point step : path) {
            if (overlapCells.contains(step)) {
                shared++;
            }
        }
        return shared;
    }

    private static int countInteriorUnexploredOnPath(List<Point> path, boolean[][] explored,
                                                      int width, int height) {
        int count = 0;
        for (Point step : path) {
            if (explored[step.y()][step.x()]) {
                continue;
            }
            if (depthFromMapEdge(step, width, height) >= MIN_INTERIOR_DEPTH_FOR_PATH_BONUS) {
                count++;
            }
        }
        return count;
    }

    private static int countInteriorCellsInCluster(UnexploredCluster cluster, boolean[][] explored,
                                                    boolean[][] obstacles, boolean[][] sealed,
                                                    int width, int height) {
        int count = 0;
        for (Point cell : cluster.cells()) {
            if (explored[cell.y()][cell.x()] || obstacles[cell.y()][cell.x()]
                || sealed[cell.y()][cell.x()]) {
                continue;
            }
            if (depthFromMapEdge(cell, width, height) >= MIN_INTERIOR_DEPTH_FOR_PATH_BONUS) {
                count++;
            }
        }
        return count;
    }

    private static int countInteriorConnectedUnexplored(Point seed, boolean[][] explored,
                                                         boolean[][] obstacles, boolean[][] sealed,
                                                         int width, int height) {
        if (explored[seed.y()][seed.x()] || obstacles[seed.y()][seed.x()]
            || sealed[seed.y()][seed.x()]) {
            return 0;
        }
        boolean[][] visited = new boolean[height][width];
        ArrayDeque<Point> queue = new ArrayDeque<>();
        queue.add(seed);
        visited[seed.y()][seed.x()] = true;
        int count = 0;
        while (!queue.isEmpty()) {
            Point current = queue.poll();
            if (depthFromMapEdge(current, width, height) >= MIN_INTERIOR_DEPTH_FOR_PATH_BONUS) {
                count++;
            }
            for (int[] direction : DIRECTIONS) {
                int nextX = current.x() + direction[0];
                int nextY = current.y() + direction[1];
                if (!isInBounds(nextX, nextY, width, height) || visited[nextY][nextX]) {
                    continue;
                }
                if (explored[nextY][nextX] || obstacles[nextY][nextX] || sealed[nextY][nextX]) {
                    continue;
                }
                visited[nextY][nextX] = true;
                queue.add(new Point(nextX, nextY));
            }
        }
        return count;
    }

    private static boolean isInBounds(int x, int y, int width, int height) {
        return x >= 0 && x < width && y >= 0 && y < height;
    }

    private static final int[][] DIRECTIONS = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};

    private static int depthFromMapEdge(Point point, int width, int height) {
        int fromLeft = point.x();
        int fromRight = width - 1 - point.x();
        int fromTop = point.y();
        int fromBottom = height - 1 - point.y();
        return Math.min(Math.min(fromLeft, fromRight), Math.min(fromTop, fromBottom));
    }

    private Optional<Point> fallbackFarthestReachable(Point currentPos, List<Point> candidates,
                                                       boolean[][] blocked, boolean[][] explored,
                                                       int width, int height) {
        Point bestTarget = null;
        int bestPathLength = -1;
        for (Point candidate : candidates) {
            List<Point> path = pathEstimator.planPath(
                currentPos, candidate, blocked, explored, width, height);
            if (path.isEmpty() || path.size() <= bestPathLength) {
                continue;
            }
            bestPathLength = path.size();
            bestTarget = candidate;
        }
        return Optional.ofNullable(bestTarget);
    }

    private List<Point> selectClusterEntryCandidates(UnexploredCluster cluster, boolean[][] explored,
                                                      boolean[][] obstacles, boolean[][] sealed,
                                                      int width, int height) {
        List<Point> frontiers = new ArrayList<>();
        for (Point cell : cluster.cells()) {
            if (FrontierCellFinder.isFrontier(
                cell.x(), cell.y(), explored, obstacles, sealed, width, height)) {
                frontiers.add(cell);
            }
        }
        return frontiers.isEmpty() ? cluster.cells() : frontiers;
    }

    private record ClusterCandidate(Point target, int score) {
    }
}
