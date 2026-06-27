package com.substation.common.map;

/** 路径搜索时区分已探索 / 未探索格子的步长代价 */
public final class ExplorationPathCosts {

    public static final int UNEXPLORED_STEP_COST = 1;
    public static final int EXPLORED_STEP_COST = 5;

    private ExplorationPathCosts() {
    }

    public static int stepCost(boolean[][] explored, int col, int row) {
        return explored[row][col] ? EXPLORED_STEP_COST : UNEXPLORED_STEP_COST;
    }
}
