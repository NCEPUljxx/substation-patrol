package com.substation.common.replay.model;

import java.time.Instant;

/** 仿真场次列表项。 */
public record SimulationRunSummary(
        long id,
        String startedBy,
        Instant startedAt,
        Instant endedAt,
        int mapWidth,
        int mapHeight,
        int carCount,
        String algorithm,
        int maxTick,
        int explorationRate,
        SimulationRunStatus status,
        boolean hasStats) {}
