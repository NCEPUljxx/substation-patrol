# Controller 模块设计文档

> **模块**：`controller`（`com.substation.controller`）
> **Maven 坐标**：`com.substation:car-homework-controller`
> **入口类**：`ControllerMain`
> **Java 文件数**：4
> **角色**：系统唯一调度器——黑板架构中的调度者（Controller）

---

## 1. 模块概述

Controller 模块是**变电站巡检仿真系统**的唯一调度中心，实现黑板架构中的 **Scheduler** 角色。它以固定节拍（tick）驱动整车群状态机运转：每 tick 扫描 Redis 黑板发现所有活跃车辆，读取每辆车的 `CarStatus`，按状态分派处理（IDLE→分配目标、WAITING_ROUTE→触发寻路、READY→发送移动指令、MOVING→卡住检测、BLOCKED→超时检测），并通过 RabbitMQ 向各知识源模块（TargetPlanner、Navigator、Car、StrategySupervisor）下达命令。

**核心约束**：
- Controller 是**唯一调度者**，其他知识源模块仅响应 Controller 发出的 MQ 消息
- 知识源之间不允许直接通信，必须通过 Controller 中转
- 单实例运行（Redis SET NX 锁保证）

### 1.1 模块结构

```
com.substation.controller
├── ControllerMain       入口：启动连接、锁单实例、创建三大组件
├── StatusDispatcher     核心调度：车辆发现、状态机分派、消息收发、完成判定
├── TickScheduler        节拍定时器：ScheduledExecutorService 单线程驱动
└── CommandHandler       消息路由：解析 MQ JSON 消息 → 按 type 路由到对应组件
```

### 1.2 与其他模块的消息交互

```
                         ┌──────────────────────────┐
  Browser ──SET_CONFIG──→│                          │──ASSIGN_TARGET──→ TargetPlanner
  Browser ──RESET───────→│                          │←─TARGET_ASSIGNED─ TargetPlanner
  Browser ──TOGGLE_PAUSE→│       Controller         │──PLAN_ROUTE─────→ Navigator
  Browser ──TICK_INTERVAL│                          │←─ROUTE_PLANNED── Navigator
  Browser ──TOGGLE_OBS──→│   (唯一调度者)            │──TICK_MOVE──────→ Car×N
                         │                          │←─MOVED/BLOCKED/ROUTE_DONE─ Car×N
  TaskConfigurator        │                          │──SUPERVISE_ROUTE→ StrategySupervisor
    ──TASK_READY────────→│                          │←─ROUTE_OPTIMIZED─ StrategySupervisor
                         │                          │──FORWARD_CONFIG─→ TaskConfigurator
                         │                          │──FORWARD_RESET──→ TaskConfigurator
                         │                          │──REFRESH_ALL(fanout)→ Display
                         └──────────────────────────┘
```

---

## 2. ControllerMain —— 控制器主入口

**文件**：`ControllerMain.java`（80 行）

**职责**：初始化 Redis/MQ 连接、声明所有队列、创建三大组件并绑定消息监听、通过 Redis 锁保证单实例运行、注册 JVM 关闭钩子。

### 2.1 构造参数

```java
public ControllerMain(String redisHost, int redisPort, String mqHost, int mqPort)
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
 2. bb.acquireControllerLock()
    └─ Redis SET controller:instance 1 NX EX 30
    └─ 失败 → System.err + System.exit(1)  ← 防多实例
 3. new MessageBus(mqHost, mqPort, "guest", "guest")
 4. bus.connect() + 声明所有队列：
    ├─ declareControllerQueue()        → "ControllerCmd"
    ├─ purgeQueue("ControllerCmd")     ← 清空积压旧消息
    ├─ declareTargetPlannerQueue()     → "TargetPlannerCmd"
    ├─ declareNavigatorQueue()         → "NavigatorCmd"
    ├─ declareTaskConfigQueue()        → "TaskConfigCmd"
    ├─ declareStrategySupervisorQueue()→ "StrategySupervisorCmd"
    └─ declareFanoutExchange()         → "UpdateView" (fanout)
 5. new StatusDispatcher(bb, bus)
 6. new TickScheduler(dispatcher)      ← 单线程守护线程 "tick-scheduler"
 7. dispatcher.setScheduler(scheduler)
 8. new CommandHandler(bus, dispatcher, scheduler)
 9. bus.subscribe("ControllerCmd", handler::handle)
10. Runtime.addShutdownHook:
    ├─ scheduler.shutdown()
    ├─ bb.releaseControllerLock()     ← DEL controller:instance
    ├─ bus.close()
    └─ bb.close()
```

### 2.3 单实例锁

```java
// 实现位于 BlackboardClient.acquireControllerLock()
SetParams params = SetParams.setParams().nx().ex(30);
"OK".equals(jedis.set("controller:instance", "1", params))
```

- `NX`：仅当 key 不存在时设置（互斥）
- `EX 30`：30 秒 TTL，防止进程崩溃后锁永不释放
- 正常退出时通过 ShutdownHook 调用 `releaseControllerLock()` 主动释放

### 2.4 main() 入口

```java
public static void main(String[] args) throws Exception {
    var infra = InfraConnectionConfig.resolve(args);
    new ControllerMain(infra.redisHost(), infra.redisPort(), infra.mqHost(), infra.mqPort()).start();
    synchronized (ControllerMain.class) { ControllerMain.class.wait(); }
}
```

独立运行时从命令行参数或 `deploy/infra.local.json` 解析基础设施连接配置，启动后主线程阻塞等待。

---

## 3. StatusDispatcher —— 状态分派器（核心）

**文件**：`StatusDispatcher.java`（629 行）

**职责**：每个 tick 执行一次完整的调度循环——发现车辆、按状态分派、发送移动指令、判定任务完成。是系统最核心的调度逻辑所在。

### 3.1 核心常量和阈值

| 常量 | 值 | 说明 |
|------|-----|------|
| `EXPLORATION_COMPLETE` | 100 | 探索完成百分比阈值 |
| `ALL_IDLE_COMPLETE_TICKS` | 30 | 全车 IDLE 且无 pending 连续 tick 数，兜底强制完成 |
| `BLOCKED_TIMEOUT_MIN` / `MAX` | 2 / 5 | 阻塞随机超时范围（每车随机，打破多车互堵死锁） |
| `MOVING_STUCK_TICKS` | 2 | 连续处于 MOVING 状态超此时长则强制切 READY |
| `WAITING_ROUTE_TIMEOUT_TICKS` | 5 | 等待路径规划超时节拍数，超时回退 IDLE |
| `SUPERVISE_RATE_THRESHOLD` | 85 | 全局探索率 >= 85% 则跳过策略监督 |
| `SUPERVISE_COOLDOWN_TICKS` | 15 | 同一辆车两次监督间的最小 tick 间隔 |
| `DYNAMIC_OBSTACLE_INTERVAL` | 20 | 动态障碍物生成间隔（每 N tick 触发一次） |

### 3.2 并发安全设计

所有跨 tick 共享的集合均使用 `ConcurrentHashMap` / `ConcurrentHashMap.newKeySet()`，保证在 MQ 回调线程（CommandHandler）与 tick 调度线程（TickScheduler）之间的线程安全。

| 集合 | 类型 | 用途 |
|------|------|------|
| `pendingTargetRequests` | `Set<String>` | 已发送 ASSIGN_TARGET 等待 TargetPlanner 响应的 carId，防重复发送 |
| `pendingPlanRequests` | `Set<String>` | 已发送 PLAN_ROUTE 等待 Navigator 响应的 carId，防重复发送 |
| `pendingMoveRequests` | `Set<String>` | 已发送 TICK_MOVE 等待 Car ACK 的 carId，防跳格（同一车必须等 ACK 才发下一条 TICK_MOVE） |
| `awaitingSupervision` | `Set<String>` | 已发送 SUPERVISE_ROUTE 等待监督结果的 carId |
| `supervisedFlags` | `Set<String>` | 被监督器标记需优化路线的车辆集合 |
| `movingTickCounts` | `Map<String, Integer>` | 每车连续处于 MOVING 状态的 tick 计数 |
| `waitingRouteTickCounts` | `Map<String, Integer>` | 每车连续处于 WAITING_ROUTE 状态的 tick 计数 |
| `blockedTimeoutTicks` | `Map<String, Integer>` | 每车随机阻塞超时阈值（首次 BLOCKED 时在 2~5 间随机生成） |
| `lastSupervisedTick` | `Map<String, Integer>` | 每车上一次被监督的 tick 号（冷却控制） |
| `declaredCarQueues` | `Set<String>` | 已声明 MQ 队列的 carId（`ConcurrentHashMap.newKeySet()`），避免重复 declare |

### 3.3 调度主循环 `dispatch()`

每个 tick 由 `TickScheduler.tickLoop()` 调用一次，完整流程如下：

```
dispatch() （tick 循环）
│
├─ 1. 检查 taskActive，非活跃则直接返回
│
├─ 2. tick++ （全局节拍号递增）
│
├─ 3. 探索完成判定
│     isExplorationComplete()? → completeTask() → return
│
├─ 4. 车辆发现
│     bb.discoverCarIds() → Redis KEYS Car*:Status
│     carIds 为空 → return
│
├─ 5. 确保队列声明
│     ensureCarQueuesDeclared(carIds)
│     为未声明队列的 carId 调用 bus.declareCarQueue(carId)
│
├─ 6. 第一轮遍历：逐车状态分派 dispatchCar(carId, status)
│     对每辆车读取 bb.getCarStatus(carId)，按状态处理：
│
│     ├─ IDLE
│     │   探索未完成 → sendAssignTarget(carId)
│     │   向 TargetPlannerCmd 发送 ASSIGN_TARGET（防重：pendingTargetRequests）
│     │
│     ├─ WAITING_ROUTE
│     │   checkAndPlanRoute(carId):
│     │   · 若 awaitingSupervision 中 → 跳过
│     │   · 若 route 已在 Redis 中（Navigator 在消息到达前已写入）→ 直接切 READY
│     │   · waitingRouteTickCounts[carId]++，>= 5 → 超时回退 IDLE（清除 target + 移除锁）
│     │   · 未超时 → sendPlanRoute（防重：pendingPlanRequests）
│     │   向 NavigatorCmd 发送 PLAN_ROUTE（携带 start/target/algorithm/supervised）
│     │
│     ├─ MOVING
│     │   checkMovingStuck(carId):
│     │   movingTickCounts[carId]++，>= MOVING_STUCK_TICKS(2)
│     │   → 强制切 READY（重新触发移动）
│     │
│     ├─ BLOCKED
│     │   checkBlockedTimeout(carId):
│     │   首次 BLOCKED 为该车生成随机超时阈值（2~5）
│     │   (tick - blockedTick) >= 阈值 → 超时：
│     │     clearRoute + clearCarTarget + clearBlockedTick + 切 IDLE
│     │     + sendBlockedTimeout(carId) 通知小车
│     │
│     └─ READY
│        不在本轮处理，由第二轮 sendReadyCarMoves() 统一发送
│
│     每次 dispatchCar 开头清理不匹配的旧状态计数：
│     · status != MOVING → 清除 movingTickCounts
│     · status != WAITING_ROUTE → 清除 waitingRouteTickCounts
│     · status != READY && status != MOVING → 清除 pendingMoveRequests
│
├─ 7. 兜底：Navigator 已写入 route 但 ROUTE_PLANNED 消息丢失
│     遍历所有车：若 pendingPlanRequests 包含该车 且 route 非空
│     → 移除 pending + waiting 计数 → 直接切 READY
│
├─ 8. 第二轮：发送移动指令
│     sendReadyCarMoves(carIds):
│     遍历所有车 → 状态 == READY 且不在 awaitingSupervision 中
│     → trySendTickMove(carId)（防重：pendingMoveRequests）
│     → 向 Car_{carId} 发送 TICK_MOVE（携带 tick 号）
│     movesSent > 0 则等 ACK 后统一 broadcastRefresh
│     movesSent == 0 → 直接 broadcastRefresh
│
├─ 9. 全车 IDLE 兜底判定
│     carIds 中所有车状态 == IDLE 且 pendingTargetRequests 和 pendingPlanRequests 均为空
│     → allIdleTicks++
│     → >= ALL_IDLE_COMPLETE_TICKS(30) 且探索已完成 → completeTask()
│     否则 → allIdleTicks = 0
│
└─ 10. 再次检查
      taskActive && isExplorationComplete() → completeTask()
```

### 3.4 策略监督集成（cooldown-based）

当车辆路径规划成功（`onRoutePlanned` 回调）时触发：

```
onRoutePlanned(carId, routeFound=true):
│
├─ 1. 从 pendingPlanRequests 移除 carId
├─ 2. shouldSupervise(carId)?
│     条件：全局探索率 < 85% 且 距上次监督 >= 15 tick
│
├─ 满足条件 → 触发监督：
│   lastSupervisedTick[carId] = tick
│   awaitingSupervision.add(carId)
│   bb.setCarStatus(carId, READY)        ← 先切 READY（允许前端显示）
│   sendSuperviseRoute(carId)            ← 向 StrategySupervisorCmd 发送 SUPERVISE_ROUTE
│
└─ 不满足条件 → 直接切 READY
```

监督器回调处理：

- **`onRouteSupervisionFinished(carId)`**：监督器完成（含 SKIP）→ 从 awaitingSupervision 移除 → 切 READY
- **`onRouteOverlapReassign(carId)`**：监督器判定路线重合 → 清除路线/目标/阻塞标记/所有 pending 计数 → 切 IDLE（触发重新分配目标）

### 3.5 移动指令发送机制

```
sendReadyCarMoves(carIds):
  遍历所有车，对每辆 READY 车调用 trySendTickMove(carId)

trySendTickMove(carId):
  1. pendingMoveRequests.add(carId) ← 防重：同一车必须等 ACK 才发下一条 TICK_MOVE
     若已存在（上一拍发送但未收到 ACK）→ 返回 false，本拍不发送
  2. 构建消息 MessageTypes.TICK_MOVE + tick 号
  3. bus.publish(QueueNames.carQueue(carId), msg)
  4. 发送失败 → pendingMoveRequests.remove(carId)
```

ACK 机制：

- Car 收到 TICK_MOVE 执行移动后发 `MOVED` / `ROUTE_DONE` / `BLOCKED` → Controller `onMoveAcknowledged(carId)` 移除 pendingMoveRequests
- 当 `pendingMoveRequests.isEmpty()` → `broadcastRefresh()`（所有车当拍移动完成，刷新前端）
- 若有车发送了 TICK_MOVE 则等待 ACK 后才广播，避免前端收到中间态

### 3.6 任务生命周期管理

```
┌──────────────┐   SET_CONFIG    ┌──────────────┐
│  等待配置     │ ──────────────→ │  初始化中     │
│ (taskActive   │                │ prepareForNew │
│  = false)     │                │  Config()     │
└──────────────┘                └──────┬───────┘
                                      │ TASK_READY
                               ┌──────▼───────┐
                               │   运行中      │
                               │ (taskActive   │
                               │   = true)     │
                               └──────┬───────┘
                         ┌────────────┼────────────┐
                         │ 探索完成    │ RESET      │
                    ┌────▼────┐  ┌────▼────┐
                    │complete │  │ forward │
                    │ Task()  │  │ Reset() │
                    └─────────┘  └─────────┘
```

**关键方法**：

| 方法 | 触发条件 | 操作 |
|------|----------|------|
| `prepareForNewConfig()` | 收到 SET_CONFIG | `taskActive = false` + `clearPendingState()` |
| `onTaskReady()` | 收到 TASK_READY | 清空 pending → `taskActive = true` → `tick = 0` → 声明小车队列 → 应用 tick 间隔 |
| `completeTask()` | 探索完成 | 停止 scheduler → `taskActive = false` → 冻结所有车（clearRoute/clearTarget/clearBlockedTick/切 IDLE）→ 设置 `elapsedSeconds` → 广播最终状态 |
| `forwardReset()` | 收到 RESET | 停止 scheduler → `resetPaused()` → 转发 FORWARD_RESET 到 TaskConfigurator → `clearPendingState()` → `taskActive = false` |

### 3.7 `completeTask()` 收尾

```
completeTask():
  1. scheduler.stop()                     ← 停止节拍
  2. taskActive = false
  3. freezeAllCars():                     ← 冻结所有车辆
     对每个 carId: clearRoute + clearCarTarget + clearBlockedTick + 切 IDLE
  4. clearPendingState()
  5. bb.setTaskActive(false)
  6. 计算耗时 = (System.currentTimeMillis() - taskStartTime) / 1000
  7. bb.setElapsedSeconds(elapsed)
  8. 广播 REFRESH_ALL (fanout)             ← 前端收到后弹出保存窗口
```

### 3.8 消息收发汇总

| 发送方法 | 消息类型 | 目标队列 | 方向 |
|----------|----------|----------|------|
| `sendAssignTarget()` | `ASSIGN_TARGET` | `TargetPlannerCmd` | Controller → TargetPlanner |
| `checkAndPlanRoute()` | `PLAN_ROUTE` | `NavigatorCmd` | Controller → Navigator |
| `trySendTickMove()` | `TICK_MOVE` | `Car_{carId}` | Controller → Car |
| `sendBlockedTimeout()` | `BLOCKED_TIMEOUT` | `Car_{carId}` | Controller → Car |
| `sendSuperviseRoute()` | `SUPERVISE_ROUTE` | `StrategySupervisorCmd` | Controller → StrategySupervisor |
| `forwardConfig()` | `FORWARD_CONFIG` | `TaskConfigCmd` | Controller → TaskConfigurator |
| `forwardReset()` | `FORWARD_RESET` | `TaskConfigCmd` | Controller → TaskConfigurator |
| `broadcastRefresh()` | `REFRESH_ALL` | `UpdateView` (fanout) | Controller → Display（广播） |

### 3.9 回调方法（供 CommandHandler 调用）

| 回调方法 | 触发消息 | 处理逻辑 |
|----------|----------|----------|
| `onTargetAssigned(carId, success)` | `TARGET_ASSIGNED` | success → 切 WAITING_ROUTE；failure → 尝试完成判定 |
| `onRoutePlanned(carId, routeFound)` | `ROUTE_PLANNED` | routeFound → 监督判定 → 切 READY；notFound → 清 target 切 IDLE |
| `onMoveAcknowledged(carId)` | `MOVED` / `ROUTE_DONE` / `BLOCKED` | 移除 pendingMoveRequests → 全部 ACK 后 broadcastRefresh |
| `onRouteSupervisionFinished(carId)` | `ROUTE_OPTIMIZED` | 从 awaitingSupervision 移除 → 切 READY |
| `onRouteOverlapReassign(carId)` | `ROUTE_OPTIMIZED` (overlapReassign=true) | 清除所有状态 → 切 IDLE 重分配 |
| `markSupervised(carId)` | `ROUTE_OPTIMIZED` (optimized=true) | 标记 supervisedFlags |

---

## 4. TickScheduler —— 节拍调度器

**文件**：`TickScheduler.java`（93 行）

**职责**：以固定间隔驱动 `StatusDispatcher.dispatch()`，支持暂停/恢复/动态调速。

### 4.1 核心实现

```java
// 单线程守护线程执行器
executor = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "tick-scheduler");
    t.setDaemon(true);
    return t;
});

// 固定延迟调度（上一次执行完成后等待 intervalMs 再执行下一次）
future = executor.scheduleWithFixedDelay(this::tickLoop, 0, intervalMs, MILLISECONDS);
```

- 使用 `scheduleWithFixedDelay`（非 `scheduleAtFixedRate`），避免 dispatch 耗时超过间隔时任务堆积
- 线程名为 `"tick-scheduler"`，守护线程（JVM 退出时自动终止）

### 4.2 公开方法

| 方法 | 说明 |
|------|------|
| `start()` | 启动定时调度（内部调用 `schedule()`） |
| `stop()` | 取消当前定时任务（`future.cancel(false)`），不关闭线程池，允许再次 `start()` |
| `togglePause()` | 切换 `paused` 标志（`paused = !paused`） |
| `resetPaused()` | 重置 `paused = false`（供 `forwardReset` 调用） |
| `isPaused()` | 查询暂停状态 |
| `setInterval(int ms)` | 设置新间隔并立即重新提交定时任务（`schedule()` 内部先 cancel 旧的 future 再 scheduleWithFixedDelay） |
| `shutdown()` | `stop()` + `executor.shutdown()`（仅 JVM 退出时调用） |

### 4.3 节拍循环

```java
private void tickLoop() {
    if (paused) return;          // 暂停时跳过，无操作
    dispatcher.dispatch();        // 执行一次完整调度
}
```

### 4.4 默认间隔与调速

- 默认间隔：`DEFAULT_INTERVAL_MS = 500`（500ms）
- 前端通过 `SET_TICK_INTERVAL` 消息调速（范围 100~2000ms，由 CommandHandler 校验）
- 调速不仅更新 `TickScheduler.intervalMs`，还调用 `dispatcher.applyTickInterval(intervalMs)` 将新间隔持久化到 Redis `TaskConfig` Hash，并广播给所有观众页同步滑块

---

## 5. CommandHandler —— 命令处理器

**文件**：`CommandHandler.java`（123 行）

**职责**：订阅 `ControllerCmd` 队列，解析入站 JSON 消息，按 `type` 字段路由到对应组件（`StatusDispatcher` / `TickScheduler` / `MessageBus`）。

### 5.1 消息处理入口

```java
public void handle(String raw) {
    JSONObject msg = JSONObject.parse(raw);   // fastjson2 解析
    String type = msg.getString("type");
    String carId = msg.getString("carId");
    JSONObject data = msg.getJSONObject("data");
    switch (type) { ... }
}
```

### 5.2 完整消息路由表（11 种消息类型）

| 消息类型 | 发送者 | 参数来源 | 处理逻辑 |
|----------|--------|----------|----------|
| `TASK_READY` | TaskConfigurator | — | `dispatcher.onTaskReady()` → `scheduler.stop()` → `scheduler.resetPaused()` → `scheduler.start()` |
| `TARGET_ASSIGNED` | TargetPlanner | `data.success` | 检查 `!scheduler.isPaused() && dispatcher.isActive()` → `dispatcher.onTargetAssigned(carId, success)` |
| `ROUTE_PLANNED` | Navigator | `data.routeFound` | 检查 paused/active → `dispatcher.onRoutePlanned(carId, routeFound)` |
| `MOVED` | Car | `carId` | `dispatcher.onMoveAcknowledged(carId)` |
| `ROUTE_DONE` | Car | `carId` | 同 MOVED → `dispatcher.onMoveAcknowledged(carId)` |
| `BLOCKED` | Car | `carId` | `dispatcher.onMoveAcknowledged(carId)`（清除 pendingMoveRequests 防止卡住无法发下一条 TICK_MOVE） |
| `ROUTE_OPTIMIZED` | StrategySupervisor | `data.overlapReassign` / `data.optimized` | 检查 paused/active → `overlapReassign=true` → `onRouteOverlapReassign(carId)`；否则 `optimized=true` → `markSupervised(carId)` + `onRouteSupervisionFinished(carId)` |
| `SET_CONFIG` | Display (Browser) | `data` (完整配置 JSON) | `scheduler.stop()` → `dispatcher.prepareForNewConfig()` → `dispatcher.forwardConfig(data)` |
| `RESET` | Display (Browser) | — | `dispatcher.forwardReset()` |
| `TOGGLE_PAUSE` | Display (Browser) | — | `scheduler.togglePause()` |
| `SET_TICK_INTERVAL` | Display (Browser) | `data.interval` | 校验 `100 <= interval <= 2000` → `scheduler.setInterval(interval)` + `dispatcher.applyTickInterval(interval)` |
| `TOGGLE_OBSTACLE` | Display (Browser) | `data.row`, `data.col` | `dispatcher.toggleObstacle(row, col)`（切换指定格子的障碍物状态） |
| 其他 | — | — | 打印 `[Controller] 未知消息类型: {type}` |

### 5.3 暂停态消息过滤

以下消息类型在**暂停**或**任务非活跃**时会被忽略（直接 `break`）：
- `TARGET_ASSIGNED`
- `ROUTE_PLANNED`
- `ROUTE_OPTIMIZED`

这防止了暂停/重置期间到达的旧消息干扰当前调度状态。

### 5.4 tick 间隔校验

```java
private static final int MIN_TICK_INTERVAL_MS = 100;
private static final int MAX_TICK_INTERVAL_MS = 2000;

case SET_TICK_INTERVAL:
    int interval = data.getIntValue("interval");
    if (interval >= MIN_TICK_INTERVAL_MS && interval <= MAX_TICK_INTERVAL_MS) {
        scheduler.setInterval(interval);
        dispatcher.applyTickInterval(interval);
    }
```

---

## 6. 关键代码路径示例

### 6.1 从 IDLE 到 MOVING 的完整消息链

```
Controller: dispatchCar(carId, IDLE)
  → sendAssignTarget(carId)
    → pendingTargetRequests.add(carId)           // 防重
    → bus.publish("TargetPlannerCmd", ASSIGN_TARGET)

TargetPlanner: 分配目标
  → bb.setCarTarget(carId, target)
  → bus.publish("ControllerCmd", TARGET_ASSIGNED)

CommandHandler: 收到 TARGET_ASSIGNED
  → dispatcher.onTargetAssigned(carId, true)
    → pendingTargetRequests.remove(carId)
    → bb.setCarStatus(carId, WAITING_ROUTE)

下一个 tick: dispatchCar(carId, WAITING_ROUTE)
  → checkAndPlanRoute(carId)
    → pendingPlanRequests.add(carId)             // 防重
    → bus.publish("NavigatorCmd", PLAN_ROUTE)

Navigator: 规划路径
  → bb.pushRoute(carId, route)
  → bus.publish("ControllerCmd", ROUTE_PLANNED)

CommandHandler: 收到 ROUTE_PLANNED
  → dispatcher.onRoutePlanned(carId, true)
    → 可选的 shouldSupervise 检查
    → bb.setCarStatus(carId, READY)

同一 tick 第二轮: sendReadyCarMoves()
  → trySendTickMove(carId)
    → pendingMoveRequests.add(carId)             // 防跳格
    → bus.publish("Car_{carId}", TICK_MOVE)

Car: 执行移动
  → popNextRouteStep → 更新位置 → 点亮地图
  → bus.publish("ControllerCmd", MOVED)

CommandHandler: 收到 MOVED
  → dispatcher.onMoveAcknowledged(carId)
    → pendingMoveRequests.remove(carId)
    → pendingMoveRequests.isEmpty()? → broadcastRefresh()
```

### 6.2 阻塞检测与恢复

```
Car: 下一步被障碍物占据
  → clearRoute + clearTarget + 切 BLOCKED + 记录 BlockedTick
  → bus.publish("ControllerCmd", BLOCKED)

CommandHandler: 收到 BLOCKED
  → onMoveAcknowledged(carId)                    // 释放 TICK_MOVE 槽位

后续 tick: dispatchCar(carId, BLOCKED)
  → checkBlockedTimeout(carId)
    首次 → blockedTimeoutTicks[carId] = 2~5 随机值
    (tick - blockedTick) >= 阈值?
      → clearRoute + clearCarTarget + clearBlockedTick
      → 切 IDLE
      → sendBlockedTimeout(carId)
      → 下一 tick 触发 IDLE → ASSIGN_TARGET（重新分配目标）
```

---

## 7. 文件清单

| 文件 | 行数 | 核心职责 |
|------|------|----------|
| `ControllerMain.java` | 80 | 入口：连接中间件、锁单实例、创建组件、注册钩子 |
| `StatusDispatcher.java` | 629 | 核心调度：车辆发现、状态机分派、消息收发、完成判定 |
| `TickScheduler.java` | 93 | 节拍定时器：ScheduledExecutorService 单线程驱动 |
| `CommandHandler.java` | 123 | 消息路由：解析 MQ JSON 消息，按 type 路由到对应组件 |

---

*文档结束。*
