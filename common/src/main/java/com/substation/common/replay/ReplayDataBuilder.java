package com.substation.common.replay;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.substation.common.replay.model.SimulationRunRecord;
import com.substation.common.redis.BlackboardClient;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/** 构建与前端 REPLAY_DATA 兼容的回放 JSON。 */
public final class ReplayDataBuilder {

    private ReplayDataBuilder() {}

    public static JSONObject fromBlackboard(BlackboardClient blackboard) {
        Map<String, String> config = blackboard.getTaskConfig();
        int mapWidth = parseInt(config.get("mapWidth"), BlackboardClient.DEFAULT_WIDTH);
        int mapHeight = parseInt(config.get("mapHeight"), BlackboardClient.DEFAULT_HEIGHT);
        Map<String, List<String>> histories = blackboard.getAllCarHistories();
        List<String> events = blackboard.getExplorationEvents();
        JSONObject payload = buildPayload(
                mapWidth, mapHeight, config,
                histories, events,
                Base64.getEncoder().encodeToString(blackboard.getMapViewBytes()),
                Base64.getEncoder().encodeToString(blackboard.getMapBlockBytes()),
                Base64.getEncoder().encodeToString(blackboard.getMapSealedBytes()),
                resolveMaxTick(histories, events),
                blackboard.isExplorationComplete() ? 100 : blackboard.getExplorationRate());
        payload.put("mapBlock", BlackboardClient.bytesToBitmap(
                blackboard.getMapBlockBytes(), mapWidth, mapHeight));
        payload.put("mapSealed", BlackboardClient.bytesToBitmap(
                blackboard.getMapSealedBytes(), mapWidth, mapHeight));
        return payload;
    }

    public static JSONObject fromRecord(SimulationRunRecord record) {
        int width = record.mapWidth();
        int height = record.mapHeight();
        JSONObject payload = buildPayload(
                width, height, Map.of(
                        "mapWidth", String.valueOf(width),
                        "mapHeight", String.valueOf(height),
                        "algorithm", record.algorithm() != null ? record.algorithm() : "",
                        "obstacleRatio", record.obstacleRatio() != null ? record.obstacleRatio() : "",
                        "tickInterval", record.tickInterval() != null ? record.tickInterval() : ""),
                record.carHistories(),
                record.explorationEvents(),
                record.mapViewFinalB64(),
                record.mapBlockB64(),
                record.mapSealedB64(),
                record.maxTick(),
                record.explorationRate());
        payload.put("runId", record.id());
        if (record.mapBlockB64() != null && !record.mapBlockB64().isBlank()) {
            payload.put("mapBlock", decodeBitmap(record.mapBlockB64(), width, height));
        }
        if (record.mapSealedB64() != null && !record.mapSealedB64().isBlank()) {
            payload.put("mapSealed", decodeBitmap(record.mapSealedB64(), width, height));
        }
        return payload;
    }

    private static boolean[][] decodeBitmap(String base64, int width, int height) {
        byte[] bytes = Base64.getDecoder().decode(base64);
        return BlackboardClient.bytesToBitmap(bytes, width, height);
    }

    private static JSONObject buildPayload(
            int mapWidth,
            int mapHeight,
            Map<String, String> taskConfig,
            Map<String, List<String>> carHistories,
            List<String> explorationEvents,
            String mapViewB64,
            String mapBlockB64,
            String mapSealedB64,
            int maxTick,
            int explorationRate) {
        JSONObject json = new JSONObject();
        json.put("type", "REPLAY_DATA");
        json.put("mapWidth", mapWidth);
        json.put("mapHeight", mapHeight);
        json.put("taskConfig", taskConfig);
        json.put("carHistories", carHistories);
        json.put("explorationEvents", explorationEvents);
        json.put("mapViewB64", mapViewB64);
        json.put("mapBlockB64", mapBlockB64);
        json.put("mapSealedB64", mapSealedB64);
        json.put("maxTick", maxTick);
        json.put("explorationRate", explorationRate);
        return json;
    }

    static int resolveMaxTick(Map<String, List<String>> histories, List<String> explorationEvents) {
        int maxTick = 0;
        for (List<String> history : histories.values()) {
            for (String entry : history) {
                JSONObject event = JSON.parseObject(entry);
                maxTick = Math.max(maxTick, event.getIntValue("tick", 0));
            }
        }
        for (String explorationEvent : explorationEvents) {
            int comma = explorationEvent.indexOf(',');
            if (comma <= 0) {
                continue;
            }
            try {
                maxTick = Math.max(maxTick, Integer.parseInt(explorationEvent.substring(0, comma)));
            } catch (NumberFormatException ignored) {
                // skip malformed event
            }
        }
        return maxTick;
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
