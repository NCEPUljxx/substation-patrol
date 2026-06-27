# Navigator 模块设计文档

> **对应源码位置**：`navigator/src/main/java/com/substation/navigator/`
>
> 文件清单：`NavigatorMain.java`， `PathPlanner.java`， `PathPlannerFactory.java`， `BfsPathFinder.java`， `AStarPathFinder.java`
>
> 依赖的 Common 类：`ExplorationWeightedPathFinder`， `ExplorationPathCosts`（`common/.../map/`）， `AlgorithmType`（`common/.../model/`）

---

## 1. 模块概述

Navigator（导航器）是仿真系统中的**路径规划服务**，负责根据小车的当前位置和目标位置，在障碍物地图上搜出一条最短加权路径。

| 项目 | 说明 |
|------|------|
| **核心职责** | 接收路径规划命令，执行路径搜索算法，将结果写入 Redis 供 CarAgent 逐步消费 |
| **输入消息** | `PLAN_ROUTE`（从 `NavigatorCmd` 队列） |
| **输出消息** | `ROUTE_PLANNED`（发布到 `ControllerCmd` 队列） |
| **算法支持** | BFS（加权 Dijkstra）/ A*（加权 A*），通过工厂模式按名称选择 |
| **代价模型** | 未探索格子代价 = 1，已探索格子代价 = 5，引导路径优先穿过未探索区域 |
| **运行方式** | 独立进程，通过 RabbitMQ 消息驱动，Redis 黑板读写共享状态 |

**模块架构：**

```
Controller ---PLAN_ROUTE{carId, algorithm}---> NavigatorCmd 队列
                                                    |
                                           NavigatorMain.subscribe
                                                    |
                              1. 从 Redis 读取 carId 的 Position + Target
                              2. PathPlannerFactory.create(algorithm) 选择算法
                              3. planner.plan(start, target, bb) 执行搜索
                              4. 写前复查位置（规划期间车是否已移动）
                              5. bb.pushRoute(carId, route) — LPUSH 到 CarID:RouteList
                              6. 发送 ROUTE_PLANNED{carId, routeFound, routeLength} → ControllerCmd
```

**注意：Navigator 不写 `CarID:Status`**——小车的状态变更由 Controller 负责管理。

---

## 2. NavigatorMain —— 消息入口与主控流程

### 2.1 构造与启动

```java
public NavigatorMain(String redisHost, int redisPort, String mqHost, int mqPort)
```

| 参数 | 说明 |
|------|------|
| `redisHost` / `redisPort` | Redis 连接地址，用于实例化 `BlackboardClient` |
| `mqHost` / `mqPort` | RabbitMQ 连接地址，用于实例化 `MessageBus` |

`start()` 方法：
1. 连接 Redis（`BlackboardClient`）和 RabbitMQ（`MessageBus`）
2. 声明 `NavigatorCmd` 队列
3. 订阅 `NavigatorCmd` 队列，注册消息回调（Lambda 表达式）
4. 注册 JVM 关闭钩子，安全释放连接资源

默认地图尺寸为 `BlackboardClient.DEFAULT_WIDTH × DEFAULT_HEIGHT`，默认算法为 `"BFS"`。

### 2.2 消息订阅与分发

```java
messageBus.subscribe(QueueNames.NAVIGATOR_CMD, rawMessage -> {
    JSONObject msg = JSONObject.parseObject(rawMessage);
    String type = msg.getString("type");
    if (!MessageTypes.PLAN_ROUTE.equals(type)) {
        log.warn("[Navigator] 未知消息类型: {}", type);
        return;
    }
    String carId = msg.getString("carId");
    JSONObject data = msg.getJSONObject("data");
    String algorithm = extractAlgorithm(data);          // 默认 "BFS"
    boolean supervised = data != null && data.getBooleanValue("supervised", false);
    handlePlanRoute(bb, messageBus, carId, algorithm, supervised, tick);
});
```

消息字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | String | 消息类型，必须为 `"PLAN_ROUTE"` |
| `tick` | int | 当前仿真 tick 序号 |
| `carId` | String | 目标小车 ID（如 `"Car001"`） |
| `data.algorithm` | String | 算法名称：`"BFS"` 或 `"ASTAR"` |
| `data.supervised` | boolean | 是否已经过策略监督器优化（标记用） |

### 2.3 路径规划主流程（`handlePlanRoute`）

```
handlePlanRoute(bb, messageBus, carId, algorithm, supervised, tick):
    1. posOpt = bb.getCarPosition(carId)         // Redis CarID:Position
    2. targetOpt = bb.getCarTarget(carId)         // Redis CarID:Target
    3. 若 posOpt 或 targetOpt 为空 → 返回 ROUTE_PLANNED(routeFound=false)
    4. planner = PathPlannerFactory.create(algorithm)
    5. route = planner.plan(start, target, bb)   // 执行路径搜索
    6. 若 route 为空 → 返回 ROUTE_PLANNED(routeFound=false)
    7. 写前复查：重新读取 carId 的 Position
       - 若位置已变更 → 放弃本次结果，返回 ROUTE_PLANNED(routeFound=false)
    8. bb.pushRoute(carId, route)                // 写入 Redis
    9. 发送 ROUTE_PLANNED(routeFound=true, routeLength=route.size())
```

### 2.4 位置复查（写前一致性保证）

在路径计算完成后、写入 Redis 之前重新读取车辆位置：

```java
Point nowPos = bb.getCarPosition(carId).orElse(null);
if (nowPos == null || !nowPos.equals(start)) {
    // 规划期间车已移动，起点不准，放弃
    sendRoutePlanned(messageBus, carId, false, 0, tick);
    return;
}
```

防止并发竞态：如果在搜索期间小车被其他 tick 移动了，写入的路径将与实际位置脱节。

### 2.5 回复消息（`sendRoutePlanned`）

```java
Map<String, Object> data = Map.of(
    "carId", carId,
    "routeFound", routeFound,
    "routeLength", routeLength
);
String reply = MessageBuilder.build(MessageTypes.ROUTE_PLANNED, tick, carId, data);
messageBus.publish(QueueNames.CONTROLLER_CMD, reply);
```

| 字段 | 说明 |
|------|------|
| `carId` | 小车 ID |
| `routeFound` | 是否成功找到路径（boolean） |
| `routeLength` | 路径长度（格子数），失败时为 0 |

回复消息发布到 `ControllerCmd` 队列，由 Controller 统一收集和处理。

---

## 3. PathPlanner 接口（策略模式）

```java
@FunctionalInterface
public interface PathPlanner {
    /**
     * @param start  起点（不含在返回路径中）
     * @param target 终点（包含在返回路径中）
     * @param bb     黑板客户端，用于读取障碍物和地图尺寸
     * @return 路径点列表（不含 start，含 target），无路径时返回空列表
     */
    List<Point> plan(Point start, Point target, BlackboardClient bb);
}
```

- 返回值：`List<Point>`——从起点**之后**到终点的有序路径点列表；若不可达则返回**空列表**（不返回 `null`）
- 起点不在返回列表内，终点在其中

---

## 4. BfsPathFinder —— 加权 Dijkstra

```java
final class BfsPathFinder implements PathPlanner {
    @Override
    public List<Point> plan(Point start, Point target, BlackboardClient bb) {
        int width = bb.getMapWidth();
        int height = bb.getMapHeight();
        if (isOutOfBounds(target, width, height)) return List.of();

        boolean[][] blocked = bb.loadBlockedMapWithCars();   // 含车辆占位
        boolean[][] explored = bb.loadExploredBitmap();
        return ExplorationWeightedPathFinder.plan(
            start, target, blocked, explored, width, height,
            ExplorationWeightedPathFinder.SearchMode.WEIGHTED_DIJKSTRA);
    }
}
```

**算法要点：**

- 委托给 `ExplorationWeightedPathFinder`，搜索模式为 `WEIGHTED_DIJKSTRA`
- 使用优先队列（最小堆），按 `g(n)`（累计代价）排序
- **无权值（不使用启发式函数 `h(n)`）**，等价于加权 Dijkstra
- 代价模型见第 7 节
- 遍历四个方向邻居（上/下/左/右，不走斜角）
- 障碍物位图（`blocked`）包含静态障碍物和当前所有小车的占位格

---

## 5. AStarPathFinder —— 加权 A*

```java
final class AStarPathFinder implements PathPlanner {
    // 结构同 BfsPathFinder，仅 SearchMode 改为 WEIGHTED_ASTAR
    return ExplorationWeightedPathFinder.plan(
        start, target, blocked, explored, width, height,
        ExplorationWeightedPathFinder.SearchMode.WEIGHTED_ASTAR);
}
```

**与 BfsPathFinder 的区别：**

| 对比维度 | BfsPathFinder | AStarPathFinder |
|----------|---------------|-----------------|
| 搜索模式 | `WEIGHTED_DIJKSTRA` | `WEIGHTED_ASTAR` |
| 优先级函数 | `g(n)` | `g(n) + h(n)` |
| 启发式 `h(n)` | 无 | 曼哈顿距离 `|dx| + |dy|` |
| 代价模型 | 完全相同（未探索=1，已探索=5） | 完全相同 |
| 收敛速度 | 保证最优但搜索范围大 | 有方向性引导，通常更快收敛 |

在 `ExplorationWeightedPathFinder` 中，`SearchNode.priority()` 方法根据模式决定排序键：

```java
int priority(SearchMode mode, Point target) {
    if (mode == WEIGHTED_ASTAR) {
        return gCost + manhattan(new Point(col, row), target);
    }
    return gCost;  // WEIGHTED_DIJKSTRA
}
```

---

## 6. PathPlannerFactory —— 算法工厂

```java
final class PathPlannerFactory {
    private PathPlannerFactory() {}

    static PathPlanner create(AlgorithmType algorithm) {
        return switch (algorithm) {
            case BFS  -> new BfsPathFinder();
            case ASTAR -> new AStarPathFinder();
        };
    }

    static PathPlanner create(String algorithmName) {
        try {
            return create(AlgorithmType.valueOf(algorithmName.toUpperCase()));
        } catch (IllegalArgumentException e) {
            return new BfsPathFinder();  // 容错降级为 BFS
        }
    }
}
```

**设计要点：**

| 项目 | 说明 |
|------|------|
| 输入方式 | 支持 `AlgorithmType` 枚举和 `String` 名称两种重载 |
| 映射关系 | `BFS` → `BfsPathFinder`， `ASTAR` → `AStarPathFinder` |
| 容错降级 | 名称无法匹配任何枚举值时，默认返回 `BfsPathFinder`，保证系统不因配置错误崩溃 |
| 可扩展性 | 新增算法只需添加枚举值 + 实现类 + 工厂分支，调用方无需修改 |

**AlgorithmType 枚举（`common/.../model/AlgorithmType.java`）：**

```java
public enum AlgorithmType {
    BFS,    // 广度优先／加权 Dijkstra
    ASTAR   // A* 算法
}
```

---

## 7. 代价模型 —— ExplorationPathCosts

```java
public final class ExplorationPathCosts {
    public static final int UNEXPLORED_STEP_COST = 1;
    public static final int EXPLORED_STEP_COST = 5;

    public static int stepCost(boolean[][] explored, int col, int row) {
        return explored[row][col] ? EXPLORED_STEP_COST : UNEXPLORED_STEP_COST;
    }
}
```

| 常量 | 值 | 含义 |
|------|------|------|
| `UNEXPLORED_STEP_COST` | 1 | 经过**未探索**格子的单步代价 |
| `EXPLORED_STEP_COST` | 5 | 经过**已探索**格子的单步代价 |

**设计意图**：已探索区域代价是未探索的 5 倍，使得路径搜索算法天然偏好穿过未探索区域。5:1 的比例既足够大以产生明确的偏向，又不至于让算法完全拒绝已探索走廊（绕远路的代价可能超出 5:1 的差异）。

该代价模型同时应用于 `WEIGHTED_DIJKSTRA` 和 `WEIGHTED_ASTAR` 两种搜索模式，保证两种算法的路径质量基线一致。

---

## 8. 路由存储机制

### 8.1 写入：`pushRoute`

```lua
-- Lua 脚本（原子操作）
redis.call('DEL', KEYS[1])                              -- 清除旧路径
for i=1, #ARGV do
    redis.call('LPUSH', KEYS[1], ARGV[i])               -- 逆序 LPUSH
end
```

| 项目 | 说明 |
|------|------|
| Redis Key | `CarID:RouteList`（如 `Car001:RouteList`） |
| 数据结构 | Redis **List** |
| 元素格式 | JSON 字符串（`Point.toJson()` → `{"x":3,"y":5}`） |
| 写入顺序 | **逆序 LPUSH**（路径终点最先 push，起点第二个 push） |

**逆序 LPUSH 的结果**：路径起点在最靠近列表头的位置，终点在最靠近列表尾的位置（或相反，取决于视角）。关键是保证后续消费时 `RPOP` 取出的第一个元素就是小车需要走的下一个格子。

### 8.2 消费方式（CarAgent 侧）

CarAgent 每 tick 通过 `RPOP CarID:RouteList` 取出下一步目标格：

```
List 结构（左头右尾）:
  头 ←  step2, step1          尾
         (LPUSH 逆序写入后)

RPOP 从右端弹出 → step1 是下一步
下次 RPOP → step2
...
```

路径走完后 List 为空，CarAgent 发送 `ROUTE_DONE` 通知 Controller。

### 8.3 清除

```java
public void clearRoute(String carId) {
    jedis.del(carId + ":RouteList");
}
```

### 8.4 重要说明

- **Navigator 只写 `CarID:RouteList`，不写 `CarID:Status`**。小车的状态管理（如 `IDLE → MOVING`）完全由 Controller 负责。
- Navigator 也不写 `CarID:Target`，目标点由 TargetPlanner 写入。
- 每次新路径写入前会 DEL 旧 Key，保证不会残留旧路径数据。

---

## 9. 消息交互汇总

| 消息类型 | 方向 | 队列 | 触发条件 |
|----------|------|------|----------|
| `PLAN_ROUTE` | Controller → Navigator | `NavigatorCmd` | 每 tick 各车请求路径规划 |
| `ROUTE_PLANNED` | Navigator → Controller | `ControllerCmd` | 路径规划完成（成功或失败） |

**消息格式（`PLAN_ROUTE`）：**

```json
{
  "type": "PLAN_ROUTE",
  "tick": 42,
  "carId": "Car001",
  "data": {
    "algorithm": "BFS",
    "supervised": false
  }
}
```

**消息格式（`ROUTE_PLANNED`）：**

```json
{
  "type": "ROUTE_PLANNED",
  "tick": 42,
  "carId": "Car001",
  "data": {
    "carId": "Car001",
    "routeFound": true,
    "routeLength": 15
  }
}
```

---

## 10. 设计要点总结

1. **策略模式**：`PathPlanner` 接口 + 工厂类 `PathPlannerFactory`，算法可插拔扩展，调用方与具体算法解耦。

2. **统一搜索引擎**：BfsPathFinder 和 AStarPathFinder 均委托给 `ExplorationWeightedPathFinder`，两种算法共享代价模型、邻居扩展、路径重构等核心逻辑，仅优先级排序策略不同。

3. **5:1 加权代价**：已探索/未探索代价差异化是本模块的核心创新，使得路径天然偏向未探索区域，无需上层显式指定探索方向。

4. **写前复查**：规划完成后重新检查车辆位置，防止并发竞态导致路径与实际位置脱节。

5. **容错降级**：工厂对未知算法名称默认回退到 BFS，保证系统鲁棒性。

6. **职责边界清晰**：Navigator 只负责"算路径 → 存路径"，不写 `CarID:Status`，不写 `CarID:Target`，不进行 tick 调度。状态管理和调度逻辑由 Controller 统一负责。
