package com.substation.taskconfigurator;

import com.substation.common.map.ReachabilityAnalyzer;
import com.substation.common.map.SpawnPositionSelector;
import com.substation.common.model.CarStatus;
import com.substation.common.model.Point;
import com.substation.common.redis.BlackboardClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

final class TaskInitializer {

    private static final int DEFAULT_MAP_WIDTH = BlackboardClient.DEFAULT_WIDTH;
    private static final int DEFAULT_MAP_HEIGHT = BlackboardClient.DEFAULT_HEIGHT;
    private static final int DEFAULT_CAR_COUNT = 5;
    private static final double DEFAULT_OBSTACLE_RATIO = 0.15;
    private static final String DEFAULT_ALGORITHM = "BFS";
    private static final int DEFAULT_TICK_INTERVAL = 500;
    private static final int EDGE_MARGIN = 1;
    private static final int FIXED_SPAWN_LAYOUT_CAR_COUNT = 5;
    private static final int OBSTACLE_PLACEMENT_MULTIPLIER = 10;

    private static final String KEY_MAP_WIDTH = "mapWidth";
    private static final String KEY_MAP_HEIGHT = "mapHeight";
    private static final String KEY_CAR_COUNT = "carCount";
    private static final String KEY_OBSTACLE_RATIO = "obstacleRatio";
    private static final String KEY_ALGORITHM = "algorithm";
    private static final String KEY_TICK_INTERVAL = "tickInterval";
    private static final String KEY_ACTIVE = "active";

    private final Random random = new Random();

    void initialize(BlackboardClient bb, Map<String, Object> config) {
        int mapWidth = parseIntParam(config, KEY_MAP_WIDTH, DEFAULT_MAP_WIDTH);
        int mapHeight = parseIntParam(config, KEY_MAP_HEIGHT, DEFAULT_MAP_HEIGHT);
        int carCount = parseIntParam(config, KEY_CAR_COUNT, DEFAULT_CAR_COUNT);
        double obstacleRatio = parseDoubleParam(config, KEY_OBSTACLE_RATIO, DEFAULT_OBSTACLE_RATIO);
        String algorithm = parseStringParam(config, KEY_ALGORITHM, DEFAULT_ALGORITHM);
        int tickInterval = parseIntParam(config, KEY_TICK_INTERVAL, DEFAULT_TICK_INTERVAL);

        writeTaskConfig(bb, mapWidth, mapHeight, carCount, obstacleRatio, algorithm, tickInterval);

        List<String> carIds = generateCarIds(carCount);
        boolean[][] obstacles = placeObstacles(bb, mapWidth, mapHeight, obstacleRatio, Set.of());
        List<Point> initialPositions = assignInitialPositions(carCount, mapWidth, mapHeight, obstacles);

        for (int i = 0; i < carIds.size(); i++) {
            initSingleCar(bb, carIds.get(i), initialPositions.get(i));
            lightUpArea(bb, initialPositions.get(i), mapWidth, mapHeight);
        }

        markSealedUnreachableCells(bb, mapWidth, mapHeight, initialPositions, obstacles);
    }

    // ==================== 步骤实现 ====================

    private void writeTaskConfig(BlackboardClient bb, int mapWidth, int mapHeight,
                                  int carCount, double obstacleRatio, String algorithm,
                                  int tickInterval) {
        Map<String, String> config = new HashMap<>();
        config.put(KEY_MAP_WIDTH, String.valueOf(mapWidth));
        config.put(KEY_MAP_HEIGHT, String.valueOf(mapHeight));
        config.put(KEY_CAR_COUNT, String.valueOf(carCount));
        config.put(KEY_OBSTACLE_RATIO, String.valueOf(obstacleRatio));
        config.put(KEY_ALGORITHM, algorithm);
        config.put(KEY_TICK_INTERVAL, String.valueOf(tickInterval));
        config.put(KEY_ACTIVE, "true");
        bb.initTaskConfig(config);
    }

    private List<String> generateCarIds(int count) {
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            ids.add(String.format("Car%03d", i));
        }
        return ids;
    }

    private List<Point> assignInitialPositions(int carCount, int mapWidth, int mapHeight,
                                                boolean[][] obstacles) {
        if (carCount <= FIXED_SPAWN_LAYOUT_CAR_COUNT) {
            return assignCornerAndCenterPositions(carCount, mapWidth, mapHeight, obstacles);
        }
        return assignExplorationWeightedPositions(carCount, mapWidth, mapHeight, obstacles);
    }

    private List<Point> assignCornerAndCenterPositions(int carCount, int mapWidth, int mapHeight,
                                                        boolean[][] obstacles) {
        Point[] preferred = {
            new Point(EDGE_MARGIN, EDGE_MARGIN),
            new Point(mapWidth - 1 - EDGE_MARGIN, EDGE_MARGIN),
            new Point(EDGE_MARGIN, mapHeight - 1 - EDGE_MARGIN),
            new Point(mapWidth - 1 - EDGE_MARGIN, mapHeight - 1 - EDGE_MARGIN),
            new Point(mapWidth / 2, mapHeight / 2)
        };

        boolean[][] occupied = new boolean[mapHeight][mapWidth];
        List<Point> positions = new ArrayList<>();
        for (int i = 0; i < carCount; i++) {
            Point chosen = resolvePreferredSpawn(preferred[i], obstacles, occupied, mapWidth, mapHeight)
                .orElseGet(() -> pickFallbackSpawn(obstacles, occupied, mapWidth, mapHeight));
            positions.add(chosen);
            occupied[chosen.y()][chosen.x()] = true;
        }
        return positions;
    }

    private List<Point> assignExplorationWeightedPositions(int carCount, int mapWidth, int mapHeight,
                                                            boolean[][] obstacles) {
        boolean[][] explored = new boolean[mapHeight][mapWidth];
        boolean[][] sealed = new boolean[mapHeight][mapWidth];
        boolean[][] occupied = new boolean[mapHeight][mapWidth];

        List<Point> positions = new ArrayList<>();
        for (int i = 0; i < carCount; i++) {
            Point chosen = SpawnPositionSelector.selectBest(
                obstacles, explored, occupied, sealed, EDGE_MARGIN, random)
                .orElse(new Point(EDGE_MARGIN, EDGE_MARGIN));
            positions.add(chosen);
            occupied[chosen.y()][chosen.x()] = true;
        }
        return positions;
    }

    private Optional<Point> resolvePreferredSpawn(Point preferred, boolean[][] obstacles,
                                                   boolean[][] occupied, int mapWidth, int mapHeight) {
        if (isSpawnable(preferred.x(), preferred.y(), obstacles, occupied, mapWidth, mapHeight)) {
            return Optional.of(preferred);
        }
        return Optional.empty();
    }

    private Point pickFallbackSpawn(boolean[][] obstacles, boolean[][] occupied,
                                     int mapWidth, int mapHeight) {
        boolean[][] explored = new boolean[mapHeight][mapWidth];
        boolean[][] sealed = new boolean[mapHeight][mapWidth];
        return SpawnPositionSelector.selectBest(
            obstacles, explored, occupied, sealed, EDGE_MARGIN, random)
            .orElse(new Point(EDGE_MARGIN, EDGE_MARGIN));
    }

    private boolean isSpawnable(int col, int row, boolean[][] obstacles, boolean[][] occupied,
                                 int mapWidth, int mapHeight) {
        if (row < EDGE_MARGIN || row >= mapHeight - EDGE_MARGIN) {
            return false;
        }
        if (col < EDGE_MARGIN || col >= mapWidth - EDGE_MARGIN) {
            return false;
        }
        return !obstacles[row][col] && !occupied[row][col];
    }

    private boolean[][] placeObstacles(BlackboardClient bb, int width, int height,
                                        double ratio, Set<Point> exclude) {
        boolean[][] obstacles = new boolean[height][width];
        int interiorCells = (width - 2 * EDGE_MARGIN) * (height - 2 * EDGE_MARGIN);
        int targetCount = (int) (interiorCells * ratio);
        int placed = 0;
        int maxAttempts = targetCount * OBSTACLE_PLACEMENT_MULTIPLIER;

        for (int attempt = 0; attempt < maxAttempts && placed < targetCount; attempt++) {
            int x = EDGE_MARGIN + random.nextInt(width - 2 * EDGE_MARGIN);
            int y = EDGE_MARGIN + random.nextInt(height - 2 * EDGE_MARGIN);
            Point candidate = new Point(x, y);
            if (!exclude.contains(candidate) && !obstacles[y][x]) {
                obstacles[y][x] = true;
                placed++;
            }
        }
        bb.writeBlockBitmap(obstacles, width);
        return obstacles;
    }

    private void initSingleCar(BlackboardClient bb, String carId, Point position) {
        bb.setCarPosition(carId, position);
        bb.setCarStatus(carId, CarStatus.IDLE);
        bb.setCarSteps(carId, 0);
        bb.setCarEffectiveSteps(carId, 0);
        bb.appendCarHistory(carId, position, 0);
    }

    private void lightUpArea(BlackboardClient bb, Point center, int mapWidth, int mapHeight) {
        int row = center.y();
        int col = center.x();
        if (row >= 0 && row < mapHeight && col >= 0 && col < mapWidth) {
            bb.recordExploration(0, row, col);
        }
    }

    private void markSealedUnreachableCells(BlackboardClient bb, int mapWidth, int mapHeight,
                                             List<Point> carStartPositions, boolean[][] obstacles) {
        boolean[][] sealed = ReachabilityAnalyzer.findSealedFreeCells(obstacles, carStartPositions);
        bb.writeSealedBitmap(sealed, mapWidth);
    }

    // ==================== 参数解析 ====================

    private int parseIntParam(Map<String, Object> config, String key, int defaultValue) {
        Object value = config.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return defaultValue;
    }

    private double parseDoubleParam(Map<String, Object> config, String key, double defaultValue) {
        Object value = config.get(key);
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return defaultValue;
    }

    private String parseStringParam(Map<String, Object> config, String key, String defaultValue) {
        Object value = config.get(key);
        return value != null ? value.toString() : defaultValue;
    }
}
