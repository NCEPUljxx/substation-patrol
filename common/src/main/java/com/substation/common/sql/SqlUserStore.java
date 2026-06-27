package com.substation.common.sql;

import com.substation.common.auth.model.UserInfo;
import com.substation.common.sql.model.UserRecord;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQL Server 用户存储，替代原 Redis UserStore。
 */
public class SqlUserStore {

    private static final Logger log = LoggerFactory.getLogger(SqlUserStore.class);

    private final DatabaseManager db;

    public SqlUserStore(DatabaseManager db) {
        this.db = db;
    }

    /** 验证用户名密码 */
    public Optional<UserInfo> authenticate(String username, String password) {
        String sql = "SELECT username, role, display_name, password FROM users WHERE username=? AND status='active'";
        try (Connection conn = db.getConnection();
             var ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            var rs = ps.executeQuery();
            if (rs.next()) {
                String hash = rs.getString("password");
                if (hash != null && BCrypt.checkpw(password, hash)) {
                    return Optional.of(new UserInfo(
                            rs.getString("username"),
                            rs.getString("role"),
                            rs.getString("display_name")));
                }
            }
        } catch (SQLException e) {
            log.error("认证查询失败: {}", e.getMessage(), e);
        }
        return Optional.empty();
    }

    /** 修改密码 */
    public boolean changePassword(String username, String oldPassword, String newPassword) {
        String sql = "SELECT password FROM users WHERE username=?";
        try (Connection conn = db.getConnection();
             var ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            var rs = ps.executeQuery();
            if (!rs.next()) return false;
            if (!BCrypt.checkpw(oldPassword, rs.getString("password"))) return false;
        } catch (SQLException e) {
            log.error("改密查询失败: {}", e.getMessage(), e);
            return false;
        }
        String updateSql = "UPDATE users SET password=? WHERE username=?";
        try (Connection conn = db.getConnection();
             var ps = conn.prepareStatement(updateSql)) {
            ps.setString(1, BCrypt.hashpw(newPassword, BCrypt.gensalt()));
            ps.setString(2, username);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("改密更新失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /** 获取用户信息 */
    public Optional<UserInfo> getUserInfo(String username) {
        String sql = "SELECT username, role, display_name FROM users WHERE username=?";
        try (Connection conn = db.getConnection();
             var ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            var rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(new UserInfo(
                        rs.getString("username"),
                        rs.getString("role"),
                        rs.getString("display_name")));
            }
        } catch (SQLException e) {
            log.error("查询用户信息失败: {}", e.getMessage(), e);
        }
        return Optional.empty();
    }

    /** 查询用户列表（管理员视角，不返回密码），支持搜索和角色筛选 */
    public List<UserRecord> queryUsers(String search, String role, int page, int size) {
        List<UserRecord> list = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT username, role, display_name, status, created_at FROM users WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (search != null && !search.isBlank()) {
            sql.append(" AND (username LIKE ? OR display_name LIKE ?)");
            String kw = "%" + search + "%";
            params.add(kw);
            params.add(kw);
        }
        if (role != null && !role.isBlank()) {
            sql.append(" AND role=?");
            params.add(role);
        }
        sql.append(" ORDER BY created_at DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
        params.add((page - 1) * size);
        params.add(size);

        try (Connection conn = db.getConnection();
             var ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            var rs = ps.executeQuery();
            while (rs.next()) {
                list.add(new UserRecord(
                        rs.getString("username"), rs.getString("role"),
                        rs.getString("display_name"), rs.getString("status"),
                        rs.getString("created_at")));
            }
        } catch (SQLException e) {
            log.error("查询用户列表失败: {}", e.getMessage(), e);
        }
        return list;
    }

    /** 查询用户总数 */
    public int countUsers(String search, String role) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM users WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (search != null && !search.isBlank()) {
            sql.append(" AND (username LIKE ? OR display_name LIKE ?)");
            String kw = "%" + search + "%";
            params.add(kw);
            params.add(kw);
        }
        if (role != null && !role.isBlank()) {
            sql.append(" AND role=?");
            params.add(role);
        }
        try (Connection conn = db.getConnection();
             var ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            var rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            log.error("统计用户数失败: {}", e.getMessage(), e);
        }
        return 0;
    }

    /** 管理员重置用户密码为 123456 */
    public boolean resetPassword(String username) {
        String sql = "UPDATE users SET password=? WHERE username=?";
        try (Connection conn = db.getConnection();
             var ps = conn.prepareStatement(sql)) {
            ps.setString(1, BCrypt.hashpw("123456", BCrypt.gensalt()));
            ps.setString(2, username);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("重置密码失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /** 将审核通过的用户写入 users 表 */
    public boolean insertApprovedUser(String username, String passwordHash, String role,
                                       String displayName) {
        String sql = "INSERT INTO users (username, password, role, display_name) VALUES (?,?,?,?)";
        try (Connection conn = db.getConnection();
             var ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, passwordHash);
            ps.setString(3, role);
            ps.setString(4, displayName != null ? displayName : username);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            log.error("插入用户失败: {}", e.getMessage(), e);
            return false;
        }
    }
}
