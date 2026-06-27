package com.substation.strategysupervisor;

import com.substation.common.model.Point;
import com.substation.common.redis.BlackboardClient;

import java.util.List;

/**
 * 多车路线重合检测：仅统计未探索格子上的重合。
 * <p>高探索率时车辆必经已探索通道，若把已探索格计入重合会产生误判并反复打断任务。
 */
final class RouteOverlapEvaluator {

    /** 本车未探索路径格中，与他人路线重合比例超过此值则触发重分配 */
    private static final double OVERLAP_THRESHOLD = 0.5;

    /**
     * 检测本车路线是否与其他车在「未探索区域」上高度重合。
     *
     * @return true 表示应请求 Controller 重新分配目标
     */
    boolean isHighlyOverlapped(String carId, List<Point> myRoute, BlackboardClient bb) {
        if (myRoute.isEmpty()) {
            return false;
        }

        boolean[][] explored = bb.loadExploredBitmap();
        int myUnexploredCount = countUnexploredCells(myRoute, explored);
        if (myUnexploredCount == 0) {
            return false;
        }

        for (String otherId : bb.discoverCarIds()) {
            if (otherId.equals(carId)) {
                continue;
            }
            List<Point> otherRoute = bb.getCarRoute(otherId);
            if (otherRoute.isEmpty()) {
                continue;
            }
            int sharedUnexplored = countSharedUnexplored(myRoute, otherRoute, explored);
            if ((double) sharedUnexplored / myUnexploredCount >= OVERLAP_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    private static int countUnexploredCells(List<Point> route, boolean[][] explored) {
        int count = 0;
        for (Point point : route) {
            if (!explored[point.y()][point.x()]) {
                count++;
            }
        }
        return count;
    }

    private static int countSharedUnexplored(List<Point> myRoute, List<Point> otherRoute,
                                              boolean[][] explored) {
        int shared = 0;
        for (Point point : myRoute) {
            if (!explored[point.y()][point.x()] && containsCell(otherRoute, point)) {
                shared++;
            }
        }
        return shared;
    }

    private static boolean containsCell(List<Point> route, Point cell) {
        for (Point point : route) {
            if (point.x() == cell.x() && point.y() == cell.y()) {
                return true;
            }
        }
        return false;
    }
}
