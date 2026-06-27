package com.substation.display;

import com.alibaba.fastjson2.JSONObject;
import com.substation.common.replay.ReplayDataBuilder;
import com.substation.common.replay.SimulationRunStore;
import com.substation.common.redis.BlackboardClient;
import org.java_websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 仿真场次历史回放协调。 */
final class ReplayCoordinator {

    private static final Logger LOG = LoggerFactory.getLogger(ReplayCoordinator.class);

    private final BlackboardClient blackboard;
    private final SimulationRunStore store;

    ReplayCoordinator(BlackboardClient blackboard, SimulationRunStore store) {
        this.blackboard = blackboard;
        this.store = store;
    }

    void beforeSimCommand(String messageType, JSONObject data) {
        if ("SET_CONFIG".equals(messageType)) {
            String operator = data != null ? data.getString("operator") : null;
            blackboard.beginSimRun(operator);
        }
    }

    void sendLiveReplay(WebSocket conn) {
        JSONObject payload = ReplayDataBuilder.fromBlackboard(blackboard);
        conn.send(payload.toJSONString());
        LOG.info("已发送当前场次回放数据 maxTick={}", payload.getIntValue("maxTick"));
    }

    void sendStoredReplay(WebSocket conn, long runId) {
        store.findById(runId).ifPresentOrElse(
                record -> {
                    JSONObject payload = ReplayDataBuilder.fromRecord(record);
                    conn.send(payload.toJSONString());
                    LOG.info("已发送历史场次回放 id={} maxTick={}", runId, payload.getIntValue("maxTick"));
                },
                () -> conn.send("{\"type\":\"REPLAY_ERROR\",\"error\":\"场次不存在\"}"));
    }
}
