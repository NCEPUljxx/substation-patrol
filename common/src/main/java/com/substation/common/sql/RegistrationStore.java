package com.substation.common.sql;

import com.substation.common.sql.model.RegistrationRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 注册申请管理 — 操作 registration_requests 表。
 */
public class RegistrationStore {

    private static final Logger log = LoggerFactory.getLogger(RegistrationStore.class);

    private final DatabaseManager db;

    public RegistrationStore(DatabaseManager db) {
        this.db = db;
    }

    /** 检查是否有同名的待审核记录 */
    public boolean hasPendingRequest(String username) {
        String sql = "SELECT COUNT(*) FROM registration_requests WHERE username=? AND status='pending'";
        try (Connection conn = db.getConnection();
             var ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            var rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1) > 0;
        } catch (SQLException e) {
            log.error("检查待审核记录失败: {}", e.getMessage(), e);
        }
        return false;
    }

    /** 插入注册申请 */
    public boolean insertRequest(String username, String passwordHash, String role,
                                  String displayName) {
        String sql = "INSERT INTO registration_requests (username, password, role, display_name) VALUES (?,?,?,?)";
        try (Connection conn = db.getConnection();
             var ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, passwordHash);
            ps.setString(3, role);
            ps.setString(4, displayName != null ? displayName : username);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            log.error("插入注册申请失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /** 查询注册申请列表 */
    public List<RegistrationRecord> queryRequests(String status, int page, int size) {
        List<RegistrationRecord> list = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT id, username, role, display_name, status, reviewed_by, review_time, created_at FROM registration_requests WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status=?");
            params.add(status);
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
                list.add(new RegistrationRecord(
                        rs.getInt("id"), rs.getString("username"), rs.getString("role"),
                        rs.getString("display_name"), rs.getString("status"),
                        rs.getString("reviewed_by"), rs.getString("review_time"),
                        rs.getString("created_at")));
            }
        } catch (SQLException e) {
            log.error("查询注册申请失败: {}", e.getMessage(), e);
        }
        return list;
    }

    /** 通过注册申请 */
    public boolean approve(int id, String reviewedBy) {
        String selSql = "SELECT username, password, role, display_name FROM registration_requests WHERE id=? AND status='pending'";
        Connection conn1 = null;
        try {
            conn1 = db.getConnection();
            var ps = conn1.prepareStatement(selSql);
            ps.setInt(1, id);
            var rs = ps.executeQuery();
            if (!rs.next()) return false;
            String username = rs.getString("username");
            String passwordHash = rs.getString("password");
            String role = rs.getString("role");
            String displayName = rs.getString("display_name");
            rs.close();
            ps.close();
            conn1.close();

            // 插入 users 表（新连接）
            String insSql = "INSERT INTO users (username, password, role, display_name) VALUES (?,?,?,?)";
            try (Connection conn2 = db.getConnection();
                 var ips = conn2.prepareStatement(insSql)) {
                ips.setString(1, username);
                ips.setString(2, passwordHash);
                ips.setString(3, role);
                ips.setString(4, displayName);
                ips.executeUpdate();
            }

            // 更新申请状态
            String updSql = "UPDATE registration_requests SET status='approved', reviewed_by=?, review_time=SYSUTCDATETIME() WHERE id=?";
            try (Connection conn3 = db.getConnection();
                 var ups = conn3.prepareStatement(updSql)) {
                ups.setString(1, reviewedBy);
                ups.setInt(2, id);
                ups.executeUpdate();
            }
            return true;
        } catch (SQLException e) {
            log.error("通过注册申请失败: {}", e.getMessage(), e);
            return false;
        } finally {
            if (conn1 != null) try { conn1.close(); } catch (SQLException ignored) {}
        }
    }

    /** 拒绝注册申请 */
    public boolean reject(int id, String reviewedBy) {
        String sql = "UPDATE registration_requests SET status='rejected', reviewed_by=?, review_time=SYSUTCDATETIME() WHERE id=? AND status='pending'";
        try (Connection conn = db.getConnection();
             var ps = conn.prepareStatement(sql)) {
            ps.setString(1, reviewedBy);
            ps.setInt(2, id);
            int affected = ps.executeUpdate();
            return affected > 0;
        } catch (SQLException e) {
            log.error("拒绝注册申请失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /** 查询注册申请总数 */
    public int countRequests(String status) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM registration_requests WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status=?");
            params.add(status);
        }
        try (Connection conn = db.getConnection();
             var ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            var rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            log.error("统计注册申请失败: {}", e.getMessage(), e);
        }
        return 0;
    }
}
