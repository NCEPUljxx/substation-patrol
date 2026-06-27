# Common模块设计文档

## 1. 模块概述

Common 模块是变电站巡检仿真系统的**共享基础库**，为所有业务模块提供统一的数据模型、基础设施封装和通用工具。该模块不包含任何业务逻辑，仅提供可复用的基础设施能力。

- **包路径**: `com.substation.common`
- **文件数量**: 55 个 Java 源文件，分布在 10 个包中（9 个子包 + 1 个根包直接类）
- **依赖关系**: 系统中其余 8 个模块（controller、target-planner、navigator、car、supervisor、websocket-server、web、admin）均依赖本模块
- **核心职责**: 数据模型定义、Redis 黑板交互、RabbitMQ 消息总线、地图算法、认证鉴权、分析回放、配置管理、数据库访问、管理员API、动态障碍物

### 1.1 包结构总览

| 包 | 文件数 | 职责 |
|-----|--------|------|
| `com.substation.common` (根) | 1 | 动态障碍物工具 |
| `com.substation.common.model` | 5 | 共享数据模型 |
| `com.substation.common.redis` | 3 | Redis 黑板系统客户端 |
| `com.substation.common.mq` | 4 | RabbitMQ 消息总线 |
| `com.substation.common.infra` | 3 | 基础设施配置 |
| `com.substation.common.map` | 8 | 地图算法工具包 |
| `com.substation.common.auth` | 10 | 认证鉴权基础设施 |
| `com.substation.common.sql` | 7 | 数据库访问层 |
| `com.substation.common.analysis` | 8 | 仿真数据分析 |
| `com.substation.common.replay` | 7 | 仿真回放基础设施 |
| `com.substation.common.admin` | 1 | 管理员API |

---

## 2. model 包——共享数据模型

`com.substation.common.model` 包含系统中所有模块共用的数据结构定义，共计 5 个类/记录。

### 2.1 Point

```java
public record Point(int x, int y)
```

二维坐标记录，使用 Java `record` 实现不可变数据载体：

| 方法 | 说明 |
|------|------|
| `int x()` | 获取 X 坐标（列号） |
| `int y()` | 获取 Y 坐标（行号） |
| `int manhattanDistance(Point other)` | 计算到另一点的曼哈顿距离：`|x1-x2| + |y1-y2|` |
| `String toJson()` | 序列化为 JSON 字符串（通过 FastJSON2） |
| `static Point fromJson(String)` | 从 JSON 字符串反序列化 |

### 2.2 CarStatus

小车状态枚举，包含 5 种状态及其前端展示颜色：

| 枚举值 | 中文名称 | 颜色代码 | 语义 |
|--------|---------|---------|------|
| `IDLE` | 空闲 | `#9E9E9E`（灰） | 小车未分配任务 |
| `WAITING_ROUTE` | 等待路径 | `#FF9800`（橙） | 已分配目标，等待路径规划完成 |
| `READY` | 就绪 | `#4CAF50`（绿） | 路径已规划完毕，等待出发指令 |
| `MOVING` | 移动中 | `#2196F3`（蓝） | 正在沿路径移动 |
| `BLOCKED` | 受阻 | `#F44336`（红） | 路径被障碍物阻塞 |

每个枚举值携带 `chineseName()` 和 `color()` 两个属性，供前端直接使用。

### 2.3 AlgorithmType

寻路算法类型枚举：

| 枚举值 | 说明 |
|--------|------|
| `BFS` | 广度优先搜索 |
| `ASTAR` | A* 算法 |

### 2.4 RouteStep

```java
public record RouteStep(Point position, int stepIndex)
```

路径步骤记录，`position` 为当前步骤所在坐标，`stepIndex` 为从 0 开始的步骤序号。

| 方法 | 说明 |
|------|------|
| `Point position()` | 当前步骤坐标 |
| `int stepIndex()` | 步骤序号（从 0 开始） |

### 2.5 SimulationState

```java
public record SimulationState(int tick, int explorationRate,
    Map<String, String> taskConfig, List<CarInfo> cars,
    boolean[][] mapView, boolean[][] mapBlock, boolean[][] mapSealed,
    String runStartedBy)
```

仿真状态快照，每个 tick 推送至前端进行渲染：

| 字段 | 类型 | 说明 |
|------|------|------|
| `tick` | `int` | 当前仿真步数 |
| `explorationRate` | `int` | 探索覆盖率（百分比，0-100） |
| `taskConfig` | `Map<String, String>` | 任务配置键值对 |
| `cars` | `List<CarInfo>` | 所有小车当前信息 |
| `mapView` | `boolean[][]` | 地图已探索区域（true = 已探索） |
| `mapBlock` | `boolean[][]` | 地图障碍物（true = 不可通行） |
| `mapSealed` | `boolean[][]` | 被障碍物包裹的密封区（true = 不可达） |
| `runStartedBy` | `String` | 仿真启动者用户名，观众端用于判断是否弹出保存框 |

内嵌记录 **CarInfo** 描述单辆小车在某一 tick 的完整状态：

| 字段 | 类型 | 说明 |
|------|------|------|
| `carId` | `String` | 小车唯一标识 |
| `number` | `int` | 小车编号 |
| `position` | `Point` | 当前位置 |
| `target` | `Point` | 目标位置 |
| `routeList` | `List<Point>` | 规划路径点列表 |
| `status` | `CarStatus` | 当前状态 |
| `steps` | `int` | 已行走步数 |
| `effectiveSteps` | `int` | 走过未探索区域的步数 |

---

## 3. redis 包——黑板系统客户端

`com.substation.common.redis` 封装所有 Redis 交互，是分布式黑板架构的核心基础设施。共 3 个类。

### 3.1 BlackboardClient

**实现 `AutoCloseable`**，内部管理 Jedis 连接池（socketTimeout 30s，适配 局域网 分布式部署的高延迟网络）。

#### 3.1.1 构造器

| 签名 | 说明 |
|------|------|
| `BlackboardClient(String host, int port, int mapWidth, int mapHeight)` | 创建客户端，初始化 Jedis 连接池 |

#### 3.1.2 公开常量

| 常量 | 值 | 说明 |
|------|-----|------|
| `DEFAULT_WIDTH` | `30` | 地图默认宽度，所有模块引用此常量 |
| `DEFAULT_HEIGHT` | `30` | 地图默认高度，所有模块引用此常量 |

内部常量（`private static final`）：

| 常量 | 值 | 说明 |
|------|-----|------|
| `KEY_MAP_VIEW` | `"mapView"` | 探索状态位图 key |
| `KEY_MAP_BLOCK` | `"mapBlock"` | 障碍物位图 key |
| `KEY_MAP_SEALED` | `"mapSealed"` | 密封区位图 key |
| `KEY_MAP_HEAT` | `"mapHeat"` | 热力图 Hash key |
| `KEY_TASK_CONFIG` | `"TaskConfig"` | 任务配置 Hash key |
| `KEY_CONTROLLER_INSTANCE` | `"controller:instance"` | 控制器实例锁 key |
| `KEY_EXPLORATION_EVENTS` | `"explorationEvents"` | 探索事件列表 key |
| `KEY_SIM_RUN_STARTED_AT` | `"sim:run:startedAt"` | 仿真开始时间 key |
| `KEY_SIM_RUN_STARTED_BY` | `"sim:run:startedBy"` | 仿真启动者 key |
| `KEY_SIM_RUN_ARCHIVED` | `"sim:run:archived"` | 仿真归档标记 key |
| `SIMULATION_SCAN_PATTERNS` | `["Car*:*", "pos:reserve:*", "lock:*"]` | 仿真数据 SCAN 模式 |
| `CONTROLLER_LOCK_TTL_SECONDS` | `30` | 控制器锁 TTL（秒） |
| `REDIS_SOCKET_TIMEOUT_MS` | `30000` | Redis socket 超时（毫秒） |

Hash 字段名常量：

| 常量 | 值 | 用途 |
|------|-----|------|
| `FIELD_X` | `"x"` | X 坐标字段 |
| `FIELD_Y` | `"y"` | Y 坐标字段 |
| `FIELD_ACTIVE` | `"active"` | 任务激活状态字段 |
| `FIELD_MAP_WIDTH` | `"mapWidth"` | 地图宽度字段 |
| `FIELD_MAP_HEIGHT` | `"mapHeight"` | 地图高度字段 |
| `FIELD_CAR_COUNT` | `"carCount"` | 小车数量字段 |
| `FIELD_ALGORITHM` | `"algorithm"` | 路径规划算法字段 |
| `FIELD_TICK_INTERVAL` | `"tickInterval"` | tick 间隔字段 |
| `FIELD_OBSTACLE_RATIO` | `"obstacleRatio"` | 障碍物比例字段 |

#### 3.1.3 地图位图操作（mapView）

| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| `getMapViewBit(int row, int col)` | `boolean` | 获取指定格子的探索状态（true = 已探索） |
| `setMapViewBit(int row, int col, boolean explored)` | `void` | 设置指定格子的探索状态 |
| `getMapViewBytes()` | `byte[]` | 一次 GET 读取整个 mapView 位图的字节数组 |
| `getMapBlockBytes()` | `byte[]` | 一次 GET 读取整个 mapBlock 位图的字节数组 |
| `getMapSealedBytes()` | `byte[]` | 一次 GET 读取整个 mapSealed 位图的字节数组 |
| `readMapBitmapSnapshot()` | `MapBitmapSnapshot` | pipeline 批量读取三张地图位图（mapView/mapBlock/mapSealed），缩短并发竞态窗口 |
| `loadExploredBitmap()` | `boolean[][]` | 一次 Redis 读取构建探索位图（行=row，列=col） |
| `loadObstacleBitmap()` | `boolean[][]` | 一次 Redis 读取构建障碍位图（不含车辆占位） |
| `loadBlockedMapWithCars()` | `boolean[][]` | 障碍位图 + 所有车当前位置视为不可通行 |
| `loadSealedBitmap()` | `boolean[][]` | 一次 Redis 读取构建密封区位图 |
| `writeSealedBitmap(boolean[][] sealed, int mapWidth)` | `void` | 写入密封区位图（初始化地图时调用） |
| `getExplorationRate()` | `int` | 计算地图探索率（百分比 0-100），分母 = 总格子 - 障碍 - 密封区 |
| `hasUnexploredExplorableCells()` | `boolean` | 是否仍存在可探索且未探索的格子 |
| `isExplorationComplete()` | `boolean` | 探索是否已结束（探索率 >= 100% 且无可探索格子） |
| `getExplorationStats()` | `long[]` | 详细探索统计：`{width, height, total, blocked, sealed, explorable, explored, rate}` |
| `countExploredCells()` | `long` | 统计已探索的格子总数（Redis BITCOUNT） |
| `static bytesToBitmap(byte[] bytes, int width, int height)` | `boolean[][]` | 从字节数组构建 boolean 二维数组 |
| `static bitmapToBytes(boolean[][] bitmap, int mapWidth)` | `byte[]` | 将 boolean 二维位图编码为 Redis SETBIT 兼容的字节数组 |

#### 3.1.4 地图障碍物操作（mapBlock）

| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| `isBlocked(int row, int col)` | `boolean` | 判断指定格子是否为障碍物 |
| `setBlock(int row, int col, boolean blocked)` | `void` | 设置指定格子的障碍物状态 |
| `writeBlockBitmap(boolean[][] blocked, int mapWidth)` | `void` | 整图写入障碍位图（初始化时批量写入） |

#### 3.1.5 小车位置操作（CarID:Position）

| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| `getCarPosition(String carId)` | `Optional<Point>` | 获取小车当前位置，不存在则为 empty |
| `setCarPosition(String carId, Point pos)` | `void` | 设置小车当前位置（Hash: carId:Position → {x, y}） |
| `clearCarPosition(String carId)` | `void` | 清除小车位置信息 |

#### 3.1.6 小车目标操作（CarID:Target）

| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| `getCarTarget(String carId)` | `Optional<Point>` | 获取小车当前目标点 |
| `setCarTarget(String carId, Point target)` | `void` | 设置小车目标点（Hash: carId:Target → {x, y}） |
| `clearCarTarget(String carId)` | `void` | 清除小车目标点 |
| `hasTarget(String carId)` | `boolean` | 判断小车是否有目标点（检查 key 是否存在） |

#### 3.1.7 小车路径操作（CarID:RouteList）

| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| `getCarRoute(String carId)` | `List<Point>` | 获取小车的完整路径列表（不可变 List） |
| `peekNextRouteStep(String carId)` | `Optional<Point>` | 查看路径的下一步（不移除，LINDEX 0） |
| `popNextRouteStep(String carId)` | `Optional<Point>` | 弹出路径的下一步（LPOP，移除并返回） |
| `pushRoute(String carId, List<Point> route)` | `void` | 将整条路径推入 Redis 列表（Lua 脚本先 DEL 再逆序 LPUSH） |
| `clearRoute(String carId)` | `void` | 清除小车路径 |

#### 3.1.8 小车状态操作（CarID:Status）

| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| `getCarStatus(String carId)` | `Optional<CarStatus>` | 获取小车当前状态枚举值 |
| `setCarStatus(String carId, CarStatus status)` | `void` | 设置小车状态（String 类型存储枚举名） |

#### 3.1.9 小车步数操作（CarID:Steps）

| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| `getCarSteps(String carId)` | `int` | 获取小车已移动步数，不存在返回 0 |
| `incrementCarSteps(String carId)` | `void` | 小车步数加 1（INCR） |
| `setCarSteps(String carId, int steps)` | `void` | 设置小车步数值 |

#### 3.1.10 小车有效步数操作（CarID:EffectiveSteps）

| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| `getCarEffectiveSteps(String carId)` | `int` | 获取小车走过未探索区域的步数，不存在返回 0 |
| `incrementCarEffectiveSteps(String carId)` | `void` | 小车有效步数加 1（踩入此前未探索的格子时调用） |
| `setCarEffectiveSteps(String carId, int steps)` | `void` | 设置小车有效步数值 |

#### 3.1.11 小车阻塞记录（CarID:BlockedTick）

| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| `getBlockedTick(String carId)` | `int` | 获取小车被阻塞时的 tick 号，不存在返回 -1 |
| `setBlockedTick(String carId, int tick)` | `void` | 记录小车被阻塞时的 tick 号 |
| `clearBlockedTick(String carId)` | `void` | 清除小车阻塞 tick 记录 |

#### 3.1.12 小车历史轨迹（CarID:History）

| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| `appendCarHistory(String carId, Point position, int tick)` | `void` | 向小车 History 追加一条移动记录（RPUSH {x, y, tick} JSON） |
| `getCarHistory(String carId)` | `List<String>` | 获取指定小车的全部历史轨迹（JSON 字符串列表） |
| `getAllCarHistories()` | `Map<String, List<String>>` | 获取所有小车的历史轨迹，返回 carId → 轨迹列表 |

#### 3.1.13 探索事件（explorationEvents）

| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| `recordExploration(int tick, int row, int col)` | `boolean` | 尝试探索指定格子并记录事件。仅在格子首次被探索时记录。返回 true 表示新发现。集成了 setMapViewBit：障碍物不计入探索，已探索的不重复记录 |
| `getExplorationEvents()` | `List<String>` | 获取全部探索事件列表（回放用） |

#### 3.1.14 热力图（mapHeat）

| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| `incrementMapHeat(int row, int col)` | `void` | 热力图指定格子计数加 1（Hash HINCRBY） |
| `getMapHeat()` | `Map<String, String>` | 获取完整热力图数据（不可变 Map，key = "row,col"） |

#### 3.1.15 仿真场次元数据

| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| `hasReplayableData()` | `boolean` | 是否存在可归档的轨迹或探索事件 |
| `isSimRunArchived()` | `boolean` | 查询本场仿真是否已归档 |
| `markSimRunArchived()` | `void` | 标记本场仿真已归档 |
| `clearSimRunArchived()` | `void` | 清除归档标记 |
| `getSimRunStartedAt()` | `Optional<Instant>` | 获取仿真开始时间（epoch 秒 → Instant） |
| `getSimRunStartedBy()` | `Optional<String>` | 获取仿真启动者用户名 |
| `beginSimRun(String startedBy)` | `void` | 新仿真开始前记录操作者与开始时间，同时清除归档标记 |
| `clearSimRunMetadata()` | `void` | 清除本场仿真元数据（操作者、开始时间、归档标记），仅在用户点"重置"时调用 |

#### 3.1.16 控制器锁

| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| `acquireControllerLock()` | `boolean` | 尝试获取控制器实例锁（SET NX EX 30s），返回 true 表示获取成功 |
| `releaseControllerLock()` | `void` | 释放控制器实例锁（DEL key） |

#### 3.1.17 任务配置（TaskConfig）

| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| `getTaskConfig()` | `Map<String, String>` | 获取任务配置的全部字段（Hash HGETALL） |
| `isTaskActive()` | `boolean` | 判断任务是否处于激活状态（检查 active 字段） |
| `setTaskActive(boolean active)` | `void` | 设置任务激活状态 |
| `getMapWidth()` | `int` | 从任务配置中读取地图宽度，未配置时回退构造函数默认值 |
| `getMapHeight()` | `int` | 从任务配置中读取地图高度，未配置时回退构造函数默认值 |
| `getCarCount()` | `int` | 从任务配置中读取小车数量，不存在返回 0 |
| `getAlgorithm()` | `String` | 从任务配置中读取路径规划算法名称，不存在返回 null |
| `getTickInterval()` | `int` | 从任务配置中读取 tick 间隔（毫秒），不存在返回 500 |
| `getObstacleRatio()` | `double` | 从任务配置中读取障碍物比例，不存在返回 0.15 |
| `setElapsedSeconds(long seconds)` | `void` | 设置任务耗时秒数（单字段写入，不覆盖其他字段） |
| `setTickInterval(int intervalMs)` | `void` | 更新节拍间隔（单字段写入，供多观众页同步滑块） |
| `initTaskConfig(Map<String, String> config)` | `void` | 批量初始化任务配置（Hash HSET） |

#### 3.1.18 位置预约锁（防多车重叠）

| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| `tryReservePosition(int x, int y, String carId)` | `boolean` | 尝试预约目标位置（SET NX EX 3），防止两车同时移动到同一格。key = `pos:reserve:x:y` |
| `releaseReservePosition(int x, int y, String carId)` | `void` | 释放位置预约（仅当 value 匹配 carId 时才删除） |

#### 3.1.19 数据发现与清理

| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| `discoverCarIds()` | `Set<String>` | 扫描 Redis 中所有已注册的小车 ID（通过 `Car*:Status` 键匹配） |
| `clearSimulationState()` | `void` | 清空仿真黑板数据（mapView/mapBlock/mapSealed/mapHeat/TaskConfig/explorationEvents + SCAN 模式匹配的仿真键），保留登录会话等非仿真键 |
| `getJedisPool()` | `JedisPool` | 获取底层 Redis 连接池，供外部组件（如 DistributedLock）使用 |
| `close()` | `void` | 关闭连接池，释放所有 Redis 连接（AutoCloseable） |

#### 3.1.20 内部私有方法（仅供理解实现）

| 方法签名 | 说明 |
|----------|------|
| `long bitmapOffset(int row, int col)` | 二维坐标 → 一维位图偏移，优先使用 effectiveWidth() |
| `static long bitmapOffset(int row, int col, int mapWidth)` | 按指定地图宽度计算位图偏移 |
| `int effectiveWidth()` | 获取有效地图宽度（每次从 TaskConfig 读取） |
| `int effectiveHeight()` | 获取有效地图高度（每次从 TaskConfig 读取） |
| `boolean[][] bytesToBitmap(byte[], int, int)` | 字节数组 → boolean 二维数组 |
| `static byte[] bitmapToBytes(boolean[][], int mapWidth)` | boolean 二维数组 → 字节数组 |
| `void setBitInByteArray(byte[], long)` | 字节数组特定位置 1 |
| `void markCarPositionsOnMap(boolean[][])` | 将所有车的位置标记在地图上 |
| `long[] countExplorationProgress()` | 统计可探索格子数和已探索的可探索格子数 |
| `static boolean isExplorableCell(boolean[][], boolean[][], int, int)` | 判断格子是否为可探索的（非障碍、非密封） |
| `static long countTrueCells(boolean[][])` | 统计二维 boolean 数组中 true 的数量 |
| `int readMapWidth(Jedis)` / `int readMapHeight(Jedis)` | 从 Jedis 读取地图宽高 |
| `void writeBitmapKey(String, boolean[][], int)` | 将位图编码后写入 Redis key |
| `static void deleteKeysMatching(Jedis, String)` | 扫描并删除匹配 pattern 的所有 key |

### 3.2 DistributedLock

基于 Redis 的分布式锁，用于多实例部署场景下对同一小车进行互斥操作。

#### 3.2.1 构造器

| 签名 | 说明 |
|------|------|
| `DistributedLock(JedisPool pool, String carId)` | 根据 carId 构造锁，lockKey = `lock:{carId}`，lockValue = `{线程名}-{时间戳}` |

#### 3.2.2 方法

| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| `tryLock()` | `boolean` | 使用默认超时时间（5000ms）尝试获取锁 |
| `tryLock(int timeoutMs)` | `boolean` | 尝试获取锁，使用 `SET key value NX PX timeoutMs` 原子命令 |
| `unlock()` | `void` | 释放锁，使用 Lua 脚本保证"先校验再删除"的原子性：仅当 `get(key) == lockValue` 时才删除 |

Lua 解锁脚本：
```lua
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
else
    return 0
end
```

内部常量：

| 常量 | 值 | 说明 |
|------|-----|------|
| `LOCK_KEY_PREFIX` | `"lock:"` | 锁 key 前缀 |
| `DEFAULT_TIMEOUT_MS` | `5000` | 默认超时时间（毫秒） |

### 3.3 MapBitmapSnapshot

地图位图快照，用于高效获取和传输地图状态。通过 pipeline 一次性读取三张位图的字节数组，减小并发写入导致的竞态窗口。

字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| `mapView` | `byte[]` | 探索位图字节数组 |
| `mapBlock` | `byte[]` | 障碍位图字节数组 |
| `mapSealed` | `byte[]` | 密封区位图字节数组 |

---

## 4. mq 包——消息总线

`com.substation.common.mq` 封装 RabbitMQ 消息通信，提供统一的消息发布/订阅机制。共 4 个类。

### 4.1 MessageBus

**实现 `AutoCloseable`**，封装 RabbitMQ 连接管理。

#### 4.1.1 构造器

| 签名 | 说明 |
|------|------|
| `MessageBus(String host, int port, String username, String password)` | 构造消息总线，不立即建立连接 |

#### 4.1.2 连接管理

| 方法签名 | 返回值 | 抛出异常 | 说明 |
|----------|--------|----------|------|
| `connect()` | `void` | `IOException`, `TimeoutException` | 建立 RabbitMQ 连接并创建通道，开启自动恢复（`setAutomaticRecoveryEnabled(true)`） |
| `isConnected()` | `boolean` | — | 判断 RabbitMQ 连接是否已建立且处于打开状态 |
| `close()` | `void` | — | 关闭通道和连接，释放资源（AutoCloseable） |

#### 4.1.3 队列声明

| 方法签名 | 返回值 | 抛出异常 | 说明 |
|----------|--------|----------|------|
| `declareCarQueue(String carId)` | `void` | `IOException` | 声明指定小车的持久化队列（`Car_{carId}`） |
| `declareNavigatorQueue()` | `void` | `IOException` | 声明导航器指令队列（`NavigatorCmd`） |
| `declareTargetPlannerQueue()` | `void` | `IOException` | 声明目标规划器指令队列（`TargetPlannerCmd`） |
| `declareTaskConfigQueue()` | `void` | `IOException` | 声明任务配置指令队列（`TaskConfigCmd`） |
| `declareControllerQueue()` | `void` | `IOException` | 声明控制器指令队列（`ControllerCmd`） |
| `declareStrategySupervisorQueue()` | `void` | `IOException` | 声明策略监督器指令队列（`StrategySupervisorCmd`） |
| `declareQueue(String queueName)` | `void` | `IOException` | 声明任意名称的持久化队列（durable=true），用于测试或扩展模块 |
| `purgeQueue(String queueName)` | `void` | `IOException` | 清空指定队列中尚未消费的消息 |

所有队列声明参数：`durable=true, exclusive=false, autoDelete=false`。

#### 4.1.4 交换机声明与绑定

| 方法签名 | 返回值 | 抛出异常 | 说明 |
|----------|--------|----------|------|
| `declareFanoutExchange()` | `void` | `IOException` | 声明视图更新 Fanout 广播交换机（`UpdateView`，durable=true） |
| `bindFanoutQueue()` | `String` | `IOException` | 声明一个临时匿名队列并绑定到 Fanout 交换机，返回临时队列名称（用于接收视图更新广播） |

#### 4.1.5 消息发布

| 方法签名 | 返回值 | 抛出异常 | 说明 |
|----------|--------|----------|------|
| `publish(String queueName, String message)` | `void` | `IOException` | 向指定队列发送消息（点对点模式，默认 exchange=""） |
| `publishFanout(String exchangeName, String message)` | `void` | `IOException` | 向指定交换机广播消息（Fanout 模式，routingKey=""） |

#### 4.1.6 消息订阅

| 方法签名 | 返回值 | 抛出异常 | 说明 |
|----------|--------|----------|------|
| `subscribe(String queueName, Consumer<String> handler)` | `void` | `IOException` | 订阅指定队列的消息，自动 ACK（`autoAck=true`），handler 接收消息体字符串 |

### 4.2 MessageTypes

消息类型常量类，定义系统中 **19 种 MQ 消息**的 `type` 字段取值。工具类（`final class`，私有构造器，禁止实例化）。

按功能分为五组：

#### 4.2.1 目标分配流程

| 常量 | 字符串值 | 方向 | 说明 |
|------|----------|------|------|
| `ASSIGN_TARGET` | `"ASSIGN_TARGET"` | Controller → TargetPlanner | 请求分配目标点 |
| `TARGET_ASSIGNED` | `"TARGET_ASSIGNED"` | TargetPlanner → Car | 返回分配结果 |

#### 4.2.2 路径规划流程

| 常量 | 字符串值 | 方向 | 说明 |
|------|----------|------|------|
| `PLAN_ROUTE` | `"PLAN_ROUTE"` | Controller → Navigator | 请求规划路径 |
| `ROUTE_PLANNED` | `"ROUTE_PLANNED"` | Navigator → Car | 返回规划完成的路径 |

#### 4.2.3 移动控制流程

| 常量 | 字符串值 | 方向 | 说明 |
|------|----------|------|------|
| `TICK_MOVE` | `"TICK_MOVE"` | Controller → Car | 触发所有小车执行一步移动 |
| `MOVED` | `"MOVED"` | Car → Controller | 返回移动结果（步数、位置等） |
| `ROUTE_DONE` | `"ROUTE_DONE"` | Car → Controller | 路径走完确认 |
| `BLOCKED` | `"BLOCKED"` | Car → Controller | 报告被障碍物阻塞 |
| `BLOCKED_TIMEOUT` | `"BLOCKED_TIMEOUT"` | Controller → Car | 阻塞超时，下发重新规划指令 |

#### 4.2.4 系统控制流程

| 常量 | 字符串值 | 方向 | 说明 |
|------|----------|------|------|
| `REFRESH_ALL` | `"REFRESH_ALL"` | —（Fanout 广播） | 刷新全部视图（地图、状态等） |
| `SET_CONFIG` | `"SET_CONFIG"` | Frontend → Controller | 前端下发任务参数配置 |
| `FORWARD_CONFIG` | `"FORWARD_CONFIG"` | Controller → Planner/Navigator | 控制器转发配置到规划器和导航器 |
| `RESET` | `"RESET"` | Frontend → Controller | 前端下发重置指令 |
| `FORWARD_RESET` | `"FORWARD_RESET"` | Controller → Planner/Navigator | 控制器转发重置到规划器和导航器 |
| `TASK_READY` | `"TASK_READY"` | Controller → Frontend | 通知前端任务准备就绪 |
| `TOGGLE_PAUSE` | `"TOGGLE_PAUSE"` | Frontend → Controller | 前端请求切换暂停/继续状态 |
| `SET_TICK_INTERVAL` | `"SET_TICK_INTERVAL"` | Frontend → Controller | 前端请求修改 tick 间隔时间 |

#### 4.2.5 策略监督流程

| 常量 | 字符串值 | 方向 | 说明 |
|------|----------|------|------|
| `SUPERVISE_ROUTE` | `"SUPERVISE_ROUTE"` | Controller → Supervisor | 请求审查路线优化 |
| `ROUTE_OPTIMIZED` | `"ROUTE_OPTIMIZED"` | Supervisor → Controller | 路线优化结果回复 |

### 4.3 QueueNames

队列与交换机名称常量。工具类（`final class`，私有构造器，禁止实例化）。

#### 4.3.1 常量

| 常量 | 字符串值 | 说明 |
|------|----------|------|
| `CAR_PREFIX` | `"Car_"` | 小车队列名前缀，完整名称为 `Car_{carId}` |
| `NAVIGATOR_CMD` | `"NavigatorCmd"` | 导航器指令队列 |
| `TARGET_PLANNER_CMD` | `"TargetPlannerCmd"` | 目标规划器指令队列 |
| `TASK_CONFIG_CMD` | `"TaskConfigCmd"` | 任务配置指令队列 |
| `CONTROLLER_CMD` | `"ControllerCmd"` | 控制器指令队列 |
| `STRATEGY_SUPERVISOR_CMD` | `"StrategySupervisorCmd"` | 策略监督器指令队列 |
| `UPDATE_VIEW_EXCHANGE` | `"UpdateView"` | 视图更新广播交换机（Fanout） |

#### 4.3.2 静态方法

| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| `static String carQueue(String carId)` | `String` | 根据小车 ID 生成对应的队列名称，返回 `Car_{carId}` |

### 4.4 MessageBuilder

消息构建工具类（`final class`，私有构造器，禁止实例化），提供 3 个重载的 `build()` 静态方法，将类型、tick、车辆 ID、数据组装成统一的 JSON 消息字符串。所有 MQ 消息均通过此工具类生成，保证格式一致。

消息 JSON 结构：
```json
{
    "type": "TICK_MOVE",
    "tick": 42,
    "carId": "Car_1",
    "timestamp": 1719500000000,
    "data": {}
}
```

#### 4.4.1 方法

| 方法签名 | 说明 |
|----------|------|
| `static String build(String type, int tick, String carId, Map<String, Object> data)` | 构建完整消息（含 data 字段）。data 为 null 时自动替换为空 Map |
| `static String build(String type, int tick, String carId)` | 构建消息（data 为空 Map），等价于 `build(type, tick, carId, null)` |
| `static String build(String type, int tick)` | 构建系统级消息（无 carId，无 data），等价于 `build(type, tick, null, null)` |

---

## 5. 根包——DynamicObstacleUtil

`com.substation.common.DynamicObstacleUtil` 直接位于根包下，为 Controller 提供动态障碍物生成能力。供 Controller 每 N 拍调用。

内部常量：

| 常量 | 值 | 说明 |
|------|-----|------|
| `MAX_ADD_COUNT` | `2` | 每轮最多新增障碍物数量 |
| `MAX_REMOVE_COUNT` | `2` | 每轮最多移除障碍物数量 |
| `MAX_RANDOM_ATTEMPTS` | `50` | 随机新增时的最大尝试次数 |

构造器与方法：

| 签名 | 返回值 | 说明 |
|------|--------|------|
| `DynamicObstacleUtil()` | — | 默认构造器，初始化 Random 实例 |
| `generate(BlackboardClient bb, int mapWidth, int mapHeight, Set<Point> carPositions)` | `List<String>` | 生成动态障碍物变更：随机移除若干障碍物，随机新增若干障碍物。新增的障碍物不会覆盖小车当前位置。返回变更日志列表，如 `["新增(12,8)", "移除(25,3)"]` |

---

## 6. infra 包——基础设施配置

`com.substation.common.infra` 管理分布式部署的连接参数。共 3 个文件。

| 类 | 说明 |
|-----|------|
| `DeployConfig` | 部署配置数据对象，记录各节点的主机地址和端口 |
| `DeployConfigLoader` | 从配置文件加载部署配置 |
| `InfraConnectionConfig` | 基础设施连接参数（Redis、RabbitMQ 等地址信息） |

---

## 7. map 包——地图算法工具包

`com.substation.common.map` 提供探索策略所需的基础算法。共 8 个文件。

| 类 | 说明 |
|-----|------|
| `ReachabilityAnalyzer` | 可达性分析，判断地图中哪些格子从起点可达 |
| `FrontierCellFinder` | 前沿格子查找器，找出已探索区域边界上的未探索格子 |
| `SpawnPositionSelector` | 初始位置选择器，为小车选择合理的出生点 |
| `UnexploredClusterFinder` | 未探索区域聚类查找 |
| `UnexploredCluster` | 未探索区域聚类数据对象 |
| `ExplorationPathCosts` | 探索路径代价计算 |
| `ExplorationWeightedPathFinder` | 带权重的探索路径查找器 |
| `ShortestHopPathFinder` | 最短跳数路径查找器 |

---

## 8. auth 包——认证鉴权基础设施

`com.substation.common.auth` 提供统一的认证鉴权能力。共 10 个文件（含 model 子包 5 个）。

| 类/子包 | 说明 |
|---------|------|
| `AuthApiHandler` | 认证 API 处理器（登录、登出、验证接口） |
| `AuthFilter` | 认证过滤器，拦截请求并进行 Token 校验 |
| `AuthResponses` | 认证响应常量与构造工具 |
| `SessionManager` | 会话管理器（创建、验证、销毁 Session） |
| `UserStore` | 用户存储接口抽象 |
| `model/LoginRequest` | 登录请求数据模型 |
| `model/LoginResponse` | 登录响应数据模型 |
| `model/SessionInfo` | 会话信息数据模型 |
| `model/SessionValidation` | 会话验证结果 |
| `model/UserInfo` | 用户信息数据模型 |

---

## 9. sql 包——数据库访问层

`com.substation.common.sql` 封装 JDBC 操作，为系统提供持久化存储能力。共 7 个文件（含 model 子包 3 个）。

| 类/子包 | 说明 |
|---------|------|
| `DatabaseManager` | 数据库连接管理与表初始化 |
| `SqlUserStore` | 基于 SQL 的用户存储实现（替代内存 UserStore，实现 UserStore 接口） |
| `RegistrationStore` | 用户注册记录存储 |
| `OperationLogStore` | 操作日志存储（审计用途） |
| `model/UserRecord` | 用户表记录 |
| `model/RegistrationRecord` | 注册记录 |
| `model/OperationLogRecord` | 操作日志记录 |

---

## 10. analysis 包——仿真数据分析

`com.substation.common.analysis` 提供仿真数据的统计分析和查询能力。共 8 个文件（含 model 子包 4 个）。

| 类/子包 | 说明 |
|---------|------|
| `AnalysisApiHandler` | 分析 API 处理器 |
| `AnalysisEngine` | 分析引擎（统计计算核心） |
| `SimulationRecordService` | 仿真记录查询服务 |
| `SimulationStatsStore` | 仿真统计数据存储 |
| `model/AnalysisQuery` | 分析查询参数 |
| `model/CarStatistics` | 单车统计数据 |
| `model/SimulationStatsSummary` | 单场仿真统计摘要 |
| `model/SummaryStatistics` | 汇总统计（多场聚合） |

---

## 11. replay 包——仿真回放基础设施

`com.substation.common.replay` 提供仿真回放和归档能力。共 7 个文件（含 model 子包 3 个）。

| 类/子包 | 说明 |
|---------|------|
| `ReplayApiHandler` | 回放 API 处理器 |
| `ReplayDataBuilder` | 回放数据构建器（从 Redis 提取事件重建帧序列） |
| `RunArchiver` | 仿真归档处理器（Redis → SQL 持久化） |
| `SimulationRunStore` | 仿真场次存储 |
| `model/SimulationRunRecord` | 仿真场次记录 |
| `model/SimulationRunStatus` | 仿真运行状态枚举 |
| `model/SimulationRunSummary` | 仿真场次摘要 |

---

## 12. admin 包——管理员 API

`com.substation.common.admin` 提供管理员后台接口。共 1 个文件。

### 12.1 AdminApiHandler

管理员 API 处理器，负责用户管理、注册审核和操作日志查询。所有接口需要 admin 角色（由 HttpFileServer 鉴权保证）。

构造器：

| 签名 | 说明 |
|------|------|
| `AdminApiHandler(SqlUserStore userStore, RegistrationStore regStore, OperationLogStore logStore)` | 注入用户存储、注册存储、日志存储 |

路由入口：

| 签名 | 说明 |
|------|------|
| `void handle(String path, HttpExchange exchange)` | 根据 HTTP 方法和路径分发到具体的处理方法 |

API 路由表：

| HTTP 方法 | 路径 | 处理方法 | 说明 |
|-----------|------|----------|------|
| `GET` | `/api/admin/users` | `handleUserList` | 用户列表（分页查询，支持 search/role 筛选） |
| `GET` | `/api/admin/users/{username}` | `handleUserDetail` | 用户详情 |
| `POST` | `/api/admin/users/{username}/reset-password` | `handleResetPassword` | 重置用户密码为 123456 |
| `GET` | `/api/admin/registrations` | `handleRegistrationList` | 注册申请列表（按 status 筛选，分页） |
| `POST` | `/api/admin/registrations/{id}/approve` | `handleApprove` | 通过注册申请 |
| `POST` | `/api/admin/registrations/{id}/reject` | `handleReject` | 拒绝注册申请 |
| `GET` | `/api/admin/logs` | `handleLogList` | 操作日志列表（支持 username/action 筛选，分页） |

内部私有处理方法：

| 方法 | 说明 |
|------|------|
| `handleUserList(HttpExchange)` | 分页查询用户列表，支持 search/role 参数 |
| `handleUserDetail(HttpExchange, String username)` | 查询单个用户详情 |
| `handleResetPassword(HttpExchange, String username)` | 重置用户密码，记录操作日志 |
| `handleRegistrationList(HttpExchange)` | 分页查询注册申请列表 |
| `handleApprove(HttpExchange, int id)` | 审批通过注册申请，记录操作日志 |
| `handleReject(HttpExchange, int id)` | 拒绝注册申请，记录操作日志 |
| `handleLogList(HttpExchange)` | 分页查询操作日志 |

---

## 13. 设计原则

1. **零业务逻辑**: Common 模块仅提供基础设施封装和数据结构定义，不包含任何与变电站巡检业务相关的决策逻辑。算法路径规划、目标分配策略、控制流程编排等业务逻辑均在各自模块中实现。

2. **纯基础设施定位**: 所有代码服务于一个明确的基础设施目的——数据模型定义、存储访问（Redis/SQL）、消息通信（RabbitMQ）、认证鉴权、配置管理、地图算法、管理员后台。任何引入 Common 模块的代码必须自问："这个能力是否是多个模块都需要的纯基础设施？"

3. **单一依赖方向**: 系统中的依赖关系严格单向——8 个业务模块依赖 Common，Common 不依赖任何业务模块。Common 仅依赖第三方库（Jedis、RabbitMQ Client、FastJSON2、JDBC 等）。

4. **AutoCloseable 资源管理**: `BlackboardClient` 和 `MessageBus` 均实现 `AutoCloseable`，支持 try-with-resources 模式，确保连接池和通道的正确释放。

5. **分布式友好**: 所有基础设施组件均面向多实例部署设计——Redis 位图支持多实例并发读写、分布式锁保证互斥、位置预约锁防多车重叠、RabbitMQ 自动恢复应对网络抖动、Socket timeout 适配 局域网 高延迟网络。

6. **工具类设计**: `MessageTypes`、`QueueNames`、`MessageBuilder`、`DynamicObstacleUtil` 均为 `final class` + 私有构造器，禁止实例化，确保纯静态工具类的语义清晰。

7. **不可变数据**: 使用 Java `record` 实现 `Point`、`RouteStep`、`SimulationState` 等数据载体，天然不可变，线程安全，适合在分布式消息传递中使用。
