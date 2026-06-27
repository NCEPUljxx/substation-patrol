package com.substation.strategysupervisor;

import com.substation.common.model.Point;
import com.substation.common.redis.BlackboardClient;

import java.util.List;

/**
 * 路线评估器：阈值随全局探索率动态上调，高探索率时不苛求路线。
 */
final class RouteEvaluator {

    /** 最低触发阈值 */
    private static final double MIN_THRESHOLD = 0.3;
    /** 最高触发阈值 */
    private static final double MAX_THRESHOLD = 0.8;

    Result evaluate(BlackboardClient bb, List<Point> route) {
        if (route.isEmpty()) {
            return Result.SKIP;
        }

        boolean[][] explored = bb.loadExploredBitmap();
        int exploredCount = countExploredOnRoute(route, explored);
        int total = route.size();

        double globalRate = bb.getExplorationRate() / 100.0;
        double threshold = Math.max(MIN_THRESHOLD, Math.min(MAX_THRESHOLD, globalRate + 0.05));

        if ((double) exploredCount / total >= threshold) {
            return Result.NEED_OPTIMIZE;
        }
        return Result.SKIP;
    }

    private static int countExploredOnRoute(List<Point> route, boolean[][] explored) {
        int count = 0;
        for (Point point : route) {
            if (explored[point.y()][point.x()]) {
                count++;
            }
        }
        return count;
    }

    enum Result {
        NEED_OPTIMIZE,
        SKIP
    }
}
