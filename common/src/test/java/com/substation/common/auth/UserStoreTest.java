package com.substation.common.auth;

import com.substation.common.auth.model.UserInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class UserStoreTest {

    private JedisPool pool;
    private UserStore userStore;

    @BeforeEach
    void setUp() {
        pool = new JedisPool("localhost", 6379);
        try (Jedis jedis = pool.getResource()) { jedis.flushDB(); }
        userStore = new UserStore(pool);
    }

    @AfterEach
    void tearDown() {
        try (Jedis jedis = pool.getResource()) { jedis.flushDB(); }
        pool.close();
    }

    @Test
    void initPresetUsersCreatesThreeAccounts() {
        userStore.initPresetUsers();

        assertTrue(userStore.authenticate("admin", "admin123").isPresent());
        assertTrue(userStore.authenticate("simulator1", "sim123").isPresent());
        assertTrue(userStore.authenticate("analyst1", "ana123").isPresent());
    }

    @Test
    void initPresetUsersIsIdempotent() {
        userStore.initPresetUsers();
        userStore.initPresetUsers(); // 第二次不报错

        assertTrue(userStore.authenticate("admin", "admin123").isPresent());
    }

    @Test
    void authenticateReturnsEmptyOnWrongPassword() {
        userStore.initPresetUsers();

        Optional<UserInfo> result = userStore.authenticate("admin", "wrong");
        assertTrue(result.isEmpty());
    }

    @Test
    void authenticateReturnsEmptyOnUnknownUser() {
        userStore.initPresetUsers();

        Optional<UserInfo> result = userStore.authenticate("nobody", "x");
        assertTrue(result.isEmpty());
    }

    @Test
    void authenticateReturnsCorrectRole() {
        userStore.initPresetUsers();

        UserInfo admin = userStore.authenticate("admin", "admin123").orElseThrow();
        assertEquals("admin", admin.role());
        assertEquals("管理员", admin.displayName());

        UserInfo sim = userStore.authenticate("simulator1", "sim123").orElseThrow();
        assertEquals("simulator", sim.role());

        UserInfo ana = userStore.authenticate("analyst1", "ana123").orElseThrow();
        assertEquals("analyst", ana.role());
    }

    @Test
    void changePasswordWorks() {
        userStore.initPresetUsers();

        assertTrue(userStore.changePassword("admin", "admin123", "newPass123"));
        // 旧密码失效
        assertTrue(userStore.authenticate("admin", "admin123").isEmpty());
        // 新密码可用
        assertTrue(userStore.authenticate("admin", "newPass123").isPresent());
    }

    @Test
    void changePasswordFailsOnWrongOldPassword() {
        userStore.initPresetUsers();

        assertFalse(userStore.changePassword("admin", "wrongOld", "newPass"));
        // 原密码仍可用
        assertTrue(userStore.authenticate("admin", "admin123").isPresent());
    }

    @Test
    void getUserInfoReturnsCorrectInfo() {
        userStore.initPresetUsers();

        UserInfo info = userStore.getUserInfo("admin").orElseThrow();
        assertEquals("admin", info.role());
        assertEquals("管理员", info.displayName());
    }

    @Test
    void getUserInfoReturnsEmptyOnUnknownUser() {
        assertTrue(userStore.getUserInfo("nobody").isEmpty());
    }

    // ==================== 注册测试 ====================

    @Test
    void registerCreatesNewUser() {
        UserStore.RegisterResult r = userStore.register(
                "newsim", "pass123456", "simulator", "新仿真员");
        assertTrue(r.success());

        Optional<UserInfo> user = userStore.authenticate("newsim", "pass123456");
        assertTrue(user.isPresent());
        assertEquals("simulator", user.get().role());
        assertEquals("新仿真员", user.get().displayName());
    }

    @Test
    void registerFailsOnDuplicateUsername() {
        userStore.initPresetUsers();
        UserStore.RegisterResult r = userStore.register(
                "admin", "pass123", "simulator", "xxx");
        assertFalse(r.success());
        assertTrue(r.error().contains("已存在"));
    }

    @Test
    void registerFailsOnShortPassword() {
        UserStore.RegisterResult r = userStore.register(
                "test", "12345", "simulator", "test");
        assertFalse(r.success());
        assertTrue(r.error().contains("6位"));
    }

    @Test
    void registerFailsOnBlankUsername() {
        UserStore.RegisterResult r = userStore.register(
                "", "pass123", "simulator", "test");
        assertFalse(r.success());
    }

    @Test
    void registerFailsOnInvalidRole() {
        UserStore.RegisterResult r = userStore.register(
                "test", "pass123", "admin", "test");
        assertFalse(r.success());
        assertTrue(r.error().contains("无效的角色"));
    }

    @Test
    void registerAnalystRoleWorks() {
        UserStore.RegisterResult r = userStore.register(
                "newanalyst", "ana123456", "analyst", "分析员");
        assertTrue(r.success());

        Optional<UserInfo> user = userStore.authenticate("newanalyst", "ana123456");
        assertTrue(user.isPresent());
        assertEquals("analyst", user.get().role());
    }

    @Test
    void registerUsesUsernameAsDefaultDisplayName() {
        UserStore.RegisterResult r = userStore.register(
                "simX", "pass123456", "simulator", null);
        assertTrue(r.success());

        UserInfo info = userStore.getUserInfo("simX").orElseThrow();
        assertEquals("simX", info.displayName());
    }
}
