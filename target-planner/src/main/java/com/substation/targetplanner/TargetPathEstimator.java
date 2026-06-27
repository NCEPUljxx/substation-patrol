package com.substation.targetplanner;

import com.substation.common.map.ExplorationWeightedPathFinder;
import com.substation.common.model.Point;

import java.util.List;

/** 加权路径估算，供目标分配时评估路径长度与重合度 */
final class TargetPathEstimator {

    List<Point> planPath(Point start, Point target, boolean[][] blocked,
                          boolean[][] explored, int width, int height) {
        return ExplorationWeightedPathFinder.plan(
            start, target, blocked, explored, width, height,
            ExplorationWeightedPathFinder.SearchMode.WEIGHTED_DIJKSTRA);
    }
}
