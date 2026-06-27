package com.substation.common.redis;

import com.alibaba.fastjson2.JSONObject;
import com.substation.common.model.CarStatus;
import com.substation.common.model.Point;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.params.SetParams;

import java.util.*;

/**
 * 黑板客户端，封装所有与Redis的交互操作。
 * 包括地图状态（探索/障碍）、小车信息（位置/目标/路径/状态/步数）、热力图、任务配置、控制器锁等。
 * 实现AutoCloseable，使用完毕后需调用close释放连接池。
 */
public class BlackboardClient implements AutoCloseable {

    /** 地图默认宽/高，所有模块引用此常量而非各自硬编码 */
    public static final int DEFAULT_WIDTH = 30;
    public static final int DEFAULT_HEIGHT = 30;

    /** Redis key: 地图探索状态（位图） */
    private static final String KEY_MAP_VIEW = "mapView";
    /** Redis key: 地图障碍物状态（位图） */
    private static final String KEY_MAP_BLOCK = "mapBlock";
    /** Redis key: 被障碍物包裹、小车不可达的格子（位图） */
    private static final String KEY_MAP_SEALED = "mapSealed";
    /** Redis key: 地图热力图（Hash） */
    private static final String KEY_MAP_HEAT = "mapHeat";
    /** Redis key: 任务配置（Hash） */
    private static final String KEY_TASK_CONFIG = "TaskConfig";
    /** Redis key: 控制器实例锁 */
    private static final String KEY_CONTROLLER_INSTANCE = "controller:instance";
    /** Redis key: 探索事件列表（回放用），每元素为 {tick,row,col} */
    private static final String KEY_EXPLORATION_EVENTS = "explorationEvents";
    /** 仿真场次元数据（归档前写入 SQL） */
    private static final String KEY_SIM_RUN_STARTED_AT = "sim:run:startedAt";
    private static final String KEY_SIM_RUN_STARTED_BY = "sim:run:startedBy";
    private static final String KEY_SIM_RUN_ARCHIVED = "sim:run:archived";
    /** 仿真数据 SCAN 模式（不含 auth:* 等非仿真键） */
    private static final String[] SIMULATION_SCAN_PATTERNS = {
        "Car*:*", "pos:reserve:*", "lock:*"
    };
    /** 控制器锁的TTL（秒），防止宕机后锁永不释放 */
    private static final int CONTROLLER_LOCK_TTL_SECONDS = 30;
    /** Hash字段名: X坐标 */
    private static final String FIELD_X = "x";
    /** Hash字段名: Y坐标 */
    private static final String FIELD_Y = "y";
    /** Hash字段名: 任务是否激活 */
    private static final String FIELD_ACTIVE = "active";
    /** Hash字段名: 地图宽度 */
    private static final String FIELD_MAP_WIDTH = "mapWidth";
    /** Hash字段名: 地图高度 */
    private static final String FIELD_MAP_HEIGHT = "mapHeight";
    /** Hash字段名: 小车数量 */
    private static final String FIELD_CAR_COUNT = "carCount";
    /** Hash字段名: 路径规划算法 */
    private static final String FIELD_ALGORITHM = "algorithm";
    /** Hash字段名: tick间隔（毫秒） */
    private static final String FIELD_TICK_INTERVAL = "tickInterval";
    /** Hash字段名: 障碍物比例 */
    private static final String FIELD_OBSTACLE_RATIO = "obstacleRatio";
    /** 分布式部署时 Tailscale 往返较慢，默认 2s 易触发 Read timed out */
    private static final int REDIS_SOCKET_TIMEOUT_MS = 30_000;

    /** Redis连接池 */
    private final JedisPool pool;
    /** 地图宽度（列数）——构造函数默认值，仅在TaskConfig未设置时回退 */
    private final int mapWidth;
    /** 地图高度（行数）——构造函数默认值，仅在TaskConfig未设置时回退 */
    private final int mapHeight;

    /**
     * 构造黑板客户端，创建Redis连接池。
     *
     * @param host     Redis主机地址
     * @param port     Redis端口
     * @param mapWidth  地图宽度
     * @param mapHeight 地图高度
     */
    public BlackboardClient(String host, int port, int mapWidth, int mapHeight) {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        this.pool = new JedisPool(poolConfig, host, port, REDIS_SOCKET_TIMEOUT_MS);
        this.mapWidth = mapWidth;
        this.mapHeight = mapHeight;
    }

    /**
     * 将二维坐标(row, col)转换为位图中的一维偏移量。
     * 优先使用TaskConfig中的地图宽度，未配置时回退到构造函数默认值。
     */
    private long bitmapOffset(int row, int col) {
        return bitmapOffset(row, col, effectiveWidth());
    }

    /** 按指定地图宽度计算位图偏移（写入密封区时必须与读取宽度一致） */
    static long bitmapOffset(int row, int col, int mapWidth) {
        return (long) row * mapWidth + col;
    }

    /** 获取有效地图宽度：每次从TaskConfig读取，保证FLUSHDB后不残留旧值 */
    private int effectiveWidth() {
        int w = getMapWidth();
        return w > 0 ? w : mapWidth;
    }

    /** 获取有效地图高度：每次从TaskConfig读取 */
    private int effectiveHeight() {
        int h = getMapHeight();
        int result = h > 0 ? h : mapHeight;
        return result;
    }

    // ==================== mapView ====================

    /**
     * 获取指定格子的探索状态。
     *
     * @param row 行号
     * @param col 列号
     * @return true表示已探索
     */
    public boolean getMapViewBit(int row, int col) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.getbit(KEY_MAP_VIEW, bitmapOffset(row, col));
        }
    }

    /**
     * 设置指定格子的探索状态。
     *
     * @param row      行号
     * @param col      列号
     * @param explored true表示已探索
     */
    public void setMapViewBit(int row, int col, boolean explored) {
        try (Jedis jedis = pool.getResource()) {
            jedis.setbit(KEY_MAP_VIEW, bitmapOffset(row, col), explored);
        }
    }

    /** 一次GET读取整个mapView位图的字节数组 */
    public byte[] getMapViewBytes() {
        try (Jedis jedis = pool.getResource()) {
            byte[] bytes = jedis.get(KEY_MAP_VIEW.getBytes());
            return bytes != null ? bytes : new byte[0];
        }
    }

    /** 一次GET读取整个mapBlock位图的字节数组 */
    public byte[] getMapBlockBytes() {
        try (Jedis jedis = pool.getResource()) {
            byte[] bytes = jedis.get(KEY_MAP_BLOCK.getBytes());
            return bytes != null ? bytes : new byte[0];
        }
    }

    /** 一次GET读取整个mapSealed位图的字节数组 */
    public byte[] getMapSealedBytes() {
        try (Jedis jedis = pool.getResource()) {
            byte[] bytes = jedis.get(KEY_MAP_SEALED.getBytes());
            return bytes != null ? bytes : new byte[0];
        }
    }

    /** pipeline 批量读取三张地图位图，缩短 Display 快照与并发写入的竞态窗口 */
    public MapBitmapSnapshot readMapBitmapSnapshot() {
        try (Jedis jedis = pool.getResource()) {
            Pipeline pipeline = jedis.pipelined();
            Response<byte[]> mapView = pipeline.get(KEY_MAP_VIEW.getBytes());
            Response<byte[]> mapBlock = pipeline.get(KEY_MAP_BLOCK.getBytes());
            Response<byte[]> mapSealed = pipeline.get(KEY_MAP_SEALED.getBytes());
            pipeline.sync();
            return new MapBitmapSnapshot(mapView.get(), mapBlock.get(), mapSealed.get());
        }
    }

    /** 从字节数组构建boolean二维数组 */
    public static boolean[][] bytesToBitmap(byte[] bytes, int width, int height) {
        boolean[][] bitmap = new boolean[height][width];
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                long offset = (long) r * width + c;
                int byteIdx = (int) (offset / 8);
                int bitIdx = (int) (7 - (offset % 8));
                if (byteIdx < bytes.length) {
                    bitmap[r][c] = ((bytes[byteIdx] >> bitIdx) & 1) == 1;
                }
            }
        }
        return bitmap;
    }

    /** 将位图编码为 Redis SETBIT 兼容的字节数组（与 {@link #bytesToBitmap} 互逆） */
    public static byte[] bitmapToBytes(boolean[][] bitmap, int mapWidth) {
        int height = bitmap.length;
        int byteLength = (mapWidth * height + 7) / 8;
        byte[] bytes = new byte[byteLength];
        for (int row = 0; row < height; row++) {
            int rowWidth = Math.min(bitmap[row].length, mapWidth);
            for (int col = 0; col < rowWidth; col++) {
                if (!bitmap[row][col]) {
                    continue;
                }
                setBitInByteArray(bytes, bitmapOffset(row, col, mapWidth));
            }
        }
        return bytes;
    }

    private static void setBitInByteArray(byte[] bytes, long offset) {
        int byteIdx = (int) (offset / 8);
        int bitIdx = (int) (7 - (offset % 8));
        bytes[byteIdx] |= (1 << bitIdx);
    }

    /** 一次 Redis 读取构建探索位图（行=row，列=col） */
    public boolean[][] loadExploredBitmap() {
        int width = getMapWidth();
        int height = getMapHeight();
        return bytesToBitmap(getMapViewBytes(), width, height);
    }

    /** 一次 Redis 读取构建障碍位图（不含车辆占位） */
    public boolean[][] loadObstacleBitmap() {
        int width = getMapWidth();
        int height = getMapHeight();
        return bytesToBitmap(getMapBlockBytes(), width, height);
    }

    /** 障碍位图 + 所有车当前位置视为不可通行 */
    public boolean[][] loadBlockedMapWithCars() {
        boolean[][] blocked = loadObstacleBitmap();
        markCarPositionsOnMap(blocked);
        return blocked;
    }

    /** 一次 Redis 读取构建密封区（不可达空格）位图 */
    public boolean[][] loadSealedBitmap() {
        int width = getMapWidth();
        int height = getMapHeight();
        return bytesToBitmap(getMapSealedBytes(), width, height);
    }

    /** 写入密封区位图（初始化地图时调用，mapWidth 必须与 TaskConfig 一致） */
    public void writeSealedBitmap(boolean[][] sealed, int mapWidth) {
        writeBitmapKey(KEY_MAP_SEALED, sealed, mapWidth);
    }

    private void markCarPositionsOnMap(boolean[][] blocked) {
        for (String carId : discoverCarIds()) {
            getCarPosition(carId).ifPresent(pos -> blocked[pos.y()][pos.x()] = true);
        }
    }

    /**
     * 计算地图探索率（百分比）。
     * 分母 = 总格子 − 障碍 − 被障碍物包裹的不可达空格。
     *
     * @return 探索率 0-100
     */
    public int getExplorationRate() {
        long[] progress = countExplorationProgress();
        long explorable = progress[0];
        long exploredOnExplorable = progress[1];
        if (explorable <= 0) {
            return 100;
        }
        return (int) (exploredOnExplorable * 100 / explorable);
    }

    /** 是否仍存在可探索且未探索的格子（不含障碍与密封区） */
    public boolean hasUnexploredExplorableCells() {
        int width = getMapWidth();
        int height = getMapHeight();
        boolean[][] explored = bytesToBitmap(getMapViewBytes(), width, height);
        boolean[][] obstacles = bytesToBitmap(getMapBlockBytes(), width, height);
        boolean[][] sealed = bytesToBitmap(getMapSealedBytes(), width, height);
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                if (isExplorableCell(obstacles, sealed, row, col)
                        && !explored[row][col]) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 探索是否已结束：所有可探索区域均已探索（探索率 100%） */
    public boolean isExplorationComplete() {
        return getExplorationRate() >= 100 && !hasUnexploredExplorableCells();
    }

    /** 返回详细探索统计：{width, height, total, blocked, sealed, explorable, explored, rate} */
    public long[] getExplorationStats() {
        int width = getMapWidth();
        int height = getMapHeight();
        long total = (long) width * height;
        long blocked = countTrueCells(loadObstacleBitmap());
        long sealed = countTrueCells(loadSealedBitmap());
        long[] progress = countExplorationProgress();
        long explorable = progress[0];
        long exploredOnExplorable = progress[1];
        long rate = explorable <= 0 ? 100 : exploredOnExplorable * 100 / explorable;
        return new long[]{width, height, total, blocked, sealed, explorable, exploredOnExplorable, rate};
    }

    private long[] countExplorationProgress() {
        int width = getMapWidth();
        int height = getMapHeight();
        boolean[][] explored = bytesToBitmap(getMapViewBytes(), width, height);
        boolean[][] obstacles = bytesToBitmap(getMapBlockBytes(), width, height);
        boolean[][] sealed = bytesToBitmap(getMapSealedBytes(), width, height);
        long explorable = 0;
        long exploredOnExplorable = 0;
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                if (!isExplorableCell(obstacles, sealed, row, col)) {
                    continue;
                }
                explorable++;
                if (explored[row][col]) {
                    exploredOnExplorable++;
                }
            }
        }
        return new long[]{explorable, exploredOnExplorable};
    }

    private static boolean isExplorableCell(boolean[][] obstacles, boolean[][] sealed,
                                             int row, int col) {
        return !obstacles[row][col] && !sealed[row][col];
    }

    private static long countTrueCells(boolean[][] bitmap) {
        long count = 0;
        for (boolean[] row : bitmap) {
            for (boolean cell : row) {
                if (cell) {
                    count++;
                }
            }
        }
        return count;
    }

    private int readMapWidth(Jedis jedis) {
        String val = jedis.hget(KEY_TASK_CONFIG, FIELD_MAP_WIDTH);
        return val != null ? Integer.parseInt(val) : mapWidth;
    }

    private int readMapHeight(Jedis jedis) {
        String val = jedis.hget(KEY_TASK_CONFIG, FIELD_MAP_HEIGHT);
        return val != null ? Integer.parseInt(val) : mapHeight;
    }

    /**
     * 统计已探索的格子总数。
     *
     * @return 已探索格子数
     */
    public long countExploredCells() {
        try (Jedis jedis = pool.getResource()) {
            return jedis.bitcount(KEY_MAP_VIEW);
        }
    }

    // ==================== mapBlock ====================

    /**
     * 判断指定格子是否为障碍物。
     *
     * @param row 行号
     * @param col 列号
     * @return true表示是障碍物
     */
    public boolean isBlocked(int row, int col) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.getbit(KEY_MAP_BLOCK, bitmapOffset(row, col));
        }
    }

    /**
     * 设置指定格子的障碍物状态。
     *
     * @param row     行号
     * @param col     列号
     * @param blocked true表示标记为障碍物
     */
    public void setBlock(int row, int col, boolean blocked) {
        try (Jedis jedis = pool.getResource()) {
            jedis.setbit(KEY_MAP_BLOCK, bitmapOffset(row, col), blocked);
        }
    }

    /** 整图写入障碍位图（初始化时批量写入，避免逐格 SETBIT 的网络往返） */
    public void writeBlockBitmap(boolean[][] blocked, int mapWidth) {
        writeBitmapKey(KEY_MAP_BLOCK, blocked, mapWidth);
    }

    private void writeBitmapKey(String key, boolean[][] bitmap, int mapWidth) {
        byte[] payload = bitmapToBytes(bitmap, mapWidth);
        try (Jedis jedis = pool.getResource()) {
            jedis.del(key);
            jedis.set(key.getBytes(), payload);
        }
    }

    // ==================== CarID:Position ====================

    /**
     * 获取小车当前位置。
     *
     * @param carId 小车ID
     * @return 当前位置，不存在则为empty
     */
    public Optional<Point> getCarPosition(String carId) {
        try (Jedis jedis = pool.getResource()) {
            java.util.List<String> vals = jedis.hmget(carId + ":Position", FIELD_X, FIELD_Y);
            if (vals.get(0) == null) return Optional.empty();
            return Optional.of(new Point(Integer.parseInt(vals.get(0)), Integer.parseInt(vals.get(1))));
        }
    }

    /**
     * 设置小车当前位置。
     *
     * @param carId 小车ID
     * @param pos   坐标
     */
    public void setCarPosition(String carId, Point pos) {
        try (Jedis jedis = pool.getResource()) {
            jedis.hset(carId + ":Position",
                Map.of(FIELD_X, String.valueOf(pos.x()),
                       FIELD_Y, String.valueOf(pos.y())));
        }
    }

    /** 清除小车位置信息。 */
    public void clearCarPosition(String carId) {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(carId + ":Position");
        }
    }

    // ==================== CarID:Target ====================

    /**
     * 获取小车当前目标点。
     *
     * @param carId 小车ID
     * @return 目标坐标，不存在则为empty
     */
    public Optional<Point> getCarTarget(String carId) {
        try (Jedis jedis = pool.getResource()) {
            java.util.List<String> vals = jedis.hmget(carId + ":Target", FIELD_X, FIELD_Y);
            if (vals.get(0) == null) return Optional.empty();
            return Optional.of(new Point(Integer.parseInt(vals.get(0)), Integer.parseInt(vals.get(1))));
        }
    }

    /**
     * 设置小车目标点。
     *
     * @param carId  小车ID
     * @param target 目标坐标
     */
    public void setCarTarget(String carId, Point target) {
        try (Jedis jedis = pool.getResource()) {
            jedis.hset(carId + ":Target",
                Map.of(FIELD_X, String.valueOf(target.x()),
                       FIELD_Y, String.valueOf(target.y())));
        }
    }

    /** 清除小车目标点。 */
    public void clearCarTarget(String carId) {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(carId + ":Target");
        }
    }

    /**
     * 判断小车是否有目标点。
     *
     * @param carId 小车ID
     * @return true表示已有目标
     */
    public boolean hasTarget(String carId) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.exists(carId + ":Target");
        }
    }

    // ==================== CarID:RouteList ====================

    /**
     * 获取小车的完整路径列表（从起点到终点）。
     *
     * @param carId 小车ID
     * @return 路径点列表（不可变）
     */
    public List<Point> getCarRoute(String carId) {
        try (Jedis jedis = pool.getResource()) {
            List<String> items = jedis.lrange(carId + ":RouteList", 0, -1);
            List<Point> route = new ArrayList<>();
            for (String item : items) {
                route.add(Point.fromJson(item));
            }
            return Collections.unmodifiableList(route);
        }
    }

    /**
     * 查看路径的下一步（不移除）。
     *
     * @param carId 小车ID
     * @return 下一步坐标，路径为空则为empty
     */
    public Optional<Point> peekNextRouteStep(String carId) {
        try (Jedis jedis = pool.getResource()) {
            String json = jedis.lindex(carId + ":RouteList", 0);
            return json != null ? Optional.of(Point.fromJson(json)) : Optional.empty();
        }
    }

    /**
     * 弹出路径的下一步（移除并返回）。
     *
     * @param carId 小车ID
     * @return 下一步坐标，路径为空则为empty
     */
    public Optional<Point> popNextRouteStep(String carId) {
        try (Jedis jedis = pool.getResource()) {
            String json = jedis.lpop(carId + ":RouteList");
            return json != null ? Optional.of(Point.fromJson(json)) : Optional.empty();
        }
    }

    /**
     * 将整条路径推入Redis列表（逆序推入，保证弹出顺序正确）。
     *
     * @param carId 小车ID
     * @param route 路径点列表（从起点到终点）
     */
    public void pushRoute(String carId, List<Point> route) {
        try (Jedis jedis = pool.getResource()) {
            String key = carId + ":RouteList";
            java.util.List<String> args = new java.util.ArrayList<>(route.size());
            for (int i = route.size() - 1; i >= 0; i--) {
                args.add(route.get(i).toJson());
            }
            jedis.eval(
                "redis.call('DEL',KEYS[1]) for i=1,#ARGV do redis.call('LPUSH',KEYS[1],ARGV[i]) end",
                java.util.Collections.singletonList(key), args);
        }
    }

    /** 清除小车路径。 */
    public void clearRoute(String carId) {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(carId + ":RouteList");
        }
    }

    // ==================== CarID:Status ====================

    /**
     * 获取小车当前状态。
     *
     * @param carId 小车ID
     * @return 状态枚举值，不存在则为empty
     */
    public Optional<CarStatus> getCarStatus(String carId) {
        try (Jedis jedis = pool.getResource()) {
            String val = jedis.get(carId + ":Status");
            return val != null ? Optional.of(CarStatus.valueOf(val)) : Optional.empty();
        }
    }

    /**
     * 设置小车状态。
     *
     * @param carId  小车ID
     * @param status 状态枚举值
     */
    public void setCarStatus(String carId, CarStatus status) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(carId + ":Status", status.name());
        }
    }

    // ==================== CarID:Steps ====================

    /**
     * 获取小车已移动步数。
     *
     * @param carId 小车ID
     * @return 步数，不存在返回0
     */
    public int getCarSteps(String carId) {
        try (Jedis jedis = pool.getResource()) {
            String val = jedis.get(carId + ":Steps");
            return val != null ? Integer.parseInt(val) : 0;
        }
    }

    /** 小车步数加1。 */
    public void incrementCarSteps(String carId) {
        try (Jedis jedis = pool.getResource()) {
            jedis.incr(carId + ":Steps");
        }
    }

    /**
     * 设置小车步数值。
     *
     * @param carId 小车ID
     * @param steps 步数值
     */
    public void setCarSteps(String carId, int steps) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(carId + ":Steps", String.valueOf(steps));
        }
    }

    /**
     * 获取小车走过未探索区域的步数。
     *
     * @param carId 小车ID
     * @return 有效步数，不存在返回0
     */
    public int getCarEffectiveSteps(String carId) {
        try (Jedis jedis = pool.getResource()) {
            String val = jedis.get(carId + ":EffectiveSteps");
            return val != null ? Integer.parseInt(val) : 0;
        }
    }

    /** 小车有效步数加1（踩入此前未探索的格子时调用）。 */
    public void incrementCarEffectiveSteps(String carId) {
        try (Jedis jedis = pool.getResource()) {
            jedis.incr(carId + ":EffectiveSteps");
        }
    }

    /**
     * 设置小车有效步数值。
     *
     * @param carId 小车ID
     * @param steps 有效步数值
     */
    public void setCarEffectiveSteps(String carId, int steps) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(carId + ":EffectiveSteps", String.valueOf(steps));
        }
    }

    // ==================== CarID:BlockedTick ====================

    /**
     * 获取小车被阻塞时的tick号。
     *
     * @param carId 小车ID
     * @return 阻塞tick号，不存在返回-1
     */
    public int getBlockedTick(String carId) {
        try (Jedis jedis = pool.getResource()) {
            String val = jedis.get(carId + ":BlockedTick");
            return val != null ? Integer.parseInt(val) : -1;
        }
    }

    /**
     * 记录小车被阻塞时的tick号。
     *
     * @param carId 小车ID
     * @param tick  当前tick号
     */
    public void setBlockedTick(String carId, int tick) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(carId + ":BlockedTick", String.valueOf(tick));
        }
    }

    /** 清除小车阻塞tick记录。 */
    public void clearBlockedTick(String carId) {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(carId + ":BlockedTick");
        }
    }

    // ==================== CarID:History ====================

    /** 向小车History追加一条移动记录 RPUSH {x, y, tick} */
    public void appendCarHistory(String carId, Point position, int tick) {
        try (Jedis jedis = pool.getResource()) {
            JSONObject record = new JSONObject();
            record.put("x", position.x());
            record.put("y", position.y());
            record.put("tick", tick);
            jedis.rpush(carId + ":History", record.toJSONString());
        }
    }

    /** 获取指定小车的全部历史轨迹 */
    public List<String> getCarHistory(String carId) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.lrange(carId + ":History", 0, -1);
        }
    }

    /** 获取所有小车的历史轨迹，返回 carId → 轨迹列表 */
    public Map<String, List<String>> getAllCarHistories() {
        Map<String, List<String>> result = new java.util.LinkedHashMap<>();
        for (String carId : discoverCarIds()) {
            List<String> history = getCarHistory(carId);
            if (!history.isEmpty()) {
                result.put(carId, history);
            }
        }
        return result;
    }

    // ==================== explorationEvents ====================

    /**
     * 尝试探索指定格子并记录事件。仅在格子首次被探索时记录到回放事件列表。
     * 集成了 setMapViewBit 功能：返回 true 表示之前未被探索（新发现）。
     */
    public boolean recordExploration(int tick, int row, int col) {
        try (Jedis jedis = pool.getResource()) {
            long offset = bitmapOffset(row, col);
            if (jedis.getbit(KEY_MAP_BLOCK, offset)) {
                return false; // 障碍物不计入探索
            }
            boolean wasExplored = jedis.setbit(KEY_MAP_VIEW, offset, true);
            if (!wasExplored) {
                jedis.rpush(KEY_EXPLORATION_EVENTS, tick + "," + row + "," + col);
            }
            return !wasExplored;
        }
    }

    /** 获取全部探索事件列表（回放用） */
    public List<String> getExplorationEvents() {
        try (Jedis jedis = pool.getResource()) {
            return jedis.lrange(KEY_EXPLORATION_EVENTS, 0, -1);
        }
    }

    // ==================== 仿真场次元数据（SQL 归档） ====================

    /** 是否存在可归档的轨迹或探索事件 */
    public boolean hasReplayableData() {
        if (!getExplorationEvents().isEmpty()) {
            return true;
        }
        return !getAllCarHistories().isEmpty();
    }

    public boolean isSimRunArchived() {
        try (Jedis jedis = pool.getResource()) {
            return "1".equals(jedis.get(KEY_SIM_RUN_ARCHIVED));
        }
    }

    public void markSimRunArchived() {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(KEY_SIM_RUN_ARCHIVED, "1");
        }
    }

    public void clearSimRunArchived() {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(KEY_SIM_RUN_ARCHIVED);
        }
    }

    public Optional<java.time.Instant> getSimRunStartedAt() {
        try (Jedis jedis = pool.getResource()) {
            String value = jedis.get(KEY_SIM_RUN_STARTED_AT);
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(java.time.Instant.ofEpochSecond(Long.parseLong(value.trim())));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public Optional<String> getSimRunStartedBy() {
        try (Jedis jedis = pool.getResource()) {
            String value = jedis.get(KEY_SIM_RUN_STARTED_BY);
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
        }
    }

    /** 新仿真开始前记录操作者与开始时间 */
    public void beginSimRun(String startedBy) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(KEY_SIM_RUN_STARTED_AT, String.valueOf(System.currentTimeMillis() / 1000));
            jedis.set(KEY_SIM_RUN_STARTED_BY, startedBy == null || startedBy.isBlank() ? "unknown" : startedBy);
            jedis.del(KEY_SIM_RUN_ARCHIVED);
        }
    }

    // ==================== mapHeat ====================

    /**
     * 热力图指定格子计数加1。
     *
     * @param row 行号
     * @param col 列号
     */
    public void incrementMapHeat(int row, int col) {
        try (Jedis jedis = pool.getResource()) {
            jedis.hincrBy(KEY_MAP_HEAT, row + "," + col, 1);
        }
    }

    /**
     * 获取完整热力图数据。
     *
     * @return 格子坐标到访问次数的映射（不可变）
     */
    public Map<String, String> getMapHeat() {
        try (Jedis jedis = pool.getResource()) {
            Map<String, String> heat = jedis.hgetAll(KEY_MAP_HEAT);
            return heat != null ? Collections.unmodifiableMap(heat) : Collections.emptyMap();
        }
    }

    // ==================== controller:instance ====================

    /**
     * 尝试获取控制器实例锁（SET NX EX），防止多个控制器同时运行。
     *
     * @return true表示获取成功
     */
    public boolean acquireControllerLock() {
        try (Jedis jedis = pool.getResource()) {
            SetParams params = SetParams.setParams().nx().ex(CONTROLLER_LOCK_TTL_SECONDS);
            return "OK".equals(jedis.set(KEY_CONTROLLER_INSTANCE, "1", params));
        }
    }

    /** 释放控制器实例锁。 */
    public void releaseControllerLock() {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(KEY_CONTROLLER_INSTANCE);
        }
    }

    // ==================== TaskConfig ====================

    /**
     * 获取任务配置的全部字段。
     *
     * @return 配置键值对Map
     */
    public Map<String, String> getTaskConfig() {
        try (Jedis jedis = pool.getResource()) {
            return jedis.hgetAll(KEY_TASK_CONFIG);
        }
    }

    /**
     * 判断任务是否处于激活状态。
     *
     * @return true表示任务正在运行
     */
    public boolean isTaskActive() {
        try (Jedis jedis = pool.getResource()) {
            return "true".equals(jedis.hget(KEY_TASK_CONFIG, FIELD_ACTIVE));
        }
    }

    /**
     * 设置任务激活状态。
     *
     * @param active true表示激活
     */
    public void setTaskActive(boolean active) {
        try (Jedis jedis = pool.getResource()) {
            jedis.hset(KEY_TASK_CONFIG, FIELD_ACTIVE, String.valueOf(active));
        }
    }

    /**
     * 从任务配置中读取地图宽度。
     *
     * @return 地图宽度，TaskConfig 未设置时回退构造函数默认值
     */
    public int getMapWidth() {
        try (Jedis jedis = pool.getResource()) {
            String val = jedis.hget(KEY_TASK_CONFIG, FIELD_MAP_WIDTH);
            if (val != null) {
                return Integer.parseInt(val);
            }
        }
        return mapWidth;
    }

    /**
     * 从任务配置中读取地图高度。
     *
     * @return 地图高度，TaskConfig 未设置时回退构造函数默认值
     */
    public int getMapHeight() {
        try (Jedis jedis = pool.getResource()) {
            String val = jedis.hget(KEY_TASK_CONFIG, FIELD_MAP_HEIGHT);
            if (val != null) {
                return Integer.parseInt(val);
            }
        }
        return mapHeight;
    }

    /**
     * 从任务配置中读取小车数量。
     *
     * @return 小车数量，不存在返回0
     */
    public int getCarCount() {
        try (Jedis jedis = pool.getResource()) {
            String val = jedis.hget(KEY_TASK_CONFIG, FIELD_CAR_COUNT);
            return val != null ? Integer.parseInt(val) : 0;
        }
    }

    /**
     * 从任务配置中读取路径规划算法名称。
     *
     * @return 算法名称，不存在返回null
     */
    public String getAlgorithm() {
        try (Jedis jedis = pool.getResource()) {
            return jedis.hget(KEY_TASK_CONFIG, FIELD_ALGORITHM);
        }
    }

    /**
     * 从任务配置中读取tick间隔。
     *
     * @return tick间隔毫秒数，不存在返回500
     */
    public int getTickInterval() {
        try (Jedis jedis = pool.getResource()) {
            String val = jedis.hget(KEY_TASK_CONFIG, FIELD_TICK_INTERVAL);
            return val != null ? Integer.parseInt(val) : 500;
        }
    }

    /**
     * 从任务配置中读取障碍物比例。
     *
     * @return 障碍物比例，不存在返回0.15
     */
    public double getObstacleRatio() {
        try (Jedis jedis = pool.getResource()) {
            String val = jedis.hget(KEY_TASK_CONFIG, FIELD_OBSTACLE_RATIO);
            return val != null ? Double.parseDouble(val) : 0.15;
        }
    }

    /**
     * 批量初始化任务配置。
     *
     * @param config 配置键值对
     */
    /** 设置任务耗时秒数（单字段写入，不覆盖其他 TaskConfig 字段） */
    public void setElapsedSeconds(long seconds) {
        try (Jedis jedis = pool.getResource()) {
            jedis.hset(KEY_TASK_CONFIG, "elapsedSeconds", String.valueOf(seconds));
        }
    }

    /** 更新节拍间隔（单字段写入，供多观众页同步滑块） */
    public void setTickInterval(int intervalMs) {
        try (Jedis jedis = pool.getResource()) {
            jedis.hset(KEY_TASK_CONFIG, FIELD_TICK_INTERVAL, String.valueOf(intervalMs));
        }
    }

    // ==================== 位置预约锁（防多车重叠） ====================

    /** 尝试预约目标位置，防止两车同时移动到同一格 */
    public boolean tryReservePosition(int x, int y, String carId) {
        try (Jedis jedis = pool.getResource()) {
            SetParams params = SetParams.setParams().nx().ex(3);
            String result = jedis.set("pos:reserve:" + x + ":" + y, carId, params);
            return "OK".equals(result);
        }
    }

    /** 释放位置预约 */
    public void releaseReservePosition(int x, int y, String carId) {
        try (Jedis jedis = pool.getResource()) {
            String key = "pos:reserve:" + x + ":" + y;
            String val = jedis.get(key);
            if (carId.equals(val)) {
                jedis.del(key);
            }
        }
    }

    public void initTaskConfig(Map<String, String> config) {
        try (Jedis jedis = pool.getResource()) {
            jedis.hset(KEY_TASK_CONFIG, config);
        }
    }

    /**
     * 清空仿真黑板数据，保留登录会话（auth:session:*）等非仿真键。
     * 替代 flushDB，避免点击「开始」后用户被登出。
     */
    /** 清除本场仿真元数据（操作者、开始时间、归档标记），仅在用户点「重置」时调用。 */
    public void clearSimRunMetadata() {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(KEY_SIM_RUN_STARTED_AT, KEY_SIM_RUN_STARTED_BY, KEY_SIM_RUN_ARCHIVED);
        }
    }

    public void clearSimulationState() {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(KEY_MAP_VIEW, KEY_MAP_BLOCK, KEY_MAP_SEALED,
                KEY_MAP_HEAT, KEY_TASK_CONFIG, KEY_EXPLORATION_EVENTS);
            for (String pattern : SIMULATION_SCAN_PATTERNS) {
                deleteKeysMatching(jedis, pattern);
            }
        }
    }

    private static void deleteKeysMatching(Jedis jedis, String pattern) {
        Set<String> keys = jedis.keys(pattern);
        if (!keys.isEmpty()) {
            jedis.del(keys.toArray(String[]::new));
        }
    }

    /**
     * 扫描Redis中所有已注册的小车ID（通过 Car*:Status 键匹配）。
     *
     * @return 小车ID集合
     */
    public Set<String> discoverCarIds() {
        try (Jedis jedis = pool.getResource()) {
            return jedis.keys("Car*:Status").stream()
                .map(key -> key.replace(":Status", ""))
                .collect(java.util.stream.Collectors.toSet());
        }
    }

    /**
     * 获取底层Redis连接池，供外部组件（如DistributedLock）使用。
     *
     * @return JedisPool实例
     */
    public JedisPool getJedisPool() {
        return pool;
    }

    /** 关闭连接池，释放所有Redis连接。 */
    @Override
    public void close() {
        pool.close();
    }
}
