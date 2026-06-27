package com.substation.common.analysis.model;

import java.time.Instant;

/** 仿真统计记录列表项（与 simulation_runs.run_id 一一对应）。 */
public record SimulationStatsSummary(
        long runId,
        String savedBy,
        Instant savedAt,
        int explorationRate,
        int tick,
        int duration,
        int totalSteps,
        int totalEffectiveSteps,
        int efficiencyPercent,
        int carCount,
        String algorithm,
        double obstacleRatio,
        int mapWidth,
        int mapHeight,
        double balanceScore,
        long clientTimestamp) {
}
