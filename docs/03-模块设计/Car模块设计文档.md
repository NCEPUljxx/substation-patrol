# Car 模块设计文档

---

## 1. 模块概述

Car 模块是智能小车协作探索系统中的**车辆执行单元**。系统支持多辆小车并发运行，每辆小车是一个**独立的 JVM 进程**，通过 Redis 黑板和 RabbitMQ 消息总线与其他模块协作。

### 1.1 核心特点

| 特性 | 说明 |
|------|------|
| **独立进程** | 每辆小车独立 JVM，互不干扰，支持并行运行 |
| **自注册机制** | 启动时可自行在 Redis 黑板注册位置和状态，无需依赖 TaskConfigurator |
| **事件驱动** | 通过 RabbitMQ 订阅 `Car_{carId}` 专属队列，被动响应 Controller 指令 |
| **分布式锁** | 每步移动加锁（`lock:CarID`），保证黑板写入原子性 |
| **位置预约** | 移动前用 Redis `SET NX EX` 预约目标格，防止多车重叠 |

### 1.2 模块依赖

Car 模块依赖 `common` 模块中的以下组件：

```
common
├── model/Point          坐标
├── model/CarStatus      状态枚举
├── redis/BlackboardClient   黑板客户端
├── redis/DistributedLock    分布式锁
├── mq/MessageBus        消息总线
├── mq/MessageTypes      消息类型常量
├── mq/MessageBuilder    消息构建器
├── mq/QueueNames        队列命名工具
├── map/SpawnPositionSelector   出生点选择算法
└── infra/InfraConnectionConfig 基础设施连接配置
```

### 1.3 源文件清单

| 文件 | 职责 |
|------|------|
| `CarMain.java` | 模块入口：连接初始化、自注册、组件装配、CLI 参数解析 |
| `CarAgent.java` | 消息代理：接收 MQ 消息，分发到 MoveExecutor 或本地处理 |
| `MoveExecutor.java` | 移动执行器：14 步原子移动流程，状态机核心 |

---

## 2. CarMain —— 模块入口

### 2.1 启动方式

**独立 JVM 运行**（CLI 参数）：

```bash
java -jar car.jar Car001              # TaskConfigurator 预注册车辆
java -jar car.jar Car004 --dynamic    # 页面动态添加，跳过注册等待
java -jar car.jar Car002 6380         # 指定 Redis 端口
```

**Launcher 调用方式**：

```java
new CarMain("Car001", "localhost", 6379, "localhost", 5672).start();
```

### 2.2 CLI 参数解析

| 参数位置 | 含义 | 示例 |
|----------|------|------|
| 第 1 个非 `--` 参数 | 车辆 ID | `Car003` 或 `003`（自动补全 `Car` 前缀） |
| `--dynamic` | 动态添加模式，跳过 TaskConfigurator 的 5 秒注册等待 | `--dynamic` |
| Redis 端口 | 覆盖默认 6379 端口 | `6380` |

- 默认 `carId` 为 `Car001`
- `--` 开头的参数视为选项参数，不参与 carId 解析

### 2.3 start() 启动流程

```
1. 创建 BlackboardClient(redisHost, redisPort, mapW, mapH)
2. 读取地图尺寸 (getMapWidth / getMapHeight)
3. selfRegister(bb, carId, mapW, mapH, dynamicAdd)    ← 自注册
4. 获取 JedisPool 共享连接池
5. 创建 MessageBus 并连接 RabbitMQ
6. 声明专属队列 Car_{carId}
7. 创建 MoveExecutor(carId, bb, mb, sharedPool)
8. 创建 CarAgent(carId, bb, moveExecutor)
9. 订阅 Car_{carId} 队列，回调 → agent.handleMessage
10. 注册 JVM 关闭钩子（释放 mb / bb 资源）
```

### 2.4 自注册逻辑（selfRegister）

```
┌─ 是否 --dynamic？
│   ├─ 是 → bb.getCarStatus(carId) 已存在？
│   │        ├─ 是 → 接管现有关卡（重置 IDLE、清 target、清 route）
│   │        └─ 否 → 跳过等待，执行自初始化
│   └─ 否 → awaitTaskConfiguratorRegistration（轮询 200ms × 25 次 = 5s）
│             ├─ 已注册 → 直接使用，日志输出当前状态
│             └─ 超时 → 执行自初始化
│
└─ 自初始化：
    1. findSpawnPosition → SpawnPositionSelector 选择最优出生点
    2. bb.setCarPosition / setCarStatus(IDLE) / setCarSteps(0)
    3. illuminateAndHeat → recordExploration + incrementMapHeat
    4. bb.appendCarHistory
```

**出生点选择**（`findSpawnPosition`）：
- 加载障碍物位图、已探索位图、密封区域位图
- 构建其他车辆占据网格（`buildOccupiedGrid`）
- 调用 `SpawnPositionSelector.selectBest`（优选地图内部，EDGE_MARGIN=1）
- 内部无可用点则回退到边距 0 再尝试一次

---

## 3. CarAgent —— 消息代理

### 3.1 职责

CarAgent 是 MQ 消息到本地逻辑的分发层，不做黑板写入（除 MoveExecutor 调用外），保持轻量。

### 3.2 消息分发（handleMessage）

```
收到 JSON 消息
  ├─ 解析 type / tick 字段
  ├─ type == "TICK_MOVE"      → moveExecutor.executeMove(tick)
  ├─ type == "BLOCKED_TIMEOUT" → handleBlockedTimeout()
  └─ 其他                      → log.warn 丢弃
```

### 3.3 BLOCKED_TIMEOUT 处理

Controller 在检测到车辆阻塞超时后发送此消息。CarAgent 的处理：

1. 日志 WARN 记录 `"阻塞超时已由 Controller 处理"`
2. 从 Redis 读取自身 Status，确认 Controller 已完成清理
3. **不做任何黑板写入**，等待 Controller 重新分配目标

```java
Optional<CarStatus> status = bb.getCarStatus(carId);
status.ifPresentOrElse(
    s -> log.info("当前状态: {}，等待重新分配目标", s.chineseName()),
    () -> log.warn("状态 Key 不存在"));
```

---

## 4. MoveExecutor —— 移动执行器

### 4.1 概述

MoveExecutor 是 Car 模块的核心，实现**原子移动操作**。每次 `executeMove(tick)` 调用对应 Controller 的一个节拍（tick）。

### 4.2 获取锁

```
executeMove(tick)
  ├─ new DistributedLock(pool, carId)
  ├─ lock.tryLock()
  │    ├─ 成功 → doMove(tick)
  │    └─ 失败 → ackMoveDeferred(tick)（通知 Controller 让出节拍）
  └─ finally → lock.unlock()
```

- 锁 Key：`lock:CarID`
- 获取锁失败时**静默跳过**，等待下一节拍重试

### 4.3 doMove —— 14 步原子移动流程

```
 1. 检查状态 == READY，否则返回
     └─ 设置状态为 MOVING（心跳，防止 Controller 误判僵死）

 2. peekNextRouteStep → 预览路径下一步
     └─ RouteList 为空 → 切回 IDLE 并返回

 3. 卡住计数更新
     └─ 同目标连续计数；目标变化则重置计数为 1

 4. tryReservePosition(nx, ny, carId)
     └─ Redis: SET pos:reserve:{row}:{col} <carId> NX EX 3
     └─ 预约失败 → handleStuckRetryOrReplan

 5. isBlocked(ny, nx) → 检查目标格是否为障碍物
     └─ 是 → clearRoute + clearTarget + 切 BLOCKED + sendBlocked

 6. isOccupiedByOtherCar(nx, ny) → 检查目标格是否被其他车辆占据
     └─ 是 → handleStuckRetryOrReplan

 7. popNextRouteStep → 从 RouteList 消费头部路径点
     └─ RPOP redisList

 8. setCarPosition(carId, nextPos) → 更新车辆坐标
     └─ 旧 mapBlock 由 updatePosition 内部清除，新 mapBlock 标记

 9. illuminateAndHeat(nextPos, tick)
     └─ recordExploration(tick, row, col) → 3×3 SETBIT mapView
     └─ incrementMapHeat(row, col)       → INCR mapHeat
     └─ 返回是否首次探索（新格 → incrementCarEffectiveSteps）

10. incrementCarSteps → 总步数 +1

11. appendCarHistory(carId, nextPos, tick) → 追加移动历史

12. releaseReservePosition(nx, ny, carId)
     └─ DEL pos:reserve:{row}:{col}

13. 路径状态判定：
     ├─ RouteList 还有步数 → setCarStatus(READY) + sendMoved
     └─ RouteList 已空      → setCarStatus(IDLE)  + sendRouteDone

14. 异常路径：
     ├─ 障碍物阻塞  → BLOCKED + record blockedTick + sendBlocked
     └─ 连续卡住3次 → BLOCKED + 同上
```

### 4.4 位置预约锁（防重叠）

- 格式：`pos:reserve:{row}:{col}` → 值为 `carId`
- Redis 命令：`SET pos:reserve:{row}:{col} <carId> NX EX 3`
- NX：仅当 Key 不存在时设置（先到先得）
- EX 3：3 秒 TTL，防止死锁
- 预约失败时回退 READY，等待下一 tick 重试
- 移动完成后在 finally 中释放（`DEL`）

### 4.5 卡住重试与切 BLOCKED

- 同一目标格连续失败计数（包括预约失败和占据检测）
- 计数 < 3：退回 READY，下一 tick 重试
- 计数 >= 3：清 route + 清 target + 切 BLOCKED + sendBlocked
- `MAX_RESERVE_RETRIES = 3`

### 4.6 消息通知

| 方法 | 消息类型 | 目标队列 | 触发条件 |
|------|---------|---------|---------|
| `sendMoved` | MOVED | `controller_cmd` | 移动成功，路径未走完 |
| `sendRouteDone` | ROUTE_DONE | `controller_cmd` | 路径走完，到达终点 |
| `sendBlocked` | BLOCKED | `controller_cmd` | 目标格为障碍物或卡住 3 次 |
| `ackMoveDeferred` | MOVED（deferred=true） | `controller_cmd` | 锁获取失败或预约失败，让出节拍 |

---

## 5. 状态机设计

### 5.1 五状态定义

| 枚举值 | 中文名 | 颜色 | 含义 | 设置者 |
|--------|--------|------|------|--------|
| `IDLE` | 空闲 | `#9E9E9E` | 无目标，等待 Controller 分配 | CarMain / MoveExecutor / Controller |
| `WAITING_ROUTE` | 等待路径 | `#FF9800` | 已分配目标，等待 Navigator 规划路径 | Controller |
| `READY` | 就绪 | `#4CAF50` | 路径就绪，等待 TICK_MOVE 指令 | Controller / MoveExecutor |
| `MOVING` | 移动中 | `#2196F3` | 正在执行移动操作 | MoveExecutor（心跳状态） |
| `BLOCKED` | 受阻 | `#F44336` | 路径受阻，等待 Controller 处理 | MoveExecutor |

### 5.2 状态转换图

```
                    Controller 分配目标
    IDLE ──────────────────────────────────→ WAITING_ROUTE
     ↑                                          │
     │ ROUTE_DONE                    Navigator 路径就绪
     │ (路径走完)                           │
     │          ┌────────────────────────────┘
     │          ↓
     │        READY ←──────────────────────────────┐
     │          │                                   │
     │          │ TICK_MOVE (MoveExecutor)          │
     │          ↓                                   │
     │       MOVING ────→ 路径还有步,切READY ───────┘
     │          │
     │          ├──→ 障碍物/卡住3次 → BLOCKED
     │          │                         │
     │          │              Controller 超时处理
     │          │                         │
     └──────────┴─────────────────────────┘
                (Controller 重新分配目标)
```

### 5.3 状态转换表

| 当前状态 | 事件 | 下一状态 | 触发模块 |
|----------|------|---------|---------|
| `IDLE` | Controller 分配目标 | `WAITING_ROUTE` | Controller |
| `WAITING_ROUTE` | Navigator 路径就绪 | `READY` | Controller（检测到路由存在） |
| `READY` | 收到 TICK_MOVE | `MOVING` | MoveExecutor |
| `MOVING` | 移动成功，路径未走完 | `READY` | MoveExecutor |
| `MOVING` | 路径走完（RouteList 空） | `IDLE` | MoveExecutor |
| `MOVING` | 目标格障碍物 | `BLOCKED` | MoveExecutor |
| `MOVING` | 连续卡住 ≥ 3 次 | `BLOCKED` | MoveExecutor |
| `BLOCKED` | Controller 超时处理 | `IDLE` | Controller |
| `READY` | RouteList 为空（异常） | `IDLE` | MoveExecutor |
| `MOVING` | 预约失败 / 占据，卡住 < 3 次 | `READY` | MoveExecutor |

---

## 6. 交互时序

### 6.1 正常移动时序

```
Controller                    CarAgent              MoveExecutor            Redis / RabbitMQ
    │                            │                       │                       │
    │── TICK_MOVE ──────────────→│                       │                       │
    │  (Car_Car001 队列)         │── executeMove(tick) ─→│                       │
    │                            │                       │── tryLock ───────────→│
    │                            │                       │←── OK                 │
    │                            │                       │── getCarStatus ──────→│
    │                            │                       │←── READY              │
    │                            │                       │── setStatus MOVING ──→│
    │                            │                       │── peekNextRouteStep ─→│
    │                            │                       │←── (nx, ny)           │
    │                            │                       │── SET reserve NX EX 3→│
    │                            │                       │←── OK                 │
    │                            │                       │── isBlocked ─────────→│
    │                            │                       │←── false              │
    │                            │                       │── isOccupied ────────→│
    │                            │                       │←── false              │
    │                            │                       │── popNextRouteStep ──→│
    │                            │                       │── setCarPosition ────→│
    │                            │                       │── illuminateAndHeat ──→│
    │                            │                       │── incrementCarSteps ──→│
    │                            │                       │── appendCarHistory ───→│
    │                            │                       │── DEL reserve ────────→│
    │                            │                       │── setStatus READY ────→│
    │                            │                       │── unlock ────────────→│
    │←── MOVED ─────────────────────────────────────────│                       │
    │  (controller_cmd 队列)     │                       │                       │
```

### 6.2 阻塞处理时序

```
Controller                    CarAgent              MoveExecutor            Redis / RabbitMQ
    │                            │                       │                       │
    │── TICK_MOVE ──────────────→│── executeMove(tick) ─→│                       │
    │                            │                       │── isBlocked ─────────→│
    │                            │                       │←── true (障碍物)      │
    │                            │                       │── clearRoute ────────→│
    │                            │                       │── clearTarget ───────→│
    │                            │                       │── setStatus BLOCKED ─→│
    │                            │                       │── setBlockedTick ────→│
    │                            │                       │── DEL reserve ────────→│
    │                            │                       │── unlock ────────────→│
    │←── BLOCKED ───────────────────────────────────────│                       │
    │                            │                       │                       │
    │  ... Controller 超时处理 ...│                       │                       │
    │                            │                       │                       │
    │── BLOCKED_TIMEOUT ────────→│                       │                       │
    │                            │── handleBlockedTimeout│                       │
    │                            │   (log, 读Status确认)  │                       │
```

---

## 7. Redis Key 说明

| Key 模式 | 类型 | 说明 | 写入者 |
|----------|------|------|--------|
| `car:{carId}:status` | String | 车辆状态枚举值 | CarMain / MoveExecutor / Controller |
| `car:{carId}:position` | String (JSON) | 车辆当前坐标 `{"x":n,"y":n}` | CarMain / MoveExecutor |
| `car:{carId}:route` | List | 待移动路径点（JSON 队列） | Navigator |
| `car:{carId}:steps` | String | 累计移动步数（含重复格） | MoveExecutor |
| `car:{carId}:effectiveSteps` | String | 有效探索步数（仅新格） | MoveExecutor |
| `car:{carId}:history` | List | 移动历史记录 | CarMain / MoveExecutor |
| `car:{carId}:blockedTick` | String | 阻塞发生的 tick 编号 | MoveExecutor |
| `lock:Car{carId}` | String | 分布式锁 | MoveExecutor |
| `pos:reserve:{row}:{col}` | String (TTL=3s) | 位置预约锁 | MoveExecutor |
| `map:view` | Bitmap | 全局探索视野（3×3 点亮） | MoveExecutor / CarMain |
| `map:heat` | 二维数组 | 全局热力图（访问次数） | MoveExecutor / CarMain |
| `map:block:{row}` | Bitmap | 障碍物位图 | 初始化脚本 |

---

## 8. 关键常量

| 常量 | 值 | 说明 |
|------|-----|------|
| `REGISTER_WAIT_MS` | 200 ms | 轮询 TaskConfigurator 注册的间隔 |
| `REGISTER_WAIT_ATTEMPTS` | 25 | 最多轮询次数（总等待 5 秒） |
| `EDGE_MARGIN` | 1 | 出生点选择时距地图边缘的最小距离 |
| `MAX_RESERVE_RETRIES` | 3 | 同一格连续失败次数阈值，超限切 BLOCKED |
