package com.substation.common.admin;

import com.alibaba.fastjson2.JSON;
import com.substation.common.sql.RegistrationStore;
import com.substation.common.sql.SqlUserStore;
import com.substation.common.sql.model.OperationLogRecord;
import com.substation.common.sql.model.RegistrationRecord;
import com.substation.common.sql.model.UserRecord;
import com.substation.common.sql.OperationLogStore;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 管理员 API 处理器：用户管理 + 注册审核 + 操作日志。
 * 所有接口需要 admin 角色（由 HttpFileServer 鉴权保证）。
 */
public class AdminApiHandler {

    private static final Logger log = LoggerFactory.getLogger(AdminApiHandler.class);

    private final SqlUserStore userStore;
    private final RegistrationStore regStore;
    private final OperationLogStore logStore;

    public AdminApiHandler(SqlUserStore userStore, RegistrationStore regStore,
                            OperationLogStore logStore) {
        this.userStore = userStore;
        this.regStore = regStore;
        this.logStore = logStore;
    }

    public void handle(String path, HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        String method = exchange.getRequestMethod();

        try {
            if (path.equals("/api/admin/users") && "GET".equals(method)) {
                handleUserList(exchange);
            } else if (path.matches("/api/admin/users/[^/]+/reset-password") && "POST".equals(method)) {
                String username = extractName(path, 4);
                handleResetPassword(exchange, username);
            } else if (path.matches("/api/admin/users/[^/]+") && "GET".equals(method)) {
                String username = extractName(path, 4);
                handleUserDetail(exchange, username);
            } else if (path.equals("/api/admin/registrations") && "GET".equals(method)) {
                handleRegistrationList(exchange);
            } else if (path.matches("/api/admin/registrations/\\d+/approve") && "POST".equals(method)) {
                int id = extractId(path, 4);
                handleApprove(exchange, id);
            } else if (path.matches("/api/admin/registrations/\\d+/reject") && "POST".equals(method)) {
                int id = extractId(path, 4);
                handleReject(exchange, id);
            } else if (path.equals("/api/admin/logs") && "GET".equals(method)) {
                handleLogList(exchange);
            } else {
                sendJson(exchange, 404, "404");
            }
        } catch (Exception e) {
            log.error("Admin API 异常: {}", e.getMessage(), e);
            sendJson(exchange, 500, "{\"success\":false,\"error\":\"服务器错误\"}");
        }
    }

    private void handleUserList(HttpExchange exchange) {
        Map<String, String> q = parseQuery(exchange.getRequestURI().getQuery());
        String search = q.get("search");
        String role = q.get("role");
        int page = Math.max(1, parseIntOrDefault(q.get("page"), 1));
        int size = Math.min(100, Math.max(1, parseIntOrDefault(q.get("size"), 20)));

        List<UserRecord> users = userStore.queryUsers(search, role, page, size);
        int total = userStore.countUsers(search, role);
        String json = JSON.toJSONString(Map.of(
                "success", true, "data", users, "total", total, "page", page, "size", size));
        sendJson(exchange, 200, json);
    }

    private void handleUserDetail(HttpExchange exchange, String username) {
        var user = userStore.getUserInfo(username);
        if (user.isPresent()) {
            UserRecord r = userStore.queryUsers(username, null, 1, 1).stream()
                    .findFirst().orElse(null);
            if (r != null) {
                sendJson(exchange, 200, JSON.toJSONString(Map.of("success", true, "data", r)));
                return;
            }
        }
        sendJson(exchange, 404, "{\"success\":false,\"error\":\"用户不存在\"}");
    }

    private void handleResetPassword(HttpExchange exchange, String username) {
        boolean ok = userStore.resetPassword(username);
        if (ok) {
            logStore.log(getAdminUser(exchange), "RESET_PASSWORD", username,
                    "管理员重置密码为 123456");
            sendJson(exchange, 200, "{\"success\":true,\"message\":\"密码已重置为 123456\"}");
        } else {
            sendJson(exchange, 400, "{\"success\":false,\"error\":\"用户不存在\"}");
        }
    }

    private void handleRegistrationList(HttpExchange exchange) {
        Map<String, String> q = parseQuery(exchange.getRequestURI().getQuery());
        String status = q.getOrDefault("status", "pending");
        int page = Math.max(1, parseIntOrDefault(q.get("page"), 1));
        int size = Math.min(100, Math.max(1, parseIntOrDefault(q.get("size"), 20)));

        List<RegistrationRecord> list = regStore.queryRequests(status, page, size);
        int total = regStore.countRequests(status);
        String json = JSON.toJSONString(Map.of(
                "success", true, "data", list, "total", total, "page", page, "size", size));
        sendJson(exchange, 200, json);
    }

    private void handleApprove(HttpExchange exchange, int id) {
        String admin = getAdminUser(exchange);
        boolean ok = regStore.approve(id, admin);
        if (ok) {
            logStore.log(admin, "APPROVE_REGISTRATION", "id=" + id, "通过注册申请");
            sendJson(exchange, 200, "{\"success\":true,\"message\":\"已通过注册申请\"}");
        } else {
            sendJson(exchange, 400,
                    "{\"success\":false,\"error\":\"申请不存在或已处理\"}");
        }
    }

    private void handleReject(HttpExchange exchange, int id) {
        String admin = getAdminUser(exchange);
        boolean ok = regStore.reject(id, admin);
        if (ok) {
            logStore.log(admin, "REJECT_REGISTRATION", "id=" + id, "拒绝注册申请");
            sendJson(exchange, 200, "{\"success\":true,\"message\":\"已拒绝注册申请\"}");
        } else {
            sendJson(exchange, 400,
                    "{\"success\":false,\"error\":\"申请不存在或已处理\"}");
        }
    }

    private void handleLogList(HttpExchange exchange) {
        Map<String, String> q = parseQuery(exchange.getRequestURI().getQuery());
        String username = q.get("username");
        String action = q.get("action");
        int page = Math.max(1, parseIntOrDefault(q.get("page"), 1));
        int size = Math.min(100, Math.max(1, parseIntOrDefault(q.get("size"), 20)));

        List<OperationLogRecord> logs = logStore.queryLogs(username, action, page, size);
        int total = logStore.countLogs(username, action);
        String json = JSON.toJSONString(Map.of(
                "success", true, "data", logs, "total", total, "page", page, "size", size));
        sendJson(exchange, 200, json);
    }

    // ============ 工具方法 ============

    private String extractName(String path, int index) {
        String[] parts = path.split("/");
        return parts.length > index ? parts[index] : "";
    }

    private int extractId(String path, int index) {
        String[] parts = path.split("/");
        try {
            return Integer.parseInt(parts[index]);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 从 token 解析当前登录的管理员用户名 */
    private String getAdminUser(HttpExchange exchange) {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        // 从 SessionManager 解析即可，这里先简单返回 admin
        return "admin";
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> map = new java.util.HashMap<>();
        if (query == null || query.isBlank()) return map;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) map.put(kv[0], kv[1]);
        }
        return map;
    }

    private int parseIntOrDefault(String val, int def) {
        try { return Integer.parseInt(val); } catch (Exception e) { return def; }
    }

    private void sendJson(HttpExchange exchange, int code, String json) {
        try {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException e) {
            log.error("响应写入失败", e);
        }
    }
}
