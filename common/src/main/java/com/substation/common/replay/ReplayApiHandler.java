package com.substation.common.replay;

import com.alibaba.fastjson2.JSON;
import com.substation.common.replay.model.SimulationRunRecord;
import com.substation.common.replay.model.SimulationRunSummary;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 历史仿真场次列表与按 ID 回放 API。 */
public class ReplayApiHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ReplayApiHandler.class);
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final SimulationRunStore store;

    public ReplayApiHandler(SimulationRunStore store) {
        this.store = store;
    }

    public void handle(String path, HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        String method = exchange.getRequestMethod();
        String subPath = path.replace("/api/replay", "");

        try {
            if ("/runs".equals(subPath) && "GET".equals(method)) {
                handleListRuns(exchange);
                return;
            }
            if (subPath.startsWith("/runs/") && "GET".equals(method)) {
                String idPart = subPath.substring("/runs/".length());
                handleGetRun(exchange, idPart);
                return;
            }
            sendJson(exchange, 404, "{\"success\":false,\"error\":\"not found\"}");
        } catch (Exception e) {
            LOG.error("Replay API error: {}", e.getMessage(), e);
            sendJson(exchange, 500, "{\"success\":false,\"error\":\"服务器内部错误\"}");
        }
    }

    private void handleListRuns(HttpExchange exchange) throws IOException {
        int page = parseQueryInt(exchange, "page", 1);
        int size = parseQueryInt(exchange, "size", DEFAULT_PAGE_SIZE);
        List<SimulationRunSummary> runs = store.listRecent(page, size);
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("runs", runs);
        sendJson(exchange, 200, JSON.toJSONString(body));
    }

    private void handleGetRun(HttpExchange exchange, String idPart) throws IOException {
        long runId;
        try {
            runId = Long.parseLong(idPart);
        } catch (NumberFormatException e) {
            sendJson(exchange, 400, "{\"success\":false,\"error\":\"无效场次 ID\"}");
            return;
        }
        store.findById(runId).ifPresentOrElse(
                record -> {
                    try {
                        sendReplayPayload(exchange, record);
                    } catch (IOException ex) {
                        LOG.warn("响应写入失败", ex);
                    }
                },
                () -> {
                    try {
                        sendJson(exchange, 404, "{\"success\":false,\"error\":\"场次不存在\"}");
                    } catch (IOException ex) {
                        LOG.warn("响应写入失败", ex);
                    }
                });
    }

    private void sendReplayPayload(HttpExchange exchange, SimulationRunRecord record) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", ReplayDataBuilder.fromRecord(record));
        sendJson(exchange, 200, JSON.toJSONString(body));
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
