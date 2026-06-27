package com.substation.common.analysis.model;

import java.util.List;

/** 分析查询参数 */
public record AnalysisQuery(List<String> carIds, Integer startTick, Integer endTick,
                              List<String> metrics) {}
