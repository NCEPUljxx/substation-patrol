package com.substation.common.auth;

import com.alibaba.fastjson2.JSON;
import com.substation.common.auth.model.SessionInfo;
import com.substation.common.auth.model.SessionValidation;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 基于 Redis 的会话管理。
 * 同一用户全局仅保留一个有效会话；新登录挤掉旧会话。
 */
public class SessionManager {

    public static final String KICKED_MESSAGE = "您的账号已在其他设备或窗口登录，当前会话已结束";

    private static final String SESSION_KEY_PREFIX = "auth:session:";
    private static final String USER_SESSION_PREFIX = "auth:user_session:";
    private static final String KICKED_PREFIX = "auth:kicked:";
    private static final int SESSION_TTL_SECONDS = 1800;
    private static final int KICKED_FLAG_TTL_SECONDS = 120;
    private static final int TOKEN_BYTE_LENGTH = 32;

    private final JedisPool pool;
    private final SecureRandom secureRandom = new SecureRandom();

    public SessionManager(JedisPool pool) {
        this.pool = pool;
    }

    /** 创建会话并绑定为用户唯一活跃会话，挤掉旧会话 */
    public String createSession(String username, String role) {
        byte[] bytes = new byte[TOKEN_BYTE_LENGTH];
        secureRandom.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);

        long now = System.currentTimeMillis() / 1000;
        SessionInfo session = new SessionInfo(username, role, now, now);

        try (Jedis jedis = pool.getResource()) {
            invalidatePreviousSession(jedis, username);
            jedis.setex(SESSION_KEY_PREFIX + token, SESSION_TTL_SECONDS, JSON.toJSONString(session));
            jedis.setex(USER_SESSION_PREFIX + username, SESSION_TTL_SECONDS, token);
        }
        return token;
    }

    /** 校验 token 并续期；无效或已被挤号时返回对应状态 */
    public SessionValidation validateDetailed(String token) {
        if (token == null || token.isBlank()) {
            return SessionValidation.notFound();
        }
        try (Jedis jedis = pool.getResource()) {
            if (jedis.exists(KICKED_PREFIX + token)) {
                jedis.del(KICKED_PREFIX + token);
                return SessionValidation.kicked();
            }

            String sessionKey = SESSION_KEY_PREFIX + token;
            String json = jedis.get(sessionKey);
            if (json == null) {
                return SessionValidation.notFound();
            }

            SessionInfo session = JSON.parseObject(json, SessionInfo.class);
            String activeToken = jedis.get(USER_SESSION_PREFIX + session.username());
            if (!token.equals(activeToken)) {
                return SessionValidation.kicked();
            }

            SessionInfo renewed = renewSession(jedis, sessionKey, session);
            jedis.expire(USER_SESSION_PREFIX + session.username(), SESSION_TTL_SECONDS);
            return SessionValidation.valid(renewed);
        }
    }

    /** 校验 token，返回会话信息；无效或已被挤号返回空 */
    public Optional<SessionInfo> validate(String token) {
        SessionValidation validation = validateDetailed(token);
        return validation.isValid() ? Optional.of(validation.session()) : Optional.empty();
    }

    /** 销毁会话 */
    public void destroySession(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        try (Jedis jedis = pool.getResource()) {
            String sessionKey = SESSION_KEY_PREFIX + token;
            String json = jedis.get(sessionKey);
            jedis.del(sessionKey);
            jedis.del(KICKED_PREFIX + token);
            if (json == null) {
                return;
            }
            SessionInfo session = JSON.parseObject(json, SessionInfo.class);
            String userKey = USER_SESSION_PREFIX + session.username();
            if (token.equals(jedis.get(userKey))) {
                jedis.del(userKey);
            }
        }
    }

    /** 从 HTTP Authorization header 提取 Bearer token */
    public static String extractToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        return authHeader.substring(7).trim();
    }

    private void invalidatePreviousSession(Jedis jedis, String username) {
        String oldToken = jedis.get(USER_SESSION_PREFIX + username);
        if (oldToken == null || oldToken.isBlank()) {
            return;
        }
        jedis.setex(KICKED_PREFIX + oldToken, KICKED_FLAG_TTL_SECONDS, "1");
        jedis.del(SESSION_KEY_PREFIX + oldToken);
    }

    private SessionInfo renewSession(Jedis jedis, String sessionKey, SessionInfo session) {
        SessionInfo renewed = new SessionInfo(
                session.username(), session.role(), session.loginAt(), System.currentTimeMillis() / 1000);
        jedis.setex(sessionKey, SESSION_TTL_SECONDS, JSON.toJSONString(renewed));
        return renewed;
    }
}
