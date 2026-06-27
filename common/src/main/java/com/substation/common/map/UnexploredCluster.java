package com.substation.common.map;

import com.substation.common.model.Point;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** 四邻接连通的未探索区域 */
public record UnexploredCluster(List<Point> cells) {

    public UnexploredCluster {
        cells = List.copyOf(cells);
    }

    public int cellCount() {
        return cells.size();
    }

    /** 去掉本轮已被其他车占用的格子，无剩余则返回 empty */
    public Optional<UnexploredCluster> withoutAllocated(Set<Point> allocated) {
        List<Point> remaining = cells.stream()
            .filter(cell -> !allocated.contains(cell))
            .toList();
        if (remaining.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new UnexploredCluster(remaining));
    }
}
