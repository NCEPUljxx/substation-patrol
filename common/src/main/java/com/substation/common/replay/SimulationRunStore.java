package com.substation.common.replay;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.substation.common.replay.model.SimulationRunRecord;
import com.substation.common.replay.model.SimulationRunStatus;
import com.substation.common.replay.model.SimulationRunSummary;
import com.substation.common.sql.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 仿真场次 SQL Server 持久化。 */
public class SimulationRunStore {

    private static final Logger LOG = LoggerFactory.getLogger(SimulationRunStore.class);

    private final DatabaseManager db;

    public SimulationRunStore(DatabaseManager db) {
        this.db = db;
    }

    public long insert(SimulationRunRecord draft) {
        String sql = """
                INSERT INTO simulation_runs (
                    started_by, started_at, ended_at, map_width, map_height, car_count,
                    algorithm, obstacle_ratio, tick_interval, max_tick, exploration_rate, status,
                    map_block_b64, map_sealed_b64, map_view_final_b64,
                    car_histories, exploration_events)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bindInsert(ps, draft);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
            throw new SQLException("insert simulation_runs did not return id");
        } catch (SQLException e) {
            throw new IllegalStateException("保存仿真场次失败: " + e.getMessage(), e);
        }
    }

    public List<SimulationRunSummary> listRecent(int page, int size) {
        int offset = Math.max(0, (page - 1) * size);
        String sql = """
                SELECT r.id, r.started_by, r.started_at, r.ended_at, r.map_width, r.map_height, r.car_count,
                       r.algorithm, r.max_tick, r.exploration_rate, r.status,
                       1 AS has_stats
                FROM simulation_runs r
                INNER JOIN simulation_run_stats s ON s.run_id = r.id
                ORDER BY r.id DESC
                OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
                """;
        List<SimulationRunSummary> rows = new ArrayList<>();
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
            LOG.warn("查询仿真场次列表失败: {}", e.getMessage());
        }
        return rows;
    }

    public Optional<SimulationRunRecord> findById(long id) {
        String sql = "SELECT * FROM simulation_runs WHERE id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapRecord(rs));
            }
        } catch (SQLException e) {
            LOG.warn("查询仿真场次 {} 失败: {}", id, e.getMessage());
            return Optional.empty();
        }
    }

    private static void bindInsert(PreparedStatement ps, SimulationRunRecord draft) throws SQLException {
        ps.setString(1, draft.startedBy());
        ps.setTimestamp(2, Timestamp.from(draft.startedAt()));
        ps.setTimestamp(3, Timestamp.from(draft.endedAt()));
        ps.setInt(4, draft.mapWidth());
        ps.setInt(5, draft.mapHeight());
        ps.setInt(6, draft.carCount());
        ps.setString(7, draft.algorithm());
        ps.setString(8, draft.obstacleRatio());
        ps.setString(9, draft.tickInterval());
        ps.setInt(10, draft.maxTick());
        ps.setInt(11, draft.explorationRate());
        ps.setString(12, draft.status().name());
        ps.setString(13, draft.mapBlockB64());
        ps.setString(14, draft.mapSealedB64());
        ps.setString(15, draft.mapViewFinalB64());
        ps.setString(16, JSON.toJSONString(draft.carHistories()));
        ps.setString(17, JSON.toJSONString(draft.explorationEvents()));
    }

    public boolean existsById(long id) {
        String sql = "SELECT 1 FROM simulation_runs WHERE id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            LOG.warn("查询场次是否存在失败 id={}: {}", id, e.getMessage());
            return false;
        }
    }

    public boolean deleteById(long id) {
        String sql = "DELETE FROM simulation_runs WHERE id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOG.warn("删除仿真场次 id={} 失败: {}", id, e.getMessage());
            return false;
        }
    }

    private static SimulationRunSummary mapSummary(ResultSet rs) throws SQLException {
        return new SimulationRunSummary(
                rs.getLong("id"),
                rs.getString("started_by"),
                toInstant(rs.getTimestamp("started_at")),
                toInstant(rs.getTimestamp("ended_at")),
                rs.getInt("map_width"),
                rs.getInt("map_height"),
                rs.getInt("car_count"),
                rs.getString("algorithm"),
                rs.getInt("max_tick"),
                rs.getInt("exploration_rate"),
                SimulationRunStatus.valueOf(rs.getString("status")),
                rs.getInt("has_stats") == 1);
    }

    @SuppressWarnings("unchecked")
    private static SimulationRunRecord mapRecord(ResultSet rs) throws SQLException {
        JSONObject histories = JSON.parseObject(rs.getString("car_histories"));
        List<String> events = JSON.parseArray(rs.getString("exploration_events"), String.class);
        return new SimulationRunRecord(
                rs.getLong("id"),
                rs.getString("started_by"),
                toInstant(rs.getTimestamp("started_at")),
                toInstant(rs.getTimestamp("ended_at")),
                rs.getInt("map_width"),
                rs.getInt("map_height"),
                rs.getInt("car_count"),
                rs.getString("algorithm"),
                rs.getString("obstacle_ratio"),
                rs.getString("tick_interval"),
                rs.getInt("max_tick"),
                rs.getInt("exploration_rate"),
                SimulationRunStatus.valueOf(rs.getString("status")),
                rs.getString("map_block_b64"),
                rs.getString("map_sealed_b64"),
                rs.getString("map_view_final_b64"),
                histories.toJavaObject(Map.class),
                events != null ? events : List.of());
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp != null ? timestamp.toInstant() : Instant.now();
    }
}
