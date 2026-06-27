package com.substation.common;

import com.substation.common.model.Point;
import com.substation.common.redis.BlackboardClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 动态障碍物工具类，供 Controller 每 N 拍调用。
 * 随机增删 mapBlock 中的障碍物，避开小车当前位置。
 */
public final class DynamicObstacleUtil {

    private static final int MAX_ADD_COUNT = 2;
    private static final int MAX_REMOVE_COUNT = 2;
    private static final int MAX_RANDOM_ATTEMPTS = 50;

    private final Random random;

    public DynamicObstacleUtil() {
        this.random = new Random();
    }

    /**
     * 生成动态障碍物变更：随机移除若干障碍物，随机新增若干障碍物。
     *
     * @param bb            BlackboardClient
     * @param mapWidth      地图宽度
     * @param mapHeight     地图高度
     * @param carPositions  所有车辆当前位置（新增障碍物不会覆盖这些位置）
     * @return 变更日志，如 {@code ["新增(12,8)", "移除(25,3)"]}
     */
    public List<String> generate(BlackboardClient bb, int mapWidth, int mapHeight,
                                  Set<Point> carPositions) {
        List<String> changes = new ArrayList<>();

        List<Point> existingObstacles = collectExistingObstacles(bb, mapWidth, mapHeight);
        removeRandomObstacles(bb, existingObstacles, changes);
        addRandomObstacles(bb, mapWidth, mapHeight, carPositions, changes);

        return Collections.unmodifiableList(changes);
    }

    // ==================== 收集现有障碍物 ====================

    private List<Point> collectExistingObstacles(BlackboardClient bb,
                                                  int width, int height) {
        List<Point> obstacles = new ArrayList<>();
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                if (bb.isBlocked(r, c)) {
                    obstacles.add(new Point(c, r));
                }
            }
        }
        return obstacles;
    }

    // ==================== 随机移除 ====================

    private void removeRandomObstacles(BlackboardClient bb, List<Point> obstacles,
                                        List<String> changes) {
        Collections.shuffle(obstacles, random);
        int removeCount = Math.min(MAX_REMOVE_COUNT, obstacles.size());
        for (int i = 0; i < removeCount; i++) {
            Point p = obstacles.get(i);
            bb.setBlock(p.y(), p.x(), false);
            changes.add("移除(" + p.x() + "," + p.y() + ")");
        }
    }

    // ==================== 随机新增 ====================

    private void addRandomObstacles(BlackboardClient bb, int width, int height,
                                     Set<Point> carPositions, List<String> changes) {
        int added = 0;
        int attempts = 0;
        while (added < MAX_ADD_COUNT && attempts < MAX_RANDOM_ATTEMPTS) {
            attempts++;
            int x = random.nextInt(width);
            int y = random.nextInt(height);
            Point candidate = new Point(x, y);

            if (!carPositions.contains(candidate) && !bb.isBlocked(y, x)) {
                bb.setBlock(y, x, true);
                changes.add("新增(" + x + "," + y + ")");
                added++;
            }
        }
    }
}
