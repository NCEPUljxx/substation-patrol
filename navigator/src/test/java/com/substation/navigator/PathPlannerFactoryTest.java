package com.substation.navigator;

import com.substation.common.model.AlgorithmType;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PathPlannerFactoryTest {

    @Test
    void createBfsReturnsBfsPathFinder() {
        PathPlanner planner = PathPlannerFactory.create(AlgorithmType.BFS);
        assertTrue(planner instanceof BfsPathFinder);
    }

    @Test
    void createAStarReturnsAStarPathFinder() {
        PathPlanner planner = PathPlannerFactory.create(AlgorithmType.ASTAR);
        assertTrue(planner instanceof AStarPathFinder);
    }

    @Test
    void createFromStringBfs() {
        PathPlanner planner = PathPlannerFactory.create("BFS");
        assertTrue(planner instanceof BfsPathFinder);
    }

    @Test
    void createFromStringAStar() {
        PathPlanner planner = PathPlannerFactory.create("ASTAR");
        assertTrue(planner instanceof AStarPathFinder);
    }

    @Test
    void unknownAlgorithmFallsBackToBfs() {
        PathPlanner planner = PathPlannerFactory.create("INVALID");
        assertTrue(planner instanceof BfsPathFinder, "非法算法名应回落 BFS");
    }
}
