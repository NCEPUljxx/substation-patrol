package com.substation.common.sql;

import com.substation.common.sql.model.OperationLogRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 操作日志管理 — 记录用户登录后的关键操作。
 */
public class OperationLogStore {

    private static final Logger log = LoggerFactory.getLogger(OperationLogStore.class);

    private final DatabaseManager db;

    public OperationLogStore(DatabaseManager db) {
        this.db = db;
    }

    /** 写入操作日志 */
    public void log(String username, String action, String target, String details) {
        log(username, action, target, details, null);
    }

    public void log(String username, String action, String target, String details,
                     String ipAddress) {
        String sql = "INSERT INTO operation_logs (username, action, target, details, ip_address) VALUES (?,?,?,?,?)";
        try (Connection conn = db.getConnection();
             var ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, action);
            ps.setString(3, target);
            ps.setString(4, details);
            ps.setString(5, ipAddress);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("写入操作日志失败: {}", e.getMessage());
        }
    }

    /** 查询操作日志 */
    public List<OperationLogRecord> queryLogs(String username, String action,
                                               int page, int size) {
        List<OperationLogRecord> list = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT id, username, action, target, details, created_at FROM operation_logs WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (username != null && !username.isBlank()) {
            sql.append(" AND username=?");
            params.add(username);
        }
        if (action != null && !action.isBlank()) {
            sql.append(" AND action=?");
            params.add(action);
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
                Timestamp ts = rs.getTimestamp("created_at");
                String beijingTime = "";
                if (ts != null) {
                    beijingTime = ts.toInstant()
                            .atZone(ZoneId.of("UTC"))
                            .withZoneSameInstant(ZoneId.of("Asia/Shanghai"))
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                }
                list.add(new OperationLogRecord(
                        rs.getInt("id"), rs.getString("username"),
                        rs.getString("action"), rs.getString("target"),
                        rs.getString("details"), beijingTime));
            }
        } catch (SQLException e) {
            log.error("查询操作日志失败: {}", e.getMessage(), e);
        }
        return list;
    }

    /** 查询操作日志总数 */
    public int countLogs(String username, String action) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM operation_logs WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (username != null && !username.isBlank()) {
            sql.append(" AND username=?");
            params.add(username);
        }
        if (action != null && !action.isBlank()) {
            sql.append(" AND action=?");
            params.add(action);
        }
        try (Connection conn = db.getConnection();
             var ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            var rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            log.error("统计操作日志失败: {}", e.getMessage(), e);
        }
        return 0;
    }
}
