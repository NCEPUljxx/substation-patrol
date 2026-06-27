# Person A 设计文档——Common 模块 + Controller 模块

---

## 1. Person A 职责概述

Person A 负责**智能小车协作探索系统**中最核心的两个 Java 模块：

| 模块 | 包路径前缀 | 职责 |
|------|-----------|------|
| **common** | `com.substation.common.*` | 所有模块共享的基础设施：数据模型、Redis 黑板、RabbitMQ 消息总线、地图算法、认证鉴权、SQL Server 持久化、分析/回放/管理 API |
| **controller** | `com.substation.controller` | 全局调度中心：以固定节拍（tick）发现所有车辆并**按状态分派**（IDLE→分配目标，WAITING_ROUTE→触发寻路，READY→发送移动指令），同时处理动态障碍物、策略监督、阻塞检测、探索完成判定 |

Person A 的工作横跨**三层架构**：

```
 ┌─────────────────────────────────────────┐
 │          Display / Browser (前端)        │
 └────────────────┬────────────────────────┘
                  │ HTTP (auth/admin/analysis/replay APIs)
 ┌────────────────▼────────────────────────┐
 │    Controller 模块  (全局调度中心)        │
 │    ControllerMain / StatusDispatcher     │
 │    TickScheduler / CommandHandler        │
 └──────┬──────────────────┬───────────────┘
        │ MQ (RabbitMQ)     │ Redis
 ┌──────▼──────────────────▼───────────────┐
 │        Common 模块 (共享基础设施)         │
 │  model / redis / mq / auth / sql        │
 │  analysis / replay / admin / map / infra│
 └─────────────────────────────────────────┘
```

**总计 Java 文件**：Common 模块 53 个源文件（9 个 package + 1 个顶级类），Controller 模块 4 个源文件。

---

## 2. Common 模块详细设计

Common 模块是系统中的**共享基础设施层**，所有子模块（controller、car、navigator、target-planner、task-configurator、strategy-supervisor、display、launcher）均依赖此模块。它定义了统一的数据模型、通信协议和持久化方案。

### 2.1 包结构总览

```
com.substation.common
├── model          数据模型（5 个类）
├── redis          Redis 黑板客户端（3 个类）
├── mq             RabbitMQ 消息总线（4 个类）
├── auth           认证鉴权（5 个类 + 5 个 model record）
├── sql            SQL Server 持久化（4 个类 + 3 个 model record）
├── analysis       统计分析（4 个类 + 4 个 model record）
├── replay         仿真回放（4 个类 + 3 个 model record）
├── admin          管理后台 API（1 个类）
├── map            地图算法（7 个类）
├── infra          部署基础设施（3 个类）
└── DynamicObstacleUtil  动态障碍物工具（1 个顶级类）
```

---

### 2.2 model 包——数据模型

#### 2.2.1 Point（坐标）

```java
public record Point(int x, int y)
```

- 不可变二维坐标记录，`x` 为列、`y` 为行
- `manhattanDistance(Point other)` 计算曼哈顿距离
- `toJson()` / `fromJson(String json)` 支持 fastjson2 序列化，用于在 Redis 列表中以 JSON 存储路径点

#### 2.2.2 CarStatus（车辆状态枚举）

| 枚举值 | 中文名 | 颜色 | 含义 |
|--------|--------|------|------|
| `IDLE` | 空闲 | `#9E9E9E` | 无目标，等待分配目标点 |
| `WAITING_ROUTE` | 等待路径 | `#FF9800` | 已分配目标，等待 Navigator 规划路径 |
| `READY` | 就绪 | `#4CAF50` | 路径就绪，等待 Controller 发送移动指令 |
| `MOVING` | 移动中 | `#2196F3` | 正在执行移动指令 |
| `BLOCKED` | 受阻 | `#F44336` | 路径被障碍物阻塞，等待超时重分配 |

**状态转换图**（由 Controller 的 StatusDispatcher 驱动）：

```
         ┌──────────┐
   ┌────►│   IDLE   │◄──────────────┐
   │     └────┬─────┘               │
   │ 分配目标 │                      │ 超时/无路径
   │     ┌────▼──────────┐         │
   │     │ WAITING_ROUTE  │─────────┘
   │     └────┬──────────┘
   │  路径就绪 │
   │     ┌────▼──────┐  移动指令  ┌────────┐
   │     │   READY   │──────────►│ MOVING │
   │     └────▲──────┘           └───┬────┘
   │          │     移动完成 ────────┘
   │          │     阻塞超时后重分配
   │     ┌────┴──────┐
   └─────│  BLOCKED  │
         └───────────┘
```

#### 2.2.3 AlgorithmType（寻路算法枚举）

- `BFS`：广度优先搜索（默认，`AlgorithmType.BFS.name()` = `"BFS"`）
- `ASTAR`：A* 算法

由 TaskConfig 中的 `algorithm` 字段决定，Controller 在发送 PLAN_ROUTE 消息时携带此参数。

#### 2.2.4 RouteStep（路径步骤）

```java
public record RouteStep(Point position, int stepIndex)
```

表示路径中的一个步骤：`position` 为当前坐标，`stepIndex` 为步骤序号（从 0 开始）。

#### 2.2.5 SimulationState（仿真状态快照）

```java
public record SimulationState(
    int tick,                    // 当前仿真步数
    int explorationRate,         // 探索率 0~100
    Map<String, String> taskConfig,  // 任务配置
    List<CarInfo> cars,          // 所有车辆信息
    boolean[][] mapView,         // 已探索位图
    boolean[][] mapBlock,        // 障碍物位图
    boolean[][] mapSealed,       // 密封区位图
    String runStartedBy          // 操作者用户名
)
```

嵌套 `CarInfo` record 包含：`carId`、`number`、`position`、`target`、`routeList`、`status`、`steps`、`effectiveSteps`。每个 tick 推送给 Display 前端渲染。

---

### 2.3 redis 包——Redis 黑板客户端

#### 2.3.1 BlackboardClient（黑板客户端）

核心类，封装所有 Redis 交互操作，是整个系统的**共享内存中枢**。约 1077 行代码。

**构造参数**：`(String host, int port, int mapWidth, int mapHeight)`

**Redis Key 设计**：

| Key 模式 | 数据类型 | 用途 |
|----------|----------|------|
| `mapView` | Bitmap (String) | 地图探索状态位图，true=已探索 |
| `mapBlock` | Bitmap (String) | 地图障碍物位图，true=不可通行 |
| `mapSealed` | Bitmap (String) | 被障碍物包裹不可达的空格位图 |
| `mapHeat` | Hash | 热力图，key=`row,col`，value=访问计数 |
| `{carId}:Position` | Hash | 车辆坐标 `{x, y}` |
| `{carId}:Target` | Hash | 车辆目标坐标 `{x, y}` |
| `{carId}:RouteList` | List | 车辆路径（从起点到终点的 Point JSON 列表），LPOP 消费 |
| `{carId}:Status` | String | 车辆状态（CarStatus 枚举名） |
| `{carId}:Steps` | String | 车辆总步数 |
| `{carId}:EffectiveSteps` | String | 车辆有效步数（踩入未探索区域） |
| `{carId}:BlockedTick` | String | 车辆被阻塞时的 tick 号 |
| `{carId}:History` | List | 车辆移动轨迹 `[{x, y, tick}, ...]` |
| `explorationEvents` | List | 探索事件列表 `["tick,row,col", ...]` |
| `TaskConfig` | Hash | 任务配置（mapWidth, mapHeight, carCount, algorithm, tickInterval, obstacleRatio, active, elapsedSeconds） |
| `controller:instance` | String | 控制器实例锁（SET NX EX 30s） |
| `pos:reserve:{x}:{y}` | String | 位置预约锁（防多车重叠，EX 3s） |
| `sim:run:startedAt` | String | 仿真开始时间戳 |
| `sim:run:startedBy` | String | 仿真操作者 |
| `sim:run:archived` | String | 是否已归档 |

**关键方法分类**：

（1）**地图操作**（位图读写）
- `getMapViewBit(row, col)` / `setMapViewBit(row, col, explored)` — 单点探索状态
- `getMapViewBytes()` / `getMapBlockBytes()` / `getMapSealedBytes()` — 整图字节数组
- `readMapBitmapSnapshot()` — pipeline 批量读取三张位图，缩短竞态窗口
- `isBlocked(row, col)` / `setBlock(row, col, blocked)` — 障碍物状态
- `loadExploredBitmap()` / `loadObstacleBitmap()` / `loadSealedBitmap()` — 构建 boolean[][] 位图
- `writeBlockBitmap(blocked, mapWidth)` / `writeSealedBitmap(sealed, mapWidth)` — 整图写入

（2）**探索统计**
- `getExplorationRate()` — 返回 0~100 百分比。分母 = 总格子 - 障碍 - 密封区
- `isExplorationComplete()` — 探索率 >= 100 且无可探索未探索格
- `recordExploration(tick, row, col)` — 原子探索：SETBIT + 首次探索记录事件（返回 true 表示新发现）
- `countExploredCells()` — Redis BITCOUNT 命令
- `getExplorationEvents()` — 获取全部探索事件列表

（3）**车辆信息管理**
- `getCarPosition(carId)` / `setCarPosition(carId, pos)` — 位置
- `getCarTarget(carId)` / `setCarTarget(carId, target)` / `clearCarTarget(carId)`
- `getCarRoute(carId)` — 获取完整路径列表
- `peekNextRouteStep(carId)` — 查看下一步（不移除）
- `popNextRouteStep(carId)` — 弹出下一步（LPOP）
- `pushRoute(carId, route)` — 整条路径推入（Lua 脚本原子操作：先 DEL 再 LPUSH 逆序推入）
- `getCarStatus(carId)` / `setCarStatus(carId, status)` — 状态读写
- `getCarSteps(carId)` / `incrementCarSteps(carId)` / `getCarEffectiveSteps(carId)` / `incrementCarEffectiveSteps(carId)`
- `getBlockedTick(carId)` / `setBlockedTick(carId, tick)` / `clearBlockedTick(carId)`
- `appendCarHistory(carId, position, tick)` — 追加轨迹记录
- `getCarHistory(carId)` / `getAllCarHistories()` — 获取全部轨迹

（4）**热力图**
- `incrementMapHeat(row, col)` — 指定格子计数 +1（HINCRBY）
- `getMapHeat()` — 获取完整热力图

（5）**任务配置**
- `getTaskConfig()` — HGETALL 获取全部配置
- `isTaskActive()` / `setTaskActive(active)` — 任务激活状态
- `getMapWidth()` / `getMapHeight()` / `getCarCount()` / `getAlgorithm()` / `getTickInterval()` / `getObstacleRatio()`
- `setElapsedSeconds(seconds)` / `setTickInterval(intervalMs)`
- `initTaskConfig(config)` — 批量初始化

（6）**控制器锁与车辆发现**
- `acquireControllerLock()` — SET NX EX 30s，保证单实例
- `releaseControllerLock()` — DEL
- `discoverCarIds()` — KEYS `Car*:Status` 扫描所有已注册车辆

（7）**位置预约锁**
- `tryReservePosition(x, y, carId)` — SET NX EX 3s，防多车同时移动到同一格
- `releaseReservePosition(x, y, carId)` — 校验 carId 后 DEL

（8）**仿真清理**
- `clearSimulationState()` — 清除所有仿真数据（保留 auth:* 等非仿真键）
- `clearSimRunMetadata()` — 清除操作者/开始时间/归档标记

（9）**工具方法**
- `bytesToBitmap(bytes, width, height)` — 字节数组转 boolean[][]（静态方法）
- `bitmapToBytes(bitmap, mapWidth)` — boolean[][] 转字节数组（静态方法）
- `getJedisPool()` — 暴露底层连接池（供 DistributedLock 等使用）

#### 2.3.2 DistributedLock（分布式锁）

基于 Redis SET NX PX + Lua 脚本原子释放。

```java
public DistributedLock(JedisPool pool, String carId)
```

- `tryLock()` / `tryLock(timeoutMs)` — SET `lock:{carId}` NX PX，lockValue = 线程名 + 时间戳
- `unlock()` — Lua 脚本原子校验 `lockValue` 后 DEL，防止误删别车锁

#### 2.3.3 MapBitmapSnapshot（位图快照记录）

```java
public record MapBitmapSnapshot(byte[] mapView, byte[] mapBlock, byte[] mapSealed)
```

用于 Pipeline 批量读取三张位图后的一次性传递，缩短 Display 快照与并发写入的竞态窗口。

---

### 2.4 mq 包——RabbitMQ 消息总线

#### 2.4.1 MessageBus（消息总线）

封装 RabbitMQ 连接管理、队列/交换机声明、消息发布与订阅。

**构造参数**：`(String host, int port, String username, String password)`

**核心方法**：

| 方法 | 说明 |
|------|------|
| `connect()` | 建立连接 + 创建 Channel，开启自动恢复（`setAutomaticRecoveryEnabled(true)`） |
| `declareCarQueue(carId)` | 声明小车持久化队列 `Car_{carId}` |
| `declareNavigatorQueue()` | 声明 `NavigatorCmd` 队列 |
| `declareTargetPlannerQueue()` | 声明 `TargetPlannerCmd` 队列 |
| `declareTaskConfigQueue()` | 声明 `TaskConfigCmd` 队列 |
| `declareControllerQueue()` | 声明 `ControllerCmd` 队列 |
| `declareStrategySupervisorQueue()` | 声明 `StrategySupervisorCmd` 队列 |
| `declareFanoutExchange()` | 声明 `UpdateView` Fanout 交换机 |
| `bindFanoutQueue()` | 创建临时匿名队列并绑定到 `UpdateView`（供前端接收广播） |
| `publish(queueName, message)` | 点对点发送（`basicPublish` 到默认 exchange） |
| `publishFanout(exchangeName, message)` | 广播到所有绑定队列 |
| `subscribe(queueName, handler)` | 订阅队列，自动 ACK，回调接收消息体字符串 |
| `purgeQueue(queueName)` | 清空队列中积压消息 |
| `close()` | 关闭 Channel 和 Connection |

所有消息以 **UTF-8 JSON 字符串**传输，消息格式由 MessageBuilder 统一生成。

#### 2.4.2 MessageTypes（消息类型常量）

| 常量名 | 值 | 发送者 → 接收者 | 语义 |
|--------|-----|-----------------|------|
| `ASSIGN_TARGET` | `"ASSIGN_TARGET"` | Controller → TargetPlanner | 请求分配目标点 |
| `TARGET_ASSIGNED` | `"TARGET_ASSIGNED"` | TargetPlanner → Controller | 目标分配结果 |
| `PLAN_ROUTE` | `"PLAN_ROUTE"` | Controller → Navigator | 请求规划路径 |
| `ROUTE_PLANNED` | `"ROUTE_PLANNED"` | Navigator → Controller | 路径规划完成 |
| `TICK_MOVE` | `"TICK_MOVE"` | Controller → Car | 触发车辆移动一步 |
| `MOVED` | `"MOVED"` | Car → Controller | 移动结果回执 |
| `ROUTE_DONE` | `"ROUTE_DONE"` | Car → Controller | 路径走完通知 |
| `BLOCKED` | `"BLOCKED"` | Car → Controller | 被障碍阻塞通知 |
| `BLOCKED_TIMEOUT` | `"BLOCKED_TIMEOUT"` | Controller → Car | 阻塞超时，重新规划 |
| `REFRESH_ALL` | `"REFRESH_ALL"` | Controller → Display（Fanout） | 广播刷新全视图 |
| `SET_CONFIG` | `"SET_CONFIG"` | Display → Controller | 下发任务参数 |
| `FORWARD_CONFIG` | `"FORWARD_CONFIG"` | Controller → TaskConfigurator | 转发配置 |
| `RESET` | `"RESET"` | Display → Controller | 重置指令 |
| `FORWARD_RESET` | `"FORWARD_RESET"` | Controller → TaskConfigurator | 转发重置 |
| `TASK_READY` | `"TASK_READY"` | TaskConfigurator → Controller | 任务初始化完毕 |
| `TOGGLE_PAUSE` | `"TOGGLE_PAUSE"` | Display → Controller | 切换暂停/继续 |
| `SET_TICK_INTERVAL` | `"SET_TICK_INTERVAL"` | Display → Controller | 修改 tick 间隔 |
| `SUPERVISE_ROUTE` | `"SUPERVISE_ROUTE"` | Controller → StrategySupervisor | 请求审查路线 |
| `ROUTE_OPTIMIZED` | `"ROUTE_OPTIMIZED"` | StrategySupervisor → Controller | 路线优化结果 |

#### 2.4.3 QueueNames（队列/交换机名称常量）

| 常量 | 值 | 类型 |
|------|-----|------|
| `CAR_PREFIX` | `"Car_"` | 前缀，完整名为 `Car_{carId}` |
| `NAVIGATOR_CMD` | `"NavigatorCmd"` | 持久化队列 |
| `TARGET_PLANNER_CMD` | `"TargetPlannerCmd"` | 持久化队列 |
| `TASK_CONFIG_CMD` | `"TaskConfigCmd"` | 持久化队列 |
| `CONTROLLER_CMD` | `"ControllerCmd"` | 持久化队列 |
| `STRATEGY_SUPERVISOR_CMD` | `"StrategySupervisorCmd"` | 持久化队列 |
| `UPDATE_VIEW_EXCHANGE` | `"UpdateView"` | Fanout 交换机 |
| `carQueue(carId)` | `"Car_{carId}"` | 动态生成 |

#### 2.4.4 MessageBuilder（消息构建器）

统一构建 JSON 消息字符串，所有 MQ 消息均通过此工具类生成。

```java
public static String build(String type, int tick, String carId, Map<String, Object> data)
```

生成格式：
```json
{
  "type": "ASSIGN_TARGET",
  "tick": 42,
  "carId": "Car001",
  "timestamp": 1719000000000,
  "data": {"carId": "Car001"}
}
```

重载版本：`build(type, tick, carId)` — data 为空 Map；`build(type, tick)` — 系统级消息，无 carId。

---

### 2.5 auth 包——认证鉴权

#### 2.5.1 AuthApiHandler（认证 API 处理器）

使用 `com.sun.net.httpserver.HttpExchange` 处理 HTTP 请求，端点 **`/api/auth/*`**：

| 方法 | 路径 | 功能 | 说明 |
|------|------|------|------|
| POST | `/api/auth/login` | 登录 | 用户名+密码 → JWT token（实际为 32 字节随机 hex），BCrypt 验证 |
| POST | `/api/auth/register` | 注册 | 写入 `registration_requests` 表，等待管理员审核 |
| POST | `/api/auth/logout` | 登出 | 销毁 Redis session |
| GET | `/api/auth/me` | 当前用户信息 | 需 Bearer token，返回 username/role/displayName |
| POST | `/api/auth/change-password` | 修改密码 | 验证旧密码 → BCrypt 加密新密码 → 销毁所有会话 |

依赖：`SqlUserStore`（认证）、`RegistrationStore`（注册）、`OperationLogStore`（日志）、`SessionManager`（会话）。

#### 2.5.2 AuthFilter（HTTP 鉴权过滤器）

继承 `com.sun.net.httpserver.Filter`，对所有 HTTP 请求进行 Token 鉴权。

**白名单**（无需登录）：
- `/login.html`、`/index.html`、`/dashboard.html`、`/analysis.html`
- `/css/*`、`/js/*`、`/favicon.ico`
- `/api/auth/*`

**权限控制**：`/api/analysis/export` 仅允许 `admin` 角色访问。

非白名单请求：提取 `Authorization: Bearer <token>` header → `SessionManager.validateDetailed(token)` → 校验成功则放行，失败则返回 401（含 `kicked: true` 标记区分被挤号）。

#### 2.5.3 SessionManager（会话管理）

基于 Redis 的单点登录会话管理。

**Redis Key 设计**：
| Key 模式 | 用途 | TTL |
|----------|------|-----|
| `auth:session:{token}` | 会话信息 JSON | 1800s (30分钟) |
| `auth:user_session:{username}` | 当前活跃 token | 1800s |
| `auth:kicked:{oldToken}` | 被挤掉的旧 token 标记 | 120s |

**核心流程**：
1. `createSession(username, role)`：生成 32 字节随机 hex token → 挤掉旧会话（写入 kicked flag + 删除旧 session key）→ 写入新 session
2. `validateDetailed(token)`：检查 kicked flag → 读取 session → 校验是否为该用户当前活跃 token → 续期
3. `destroySession(token)`：删除 session key + kicked flag + user_session 映射
4. `extractToken(authHeader)`：从 `"Bearer xxx"` 中提取 token

**特性**：同一用户全局仅保留一个有效会话，新登录挤掉旧会话（前端收到 `kicked: true` 后显示"已在其他设备登录"提示）。

#### 2.5.4 UserStore（Redis 用户存储）

使用 Redis Hash `auth:users` 存储用户信息（HSET）。

- `initPresetUsers()`：幂等创建预设账号（`admin/admin123`、`simulator1/sim123`、`analyst1/ana123`）
- `authenticate(username, password)`：BCrypt 验证 + 登录失败计数（5 次锁 15 分钟）
- `changePassword(username, oldPassword, newPassword)`
- `register(username, password, role, displayName)`：允许 `simulator`/`analyst` 角色自行注册
- `getUserInfo(username)`：返回不含密码的用户信息

#### 2.5.5 auth 模型 records

| Record | 字段 |
|--------|------|
| `LoginRequest` | `username, password` |
| `LoginResponse` | `success, token, username, role, displayName, error` |
| `SessionInfo` | `username, role, loginAt, lastAccess` |
| `SessionValidation` | `Status status, SessionInfo session` — Status 枚举: `VALID / NOT_FOUND / KICKED` |
| `UserInfo` | `username, role, displayName` |

---

### 2.6 sql 包——SQL Server 持久化

#### 2.6.1 DatabaseManager（数据库连接管理）

> 连接参数通过 `deploy/infra.local.json` 配置（推荐）或使用默认值。Display 模块启动时加载配置并初始化连接。

**默认连接信息**：
- JDBC URL: `jdbc:sqlserver://localhost:1433;databaseName=substation-patrol;encrypt=false;trustServerCertificate=true`
- 用户: `sa` / 密码: `Root@1234`（可通过 `infra.local.json` → `dbPassword` 修改）
- 驱动: `com.microsoft.sqlserver.jdbc.SQLServerDriver`

`initDatabase()` 在系统启动时幂等建表 + 插入预设管理员 `admin/admin123`。

**数据库表设计**：

| 表名 | 主要字段 | 用途 |
|------|----------|------|
| `users` | id, username, password(BCrypt), role, display_name, status, created_at | 用户账号 |
| `registration_requests` | id, username, password, role, display_name, status(pending/approved/rejected), reviewed_by, review_time, created_at | 注册申请审核 |
| `operation_logs` | id, username, action, target, details, ip_address, created_at | 操作审计日志 |
| `simulation_runs` | id, started_by, started_at, ended_at, map_width, map_height, car_count, algorithm, obstacle_ratio, tick_interval, max_tick, exploration_rate, status, map_block_b64, map_sealed_b64, map_view_final_b64, car_histories(NVARCHAR MAX), exploration_events(NVARCHAR MAX) | 仿真场次（回放数据） |
| `simulation_run_stats` | run_id(PK+FK), saved_by, saved_at, client_timestamp, exploration_rate, tick, duration, total_steps, total_effective_steps, efficiency_percent, car_count, algorithm, obstacle_ratio, map_width, map_height, balance_score, payload(NVARCHAR MAX) | 仿真统计分析 |

#### 2.6.2 SqlUserStore（SQL 用户存储）

替代 Redis UserStore，操作 `users` 表的完整 CRUD：
- `authenticate(username, password)`：BCrypt 验证
- `changePassword(username, oldPassword, newPassword)`
- `getUserInfo(username)`：返回 `UserInfo(username, role, displayName)`
- `queryUsers(search, role, page, size)` / `countUsers(search, role)`：管理员查询（支持搜索 + 角色筛选 + 分页，使用 SQL Server `OFFSET ... FETCH NEXT` 语法）
- `resetPassword(username)`：管理员重置密码为 `123456`
- `insertApprovedUser(username, passwordHash, role, displayName)`：审核通过后插入

#### 2.6.3 RegistrationStore（注册申请管理）

操作 `registration_requests` 表：
- `hasPendingRequest(username)`：防重复提交
- `insertRequest(username, passwordHash, role, displayName)`：插入待审核申请
- `queryRequests(status, page, size)` / `countRequests(status)`：管理员查询
- `approve(id, reviewedBy)`：通过申请（插入 users 表 + 更新状态为 approved）
- `reject(id, reviewedBy)`：拒绝申请（更新状态为 rejected）

#### 2.6.4 OperationLogStore（操作日志）

操作 `operation_logs` 表：
- `log(username, action, target, details)` / `log(..., ipAddress)`：写入日志
- `queryLogs(username, action, page, size)` / `countLogs(username, action)`：查询日志（时间自动转为北京时间 Asia/Shanghai）

#### 2.6.5 sql 模型 records

| Record | 字段 |
|--------|------|
| `UserRecord` | `username, role, displayName, status, createdAt` |
| `RegistrationRecord` | `id, username, role, displayName, status, reviewedBy, reviewTime, createdAt` |
| `OperationLogRecord` | `id, username, action, target, details, createdAt` |

---

### 2.7 analysis 包——统计分析

#### 2.7.1 AnalysisApiHandler（统计分析 API）

端点 **`/api/analysis/*`**：

| 方法 | 路径 | 功能 |
|------|------|------|
| GET | `/api/analysis/records` | 分页查询统计记录列表 |
| POST | `/api/analysis/records` | 保存仿真统计（支持已有 runId 的补录） |
| POST | `/api/analysis/discard` | 用户拒绝保存 |
| DELETE | `/api/analysis/records/{runId}` | 删除统计 + 回放数据 |
| GET | `/api/analysis/summary` | 全局汇总统计 |
| GET | `/api/analysis/car/{carId}` | 单车统计 |
| GET | `/api/analysis/leaderboard` | 排行榜 |
| POST | `/api/analysis/query` | 自定义查询 |
| GET | `/api/analysis/export` | 数据导出（仅 admin） |

#### 2.7.2 SimulationStatsStore（仿真统计持久化）

操作 `simulation_run_stats` 表：
- `save(runId, savedBy, payload)`：插入统计记录，含 SQL Duplicate Key (2627) 冲突检测和外键约束 (547) 校验
- `existsForRun(runId)` / `findPayloadByRunId(runId)` / `listRecent(page, size)` / `listFullRecords(page, size)` / `deleteByRunId(runId)`
- `listFullRecords` 使用 INNER JOIN 关联 `simulation_runs` 表，合并回放与统计信息

#### 2.7.3 SimulationRecordService（仿真记录服务）

协调保存流程：归档路径回放（RunArchiver）→ 保存统计分析（SimulationStatsStore），保持两表 `run_id` 一致。失败时回滚删除已插入的回放记录。

- `saveConfirmed(payload, savedBy)`：归档 + 保存统计
- `declineSave()`：标记已处理，不写入数据库
- `deleteByRunId(runId)`：同时删除统计和回放

#### 2.7.4 AnalysisEngine（分析引擎）

当前为接口桩（stub），`getSummary()` 返回空 `SummaryStatistics.empty()`。后续从 Redis 读取 `CarID:History` 等数据计算实际统计指标。

#### 2.7.5 analysis 模型 records

| Record | 字段 |
|--------|------|
| `AnalysisQuery` | `carIds, startTick, endTick, metrics` |
| `CarStatistics` | `carId, steps, pathCount, avgPathLength, blockCount, idleRate, pathHistory` |
| `SimulationStatsSummary` | `runId, savedBy, savedAt, explorationRate, tick, duration, totalSteps, totalEffectiveSteps, efficiencyPercent, carCount, algorithm, obstacleRatio, mapWidth, mapHeight, balanceScore, clientTimestamp` |
| `SummaryStatistics` | `totalSteps, explorationRate, efficiency, duration, blockCount, idleRate, reExploreRate, activeCars` |

---

### 2.8 replay 包——仿真回放

#### 2.8.1 ReplayApiHandler（回放 API）

端点 **`/api/replay/*`**：

| 方法 | 路径 | 功能 |
|------|------|------|
| GET | `/api/replay/runs` | 分页查询历史场次列表（仅含关联了统计记录的场次，INNER JOIN `simulation_run_stats`） |
| GET | `/api/replay/runs/{runId}` | 获取完整回放数据（含 mapView/mapBlock/mapSealed 位图 B64 + carHistories + explorationEvents） |

#### 2.8.2 RunArchiver（场次归档器）

将 Redis 黑板中的当前仿真数据归档到 SQL Server `simulation_runs` 表。

- `archiveIfNeeded(blackboard, status)`：检查是否已归档 + 是否有可回放数据 → 构建 record → insert → 标记已归档
- 归档内容：操作者、开始/结束时间、地图参数、最大 tick、探索率、三张位图 B64、车辆轨迹、探索事件

#### 2.8.3 SimulationRunStore（仿真场次持久化）

操作 `simulation_runs` 表：
- `insert(draft)`：生成自增 ID，`car_histories` 和 `exploration_events` 以 JSON 字符串存储
- `listRecent(page, size)`：关联 `simulation_run_stats` 查询有统计的场次列表
- `findById(id)` / `existsById(id)` / `deleteById(id)`

#### 2.8.4 ReplayDataBuilder（回放数据构建器）

构建与前端 `REPLAY_DATA` 兼容的 JSON：
- `fromBlackboard(blackboard)`：从当前黑板实时构建
- `fromRecord(record)`：从数据库记录构建
- `resolveMaxTick(histories, events)`：遍历所有轨迹和探索事件找到最大 tick
- 输出格式包含：`type, mapWidth, mapHeight, taskConfig, carHistories, explorationEvents, mapViewB64, mapBlockB64, mapSealedB64, maxTick, explorationRate, runId, mapBlock, mapSealed`

#### 2.8.5 replay 模型 records

| Record | 字段 |
|--------|------|
| `SimulationRunRecord` | `id, startedBy, startedAt, endedAt, mapWidth, mapHeight, carCount, algorithm, obstacleRatio, tickInterval, maxTick, explorationRate, status, mapBlockB64, mapSealedB64, mapViewFinalB64, carHistories, explorationEvents` |
| `SimulationRunStatus` | 枚举：`COMPLETED, ABORTED` |
| `SimulationRunSummary` | `id, startedBy, startedAt, endedAt, mapWidth, mapHeight, carCount, algorithm, maxTick, explorationRate, status, hasStats` |

---

### 2.9 admin 包——管理后台 API

#### AdminApiHandler（管理员 API 处理器）

端点 **`/api/admin/*`**（所有接口由 AuthFilter 保证仅 admin 角色可访问）：

| 方法 | 路径 | 功能 |
|------|------|------|
| GET | `/api/admin/users` | 用户列表（支持 search、role 筛选、分页） |
| GET | `/api/admin/users/{username}` | 用户详情 |
| POST | `/api/admin/users/{username}/reset-password` | 重置密码为 `123456`（记录操作日志） |
| GET | `/api/admin/registrations` | 注册申请列表（默认 status=pending） |
| POST | `/api/admin/registrations/{id}/approve` | 通过注册申请（插入 users 表 + 记录日志） |
| POST | `/api/admin/registrations/{id}/reject` | 拒绝注册申请（记录日志） |
| GET | `/api/admin/logs` | 操作日志列表（支持 username、action 筛选、分页） |

---

### 2.10 map 包——地图算法

提供路径规划、探索前沿分析、出生点选择等核心算法，供 Navigator、TargetPlanner、TaskConfigurator 等模块使用。

#### 2.10.1 ExplorationWeightedPathFinder（加权探索路径搜索）

**目标**：偏好未探索区域的路径搜索，已探索格代价更高（避免在已知走廊空跑）。被 Navigator 模块调用，是整个系统路径规划的核心算法。

**算法**：支持两种模式（`SearchMode` 枚举）：
- `WEIGHTED_DIJKSTRA`：使用优先队列的标准 Dijkstra，代价 = gCost。优先扩展实际代价最小的节点，保证找到代价最优路径。
- `WEIGHTED_ASTAR`：在 gCost 基础上加上曼哈顿距离启发值 `h = |dx| + |dy|`。启发函数引导搜索向目标方向收缩，通常比纯 Dijkstra 更快。

**内部数据结构**：
- `int[][] minCost`：记录到达每格的最小代价，初始值 `Integer.MAX_VALUE`，无 `closed` 集合（通过 `minCost` 比较替代已访问判定）
- `Point[][] parent`：记录每格的父节点，用于最终路径回溯
- `PriorityQueue<SearchNode>`：按 `priority(mode, target)` 排序的开放节点优先队列
- `SearchNode`（内部 record）：`(int col, int row, int gCost)`，`priority()` 方法根据模式返回 `gCost` 或 `gCost + h`

**主方法** `plan(Point start, Point target, boolean[][] blocked, boolean[][] explored, int width, int height, SearchMode mode)`：
1. 边界检查：目标越界或起点 = 终点 → 返回空列表
2. 初始化 `minCost[start] = 0`，起点入队
3. 主循环：从优先队列取最小代价节点 → 跳过过时节点（`gCost > minCost`）→ 到达终点则 `reconstructPath()` 回溯路径
4. `expandNeighbors()`：遍历四方向 → 边界/障碍检查 → 查 `ExplorationPathCosts.stepCost(explored, nextX, nextY)` 获取步长代价 → 松弛操作（`newCost < minCost` 时更新并入队）
5. `reconstructPath()`：从终点沿 `parent` 回溯到起点 → 反转 → 返回不可变列表（不含起点）

**代价模型**（来自 `ExplorationPathCosts`）：
- `UNEXPLORED_STEP_COST = 1`（未探索格，优先走）
- `EXPLORED_STEP_COST = 5`（已探索格，5 倍代价避免空跑）

**输入**：起点/终点坐标、障碍位图 `blocked[][]`、探索位图 `explored[][]`、地图宽高
**输出**：从起点到终点的路径点列表（不含起点，已反转）。无可行路径时返回空列表。

#### 2.10.2 ShortestHopPathFinder（最短跳数路径）

纯 BFS 无权最短路径（每格代价 = 1），用于对比测试与基线评估。输入为起点/终点 + 障碍位图，输出路径点列表。

#### 2.10.3 ExplorationPathCosts（路径代价常量）

```java
UNEXPLORED_STEP_COST = 1  // 未探索格的步长代价
EXPLORED_STEP_COST = 5    // 已探索格的步长代价
```

`stepCost(explored, col, row)` 根据探索状态返回对应代价。

#### 2.10.4 FrontierCellFinder（前沿格查找器）

查找与已探索区相邻的未探索前沿格（frontier），供 TargetPlanner 确定待探索的边界区域。

**核心概念**：前沿格是已探索区域与未探索区域之间的"边界线"——车辆从已探索区向外推进时，前沿格代表下一步可扩展的目标候选。

**类设计**：
- `final` 类，私有构造器，所有方法均为 `static` 工具方法
- 四方向数组 `DIRECTIONS = {{0,-1}, {0,1}, {-1,0}, {1,0}}`

**核心方法**：

（1）`findFrontierCells(explored, obstacles, sealed, width, height)`：
- 双重循环遍历整张地图（row-major 顺序）
- 对每格调用 `isFrontierCandidate()` 检查基础条件 → 调用 `hasExploredNeighbor()` 检查邻域
- 满足条件的格包装为 `Point(col, row)` 加入结果列表
- 返回 `List<Point>`，按扫描顺序排列

（2）`isFrontier(col, row, explored, obstacles, sealed, width, height)`：
- 单格判断版本，逻辑与批量查找一致

（3）`isFrontierCandidate(col, row, explored, obstacles, sealed)`：
- 三个必要条件：`!explored[row][col]` + `!obstacles[row][col]` + `!sealed[row][col]`
- 即：自身未探索、非障碍物、非密封区

（4）`hasExploredNeighbor(col, row, explored, obstacles, width, height)`：
- 遍历四方向 → 边界检查 + 障碍跳过 → 只要任一邻格 `explored` 为 true 即返回 true
- 若四个方向均无已探索邻格，返回 false

**使用场景**：TargetPlanner 根据前沿格列表选择车辆的下一个探索目标点。

#### 2.10.5 UnexploredClusterFinder（未探索簇查找器）

将未探索格子按四邻接连通性划分为若干区域块（簇），用于 TargetPlanner 基于簇的分配阶段（cluster-based allocation）。

**核心概念**：未探索区域往往被已探索区域或障碍物分割成多个不连通的"孤岛"。每个孤岛是一个连通分量（cluster），系统按簇给不同车辆分配探索区域，避免多车挤在同一片未探索区。

**类设计**：
- `final` 类，私有构造器，所有方法均为 `static` 工具方法
- 四邻接方向 `CARDINAL_DELTAS = {{0,-1}, {0,1}, {-1,0}, {1,0}}`

**核心方法** `findClusters(explored, obstacles, sealed)`：
1. 获取地图尺寸 `height = explored.length`, `width = explored[0].length`
2. 创建 `boolean[][] visited` 防重复访问
3. 双重循环扫描每格 → `isUnexplored()` 检查（未探索 + 非障碍 + 非密封）且未访问 → 启动 BFS 区域填充
4. `floodFillCluster()`：从种子格开始 BFS（`ArrayDeque` 队列）→ 向四方向扩展 → 每步检查边界/访问/未探索条件 → 标记 visited → 入队
5. 每个簇包装为 `UnexploredCluster(cells)` 加入结果列表
6. 返回 `List<UnexploredCluster>`

**UnexploredCluster**（辅助 record）：
- `List<Point> cells`：簇内所有格子的坐标列表
- `withoutAllocated(Set<Point> allocated)`：排除已被其他车辆占用的格子，返回过滤后的新 `UnexploredCluster`

**辅助方法**：
- `isUnexplored(explored, obstacles, sealed, row, col)`：`!explored[row][col] && !obstacles[row][col] && !sealed[row][col]`
- `isInside(col, row, grid)`：`0 <= col < grid[0].length && 0 <= row < grid.length`

**使用场景**：TargetPlanner 分配阶段：先找簇 → 簇-车辆匹配（cluster-to-car matching）→ 在分配的簇内选择具体前沿格。

#### 2.10.6 ReachabilityAnalyzer（可达性分析器）

从车辆起点 BFS 洪水填充（flood-fill），检测被障碍物完全包围、车辆永远无法进入的空格（密封区）。密封区在探索率计算时从分母中排除，避免"永远无法完成探索"的问题。

**类设计**：
- `final` 类，私有构造器，所有方法均为 `static` 工具方法
- 四方向 `CARDINAL_DELTAS = {{0,-1}, {0,1}, {-1,0}, {1,0}}`

**核心方法** `findSealedFreeCells(obstacles, startPoints)`：
1. 获取地图尺寸 `width = obstacles[0].length`, `height = obstacles.length`
2. `floodFillReachable()`：从所有车辆起点同时启动 BFS，标记所有可达区域
3. `markSealedFreeCells()`：对比障碍位图和可达位图 —— 非障碍 且 不可达 的格子标记为 sealed
4. 返回 `boolean[][] sealed`（`sealed[row][col] = true` 表示密封格）

**内部方法详细**：

（1）`floodFillReachable(obstacles, startPoints, width, height)`：
- 创建 `boolean[][] reachable` 记录已访问
- 所有起点 `enqueueIfWalkable()` 入队
- BFS 主循环：出队 → 四方向扩展 → `enqueueIfWalkable()` 检查边界/障碍/已访问 → 标记 reachable + 入队
- 返回可达位图

（2）`enqueueIfWalkable(col, row, obstacles, reachable, queue)`：
- 边界检查 `isInsideMap()` → 障碍检查 → 已访问检查
- 三项均通过则 `reachable[row][col] = true` + `queue.add(new int[]{col, row})`

（3）`markSealedFreeCells(obstacles, reachable, width, height)`：
- 双重循环 → `!obstacles[row][col] && !reachable[row][col]` → 标记 `sealed[row][col] = true`
- 即：不是障碍物、但 BFS 未能到达的空格

（4）`isInsideMap(col, row, width, height)`：`col >= 0 && col < width && row >= 0 && row < height`

**使用场景**：
- TaskConfigurator 在障碍物生成后调用，确保初始地图无密封区（如有则重新生成）
- Navigator 和 TargetPlanner 使用 `mapSealed` 位图过滤不可达格
- BlackboardClient 探索率计算：`explorable = total - obstacles - sealed`

#### 2.10.7 SpawnPositionSelector（出生点选择器）

按「周围未探索格数量 + 与已有车的距离」的加权评分选择最优车辆出生点，供 TaskConfigurator 初始化车辆位置时使用。

**类设计**：
- `final` 类，私有构造器，所有方法均为 `static` 工具方法
- 常量：`NEIGHBOR_RADIUS = 4`（邻域搜索半径）、`UNEXPLORED_CELL_WEIGHT = 10`（未探索格权重）、`CAR_SEPARATION_WEIGHT = 5`（车间距权重）

**主方法** `selectBest(obstacles, explored, occupiedByCars, sealed, margin, random)`：
1. `collectCandidates()`：收集所有合法候选格 → 若无候选则返回 `Optional.empty()`
2. 遍历候选格 → `scoreCandidate()` 评分
3. 记录最高分 → 平局时收集所有最高分候选 → 随机选择一个
4. 返回 `Optional<Point>`

**关键内部方法**：

（1）`collectCandidates(obstacles, explored, occupiedByCars, sealed, margin, width, height)`：
- 搜索范围：`margin <= row < height-margin`（边缘留白 margin 防止出生在边界）
- 每格调用 `isSpawnable()`：`isWalkable(obstacles, sealed, col, row) && !occupiedByCars[row][col]`
- `isWalkable()` = `!obstacles[row][col] && !sealed[row][col]`

（2）`scoreCandidate(candidate, obstacles, explored, occupiedByCars, sealed)`：
- `countUnexploredNearby()`：以候选点为中心，5×5 方形区域（半径 4）内遍历 → 跳过界外/障碍/密封/已占用/已探索格 → 统计未探索且可通行的格子数
- `nearestOccupiedDistance()`：遍历全图找最近已被占用的格 → 若无车则返回 `mapWidth + mapHeight`（极大值）
- **评分公式**：`score = unexploredNearby × 10 + nearestCarDistance × 5`

（3）`nearestOccupiedDistance(candidate, occupiedByCars)`：
- 双重循环遍历全图 → 找到 `occupiedByCars[row][col]` 为 true 的格
- 计算曼哈顿距离 = `|candidate.x - col| + |candidate.y - row|`
- 取最小值

**使用场景**：TaskConfigurator 依次为每辆车选择出生点，每选一辆更新 `occupiedByCars` 位图，确保后续车辆不会与已有车重叠。

---

### 2.11 infra 包——部署基础设施

#### 2.11.1 InfraConnectionConfig（基础设施连接配置）

```java
public record InfraConnectionConfig(String redisHost, int redisPort, String mqHost, int mqPort)
```

**默认值常量**：
- `DEFAULT_REDIS_HOST = "localhost"`
- `DEFAULT_REDIS_PORT = 6379`
- `DEFAULT_MQ_HOST = "localhost"`
- `DEFAULT_MQ_PORT = 5672`

**静态工厂方法**：

| 方法 | 说明 |
|------|------|
| `localhost()` | 返回全 localhost 默认配置 |
| `resolve(String[] args)` | 合并配置文件 + 命令行：先加载 `DeployConfigLoader.loadOptional()`，再将命令行 `--redis-host/--redis-port/--mq-host/--mq-port` 参数覆盖到结果 |
| `fromArgs(String[] args)` | 仅从命令行参数解析，未传入项使用 localhost 默认值（不读取配置文件） |

**配置优先级**（`resolve` 方法）：命令行显式参数 > `deploy/infra.local.json` 配置文件 > localhost 默认值。

**命令行参数解析**（`parseOverrides(args)`）：
- 遍历 `args` 数组，匹配 `--redis-host` / `--redis-port` / `--mq-host` / `--mq-port` 四个开关
- 每个开关提取下一个数组元素作为值（`requireValue`）
- 端口参数额外调用 `requireInt` 做数字解析（非数字抛出 `IllegalArgumentException`）
- 未匹配的开关（如 `Car001`、`--dynamic` 等）被忽略
- 内部使用 `ArgOverrides` record 封装四个 `Optional` 字段

**辅助方法**：
- `requireValue(args, index, key)`：越界检查 + 取值
- `requireInt(args, index, key)`：取值后 `Integer.parseInt`，解析失败抛出 `IllegalArgumentException`

**使用模式**：各模块 `main` 方法启动时调用 `InfraConnectionConfig.resolve(args)` → 传递给 `BlackboardClient` 和 `MessageBus` 构造器。

#### 2.11.2 DeployConfig（部署配置）

分布式部署的完整配置 record，对应 `deploy/infra.local.json` 的结构。

```java
public record DeployConfig(
    String redisHost, int redisPort, String mqHost, int mqPort,
    String role,           // "infra" / "planner" / "car" / "display"
    String displayHost,    // 显示主机地址
    int displayHttpPort,   // HTTP 端口（默认 8887）
    int displayWsPort,     // WebSocket 端口（默认 8888）
    List<String> cars)     // 默认 ["Car001", "Car002", "Car003"]
```

**角色常量**：
| 常量 | 值 | 含义 |
|------|-----|------|
| `ROLE_INFRA` | `"infra"` | 基础设施节点（Redis + RabbitMQ + SQL Server + HTTP 服务） |
| `ROLE_PLANNER` | `"planner"` | 规划节点（Navigator + TargetPlanner + StrategySupervisor + TaskConfigurator） |
| `ROLE_CAR` | `"car"` | 车载代理节点（CarAgent 实例，可独立部署） |
| `ROLE_DISPLAY` | `"display"` | 前端展示节点（WebSocket 推送 + 静态页面服务） |

**默认值** `DEFAULT_CARS = List.of("Car001", "Car002", "Car003")`。

**静态方法**：

| 方法 | 说明 |
|------|------|
| `localhostDefaults()` | 返回全 localhost 默认配置：Redis `localhost:6379`、MQ `localhost:5672`、role=`"infra"`、displayHost=`"localhost"`、HTTP 端口 `8887`、WS 端口 `8888`、3 辆默认车 |
| `toInfraConnectionConfig()` | 从 DeployConfig 提取 `(redisHost, redisPort, mqHost, mqPort)` 构造 `InfraConnectionConfig`，用于传递给 `BlackboardClient` / `MessageBus` |

**配置字段说明**：
- `role`：决定当前 JVM 进程启动哪些组件。Launcher 读取此字段后按角色启动对应模块
- `displayHost` / `displayHttpPort` / `displayWsPort`：前端部署地址，planner/car 节点需要此信息连接 Display 推送
- `cars`：本节点管理的车辆 ID 列表，car 节点通常只包含自己负责的车辆

#### 2.11.3 DeployConfigLoader（配置加载器）

从 `deploy/infra.local.json` 或环境变量 `CAR_HOMEWORK_CONFIG` 指定的路径加载 JSON 配置。

**类设计**：
- `final` 类，私有构造器，所有方法均为 `static` 工具方法
- 常量：`ENV_CONFIG_PATH = "CAR_HOMEWORK_CONFIG"`、`DEFAULT_RELATIVE_PATH = "deploy/infra.local.json"`

**配置解析流程**：

```
loadOptional()
  └─► resolveConfigPath()          // 路径解析
        ├─ System.getenv("CAR_HOMEWORK_CONFIG") → 非空 → Path.of(envPath)
        └─ 否则 → Path.of("deploy/infra.local.json")
  └─► loadFrom(path)
        └─► readConfig(path)        // 文件读取 + JSON 解析
              ├─ Files.isRegularFile(path) → false → Optional.empty()
              └─ JSON.parseObject(Files.readString(path))
                   └─► parse(root)  // 字段提取
```

**核心方法**：

（1）`resolveConfigPath()`：
- 优先检查环境变量 `CAR_HOMEWORK_CONFIG`：支持跨机部署时指定不同配置路径
- 环境变量为空或空白 → 回退到相对路径 `deploy/infra.local.json`

（2）`readConfig(path)`：
- 检查文件存在性 `Files.isRegularFile(path)` → 不存在返回 `Optional.empty()`
- 使用 fastjson2 `JSON.parseObject(Files.readString(path))` 解析 JSON
- 调用 `parse(root)` 提取字段
- IO 异常包装为 `IllegalStateException("无法读取部署配置: " + path)`

（3）`parse(JSONObject root)`：
- 以 `DeployConfig.localhostDefaults()` 为默认值基底
- 逐个字段调用 `textOrDefault(root, key, default)` 或 `intOrDefault(root, key, default)` 提取
- `textOrDefault`：取值→非空非空白→trim；否则返回默认值
- `intOrDefault`：`root.getInteger(key)` → null 则取默认值
- `parseCars(root, defaults)`：从 `cars` JSON Array 逐元素读取 → 过滤空字符串 → 非空则返回 `List.copyOf`，全空则返回默认车列表

**使用模式**：各模块 `main` 通过 `DeployConfigLoader.loadOptional()` 尝试加载配置文件 → `InfraConnectionConfig.resolve(args)` 合并命令行覆盖 → 传递给中间件客户端。

---

### 2.12 DynamicObstacleUtil（动态障碍物工具）

供 Controller 的 StatusDispatcher 每 N 拍调用（`DYNAMIC_OBSTACLE_INTERVAL = 20` tick 一次），随机增删地图上的障碍物以增加任务动态性。通过引入环境变化迫使车辆重新规划路径，增加模拟的真实感和难度。

**类设计**：
- 包路径：`com.substation.common`（顶级类，非子包）
- `final` 类，可实例化（非 static 工具类），内部持有 `Random` 实例
- 三个静态常量控制行为边界

**参数常量**：
| 常量 | 值 | 说明 |
|------|-----|------|
| `MAX_ADD_COUNT` | 2 | 每次最多新增障碍物数 |
| `MAX_REMOVE_COUNT` | 2 | 每次最多移除障碍物数 |
| `MAX_RANDOM_ATTEMPTS` | 50 | 随机位置试选上限（防止全地图满障碍时无限循环） |

**主方法** `generate(BlackboardClient bb, int mapWidth, int mapHeight, Set<Point> carPositions)`：

```
generate(bb, width, height, carPositions)
  ├─► collectExistingObstacles()           // 扫描全图，收集所有现有障碍物坐标
  ├─► removeRandomObstacles()              // 随机打乱 → 移除 min(MAX_REMOVE_COUNT, size) 个
  │     └─ bb.setBlock(y, x, false)        //    逐个写入 Redis mapBlock
  └─► addRandomObstacles()                 // while added < 2 && attempts < 50
        └─ 随机 (x, y) → 不在 carPositions 中 && !bb.isBlocked(y, x)
           → bb.setBlock(y, x, true) → added++
```

**内部方法详细**：

（1）`collectExistingObstacles(bb, width, height)`：
- 双重循环遍历全图 `(row, col)` → `bb.isBlocked(row, col)` → 收集为 `Point(col, row)`
- 返回 `List<Point>`（row-major 顺序）

（2）`removeRandomObstacles(bb, obstacles, changes)`：
- `Collections.shuffle(obstacles, random)` 随机打乱现有障碍物列表
- 取前 `min(MAX_REMOVE_COUNT, obstacles.size())` 个 → `bb.setBlock(y, x, false)` + 记录日志 `"移除(x,y)"`

（3）`addRandomObstacles(bb, width, height, carPositions, changes)`：
- while 循环：`added < MAX_ADD_COUNT && attempts < MAX_RANDOM_ATTEMPTS`
- 每轮 `random.nextInt(width)` / `random.nextInt(height)` 生成随机坐标
- 条件：`!carPositions.contains(candidate)`（不覆盖车辆位置）+ `!bb.isBlocked(y, x)`（不覆盖已有障碍物）
- 满足条件 → `bb.setBlock(y, x, true)` + `added++`
- 50 次尝试用完仍无法新增 → 静默放弃（地图可能已饱和）

**返回值**：`List<String>` 变更日志（`Collections.unmodifiableList` 包装），如 `["新增(12,8)", "移除(25,3)"]`，供 Controller 日志记录。

**使用场景**：StatusDispatcher 在 `dispatch()` 主循环中每 20 tick 调用一次 —— `if (tick % DYNAMIC_OBSTACLE_INTERVAL == 0)` 时触发 `dynamicObstacleUtil.generate(bb, mapWidth, mapHeight, carPositions)`。

---

## 3. Controller 模块详细设计

Controller 模块是整个系统的**全局调度中心**，负责以固定节拍发现所有车辆并按状态分派处理。

### 3.1 模块结构

```
com.substation.controller
├── ControllerMain      入口：启动连接、锁单实例、创建组件
├── StatusDispatcher    核心调度：状态机分派 + 消息收发
├── TickScheduler       节拍定时器：ScheduledExecutorService 驱动
└── CommandHandler      消息路由：解析 MQ 消息 → 分发到对应组件
```

---

### 3.2 ControllerMain（控制器主入口）

**职责**：初始化中间件连接、绑定消息监听、注册关闭钩子，确保全局只有一个 Controller 实例运行。

**启动流程**：

```
1. 创建 BlackboardClient(redisHost, redisPort, 30, 30)
2. acquireControllerLock() → SET NX EX 30s
   └─ 失败 → System.exit(1)，防止多实例
3. 创建 MessageBus(mqHost, mqPort, "guest", "guest")
4. connect() → 声明所有队列 + Fanout 交换机
   ├─ declareControllerQueue() → "ControllerCmd"
   ├─ purgeQueue("ControllerCmd") → 清空积压
   ├─ declareTargetPlannerQueue() → "TargetPlannerCmd"
   ├─ declareNavigatorQueue() → "NavigatorCmd"
   ├─ declareTaskConfigQueue() → "TaskConfigCmd"
   ├─ declareStrategySupervisorQueue() → "StrategySupervisorCmd"
   └─ declareFanoutExchange() → "UpdateView" (fanout)
5. 创建 StatusDispatcher(bb, bus)
6. 创建 TickScheduler(dispatcher) → 单线程守护线程
7. dispatcher.setScheduler(scheduler)
8. 创建 CommandHandler(bus, dispatcher, scheduler)
9. bus.subscribe("ControllerCmd", handler::handle) → 绑定消息监听
10. 注册 ShutdownHook: shutdown(), releaseControllerLock(), close()
```

**关键设计点**：
- 使用 Redis `SET controller:instance 1 NX EX 30` 实现**单实例锁**，TTL 30 秒防止宕机后锁永不释放
- 启动前 `purgeQueue` 清空 ControllerCmd 队列中的旧消息
- 使用 `ScheduledExecutorService` 单线程守护线程驱动 tick loop
- JVM 关闭时按序释放：scheduler → lock → bus → bb

---

### 3.3 StatusDispatcher（状态分派器）——核心

**职责**：每个 tick 执行一次完整的调度循环，是系统最核心的调度逻辑。

#### 3.3.1 常量和阈值

| 常量 | 值 | 说明 |
|------|-----|------|
| `EXPLORATION_COMPLETE` | 100 | 探索完成阈值 |
| `ALL_IDLE_COMPLETE_TICKS` | 30 | 全车 IDLE 连续 tick 数，超时强制完成 |
| `BLOCKED_TIMEOUT_MIN/MAX` | 2~5 | 阻塞随机超时范围（打破死锁） |
| `MOVING_STUCK_TICKS` | 2 | 连续 MOVING 超此时长则强制切 READY |
| `WAITING_ROUTE_TIMEOUT_TICKS` | 5 | 等待路径超时节拍数 |
| `SUPERVISE_RATE_THRESHOLD` | 85 | 全局探索率超此阈值则跳过策略监督 |
| `SUPERVISE_COOLDOWN_TICKS` | 15 | 同辆车两次监督间的最小 tick 间隔 |
| `DYNAMIC_OBSTACLE_INTERVAL` | 20 | 动态障碍物生成间隔 |

#### 3.3.2 并发安全设计

使用 `ConcurrentHashMap.newKeySet()` 和 `ConcurrentHashMap` 管理以下集合：

| 集合 | 用途 |
|------|------|
| `pendingTargetRequests` | 已发送 ASSIGN_TARGET 等待响应的 carId 集合 |
| `pendingPlanRequests` | 已发送 PLAN_ROUTE 等待响应的 carId 集合（防重复发） |
| `pendingMoveRequests` | 已发送 TICK_MOVE 等待 ACK 的 carId 集合（防跳格） |
| `awaitingSupervision` | 已发送 SUPERVISE_ROUTE 等待监督结果的 carId 集合 |
| `supervisedFlags` | 被监督器标记需优化的车辆集合 |
| `movingTickCounts` | Map<carId, Integer> 车辆连续 MOVING 计数 |
| `waitingRouteTickCounts` | Map<carId, Integer> 车辆连续 WAITING_ROUTE 计数 |
| `blockedTimeoutTicks` | Map<carId, Integer> 每车随机阻塞超时阈值 |
| `lastSupervisedTick` | Map<carId, Integer> 每车上次被监督的 tick 号 |
| `declaredCarQueues` | ConcurrentHashMap.newKeySet() 已声明 MQ 队列的 carId |

#### 3.3.3 调度主循环 dispatch()

```
┌─────────────────────────────────────────┐
│  dispatch() 每次 tick 调用               │
├─────────────────────────────────────────┤
│  1. 检查 taskActive（任务是否激活）       │
│  2. tick++                              │
│  3. isExplorationComplete()? → completeTask() │
│  4. discoverCarIds() 发现所有车辆         │
│  5. ensureCarQueuesDeclared() 确保队列    │
│  6. 遍历每辆车 → dispatchCar(carId, status)│
│     ├─ IDLE          → sendAssignTarget()│
│     ├─ WAITING_ROUTE → checkAndPlanRoute()│
│     ├─ MOVING        → checkMovingStuck()│
│     ├─ BLOCKED       → checkBlockedTimeout()│
│     └─ READY         → 保持（下一步统一处理）│
│  7. 兜底：pendingPlanRequest 但 route 已就绪 → 切 READY│
│  8. sendReadyCarMoves() 对 READY 车发送 TICK_MOVE│
│  9. 全部车 IDLE 且无 pending → allIdleTicks++│
│     └─ >= 30 tick → 强制 completeTask()│
│ 10. 再次检查探索是否完成                   │
└─────────────────────────────────────────┘
```

#### 3.3.4 状态分派逻辑 dispatchCar()

```java
switch (status) {
    case IDLE:
        // 如果探索未完成（!isExplorationComplete()），发送 ASSIGN_TARGET
        // 使用 pendingTargetRequests 防重复
        sendAssignTarget(carId);
        break;
    case WAITING_ROUTE:
        // 如果 route 已就绪 → 切 READY
        // 等待超过 WAITING_ROUTE_TIMEOUT_TICKS(5) → 清除目标，切 IDLE
        // 否则发送 PLAN_ROUTE（使用 pendingPlanRequests 防重复）
        checkAndPlanRoute(carId);
        break;
    case MOVING:
        // 连续 MOVING 超过 MOVING_STUCK_TICKS(2) → 强制切 READY 重新触发移动
        checkMovingStuck(carId);
        break;
    case BLOCKED:
        // 随机超时阈值（2~5 tick）→ 超时则清除路径+目标+阻塞标记，切 IDLE
        checkBlockedTimeout(carId);
        break;
    case READY:
        // 不在此处处理，由 sendReadyCarMoves() 统一发送
        break;
}
```

**状态清理规则**：每次 dispatchCar 开头会清理不匹配的旧状态：
- status != MOVING → 清除 movingTickCounts
- status != WAITING_ROUTE → 清除 waitingRouteTickCounts
- status != READY && status != MOVING → 清除 pendingMoveRequests

#### 3.3.5 策略监督集成

当车辆路径规划成功（`onRoutePlanned`）时：
1. 检查 `shouldSupervise(carId)`：全局探索率 < 85% 且冷却已过（上次监督距今 >= 15 tick）
2. 满足条件 → `awaitingSupervision.add(carId)` + `sendSuperviseRoute(carId)`：发送给 StrategySupervisor
3. 监督完成的回调 `onRouteSupervisionFinished`：切 READY
4. 路由重合回调 `onRouteOverlapReassign`：清除路径+目标，切 IDLE 重新分配

#### 3.3.6 移动指令发送机制

`sendReadyCarMoves()` 对所有 READY 车发送 TICK_MOVE：
1. 跳过 `awaitingSupervision` 中的车
2. `trySendTickMove(carId)` 使用 `pendingMoveRequests` 防重复
3. 每 tick 最多对每辆车发送一条 TICK_MOVE（等待 ACK 期间不重发）
4. 所有 ACK 收到后（`pendingMoveRequests.isEmpty()`）→ `broadcastRefresh()` 刷新前端

#### 3.3.7 任务生命周期管理

```
┌──────────────┐   SET_CONFIG    ┌──────────────┐
│  等待配置     │ ──────────────► │  初始化中     │
│ (taskActive   │                │ prepareForNew │
│  = false)     │                │  Config()     │
└──────────────┘                └──────┬───────┘
                                      │ TASK_READY
                               ┌──────▼───────┐
                               │   运行中      │
                               │ (taskActive   │
                               │   = true)     │
                               └──────┬───────┘
                                      │ 探索完成 / RESET
                               ┌──────▼───────┐
                               │  completeTask │
                               │ forwardReset  │
                               └──────────────┘
```

- `prepareForNewConfig()`：停止调度 + 清空所有 pending 集合（`clearPendingState()`）
- `onTaskReady()`：清空 pending + 激活调度 + 重置 tick=0 + 声明小车队列 + 应用 tick 间隔
- `completeTask()`：停止 scheduler + 冻结所有车（clearRoute/clearTarget/clearBlockedTick/setStatus IDLE）+ 设置 `taskActive=false` + 广播最终状态
- `forwardReset()`：停止 scheduler + 转发 RESET 到 TaskConfigurator + 清空 pending

#### 3.3.8 消息收发

| 消息方向 | 方法 | 消息类型 | 目标队列 |
|----------|------|----------|----------|
| Controller → TargetPlanner | `sendAssignTarget()` | `ASSIGN_TARGET` | `TargetPlannerCmd` |
| Controller → Navigator | `checkAndPlanRoute()` | `PLAN_ROUTE` | `NavigatorCmd` |
| Controller → Car | `trySendTickMove()` | `TICK_MOVE` | `Car_{carId}` |
| Controller → Car | `sendBlockedTimeout()` | `BLOCKED_TIMEOUT` | `Car_{carId}` |
| Controller → StrategySupervisor | `sendSuperviseRoute()` | `SUPERVISE_ROUTE` | `StrategySupervisorCmd` |
| Controller → TaskConfigurator | `forwardConfig()` | `FORWARD_CONFIG` | `TaskConfigCmd` |
| Controller → TaskConfigurator | `forwardReset()` | `FORWARD_RESET` | `TaskConfigCmd` |
| Controller → Display | `broadcastRefresh()` | `REFRESH_ALL` | `UpdateView` (fanout) |

---

### 3.4 TickScheduler（节拍调度器）

**职责**：以固定间隔驱动 StatusDispatcher 的 dispatch() 方法。

**核心实现**：
- `ScheduledExecutorService` 单线程守护线程（线程名 `"tick-scheduler"`）
- `scheduleWithFixedDelay(this::tickLoop, 0, intervalMs, MILLISECONDS)`：固定延迟调度
- `start()` / `stop()` / `togglePause()` / `resetPaused()` / `setInterval(ms)` / `shutdown()`
- `tickLoop()` 检查 `paused` 标志，暂停时跳过
- 默认间隔 `DEFAULT_INTERVAL_MS = 500ms`

**暂停/恢复**：前端通过 `TOGGLE_PAUSE` 消息切换 paused 标志，tickLoop 在暂停时不做任何操作。

---

### 3.5 CommandHandler（命令处理器）

**职责**：解析 ControllerCmd 队列中的 JSON 消息，按消息类型 `type` 字段路由到对应组件。

**消息路由表**：

| 消息类型 | 处理逻辑 |
|----------|----------|
| `TASK_READY` | `dispatcher.onTaskReady()` → `scheduler.stop()` → `scheduler.resetPaused()` → `scheduler.start()` |
| `TARGET_ASSIGNED` | 检查 paused/active → `dispatcher.onTargetAssigned(carId, success)` |
| `ROUTE_PLANNED` | 检查 paused/active → `dispatcher.onRoutePlanned(carId, routeFound)` |
| `MOVED` / `ROUTE_DONE` | `dispatcher.onMoveAcknowledged(carId)` |
| `BLOCKED` | `dispatcher.onMoveAcknowledged(carId)`（清除 pendingMoveRequests 防卡住） |
| `ROUTE_OPTIMIZED` | 检查 paused/active + 检查 `overlapReassign` 标志 → `onRouteOverlapReassign` 或 `markSupervised` + `onRouteSupervisionFinished` |
| `SET_CONFIG` | `scheduler.stop()` → `dispatcher.prepareForNewConfig()` → `dispatcher.forwardConfig(data)` |
| `RESET` | `dispatcher.forwardReset()` |
| `TOGGLE_PAUSE` | `scheduler.togglePause()` |
| `SET_TICK_INTERVAL` | 校验范围 100~2000ms → `scheduler.setInterval(interval)` + `dispatcher.applyTickInterval(interval)` |
| `TOGGLE_OBSTACLE` | `dispatcher.toggleObstacle(row, col)` |
| 其他 | 打印"未知消息类型"日志 |

---

## 4. 消息路由与黑板读写

### 4.1 系统消息流全景

```
┌──────────┐          ┌──────────┐          ┌──────────┐
│  Display │          │Controller│          │Target    │
│  (前端)   │          │          │          │Planner   │
└────┬─────┘          └────┬─────┘          └────┬─────┘
     │SET_CONFIG           │ASSIGN_TARGET         │
     ├────────────────────►├─────────────────────►│
     │                     │TARGET_ASSIGNED       │
     │                     │◄─────────────────────┤
     │                     │                      │
     │                     │PLAN_ROUTE   ┌────────┴─────┐
     │                     ├────────────►│  Navigator   │
     │                     │ROUTE_PLANNED│              │
     │                     │◄────────────┤              │
     │                     │             └──────────────┘
     │                     │TICK_MOVE    ┌──────────────┐
     │                     ├────────────►│   Car Agent   │
     │                     │MOVED/BLOCKED│              │
     │                     │◄────────────┤              │
     │                     │             └──────────────┘
     │REFRESH_ALL (fanout) │
     │◄────────────────────┤
     │                     │SUPERVISE_ROUTE ┌─────────────────┐
     │                     ├───────────────►│StrategySupervisor│
     │                     │ROUTE_OPTIMIZED │                 │
     │                     │◄───────────────┤                 │
     │                     │                └─────────────────┘
```

### 4.2 黑板（Redis）读写模式

| 读写方 | 读 Key | 写 Key |
|--------|--------|--------|
| **Controller** | `Car*:Status`, `Car*:RouteList`, `Car*:Position`, `Car*:Target`, `mapView`, `TaskConfig` | `Car*:Status`, `Car*:BlockedTick`, `TaskConfig` (elapsedSeconds, tickInterval, active), `mapBlock` (toggleObstacle) |
| **Car Agent** | `Car*:RouteList`, `mapView`, `TaskConfig` | `Car*:Position`, `Car*:Steps`, `Car*:EffectiveSteps`, `Car*:BlockedTick`, `Car*:History`, `mapView`, `mapHeat` |
| **Navigator** | `mapBlock`, `mapSealed`, `Car*:Position`, 全车 position 集合 | `Car*:RouteList` |
| **TargetPlanner** | `mapBlock`, `mapSealed`, `mapView`, `Car*:Target`, 全车 position 集合 | `Car*:Target` |
| **TaskConfigurator** | — | `TaskConfig`, `mapBlock`, `mapSealed`, `Car*:Position` (初始化), `mapView` |
| **Display** | `mapView`, `mapBlock`, `mapSealed`, `Car*:*`, `mapHeat`, `TaskConfig` | `mapBlock` (toggleObstacle) |
| **StrategySupervisor** | `Car*:RouteList`, `mapBlock`, `mapHeat` | `Car*:RouteList` (覆盖优化路径) |

**读写安全机制**：
- 位置预约锁 `pos:reserve:{x}:{y}`（SET NX EX 3s）防多车同时移动到同一格
- 控制器锁 `controller:instance`（SET NX EX 30s）防多 Controller 实例
- `DistributedLock`（SET NX PX + Lua 原子释放）用于多实例并发互斥

---

## 5. 关键代码片段

### 5.1 Controller 单实例锁

```java
// ControllerMain.java:36
if (!bb.acquireControllerLock()) {
    System.err.println("[Controller] 已有实例在运行，退出");
    System.exit(1);
}

// BlackboardClient.java:848
public boolean acquireControllerLock() {
    try (Jedis jedis = pool.getResource()) {
        SetParams params = SetParams.setParams().nx().ex(30);
        return "OK".equals(jedis.set("controller:instance", "1", params));
    }
}
```

### 5.2 Tick 循环核心

```java
// TickScheduler.java:79
private void schedule() {
    if (future != null) {
        future.cancel(false);
    }
    future = executor.scheduleWithFixedDelay(
        this::tickLoop, 0, intervalMs, TimeUnit.MILLISECONDS);
}

private void tickLoop() {
    if (paused) return;
    dispatcher.dispatch();
}
```

### 5.3 状态分派 DFA

```java
// StatusDispatcher.java:386
private void dispatchCar(String carId, CarStatus status) {
    // 清理不匹配的旧状态
    if (status != CarStatus.MOVING) movingTickCounts.remove(carId);
    if (status != CarStatus.WAITING_ROUTE) waitingRouteTickCounts.remove(carId);
    if (status != CarStatus.READY && status != CarStatus.MOVING) pendingMoveRequests.remove(carId);
    switch (status) {
        case IDLE          -> { if (!isExplorationComplete()) sendAssignTarget(carId); }
        case WAITING_ROUTE -> checkAndPlanRoute(carId);
        case MOVING        -> checkMovingStuck(carId);
        case BLOCKED       -> checkBlockedTimeout(carId);
        case READY         -> {} // 在 sendReadyCarMoves() 中统一处理
    }
}
```

### 5.4 加权探索路径搜索

```java
// ExplorationPathCosts.java
public static final int UNEXPLORED_STEP_COST = 1;    // 未探索格——优先走
public static final int EXPLORED_STEP_COST = 5;       // 已探索格——5倍代价，避免空跑

// ExplorationWeightedPathFinder.java:73
int stepCost = ExplorationPathCosts.stepCost(explored, nextX, nextY);
int newCost = minCost[row][col] + stepCost;
```

### 5.5 探索率计算

```java
// BlackboardClient.java:268
public int getExplorationRate() {
    long[] progress = countExplorationProgress();
    long explorable = progress[0];
    long exploredOnExplorable = progress[1];
    if (explorable <= 0) return 100;
    return (int) (exploredOnExplorable * 100 / explorable);
}
// 分母 = 总格子 - 障碍物 - 密封区（不可达空格）
```

### 5.6 会话管理与单点登录

```java
// SessionManager.java:36
public String createSession(String username, String role) {
    byte[] bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    String token = HexFormat.of().formatHex(bytes);
    // ...
    invalidatePreviousSession(jedis, username);  // 挤掉旧会话
    jedis.setex("auth:session:" + token, 1800, JSON.toJSONString(session));
    jedis.setex("auth:user_session:" + username, 1800, token);
    return token;
}
```

### 5.7 动态障碍物生成

```java
// DynamicObstacleUtil.java:35
public List<String> generate(BlackboardClient bb, int mapWidth, int mapHeight,
                              Set<Point> carPositions) {
    List<String> changes = new ArrayList<>();
    List<Point> existingObstacles = collectExistingObstacles(bb, mapWidth, mapHeight);
    removeRandomObstacles(bb, existingObstacles, changes);  // 随机移除最多 2 个
    addRandomObstacles(bb, mapWidth, mapHeight, carPositions, changes);  // 随机新增最多 2 个
    return changes;
}
```

### 5.8 SQL Server 建表（幂等）

```sql
-- DatabaseManager.java:82
IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name='users')
CREATE TABLE users (
    id INT IDENTITY(1,1) PRIMARY KEY,
    username NVARCHAR(50) NOT NULL UNIQUE,
    password NVARCHAR(200) NOT NULL,
    role NVARCHAR(20) NOT NULL DEFAULT 'simulator',
    display_name NVARCHAR(50) NULL,
    status NVARCHAR(20) NOT NULL DEFAULT 'active',
    created_at DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
);
-- registration_requests, operation_logs, simulation_runs, simulation_run_stats 表同理
```

### 5.9 位置预约锁（防多车重叠）

```java
// BlackboardClient.java:996
public boolean tryReservePosition(int x, int y, String carId) {
    try (Jedis jedis = pool.getResource()) {
        SetParams params = SetParams.setParams().nx().ex(3);  // 3 秒 TTL
        return "OK".equals(jedis.set("pos:reserve:" + x + ":" + y, carId, params));
    }
}
```

---

## 附录：文件清单

### Common 模块（53 个 Java 文件）

| 包 / 类 | 文件 |
|----------|------|
| `model` | `Point.java`, `CarStatus.java`, `AlgorithmType.java`, `RouteStep.java`, `SimulationState.java` |
| `redis` | `BlackboardClient.java`, `DistributedLock.java`, `MapBitmapSnapshot.java` |
| `mq` | `MessageBus.java`, `MessageTypes.java`, `QueueNames.java`, `MessageBuilder.java` |
| `auth` | `AuthApiHandler.java`, `AuthFilter.java`, `AuthResponses.java`, `SessionManager.java`, `UserStore.java` |
| `auth.model` | `LoginRequest.java`, `LoginResponse.java`, `SessionInfo.java`, `SessionValidation.java`, `UserInfo.java` |
| `sql` | `DatabaseManager.java`, `SqlUserStore.java`, `RegistrationStore.java`, `OperationLogStore.java` |
| `sql.model` | `UserRecord.java`, `RegistrationRecord.java`, `OperationLogRecord.java` |
| `analysis` | `AnalysisApiHandler.java`, `AnalysisEngine.java`, `SimulationRecordService.java`, `SimulationStatsStore.java` |
| `analysis.model` | `AnalysisQuery.java`, `CarStatistics.java`, `SimulationStatsSummary.java`, `SummaryStatistics.java` |
| `replay` | `ReplayApiHandler.java`, `ReplayDataBuilder.java`, `RunArchiver.java`, `SimulationRunStore.java` |
| `replay.model` | `SimulationRunRecord.java`, `SimulationRunStatus.java`, `SimulationRunSummary.java` |
| `admin` | `AdminApiHandler.java` |
| `map` | `ExplorationWeightedPathFinder.java`, `ExplorationPathCosts.java`, `ShortestHopPathFinder.java`, `FrontierCellFinder.java`, `UnexploredClusterFinder.java`, `UnexploredCluster.java`, `ReachabilityAnalyzer.java`, `SpawnPositionSelector.java` |
| `infra` | `DeployConfig.java`, `DeployConfigLoader.java`, `InfraConnectionConfig.java` |
| 顶级 | `DynamicObstacleUtil.java` |

### Controller 模块（4 个 Java 文件）

| 文件 | 行数 | 职责 |
|------|------|------|
| `ControllerMain.java` | 80 | 入口：中间件连接、锁单实例、创建组件、注册关闭钩子 |
| `StatusDispatcher.java` | 629 | 核心调度：车辆发现、状态机分派、消息收发、完成判定 |
| `TickScheduler.java` | 93 | 节拍定时器：ScheduledExecutorService 单线程驱动 |
| `CommandHandler.java` | 123 | 消息路由：解析 MQ JSON 消息，按 type 路由到对应组件 |
