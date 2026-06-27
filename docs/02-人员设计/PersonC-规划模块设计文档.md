# Person C 设计文档 —— 导航 / 目标规划 / 任务配置模块

> **对应源码位置**  
> - Navigator: `navigator/src/main/java/com/substation/navigator/`  
> - Target Planner: `target-planner/src/main/java/com/substation/targetplanner/`  
> - Task Configurator: `task-configurator/src/main/java/com/substation/taskconfigurator/`  
> - Common Map 工具类: `common/src/main/java/com/substation/common/map/`  
> - 动态障碍物: `common/src/main/java/com/substation/common/DynamicObstacleUtil.java`

---

## 1. Person C 职责概述

Person C 负责仿真系统中**与规划相关的三个核心模块**：

| 模块 | 核心职责 | 输入消息 | 输出消息 | MQ 队列 |
|------|----------|----------|----------|---------|
| **Navigator**（导航器） | 根据起点/终点为小车规划最终路径 | `PLAN_ROUTE` | `ROUTE_PLANNED` | `NavigatorCmd` -> `ControllerCmd` |
| **Target Planner**（目标规划器） | 为每一辆小车分配下一步探索目标 | `ASSIGN_TARGET` | `TARGET_ASSIGNED` | `TargetPlannerCmd` -> `ControllerCmd` |
| **Task Configurator**（任务配置器） | 接收前端配置，初始化仿真地图与车辆 | `FORWARD_CONFIG` / `FORWARD_RESET` | `TASK_READY` | `TaskConfigCmd` -> `ControllerCmd` |

三个模块均采用**独立进程 + RabbitMQ 消息驱动**的架构，与 Controller / Car Agent 等其他模块解耦，通过 Redis 黑板（BlackboardClient）读写共享状态。

此外，`common/map/` 包下的**5 个地图工具类**是 Person C 模块的核心基础设施：`ExplorationWeightedPathFinder`、`FrontierCellFinder`、`ReachabilityAnalyzer`、`UnexploredClusterFinder`、`SpawnPositionSelector`，以及 `DynamicObstacleUtil` 动态障碍物工具。

---

## 2. Navigator 详细设计（`com.substation.navigator`）

> 文件清单：`NavigatorMain.java`, `PathPlanner.java`, `PathPlannerFactory.java`, `BfsPathFinder.java`, `AStarPathFinder.java`

### 2.1 架构概览

```
控制器 ---PLAN_ROUTE{carId,algorithm}---> NavigatorCmd 队列
                                                    |
                                           NavigatorMain.subscribe
                                                    |
                                         1. 取 carId 的 Position + Target (Redis)
                                         2. PathPlannerFactory.create(algorithm)
                                         3. planner.plan(start, target, bb)
                                         4. 写前复查位置（防止规划期间车已移动）
                                         5. bb.pushRoute(carId, route) —— LPUSH 到 CarID:RouteList
                                         6. 发送 ROUTE_PLANNED{routeFound, routeLength} -> ControllerCmd
```

### 2.2 PathPlanner 接口（策略模式）

```java
// 文件: PathPlanner.java
@FunctionalInterface
public interface PathPlanner {
    List<Point> plan(Point start, Point target, BlackboardClient bb);
}
```

所有路径搜索算法实现此接口。返回值：
- `List<Point>`：路径点列表，**不含 start，含 target**，按从起点到终点的顺序
- 空列表：无可用路径

### 2.3 PathPlannerFactory —— 算法工厂

```java
// 文件: PathPlannerFactory.java
static PathPlanner create(String algorithmName) {
    try {
        return create(AlgorithmType.valueOf(algorithmName.toUpperCase()));
    } catch (IllegalArgumentException e) {
        return new BfsPathFinder();  // 容错降级
    }
}
```

支持两种算法类型 (`AlgorithmType` 枚举)：

| 枚举值 | 对应实现类 | 使用的搜索模式 |
|--------|-----------|----------------|
| `BFS` | `BfsPathFinder` | `WEIGHTED_DIJKSTRA` |
| `ASTAR` | `AStarPathFinder` | `WEIGHTED_ASTAR` |

默认算法为 `BFS`（容错降级策略）。

### 2.4 BfsPathFinder —— 加权 Dijkstra

```java
// 文件: BfsPathFinder.java
List<Point> plan(Point start, Point target, BlackboardClient bb) {
    int width = bb.getMapWidth();
    int height = bb.getMapHeight();
    // 越界检查
    if (isOutOfBounds(target, width, height)) return List.of();
    // 加载障碍物（含车辆占位）+ 已探索位图
    boolean[][] blocked = bb.loadBlockedMapWithCars();
    boolean[][] explored = bb.loadExploredBitmap();
    // 委托给统一加权搜索器，模式 = WEIGHTED_DIJKSTRA
    return ExplorationWeightedPathFinder.plan(
        start, target, blocked, explored, width, height,
        ExplorationWeightedPathFinder.SearchMode.WEIGHTED_DIJKSTRA);
}
```

**算法特性：**
- 基于 Dijkstra 的加权最短路径搜索（不使用启发式函数 `h(n)`）
- **已探索格子代价 = 5**，**未探索格子代价 = 1**（详见 `ExplorationPathCosts`）
- 效果：路径倾向于穿过未探索区域，避免沿已知走廊空跑
- 使用优先队列 + 最小代价表，保证找到的是加权意义下的最优解

### 2.5 AStarPathFinder —— 加权 A*

```java
// 文件: AStarPathFinder.java
List<Point> plan(Point start, Point target, BlackboardClient bb) {
    // 结构同 BfsPathFinder，仅 SearchMode 改为 WEIGHTED_ASTAR
    return ExplorationWeightedPathFinder.plan(
        start, target, blocked, explored, width, height,
        ExplorationWeightedPathFinder.SearchMode.WEIGHTED_ASTAR);
}
```

**与 BFS 的区别：**
- 使用**曼哈顿距离 `|dx| + |dy|`** 作为启发式函数 `h(n)`
- 优先队列排序键 = `g(n) + h(n)`（而非纯 `g(n)`）
- 在有目标的路径搜索中通常更快收敛（因为有启发式引导方向）
- 代价模型与 BFS 完全相同（已探索=5，未探索=1）

### 2.6 路由写入 Redis

路径写入采用 Lua 脚本原子操作：

```
redis.call('DEL', KEYS[1])        // 清除旧路径
for i=1,#ARGV do
    redis.call('LPUSH', KEYS[1], ARGV[i])  // 逆序 LPUSH，保证 LPOP 弹出正确的第一步
end
```

Redis Key 格式：`CarID:RouteList`（List 类型）。路径点以 JSON 字符串存储，`Point` 支持 `toJson()/fromJson()` 互转。

### 2.7 位置复查（一致性保证）

```java
// NavigatorMain.handlePlanRoute() 片段
Point nowPos = bb.getCarPosition(carId).orElse(null);
if (nowPos == null || !nowPos.equals(start)) {
    // 规划期间车已移动，放弃本次结果，返回 routeFound=false
    sendRoutePlanned(messageBus, carId, false, 0, tick);
    return;
}
```

在路径计算完成后、写入 Redis 之前重新检查车辆位置是否与规划起点一致，防止并发情况下路径与车辆实际位置脱节。

---

## 3. Target Planner 详细设计（`com.substation.targetplanner`）

> 文件清单：`TargetPlannerMain.java`, `GreedyTargetAllocator.java`, `TargetPathEstimator.java`

### 3.1 架构概览

```
控制器 ---ASSIGN_TARGET{carId}---> TargetPlannerCmd 队列
                                              |
                                     TargetPlannerMain.subscribe
                                              |
                              1. 同一 tick 首次调用时清空 allocatedTargets 集合
                              2. 取 carId 的 Position (Redis)
                              3. allocator.allocate(carId, pos, bb, allocatedTargets)
                              4. 分配成功 → bb.setCarTarget(carId, target) + TARGET_ASSIGNED{success:true}
                              5. 分配失败 → TARGET_ASSIGNED{success:false}
                              （注意：不写 CarID:Status，由 Controller 负责）
```

### 3.2 重复分配防护

```java
// TargetPlannerMain 成员字段
private final Set<Point> allocatedTargets = new HashSet<>();
private int currentTick = -1;

// handleAssignTarget() 中
if (tick != currentTick) {
    allocatedTargets.clear();   // 新 tick，清空已分配集合
    currentTick = tick;
}
```

- 同一 tick 内多车分配时，后分配的车不会分到已被占用的格子
- tick 切换时自动清空，保证每 tick 的分配相互独立

### 3.3 GreedyTargetAllocator —— 贪心目标分配器

#### 3.3.1 双模式分配策略

```java
Optional<Point> allocate(String carId, Point currentPosition,
                         BlackboardClient bb, Set<Point> alreadyAllocated) {
    if (bb.getExplorationRate() >= CLUSTER_MODE_RATE_THRESHOLD) {  // 85%
        return allocateByCluster(carId, currentPosition, bb, alreadyAllocated);
    }
    return allocateByCell(carId, currentPosition, bb, alreadyAllocated);
}
```

| 模式 | 触发条件 | 策略描述 |
|------|----------|----------|
| **逐格模式** (`allocateByCell`) | 探索率 < 85% | 前沿优先（Frontier-First）的单格评分，配合路径重叠惩罚 |
| **区域块模式** (`allocateByCluster`) | 探索率 >= 85% | 按 4-连通未探索区域块分配，每个区域块选最优入口格 |

#### 3.3.2 逐格模式（探索率 < 85%）

**候选集生成：**
1. 调用 `FrontierCellFinder.findFrontierCells()` 获取前沿格（与已探索区相邻的未探索格）
2. 若前沿格为空（地图尚未探索），退化为所有未探索 + 非障碍 + 非密封格
3. 排除已分配格、当前车所在格

**候选池收窄（`narrowCandidatePool`）：**
- 若候选数 > `MAX_CANDIDATES_TO_SCORE`（40），按 BFS 可达距离倒序排序后取前 40 个
- 减少评分计算量

**评分函数 `scoreTargetCell`：**

```
score = path.size()                          // 路径长度（越小越好）
       + 6 × overlap                         // 路径与其他车路线重合的格子数
       + (深度≤1 ? 20 : 0)                   // 贴边目标额外惩罚
       - 4 × clusterSize                     // 候选格附近连通未探索区域大小（越大越优先）
       - 2 × unexploredOnPath                // 路径上距边缘≥2的内部未探索格数
       - 4 × interiorDepth                   // 目标距地图边缘最小深度
```

score **越低越好**，取最优候选格作为本次分配目标。

**冲突逃避（`collectOverlapCells`）：**
- 收集所有其他车的路径格子 + 目标格 → `overlapCells` 集合
- 候选格的路径与 `overlapCells` 每重合 1 格，加惩罚 6 分
- 有效分散多辆车，避免挤在同一走廊

**保底策略（`fallbackFarthestReachable`）：**
- 若所有候选评分后无有效目标，退化为选 BFS 可达距离最远的候选格

#### 3.3.3 区域块模式（探索率 >= 85%）

在探索后期，剩余未探索区域呈岛屿状分布，逐个格子评分效率低下。此时：

1. 调用 `UnexploredClusterFinder.findClusters()` 找出所有 4-连通未探索区域块
2. 对每个区域块，选出其前沿格子作为入口候选（`selectClusterEntryCandidates`）
3. 对每个入口候选格，调用 `scoreTargetCell` 评分
4. 增加**区域块有价值大小**奖励 `valuableClusterSize`：统计距边缘>=2 的内部格子数（排除已探索/障碍/密封）
5. 取总分最低的区域块入口格作为目标

**区域块去重（`withoutAllocated`）：**
- 区域块中被当前 tick 已分配格占用的部分会被剔除
- 若剔除后区域块为空，跳过该块

#### 3.3.4 关键常量

| 常量 | 值 | 含义 |
|------|-----|------|
| `CLUSTER_MODE_RATE_THRESHOLD` | 85 | 启用区域块模式的探索率阈值 |
| `OVERLAP_PENALTY` | 6 | 路径与他人路线每重合一格的惩罚 |
| `CLUSTER_SIZE_WEIGHT` | 4 | 区域块大小奖励权重 |
| `UNEXPLORED_PATH_WEIGHT` | 2 | 路径上未探索格奖励权重 |
| `INTERIOR_DEPTH_WEIGHT` | 4 | 目标纵深奖励权重 |
| `PERIMETER_TARGET_PENALTY` | 20 | 贴边目标惩罚 |
| `MIN_INTERIOR_DEPTH_FOR_PATH_BONUS` | 2 | 计入路径奖励的最小边缘深度 |
| `MAX_CANDIDATES_TO_SCORE` | 40 | 精细评分前保留的最大候选数 |

### 3.4 TargetPathEstimator —— 路径估算器

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

目标评分时需要估算路径长度和重合度，统一使用 `WEIGHTED_DIJKSTRA` 模式（不使用 `WEIGHTED_ASTAR`），保证评分的一致性和可比性。

---

## 4. Task Configurator 详细设计（`com.substation.taskconfigurator`）

> 文件清单：`TaskConfiguratorMain.java`, `TaskInitializer.java`

### 4.1 消息处理

```java
// TaskConfiguratorMain.subscribe 回调
if (MessageTypes.FORWARD_CONFIG.equals(type)) {
    handleConfig(initializer, data, tick);
} else if (MessageTypes.FORWARD_RESET.equals(type)) {
    handleReset(tick);
}
```

| 消息类型 | 处理方式 | 输出 |
|----------|----------|------|
| `FORWARD_CONFIG` | 选择性清空 + 完整初始化 | `TASK_READY` |
| `FORWARD_RESET` | 选择性清空 + 清除仿真元数据 | 无输出消息 |

### 4.2 选择性清空（`selectiveClear`）

```java
private void selectiveClear() {
    bb.clearSimulationState();
}
```

清除的 Redis Key：
- `mapView`（探索位图）
- `mapBlock`（障碍物位图）
- `mapSealed`（密封区位图）
- `mapHeat`（热力图）
- `TaskConfig`（任务配置 Hash）
- `explorationEvents`（探索事件列表）
- 匹配 `Car*:*` 的所有 Key（车辆位置/目标/路径/状态/步数等）
- 匹配 `pos:reserve:*` 的所有 Key（位置预约锁）
- 匹配 `lock:*` 的所有 Key（分布式锁）

**不清除的 Key：** `auth:session:*` 等用户会话数据，保证重置后用户无需重新登录。

### 4.3 TaskInitializer 完整初始化流程

```java
void initialize(BlackboardClient bb, Map<String, Object> config) {
    // Step 1: 解析参数（提供默认值）
    // Step 2: 写 Redis TaskConfig
    // Step 3: 随机放置障碍物
    // Step 4: 分配初始位置
    // Step 5: 注册小车（Position/Status=IDLE/Steps=0）
    // Step 6: 照亮初始 3×3 区域
    // Step 7: 标记密封不可达格子
}
```

#### 4.3.1 参数解析

| 参数 Key | 类型 | 默认值 | 说明 |
|----------|------|--------|------|
| `mapWidth` | int | 30 | 地图宽度（格子数） |
| `mapHeight` | int | 30 | 地图高度（格子数） |
| `carCount` | int | 5 | 小车数量 |
| `obstacleRatio` | double | 0.15 | 障碍物比例 |
| `algorithm` | String | "BFS" | 路径规划算法 |
| `tickInterval` | int | 500 | tick 间隔（ms） |

支持 Number 和 String 两种类型的参数值自动转换。

#### 4.3.2 障碍物随机放置

```
算法：
  interiorCells = (width - 2) × (height - 2)    // 边缘保留 1 格不放置
  targetCount = interiorCells × obstacleRatio
  maxAttempts = targetCount × 10                  // 放 10 倍尝试次数
  for attempt in 1..maxAttempts:
      x = random([1, width-2]), y = random([1, height-2])
      if (x,y) 未被占用且非障碍: 标记为障碍, placed++
      if placed >= targetCount: break
```

边缘 1 格宽的缓冲区不放置障碍物，保证车辆始终有外围通道可达。

#### 4.3.3 初始位置分配

**两种策略：**

| 车数 | 策略 | 具体方法 |
|------|------|----------|
| <= 5 | 固定布局 | 四角 + 中心：`(1,1)`, `(w-2,1)`, `(1,h-2)`, `(w-2,h-2)`, `(w/2, h/2)` |
| > 5 | 加权选择 | 调用 `SpawnPositionSelector.selectBest()` |

**固定布局容错：**
- 若首选位置被障碍物占据或已被分配，调用 `SpawnPositionSelector` 寻找备选出生点
- 最终保证每辆车都有合法位置

**加权选择评分：**
- 附近未探索格子数 × 10（半径 4 邻域内）
- 到最近已分配车辆的距离 × 5（曼哈顿距离）
- 取最高分候选格，同分时随机选择

#### 4.3.4 小车初始化

```java
void initSingleCar(BlackboardClient bb, String carId, Point position) {
    bb.setCarPosition(carId, position);       // CarID:Position Hash {x, y}
    bb.setCarStatus(carId, CarStatus.IDLE);   // CarID:Status → "IDLE"
    bb.setCarSteps(carId, 0);                 // CarID:Steps → "0"
    bb.setCarEffectiveSteps(carId, 0);        // CarID:EffectiveSteps → "0"
    bb.appendCarHistory(carId, position, 0);  // CarID:History RPUSH {x, y, tick:0}
}
```

小车 ID 命名规则：`Car001`, `Car002`, …, `CarNNN`（三位零填充）。

#### 4.3.5 初始区域照亮

```java
void lightUpArea(BlackboardClient bb, Point center, int mapWidth, int mapHeight) {
    bb.recordExploration(0, center.y(), center.x());  // tick=0 照亮当前格
}
```

每辆车的初始位置立即标记为已探索。`recordExploration` 内部调用 `SETBIT mapView`，若格子此前未被探索则追加到 `explorationEvents` 列表（回放用）。

#### 4.3.6 密封不可达格子标记

```java
void markSealedUnreachableCells(...) {
    boolean[][] sealed = ReachabilityAnalyzer.findSealedFreeCells(obstacles, carStartPositions);
    bb.writeSealedBitmap(sealed, mapWidth);
}
```

**目的：** 障碍物可能形成封闭区域，小车永远无法进入这些区域。将这些格子标记为 `mapSealed`，使：
- 探索率计算时排除密封区（分母只算可达格子）
- Target Planner 不会将密封格子作为候选目标
- Navigator 的路径搜索不计入密封区

### 4.4 仿真运行记录

```java
private void recordRunStarter(Map<String, Object> config) {
    Object operator = config.get("operator");
    String startedBy = operator == null ? null : String.valueOf(operator);
    bb.beginSimRun(startedBy);
}
```

记录操作者（`sim:run:startedBy`）和开始时间戳（`sim:run:startedAt`），供回放归档使用。同时清除旧的归档标记（`sim:run:archived`）。

---

## 5. Common Map 工具类

> 所有工具类位于 `common/src/main/java/com/substation/common/map/`，均为 `public final` 不可实例化工具类，所有方法均为 `static`。

### 5.1 ExplorationWeightedPathFinder —— 加权路径搜索

```
文件: ExplorationWeightedPathFinder.java
功能: 统一的加权路径搜索引擎，同时支持 Dijkstra 和 A* 模式
```

**搜索模式枚举：**

```java
public enum SearchMode {
    WEIGHTED_DIJKSTRA,   // 纯代价优先（无启发函数）
    WEIGHTED_ASTAR       // g(n) + Manhattan(target)
}
```

**核心算法（统一实现）：**
```
初始化:
  minCost[start] = 0
  openSet = PriorityQueue(按 mode 计算优先级)
  遍历四个方向邻居:
    stepCost = explored[neighbor] ? 5 : 1      // 已探索代价 5，未探索代价 1
    newCost = minCost[current] + stepCost
    若 newCost < minCost[neighbor]: 更新并加入 openSet
  到达 target → 回溯 parent 重建路径
```

**SearchNode 优先级计算：**
```java
int priority(SearchMode mode, Point target) {
    if (mode == WEIGHTED_ASTAR) {
        return gCost + manhattan(new Point(col, row), target);
    }
    return gCost;  // 纯 Dijkstra
}
```

**关键细节：**
- 四个方向移动：上/下/左/右（不走斜角）
- 起点与终点相同时返回空列表
- 起点不在返回路径中，终点在其中
- 无路径时返回空列表（而非 null）
- 使用 `Collections.unmodifiableList` 返回不可变路径

### 5.2 ExplorationPathCosts —— 路径代价常量

```
文件: ExplorationPathCosts.java
```

| 常量 | 值 | 含义 |
|------|-----|------|
| `UNEXPLORED_STEP_COST` | 1 | 经过未探索格子的代价 |
| `EXPLORED_STEP_COST` | 5 | 经过已探索格子的代价 |

差别化的代价设计使得路径规划**优先穿过未探索区域**，减少在已探明走廊中的无效移动。

### 5.3 FrontierCellFinder —— 前沿格检测

```
文件: FrontierCellFinder.java
功能: 查找与已探索区域相邻的未探索格子
```

**前沿格定义：**
- 自身**未探索** 且 **非障碍** 且 **非密封**
- **至少有一个四方向邻居已探索**

**公共 API：**

```java
// 全图扫描，返回所有前沿格列表
static List<Point> findFrontierCells(boolean[][] explored, boolean[][] obstacles,
                                     boolean[][] sealed, int width, int height);

// 判断单格是否为前沿格
static boolean isFrontier(int col, int row, boolean[][] explored, boolean[][] obstacles,
                          boolean[][] sealed, int width, int height);
```

**在 Target Planner 中的使用：**
- 逐格模式首选的候选集（前沿格优先，无前沿时才退化为所有未探索格）
- 区域块模式中选择块内前沿格作为入口候选（`selectClusterEntryCandidates`）

### 5.4 ReachabilityAnalyzer —— 可达性分析

```
文件: ReachabilityAnalyzer.java
功能: 标记被障碍物包围、小车从起点无法到达的格子
```

**算法：**
```
1. floodFillReachable: 从所有 carStartPositions 出发，BFS 洪水填充所有可达格子
2. markSealedFreeCells: 遍历全图，非障碍 + 不可达 → sealed[row][col] = true
```

**在 TaskInitializer 中的使用：**
初始化末尾调用 `ReachabilityAnalyzer.findSealedFreeCells(obstacles, carStartPositions)`，结果写入 `mapSealed` 位图。

### 5.5 UnexploredClusterFinder —— 未探索区域块划分

```
文件: UnexploredClusterFinder.java
功能: 将剩余未探索格子按四邻接连通性划分为若干区域块
```

**算法：**
- 全图扫描，遇到未探索 + 非障碍 + 非密封的格子且未被访问过，即启动 BFS 洪泛
- BFS 沿四方向扩展，收集所有连通的未探索格子为一个 `UnexploredCluster`
- 返回所有区域块列表

**UnexploredCluster 数据结构：**
```java
public record UnexploredCluster(List<Point> cells) {
    public int cellCount();                              // 总格子数
    public Optional<UnexploredCluster> withoutAllocated(Set<Point> allocated);  // 去重
}
```

### 5.6 SpawnPositionSelector —— 出生点选择器

```
文件: SpawnPositionSelector.java
功能: 为多车场景选择最优出生位置
```

**评分模型：**
```
score = 周围未探索格数(半径4) × 10 + 到最近已分配车的曼哈顿距离 × 5
```

- 候选范围：边缘 margin 以内的所有可通行非占用格子
- 取最高分候选，同分随机选择
- 无候选时返回 `Optional.empty()`

**使用场景：**
- TaskInitializer 中 >5 辆车的出生点分配
- TaskInitializer 中 <=5 辆车时，首选位置不可用时的备选

### 5.7 ShortestHopPathFinder —— 最短跳数路径

```
文件: ShortestHopPathFinder.java (common/map/)
```

BFS 的最短跳数路径计算（未加权，每步代价均为 1），与 `ExplorationWeightedPathFinder` 互补，适用于需要纯距离评估的场景。

### 5.8 DynamicObstacleUtil —— 动态障碍物

```
文件: DynamicObstacleUtil.java (common/)
功能: 每 N 拍随机增删障碍物，制造动态环境
```

**变更规则：**
- 每次最多新增 **2 个**障碍物，最多移除 **2 个**障碍物
- 新增位置不能覆盖小车当前位置（`carPositions` 集合）
- 新增尝试最多 50 次随机采样

**调用方式：** 由 Controller 每 N 拍调用，传入 `BlackboardClient` 和 `carPositions`。

---

## 6. 关键代码片段

### 6.1 Navigator 消息监听与路径规划主流程

> 来源：`NavigatorMain.java` 第 53-77 行

```java
messageBus.subscribe(QueueNames.NAVIGATOR_CMD, rawMessage -> {
    JSONObject msg = JSONObject.parseObject(rawMessage);
    String type = msg.getString("type");
    int tick = msg.getIntValue("tick", 0);

    if (!MessageTypes.PLAN_ROUTE.equals(type)) {
        log.warn("[Navigator] 未知消息类型: {}", type);
        return;
    }

    String carId = msg.getString("carId");
    JSONObject data = msg.getJSONObject("data");
    String algorithm = extractAlgorithm(data);
    boolean supervised = data != null && data.getBooleanValue("supervised", false);

    log.info("[Navigator] 收到 PLAN_ROUTE carId={} algorithm={} tick={}{}",
        carId, algorithm, tick, supervised ? " 经过监督器优化" : "");

    try {
        handlePlanRoute(bb, messageBus, carId, algorithm, supervised, tick);
    } catch (Exception e) {
        log.error("[Navigator] 规划失败 carId={}", carId, e);
        sendRoutePlanned(messageBus, carId, false, 0, tick);
    }
});
```

### 6.2 贪心目标分配器——逐格模式评分

> 来源：`GreedyTargetAllocator.java` 第 106-123 行

```java
private int scoreTargetCell(Point currentPos, Point target, int clusterSize,
                           boolean[][] blocked, boolean[][] explored,
                           int width, int height, Set<Point> overlapCells) {
    List<Point> path = pathEstimator.planPath(
        currentPos, target, blocked, explored, width, height);
    if (path.isEmpty() && !currentPos.equals(target)) {
        return Integer.MAX_VALUE;  // 不可达
    }
    int overlap = countSharedCells(path, overlapCells);
    int unexploredOnPath = countInteriorUnexploredOnPath(path, explored, width, height);
    int interiorDepth = depthFromMapEdge(target, width, height);
    int perimeterPenalty = interiorDepth <= 1 ? PERIMETER_TARGET_PENALTY : 0;
    return path.size()
        + OVERLAP_PENALTY * overlap
        + perimeterPenalty
        - CLUSTER_SIZE_WEIGHT * clusterSize
        - UNEXPLORED_PATH_WEIGHT * unexploredOnPath
        - INTERIOR_DEPTH_WEIGHT * interiorDepth;
}
```

### 6.3 TaskInitializer 完整初始化

> 来源：`TaskInitializer.java` 第 39-58 行

```java
void initialize(BlackboardClient bb, Map<String, Object> config) {
    int mapWidth = parseIntParam(config, KEY_MAP_WIDTH, DEFAULT_MAP_WIDTH);
    int mapHeight = parseIntParam(config, KEY_MAP_HEIGHT, DEFAULT_MAP_HEIGHT);
    int carCount = parseIntParam(config, KEY_CAR_COUNT, DEFAULT_CAR_COUNT);
    double obstacleRatio = parseDoubleParam(config, KEY_OBSTACLE_RATIO, DEFAULT_OBSTACLE_RATIO);
    String algorithm = parseStringParam(config, KEY_ALGORITHM, DEFAULT_ALGORITHM);
    int tickInterval = parseIntParam(config, KEY_TICK_INTERVAL, DEFAULT_TICK_INTERVAL);

    writeTaskConfig(bb, mapWidth, mapHeight, carCount, obstacleRatio, algorithm, tickInterval);

    List<String> carIds = generateCarIds(carCount);
    boolean[][] obstacles = placeObstacles(bb, mapWidth, mapHeight, obstacleRatio, Set.of());
    List<Point> initialPositions = assignInitialPositions(carCount, mapWidth, mapHeight, obstacles);

    for (int i = 0; i < carIds.size(); i++) {
        initSingleCar(bb, carIds.get(i), initialPositions.get(i));
        lightUpArea(bb, initialPositions.get(i), mapWidth, mapHeight);
    }

    markSealedUnreachableCells(bb, mapWidth, mapHeight, initialPositions, obstacles);
}
```

### 6.4 加权路径搜索核心循环

> 来源：`ExplorationWeightedPathFinder.java` 第 27-58 行

```java
public static List<Point> plan(Point start, Point target, boolean[][] blocked,
                               boolean[][] explored, int width, int height,
                               SearchMode mode) {
    if (isOutOfBounds(target, width, height) || start.equals(target)) {
        return List.of();
    }

    int[][] minCost = new int[height][width];
    for (int[] row : minCost) { Arrays.fill(row, Integer.MAX_VALUE); }
    Point[][] parent = new Point[height][width];
    PriorityQueue<SearchNode> openSet = new PriorityQueue<>(
        Comparator.comparingInt(node -> node.priority(mode, target)));

    minCost[start.y()][start.x()] = 0;
    openSet.add(new SearchNode(start.x(), start.y(), 0));

    while (!openSet.isEmpty()) {
        SearchNode node = openSet.poll();
        if (node.gCost() > minCost[node.row()][node.col()]) continue;

        Point current = new Point(node.col(), node.row());
        if (current.equals(target)) {
            return reconstructPath(parent, start, target);
        }
        expandNeighbors(current, blocked, explored, minCost, parent, openSet, mode, width, height);
    }
    return List.of();
}
```

### 6.5 前沿格检测逻辑

> 来源：`FrontierCellFinder.java` 第 32-35 + 第 38-56 行

```java
public static boolean isFrontier(int col, int row, boolean[][] explored,
                                  boolean[][] obstacles, boolean[][] sealed,
                                  int width, int height) {
    return isFrontierCandidate(col, row, explored, obstacles, sealed)
        && hasExploredNeighbor(col, row, explored, obstacles, width, height);
}

private static boolean isFrontierCandidate(int col, int row, boolean[][] explored,
                                           boolean[][] obstacles, boolean[][] sealed) {
    return !explored[row][col] && !obstacles[row][col] && !sealed[row][col];
}

private static boolean hasExploredNeighbor(int col, int row, boolean[][] explored,
                                           boolean[][] obstacles, int width, int height) {
    for (int[] direction : DIRECTIONS) {
        int nextX = col + direction[0];
        int nextY = row + direction[1];
        if (!isInBounds(nextX, nextY, width, height) || obstacles[nextY][nextX])
            continue;
        if (explored[nextY][nextX]) return true;
    }
    return false;
}
```

### 6.6 密封不可达格子分析

> 来源：`ReachabilityAnalyzer.java` 第 24-29 + 第 32-50 行

```java
public static boolean[][] findSealedFreeCells(boolean[][] obstacles,
                                             List<Point> startPoints) {
    int height = obstacles.length;
    int width = obstacles[0].length;
    boolean[][] reachable = floodFillReachable(obstacles, startPoints, width, height);
    return markSealedFreeCells(obstacles, reachable, width, height);
}

private static boolean[][] floodFillReachable(boolean[][] obstacles,
                                             List<Point> startPoints,
                                             int width, int height) {
    boolean[][] reachable = new boolean[height][width];
    Queue<int[]> queue = new ArrayDeque<>();
    for (Point start : startPoints) {
        enqueueIfWalkable(start.x(), start.y(), obstacles, reachable, queue);
    }
    while (!queue.isEmpty()) {
        int[] cell = queue.poll();
        for (int[] delta : CARDINAL_DELTAS) {
            enqueueIfWalkable(cell[0] + delta[0], cell[1] + delta[1],
                obstacles, reachable, queue);
        }
    }
    return reachable;
}
```

---

## 7. 模块间交互时序

### 7.1 初始化流程

```
Frontend → SET_CONFIG → Controller → FORWARD_CONFIG → TaskConfigurator
                                                            |
                                                  1. selectiveClear()
                                                  2. TaskInitializer.initialize()
                                                  3. beginSimRun()
                                                  4. TASK_READY → Controller → REFRESH_ALL → Frontend
```

### 7.2 每 tick 规划流程

```
Controller (每 tick)
    │
    ├─→ TargetPlanner: ASSIGN_TARGET(carId)
    │       ├─ allocate() → setCarTarget()
    │       └─→ TARGET_ASSIGNED(carId, success) → Controller
    │
    ├─→ Navigator: PLAN_ROUTE(carId, algorithm)
    │       ├─ planner.plan(start, target) → pushRoute()
    │       └─→ ROUTE_PLANNED(carId, routeFound, routeLength) → Controller
    │
    └─→ Car: TICK_MOVE → Car 执行移动 → MOVED → Controller
```

### 7.3 重置流程

```
Frontend → RESET → Controller → FORWARD_RESET → TaskConfigurator
                                                    |
                                          1. selectiveClear()
                                          2. clearSimRunMetadata()
```

---

## 8. Redis Key 汇总（Person C 操作）

| Key 模式 | 类型 | 写入方 | 读取方 |
|----------|------|--------|--------|
| `TaskConfig` | Hash | TaskConfigurator | 所有模块 |
| `mapBlock` | Bitmap (String) | TaskConfigurator, DynamicObstacleUtil | Navigator, TargetPlanner |
| `mapSealed` | Bitmap (String) | TaskConfigurator | Navigator, TargetPlanner |
| `mapView` | Bitmap (String) | TaskConfigurator, CarAgent | Navigator, TargetPlanner |
| `CarID:Position` | Hash `{x, y}` | TaskConfigurator, CarAgent | Navigator, TargetPlanner |
| `CarID:Target` | Hash `{x, y}` | TargetPlanner | Navigator |
| `CarID:RouteList` | List (JSON Point) | Navigator | CarAgent |
| `CarID:Status` | String | TaskConfigurator, Controller | All |
| `CarID:Steps` | String | TaskConfigurator, CarAgent | All |
| `CarID:EffectiveSteps` | String | TaskConfigurator, CarAgent | All |
| `CarID:History` | List (JSON) | TaskConfigurator, CarAgent | Replay |
| `mapHeat` | Hash `{row,col → count}` | TaskConfigurator (incr) | Frontend |
| `explorationEvents` | List | BlackboardClient.recordExploration() | Replay |
| `sim:run:startedAt` | String | TaskConfigurator | Replay |
| `sim:run:startedBy` | String | TaskConfigurator | Replay |

---

## 9. 设计要点总结

1. **加权代价模型**是 Person C 模块区别于传统寻路系统的核心创新：已探索区域步长代价为未探索的 5 倍，使得无论是 Dijkstra 还是 A*，路径都会自然偏向未探索区域，实现隐式的探索导向。

2. **双阶段目标分配**（逐格模式 → 区域块模式）适应探索进程的不同阶段：前期探索区域广阔时逐格评分，后期剩余区域碎片化时按区域块分配，避免对孤立格子逐一评分的浪费。

3. **路径重叠惩罚**是多车协同的关键机制：后分配的车在评分时会考虑已分配车的路径和目标，每重合 1 格罚 6 分，自然地分散车辆走向。

4. **位置复查**防止并发竞态：Navigator 在路径计算完成后再次确认车辆未移动，避免写入与实际位置脱节的路径。

5. **选择性清空**而非 FLUSHDB，保证重置操作不会清除用户登录会话，提升多用户并发体验。

6. **所有地图工具类均无状态**（`static` 方法 + `private` 构造器），数据通过参数传入传出，天然线程安全，可被多个模块并发调用。

---

## 10. Unity 3D 可视化模块

> **对应源码位置**
> - Unity WebGL 宿主页: `display/src/main/resources/web/unity/index.html`
> - WebSocket 地址重定向: `display/src/main/resources/web/unity/ws-redirect.js`
> - 2D/3D 视图切换桥接: `display/src/main/resources/web/js/unity-view.js`
> - 页面布局 iframe 集成: `display/src/main/resources/web/index.html`
> - CSS 样式: `display/src/main/resources/web/css/style.css`（`.unity-shell` / `.unity-frame` / `.unity-input-layer`）
> - Build 产物: `display/src/main/resources/web/unity/Build/`（`unity.data`, `unity.wasm`, `unity.framework.js`, `unity.loader.js`）
> - TemplateData: `display/src/main/resources/web/unity/TemplateData/`（`style.css`, `favicon.ico`, 进度条/Logo 图片等）

### 10.1 概述

Person C 负责开发了变电站巡检仿真系统的 **Unity 3D 可视化子系统**，与 Person A（Controller）和 Person D（Display/WebSocket）协同工作。该子系统提供了一个基于 Unity WebGL 的三维变电站场景，用户可在浏览器中实时观察小车巡检过程，并与传统的 Canvas 2D 地图视图无缝切换。

Unity 3D 模块采用 **iframe 嵌入 + JavaScript 桥接** 的架构，将 Unity WebGL 构建产物作为独立资源部署，通过 `postMessage`/`SendMessage` 机制与宿主页面通信，共享主应用的 WebSocket 连接获取实时仿真数据。

整体架构如下：

```
┌─ index.html（宿主页面）─────────────────────────────────────┐
│  ┌─ Canvas 2D Map ──┐  ┌─ Unity Shell ───────────────┐    │
│  │  #map-canvas      │  │  <iframe id="unity-frame"> │    │
│  │  #car-canvas      │  │    src="unity/index.html"  │    │
│  └───────────────────┘  │    ┌─────────────────────┐ │    │
│                          │    │ Unity WebGL Canvas │ │    │
│  ┌─ unity-view.js ──┐   │    │ (unity.framework.js)│ │    │
│  │ 2D/3D 切换逻辑   │   │    │                     │ │    │
│  │ UnityView.init() │   │    │ C# GameController   │ │    │
│  │ toggleView()     │   │    │  ← SendMessage()    │ │    │
│  └──────────────────┘   │    └─────────────────────┘ │    │
│                          └────────────────────────────┘    │
│  WebSocket ──→ ws-redirect.js ──→ Unity C# WebSocket      │
└────────────────────────────────────────────────────────────┘
```

### 10.2 Unity WebGL 构建

Unity 项目构建为 WebGL 目标平台，产物部署在 `display/src/main/resources/web/unity/` 目录下。构建配置如下：

| 配置项 | 值 | 说明 |
|--------|-----|------|
| `companyName` | `DefaultCompany` | Unity 项目公司名 |
| `productName` | `Substation3D` | 产品名称，显示在页脚 |
| `productVersion` | `0.1` | 版本号 |
| `dataUrl` | `Build/unity.data` | Unity 场景与资源数据包 |
| `frameworkUrl` | `Build/unity.framework.js` | Unity WebGL 运行时框架 JS |
| `codeUrl` | `Build/unity.wasm` | C# 代码编译的 WebAssembly 模块 |
| `streamingAssetsUrl` | `StreamingAssets` | 流式资源路径 |
| `unityBridgeObject` | `GameController` | JS 与 C# 通信的 GameObject 名称 |

Unity WebGL 实例通过 `createUnityInstance(canvas, config, onProgress)` API 加载，加载进度通过 `#unity-progress-bar-full` 进度条可视化反馈。

### 10.3 Unity-JS 桥接（postMessage / SendMessage 协议）

宿主页面通过 `unityInstance.SendMessage(gameObjectName, methodName, payload)` 向 Unity C# 脚本发送命令。桥接协议定义在 `unity/index.html` 和 `unity-view.js` 中。

**摄像机控制协议：**

| C# 方法 | 触发方式 | 参数格式 | 功能 |
|---------|----------|----------|------|
| `OnWebPan` | 左键拖拽（无修饰键） | `"dx,dy"` | 平移摄像机 |
| `OnWebOrbit` | Shift+左键 / 右键拖拽 | `"dx,dy"` | 轨道旋转摄像机 |
| `OnWebZoom` | 鼠标滚轮 | `String(-deltaY)` | 缩放摄像机 |

**拖拽模式判定（`resolveDragMode`）：**

```
button === 0 && !shiftKey && !altKey  →  "pan"      （平移）
button === 0 && (shiftKey || altKey)  →  "orbit"    （旋转）
button === 1 || button === 2          →  "orbit"    （中键/右键 → 旋转）
其他                                  →  "none"     （不处理）
```

**iframe 摄像机输入代理（`unity-view.js`）：**

由于 Unity 运行在 iframe 内，而 2D/3D 切换层 `#unity-input-layer` 覆盖在 iframe 上方接收指针事件，`unity-view.js` 负责：

1. 在 `#unity-input-layer` 上监听 `pointerdown/pointermove/pointerup/wheel` 事件
2. 通过 `$unityFrame.contentWindow.unityInstance` 跨 iframe 获取 Unity 实例引用
3. 调用 `sendUnityCamera(method, payload)` → `instance.SendMessage(UNITY_BRIDGE, method, payload)` 转发事件
4. iframe 自身的 `Canvas` 设置 `pointer-events: none`，所有交互由外层代理层接管

**仿真状态同步：**

Unity C# 脚本通过内建 WebSocket 客户端直接连接到 Display 模块的 WebSocket 服务（端口 8888），接收 `SimulationState` JSON 广播，更新：
- 小车位置与朝向（`CarInfo.position` → 3D 场景中移动对应的 GameObject）
- 小车状态颜色（`CarInfo.status` → 5 种材质颜色：IDLE=灰、WAITING_ROUTE=橙、READY=绿、MOVING=蓝、BLOCKED=红）
- 探索位图（`mapViewB64` → 网格单元着色：已探索/未探索/障碍/密封）
- 地图尺寸与障碍物布局（`taskConfig.mapWidth/mapHeight` + `mapBlockB64`）

### 10.4 WebSocket 集成（ws-redirect.js）

Unity WebGL 构建时 WebSocket 连接地址被硬编码为 `localhost`，无法适配实际部署环境。`ws-redirect.js` 在 Unity 加载前拦截全局 `WebSocket` 构造函数，将指向 localhost 的连接重定向到正确的动态主机地址。

**工作原理：**

```javascript
// 1. 从 URL 查询参数 wsPort 读取端口（默认 8888）
// 2. 根据页面协议构建 ws:// 或 wss:// URL
// 3. 代理 window.WebSocket，将 localhost/127.0.0.1 URL 替换为动态地址
```

**关键设计：**

| 设计点 | 实现 |
|--------|------|
| 端口可配置 | URL 参数 `?wsPort=8888` 或默认 `8888` |
| 协议自适应 | `https:` → `wss:`，`http:` → `ws:` |
| 主机名动态 | 取 `window.location.hostname`，适配任意部署环境 |
| 代理完整性 | `PatchedWebSocket.prototype = NativeWebSocket.prototype`，保留 `CONNECTING/OPEN/CLOSING/CLOSED` 静态常量 |
| 暴露调试接口 | `window.__unityWsUrl` 存储解析后的目标 WebSocket URL |
| 加载时序 | `<script src="ws-redirect.js"></script>` 在 `<head>` 中首先加载，确保在任何其他脚本执行前完成拦截 |

**数据流：**

```
Display Server (Java)                    Unity C# WebSocket Client
  WebSocketBridge                          (内建于 Unity WebGL)
  port 8888                                  |
    │                                        │ new WebSocket("ws://localhost:8888")
    │  SimulationState JSON                  │   ↓ ws-redirect.js 拦截
    ├────────────────────────────────────────┤ → "ws://<actual-host>:8888"
    │                                        │
    │                                        │ OnMessage(SimulationState JSON)
    │                                        │   → 更新 3D 场景
```

由于 Unity 与宿主页面共享同一个 WebSocket 服务，两者接收的数据完全一致，保证 2D Canvas 和 Unity 3D 视图展示的数据时刻同步。

### 10.5 Unity iframe 集成与 2D/3D 视图切换

宿主页面 `index.html` 的中央地图区域（`<main class="map-area">`）同时容纳 Canvas 2D 地图和 Unity 3D iframe，二者通过 CSS 显示/隐藏实现切换。

**DOM 结构：**

```html
<main class="map-area">
  <!-- 欢迎遮罩 -->
  <div id="welcome-overlay">...</div>

  <!-- Canvas 2D 地图栈 -->
  <div class="map-stack" id="map-stack" style="display:none">
    <canvas id="map-canvas"></canvas>    <!-- 地图层：网格 + 探索/障碍位图 -->
    <canvas id="car-canvas"></canvas>    <!-- 小车层：位置 + 路径 + 状态色 -->
  </div>

  <!-- Unity 3D 视图（默认隐藏） -->
  <div id="unity-shell" class="unity-shell" hidden>
    <iframe id="unity-frame" class="unity-frame"
            src="unity/index.html" title="Unity 3D 地图" tabindex="-1"></iframe>
    <div id="unity-input" class="unity-input-layer"
         title="左键拖移 | Shift+左键或右键旋转 | 滚轮缩放"></div>
  </div>
</main>
```

**切换按钮：**

左侧栏的 `<button id="btn-unity">🎮 3D视图</button>` 触发 `UnityView.toggleView()`。

**切换逻辑（`unity-view.js`）：**

| 切换方向 | 操作 |
|----------|------|
| 2D → 3D | 隐藏 Canvas（`#map-stack`、`#welcome-overlay`），显示 `#unity-shell`，同步 iframe 尺寸，按钮文字改为 "📐 2D视图" |
| 3D → 2D | 隐藏 `#unity-shell`，恢复 Canvas 显示，按钮文字改为 "🎮 3D视图"。若 2D Canvas 未绘制且实时数据已就绪，触发 `finalizeCanvas()` 重新绘制 |
| 仿真重置 | 调用 `UnityView.resetForNewSimulation()`：强制退出 3D 视图，重载 iframe（带 `?_=timestamp` 参数绕过缓存），清空 Unity 内缓存的探索格状态 |

**尺寸同步（`syncSize`）：**

Unity 视图的 DOM 尺寸根据当前地图配置动态计算，使 Unity WebGL 画布与 Canvas 2D 的网格比例一致：

```
cellSize = max(4, min(floor(availW / mapWidth), floor(availH / mapHeight)))
shellWidth  = mapWidth  × cellSize
shellHeight = mapHeight × cellSize
```

**CSS 关键样式：**

```css
.unity-shell       { display: none; position: relative; overflow: hidden; }
.unity-shell.active { display: block; }
.unity-frame       { pointer-events: none; border: none; background: #231F20; }
.unity-input-layer { position: absolute; inset: 0; z-index: 2;
                     cursor: grab; touch-action: none; }
.map-area.map-area-unity { overflow: hidden; }
```

`#unity-frame` 的 `pointer-events: none` 确保 iframe 内 Canvas 不直接接收鼠标事件，所有交互通过 `#unity-input-layer` 代理层采集后再通过 `SendMessage` 注入 Unity。

**app.js 集成点：**

```javascript
// 页面加载时初始化 UnityView 桥接
if (window.UnityView) {
  UnityView.init({
    getLiveData:  function () { return liveData; },
    getReplayData: function () { return replayData; },
    isCanvasReady: function () { return canvasReady; },
    requestFinalizeCanvas: function () { finalizeCanvas(); }
  });
}

// Canvas 绘制完成后通知 UnityView
if (window.UnityView) { UnityView.onCanvasReady(); }

// 窗口 resize 时同步 Unity 尺寸
window.addEventListener('resize', function () {
  if (window.UnityView && UnityView.is3D()) UnityView.syncSize();
});

// 仿真重置时退出 3D 并重载 iframe
if (window.UnityView && UnityView.resetForNewSimulation) {
  UnityView.resetForNewSimulation();
}
```

### 10.6 Unity Build 构建资产

Unity WebGL 构建产物位于 `display/src/main/resources/web/unity/`：

```
unity/
├── index.html                 # Unity WebGL 宿主页（含摄像机 JS 桥接代码）
├── ws-redirect.js             # WebSocket 地址重定向拦截器
├── Build/
│   ├── unity.data             # Unity 场景资源包（网格模型、材质、贴图等）
│   ├── unity.framework.js     # Unity WebGL 运行时框架（约数 MB，核心引擎 JS）
│   ├── unity.loader.js        # Unity 加载器（createUnityInstance API）
│   └── unity.wasm             # C# 脚本编译产物（WebAssembly 字节码）
└── TemplateData/
    ├── style.css              # Unity 默认 UI 样式（进度条、Logo、全屏按钮）
    ├── favicon.ico            # 站点图标
    ├── *.png                  # 进度条背景/填充图、Logo、全屏按钮、WebGL Logo 等
    └── MemoryProfiler.png     # 内存分析器占位图
```

| 文件 | 大小级别 | 用途 |
|------|----------|------|
| `unity.data` | 数 MB ~ 数十 MB | 包含 3D 场景、模型网格、材质、纹理贴图、预制体等所有非代码资源 |
| `unity.wasm` | 数 MB | C# MonoBehaviour 脚本编译为 WebAssembly，包含 SubstationSceneBuilder、CarController、CameraController、WebSocketClient 等游戏逻辑 |
| `unity.framework.js` | 数 MB | Unity 引擎的 JavaScript/WebAssembly 胶水层，负责内存管理、图形渲染（WebGL 2.0）、音频、输入等底层功能 |
| `unity.loader.js` | 数十 KB | WebGL 实例创建与加载流程管理，暴露 `createUnityInstance()` 全局函数 |
| `TemplateData/` | 数十 KB | 加载进度条 UI、Logo 图片、全屏按钮图标等纯展示资源 |

所有文件通过 Spring Boot 的静态资源服务（`classpath:/web/unity/`）对外暴露，无需额外配置。

### 10.7 关键 C# 脚本（Unity 项目内）

以下 C# 脚本位于 Unity 项目中（不在 Java 仓库中），通过 `unity.wasm` 编译后在浏览器中运行：

#### 10.7.1 SubstationSceneBuilder —— 变电站场景构建器

**职责：** 在 Unity 3D 场景中按地图配置动态构建变电站巡检网格。

**核心逻辑：**
- 根据 WebSocket 接收的 `taskConfig.mapWidth × mapHeight` 创建 30×30（默认）的网格地面
- 为每个网格单元实例化 Floor 预制体（带坐标标签），作为可探索区域
- 根据 `mapBlockB64` 位图数据在障碍物位置实例化 Obstacle 预制体（配电柜、变压器、围栏等变电设备模型）
- 为每个网格单元附加 `ExplorableCell` 组件，记录其坐标与探索状态
- 边缘保留 1 格宽的通道（与 Person C 的 TaskInitializer 障碍物生成策略一致）
- 支持运行时重建场景（RESET 时清空旧网格、重新实例化）

#### 10.7.2 CarController —— 小车控制器

**职责：** 管理每辆巡检小车的 3D 表现与状态同步。

**核心逻辑：**
- 接收 `SimulationState.CarInfo[]` 数组，为每辆车创建/更新对应的 GameObject：
  - 若 carId 对应的 GameObject 尚不存在，从 Car 预制体实例化（带编号标签）
  - 将 `position (x, y)` 映射到 3D 世界坐标，使用 `Vector3.Lerp` 实现插值平滑移动
  - 根据 `target` 调整小车朝向（`Quaternion.LookRotation`）
- 根据 `status` 切换材质颜色（5 种状态色）：
  - `IDLE` → 灰色 `#9E9E9E`
  - `WAITING_ROUTE` → 橙色 `#FF9800`
  - `READY` → 绿色 `#4CAF50`
  - `MOVING` → 蓝色 `#2196F3`
  - `BLOCKED` → 红色 `#F44336`
- 根据 `route` 路径点序列绘制行进路线（LineRenderer 或路径点标记）
- 车辆退出（carId 不再出现在 cars 数组中）时隐藏或销毁对应 GameObject

#### 10.7.3 CameraController —— 摄像机控制器

**职责：** 提供三维场景的观察视角控制，接收 Web 端鼠标/触摸事件。

**核心逻辑：**
- 接收来自 JavaScript 的 `OnWebPan(dx, dy)`、`OnWebOrbit(dx, dy)`、`OnWebZoom(delta)` 调用
- **平移（Pan）：** 摄像机在 XZ 平面上沿鼠标拖拽方向移动，保持高度不变
- **轨道旋转（Orbit）：** 摄像机绕场景中心点（地图几何中心）旋转，`dx` 控制水平角度（Yaw），`dy` 控制俯仰角度（Pitch），限制俯仰范围防止翻转
- **缩放（Zoom）：** 调整摄像机到注视点的距离（或调整 FOV），限制最小/最大距离避免穿透地面或脱离场景
- 默认视角：从变电站上方 45° 俯视，覆盖完整地图范围
- 支持移动端触摸手势（单指平移、双指缩放/旋转）

#### 10.7.4 WebSocketClient —— WebSocket 客户端

**职责：** 在 Unity C# 中建立 WebSocket 连接，接收 Display 服务广播的仿真状态。

**核心逻辑：**
- 场景启动时（`Start()` / `Awake()`）创建 `ClientWebSocket` 或使用 Unity 的 `WebSocket` 插件连接 `ws://localhost:8888`（被 `ws-redirect.js` 自动重写为实际主机）
- 保持长连接，处理断线重连（指数退避，最大重试间隔 30 秒）
- 收到文本消息后使用 `JsonUtility.FromJson<SimulationState>(message)` 反序列化
- 分发数据到对应组件：
  - `SubstationSceneBuilder.OnSimulationState(state)` → 地图重建/探索更新
  - `CarController.OnSimulationState(state)` → 小车位置/状态更新
- Unity 主线程调度：WebSocket 回调在后台线程中触发，使用 `UnityMainThreadDispatcher` 将更新操作调度到主线程执行（保证 GameObject API 调用的线程安全）

#### 10.7.5 ExplorationVisualizer —— 探索可视化器

**职责：** 根据探索位图更新 3D 场景中网格单元的颜色/材质。

**核心逻辑：**
- 解析 `mapViewB64`（Base64 编码的探索位图），对每个网格单元：
  - **已探索（bit=1）：** 地板材质切换为浅色/高亮色（如浅绿 `#C8E6C9`），表示该区域已被巡检
  - **未探索（bit=0）：** 地板保持默认暗色材质（如深灰 `#37474F`），表示尚未到达
  - **障碍物（mapBlock bit=1）：** 格子显示设备模型（Obstacle 预制体），地板不渲染
  - **密封不可达（mapSealed bit=1）：** 地板渲染为红色警告色，表示该区域被障碍物包围无法到达
- 使用 GPU Instancing 或 MaterialPropertyBlock 批量更新材质，避免逐个修改 Material 导致的高开销
- 探索事件动画：当网格单元状态从未探索变为已探索时，播放短暂的材质渐变/发光动画（0.3s 过渡）
- 热力图叠加（可选）：根据 `mapHeat` 数据，在高频访问格子上叠加颜色强度（黄→红渐变），直观展示小车活动热点

---

## 11. Unity 3D 模块与 Person C 规划模块的协作

Unity 3D 可视化模块与 Person C 的三个核心规划模块（Navigator、Target Planner、Task Configurator）形成"规划 → 执行 → 可视化"闭环：

```
TaskConfigurator.initialize()
  │
  ├─→ Redis 写入 mapBlock / mapSealed / CarID:Position
  │
  └─→ Display WebSocket 广播 SimulationState
        │
        ├─→ Canvas 2D (app.js renderMapLayer / renderCarsLayer)
        └─→ Unity 3D (SubstationSceneBuilder + CarController)
              └─→ 用户观察 3D 场景 → 调整配置 → SET_CONFIG → ...
```

| Person C 模块 | 影响的 Unity 可视化元素 |
|---------------|------------------------|
| **TaskInitializer** | 地图尺寸决定网格规模；障碍物位图决定 Obstacle 预制体摆放；出生点决定小车初始 3D 位置；密封位图决定红色警告格 |
| **TargetPlanner** | 分配的 Target 决定小车的目标朝向（Unity 中显示目标标记/路径终点指示器） |
| **Navigator** | 计算的 RouteList 决定小车移动轨迹（Unity 中显示行进路线/路径箭头 LineRenderer） |
| **ExplorationWeightedPathFinder** | 已探索/未探索代价差驱动路径偏好未探索区域，对应 Unity 中灰暗→亮绿的探索动画推进 |
| **DynamicObstacleUtil** | 障碍物动态增删触发 Unity 场景中 Obstacle GameObject 的实时创建/销毁 |

Unity 3D 视图与 Canvas 2D 视图共享同一份 Redis 数据源，由 Display 模块的 `WebSocketBridge.pushSimulationState()` 统一广播，保证两个视图始终展示一致的仿真状态。
