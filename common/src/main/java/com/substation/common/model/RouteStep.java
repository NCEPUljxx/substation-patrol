package com.substation.common.model;

/** 路径步骤记录，包含当前坐标和步骤序号 */
public record RouteStep(
        /** 当前步骤所在的坐标 */
        Point position,
        /** 步骤序号（从0开始） */
        int stepIndex) {}
