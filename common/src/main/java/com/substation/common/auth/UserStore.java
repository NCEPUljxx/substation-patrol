package com.substation.common.auth;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.substation.common.auth.model.UserInfo;
import org.mindrot.jbcrypt.BCrypt;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.Optional;
import java.util.Set;

/**
 * Redis 用户存储，使用 HSET 操作 auth:users Hash。
 * 系统启动时自动创建预设管理员和普通用户账号。
 */
public class UserStore {

    private static final String KEY_USERS = "auth:users";
    private static final int LOGIN_FAIL_MAX = 5;
    private static final int LOGIN_LOCK_SECONDS = 900;

    private final JedisPool pool;

    public UserStore(JedisPool pool) {
        this.pool = pool;
    }

    /** 初始化预设账号（幂等，已有则跳过） */
    public void initPresetUsers() {
        createUserIfAbsent("admin", "admin123", "admin", "管理员");
        createUserIfAbsent("simulator1", "sim123", "simulator", "仿真员1");
        createUserIfAbsent("analyst1", "ana123", "analyst", "统计分析员1");
    }

    /** 验证用户名密码，返回用户信息；失败返回空 */
    public Optional<UserInfo> authenticate(String username, String password) {
        try (Jedis jedis = pool.getResource()) {
            String failKey = "auth:fail:" + username;
            String failCount = jedis.get(failKey);
            if (failCount != null && Integer.parseInt(failCount) >= LOGIN_FAIL_MAX) {
                return Optional.empty(); // 已锁定
            }

            String json = jedis.hget(KEY_USERS, username);
            if (json == null) {
                recordLoginFail(jedis, failKey);
                return Optional.empty();
            }
            JSONObject user = JSON.parseObject(json);
            String hash = user.getString("passwordHash");
            if (hash == null || !BCrypt.checkpw(password, hash)) {
                recordLoginFail(jedis, failKey);
                return Optional.empty();
            }

            jedis.del(failKey); // 成功后清除失败计数
            return Optional.of(new UserInfo(username,
                    user.getString("role"),
                    user.getString("displayName")));
        }
    }

    /** 修改密码 */
    public boolean changePassword(String username, String oldPassword, String newPassword) {
        try (Jedis jedis = pool.getResource()) {
            String json = jedis.hget(KEY_USERS, username);
            if (json == null) return false;
            JSONObject user = JSON.parseObject(json);
            String hash = user.getString("passwordHash");
            if (hash == null || !BCrypt.checkpw(oldPassword, hash)) {
                return false;
            }
            user.put("passwordHash", BCrypt.hashpw(newPassword, BCrypt.gensalt()));
            jedis.hset(KEY_USERS, username, user.toJSONString());
            return true;
        }
    }

    /**
     * 注册新用户。
     * @param username    用户名（不可重复）
     * @param password    明文密码（≥6位）
     * @param role        角色（simulator 或 analyst，admin 只能由预设创建）
     * @param displayName 显示名称
     * @return 注册结果：success 为 true 表示成功，否则 error 包含原因
     */
    public record RegisterResult(boolean success, String error) {
        public static RegisterResult ok() { return new RegisterResult(true, null); }
        public static RegisterResult fail(String error) { return new RegisterResult(false, error); }
    }

    /** 允许用户自行注册的角色 */
    private static final Set<String> ALLOWED_SELF_REGISTER_ROLES = Set.of("simulator", "analyst");

    public RegisterResult register(String username, String password, String role,
                                    String displayName) {
        if (username == null || username.isBlank()) {
            return RegisterResult.fail("用户名不能为空");
        }
        if (password == null || password.length() < 6) {
            return RegisterResult.fail("密码至少需要6位");
        }
        if (role == null || !ALLOWED_SELF_REGISTER_ROLES.contains(role)) {
            return RegisterResult.fail("无效的角色，可选: simulator, analyst");
        }
        try (Jedis jedis = pool.getResource()) {
            if (jedis.hexists(KEY_USERS, username)) {
                return RegisterResult.fail("用户名已存在");
            }
            String display = (displayName != null && !displayName.isBlank())
                    ? displayName : username;
            JSONObject user = new JSONObject();
            user.put("passwordHash", BCrypt.hashpw(password, BCrypt.gensalt()));
            user.put("role", role);
            user.put("displayName", display);
            user.put("createdAt", System.currentTimeMillis() / 1000);
            jedis.hset(KEY_USERS, username, user.toJSONString());
            return RegisterResult.ok();
        }
    }

    /** 获取用户信息（不含密码） */
    public Optional<UserInfo> getUserInfo(String username) {
        try (Jedis jedis = pool.getResource()) {
            String json = jedis.hget(KEY_USERS, username);
            if (json == null) return Optional.empty();
            JSONObject user = JSON.parseObject(json);
            return Optional.of(new UserInfo(username,
                    user.getString("role"),
                    user.getString("displayName")));
        }
    }

    private void createUserIfAbsent(String username, String password, String role,
                                     String displayName) {
        try (Jedis jedis = pool.getResource()) {
            if (jedis.hexists(KEY_USERS, username)) return;
            JSONObject user = new JSONObject();
            user.put("passwordHash", BCrypt.hashpw(password, BCrypt.gensalt()));
            user.put("role", role);
            user.put("displayName", displayName);
            user.put("createdAt", System.currentTimeMillis() / 1000);
            jedis.hset(KEY_USERS, username, user.toJSONString());
        }
    }

    private void recordLoginFail(Jedis jedis, String failKey) {
        jedis.incr(failKey);
        jedis.expire(failKey, LOGIN_LOCK_SECONDS);
    }
}
