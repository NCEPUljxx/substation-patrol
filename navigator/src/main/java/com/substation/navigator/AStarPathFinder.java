package com.substation.navigator;

import com.substation.common.map.ExplorationWeightedPathFinder;
import com.substation.common.model.Point;
import com.substation.common.redis.BlackboardClient;

import java.util.List;

final class AStarPathFinder implements PathPlanner {

    @Override
    public List<Point> plan(Point start, Point target, BlackboardClient bb) {
        int width = bb.getMapWidth();
        int height = bb.getMapHeight();

        if (isOutOfBounds(target, width, height)) {
            return List.of();
        }

        boolean[][] blocked = bb.loadBlockedMapWithCars();
        boolean[][] explored = bb.loadExploredBitmap();
        return ExplorationWeightedPathFinder.plan(
            start, target, blocked, explored, width, height,
            ExplorationWeightedPathFinder.SearchMode.WEIGHTED_ASTAR);
    }

    private boolean isOutOfBounds(Point point, int width, int height) {
        return point.x() < 0 || point.x() >= width || point.y() < 0 || point.y() >= height;
    }
}
