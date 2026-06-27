# TargetPlanner 模块设计文档

> **模块**：`target-planner`（`com.substation.targetplanner`）
> **Maven 坐标**：`com.substation:car-homework-target-planner`
> **入口类**：`TargetPlannerMain`
> **Java 文件数**：3
> **角色**：知识源——目标分配器（TargetPlanner），响应 Controller 的 ASSIGN_TARGET 命令，为指定车辆分配最优探索目标

---

## 1. 模块概述

TargetPlanner 模块是**变电站巡检仿真系统**中的知识源（Knowledge Source），负责在收到 Controller 的 `ASSIGN_TARGET` 命令后，根据当前地图探索状态为指定车辆计算并分配探索目标。它实现黑板架构中**自主决策知识源**角色：只读取黑板状态（车辆位置、探索地图、障碍物地图、其他车辆路线），不做任何黑板写入（目标写入由 `TargetPlannerMain` 通过 `BlackboardClient` 完成）。

**核心约束**：
- 只响应 Controller 发出的 `ASSIGN_TARGET` 消息，不主动发起通信
- **只写入 `CarID:Target`**，不写入 `CarID:Status`（状态修改归 Controller 管辖）
- 同一 tick 内通过 `Set<Point>` 防止多车分配到同一目标格子
- 路径估算使用加权 Dijkstra，优先穿越未探索区域

### 1.1 模块结构

```
com.substation.targetplanner
├── TargetPlannerMain        入口：连接中间件、订阅 ASSIGN_TARGET、发送 TARGET_ASSIGNED
├── GreedyTargetAllocator    核心：双模式贪心分配算法（逐格/聚类）
└── TargetPathEstimator      路径估算：封装 ExplorationWeightedPathFinder 供评分使用
```

### 1.2 与其他模块的消息交互

```
                         ┌──────────────────────────┐
                         │                          │
  Controller ──ASSIGN_TARGET──→   TargetPlanner     │
                         │                          │
                         │  1. 读 bb: 车辆位置         │
                         │  2. 读 bb: explored 位图    │
                         │  3. 读 bb: obstacles 位图   │
                         │  4. 读 bb: sealed 位图      │
                         │  5. 读 bb: 其他车路线/target │
                         │  6. 写 bb: setCarTarget()   │
                         │                          │
  Controller ←──TARGET_ASSIGNED── TargetPlanner     │
                         └──────────────────────────┘
```

---

## 2. TargetPlannerMain —— 目标分配器主入口

**文件**：`TargetPlannerMain.java`（146 行）

**职责**：初始化 Redis/MQ 连接、订阅 `TargetPlannerCmd` 队列、解析 `ASSIGN_TARGET` 消息并分派给 `GreedyTargetAllocator`、将分配结果通过 `TARGET_ASSIGNED` 消息回传给 Controller。

### 2.1 构造参数

```java
public TargetPlannerMain(String redisHost, int redisPort, String mqHost, int mqPort)
```

| 参数 | 说明 | 默认值（独立运行时） |
|------|------|---------------------|
| `redisHost` | Redis 地址 | localhost |
| `redisPort` | Redis 端口 | 6379 |
| `mqHost` | RabbitMQ 地址 | localhost |
| `mqPort` | RabbitMQ 端口 | 5672 |

MQ 用户名密码固定为 `guest/guest`。地图尺寸固定为 `BlackboardClient.DEFAULT_WIDTH × DEFAULT_HEIGHT`（30×30）。

### 2.2 启动流程 (`start()`)

```
 1. new BlackboardClient(redisHost, redisPort, 30, 30)
 2. new MessageBus(mqHost, mqPort, "guest", "guest")
 3. bus.connect()
 4. bus.declareTargetPlannerQueue()         ← "TargetPlannerCmd"
 5. new GreedyTargetAllocator()
 6. bus.subscribe("TargetPlannerCmd", callback)
    └─ 解析 JSON → type == "ASSIGN_TARGET"？
       └─ handleAssignTarget(allocator, carId, tick)
 7. Runtime.addShutdownHook:
    ├─ bus.close()
    └─ bb.close()
```

### 2.3 `main()` 入口

```java
public static void main(String[] args) throws IOException, TimeoutException {
    var infra = InfraConnectionConfig.resolve(args);
    new TargetPlannerMain(
            infra.redisHost(), infra.redisPort(), infra.mqHost(), infra.mqPort()).start();
    synchronized (TargetPlannerMain.class) {
        try { TargetPlannerMain.class.wait(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
```

独立运行时从命令行参数或 `deploy/infra.local.json` 解析基础设施连接配置，启动后主线程阻塞等待。

### 2.4 消息处理核心流程 (`handleAssignTarget`)

```
handleAssignTarget(allocator, carId, tick):
│
├─ 1. tick 变化检测
│     if (tick != currentTick):
│       allocatedTargets.clear()
│       currentTick = tick
│     → 新的 tick 开始，清空本 tick 已分配集合
│
├─ 2. 获取车辆当前位置
│     bb.getCarPosition(carId)
│     不存在 → sendFailureReply + return
│
├─ 3. 贪心分配
│     allocator.allocate(carId, currentPos, bb, allocatedTargets)
│     → 内部完成 scoring、路径估算、去重
│
├─ 4. 成功
│     target.isPresent():
│       bb.setCarTarget(carId, target)          ← 唯一黑板写入：只写 Target
│       sendSuccessReply(carId, target, tick)
│
└─ 5. 失败
│     target.isEmpty():
│       log "暂无可分配目标"
│       sendFailureReply(carId, tick)
```

### 2.5 同一 tick 内防重复分配机制

```java
private final Set<Point> allocatedTargets = new HashSet<>();
private int currentTick = -1;

// 每次 handleAssignTarget 开头：
if (tick != currentTick) {
    allocatedTargets.clear();  // 新 tick 重置
    currentTick = tick;
}
// allocate() 内部会将选定的目标加入 allocatedTargets
// 后续同一 tick 的其他车辆分配时，已分配格自动排除
```

关键设计：
- `allocatedTargets` 是 `HashSet<Point>`，存储本 tick 已分配给其他车辆的 `(x, y)` 坐标
- tick 号变化时自动清空，无需手动管理生命周期
- `GreedyTargetAllocator` 内部在确定目标后会调用 `alreadyAllocated.add(bestTarget)`，同时候选集筛选时会 `.removeAll(alreadyAllocated)`

### 2.6 回复消息构造

**成功回复**：

```java
Map<String, Object> data = Map.of(
    "carId", carId,
    "success", true,
    "target", Map.of("x", target.x(), "y", target.y())
);
String reply = MessageBuilder.build(MessageTypes.TARGET_ASSIGNED, tick, carId, data);
messageBus.publish(QueueNames.CONTROLLER_CMD, reply);
```

**失败回复**：

```java
Map<String, Object> data = Map.of("carId", carId, "success", false);
String reply = MessageBuilder.build(MessageTypes.TARGET_ASSIGNED, tick, carId, data);
messageBus.publish(QueueNames.CONTROLLER_CMD, reply);
```

两种回复均发送到 `ControllerCmd` 队列，消息类型均为 `TARGET_ASSIGNED`。Controller 根据 `data.success` 字段判断分配是否成功。

### 2.7 消息收发汇总

| 方向 | 消息类型 | 队列 | 说明 |
|------|----------|------|------|
| 接收 | `ASSIGN_TARGET` | `TargetPlannerCmd` | Controller 命令：为指定 carId 分配目标 |
| 发送 | `TARGET_ASSIGNED` | `ControllerCmd` | 分配结果回复（成功/失败） |

---

## 3. GreedyTargetAllocator —— 贪心目标分配器（核心）

**文件**：`GreedyTargetAllocator.java`（354 行）

**职责**：实现双模式贪心目标分配算法。根据全局探索率自动切换模式——低探索率时采用 frontier-first 逐格评分，高探索率时采用未探索聚类分配。通过路径长度、重合度、内部深度、聚类大小等多维评分函数选择最优目标。

### 3.1 核心常量和阈值

| 常量 | 值 | 说明 |
|------|-----|------|
| `CLUSTER_MODE_RATE_THRESHOLD` | 85 | 探索率 >= 85% 时切换为聚类分配模式 |
| `OVERLAP_PENALTY` | 6 | 路径与他人路线每重合一格的惩罚步数 |
| `CLUSTER_SIZE_WEIGHT` | 4 | 聚类区域内每格的收益权重 |
| `UNEXPLORED_PATH_WEIGHT` | 2 | 路径上每经过一个未探索内部格的收益权重 |
| `INTERIOR_DEPTH_WEIGHT` | 4 | 目标距地图边缘每深一格的收益权重 |
| `PERIMETER_TARGET_PENALTY` | 20 | 贴边目标（深度 <= 1）额外惩罚，避免沿外围兜圈 |
| `MIN_INTERIOR_DEPTH_FOR_PATH_BONUS` | 2 | 路径未探索收益只统计距边缘至少此深度的格子 |
| `MAX_CANDIDATES_TO_SCORE` | 40 | 候选过多时，按 BFS 可达距离保留的前 N 个再精细评分 |

### 3.2 双模式分配入口

```java
Optional<Point> allocate(String carId, Point currentPosition, BlackboardClient bb,
                         Set<Point> alreadyAllocated) {
    if (bb.getExplorationRate() >= CLUSTER_MODE_RATE_THRESHOLD) {
        return allocateByCluster(carId, currentPosition, bb, alreadyAllocated);
    }
    return allocateByCell(carId, currentPosition, bb, alreadyAllocated);
}
```

| 模式 | 触发条件 | 策略 | 适用场景 |
|------|----------|------|----------|
| 逐格模式 (`allocateByCell`) | 探索率 < 85% | frontier-first 候选 + 路径重合评分 | 探索前期，未探索区域广阔，优先扩展前沿 |
| 聚类模式 (`allocateByCluster`) | 探索率 >= 85% | UnexploredClusterFinder 聚类 + 区域块入口评分 | 探索末期，未探索区域呈散落斑块，集中清扫残留区域 |

### 3.3 逐格分配模式 (`allocateByCell`)

```
allocateByCell(carId, currentPosition, bb, alreadyAllocated):
│
├─ 1. 收集候选格
│     collectFrontierFirstCandidates(bb, width, height)
│     → 优先 frontier cells（已探区域边界处的未探格）
│     → frontier 为空时回退到所有未探索格
│
├─ 2. 排除已分配 + 排除自身
│     candidates.removeAll(alreadyAllocated)
│     candidates.removeIf(cell == currentPos)
│
├─ 3. 候选为空 → Optional.empty()
│
└─ 4. 路径重合评分选择
│     selectByPathOverlapScore(carId, currentPos, candidates, bb, alreadyAllocated)
│     → chosen 加入 alreadyAllocated
│     → 返回 chosen
```

#### 3.3.1 Frontier-first 候选收集

```
collectFrontierFirstCandidates(bb, width, height):
│
├─ 1. 加载已探索/障碍物/密封位图
├─ 2. FrontierCellFinder.findFrontierCells(explored, obstacles, sealed, width, height)
│     → frontier cell：已探索格的四邻接未探索格（未探索且在障碍/密封之外）
│
├─ 3. frontier 非空 → 返回 frontier 列表
└─ 4. frontier 为空 → 返回全部未探索格列表
```

Frontier-first 策略的意图：优先在已探索边界处分配目标，使多辆车从已探索区域的多个前沿同时向未探索区域推进，最大化探索效率。当所有前沿都被覆盖后（无 frontier cell），回退到扫描全图未探索格。

#### 3.3.2 路径重合评分选择 (`selectByPathOverlapScore`)

```
selectByPathOverlapScore(carId, currentPos, candidates, bb, alreadyAllocated):
│
├─ 1. 收集重合格集合
│     collectOverlapCells(carId, bb, alreadyAllocated)
│     = alreadyAllocated          ← 本 tick 已分配目标坐标
│     + 其他车辆的路线全部坐标     ← bb.getCarRoute(otherId)
│     + 其他车辆的当前目标坐标     ← bb.getCarTarget(otherId)
│
├─ 2. 候选池裁剪
│     narrowCandidatePool(currentPos, candidates, blocked, explored, width, height)
│     → candidates 超过 MAX_CANDIDATES_TO_SCORE(40) 时：
│       按 BFS 可达路径长度排序，保留前 40 个最近候选
│
├─ 3. 遍历评分
│     for each candidate in pool:
│       clusterSize = countInteriorConnectedUnexplored(candidate, ...)
│       score = scoreTargetCell(currentPos, candidate, clusterSize, ...)
│       → 保留 score 最小的候选为 bestTarget
│
├─ 4. 有最优目标 → 返回
│
└─ 5. 无最优目标 → fallbackFarthestReachable()
     → 从全部候选中选 BFS 可达路径最长的目标
     → 宁可走远一点也要有目标可分配
```

### 3.4 聚类分配模式 (`allocateByCluster`)

```
allocateByCluster(carId, currentPos, bb, alreadyAllocated):
│
├─ 1. 加载位图矩阵
│     explored, obstacles, sealed, blocked (含车辆占据)
│
├─ 2. 发现未探索聚类
│     UnexploredClusterFinder.findClusters(explored, obstacles, sealed)
│     → 返回 List<UnexploredCluster>（每个聚类包含一组连通未探索格）
│
├─ 3. 收集重合格集合（同逐格模式）
│
├─ 4. 遍历每个聚类
│     for each cluster:
│
│       ├─ 排除已分配格
│       │   cluster.withoutAllocated(alreadyAllocated)
│       │   → 若聚类全部格已被分配 → 跳过此聚类
│       │
│       └─ scoreCluster(currentPos, cluster, ...)
│           │
│           ├─ (a) 计算聚类价值
│           │   countInteriorCellsInCluster(cluster, ...)
│           │   → 统计聚类中位于内部区域（深度 >= 2）的未探格数量
│           │   → 反映该聚类的"可探索价值"
│           │
│           ├─ (b) 选择聚类入口候选
│           │   selectClusterEntryCandidates(cluster, explored, obstacles, sealed, ...)
│           │   → 优先该聚类中的 frontier cells（已探区邻接的格子）
│           │   → 无 frontier 时回退到整个聚类的所有格子
│           │
│           └─ (c) 评分每个入口候选
│               scoreTargetCell(currentPos, entry, valuableClusterSize, ...)
│               → 保留得分最低的入口
│
├─ 5. 所有聚类中选得分最低的入口作为全局最佳目标
│
├─ 6. bestTarget != null → alreadyAllocated.add(bestTarget) + 返回
└─ 7. bestTarget == null → Optional.empty()（无可分配目标）
```

#### 3.4.1 聚类入口候选选择

```java
private List<Point> selectClusterEntryCandidates(UnexploredCluster cluster, ...) {
    List<Point> frontiers = new ArrayList<>();
    for (Point cell : cluster.cells()) {
        if (FrontierCellFinder.isFrontier(cell.x(), cell.y(), explored, obstacles, sealed, width, height)) {
            frontiers.add(cell);
        }
    }
    return frontiers.isEmpty() ? cluster.cells() : frontiers;
}
```

入口候选策略：优先选择聚类中紧邻已探索区域的"前沿"格子，使得车辆能直接抵达聚类入口并开始清扫。若无前沿格子（聚类完全被障碍物/地图边缘包围），则回退到聚类内所有格子。

### 3.5 核心评分函数 (`scoreTargetCell`)

```java
private int scoreTargetCell(Point currentPos, Point target, int clusterSize,
                             boolean[][] blocked, boolean[][] explored,
                             int width, int height, Set<Point> overlapCells) {
    // 1. 路径估算
    List<Point> path = pathEstimator.planPath(currentPos, target, blocked, explored, width, height);
    if (path.isEmpty() && !currentPos.equals(target)) {
        return Integer.MAX_VALUE;  // 不可达
    }

    // 2. 各维度评分项
    int overlap = countSharedCells(path, overlapCells);              // 与其他车辆路线重合的步数
    int unexploredOnPath = countInteriorUnexploredOnPath(path, ...); // 路径上经过的内部未探索格数量
    int interiorDepth = depthFromMapEdge(target, width, height);     // 目标距地图边缘的最小距离
    int perimeterPenalty = interiorDepth <= 1 ? PERIMETER_TARGET_PENALTY : 0;

    // 3. 综合评分（越低越好）
    return path.size()                                    // + 路径长度
        + OVERLAP_PENALTY * overlap                       // + 重合惩罚
        + perimeterPenalty                                // + 贴边惩罚
        - CLUSTER_SIZE_WEIGHT * clusterSize                // - 聚类收益
        - UNEXPLORED_PATH_WEIGHT * unexploredOnPath        // - 路径未探收益
        - INTERIOR_DEPTH_WEIGHT * interiorDepth;           // - 深度收益
}
```

**评分维度详解**：

| 评分项 | 符号 | 计算方式 | 设计意图 |
|--------|------|----------|----------|
| `path.size()` | + | BFS/加权 Dijkstra 路径步数 | 优先选择近的目标，减少无效移动 |
| `OVERLAP_PENALTY * overlap` | + | 路径与其他车辆已占格子的重合步数 | 避免多车走相同路线，减少拥堵 |
| `perimeterPenalty` | + | 目标距地图边缘 <= 1 时加 20 分 | 防止车辆贴着地图边缘绕圈，推进内部探索 |
| `CLUSTER_SIZE_WEIGHT * clusterSize` | - | 目标所在连通未探区域中内部格的数量 | 鼓励车辆前往大面积未探区域（逐格模式）或高价值聚类（聚类模式） |
| `UNEXPLORED_PATH_WEIGHT * unexploredOnPath` | - | 路径上经过的深度 >= 2 的未探格数 | 鼓励选择途经大量未探区域的路径，行走过程本身即探索 |
| `INTERIOR_DEPTH_WEIGHT * interiorDepth` | - | 目标距地图边缘的曼哈顿距离（四边最小值） | 鼓励深入地图内部，避免在边缘徘徊 |

**评分越低越好**（类似代价函数），分配器选择得分最小的候选。

### 3.6 路径重合避免机制 (`collectOverlapCells`)

```java
private Set<Point> collectOverlapCells(String carId, BlackboardClient bb,
                                        Set<Point> alreadyAllocated) {
    Set<Point> cells = new HashSet<>(alreadyAllocated);      // 本 tick 已分配的目标
    for (String otherId : bb.discoverCarIds()) {
        if (otherId.equals(carId)) continue;                 // 排除自己
        bb.getCarRoute(otherId).forEach(cells::add);         // 其他车的完整路线
        bb.getCarTarget(otherId).ifPresent(cells::add);      // 其他车的当前目标
    }
    return cells;
}
```

三重避免：
1. **同 tick 已分配目标**（`alreadyAllocated`）：防止多车在同一 tick 分配到同一格子
2. **其他车辆完整路线**（`getCarRoute`）：防止新路径与已在路上的车辆重叠
3. **其他车辆当前目标**（`getCarTarget`）：防止两车朝同一目标移动

### 3.7 候选池裁剪 (`narrowCandidatePool`)

```java
private List<Point> narrowCandidatePool(Point currentPos, List<Point> candidates,
                                         boolean[][] blocked, boolean[][] explored,
                                         int width, int height) {
    if (candidates.size() <= MAX_CANDIDATES_TO_SCORE) {
        return candidates;  // 候选不多，全部评分
    }
    // 按 BFS 可达路径长度降序排列（优先保留可达且远的）
    List<Point> sorted = new ArrayList<>(candidates);
    sorted.sort(Comparator.comparingInt((Point candidate) ->
        reachablePathLength(currentPos, candidate, blocked, explored, width, height)).reversed());
    return sorted.subList(0, MAX_CANDIDATES_TO_SCORE);
}
```

当未探索格数量很大时（如地图 30×30 的早期阶段），对所有候选逐一计算加权 Dijkstra 路径的代价过高。裁剪策略：先对所有候选做一次轻量 BFS 路径长度计算（通过 `pathEstimator.planPath` 得到路径步数），保留路径最长（即最远）的 40 个候选进入精细评分，平衡性能与分配质量。

### 3.8 地图深度与内部区域判定 (`depthFromMapEdge`)

```java
private static int depthFromMapEdge(Point point, int width, int height) {
    int fromLeft = point.x();
    int fromRight = width - 1 - point.x();
    int fromTop = point.y();
    int fromBottom = height - 1 - point.y();
    return Math.min(Math.min(fromLeft, fromRight), Math.min(fromTop, fromBottom));
}
```

深度定义为格点到四条地图边界距离的**最小值**：
- `depth = 0`：位于地图边缘（至少一边 x/y 坐标为 0 或 width-1/height-1）
- `depth = 1`：距边缘 1 格
- `depth >= 2`：属于"内部区域"（触发 `MIN_INTERIOR_DEPTH_FOR_PATH_BONUS` 阈值）

判定为"内部"的格子（`depth >= 2`）在路径未探索收益和聚类价值计数中才被计入，避免把沿边的浅层格子误判为高价值目标。

### 3.9 回落机制 (`fallbackFarthestReachable`)

```java
private Optional<Point> fallbackFarthestReachable(Point currentPos, List<Point> candidates,
                                                   boolean[][] blocked, boolean[][] explored,
                                                   int width, int height) {
    Point bestTarget = null;
    int bestPathLength = -1;
    for (Point candidate : candidates) {
        List<Point> path = pathEstimator.planPath(currentPos, candidate, blocked, explored, width, height);
        if (path.isEmpty() || path.size() <= bestPathLength) continue;
        bestPathLength = path.size();
        bestTarget = candidate;
    }
    return Optional.ofNullable(bestTarget);
}
```

当精细评分无法选出任何目标时（所有候选得分均为 `Integer.MAX_VALUE` 即不可达），启用回落：从全部候选中选出 BFS 可达且路径最远的目标。这是最后的兜底机制，确保车辆在任何情况下都有目标可分配。

### 3.10 黑板读取汇总

TargetPlanner 在分配过程中需要从 Redis 黑板读取以下数据：

| 数据 | 读取方法 | 用途 |
|------|----------|------|
| 车辆位置 | `bb.getCarPosition(carId)` | 路径估算的起点 |
| 全局探索率 | `bb.getExplorationRate()` | 切换逐格/聚类模式的依据 |
| 探索位图 | `bb.loadExploredBitmap()` | 识别未探索区域、frontier 判断、路径规划权重 |
| 障碍物位图 | `bb.loadObstacleBitmap()` | 排除不可达格子、路径规划障碍 |
| 密封位图 | `bb.loadSealedBitmap()` | 排除被封锁格子 |
| 含车辆占据的阻塞图 | `bb.loadBlockedMapWithCars()` | 路径规划中的动态障碍物 |
| 活跃车辆 ID 列表 | `bb.discoverCarIds()` | 收集其他车辆路线用于重合避免 |
| 其他车辆路线 | `bb.getCarRoute(otherId)` | 路径重合评分 |
| 其他车辆目标 | `bb.getCarTarget(otherId)` | 防止多车朝同一目标移动 |
| 地图尺寸 | `bb.getMapWidth()` / `bb.getMapHeight()` | 位图遍历边界 |

### 3.11 黑板写入（唯一）

| 数据 | 写入方法 | 说明 |
|------|----------|------|
| 车辆目标 | `bb.setCarTarget(carId, target)` | **唯一黑板写入**。仅在分配成功后由 `TargetPlannerMain` 调用。不写入 Car Status。 |

---

## 4. TargetPathEstimator —— 路径估算器

**文件**：`TargetPathEstimator.java`（17 行）

**职责**：薄封装 `ExplorationWeightedPathFinder`，为 `GreedyTargetAllocator` 的评分函数提供路径估算。使用加权 Dijkstra 算法，优先穿越未探索区域。

```java
final class TargetPathEstimator {
    List<Point> planPath(Point start, Point target, boolean[][] blocked,
                          boolean[][] explored, int width, int height) {
        return ExplorationWeightedPathFinder.plan(
            start, target, blocked, explored, width, height,
            ExplorationWeightedPathFinder.SearchMode.WEIGHTED_DIJKSTRA);
    }
}
```

**关键特性**：
- 使用 `WEIGHTED_DIJKSTRA` 模式：已探索格权重高（穿越代价高）、未探索格权重低（穿越代价低），引导路径穿过未探索区域
- 路径仅用于**估算评分**，并非车辆实际行驶路线（实际寻路由 Navigator 模块完成）
- 若目标不可达则返回空列表，`scoreTargetCell` 会将其评分设为 `Integer.MAX_VALUE`

---

## 5. 完整分配流程示例

### 5.1 低探索率阶段（逐格模式）

```
探索率 = 42%（< 85%），3 辆车同时运行，tick=15

Controller → TargetPlannerCmd: ASSIGN_TARGET carId=CarA tick=15

handleAssignTarget:
  1. tick=15 != currentTick(-1) → allocatedTargets.clear(), currentTick=15
  2. bb.getCarPosition("CarA") → (12, 8)
  3. allocator.allocate("CarA", (12,8), bb, allocatedTargets):
     │
     ├─ 探索率 42% < 85% → allocateByCell()
     │
     ├─ collectFrontierFirstCandidates()
     │   → FrontierCellFinder 找到 67 个 frontier cell
     │
     ├─ candidates.removeAll(allocatedTargets)  → 67 个（本 tick 首次分配，空集合）
     │
     ├─ collectOverlapCells("CarA", bb, allocatedTargets):
     │   → CarB 路线: [(15,10), (15,9), (14,9), (14,8)...]   共 8 格
     │   → CarB 目标: (12,6)
     │   → CarC 路线: [(8,12), (8,11), (9,11)...]             共 6 格
     │   → overlapCells = {(15,10),(15,9),(14,9),...,(12,6),(8,12),...}  共 15 格
     │
     ├─ narrowCandidatePool → 67 > 40 → 按路径长度排序保留 40 个
     │
     ├─ 对 40 个候选逐一评分：
     │   candidate(14,10):
     │     path = [(12,8),(13,8),(13,9),(14,9),(14,10)]  → 5 步
     │     overlap = 1  (14,9 与 CarB 路线重合)
     │     unexploredOnPath = 2  (13,9 和 14,10 深度>=2 且未探)
     │     interiorDepth = 4  (min(14,15,10,19))
     │     clusterSize = 23  (连通未探内部格)
     │   得分 = 5 + 6*1 + 0 - 4*23 - 2*2 - 4*4 = 5+6-92-4-16 = -101
     │
     │   candidate(5,27):
     │     path = [...20 步...]
     │     overlap = 0
     │     unexploredOnPath = 8
     │     interiorDepth = 2
     │     clusterSize = 4
     │   得分 = 20 + 0 + 0 - 16 - 16 - 8 = -20
     │
     │   → (14,10) 得分 -101 优于 (5,27) 得分 -20 → 选 (14,10)
     │
     ├─ allocatedTargets.add((14,10))
     └─ return Optional.of((14,10))

  4. bb.setCarTarget("CarA", (14,10))
  5. sendSuccessReply → ControllerCmd: TARGET_ASSIGNED CarA success=true target={x:14,y:10}
```

### 5.2 高探索率阶段（聚类模式）

```
探索率 = 89%（>= 85%），1 个残留聚类，tick=87

Controller → TargetPlannerCmd: ASSIGN_TARGET carId=CarB tick=87

handleAssignTarget:
  1. tick=87 → allocatedTargets.clear(), currentTick=87
  2. bb.getCarPosition("CarB") → (22, 18)
  3. allocator.allocate("CarB", (22,18), bb, allocatedTargets):
     │
     ├─ 探索率 89% >= 85% → allocateByCluster()
     │
     ├─ UnexploredClusterFinder.findClusters():
     │   → 发现 Cluster#3: 位于 (24~28, 20~26)，共 15 个连通未探格
     │
     ├─ cluster.withoutAllocated(allocatedTargets) → Cluster#3（全未分配）
     │
     ├─ scoreCluster(Cluster#3):
     │   ├─ countInteriorCellsInCluster → 12 个内部格（depth >= 2）
     │   ├─ selectClusterEntryCandidates → 3 个 frontier cell:
     │   │   (24,20), (25,20), (24,21)
     │   │
     │   ├─ scoreTargetCell for (24,20):
     │   │   path = [(22,18),(23,18),(24,18),(24,19),(24,20)] → 5 步
     │   │   overlap = 0（CarA 已停在最终位置）
     │   │   unexploredOnPath = 3
     │   │   interiorDepth = 6  (min(24,5,20,9))
     │   │   clusterSize = 12
     │   │   得分 = 5 + 0 + 0 - 4*12 - 2*3 - 4*6 = 5 - 48 - 6 - 24 = -73
     │   │
     │   ├─ scoreTargetCell for (25,20): 得分 = -68
     │   └─ scoreTargetCell for (24,21): 得分 = -60
     │   → bestEntry = (24,20), bestEntryScore = -73
     │
     ├─ allocatedTargets.add((24,20))
     └─ return Optional.of((24,20))

  4. bb.setCarTarget("CarB", (24,20))
  5. sendSuccessReply → ControllerCmd: TARGET_ASSIGNED CarB success=true target={x:24,y:20}
```

---

## 6. 关键设计决策

### 6.1 只写 Target，不写 Status

TargetPlanner 模块**仅调用 `bb.setCarTarget(carId, target)`**，从不调用 `bb.setCarStatus()`。车辆状态管理（IDLE → WAITING_ROUTE → READY → MOVING 等）完全由 Controller 模块负责。这一职责分离保证了：

- Controller 作为唯一调度者能完整追踪每辆车的状态流转
- TargetPlanner 作为知识源始终保持无状态（stateless），不持有车辆状态
- 避免两个模块同时修改 Car Status 导致状态不一致

### 6.2 贪心而非全局最优

算法采用贪心策略：每辆车分配时仅考虑当前车辆的最优目标，不进行多车全局优化（如多车分配问题的匈牙利算法）。这是权衡的选择：

- 车辆目标分配是渐进式的——每次分配后地图状态会变化（其他车移动后路线改变）
- tick 级实时系统中，计算全局最优的代价过高且下一 tick 就可能过时
- 通过路径重合避免（`overlapCells`）和同 tick 去重（`alreadyAllocated`），贪心算法在实践中已能有效避免多车拥堵

### 6.3 探索率 = 85% 作为模式切换阈值

85% 阈值基于以下观察：
- 低于 85%：未探索区域仍然广阔且连片，frontier 推进是最优策略
- 高于 85%：未探索区域退化为零散斑块，以聚类为单位分配更高效——每个聚类由一辆车集中清扫，减少跨区域迁移的开销

### 6.4 路径估算用加权 Dijkstra 而非最短路径

目标分配阶段的路径仅用于评分对比，不需要是精确最短路径。使用加权 Dijkstra（`WEIGHTED_DIJKSTRA`）的优点是：
- 已探索格的边权高于未探索格，路径天然偏向穿越未探索区域
- 估算出的路径长度更接近实际探索路径（车辆实际行驶时也应优先走未探索格）
- 计算代价略高于 BFS 但远低于完整 A*，适合在评分循环中大量调用

---

## 7. 文件清单

| 文件 | 行数 | 核心职责 |
|------|------|----------|
| `TargetPlannerMain.java` | 146 | 入口：连接中间件、订阅 ASSIGN_TARGET、维护 tick 去重集合、回复 TARGET_ASSIGNED |
| `GreedyTargetAllocator.java` | 354 | 核心算法：双模式贪心分配（逐格/聚类）、多维评分函数、路径重合避免、候选池裁剪 |
| `TargetPathEstimator.java` | 17 | 路径估算：封装 ExplorationWeightedPathFinder，供评分函数使用 |

---

## 8. 依赖的外部类

| 类 | 包 | 用途 |
|------|-----|------|
| `FrontierCellFinder` | `com.substation.common.map` | 寻找前沿格子（已探区边界处未探格） |
| `UnexploredClusterFinder` | `com.substation.common.map` | 高探索率模式下发现未探索聚类 |
| `UnexploredCluster` | `com.substation.common.map` | 未探索聚类数据结构（格子集合 + 无已分配格过滤） |
| `ExplorationWeightedPathFinder` | `com.substation.common.map` | 加权 Dijkstra 路径规划（已探/未探区域权重不同） |
| `Point` | `com.substation.common.model` | 二维坐标 (x, y) |
| `BlackboardClient` | `com.substation.common.redis` | Redis 黑板读写客户端 |
| `MessageBus` | `com.substation.common.mq` | RabbitMQ 消息总线 |
| `MessageBuilder` | `com.substation.common.mq` | 构建标准化 MQ 消息 JSON |
| `MessageTypes` | `com.substation.common.mq` | 消息类型常量（ASSIGN_TARGET / TARGET_ASSIGNED） |
| `QueueNames` | `com.substation.common.mq` | 队列名称常量（TARGET_PLANNER_CMD / CONTROLLER_CMD） |

---

*文档结束。*
