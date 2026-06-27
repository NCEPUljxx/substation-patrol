package com.substation.navigator;

import com.substation.common.model.Point;
import com.substation.common.redis.BlackboardClient;

import java.util.List;

@FunctionalInterface
public interface PathPlanner {

    /**
     * 搜索从起点到终点的最短路径。
     *
     * @param start  起点（不含在返回路径中）
     * @param target 终点（包含在返回路径中）
     * @param bb     黑板客户端，用于读取障碍物和地图尺寸
     * @return 路径点列表（不含 start，含 target），无路径时返回空列表
     */
    List<Point> plan(Point start, Point target, BlackboardClient bb);
}
