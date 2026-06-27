package com.substation.common.analysis;

import com.substation.common.analysis.model.SummaryStatistics;
import com.substation.common.redis.BlackboardClient;

/**
 * 分析引擎接口（具体实现留空）。
 * 后续开发时从 Redis 读取 CarID:History 等数据计算统计指标。
 */
public class AnalysisEngine {

    private final BlackboardClient bb;

    public AnalysisEngine(BlackboardClient bb) {
        this.bb = bb;
    }

    /** 获取全局汇总统计 */
    public SummaryStatistics getSummary() {
        // TODO: 从 Redis History/Steps/mapView 计算
        return SummaryStatistics.empty();
    }
}
