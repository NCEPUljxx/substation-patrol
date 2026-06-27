package com.substation.navigator;

import com.substation.common.model.AlgorithmType;

final class PathPlannerFactory {

    private PathPlannerFactory() {}

    static PathPlanner create(AlgorithmType algorithm) {
        return switch (algorithm) {
            case BFS -> new BfsPathFinder();
            case ASTAR -> new AStarPathFinder();
        };
    }

    static PathPlanner create(String algorithmName) {
        try {
            return create(AlgorithmType.valueOf(algorithmName.toUpperCase()));
        } catch (IllegalArgumentException e) {
            return new BfsPathFinder();
        }
    }
}
