package com.substation.common.model;

import java.util.List;
import java.util.Map;

/** 仿真状态快照，每个tick推送给前端渲染 */
public record SimulationState(
        /** 当前仿真步数 */
        int tick,
        /** 探索覆盖率（百分比，0-100） */
        int explorationRate,
        /** 任务配置（key为任务ID，value为探测次数） */
        Map<String, String> taskConfig,
        /** 所有小车当前信息 */
        List<CarInfo> cars,
        /** 地图视野（已探索区域），true表示已探索 */
        boolean[][] mapView,
        /** 地图障碍物，true表示该格子不可通行 */
        boolean[][] mapBlock,
        /** 被障碍物包裹、小车不可达的格子，true表示密封区 */
        boolean[][] mapSealed,
        /** 本场仿真点「开始」的操作者用户名，观众端用于判断是否弹出保存框 */
        String runStartedBy) {

    /** 单辆小车在某一tick的状态快照 */
    public record CarInfo(
            /** 小车唯一标识 */
            String carId,
            /** 小车编号 */
            int number,
            /** 小车当前位置 */
            Point position,
            /** 小车目标位置 */
            Point target,
            /** 规划的路径点列表 */
            List<Point> routeList,
            /** 小车当前状态 */
            CarStatus status,
            /** 已行走的步数 */
            int steps,
            /** 走过未探索区域的步数 */
            int effectiveSteps) {}
}
