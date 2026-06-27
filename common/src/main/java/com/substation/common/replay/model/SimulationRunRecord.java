package com.substation.common.replay.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 从数据库读取的完整场次记录。 */
public record SimulationRunRecord(
        long id,
        String startedBy,
        Instant startedAt,
        Instant endedAt,
        int mapWidth,
        int mapHeight,
        int carCount,
        String algorithm,
        String obstacleRatio,
        String tickInterval,
        int maxTick,
        int explorationRate,
        SimulationRunStatus status,
        String mapBlockB64,
        String mapSealedB64,
        String mapViewFinalB64,
        Map<String, List<String>> carHistories,
        List<String> explorationEvents) {}
