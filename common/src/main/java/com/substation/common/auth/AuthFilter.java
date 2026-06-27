package com.substation.common.auth;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * HTTP 请求鉴权过滤器。
 * 白名单放行所有页面 + 静态资源 + 认证接口，其余需 Token。
 */
public class AuthFilter extends Filter {

    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);

    private static final Set<String> WHITELIST_PREFIXES = Set.of(
            "/login.html", "/index.html", "/dashboard.html", "/analysis.html",
            "/css/", "/js/", "/api/auth/", "/favicon.ico");

    private final SessionManager sessionManager;

    public AuthFilter(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (isWhitelisted(path)) { chain.doFilter(exchange); return; }
        String token = SessionManager.extractToken(
                exchange.getRequestHeaders().getFirst("Authorization"));
        var validation = sessionManager.validateDetailed(token);
        if (validation.isValid()) {
            try {
                var session = validation.session();
                if (path.startsWith("/api/analysis/export") && !"admin".equals(session.role())) {
                    sendJson(exchange, 403, "{\"success\":false,\"error\":\"权限不足，仅管理员可导出\"}");
                    return;
                }
                chain.doFilter(exchange);
            } catch (IOException e) {
                log.error("Filter 链异常: {}", e.getMessage());
            }
            return;
        }
        if (validation.isKicked()) {
            sendJson(exchange, 401, AuthResponses.kicked());
            return;
        }
        sendJson(exchange, 401, AuthResponses.unauthorized());
    }

    private boolean isWhitelisted(String path) {
        return WHITELIST_PREFIXES.stream().anyMatch(path::startsWith);
    }
    private void sendJson(HttpExchange exchange, int code, String json) {
        try {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        } catch (IOException ignored) {}
    }
    @Override
    public String description() { return "AuthFilter: Token 鉴权"; }
}
