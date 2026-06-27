package com.substation.common.auth;

import com.substation.common.auth.model.LoginResponse;
import com.substation.common.sql.OperationLogStore;
import com.substation.common.sql.RegistrationStore;
import com.substation.common.sql.SqlUserStore;
import com.sun.net.httpserver.HttpExchange;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

/**
 * 认证 API 处理器，使用 SQL Server 用户存储。
 * 注册走审核流程（写 registration_requests 表），管理员通过后才生效。
 */
public class AuthApiHandler {

    private static final Logger log = LoggerFactory.getLogger(AuthApiHandler.class);

    private final SqlUserStore sqlUserStore;
    private final RegistrationStore regStore;
    private final OperationLogStore logStore;
    private final SessionManager sessionManager;

    public AuthApiHandler(SqlUserStore sqlUserStore, RegistrationStore regStore,
                           OperationLogStore logStore, SessionManager sessionManager) {
        this.sqlUserStore = sqlUserStore;
        this.regStore = regStore;
        this.logStore = logStore;
        this.sessionManager = sessionManager;
    }

    public void handle(String path, HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        String method = exchange.getRequestMethod();
        String subPath = path.replace("/api/auth", "");

        try {
            switch (subPath) {
                case "/register" -> { if ("POST".equals(method)) handleRegister(exchange); else send405(exchange); }
                case "/login"    -> { if ("POST".equals(method)) handleLogin(exchange);    else send405(exchange); }
                case "/logout"   -> { if ("POST".equals(method)) handleLogout(exchange);   else send405(exchange); }
                case "/me"       -> { if ("GET".equals(method))  handleMe(exchange);       else send405(exchange); }
                case "/change-password" ->
                    { if ("POST".equals(method)) handleChangePassword(exchange); else send405(exchange); }
                default -> send404(exchange);
            }
        } catch (Exception e) {
            log.error("Auth API 异常: {}", e.getMessage(), e);
            send500(exchange);
        }
    }

    private void handleRegister(HttpExchange exchange) throws IOException {
        JSONObject body = readBody(exchange);
        if (body == null) { sendJson(exchange, 400, "{\"success\":false,\"error\":\"请求体为空\"}"); return; }
        String username = body.getString("username");
        String password = body.getString("password");
        String role = body.getString("role");
        String displayName = body.getString("displayName");

        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            sendJson(exchange, 400, "{\"success\":false,\"error\":\"用户名和密码不能为空\"}"); return;
        }
        if (password.length() < 6) {
            sendJson(exchange, 400, "{\"success\":false,\"error\":\"密码至少需要6位\"}"); return;
        }
        if (role == null || (!"simulator".equals(role) && !"analyst".equals(role))) {
            sendJson(exchange, 400, "{\"success\":false,\"error\":\"无效的角色，可选: simulator, analyst\"}"); return;
        }
        if (regStore.hasPendingRequest(username)) {
            sendJson(exchange, 400, "{\"success\":false,\"error\":\"该账号正在审核中，请勿重复提交\"}");
            logStore.log(username, "REGISTER_DUPLICATE", username, "重复注册-审核中");
            return;
        }
        String hash = BCrypt.hashpw(password, BCrypt.gensalt());
        boolean ok = regStore.insertRequest(username, hash, role, displayName != null ? displayName : username);
        if (ok) {
            logStore.log(username, "REGISTER", username, "提交注册申请,角色=" + role);
            sendJson(exchange, 200, "{\"success\":true,\"message\":\"注册申请已提交，请等待管理员审核\"}");
        } else {
            sendJson(exchange, 500, "{\"success\":false,\"error\":\"提交失败，请稍后重试\"}");
        }
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        JSONObject body = readBody(exchange);
        if (body == null) { sendJson(exchange, 400, LoginResponse.fail("请求体为空").toJson()); return; }
        String username = body.getString("username");
        String password = body.getString("password");
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            sendJson(exchange, 400, LoginResponse.fail("用户名和密码不能为空").toJson()); return;
        }
        sqlUserStore.authenticate(username, password)
                .map(user -> {
                    String token = sessionManager.createSession(user.username(), user.role());
                    return LoginResponse.ok(token, user.username(), user.role(), user.displayName());
                })
                .ifPresentOrElse(
                        resp -> { sendJson(exchange, 200, resp.toJson()); logStore.log(username, "LOGIN", null, "登录成功"); },
                        () -> sendJson(exchange, 401, LoginResponse.fail("用户名或密码错误").toJson()));
    }

    private void handleLogout(HttpExchange exchange) throws IOException {
        String token = SessionManager.extractToken(exchange.getRequestHeaders().getFirst("Authorization"));
        sessionManager.destroySession(token);
        sendJson(exchange, 200, "{\"success\":true,\"message\":\"已退出登录\"}");
    }

    private void handleMe(HttpExchange exchange) throws IOException {
        String token = SessionManager.extractToken(exchange.getRequestHeaders().getFirst("Authorization"));
        var validation = sessionManager.validateDetailed(token);
        if (validation.isKicked()) {
            sendJson(exchange, 401, AuthResponses.kicked());
            return;
        }
        if (!validation.isValid()) {
            sendJson(exchange, 401, AuthResponses.unauthorized());
            return;
        }
        var session = validation.session();
        sqlUserStore.getUserInfo(session.username()).ifPresentOrElse(
                user -> sendJson(exchange, 200, JSON.toJSONString(
                        java.util.Map.of("success", true, "username", user.username(),
                                "role", user.role(), "displayName", user.displayName()))),
                () -> sendJson(exchange, 401, "{\"success\":false,\"error\":\"用户不存在\"}"));
    }

    private void handleChangePassword(HttpExchange exchange) throws IOException {
        String token = SessionManager.extractToken(exchange.getRequestHeaders().getFirst("Authorization"));
        sessionManager.validate(token).ifPresentOrElse(session -> {
            JSONObject body = readBody(exchange);
            if (body == null) { sendJson(exchange, 400, "{\"success\":false,\"error\":\"请求体为空\"}"); return; }
            String oldPwd = body.getString("oldPassword");
            String newPwd = body.getString("newPassword");
            if (newPwd == null || newPwd.length() < 6) {
                sendJson(exchange, 400, "{\"success\":false,\"error\":\"新密码至少6位\"}"); return;
            }
            if (sqlUserStore.changePassword(session.username(), oldPwd, newPwd)) {
                sessionManager.destroySession(token);
                logStore.log(session.username(), "CHANGE_PASSWORD", null, "密码已修改");
                sendJson(exchange, 200, "{\"success\":true,\"message\":\"密码修改成功，请重新登录\"}");
            } else {
                sendJson(exchange, 400, "{\"success\":false,\"error\":\"旧密码不正确\"}");
            }
        }, () -> sendJson(exchange, 401, "{\"success\":false,\"error\":\"请先登录\"}"));
    }

    private JSONObject readBody(HttpExchange exchange) {
        try {
            InputStream is = exchange.getRequestBody();
            String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return body.isBlank() ? null : JSON.parseObject(body);
        } catch (IOException e) { return null; }
    }
    private void sendJson(HttpExchange exchange, int code, String json) {
        try {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        } catch (IOException e) { log.error("响应写入失败", e); }
    }
    private void send404(HttpExchange exchange) { sendJson(exchange, 404, "404"); }
    private void send405(HttpExchange exchange) { sendJson(exchange, 405, "405"); }
    private void send500(HttpExchange exchange) { sendJson(exchange, 500, "{\"success\":false,\"error\":\"服务器内部错误\"}"); }
}
