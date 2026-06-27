# StrategySupervisor 模块设计文档

> **对应源码位置**：`strategy-supervisor/src/main/java/com/substation/strategysupervisor/`
>
> 文件清单：`StrategySupervisorMain.java`， `RouteOverlapEvaluator.java`， `RouteEvaluator.java`， `WeightedPathPlanner.java`
>
> 依赖的 Common 类：`CarStatus`， `Point`（`common/.../model/`）， `MessageBuilder`， `MessageBus`， `MessageTypes`， `QueueNames`（`common/.../mq/`）， `BlackboardClient`（`common/.../redis/`）

---

## 1. 模块概述

StrategySupervisor（策略监督器）是仿真系统中的**路线质量监督与多车协同优化服务**。它作为独立的后台进程运行，在每辆小车获得初始路线后对其质量进行评估：检测路线是否与其他车辆在未探索区域上高度重合，以及已探索格子的占比是否过高；在必要时触发路线重规划或请求任务重分配。

**本模块是 v4 新增模块**，在 v3 参考实现中不存在。v3 中 Navigator 完成路线规划后直接交付给 CarAgent，没有对路线质量进行监督或优化的环节。StrategySupervisor 填补了这一空白，使得系统能够在运行过程中动态纠正低质量路线、缓解多车路线冲突。

| 项目 | 说明 |
|------|------|
| **核心职责** | 接收路线监督命令，评估路线质量（重合度 + 探索率），必要时重新搜索偏好未探索区域的替代路径或多车重合时请求重分配 |
| **输入消息** | `SUPERVISE_ROUTE`（从 `StrategySupervisorCmd` 队列） |
| **输出消息** | `ROUTE_OPTIMIZED`（发布到 `ControllerCmd` 队列） |
| **核心算法** | 路线重合检测（未探索格交集比例）、动态阈值探索率评估、加权 A* 优先队列路径搜索（已探索代价=3） |
| **运行方式** | 独立进程，通过 RabbitMQ 消息驱动，Redis 黑板读写共享状态 |

**模块架构：**

```
Controller ---SUPERVISE_ROUTE{carId}---> StrategySupervisorCmd 队列
                                                   |
                                          StrategySupervisorMain.subscribe
                                                   |
                              ┌────────────────────┼────────────────────┐
                              │                    │                    │
                    1. 从 Redis 读取 carId 的 Position, Target, Route, Status
                              │                    │                    │
                    2. RouteOverlapEvaluator.isHighlyOverlapped()
                              │                    │                    │
                       高度重合？                  │                    │
                    ┌───────┴───────┐              │                    │
                   是               否              │                    │
                    │                │              │                    │
                    │         3. RouteEvaluator.evaluate()
                    │                │              │                    │
                    │         NEED_OPTIMIZE?       SKIP                 │
                    │         ┌──────┴──────┐       │                   │
                    │        是             否       │                   │
                    │         │              │       │                   │
              清空 Route/Target 4. WeightedPathPlanner.plan()
              发送重合重分配             │                            │
                               复查位置 → pushRoute → ROUTE_OPTIMIZED
```

**注意：StrategySupervisor 不清除 Car 的状态（IDLE/MOVING）**，重合重分配时只清除 Route 和 Target，状态变更由 Controller 在收到消息后统一管理。

---

## 2. StrategySupervisorMain —— 消息入口与主控流程

### 2.1 构造与启动

```java
public StrategySupervisorMain(String redisHost, int redisPort, String mqHost, int mqPort)
```

| 参数 | 说明 |
|------|------|
| `redisHost` / `redisPort` | Redis 连接地址，用于实例化 `BlackboardClient` |
| `mqHost` / `mqPort` | RabbitMQ 连接地址，用于实例化 `MessageBus` |

`start()` 方法：
1. 连接 Redis（`BlackboardClient`）和 RabbitMQ（`MessageBus`）
2. 声明 `StrategySupervisorCmd` 队列
3. 实例化三个核心组件：`RouteEvaluator`、`RouteOverlapEvaluator`、`WeightedPathPlanner`
4. 订阅 `StrategySupervisorCmd` 队列，注册消息回调
5. 注册 JVM 关闭钩子，安全释放连接资源

默认地图尺寸为 `BlackboardClient.DEFAULT_WIDTH × DEFAULT_HEIGHT`。

### 2.2 消息订阅与分发

```java
messageBus.subscribe(QueueNames.STRATEGY_SUPERVISOR_CMD, rawMessage -> {
    JSONObject msg = JSONObject.parseObject(rawMessage);
    String type = msg.getString("type");
    int tick = msg.getIntValue("tick", 0);

    if (!MessageTypes.SUPERVISE_ROUTE.equals(type)) {
        log.warn("[StrategySupervisor] 未知消息类型: {}", type);
        return;
    }

    String carId = msg.getString("carId");
    handleSupervise(evaluator, overlapEvaluator, planner, carId, tick);
});
```

消息字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | String | 消息类型，必须为 `"SUPERVISE_ROUTE"` |
| `tick` | int | 当前仿真 tick 序号 |
| `carId` | String | 目标小车 ID（如 `"Car001"`） |

### 2.3 监督主流程（`handleSupervise`）

```
handleSupervise(evaluator, overlapEvaluator, planner, carId, tick):
    1. 从 Redis 读取:
       - posOpt = bb.getCarPosition(carId)
       - targetOpt = bb.getCarTarget(carId)
       - currentRoute = bb.getCarRoute(carId)
       - statusOpt = bb.getCarStatus(carId)
    2. 若 posOpt/targetOpt/currentRoute 任一为空 → 发送 ROUTE_OPTIMIZED(optimized=false)，退出
    3. 若 statusOpt 为空或 status != READY → 跳过监督（非就绪状态不干预），退出
    4. ── 重合检测阶段 ──
       if shouldReassignForOverlap(carId, tick)
         && overlapEvaluator.isHighlyOverlapped(carId, currentRoute, bb):
           a. lastOverlapReassignTick.put(carId, tick)      // 记录冷却时间
           b. bb.clearRoute(carId)                           // 清空路线
           c. bb.clearCarTarget(carId)                       // 清空目标
           d. sendOverlapReassign(carId, currentRoute.size(), tick)  // 通知 Controller
           e. 退出
    5. ── 探索率评估阶段 ──
       evalResult = evaluator.evaluate(bb, currentRoute)
       if evalResult == SKIP → 发送 ROUTE_OPTIMIZED(optimized=false)，退出
    6. ── 路径优化阶段 ──
       newRoute = planner.plan(posOpt, targetOpt, bb, currentRoute)
    7. 若 newRoute == currentRoute 或 newRoute 为空 → 发送 ROUTE_OPTIMIZED(optimized=false)，退出
    8. 若 newRoute.size() > currentRoute.size() * MAX_PATH_LENGTH_RATIO(2.0)
       → 路径过长，放弃优化 → 发送 ROUTE_OPTIMIZED(optimized=false)，退出
    9. 写前复查：重新读取 carId 的 Position
       - 若位置已变更 → 放弃本次优化 → 发送 ROUTE_OPTIMIZED(optimized=false)，退出
    10. bb.pushRoute(carId, newRoute)                        // 原子写入新路线
    11. 发送 ROUTE_OPTIMIZED(optimized=true, oldLength, newLength)
```

### 2.4 重合重分配冷却机制

```java
private static final int OVERLAP_REASSIGN_COOLDOWN_TICKS = 10;
private final Map<String, Integer> lastOverlapReassignTick = new ConcurrentHashMap<>();

private boolean shouldReassignForOverlap(String carId, int tick) {
    Integer last = lastOverlapReassignTick.get(carId);
    return last == null || tick - last >= OVERLAP_REASSIGN_COOLDOWN_TICKS;
}
```

| 参数 | 值 | 说明 |
|------|------|------|
| `OVERLAP_REASSIGN_COOLDOWN_TICKS` | 10 | 同车两次重合重分配的最小间隔 tick 数 |

**设计意图**：防止同一辆车在连续 tick 内反复触发重合重分配，给新分配的目标和路线留出执行时间。冷却期内即使再次检测到重合也跳过重分配，转而尝试路线优化。

### 2.5 回复消息（`sendResult` / `sendOverlapReassign`）

**常规优化结果（`sendResult`）：**

```java
Map<String, Object> data = Map.of(
    "carId", carId,
    "optimized", optimized,
    "oldLength", oldLen,
    "newLength", newLen
);
messageBus.publish(QueueNames.CONTROLLER_CMD, msg);  // type = ROUTE_OPTIMIZED
```

| 字段 | 说明 |
|------|------|
| `carId` | 小车 ID |
| `optimized` | 是否完成了路线优化（boolean） |
| `oldLength` | 原路线步数 |
| `newLength` | 新路线步数（未优化时为 0） |

**重合重分配（`sendOverlapReassign`）：**

```java
Map<String, Object> data = Map.of(
    "carId", carId,
    "optimized", false,
    "overlapReassign", true,     // 标记：这是重合触发的重分配，非正常优化
    "oldLength", oldLen,
    "newLength", 0
);
messageBus.publish(QueueNames.CONTROLLER_CMD, msg);  // type = ROUTE_OPTIMIZED
```

`overlapReassign: true` 标记让 Controller 知道这是一次**任务级别的重分配请求**（需要重新分配目标点），而非仅仅路线级别的优化替换。此时 Controller 应将该车置为 IDLE 状态并调用 TargetPlanner 分配新目标。

---

## 3. RouteOverlapEvaluator —— 多车路线重合检测

### 3.1 设计意图

多辆小车同时执行探索任务时，如果它们的路线在**未探索区域**上高度重合，意味着多辆车将走相同的路径去覆盖相同的未探索格子，造成探索效率低下。RouteOverlapEvaluator 识别这种场景并触发任务重分配。

**关键设计决策：仅统计未探索格子上的重合。** 高探索率时车辆必然经过已探索通道返回出发点，若把已探索格计入重合会产生大量误判并反复打断任务。

### 3.2 核心常量

```java
private static final double OVERLAP_THRESHOLD = 0.5;
```

| 常量 | 值 | 含义 |
|------|------|------|
| `OVERLAP_THRESHOLD` | 0.5 | 本车路线未探索格中，与其他车路线重合比例超过 50% 即触发重分配 |

### 3.3 算法流程

```
isHighlyOverlapped(carId, myRoute, bb):
    1. 若 myRoute 为空 → 返回 false
    2. explored = bb.loadExploredBitmap()           // 加载全局探索位图
    3. myUnexploredCount = countUnexploredCells(myRoute, explored)
    4. 若 myUnexploredCount == 0 → 返回 false      // 路线全在已探索区域，不触发
    5. 遍历 bb.discoverCarIds() 获取所有其他车辆:
       for each otherId != carId:
           otherRoute = bb.getCarRoute(otherId)
           若 otherRoute 为空 → 跳过
           sharedUnexplored = countSharedUnexplored(myRoute, otherRoute, explored)
           if sharedUnexplored / myUnexploredCount >= 0.5 → 返回 true
    6. 未找到高度重合 → 返回 false
```

### 3.4 辅助方法

| 方法 | 功能 |
|------|------|
| `countUnexploredCells(route, explored)` | 遍历路线，统计格子状态为未探索（`!explored[y][x]`）的数量 |
| `countSharedUnexplored(myRoute, otherRoute, explored)` | 遍历 `myRoute`，统计同时满足"未探索"且在 `otherRoute` 中出现的格子数 |
| `containsCell(route, cell)` | 线性扫描路线列表，判断某坐标是否在路线上 |

### 3.5 触发后果

检测到高度重合后，`StrategySupervisorMain`：
1. 记录当前 tick 到 `lastOverlapReassignTick`（冷却用）
2. 调用 `bb.clearRoute(carId)` 清空 Redis `CarID:RouteList`
3. 调用 `bb.clearCarTarget(carId)` 清空 Redis `CarID:Target`
4. 发送 `ROUTE_OPTIMIZED{overlapReassign=true}` 通知 Controller 为该车分配新目标

**注意：不清除 `CarID:Status`**。Controller 收到 `overlapReassign=true` 后负责将该车状态设为 IDLE 并重新分配目标。

---

## 4. RouteEvaluator —— 路线探索率评估

### 4.1 设计意图

即使路线没有多车重合问题，如果路线大部分经过的是**已经探索过的格子**，该路线的边际探索价值很低。RouteEvaluator 评估路线上已探索格子的占比，当超过动态阈值时标记为 `NEED_OPTIMIZE`，触发加权路径重搜索。

### 4.2 动态阈值设计

```java
private static final double MIN_THRESHOLD = 0.3;   // 最低触发阈值
private static final double MAX_THRESHOLD = 0.8;   // 最高触发阈值
```

评估阈值**随全局探索率动态上调**：

```java
double globalRate = bb.getExplorationRate() / 100.0;    // 0.0 ~ 1.0
double threshold = Math.max(MIN_THRESHOLD, Math.min(MAX_THRESHOLD, globalRate + 0.05));
```

| 全局探索率 | 阈值（globalRate + 0.05，钳位到 [0.3, 0.8]） |
|-----------|---------------------------------------------|
| 0%（开局） | 0.30 |
| 25% | 0.30 |
| 50% | 0.55 |
| 75% | 0.80 |
| 90%+（后期） | 0.80 |

**设计意图**：
- **低探索率时**：阈值 0.3，对路线敏感——只要 30% 的格子已探索就触发优化，鼓励车辆走更多新路。
- **高探索率时**：阈值放宽到 0.8——地图已几乎探索完毕，车辆路线必然大量经过已探索区域，不应苛求。
- **`+0.05` 偏移**：阈值略高于全局探索率，避免在全局探索率本身较高时过度触发优化（已探索区域占大多数时难以避免走已探索格）。

### 4.3 算法流程

```
evaluate(bb, route):
    1. 若 route 为空 → 返回 SKIP
    2. explored = bb.loadExploredBitmap()
    3. exploredCount = countExploredOnRoute(route, explored)
    4. total = route.size()
    5. globalRate = bb.getExplorationRate() / 100.0
    6. threshold = max(0.3, min(0.8, globalRate + 0.05))
    7. if exploredCount / total >= threshold → 返回 NEED_OPTIMIZE
       else → 返回 SKIP
```

### 4.4 计数器

```java
private static int countExploredOnRoute(List<Point> route, boolean[][] explored) {
    int count = 0;
    for (Point point : route) {
        if (explored[point.y()][point.x()]) count++;
    }
    return count;
}
```

### 4.5 返回枚举

```java
enum Result {
    NEED_OPTIMIZE,   // 已探索比例过高，需要重新搜索
    SKIP             // 质量可接受，跳过优化
}
```

---

## 5. WeightedPathPlanner —— 加权 A* 路径搜索

### 5.1 设计意图

当 `RouteEvaluator` 判定路线已探索比例过高时，`WeightedPathPlanner` 负责在相同起点和终点之间搜索一条**偏好未探索区域**的替代路径。与 Navigator 中使用的 `ExplorationWeightedPathFinder` 不同，`WeightedPathPlanner` 是 StrategySupervisor 内部独立的搜索实现，具有独立的代价模型和更严格的质量校验。

### 5.2 代价模型

| 常量 | 值 | 含义 |
|------|------|------|
| `UNEXPLORED_COST` | 1 | 经过未探索格子的单步代价 |
| `EXPLORED_PENALTY` | 3 | 经过已探索格子的单步代价 |

**与 Navigator 代价模型的对比：**

| 维度 | Navigator | StrategySupervisor |
|------|-----------|-------------------|
| 未探索代价 | 1 | 1 |
| 已探索代价 | 5 | **3** |
| 惩罚比 | 5:1 | **3:1** |
| 搜索算法 | 加权 Dijkstra / 加权 A*（通过 `ExplorationWeightedPathFinder`） | 加权 A*（自实现优先队列） |

StrategySupervisor 使用更温和的 3:1 惩罚比，原因在于：
- 监督优化是**二次搜索**，如果惩罚过于激进可能导致替代路径绕远路，被后续的 `MAX_PATH_LENGTH_RATIO` 检查拦截。
- 3:1 的差异足以产生偏好未探索区域的方向性引力，同时不会让算法完全绕过已探索的必经走廊。

### 5.3 搜索算法

使用**加权 A***（优先队列按 `g(n) + h(n)` 排序）：

```java
final class WeightedPathPlanner implements Comparator<int[]> {

    private static final int[][] DIRECTIONS = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};

    @Override
    public int compare(int[] a, int[] b) {
        return Integer.compare(a[2], b[2]);   // a[2] = g(n) + h(n)
    }
}
```

| 项目 | 实现 |
|------|------|
| 优先队列 | `PriorityQueue<int[]>`，堆顶为 `g(n) + h(n)` 最小的节点 |
| 节点表示 | `int[]{x, y, priority}`，其中 `priority = g_cost + manhattan(node, target)` |
| 邻居扩展 | 四方向（上/下/左/右，不走斜角） |
| 启发式 `h(n)` | 曼哈顿距离 |
| 障碍物处理 | `bb.loadBlockedMapWithCars()` —— 包含静态障碍物 + 当前所有车辆占位 |
| 探索代价查询 | `explored[ny][nx] ? EXPLORED_PENALTY(3) : UNEXPLORED_COST(1)` |
| 最小代价剪枝 | `minCost[ny][nx]` 记录到达每个格子的最小累计代价，新代价更高则跳过 |

### 5.4 主要方法

#### `plan(Point start, Point target, BlackboardClient bb, List<Point> currentRoute)`

```
plan(start, target, bb, currentRoute):
    1. 获取地图尺寸
    2. 若 start 或 target 越界 → 返回 currentRoute（不修改）
    3. blocked = bb.loadBlockedMapWithCars()
    4. explored = bb.loadExploredBitmap()
    5. return planOnBitmap(start, target, blocked, explored, width, height, currentRoute)
```

当起点或终点越界时返回原路线而不是空列表，确保调用方不会因越界而误判优化失败。

#### `planOnBitmap(Point start, Point target, boolean[][] blocked, boolean[][] explored, int width, int height, List<Point> currentRoute)`

核心搜索循环：
1. 初始化 `minCost[][]`（全 `Integer.MAX_VALUE`）和 `parent[][]`（路径回溯用）
2. `minCost[start] = 0`，起始节点加入优先队列
3. 循环：取出最小优先级节点，若等于 target → 调用 `reconstructPath` 返回
4. 遍历四方向邻居，跳过越界/障碍物
5. 计算步长代价（`explored ? 3 : 1`），更新 `minCost` 和 `parent`
6. 将邻居加入优先队列（含启发式）
7. 优先队列耗尽仍未到达 target → 返回 `currentRoute`（不可达，保持原路线）

#### `reconstructPath(Point[][] parent, Point start, Point target)`

从 target 沿 `parent` 链回溯到 start（不含 start），反转顺序后返回不可变列表：

```java
Collections.reverse(path);
return Collections.unmodifiableList(path);
```

### 5.5 路径长度校验（调用方）

`WeightedPathPlanner` 本身不做长度校验——校验由 `StrategySupervisorMain.handleSupervise` 在获得新路线后执行：

```java
private static final double MAX_PATH_LENGTH_RATIO = 2.0;

if (newRoute.size() > currentRoute.size() * MAX_PATH_LENGTH_RATIO) {
    sendResult(carId, false, 0, 0, tick);   // 路径过长，放弃
    return;
}
```

| 参数 | 值 | 说明 |
|------|------|------|
| `MAX_PATH_LENGTH_RATIO` | 2.0 | 新路径长度不得超过原路径的 **2 倍** |

**设计意图**：追求未探索区域不能以几何级数的绕路为代价。如果偏好未探索区域的路径比原路径长 2 倍以上，说明"抄近路"的已探索走廊对通行效率至关重要，此时保留原路线更合理。

### 5.6 写前复查

与 Navigator 一致，在推送新路线前重新检查车辆位置：

```java
Point nowPos = bb.getCarPosition(carId).orElse(null);
if (nowPos == null || !nowPos.equals(posOpt.get())) {
    // 规划期间车已移动，起点不准，放弃
    sendResult(carId, false, 0, 0, tick);
    return;
}
```

防止在搜索期间车辆被其他 tick 移动导致路线与实际位置脱节。

---

## 6. 路由存储机制

### 6.1 写入

`StrategySupervisorMain` 调用 `bb.pushRoute(carId, newRoute)` 写入优化后的路线。

写入机制与 Navigator 相同：使用 Lua 脚本原子操作，先 DEL 旧 Key，再逆序 LPUSH 新路径点到 `CarID:RouteList`。路径的第一个步点（小车下一步要走的格子）在 List 右端，CarAgent 通过 `RPOP` 消费。

### 6.2 清除（重合重分配场景）

重合重分配时调用 `bb.clearRoute(carId)` 和 `bb.clearCarTarget(carId)`：

```java
public void clearRoute(String carId) { jedis.del(carId + ":RouteList"); }
public void clearCarTarget(String carId) { jedis.del(carId + ":Target"); }
```

**不清除 `CarID:Status`**——统计变更由 Controller 统一管理。

### 6.3 与 Navigator 的协同

| 场景 | Navigator 职责 | StrategySupervisor 职责 |
|------|---------------|------------------------|
| 正常流程 | 收到 `PLAN_ROUTE` → 搜索路径 → 写入 Redis | Navigator 完成后 → 收到 `SUPERVISE_ROUTE` → 评估 → 可能覆盖路线 |
| 覆盖策略 | 每次 `PLAN_ROUTE` 先 DEL 再写入 | `pushRoute` 同样先 DEL 再写入，保证无残留 |
| 并发安全 | 写前复查位置 | 写前复查位置（与 Navigator 独立检查） |

两个模块对同一辆车的路线写入是串行化的：Controller 先发 `PLAN_ROUTE` 等 Navigator 回复 `ROUTE_PLANNED`，再发 `SUPERVISE_ROUTE` 等 StrategySupervisor 回复 `ROUTE_OPTIMIZED`。不会出现两个模块同时写入 `CarID:RouteList` 的竞态。

---

## 7. 消息交互汇总

| 消息类型 | 方向 | 队列 | 触发条件 |
|----------|------|------|----------|
| `SUPERVISE_ROUTE` | Controller → StrategySupervisor | `StrategySupervisorCmd` | 每 tick 各车路线规划完成后 |
| `ROUTE_OPTIMIZED` | StrategySupervisor → Controller | `ControllerCmd` | 监督评估完成（含优化、跳过、重分配） |

**消息格式（`SUPERVISE_ROUTE`）：**

```json
{
  "type": "SUPERVISE_ROUTE",
  "tick": 42,
  "carId": "Car001"
}
```

**消息格式（`ROUTE_OPTIMIZED`——正常结果）：**

```json
{
  "type": "ROUTE_OPTIMIZED",
  "tick": 42,
  "carId": "Car001",
  "data": {
    "carId": "Car001",
    "optimized": true,
    "oldLength": 25,
    "newLength": 20
  }
}
```

**消息格式（`ROUTE_OPTIMIZED`——重合重分配）：**

```json
{
  "type": "ROUTE_OPTIMIZED",
  "tick": 42,
  "carId": "Car001",
  "data": {
    "carId": "Car001",
    "optimized": false,
    "overlapReassign": true,
    "oldLength": 25,
    "newLength": 0
  }
}
```

---

## 8. 完整流程总览

```
Controller 每 tick 对每辆车:

    1. 发送 PLAN_ROUTE → Navigator
    2. 等待 ROUTE_PLANNED 回复
    3. 发送 SUPERVISE_ROUTE → StrategySupervisor
              │
              ▼
    ┌─────────────────────────────────────────┐
    │      StrategySupervisor 内部流程         │
    │                                         │
    │  ① 读取 Redis: Position, Target, Route,  │
    │     Status                              │
    │                                         │
    │  ② overlapEvaluator.isHighlyOverlapped? │
    │     ├─ 是 → clearRoute + clearTarget    │
    │     │       发送 overlapReassign=true    │
    │     │       Controller → 置 IDLE →      │
    │     │       TargetPlanner 分配新目标     │
    │     └─ 否 ↓                             │
    │                                         │
    │  ③ evaluator.evaluate(bb, route)        │
    │     ├─ SKIP → 发送 optimized=false      │
    │     └─ NEED_OPTIMIZE ↓                  │
    │                                         │
    │  ④ planner.plan(start, target, bb,      │
    │                   currentRoute)         │
    │     偏好未探索区域（explored cost=3）     │
    │                                         │
    │  ⑤ 校验:                               │
    │     - 新路线非空、不同于原路线           │
    │     - 长度 ≤ 原路线 × 2.0               │
    │     - 车未移动（写前复查）               │
    │                                         │
    │  ⑥ pushRoute(carId, newRoute)           │
    │     发送 ROUTE_OPTIMIZED(optimized=true) │
    └─────────────────────────────────────────┘
```

---

## 9. 设计要点总结

1. **新增监督层**：v4 新增模块，在 Navigator 规划完成后对路线质量进行二次审查，填补 v3 中路线质量缺乏反馈的空白。

2. **双维度评估**：重合检测（多车协同）+ 探索率评估（单路线质量），两个维度独立判断、串行执行，重合检测优先。

3. **仅统计未探索格重合**：`RouteOverlapEvaluator` 只统计未探索格子上的路线重合，避免高探索率时已探索通道的"假重合"导致误判和反复打断任务。

4. **动态评估阈值**：`RouteEvaluator` 的触发阈值随全局探索率从 0.3 到 0.8 上调，低探索率时严格、高探索率时宽容，避免后期无意义优化。

5. **独立代价模型（3:1）**：`WeightedPathPlanner` 使用 3:1 的已探索/未探索代价比，与 Navigator 的 5:1 形成梯度——监督优化是二次搜索，更温和的代价比避免过度绕路。

6. **长度硬约束（2x）**：新路线长度不得超过原路线的 2 倍，防止"为探索而绕路"的极端情况。

7. **重合冷却机制**：同车两次重合重分配至少间隔 10 tick，避免新目标刚分配就被再次打断。

8. **写前复查**：与 Navigator 相同的并发安全机制，推送路线前重新检查车辆位置。

9. **职责边界清晰**：StrategySupervisor 只负责"评估 → 优化 → 写入路线 / 请求重分配"，不直接修改 `CarID:Status`，状态变更由 Controller 统一管理。

10. **容错设计**：任何异常（数据缺失、不可达、路径过长、位置已变）均通过 `sendResult(optimized=false)` 优雅降级，不会阻塞或崩溃。
