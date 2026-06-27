# TaskConfigurator 模块设计文档

> **模块**：`task-configurator`（`com.substation.taskconfigurator`）
> **Maven 坐标**：`com.substation:car-homework-task-configurator`
> **入口类**：`TaskConfiguratorMain`
> **Java 文件数**：2
> **角色**：黑板初始化器——黑板架构中的任务配置知识源（Knowledge Source），负责初始化 Redis 黑板并生成仿真场景

---

## 1. 模块概述

TaskConfigurator 模块是**变电站巡检仿真系统**的任务初始化知识源，实现黑板架构中的 **Knowledge Source** 角色。它仅在仿真启动前（FORWARD_CONFIG）和仿真重置（FORWARD_RESET）时被激活：**FORWARD_CONFIG** 时接收用户配置 JSON，在 Redis 黑板上完成地图障碍物生成、车辆初始化、可达性分析等全套场景铺设，完成后以 `TASK_READY` 回复 Controller 启动仿真；**FORWARD_RESET** 时清理仿真运行期数据，使黑板恢复到待配置状态。

**核心约束**：
- TaskConfigurator **仅接受 Controller 转发的命令**（`FORWARD_CONFIG` / `FORWARD_RESET`），**不直接处理来自 WebSocketBridge（Display/Browser）的消息**
- 订阅队列为 `TaskConfigCmd`，消息由 Controller 的 `CommandHandler` 在收到 `SET_CONFIG` / `RESET` 后转发写入
- 不参与仿真运行期的任何调度，不订阅车辆状态变更
- 地图尺寸固定为 `BlackboardClient.DEFAULT_WIDTH × DEFAULT_HEIGHT`（30×30）

### 1.1 模块结构

```
com.substation.taskconfigurator
├── TaskConfiguratorMain       入口：连接中间件、订阅 TASK_CONFIG_CMD、处理 FORWARD_CONFIG/FORWARD_RESET
└── TaskInitializer            场景初始化：生成障碍物、分配车辆初始位置、写入黑板、标记不可达格
```

### 1.2 与其他模块的消息交互

```
                              ┌──────────────────────────────────────┐
  Browser ──SET_CONFIG────→   │             Controller               │
  Browser ──RESET─────────→   │                                      │
                              │  (转发 FORWARD_CONFIG/FORWARD_RESET)  │
                              └──────────┬───────────────────────────┘
                                         │
                               FORWARD_CONFIG / FORWARD_RESET
                                         │
                              ┌──────────▼───────────────────────────┐
                              │         TaskConfigurator             │
                              │                                      │
                              │  · 订阅 TaskConfigCmd                 │
                              │  · 解析用户配置 JSON                  │
                              │  · 初始化 Redis 黑板                 │
                              │  · 生成障碍物、车辆、初始位置         │
                              │  · 回复 TASK_READY → ControllerCmd    │
                              └──────────────────────────────────────┘
```

**消息收发**：

| 方向 | 消息类型 | 队列 | 说明 |
|------|----------|------|------|
| Controller → TaskConfigurator | `FORWARD_CONFIG` | `TaskConfigCmd` | 转发用户仿真配置，触发场景初始化 |
| Controller → TaskConfigurator | `FORWARD_RESET` | `TaskConfigCmd` | 转发重置命令，清理仿真状态 |
| TaskConfigurator → Controller | `TASK_READY` | `ControllerCmd` | 场景初始化完成，通知 Controller 启动节拍 |

---

## 2. TaskConfiguratorMain —— 任务配置器主入口

**文件**：`TaskConfiguratorMain.java`（122 行）

**职责**：初始化 Redis/MQ 连接、声明 `TaskConfigCmd` 队列、订阅 `FORWARD_CONFIG` 和 `FORWARD_RESET` 消息、调度 `TaskInitializer` 完成场景初始化、注册 JVM 关闭钩子。

### 2.1 构造参数

```java
public TaskConfiguratorMain(String redisHost, int redisPort, String mqHost, int mqPort)
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
 4. bus.declareTaskConfigQueue()              → 声明 "TaskConfigCmd" 队列
 5. new TaskInitializer()                      → 创建场景初始化器（一次性创建，可重用）
 6. bus.subscribe("TaskConfigCmd", callback)   → 订阅配置命令队列
    ├─ type == "FORWARD_CONFIG" → handleConfig(initializer, data, tick)
    └─ type == "FORWARD_RESET"  → handleReset(tick)
 7. Runtime.addShutdownHook:
    ├─ bus.close()
    └─ bb.close()
```

**关键设计点**：
- `TaskInitializer` 在 `start()` 时创建一次并捕获为闭包变量，后续每次 `FORWARD_CONFIG` 复用同一实例
- 订阅回调中使用 `rawMessage` 解析 JSON，提取 `type`、`tick`、`data` 字段
- 仅识别 `FORWARD_CONFIG` 和 `FORWARD_RESET` 两种类型，其他消息记录 `WARN` 日志

### 2.3 main() 入口

```java
public static void main(String[] args) throws IOException, TimeoutException {
    var infra = com.substation.common.infra.InfraConnectionConfig.resolve(args);
    new TaskConfiguratorMain(
            infra.redisHost(), infra.redisPort(), infra.mqHost(), infra.mqPort()).start();
    synchronized (TaskConfiguratorMain.class) {
        try { TaskConfiguratorMain.class.wait(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
```

独立运行时从命令行参数或 `deploy/infra.local.json` 解析基础设施连接配置，启动后主线程阻塞等待。

---

## 3. FORWARD_CONFIG 处理流程

**文件**：`TaskConfiguratorMain.java` — `handleConfig()` 方法

```
handleConfig(initializer, data, tick)
│
├─ 1. 解析配置 JSON
│     data != null ? data.toJavaObject(Map.class) : Map.of()
│     若 data 为 null 则使用空 Map（全部取默认值）
│
├─ 2. selectiveClear()
│     └─ bb.clearSimulationState()   ← 清除上轮仿真运行时数据
│
├─ 3. initializer.initialize(bb, config)
│     └─ 详见第 4 节 TaskInitializer
│
├─ 4. recordRunStarter(config)
│     └─ 提取 config.operator → bb.beginSimRun(startedBy)
│        · 记录仿真发起者标识到 Redis
│
├─ 5. 日志输出
│     log.info("[TaskConfigurator] 初始化完成")
│
└─ 6. 回复 TASK_READY
      MessageBuilder.build(MessageTypes.TASK_READY, tick)
      bus.publish(QueueNames.CONTROLLER_CMD, reply)
      log.info("[TaskConfigurator] 已发送 TASK_READY，车辆数={}", carCount)
```

### 3.1 selectiveClear() —— 选择性清理

```java
private void selectiveClear() {
    bb.clearSimulationState();
}
```

`clearSimulationState()` 清除的是 Redis 中**仿真运行时产生的数据**（车辆位置、状态、路线、历史记录等），而**不删除** `TaskConfig` 配置项。这确保了在 FORWARD_RESET 后黑板恢复到待配置状态，但不会丢失已写入的配置 Hash。

### 3.2 recordRunStarter() —— 仿真发起者记录

```java
private void recordRunStarter(Map<String, Object> config) {
    Object operator = config.get("operator");
    String startedBy = operator == null ? null : String.valueOf(operator);
    bb.beginSimRun(startedBy);
}
```

从用户配置中提取 `operator` 字段（可选），写入 Redis 作为仿真发起者标识。若未提供则 `startedBy` 为 `null`。

---

## 4. TaskInitializer —— 场景初始化器

**文件**：`TaskInitializer.java`（238 行）

**职责**：根据用户配置参数，在 Redis 黑板上执行完整的场景铺设——写入任务配置、生成随机障碍物、分配车辆初始位置、初始化每辆车、点亮初始区域、标记不可达格。

**访问级别**：`package-private`（仅 `com.substation.taskconfigurator` 包内可见），不对外暴露。

### 4.1 默认配置常量

| 常量 | 值 | 说明 |
|------|-----|------|
| `DEFAULT_MAP_WIDTH` / `DEFAULT_MAP_HEIGHT` | 30 × 30 | 地图尺寸（与 BlackboardClient 默认值一致） |
| `DEFAULT_CAR_COUNT` | 5 | 默认小车数量 |
| `DEFAULT_OBSTACLE_RATIO` | 0.15 | 默认障碍物占比（15%） |
| `DEFAULT_ALGORITHM` | `"BFS"` | 默认寻路算法 |
| `DEFAULT_TICK_INTERVAL` | 500 | 默认节拍间隔（毫秒） |
| `EDGE_MARGIN` | 1 | 地图边界保留格数（不放置障碍物/车辆） |
| `FIXED_SPAWN_LAYOUT_CAR_COUNT` | 5 | 固定位置出生的车辆数阈值 |
| `OBSTACLE_PLACEMENT_MULTIPLIER` | 10 | 障碍物放置最大尝试倍数 |

### 4.2 初始化主流程 `initialize()`

```
initialize(bb, config)
│
├─ 步骤 1：解析配置参数
│     parseIntParam / parseDoubleParam / parseStringParam
│     对每个参数：若 config 中存在且有效则使用，否则取默认值
│     参数列表：mapWidth, mapHeight, carCount, obstacleRatio, algorithm, tickInterval
│
├─ 步骤 2：writeTaskConfig()
│     将解析后的配置写入 Redis Hash "TaskConfig"：
│       mapWidth, mapHeight, carCount, obstacleRatio, algorithm, tickInterval, active=true
│     └─ bb.initTaskConfig(config)
│
├─ 步骤 3：generateCarIds(carCount)
│     Car001, Car002, ..., CarNNN（3 位零填充）
│
├─ 步骤 4：placeObstacles()
│     在内部区域（排除边界 1 格）随机放置障碍物
│     目标数量 = (width-2)(height-2) × obstacleRatio
│     最大尝试次数 = 目标数量 × 10（防止死循环）
│     排除车辆起始位置（此时为空集 Set.of()）
│     写入 Redis: bb.writeBlockBitmap(obstacles, width)
│
├─ 步骤 5：assignInitialPositions()
│     ├─ carCount ≤ 5 → assignCornerAndCenterPositions()
│     │   四个角 + 中心点（共 5 个预设位置），逐个尝试
│     │   若预设位置被障碍物占据 → fallback（SpawnPositionSelector）
│     │
│     └─ carCount > 5 → assignExplorationWeightedPositions()
│         使用 SpawnPositionSelector.selectBest() 为每辆车选择探索加权最优出生点
│         确保各车出生点分散、不重叠
│
├─ 步骤 6：逐车初始化
│     for each carId:
│       initSingleCar(bb, carId, position):
│         · bb.setCarPosition(carId, position)
│         · bb.setCarStatus(carId, IDLE)
│         · bb.setCarSteps(carId, 0)
│         · bb.setCarEffectiveSteps(carId, 0)
│         · bb.appendCarHistory(carId, position, 0)
│       lightUpArea(bb, position, mapWidth, mapHeight):
│         · bb.recordExploration(0, row, col)  ← 点亮初始探索记录
│
└─ 步骤 7：markSealedUnreachableCells()
      ReachabilityAnalyzer.findSealedFreeCells(obstacles, carStartPositions)
      分析所有车辆起始位置是否可达各空闲格
      将不可达的空闲格标记为 sealed
      bb.writeSealedBitmap(sealed, mapWidth)
```

### 4.3 配置参数解析

三个解析方法均为防御式设计：支持 `Number` 和 `String` 两种类型，解析失败时静默回退到默认值。

```java
parseIntParam(config, key, defaultValue):
  value instanceof Number n → return n.intValue()
  value instanceof String s → try Integer.parseInt(s), catch → default
  else → default

parseDoubleParam(config, key, defaultValue):
  value instanceof Number n → return n.doubleValue()
  value instanceof String s → try Double.parseDouble(s), catch → default
  else → default

parseStringParam(config, key, defaultValue):
  value != null → value.toString()
  else → default
```

### 4.4 障碍物生成 `placeObstacles()`

```
placeObstacles(bb, width, height, ratio, exclude):
│
├─ 计算内部区域格数: interiorCells = (width - 2) × (height - 2)
├─ 计算目标障碍物数: targetCount = (int)(interiorCells × ratio)
├─ 随机放置循环（最多 targetCount × 10 次尝试）:
│     x = EDGE_MARGIN + random.nextInt(width - 2 × EDGE_MARGIN)
│     y = EDGE_MARGIN + random.nextInt(height - 2 × EDGE_MARGIN)
│     若不在 exclude 集合 且 该格尚未放置障碍物:
│       obstacles[y][x] = true
│       placed++
│     placed >= targetCount → 退出
│
└─ bb.writeBlockBitmap(obstacles, width)   ← 写入 Redis 位图
```

**关键约束**：
- 障碍物仅放置在内部区域 `[1..width-2] × [1..height-2]`，边界 1 格保证为空白（确保边缘可达性）
- `MAX_ATTEMPTS = targetCount × 10` 防止高障碍物比例时无限循环
- `exclude` 参数预留了排除特定位置的能力（当前调用时传入空集）

### 4.5 车辆初始位置分派

#### 4.5.1 固定位置模式（carCount ≤ 5）

```
assignCornerAndCenterPositions(carCount, mapWidth, mapHeight, obstacles):
│
├─ 预设 5 个优先位置（按顺序）:
│     [0] 左上角: (1, 1)
│     [1] 右上角: (width-2, 1)
│     [2] 左下角: (1, height-2)
│     [3] 右下角: (width-2, height-2)
│     [4] 中心点:  (width/2, height/2)
│
├─ 维护 occupied[][] 标记已分配位置
│
├─ 对前 carCount 个预设位置逐一处理:
│     resolvePreferredSpawn(preferred, obstacles, occupied):
│       检查该格是否在边界内、无障碍物、未被占用
│       满足 → 采用此位置
│       不满足 → pickFallbackSpawn(obstacles, occupied)
│         调用 SpawnPositionSelector.selectBest() 寻找备用位置
│
└─ 返回位置列表
```

#### 4.5.2 探索加权模式（carCount > 5）

```
assignExplorationWeightedPositions(carCount, mapWidth, mapHeight, obstacles):
│
├─ 维护三个二维布尔数组:
│     explored[][]  — 已探索记录
│     sealed[][]    — 不可达标记
│     occupied[][]  — 已分配车位（防重叠）
│
├─ 逐车分配:
│     for i = 0 to carCount-1:
│       chosen = SpawnPositionSelector.selectBest(obstacles, explored, occupied, sealed, EDGE_MARGIN, random)
│       positions.add(chosen)
│       occupied[chosen.y()][chosen.x()] = true
│
└─ 返回位置列表
```

`SpawnPositionSelector.selectBest()` 基于探索加权算法，优先选择距离已分配车辆最远的空闲格，确保多车时出生点在整个地图内均匀分散。

### 4.6 车辆初始化 `initSingleCar()`

```
initSingleCar(bb, carId, position):
  bb.setCarPosition(carId, position)       ← 写入初始坐标
  bb.setCarStatus(carId, CarStatus.IDLE)   ← 状态设为 IDLE
  bb.setCarSteps(carId, 0)                 ← 总步数归零
  bb.setCarEffectiveSteps(carId, 0)        ← 有效步数归零
  bb.appendCarHistory(carId, position, 0)  ← 追加初始历史轨迹点
```

每辆车初始化为 `IDLE` 状态，由 Controller 在 `TASK_READY` 后开始节拍调度（IDLE → ASSIGN_TARGET → WAITING_ROUTE → ...）。

### 4.7 初始区域点亮 `lightUpArea()`

```java
private void lightUpArea(BlackboardClient bb, Point center, int mapWidth, int mapHeight) {
    int row = center.y();
    int col = center.x();
    if (row >= 0 && row < mapHeight && col >= 0 && col < mapWidth) {
        bb.recordExploration(0, row, col);
    }
}
```

为每辆车的初始位置调用 `bb.recordExploration(0, row, col)`，点亮该格。注意此方法**仅点亮车辆所在格**（1×1），而非字面上的 3×3 区域——`recordExploration` 记录的是该格在 tick=0 时已被该车探索，后续 Car 模块的移动逻辑中会在每步到达新位置时再次调用以扩展点亮范围。

### 4.8 不可达格标记 `markSealedUnreachableCells()`

```
markSealedUnreachableCells(bb, mapWidth, mapHeight, carStartPositions, obstacles):
│
├─ sealed = ReachabilityAnalyzer.findSealedFreeCells(obstacles, carStartPositions)
│     以所有车辆起始位置为起点，执行连通性分析（BFS/DFS）
│     所有空闲但无法从任一车辆起始位置到达的格子 → 标记为 sealed
│
└─ bb.writeSealedBitmap(sealed, mapWidth)   ← 写入 Redis sealed 位图
```

这确保 TargetPlanner 在分配目标时不会选择车辆永远无法到达的格子。

---

## 5. FORWARD_RESET 处理流程

**文件**：`TaskConfiguratorMain.java` — `handleReset()` 方法

```
handleReset(tick):
│
├─ 1. selectiveClear()
│     └─ bb.clearSimulationState()
│        清除：车辆位置、状态、路线、历史记录、步数、阻塞标记等
│        保留：TaskConfig 配置 Hash（mapWidth, carCount 等参数）
│
└─ 2. bb.clearSimRunMetadata()
      └─ 清除：仿真发起者、开始时间、耗时等元数据
```

**与 FORWARD_CONFIG 的对比**：

| 操作 | FORWARD_CONFIG | FORWARD_RESET |
|------|---------------|---------------|
| `selectiveClear()` | 是 | 是 |
| `TaskInitializer.initialize()` | 是 | 否 |
| `recordRunStarter()` | 是 | 否 |
| `clearSimRunMetadata()` | 否 | 是 |
| 回复 `TASK_READY` | 是 | 否 |

FORWARD_RESET 仅清理仿真运行时状态和元数据，**不重新生成场景**，不回复 `TASK_READY`。Controller 在 `forwardReset()` 中已停止节拍并设置 `taskActive = false`，此后用户可通过前端点击"开始"重新触发 SET_CONFIG → FORWARD_CONFIG 流程。

---

## 6. 消息安全约束

### 6.1 仅接受 Controller 转发

TaskConfigurator 订阅的队列是 `TaskConfigCmd`，该队列**唯一的生产者是 Controller**。前端（Browser/Display）通过 WebSocket 发送 `SET_CONFIG` 和 `RESET` 消息到达 Controller 的 `ControllerCmd` 队列，由 `CommandHandler` 解析后转发：

```
Browser → WebSocket → Display → MQ:ControllerCmd(SET_CONFIG)
                                    ↓
                              CommandHandler.handle()
                                    ↓
                              StatusDispatcher.forwardConfig(data)
                                    ↓
                              bus.publish("TaskConfigCmd", FORWARD_CONFIG)
                                    ↓
                              TaskConfigurator.handleConfig()
```

**TaskConfigurator 绝不直接消费来自 WebSocketBridge 的消息**，所有配置命令必须经 Controller 转发，确保调度时序受控。

### 6.2 消息类型识别

```java
if (MessageTypes.FORWARD_CONFIG.equals(type)) {
    handleConfig(initializer, data, tick);
} else if (MessageTypes.FORWARD_RESET.equals(type)) {
    handleReset(tick);
} else {
    log.warn("[TaskConfigurator] 未知消息类型: {}", type);
}
```

仅识别两种类型，未识别的消息仅记录警告日志，不抛出异常（保证订阅回调的健壮性）。

---

## 7. 关键代码路径示例

### 7.1 完整仿真启动流程

```
浏览器点击"开始仿真"
  ↓
WebSocketBridge → Display → MQ:ControllerCmd (SET_CONFIG, data={...})
  ↓
Controller.CommandHandler.handle()
  case SET_CONFIG:
    scheduler.stop()
    dispatcher.prepareForNewConfig()     ← taskActive=false, clearPendingState
    dispatcher.forwardConfig(data)
      bus.publish("TaskConfigCmd", FORWARD_CONFIG)
  ↓
TaskConfiguratorMain 回调:
  handleConfig(initializer, data, tick):
    selectiveClear()                     ← 清除上一轮仿真数据
    initializer.initialize(bb, config)    ← 全套场景铺设（见 4.2 节）
    recordRunStarter(config)             ← 记录发起者
    bus.publish("ControllerCmd", TASK_READY)
  ↓
Controller.CommandHandler.handle()
  case TASK_READY:
    dispatcher.onTaskReady()
      clearPendingState()
      taskActive = true
      tick = 0
      ensureCarQueuesDeclared()
      scheduler.start()                  ← 启动节拍循环
  ↓
仿真运行中 ...
```

### 7.2 重置流程

```
浏览器点击"重置"
  ↓
Controller.CommandHandler.handle()
  case RESET:
    dispatcher.forwardReset()
      scheduler.stop()
      resetPaused()
      bus.publish("TaskConfigCmd", FORWARD_RESET)
      clearPendingState()
      taskActive = false
  ↓
TaskConfiguratorMain 回调:
  handleReset(tick):
    selectiveClear()                     ← 清除仿真运行时数据
    bb.clearSimRunMetadata()            ← 清除元数据
  ↓
(不回复 TASK_READY，系统回到 "等待配置" 状态)
```

---

## 8. 文件清单

| 文件 | 行数 | 核心职责 |
|------|------|----------|
| `TaskConfiguratorMain.java` | 122 | 入口：连接中间件、订阅命令队列、分发 FORWARD_CONFIG/FORWARD_RESET、回复 TASK_READY |
| `TaskInitializer.java` | 238 | 场景初始化：解析配置、生成障碍物、分配车辆初始位置、初始化车辆、点亮区域、标记不可达格 |

---

*文档结束。*
