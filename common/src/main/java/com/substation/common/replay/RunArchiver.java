package com.substation.common.replay;

import com.substation.common.replay.model.SimulationRunRecord;
import com.substation.common.replay.model.SimulationRunStatus;
import com.substation.common.redis.BlackboardClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 将 Redis 黑板中的当前仿真数据归档到 SQL Server。 */
public class RunArchiver {

    private static final Logger LOG = LoggerFactory.getLogger(RunArchiver.class);
    private static final String UNKNOWN_OPERATOR = "unknown";

    private final SimulationRunStore store;

    public RunArchiver(SimulationRunStore store) {
        this.store = store;
    }

    public Optional<Long> archiveIfNeeded(BlackboardClient blackboard, SimulationRunStatus status) {
        if (blackboard.isSimRunArchived() || !blackboard.hasReplayableData()) {
            return Optional.empty();
        }
        SimulationRunRecord draft = buildDraft(blackboard, status);
        long runId = store.insert(draft);
        blackboard.markSimRunArchived();
        LOG.info("仿真场次已归档 id={} status={} maxTick={}", runId, status, draft.maxTick());
        return Optional.of(runId);
    }

    private SimulationRunRecord buildDraft(BlackboardClient blackboard, SimulationRunStatus status) {
        Map<String, String> config = blackboard.getTaskConfig();
        Map<String, List<String>> histories = blackboard.getAllCarHistories();
        List<String> events = blackboard.getExplorationEvents();
        int mapWidth = parseInt(config.get("mapWidth"), BlackboardClient.DEFAULT_WIDTH);
        int mapHeight = parseInt(config.get("mapHeight"), BlackboardClient.DEFAULT_HEIGHT);
        int explorationRate = blackboard.isExplorationComplete()
                ? 100
                : blackboard.getExplorationRate();
        Instant startedAt = blackboard.getSimRunStartedAt().orElse(Instant.now());
        return new SimulationRunRecord(
                0L,
                blackboard.getSimRunStartedBy().orElse(UNKNOWN_OPERATOR),
                startedAt,
                Instant.now(),
                mapWidth,
                mapHeight,
                blackboard.discoverCarIds().size(),
                config.get("algorithm"),
                config.get("obstacleRatio"),
                config.get("tickInterval"),
                ReplayDataBuilder.resolveMaxTick(histories, events),
                explorationRate,
                status,
                Base64.getEncoder().encodeToString(blackboard.getMapBlockBytes()),
                Base64.getEncoder().encodeToString(blackboard.getMapSealedBytes()),
                Base64.getEncoder().encodeToString(blackboard.getMapViewBytes()),
                histories,
                events);
    }

    private static int parseInt(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
