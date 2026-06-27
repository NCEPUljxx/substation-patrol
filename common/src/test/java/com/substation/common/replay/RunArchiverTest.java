package com.substation.common.replay;

import com.substation.common.model.Point;
import com.substation.common.replay.model.SimulationRunStatus;
import com.substation.common.redis.BlackboardClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import static org.junit.jupiter.api.Assertions.*;

class RunArchiverTest {

    private JedisPool pool;
    private BlackboardClient blackboard;

    @BeforeEach
    void setUp() {
        pool = new JedisPool("localhost", 6379);
        try (Jedis jedis = pool.getResource()) {
            jedis.flushDB();
        }
        blackboard = new BlackboardClient("localhost", 6379, 10, 10);
        blackboard.initTaskConfig(java.util.Map.of(
                "mapWidth", "10",
                "mapHeight", "10",
                "algorithm", "BFS"));
    }

    @AfterEach
    void tearDown() {
        blackboard.close();
        try (Jedis jedis = pool.getResource()) {
            jedis.flushDB();
        }
        pool.close();
    }

    @Test
    void hasReplayableDataFalseWhenEmpty() {
        assertFalse(blackboard.hasReplayableData());
    }

    @Test
    void hasReplayableDataTrueWhenHistoryExists() {
        blackboard.appendCarHistory("Car001", new Point(1, 2), 1);
        assertTrue(blackboard.hasReplayableData());
    }

    @Test
    void archiveMarksRunAsArchived() {
        blackboard.beginSimRun("admin");
        blackboard.appendCarHistory("Car001", new Point(1, 2), 1);
        SimulationRunStore store = new SimulationRunStore(new com.substation.common.sql.DatabaseManager());
        RunArchiver archiver = new RunArchiver(store);
        assertTrue(archiver.archiveIfNeeded(blackboard, SimulationRunStatus.COMPLETED).isPresent());
        assertTrue(blackboard.isSimRunArchived());
        assertTrue(archiver.archiveIfNeeded(blackboard, SimulationRunStatus.COMPLETED).isEmpty());
    }
}
