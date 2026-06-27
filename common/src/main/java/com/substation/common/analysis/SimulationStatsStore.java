package com.substation.common.analysis;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.substation.common.analysis.model.SimulationStatsSummary;
import com.substation.common.sql.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 仿真场次统计关系表（run_id → simulation_runs）持久化。 */
public class SimulationStatsStore {

    private static final Logger LOG = LoggerFactory.getLogger(SimulationStatsStore.class);
    private static final int SQL_DUPLICATE_KEY = 2627;

    private final DatabaseManager db;

    public SimulationStatsStore(DatabaseManager db) {
        this.db = db;
    }

    public long save(long runId, String savedBy, JSONObject payload) {
        String sql = """
                INSERT INTO simulation_run_stats (
                    run_id, saved_by, saved_at, client_timestamp, exploration_rate, tick, duration,
                    total_steps, total_effective_steps, efficiency_percent, car_count,
                    algorithm, obstacle_ratio, map_width, map_height, balance_score, payload)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bindInsert(ps, runId, savedBy, payload);
            ps.executeUpdate();
            return runId;
        } catch (SQLException e) {
            if (e.getErrorCode() == SQL_DUPLICATE_KEY) {
                throw new IllegalStateException("该场次统计已存在: runId=" + runId);
            }
            if (e.getErrorCode() == 547) {
                throw new IllegalArgumentException("场次不存在: runId=" + runId);
            }
            throw new IllegalStateException("保存仿真统计失败: " + e.getMessage(), e);
        }
    }

    public boolean existsForRun(long runId) {
        String sql = "SELECT 1 FROM simulation_run_stats WHERE run_id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            LOG.warn("查询场次统计是否存在失败 runId={}: {}", runId, e.getMessage());
            return false;
        }
    }

    public List<SimulationStatsSummary> listRecent(int page, int size) {
        int offset = Math.max(0, (page - 1) * size);
        String sql = """
                SELECT run_id, saved_by, saved_at, client_timestamp, exploration_rate, tick, duration,
                       total_steps, total_effective_steps, efficiency_percent, car_count,
                       algorithm, obstacle_ratio, map_width, map_height, balance_score
                FROM simulation_run_stats
                ORDER BY run_id DESC
                OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
                """;
        List<SimulationStatsSummary> rows = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, offset);
            ps.setInt(2, size);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(mapSummary(rs));
                }
            }
        } catch (SQLException e) {
            LOG.warn("查询仿真统计列表失败: {}", e.getMessage());
        }
        return rows;
    }

    public Optional<JSONObject> findPayloadByRunId(long runId) {
        String sql = """
                SELECT s.run_id, s.saved_by, s.saved_at, s.payload,
                       r.started_by AS run_started_by, r.started_at AS run_started_at
                FROM simulation_run_stats s
                INNER JOIN simulation_runs r ON r.id = s.run_id
                WHERE s.run_id = ?
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mergeRecord(rs));
            }
        } catch (SQLException e) {
            LOG.warn("查询仿真统计 runId={} 失败: {}", runId, e.getMessage());
            return Optional.empty();
        }
    }

    public List<JSONObject> listFullRecords(int page, int size) {
        int offset = Math.max(0, (page - 1) * size);
        String sql = """
                SELECT s.run_id, s.saved_by, s.saved_at, s.payload,
                       r.started_by AS run_started_by, r.started_at AS run_started_at
                FROM simulation_run_stats s
                INNER JOIN simulation_runs r ON r.id = s.run_id
                ORDER BY s.run_id DESC
                OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
                """;
        List<JSONObject> rows = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, offset);
            ps.setInt(2, size);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(mergeRecord(rs));
                }
            }
        } catch (SQLException e) {
            LOG.warn("查询仿真统计详情列表失败: {}", e.getMessage());
        }
        return rows;
    }

    public boolean deleteByRunId(long runId) {
        String sql = "DELETE FROM simulation_run_stats WHERE run_id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, runId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOG.warn("删除仿真统计 runId={} 失败: {}", runId, e.getMessage());
            return false;
        }
    }

    private static void bindInsert(PreparedStatement ps, long runId, String savedBy, JSONObject payload)
            throws SQLException {
        ps.setLong(1, runId);
        ps.setString(2, savedBy);
        ps.setTimestamp(3, Timestamp.from(Instant.now()));
        ps.setLong(4, payload.getLongValue("timestamp"));
        ps.setInt(5, payload.getIntValue("explorationRate"));
        ps.setInt(6, payload.getIntValue("tick"));
        ps.setInt(7, payload.getIntValue("duration"));
        ps.setInt(8, payload.getIntValue("totalSteps"));
        ps.setInt(9, payload.getIntValue("totalEffectiveSteps"));
        ps.setInt(10, payload.getIntValue("efficiencyPercent"));
        ps.setInt(11, payload.getIntValue("carCount"));
        ps.setString(12, payload.getString("algorithm"));
        ps.setDouble(13, payload.getDoubleValue("obstacleRatio"));
        ps.setInt(14, payload.getIntValue("mapWidth"));
        ps.setInt(15, payload.getIntValue("mapHeight"));
        ps.setDouble(16, payload.getDoubleValue("balanceScore"));
        ps.setString(17, JSON.toJSONString(payload));
    }

    private static SimulationStatsSummary mapSummary(ResultSet rs) throws SQLException {
        return new SimulationStatsSummary(
                rs.getLong("run_id"),
                rs.getString("saved_by"),
                rs.getTimestamp("saved_at").toInstant(),
                rs.getInt("exploration_rate"),
                rs.getInt("tick"),
                rs.getInt("duration"),
                rs.getInt("total_steps"),
                rs.getInt("total_effective_steps"),
                rs.getInt("efficiency_percent"),
                rs.getInt("car_count"),
                rs.getString("algorithm"),
                rs.getDouble("obstacle_ratio"),
                rs.getInt("map_width"),
                rs.getInt("map_height"),
                rs.getDouble("balance_score"),
                rs.getLong("client_timestamp"));
    }

    private static JSONObject mergeRecord(ResultSet rs) throws SQLException {
        JSONObject merged = JSON.parseObject(rs.getString("payload"));
        long runId = rs.getLong("run_id");
        merged.put("runId", runId);
        merged.put("id", runId);
        merged.put("savedBy", rs.getString("saved_by"));
        merged.put("savedAt", rs.getTimestamp("saved_at").toInstant().toString());
        merged.put("runStartedBy", rs.getString("run_started_by"));
        Timestamp runStartedAt = rs.getTimestamp("run_started_at");
        if (runStartedAt != null) {
            merged.put("runStartedAt", runStartedAt.toInstant().toString());
        }
        return merged;
    }
}
