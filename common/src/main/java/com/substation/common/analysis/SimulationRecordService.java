package com.substation.common.analysis;

import com.alibaba.fastjson2.JSONObject;
import com.substation.common.replay.RunArchiver;
import com.substation.common.replay.SimulationRunStore;
import com.substation.common.replay.model.SimulationRunStatus;
import com.substation.common.redis.BlackboardClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 用户确认保存时，同步写入路径回放与统计分析。 */
public class SimulationRecordService {

    private static final Logger LOG = LoggerFactory.getLogger(SimulationRecordService.class);

    private final BlackboardClient blackboard;
    private final RunArchiver runArchiver;
    private final SimulationRunStore runStore;
    private final SimulationStatsStore statsStore;

    public SimulationRecordService(BlackboardClient blackboard,
                                   RunArchiver runArchiver,
                                   SimulationRunStore runStore,
                                   SimulationStatsStore statsStore) {
        this.blackboard = blackboard;
        this.runArchiver = runArchiver;
        this.runStore = runStore;
        this.statsStore = statsStore;
    }

    /** 归档当前黑板场次并保存统计，返回统一 runId。 */
    public long saveConfirmed(JSONObject payload, String savedBy) {
        if (blackboard.isSimRunArchived()) {
            throw new IllegalStateException("该场次已处理，请勿重复保存");
        }
        if (!blackboard.hasReplayableData()) {
            throw new IllegalArgumentException("没有可保存的仿真数据");
        }
        long runId = runArchiver.archiveIfNeeded(blackboard, SimulationRunStatus.COMPLETED)
                .orElseThrow(() -> new IllegalStateException("归档路径回放失败"));
        try {
            statsStore.save(runId, savedBy, payload);
            LOG.info("已同步保存场次 runId={}（回放+统计）", runId);
            return runId;
        } catch (RuntimeException e) {
            runStore.deleteById(runId);
            blackboard.clearSimRunArchived();
            throw e;
        }
    }

    /** 用户拒绝保存：标记场次已处理，但不写入数据库。 */
    public void declineSave() {
        blackboard.markSimRunArchived();
    }

    /** 删除统计与路径回放，保持两表编号一致。 */
    public boolean deleteByRunId(long runId) {
        statsStore.deleteByRunId(runId);
        return runStore.deleteById(runId);
    }
}
