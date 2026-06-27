package com.substation.common.analysis;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.substation.common.analysis.model.CarStatistics;
import com.substation.common.analysis.model.SummaryStatistics;
import com.substation.common.auth.SessionManager;
import com.substation.common.replay.SimulationRunStore;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 统计分析 API：按场次 run_id 关联回放与统计。 */
public class AnalysisApiHandler {

    private static final Logger LOG = LoggerFactory.getLogger(AnalysisApiHandler.class);
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final String RECORDS_PREFIX = "/records";
    private static final String DISCARD_PATH = "/discard";

    private final SimulationStatsStore statsStore;
    private final SimulationRunStore runStore;
    private final SimulationRecordService recordService;
    private final SessionManager sessionManager;

    public AnalysisApiHandler(SimulationStatsStore statsStore,
                              SimulationRunStore runStore,
                              SimulationRecordService recordService,
                              SessionManager sessionManager) {
        this.statsStore = statsStore;
        this.runStore = runStore;
        this.recordService = recordService;
        this.sessionManager = sessionManager;
    }

    /** 分发 /api/analysis/* 请求 */
    public void handle(String path, HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");

        String method = exchange.getRequestMethod();
        String subPath = path.replace("/api/analysis", "");

        try {
            if (subPath.equals(RECORDS_PREFIX) && "GET".equals(method)) {
                handleListRecords(exchange);
                return;
            }
            if (subPath.equals(RECORDS_PREFIX) && "POST".equals(method)) {
                handleSaveRecord(exchange);
                return;
            }
            if (subPath.equals(DISCARD_PATH) && "POST".equals(method)) {
                handleDiscard(exchange);
                return;
            }
            if (subPath.startsWith(RECORDS_PREFIX + "/") && "DELETE".equals(method)) {
                handleDeleteRecord(exchange, subPath.substring((RECORDS_PREFIX + "/").length()));
                return;
            }
            if (subPath.equals("/summary") && "GET".equals(method)) {
                handleSummary(exchange);
                return;
            }
            if (subPath.startsWith("/car/") && "GET".equals(method)) {
                handleCarStats(exchange, subPath.substring(5));
                return;
            }
            if (subPath.equals("/leaderboard") && "GET".equals(method)) {
                handleLeaderboard(exchange);
                return;
            }
            if (subPath.equals("/query") && "POST".equals(method)) {
                handleQuery(exchange);
                return;
            }
            if (subPath.equals("/export") && "GET".equals(method)) {
                handleExport(exchange);
                return;
            }
            sendJson(exchange, 404, "{\"success\":false,\"error\":\"not found\"}");
        } catch (Exception e) {
            LOG.error("Analysis API 处理异常: {}", e.getMessage(), e);
            sendJson(exchange, 500, "{\"success\":false,\"error\":\"服务器内部错误\"}");
        }
    }

    private void handleListRecords(HttpExchange exchange) throws IOException {
        int page = parseQueryInt(exchange, "page", 1);
        int size = parseQueryInt(exchange, "size", DEFAULT_PAGE_SIZE);
        List<JSONObject> records = statsStore.listFullRecords(page, size);
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("records", records);
        sendJson(exchange, 200, JSON.toJSONString(body));
    }

    private void handleSaveRecord(HttpExchange exchange) throws IOException {
        String bodyStr = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JSONObject payload = JSON.parseObject(bodyStr);
        if (payload == null || payload.isEmpty()) {
            sendJson(exchange, 400, "{\"success\":false,\"error\":\"请求体无效\"}");
            return;
        }
        String savedBy = resolveUsername(exchange);
        long requestedRunId = payload.getLongValue("runId");
        try {
            long runId = requestedRunId > 0
                    ? saveStatsForExistingRun(requestedRunId, savedBy, payload)
                    : recordService.saveConfirmed(payload, savedBy);
            JSONObject saved = statsStore.findPayloadByRunId(runId).orElse(payload);
            Map<String, Object> body = new HashMap<>();
            body.put("success", true);
            body.put("runId", runId);
            body.put("record", saved);
            sendJson(exchange, 200, JSON.toJSONString(body));
        } catch (IllegalStateException e) {
            sendJson(exchange, 409, JSON.toJSONString(Map.of("success", false, "error", e.getMessage())));
        } catch (IllegalArgumentException e) {
            sendJson(exchange, 400, JSON.toJSONString(Map.of("success", false, "error", e.getMessage())));
        }
    }

    private long saveStatsForExistingRun(long runId, String savedBy, JSONObject payload)
            throws IllegalStateException, IllegalArgumentException {
        if (!runStore.existsById(runId)) {
            throw new IllegalArgumentException("场次不存在: runId=" + runId);
        }
        if (statsStore.existsForRun(runId)) {
            throw new IllegalStateException("该场次统计已存在: runId=" + runId);
        }
        statsStore.save(runId, savedBy, payload);
        return runId;
    }

    private void handleDiscard(HttpExchange exchange) throws IOException {
        recordService.declineSave();
        sendJson(exchange, 200, "{\"success\":true}");
    }

    private void handleDeleteRecord(HttpExchange exchange, String idPart) throws IOException {
        long runId;
        try {
            runId = Long.parseLong(idPart);
        } catch (NumberFormatException e) {
            sendJson(exchange, 400, "{\"success\":false,\"error\":\"无效场次编号\"}");
            return;
        }
        boolean deleted = recordService.deleteByRunId(runId);
        if (!deleted) {
            sendJson(exchange, 404, "{\"success\":false,\"error\":\"记录不存在\"}");
            return;
        }
        sendJson(exchange, 200, "{\"success\":true}");
    }

    private String resolveUsername(HttpExchange exchange) {
        String token = SessionManager.extractToken(
                exchange.getRequestHeaders().getFirst("Authorization"));
        return sessionManager.validate(token)
                .map(session -> session.username())
                .orElse("unknown");
    }

    private void handleSummary(HttpExchange exchange) throws IOException {
        SummaryStatistics summary = SummaryStatistics.empty();
        sendJson(exchange, 200, JSON.toJSONString(Map.of("success", true, "data", summary)));
    }

    private void handleCarStats(HttpExchange exchange, String carId) throws IOException {
        CarStatistics stats = CarStatistics.empty(carId);
        sendJson(exchange, 200, JSON.toJSONString(Map.of("success", true, "data", stats)));
    }

    private void handleLeaderboard(HttpExchange exchange) throws IOException {
        var leaderboard = List.of(
                Map.of("carId", "Car001", "steps", 0, "coverage", 0, "efficiency", 0.0),
                Map.of("carId", "Car002", "steps", 0, "coverage", 0, "efficiency", 0.0),
                Map.of("carId", "Car003", "steps", 0, "coverage", 0, "efficiency", 0.0));
        sendJson(exchange, 200, JSON.toJSONString(Map.of("success", true, "leaderboard", leaderboard)));
    }

    private void handleQuery(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        sendJson(exchange, 200, JSON.toJSONString(Map.of("success", true, "data", Map.of())));
    }

    private void handleExport(HttpExchange exchange) throws IOException {
        sendJson(exchange, 200, JSON.toJSONString(Map.of("success", true, "message", "导出功能开发中")));
    }

    private static int parseQueryInt(HttpExchange exchange, String key, int defaultValue) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) {
            return defaultValue;
        }
        for (String part : query.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && key.equals(kv[0])) {
                try {
                    return Integer.parseInt(kv[1]);
                } catch (NumberFormatException ignored) {
                    return defaultValue;
                }
            }
        }
        return defaultValue;
    }

    private void sendJson(HttpExchange exchange, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
