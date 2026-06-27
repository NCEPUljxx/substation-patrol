package com.substation.common.analysis.model;

import java.util.Collections;
import java.util.List;

/** 单车统计 */
public record CarStatistics(String carId, int steps, int pathCount, double avgPathLength,
                              int blockCount, double idleRate, List<String> pathHistory) {

    public static CarStatistics empty(String carId) {
        return new CarStatistics(carId, 0, 0, 0.0, 0, 0.0, Collections.emptyList());
    }
}
