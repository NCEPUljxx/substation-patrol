package com.substation.common.redis;

/** 地图三张位图的一次性批量读取结果（pipeline），用于 Display 快照 */
public record MapBitmapSnapshot(byte[] mapView, byte[] mapBlock, byte[] mapSealed) {

    public MapBitmapSnapshot {
        mapView = mapView != null ? mapView : new byte[0];
        mapBlock = mapBlock != null ? mapBlock : new byte[0];
        mapSealed = mapSealed != null ? mapSealed : new byte[0];
    }
}
