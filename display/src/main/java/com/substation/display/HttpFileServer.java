package com.substation.display;

import com.substation.common.admin.AdminApiHandler;
import com.substation.common.analysis.AnalysisApiHandler;
import com.substation.common.auth.AuthApiHandler;
import com.substation.common.auth.SessionManager;
import com.substation.common.replay.ReplayApiHandler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * HTTP 服务器，集成认证、分析、管理 API 路由。
 * 白名单放行所有页面 + 静态资源 + 认证接口。
 */
final class HttpFileServer {

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "css",  "text/css; charset=utf-8",
            "js",   "application/javascript; charset=utf-8",
            "json", "application/json",
            "png",  "image/png",
            "svg",  "image/svg+xml",
            "wasm", "application/wasm",
            "data", "application/octet-stream",
            "ico",  "image/x-icon"
    );

    private static final Set<String> WHITELIST = Set.of(
            "/login.html", "/index.html", "/dashboard.html", "/analysis.html",
            "/css/", "/js/", "/unity/", "/api/auth/login", "/api/auth/register",
            "/favicon.ico", "/");

    private final HttpServer server;
    private final Path webRoot;
    private final AuthApiHandler authApi;
    private final AnalysisApiHandler analysisApi;
    private final AdminApiHandler adminApi;
    private final ReplayApiHandler replayApi;
    private final SessionManager sessionManager;

    HttpFileServer(int port, Path webRoot,
                   AuthApiHandler authApi, AnalysisApiHandler analysisApi,
                   AdminApiHandler adminApi, ReplayApiHandler replayApi,
                   SessionManager sessionManager) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.webRoot = webRoot;
        this.authApi = authApi;
        this.analysisApi = analysisApi;
        this.adminApi = adminApi;
        this.replayApi = replayApi;
        this.sessionManager = sessionManager;
        this.server.createContext("/", this::handle);
    }

    void start() { server.start(); }
    void stop() { server.stop(0); }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();

        if (path.startsWith("/api/auth/")) { authApi.handle(path, exchange); return; }
        if (path.startsWith("/api/admin/")) {
            if (!checkAuth(exchange, path)) return;
            adminApi.handle(path, exchange);
            return;
        }
        if (path.startsWith("/api/analysis/")) {
            if (!checkAuth(exchange, path)) return;
            analysisApi.handle(path, exchange);
            return;
        }
        if (path.startsWith("/api/replay/")) {
            if (!checkAuth(exchange, path)) return;
            replayApi.handle(path, exchange);
            return;
        }
        if (isWhitelisted(path)) { serveStatic(exchange, path); return; }
        if (!checkAuth(exchange, path)) return;
        serveStatic(exchange, path);
    }

    private boolean checkAuth(HttpExchange exchange, String path) throws IOException {
        String token = SessionManager.extractToken(
                exchange.getRequestHeaders().getFirst("Authorization"));
        if (token == null || sessionManager.validate(token).isEmpty()) {
            sendJson(exchange, 401, "{\"success\":false,\"error\":\"请先登录\"}");
            return false;
        }
        return true;
    }

    private boolean isWhitelisted(String path) {
        return WHITELIST.stream().anyMatch(path::startsWith);
    }

    private void serveStatic(HttpExchange exchange, String path) throws IOException {
        String resolvedPath = "/".equals(path) ? "/login.html" : path;
        Path filePath = webRoot.resolve(resolvedPath.substring(1));
        if (Files.isRegularFile(filePath)) {
            serveFile(exchange, filePath);
        } else {
            sendNotFound(exchange);
        }
    }

    private void serveFile(HttpExchange exchange, Path filePath) throws IOException {
        String contentType = detectContentType(filePath);
        byte[] content = Files.readAllBytes(filePath);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream body = exchange.getResponseBody()) { body.write(content); }
    }

    private void sendJson(HttpExchange exchange, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }

    private void sendNotFound(HttpExchange exchange) throws IOException {
        byte[] body = "404 Not Found".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(404, body.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
    }

    static String detectContentType(Path filePath) {
        String fileName = filePath.getFileName().toString();
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot < 0) return "application/octet-stream";
        return CONTENT_TYPES.getOrDefault(fileName.substring(lastDot + 1), "application/octet-stream");
    }
}
