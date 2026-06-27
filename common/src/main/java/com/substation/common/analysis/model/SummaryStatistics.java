package com.substation.common.analysis.model;

/** 全局汇总统计 */
public record SummaryStatistics(int totalSteps, int explorationRate, double efficiency,
                                 long duration, int blockCount, double idleRate,
                                 double reExploreRate, int activeCars) {

    public static SummaryStatistics empty() {
        return new SummaryStatistics(0, 0, 0.0, 0, 0, 0.0, 0.0, 0);
    }
}
