# Person B 设计文档 — Car 模块 + 认证系统 + SQL Server 用户管理 + 统计分析 + 历史回放 + 管理员系统

> **负责人**：Person B  
> **分支**：car + auth 开发分支  
> **编写日期**：2026-06-26  
> **状态**：已完成  

---

## 一、Person B 职责概述

Person B 负责变电站巡检仿真系统中以下六大子系统的设计与实现：

| 子系统 | 核心内容 | 代码位置 |
|--------|---------|----------|
| **Car 模块** | 小车知识源进程，五状态机 + 原子移动执行 + 防碰撞 | `car/src/main/java/com/substation/car/` |
| **认证系统** | 登录/注册/登出/改密，会话管理，角色分级鉴权 | `common/src/main/java/com/substation/common/auth/` |
| **SQL Server 用户管理** | 用户表/注册审核/操作日志持久化 | `common/src/main/java/com/substation/common/sql/` |
| **管理员系统** | 用户列表/注册审批/操作日志查询，admin 专属 API | `common/src/main/java/com/substation/common/admin/` |
| **统计分析系统** | 仿真摘要/单车统计/排行榜，SQL Server 持久化 | `common/src/main/java/com/substation/common/analysis/` |
| **历史回放系统** | 仿真场次归档/位图序列化/按 ID 回放 | `common/src/main/java/com/substation/common/replay/` |

### 1.1 任务书对应关系

| 任务书要求 | 实现 |
|-----------|------|
| 小车可独立启动 | `CarMain` 接受 `carId` 命令行参数 |
| 可动态添加新车 | 新进程启动即自注册到 Redis 黑板，支持 `--dynamic` 标志 |
| 5 状态机 | IDLE / WAITING_ROUTE / READY / MOVING / BLOCKED |
| 原子移动操作 | 分布式锁 + 位置预约锁保护 14 步流程 |
| 防碰撞 | 位置预约锁 `pos:reserve:{x}:{y}` + 同格占据检测 `occupyOtherCar` |
| History 路径记录 | 每步 RPUSH `{x, y, tick}` |
| mapHeat 热力图 | 每步 HINCRBY 点亮计数器 |
| 用户登录认证 | BCrypt + Redis 会话 Token |
| 三角色权限 | admin / simulator / analyst |
| SQL Server 持久化 | 用户表 / 注册审核 / 操作日志 / 仿真场次 / 仿真统计 |
| 仿真回放 | 场次归档 RunArchiver + 按 ID 查询 ReplayApiHandler |
| 统计分析 | 保存统计 Summary + Leaderboard + 按场次查询 |

---

## 二、Car 模块详细设计

### 2.1 模块总览

Car 是变电站巡检仿真系统的**小车知识源模块**，每个 Car 实例是一个独立 Java 进程。收到 Controller 下发的 `TICK_MOVE` 指令后，沿 Navigator 规划的路径前进一步，并点亮地图、记录轨迹。

### 2.2 文件结构

```
car/
├── pom.xml
└── src/
    ├── main/java/com/substation/car/
    │   ├── CarMain.java          # 进程入口 + 自注册 + CLI 解析
    │   ├── CarAgent.java         # 消息分发 + 状态机入口
    │   └── MoveExecutor.java     # 14 步原子移动执行器
    ├── main/resources/
    │   └── logback.xml
    └── test/java/com/substation/car/
        ├── CarMainTest.java
        ├── CarAgentTest.java     # 6 个测试
        └── MoveExecutorTest.java # 13 个测试
```

### 2.3 CarMain — 进程入口

**启动方式**：

```bash
java -jar car.jar Car001              # TaskConfigurator 预注册车辆
java -jar car.jar Car004 --dynamic    # 页面动态添加，跳过 5 秒注册等待
java -jar car.jar Car002 6380         # 指定 Redis 端口
```

**Launcher 调用方式**（由 Display 模块的 DynamicCarLauncher 调用）：

```java
new CarMain("Car001", "localhost", 6379, "localhost", 5672).start();
```

**关键常量**：

```java
private static final int FALLBACK_W = 30;            // 地图默认宽度
private static final int FALLBACK_H = 30;            // 地图默认高度
private static final int EDGE_MARGIN = 1;             // 出生点边距
private static final int REGISTER_WAIT_MS = 200;      // 注册等待间隔
private static final int REGISTER_WAIT_ATTEMPTS = 25; // 注册等待次数 (共5s)
private static final String DYNAMIC_FLAG = "--dynamic";
```

**启动流程**：

```
1. 解析 CLI 参数（carId、动态标志、自定义端口）
2. 创建 BlackboardClient，从黑板读取地图尺寸（优先 TaskConfig，fallback 30×30）
3. selfRegister() 自注册：
   ├─ 动态添加模式（--dynamic）：
   │   ├─ 黑板已有本车 → 接管，状态重置为 IDLE，清目标/路径
   │   └─ 黑板无本车 → 跳过等待，直接自初始化
   ├─ 预注册模式：
   │   ├─ 每 200ms 轮询，最多 25 次等待 TaskConfigurator 注册
   │   ├─ 已注册 → 跳过
   │   └─ 超时后仍无 → 自初始化
   └─ 自初始化：
       ├─ findSpawnPosition()：加载障碍位图+探索位图+密封区位图+已占用网格
       │   └─ SpawnPositionSelector.selectBest() 随机选空地
       ├─ 写 CarID:Position、Status=IDLE、Steps=0、EffectiveSteps=0
       ├─ illuminateAndHeat() 点亮出生格
       └─ appendCarHistory() 写入历史
4. 连接 RabbitMQ，声明 Car_{carId} 队列
5. 创建 MoveExecutor + CarAgent，订阅消息
6. 注册 shutdown hook（关闭 MQ + Redis）
7. 主线程保持存活
```

**出生点选择算法**：

```java
static Optional<Point> findSpawnPosition(BlackboardClient bb, String carId,
                                          int mapWidth, int mapHeight) {
    // 加载四位图：障碍、已探索、密封区、已占用
    boolean[][] obstacles = bb.loadObstacleBitmap();
    boolean[][] explored = BlackboardClient.bytesToBitmap(bb.getMapViewBytes(), mapWidth, mapHeight);
    boolean[][] sealed = BlackboardClient.bytesToBitmap(bb.getMapSealedBytes(), mapWidth, mapHeight);
    boolean[][] occupied = buildOccupiedGrid(bb, carId, mapWidth, mapHeight);

    // 优先在边界1格以内的内部区域选择
    Optional<Point> interior = SpawnPositionSelector.selectBest(
        obstacles, explored, occupied, sealed, EDGE_MARGIN, SPAWN_RANDOM);
    if (interior.isPresent()) return interior;
    // 内部无空位时放宽到全地图
    return SpawnPositionSelector.selectBest(
        obstacles, explored, occupied, sealed, 0, SPAWN_RANDOM);
}
```

### 2.4 CarAgent — 消息分发器

`CarAgent` 是消息回调入口，由 MessageBus 订阅机制调用。负责两种消息的识别与分发：

| 消息类型 | 来源 | 处理方式 |
|---------|------|---------|
| `TICK_MOVE` | Controller → Car_{carId} | 调用 `MoveExecutor.executeMove(tick)` |
| `BLOCKED_TIMEOUT` | Controller → Car_{carId} | WARN 日志记录，从 Redis 确认状态（只读不写） |
| 未知类型 / 非法 JSON | — | WARN 日志并丢弃，**不崩溃** |

**BLOCKED_TIMEOUT 处理语义**：当此消息到达时，Controller 已完成全部黑板清理（清 RouteList/Target/BlockedTick，写 IDLE），Car 只做日志记录和状态确认，不做任何黑板写入，等待下一轮从 IDLE 重新分配目标。

```java
private void handleBlockedTimeout() {
    log.warn("[{}] 收到 BLOCKED_TIMEOUT，阻塞超时已由 Controller 处理", carId);
    Optional<CarStatus> status = bb.getCarStatus(carId);
    status.ifPresentOrElse(
        s -> log.info("[{}] 当前状态: {}，等待重新分配目标", carId, s.chineseName()),
        () -> log.warn("[{}] 状态 Key 不存在", carId));
}
```

### 2.5 MoveExecutor — 14 步原子移动执行器

#### 2.5.1 主流程

```
executeMove(tick):
  ┌─ 1. 分布式锁 lock:{carId}（SET NX PX 5000）
  │     └─ 获取失败 → WARN，ackMoveDeferred(tick)，return
  ├─ 2. 检查状态 == READY
  │     └─ 非 READY → 释放锁，return
  ├─ 3. 写 Status = MOVING（心跳，防止 Controller 误判崩溃）
  ├─ 4. peekNextRouteStep → 获取下一步位置（不移除）
  │     └─ RouteList 为空 → 写 IDLE + WARN，释放锁，return
  ├─ 5. 更新同格卡住计数（stuckCount）
  ├─ 6. 位置预约锁 pos:reserve:{x}:{y}（SET NX EX 3）
  │     └─ 预约失败 → handleStuckRetryOrReplan
  │          ├─ stuckCount >= 3 → 切 BLOCKED + 发 BLOCKED 消息
  │          └─ stuckCount < 3 → 退回 READY
  │        → ackMoveDeferred，return
  ├─ 7. 障碍物检查：bb.isBlocked(ny, nx)
  │     └─ 是 → clearRoute + clearCarTarget + BLOCKED + setBlockedTick
  │           → sendBlocked + ackMoveDeferred，return
  ├─ 8. 其他车辆占位检查：isOccupiedByOtherCar(nx, ny)
  │     └─ 是 → handleStuckRetryOrReplan（同上），return
  ├─ 9. popNextRouteStep（消费该步）
  ├─10. setCarPosition(carId, nextPos)（更新新位置）
  ├─11. illuminateAndHeat → recordExploration(tick, row, col) + incrementMapHeat
  │      若新探索 → incrementCarEffectiveSteps
  ├─12. incrementCarSteps
  ├─13. appendCarHistory → RPUSH {x, y, tick}
  ├─14. finalizeMove(tick, pos):
  │       ├─ 路径为空 → IDLE + sendRouteDone（ROUTE_DONE 消息）
  │       └─ 路径非空 → READY + sendMoved（MOVED 消息）
  └─    releaseReservePosition + lock.unlock()
```

#### 2.5.2 位置预约锁（防多车重叠）

两辆车可能在同一 tick 的相邻步中移动到同一格。仅靠 `mapBlock` 写入不够（非原子操作），引入 Redis 预约锁：

```java
// BlackboardClient.java: tryReservePosition
public boolean tryReservePosition(int x, int y, String carId) {
    SetParams params = SetParams.setParams().nx().ex(3);  // 3秒超时
    String result = jedis.set("pos:reserve:" + x + ":" + y, carId, params);
    return "OK".equals(result);  // 原子化 SET NX，只有一个 Car 成功
}

// 释放时校验身份，防止误删
public void releaseReservePosition(int x, int y, String carId) {
    String key = "pos:reserve:" + x + ":" + y;
    String val = jedis.get(key);
    if (carId.equals(val)) jedis.del(key);
}
```

#### 2.5.3 卡住重试机制

当预约失败或被其他车占据时，使用同格计数器 `stuckCount` 区分临时争用与真正死锁：

- 目标格变化 → `stuckCount` 重置为 1
- 连续卡在同一格 → `stuckCount++`
- `stuckCount >= 3` → 认定为死锁，切 BLOCKED 状态，等待 Controller 重新规划
- `stuckCount < 3` → 退回 READY，等下一 tick 重试

#### 2.5.4 防碰撞完整策略

| 层级 | 机制 | 说明 |
|------|------|------|
| 1 | **分布式锁** `lock:CarID` | 同一辆车的移动操作串行化 |
| 2 | **位置预约锁** `pos:reserve:{x}:{y}` | 不同车辆对同一格的原子抢占 |
| 3 | **occupancy 检测** | 遍历所有车当前位置检查目标格是否已被占 |
| 4 | **障碍物检查** | 检查目标格 `mapBlock` 位图 |
| 5 | **卡住重试** | 连续 3 次失败 → BLOCKED |
| 6 | **预约锁 TTL 3s** | 防止宕车导致预约锁泄漏 |

#### 2.5.5 3×3 点亮与热力图

```java
// MoveExecutor.illuminateAndHeat: 点亮当前格并更新热力图
private boolean illuminateAndHeat(Point center, int tick) {
    int row = center.y();
    int col = center.x();
    boolean newlyExplored = bb.recordExploration(tick, row, col);  // SETBIT mapView
    bb.incrementMapHeat(row, col);                                   // HINCRBY mapHeat
    return newlyExplored;
}
```

`recordExploration` 内部集成了首次探索判定：`SETBIT mapView offset 1` 返回旧值，若旧值为 0 则 `RPUSH explorationEvents` 记录回放事件。同时排除障碍物格子（`mapBlock` 位已置 1 不计入探索）。

### 2.6 五状态机

**CarStatus 枚举定义**（来自 `common/src/main/java/com/substation/common/model/CarStatus.java`）：

| 状态 | 中文名 | 颜色 | 含义 |
|------|--------|------|------|
| `IDLE` | 空闲 | `#9E9E9E` (灰) | 无目标无路径，等待分配 |
| `WAITING_ROUTE` | 等待路径 | `#FF9800` (橙) | 有目标，等待 Navigator 规划路径 |
| `READY` | 就绪 | `#4CAF50` (绿) | 有目标有路径，等待 TICK_MOVE |
| `MOVING` | 移动中 | `#2196F3` (蓝) | 正在执行移动（心跳状态） |
| `BLOCKED` | 受阻 | `#F44336` (红) | 路径受阻，需重新规划 |

**Car 写入的状态变迁**：

```
                    Car 写入
                    ────────
初始化注册        → IDLE

收到 TICK_MOVE    → MOVING（心跳）
  移动成功        → READY（路径仍有剩余）
                 → IDLE（路径走完）
  下一步障碍      → BLOCKED
  预约/占据连续3次 → BLOCKED
```

| 变迁 | 触发条件 | 写入值 |
|------|----------|--------|
| (初始化) → IDLE | CarMain 自注册 | IDLE |
| READY → MOVING | 收到 TICK_MOVE | MOVING |
| MOVING → READY | pop 后路径非空 | READY |
| MOVING → IDLE | pop 后路径为空（走完） | IDLE |
| MOVING → BLOCKED | peek 到下一步是障碍 / 连续 3 次卡住 | BLOCKED |

> **Car 永不写 `WAITING_ROUTE`**——那是 Controller 的管辖范围。Car 只在自己管辖的状态（IDLE/READY/MOVING/BLOCKED）之间变迁。

### 2.7 消息接口

**接收消息**（订阅 `Car_{carId}` 队列）：

| 消息类型 | JSON 字段 | 处理 |
|---------|----------|------|
| `TICK_MOVE` | `{type, tick}` | executeMove |
| `BLOCKED_TIMEOUT` | `{type, carId}` | 只读验证 |

**发送消息**（发布到 `ControllerCmd` 队列）：

| 消息类型 | data 字段 |
|---------|----------|
| `MOVED` | `{newPosition: {x,y}, routeRemaining: int}` |
| `ROUTE_DONE` | `{finalPosition: {x,y}}` |
| `BLOCKED` | `{blockedPosition: {x,y}, blockedTick: int}` |

### 2.8 Redis Key 读写

**Car 写入的 Key**：

| Key | 写入场景 | 说明 |
|-----|----------|------|
| `{carId}:Position` | 自注册 / 每步移动后 | HSET x y |
| `{carId}:Status` | 状态变迁 | IDLE/READY/MOVING/BLOCKED |
| `{carId}:Steps` | 每步移动后 | INCR |
| `{carId}:EffectiveSteps` | 踩入未探索格时 | INCR |
| `{carId}:BlockedTick` | 检测到障碍/卡住 | 记录受阻 tick |
| `{carId}:History` | 每步移动后 | RPUSH `{x,y,tick}` JSON |
| `mapView` | 每步 recordExploration | SETBIT |
| `mapBlock` | 自注册/移动时旧位清除 | SETBIT |
| `mapHeat` | 每步点亮 | HINCRBY row,col 1 |
| `pos:reserve:{x}:{y}` | 移动前预约 | SET NX EX 3 |
| `explorationEvents` | 首次探索时 | RPUSH `{tick,row,col}` |

**Car 只读的 Key**：`{carId}:RouteList`（peek/pop）、`TaskConfig`、`{carId}:Target`、`mapSealed`

### 2.9 分布式锁

```java
public class DistributedLock {
    private static final String LOCK_KEY_PREFIX = "lock:";
    private static final int DEFAULT_TIMEOUT_MS = 5000;

    public boolean tryLock() {
        SetParams params = SetParams.setParams().nx().px(DEFAULT_TIMEOUT_MS);
        String result = jedis.set(lockKey, lockValue, params);
        return "OK".equals(result);
    }

    public void unlock() {
        // Lua 脚本原子释放：校验 owner 后再删除
        String script =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "    return redis.call('del', KEYS[1]) " +
            "else " +
            "    return 0 " +
            "end";
        jedis.eval(script, singletonList(lockKey), singletonList(lockValue));
    }
}
```

- 超时 5 秒，获取失败时跳过本拍并发送 `ackMoveDeferred`（通知 Controller 释放 tick 槽位）
- 释放用 Lua 脚本验证 ownership（`lockValue = Thread.currentThread() + "-" + timestamp`），避免误删

### 2.10 与其他模块的协作

```
Controller                      Car                       Navigator
    │                             │                           │
    ├── TICK_MOVE ──────────────→│                           │
    │                             │                           │
    │                        executeMove()                    │
    │                             │                           │
    │←── MOVED ──────────────────┤                           │
    │←── ROUTE_DONE ─────────────┤                           │
    │←── BLOCKED ────────────────┤                           │
    │                             │                           │
    ├── BLOCKED_TIMEOUT ────────→│                           │
    │                             │                           │
                              Redis 黑板:
                              {carId}:RouteList (Nav 写 ←→ Car 读)
                              {carId}:Position/Status... (Car 写 → Display 读)
```

---

## 三、登录认证系统

### 3.1 技术选型

| 组件 | 方案 | 理由 |
|------|------|------|
| 用户存储 | Redis Hash（UserStore）+ SQL Server（SqlUserStore） | 双存储：Redis 用于预设账号快速原型，SQL Server 用于生产持久化 |
| 密码加密 | BCrypt | 业界标准 |
| 会话管理 | Redis Session Token | SET NX + TTL 1800s |
| 前后端鉴权 | Bearer Token + AuthFilter | 纯 JDK HttpServer Filter |
| 暴力防护 | 登录失败 5 次锁定 15 分钟 | Redis incr + expire |

### 3.2 三角色权限矩阵

| 功能 | admin | simulator | analyst |
|------|-------|-----------|---------|
| 查看仿真地图 / 车辆状态 | 是 | 是 | 是 |
| 开始 / 暂停 / 重置任务 | 是 | 是 | 否 |
| 修改任务配置参数 | 是 | 是 | 否 |
| 添加小车 | 是 | 是 | 否 |
| 进入统计分析界面 | 否 | 否 | 是 |
| 导出统计数据 | 是 | 否 | 否 |
| 历史回放 | 是 | 是 | 是 |
| 修改密码 | 是 | 是 | 是 |
| 用户管理（审核注册/重置密码） | 是 | 否 | 否 |

**三种角色统一存于 `users` 表，用 `role` 字段区分。**

### 3.3 预设账号

系统首次启动时自动创建：

| 存储位置 | 用户名 | 密码 | 角色 | 显示名 |
|---------|--------|------|------|--------|
| SQL Server (DatabaseManager) | `admin` | `admin123` | admin | 管理员 |
| Redis (UserStore) | `simulator1` | `sim123` | simulator | 仿真员1 |
| Redis (UserStore) | `analyst1` | `ana123` | analyst | 统计分析员1 |

> 注意：`admin` 由 `DatabaseManager.insertPresetAdmin()` 在 SQL Server 端初始化（BCrypt 哈希）；`simulator1` 和 `analyst1` 由 `UserStore.initPresetUsers()` 在 Redis 端初始化。两者分别管理各自的存储层，均为幂等操作。

### 3.4 AuthApiHandler — 认证 API 完整接口

**Base Path**: `/api/auth/`

所有端点均不经过 AuthFilter 鉴权（白名单放行），但 `/logout`、`/me`、`/change-password` 内部通过 token 校验身份。

#### 3.4.1 POST /api/auth/login — 用户登录

请求：
```json
{
    "username": "admin",
    "password": "admin123"
}
```

成功响应 (200)：
```json
{
    "success": true,
    "token": "a1b2c3d4e5f6... (64位 hex)",
    "username": "admin",
    "role": "admin",
    "displayName": "管理员"
}
```

失败响应 (401)：
```json
{
    "success": false,
    "error": "用户名或密码错误"
}
```

失败响应 (400)：
```json
{
    "success": false,
    "error": "用户名和密码不能为空"
}
```

**处理逻辑**：
1. 校验 username/password 非空
2. 调用 `SqlUserStore.authenticate(username, password)` 查询 SQL Server users 表
3. BCrypt 验证密码哈希
4. 成功：`SessionManager.createSession()` 生成 64 字符 hex token，写入 Redis
5. 写入操作日志 `LOGIN`
6. 返回 `LoginResponse` JSON（含 token、username、role、displayName）

#### 3.4.2 POST /api/auth/register — 用户注册（审核制）

请求：
```json
{
    "username": "newSimulator",
    "password": "mypassword",
    "role": "simulator",
    "displayName": "新仿真员"
}
```

成功响应 (200)：
```json
{
    "success": true,
    "message": "注册申请已提交，请等待管理员审核"
}
```

失败响应 (400)：
```json
{"success": false, "error": "用户名和密码不能为空"}
{"success": false, "error": "密码至少需要6位"}
{"success": false, "error": "无效的角色，可选: simulator, analyst"}
{"success": false, "error": "该账号正在审核中，请勿重复提交"}
```

失败响应 (500)：
```json
{"success": false, "error": "提交失败，请稍后重试"}
```

**处理逻辑**：
1. 校验 username 非空、password >= 6 位
2. 校验 role 仅限 `simulator` 或 `analyst`（admin 不可通过注册获取）
3. 检查 `registration_requests` 是否有同名 pending 记录
4. BCrypt 哈希密码
5. `RegistrationStore.insertRequest()` INSERT registration_requests
6. 写操作日志 `REGISTER`
7. 若有同名 pending 记录，写日志 `REGISTER_DUPLICATE`

#### 3.4.3 POST /api/auth/logout — 用户登出

请求：`Authorization: Bearer {token}`

成功响应 (200)：
```json
{
    "success": true,
    "message": "已退出登录"
}
```

**处理逻辑**：
1. 提取 Authorization header 中的 Bearer token
2. `SessionManager.destroySession(token)` 清理 Redis 会话 key
3. 写操作日志 `LOGOUT`

#### 3.4.4 GET /api/auth/me — 获取当前用户信息

请求：`Authorization: Bearer {token}`

成功响应 (200)：
```json
{
    "success": true,
    "username": "admin",
    "role": "admin",
    "displayName": "管理员"
}
```

被挤号响应 (401)：
```json
{
    "success": false,
    "error": "您的账号已在其他设备或窗口登录，当前会话已结束",
    "kicked": true
}
```

未登录响应 (401)：
```json
{
    "success": false,
    "error": "请先登录"
}
```

**处理逻辑**：
1. 提取 token，`validateDetailed` 校验
2. 若 KICKED → 返回挤号提示
3. 若 NOT_FOUND → 返回未登录
4. 若 VALID → 从 `SqlUserStore.getUserInfo()` 查询用户详情返回

#### 3.4.5 POST /api/auth/change-password — 修改密码

请求：`Authorization: Bearer {token}`
```json
{
    "oldPassword": "old123",
    "newPassword": "new456"
}
```

成功响应 (200)：
```json
{
    "success": true,
    "message": "密码修改成功，请重新登录"
}
```

失败响应 (400)：
```json
{"success": false, "error": "请求体为空"}
{"success": false, "error": "新密码至少6位"}
{"success": false, "error": "旧密码不正确"}
```

失败响应 (401)：
```json
{"success": false, "error": "请先登录"}
```

**处理逻辑**：
1. 校验 token，从会话获取 username
2. 校验 newPassword >= 6 位
3. `SqlUserStore.changePassword(username, oldPwd, newPwd)`：
   - 查询当前 BCrypt 哈希，验证 oldPassword
   - BCrypt 哈希 newPassword，UPDATE users 表
4. 成功后 `destroySession(token)`（强制重新登录）
5. 写操作日志 `CHANGE_PASSWORD`

---

### 3.5 SessionManager — 会话管理

**类**: `com.substation.common.auth.SessionManager`  
**存储**: Redis  
**数据模型**: `SessionInfo(username, role, loginAt, lastAccess)`

**Redis Key 结构**：

| Key | 内容 | TTL | 说明 |
|-----|------|-----|------|
| `auth:session:{token}` | SessionInfo JSON | 1800s | 会话数据 |
| `auth:user_session:{username}` | 当前活跃 token | 1800s | 单会话绑定 |
| `auth:kicked:{old_token}` | "1" | 120s | 挤号标记 |

**核心参数**：

```java
SESSION_TTL_SECONDS = 1800        // 30分钟过期
KICKED_FLAG_TTL_SECONDS = 120     // 挤号标记2分钟窗口
TOKEN_BYTE_LENGTH = 32            // 32字节 → 64字符 hex token
```

**会话生命周期**：

```
createSession(username, role):
  1. SecureRandom 生成 32 字节 token（HexFormat 编码 → 64 字符）
  2. invalidatePreviousSession: 读旧 token → 写 auth:kicked:{old_token} + DEL auth:session:{old_token}
  3. SET auth:session:{token} → SessionInfo JSON, EX 1800
  4. SET auth:user_session:{username} → token, EX 1800
  5. 返回 token

validateDetailed(token):
  1. token 为空 → SessionValidation.notFound()
  2. 检查 auth:kicked:{token} 是否存在 → 是则 DEL + 返回 KICKED
  3. GET auth:session:{token} → JSON, 解析 SessionInfo
  4. 比对 auth:user_session:{username} 当前活跃 token
     └─ 不一致 → 返回 KICKED（被挤号）
  5. 一致 → 续期: renewSession (更新 lastAccess, EXPIRE 1800) → 返回 VALID

validate(token):
  → 调用 validateDetailed, 只返回 VALID 时的 SessionInfo

destroySession(token):
  1. DEL auth:session:{token}
  2. DEL auth:kicked:{token}
  3. 若当前 token 是用户活跃 token，则 DEL auth:user_session:{username}
```

**Token 安全性**：
- `SecureRandom` 生成，不可预测
- 64 字符 hex 编码（32 字节随机数）
- 每 1800 秒过期，每次请求自动续期
- 挤号后旧 token 在 120 秒窗口内返回 `kicked: true`，前端显示提示信息

**Token 提取**：
```java
public static String extractToken(String authHeader) {
    if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
    return authHeader.substring(7).trim();
}
```

---

### 3.6 AuthFilter — HTTP 鉴权过滤器

**类**: `com.substation.common.auth.AuthFilter`  
**基类**: `com.sun.net.httpserver.Filter`

**白名单**（无需 Token，直接放行）：

| 前缀匹配 | 说明 |
|---------|------|
| `/login.html` | 登录页 |
| `/index.html` | 首页 |
| `/dashboard.html` | 仪表盘 |
| `/analysis.html` | 统计分析页 |
| `/css/` | 所有 CSS 静态资源 |
| `/js/` | 所有 JS 静态资源 |
| `/api/auth/` | 所有认证 API |
| `/favicon.ico` | 网站图标 |

**鉴权流程**：

```
1. 路径匹配白名单 → chain.doFilter → 直接放行
2. 提取 Authorization: Bearer {token}
3. sessionManager.validateDetailed(token):
   ├─ VALID → 角色检查:
   │           ├─ /api/analysis/export 且非 admin → 403 "权限不足，仅管理员可导出"
   │           └─ 其他 → chain.doFilter 放行
   ├─ KICKED → 401 {success:false, error:"您的账号已在其他设备...", kicked:true}
   └─ NOT_FOUND → 401 {success:false, error:"请先登录"}
```

**响应格式**（通过 AuthResponses 工具类生成）：

未登录 401:
```json
{"success": false, "error": "请先登录"}
```

被挤号 401:
```json
{
    "success": false,
    "error": "您的账号已在其他设备或窗口登录，当前会话已结束",
    "kicked": true
}
```

权限不足 403:
```json
{"success": false, "error": "权限不足，仅管理员可导出"}
```

---

### 3.7 UserStore — Redis 用户存储（预设账号）

**类**: `com.substation.common.auth.UserStore`  
**存储**: Redis Hash `auth:users`  
**用途**: 维护预设普通用户账号（simulator1, analyst1）及暴力破解防护

**数据模型**（Redis Hash field = username, value = JSON）：
```json
{
    "passwordHash": "$2a$...",
    "role": "simulator",
    "displayName": "仿真员1",
    "createdAt": 1719440000
}
```

**方法**：

| 方法 | 说明 |
|------|------|
| `initPresetUsers()` | 幂等创建 3 个预设账号（admin, simulator1, analyst1） |
| `authenticate(username, pwd)` | BCrypt 验证，失败 5 次锁定 15 分钟 |
| `changePassword(...)` | 验证旧密码 → BCrypt 哈希新密码 → HSET |
| `register(...)` | 注册到 Redis（仅 simulator/analyst） |
| `getUserInfo(username)` | 查用户信息（不含密码哈希） |

**暴力破解防护**：

```java
LOGIN_FAIL_MAX = 5           // 最大失败次数
LOGIN_LOCK_SECONDS = 900     // 锁定时间 15 分钟
failKey = "auth:fail:" + username

// 每次失败: INCR + EXPIRE 900
// 登录前检查: failCount >= 5 → 直接返回空
// 成功后: DEL failKey
```

**注册约束**：
- 合法角色：`simulator` / `analyst`（admin 不允许自注册）
- 用户名唯一性：`hexists` 检查
- 密码最小长度：6 位
- 返回 `RegisterResult(success, error)` 记录

---

### 3.8 安全措施汇总

| 事项 | 方案 |
|------|------|
| 密码存储 | BCrypt 哈希，永不明文 |
| 传输安全 | Token 通过 HTTP Header 传输（`Authorization: Bearer xxx`） |
| 暴力破解 | 5 次失败后锁定 15 分钟（`auth:fail:{user}` incr + expire） |
| Token 时效 | 30 分钟无操作过期，每次请求自动续期 |
| 单点登录 | 同一用户仅一个活跃会话，新登录挤旧（`auth:user_session:{user}` 绑定） |
| 挤号通知 | `auth:kicked:{token}` 120 秒窗口，前端收到 `kicked: true` 显示提示 |
| 锁持有验证 | Lua 脚本校验 owner 后释放，防止误删 |

---

## 四、SQL Server 数据库系统

### 4.1 系统架构

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│   Display    │────▶│  AuthApi     │────▶│  SqlUserStore │──▶ SQL Server
│   HttpServer │     │  Handler     │     │              │    users
│              │     │              │     │ Registration │    registration
│              │────▶│  AdminApi    │────▶│ Store        │──▶ _requests
│              │     │  Handler     │     │              │
│              │     │              │     │ OperationLog │    operation
│              │     └──────────────┘     │ Store        │──▶ _logs
│              │                          │              │
│              │     ┌──────────────┐     │ Simulation   │    simulation
│              │────▶│  AnalysisApi │────▶│ StatsStore   │──▶ _run_stats
│              │     │  Handler     │     │              │
│              │     └──────────────┘     │ Simulation   │    simulation
│              │                          │ RunStore     │──▶ _runs
│              │     ┌──────────────┐     │              │
│              │────▶│  ReplayApi   │────▶│              │
└──────────────┘     │  Handler     │     └──────────────┘
                     └──────────────┘
```

**会话管理仍保留在 Redis**（TTL 机制更适合），其余持久化数据迁移至 SQL Server。

### 4.2 DatabaseManager — 数据库连接管理

**类**: `com.substation.common.sql.DatabaseManager`  
**连接信息**（可通过 `deploy/infra.local.json` 中 `dbHost`、`dbPort`、`dbName`、`dbUser`、`dbPassword` 字段配置，未配置时使用以下默认值）：
```
jdbc:sqlserver://localhost:1433;databaseName=substation-patrol;encrypt=false;trustServerCertificate=true
用户名: sa
密码: Root@1234
驱动: com.microsoft.sqlserver.jdbc.SQLServerDriver
```

**启动初始化** `initDatabase()`：
1. 执行 `IF NOT EXISTS ... CREATE TABLE`（5 张表，幂等）
2. 清理废弃表: `DROP TABLE IF EXISTS simulation_stats`
3. `insertPresetAdmin()`: 检查不存在则 INSERT admin（BCrypt 哈希 admin123）

### 4.3 数据库表结构

#### 4.3.1 users 表

```sql
CREATE TABLE users (
    id           INT IDENTITY(1,1) PRIMARY KEY,
    username     NVARCHAR(50)  NOT NULL UNIQUE,
    password     NVARCHAR(200) NOT NULL,              -- BCrypt 哈希
    role         NVARCHAR(20)  NOT NULL DEFAULT 'simulator',
    display_name NVARCHAR(50) NULL,
    status       NVARCHAR(20)  NOT NULL DEFAULT 'active',
    created_at   DATETIME2     NOT NULL DEFAULT SYSUTCDATETIME()
);
```

#### 4.3.2 registration_requests 表

```sql
CREATE TABLE registration_requests (
    id           INT IDENTITY(1,1) PRIMARY KEY,
    username     NVARCHAR(50)  NOT NULL,
    password     NVARCHAR(200) NOT NULL,
    role         NVARCHAR(20)  NOT NULL,
    display_name NVARCHAR(50) NULL,
    status       NVARCHAR(20)  NOT NULL DEFAULT 'pending',
    reviewed_by  NVARCHAR(50) NULL,
    review_time  DATETIME2     NULL,
    created_at   DATETIME2     NOT NULL DEFAULT SYSUTCDATETIME()
);
```

#### 4.3.3 operation_logs 表

```sql
CREATE TABLE operation_logs (
    id          INT IDENTITY(1,1) PRIMARY KEY,
    username    NVARCHAR(50)  NOT NULL,
    action      NVARCHAR(50)  NOT NULL,
    target      NVARCHAR(200) NULL,
    details     NVARCHAR(500) NULL,
    ip_address  NVARCHAR(50)  NULL,
    created_at  DATETIME2     NOT NULL DEFAULT SYSUTCDATETIME()
);
```

**记录的操作类型**（action 字段值）：

| 类别 | action 值 |
|------|----------|
| 认证 | `LOGIN`, `LOGOUT`, `CHANGE_PASSWORD` |
| 注册 | `REGISTER`, `REGISTER_DUPLICATE` |
| 管理 | `APPROVE_REGISTRATION`, `REJECT_REGISTRATION`, `RESET_PASSWORD` |
| 仿真 | `START_TASK`, `RESET`, `ADD_CAR`, `CHANGE_CONFIG` |

**时间处理**：SQL Server 存储 UTC 时间（`SYSUTCDATETIME()`），查询时 `OperationLogStore.queryLogs()` 转换为北京时间（`Asia/Shanghai`）返回给前端。

#### 4.3.4 simulation_runs 表

```sql
CREATE TABLE simulation_runs (
    id              INT IDENTITY(1,1) PRIMARY KEY,
    started_by      NVARCHAR(50) NOT NULL,
    started_at      DATETIME2 NOT NULL,
    ended_at        DATETIME2 NOT NULL,
    map_width       INT NOT NULL,
    map_height      INT NOT NULL,
    car_count       INT NOT NULL,
    algorithm       NVARCHAR(20) NULL,
    obstacle_ratio  NVARCHAR(20) NULL,
    tick_interval   NVARCHAR(20) NULL,
    max_tick        INT NOT NULL DEFAULT 0,
    exploration_rate INT NOT NULL DEFAULT 0,
    status          NVARCHAR(20) NOT NULL,              -- COMPLETED / ABORTED
    map_block_b64   NVARCHAR(MAX) NULL,                 -- 障碍位图 Base64
    map_sealed_b64  NVARCHAR(MAX) NULL,                 -- 密封区位图 Base64
    map_view_final_b64 NVARCHAR(MAX) NULL,              -- 最终探索位图 Base64
    car_histories   NVARCHAR(MAX) NOT NULL,             -- JSON: Map<carId, List<String>>
    exploration_events NVARCHAR(MAX) NOT NULL           -- JSON: List<String>
);
```

每个字段均为仿真结束时的完整快照，用于历史回放。

#### 4.3.5 simulation_run_stats 表

```sql
CREATE TABLE simulation_run_stats (
    run_id               INT NOT NULL PRIMARY KEY,
    saved_by             NVARCHAR(50) NOT NULL,
    saved_at             DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    client_timestamp     BIGINT NOT NULL DEFAULT 0,
    exploration_rate     INT NOT NULL DEFAULT 0,
    tick                 INT NOT NULL DEFAULT 0,
    duration             INT NOT NULL DEFAULT 0,
    total_steps          INT NOT NULL DEFAULT 0,
    total_effective_steps INT NOT NULL DEFAULT 0,
    efficiency_percent   INT NOT NULL DEFAULT 0,
    car_count            INT NOT NULL DEFAULT 0,
    algorithm            NVARCHAR(20) NULL,
    obstacle_ratio       FLOAT NOT NULL DEFAULT 0,
    map_width            INT NOT NULL DEFAULT 0,
    map_height           INT NOT NULL DEFAULT 0,
    balance_score        FLOAT NOT NULL DEFAULT 0,
    payload              NVARCHAR(MAX) NOT NULL,       -- 完整统计 JSON
    CONSTRAINT FK_simulation_run_stats_run
        FOREIGN KEY (run_id) REFERENCES simulation_runs(id)
);
```

外键约束保证统计与回放一一对应。

---

### 4.4 SqlUserStore — 核心用户数据访问层

**类**: `com.substation.common.sql.SqlUserStore`  
**依赖**: `DatabaseManager`

| 方法 | 说明 | SQL 要点 |
|------|------|----------|
| `authenticate(username, pwd)` | 验证密码 → `Optional<UserInfo>` | `WHERE username=? AND status='active'` + BCrypt.checkpw |
| `changePassword(username, old, new)` | 两步：查旧哈希验证 → UPDATE 新哈希 | SELECT password → BCrypt.checkpw → UPDATE |
| `getUserInfo(username)` | 查用户信息（不含密码） | `SELECT username,role,display_name` |
| `queryUsers(search, role, page, size)` | 分页搜索 | `LIKE %kw%` + `OFFSET/FETCH NEXT` |
| `countUsers(search, role)` | 统计用户数 | `COUNT(*)` + 条件筛选 |
| `resetPassword(username)` | 管理员重置为 `123456` | `UPDATE users SET password=? (BCrypt)` |
| `insertApprovedUser(...)` | 审核通过后插入 | `INSERT INTO users (username,password,role,display_name)` |

**分页查询参数**：
- `search`: 模糊匹配 username 或 display_name
- `role`: 精确匹配角色筛选
- `page`: 页码（1-based），`size`: 每页条数（默认20）
- 排序：`created_at DESC`

---

### 4.5 RegistrationStore — 注册审核

**类**: `com.substation.common.sql.RegistrationStore`

| 方法 | 说明 |
|------|------|
| `hasPendingRequest(username)` | 检查是否有同名 `status='pending'` 记录 |
| `insertRequest(username, hash, role, display)` | INSERT registration_requests |
| `queryRequests(status, page, size)` | 分页查询，按 `created_at DESC` |
| `approve(id, reviewedBy)` | 三步操作：查 pending → INSERT users → UPDATE status='approved' |
| `reject(id, reviewedBy)` | UPDATE status='rejected'（条件 `status='pending'`） |
| `countRequests(status)` | 统计申请数 |

**approve 事务性保证**（三步操作使用独立连接）：
1. 查询 pending 记录 → 提取 username, passwordHash, role, displayName
2. 新连接 `INSERT INTO users`
3. 新连接 `UPDATE registration_requests SET status='approved'`
4. `WHERE status='pending'` 条件防止重复审批

**reject 操作**：
```sql
UPDATE registration_requests
SET status='rejected', reviewed_by=?, review_time=SYSUTCDATETIME()
WHERE id=? AND status='pending'
```

---

### 4.6 OperationLogStore — 操作日志

**类**: `com.substation.common.sql.OperationLogStore`

| 方法 | 签名 | 说明 |
|------|------|------|
| `log` | `(username, action, target, details)` | 写入操作日志（无 IP） |
| `log` | `(username, action, target, details, ipAddress)` | 写入操作日志（含 IP） |
| `queryLogs` | `(username, action, page, size)` | 分页查询，时间转北京时间 |
| `countLogs` | `(username, action)` | 统计日志数 |

**时间转换逻辑**：
```java
Timestamp ts = rs.getTimestamp("created_at");
String beijingTime = ts.toInstant()
    .atZone(ZoneId.of("UTC"))
    .withZoneSameInstant(ZoneId.of("Asia/Shanghai"))
    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
```

---

## 五、管理员系统

### 5.1 AdminApiHandler — 管理员 API 完整接口

**类**: `com.substation.common.admin.AdminApiHandler`  
**Base Path**: `/api/admin/`  
**鉴权要求**: 所有接口需要 admin 角色（由 AuthFilter 在 `/api/admin/` 路径上校验）；实际实现中 admin 角色校验由外部保证，AdminApiHandler 内部通过 `getAdminUser()` 从 Authorization header 获取操作人。

#### 5.1.1 GET /api/admin/users — 用户列表

查询参数: `?search=&role=&page=1&size=20`

成功响应 (200)：
```json
{
    "success": true,
    "data": [
        {
            "username": "admin",
            "role": "admin",
            "displayName": "管理员",
            "status": "active",
            "createdAt": "2026-06-15T10:30:00"
        },
        {
            "username": "simulator1",
            "role": "simulator",
            "displayName": "仿真员1",
            "status": "active",
            "createdAt": "2026-06-15T10:30:01"
        }
    ],
    "total": 5,
    "page": 1,
    "size": 20
}
```

**数据模型**（`UserRecord`）:
```
UserRecord(username, role, displayName, status, createdAt)
```

注意：**不返回 password 字段**（安全原则：查询时不返回密码哈希）。

**SQL 查询逻辑**：
```sql
SELECT username, role, display_name, status, created_at FROM users WHERE 1=1
  AND (username LIKE ? OR display_name LIKE ?)   -- 仅当 search 非空
  AND role=?                                      -- 仅当 role 非空
ORDER BY created_at DESC
OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
```

#### 5.1.2 GET /api/admin/users/{username} — 用户详情

路径参数: `{username}`

成功响应 (200)：
```json
{
    "success": true,
    "data": {
        "username": "simulator1",
        "role": "simulator",
        "displayName": "仿真员1",
        "status": "active",
        "createdAt": "2026-06-15T10:30:01"
    }
}
```

失败响应 (404)：
```json
{"success": false, "error": "用户不存在"}
```

**实现**：调用 `userStore.getUserInfo(username)` 再联合 `queryUsers(username, null, 1, 1)` 获取完整 UserRecord。

#### 5.1.3 POST /api/admin/users/{username}/reset-password — 重置密码

路径参数: `{username}`

成功响应 (200)：
```json
{
    "success": true,
    "message": "密码已重置为 123456"
}
```

失败响应 (400)：
```json
{"success": false, "error": "用户不存在"}
```

**处理逻辑**：
1. `SqlUserStore.resetPassword(username)` → UPDATE users SET password=BCrypt("123456")
2. 写操作日志 `RESET_PASSWORD`（target=被重置用户名, details="管理员重置密码为 123456"）
3. 操作人通过 `getAdminUser(exchange)` 获取（当前实现返回 "admin"）

#### 5.1.4 GET /api/admin/registrations — 注册申请列表

查询参数: `?status=pending&page=1&size=20`

成功响应 (200)：
```json
{
    "success": true,
    "data": [
        {
            "id": 1,
            "username": "newSimulator",
            "role": "simulator",
            "displayName": "新仿真员",
            "status": "pending",
            "reviewedBy": null,
            "reviewTime": null,
            "createdAt": "2026-06-20T14:30:00"
        }
    ],
    "total": 3,
    "page": 1,
    "size": 20
}
```

**数据模型**（`RegistrationRecord`）:
```
RegistrationRecord(id, username, role, displayName, status, reviewedBy, reviewTime, createdAt)
```

**SQL 查询逻辑**：
```sql
SELECT id, username, role, display_name, status, reviewed_by, review_time, created_at
FROM registration_requests WHERE 1=1
  AND status=?  -- 仅当 status 非空
ORDER BY created_at DESC
OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
```

#### 5.1.5 POST /api/admin/registrations/{id}/approve — 通过注册申请

路径参数: `{id}` (整数)

成功响应 (200)：
```json
{
    "success": true,
    "message": "已通过注册申请"
}
```

失败响应 (400)：
```json
{"success": false, "error": "申请不存在或已处理"}
```

**处理逻辑**：
1. 从路径提取 `id`
2. `RegistrationStore.approve(id, adminUsername)`：查询 pending → INSERT users → UPDATE status='approved'
3. 写操作日志 `APPROVE_REGISTRATION`

#### 5.1.6 POST /api/admin/registrations/{id}/reject — 拒绝注册申请

路径参数: `{id}` (整数)

成功响应 (200)：
```json
{
    "success": true,
    "message": "已拒绝注册申请"
}
```

失败响应 (400)：
```json
{"success": false, "error": "申请不存在或已处理"}
```

**处理逻辑**：
1. 从路径提取 `id`
2. `RegistrationStore.reject(id, adminUsername)` → UPDATE status='rejected'
3. 写操作日志 `REJECT_REGISTRATION`

#### 5.1.7 GET /api/admin/logs — 操作日志查询

查询参数: `?username=&action=&page=1&size=20`

成功响应 (200)：
```json
{
    "success": true,
    "data": [
        {
            "id": 142,
            "username": "admin",
            "action": "LOGIN",
            "target": null,
            "details": "登录成功",
            "createdAt": "2026-06-26 14:30:05"
        },
        {
            "id": 141,
            "username": "admin",
            "action": "APPROVE_REGISTRATION",
            "target": "id=3",
            "details": "通过注册申请",
            "createdAt": "2026-06-26 14:25:30"
        }
    ],
    "total": 142,
    "page": 1,
    "size": 20
}
```

**数据模型**（`OperationLogRecord`）:
```
OperationLogRecord(id, username, action, target, details, createdAt)
```

**SQL 查询逻辑**：
```sql
SELECT id, username, action, target, details, created_at FROM operation_logs WHERE 1=1
  AND username=?  -- 仅当 username 非空
  AND action=?    -- 仅当 action 非空
ORDER BY created_at DESC
OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
```

**时间处理**：`createdAt` 返回北京时间格式 `yyyy-MM-dd HH:mm:ss`。

---

## 六、统计分析系统

### 6.1 概述

统计分析系统负责接收前端提交的仿真统计摘要、持久化到 SQL Server，并提供查询、排行榜、删除等功能。与历史回放系统通过 `run_id` 一一关联。

**核心类**：

| 类 | 职责 |
|----|------|
| `AnalysisApiHandler` | HTTP API 端点分发 |
| `SimulationStatsStore` | 统计数据的 SQL Server CRUD |
| `SimulationRecordService` | 协调归档与统计保存的原子操作 |
| `SummaryStatistics` | 全局汇总统计 record |
| `CarStatistics` | 单车统计 record |
| `SimulationStatsSummary` | 统计记录列表项 record |

### 6.2 AnalysisApiHandler — 统计分析 API 完整接口

**类**: `com.substation.common.analysis.AnalysisApiHandler`  
**Base Path**: `/api/analysis/`

#### 6.2.1 GET /api/analysis/records — 统计记录列表

查询参数: `?page=1&size=50`

成功响应 (200)：
```json
{
    "success": true,
    "records": [
        {
            "runId": 5,
            "id": 5,
            "savedBy": "analyst1",
            "savedAt": "2026-06-26T14:30:00Z",
            "runStartedBy": "simulator1",
            "runStartedAt": "2026-06-26T14:25:00Z",
            "explorationRate": 85,
            "tick": 120,
            "duration": 300,
            "totalSteps": 450,
            "totalEffectiveSteps": 320,
            "efficiencyPercent": 71,
            "carCount": 3,
            "algorithm": "astar",
            "obstacleRatio": 0.2,
            "mapWidth": 30,
            "mapHeight": 30,
            "balanceScore": 0.75,
            "timestamp": 1719440000000,
            "...(其它 payload 字段)..."
        }
    ]
}
```

**实现**：`SimulationStatsStore.listFullRecords(page, size)` — INNER JOIN simulation_runs 获取完整信息。

#### 6.2.2 POST /api/analysis/records — 保存统计记录

请求：
```json
{
    "runId": 0,
    "timestamp": 1719440000000,
    "explorationRate": 85,
    "tick": 120,
    "duration": 300,
    "totalSteps": 450,
    "totalEffectiveSteps": 320,
    "efficiencyPercent": 71,
    "carCount": 3,
    "algorithm": "astar",
    "obstacleRatio": 0.2,
    "mapWidth": 30,
    "mapHeight": 30,
    "balanceScore": 0.75,
    "...(更多字段)..."
}
```

成功响应 (200)：
```json
{
    "success": true,
    "runId": 5,
    "record": { "...(完整的保存后记录)..." }
}
```

冲突响应 (409)：
```json
{"success": false, "error": "该场次已处理，请勿重复保存"}
```

参数错误响应 (400)：
```json
{"success": false, "error": "没有可保存的仿真数据"}
{"success": false, "error": "场次不存在: runId=99"}
```

**处理逻辑**（两种保存路径）：
1. 若 `runId > 0`（指定已有场次）：
   - 调用 `SimulationRunStore.existsById(runId)` 验证场次存在
   - 调用 `SimulationStatsStore.existsForRun(runId)` 检查不重复
   - 调用 `statsStore.save(runId, savedBy, payload)` 写入统计
2. 若 `runId == 0`（当前场次首次归档）：
   - 调用 `recordService.saveConfirmed(payload, savedBy)`
   - 内部：检查黑板未归档 → 检查有可回放数据 → `RunArchiver.archiveIfNeeded()` 归档 → `statsStore.save()` 保存统计
   - 若统计保存失败 → 回滚：删除已插入的 simulation_run + 清除归档标记

#### 6.2.3 POST /api/analysis/discard — 拒绝保存

请求体：无特殊要求

成功响应 (200)：
```json
{"success": true}
```

**实现**：`recordService.declineSave()` → 仅标记黑板 `markSimRunArchived()`，不写入数据库。

#### 6.2.4 DELETE /api/analysis/records/{runId} — 删除统计记录

路径参数: `{runId}` (整数)

成功响应 (200)：
```json
{"success": true}
```

失败响应 (404)：
```json
{"success": false, "error": "记录不存在"}
```

失败响应 (400)：
```json
{"success": false, "error": "无效场次编号"}
```

**实现**：`recordService.deleteByRunId(runId)` → 删除 simulation_run_stats → 删除 simulation_runs（保持两表一致）。

#### 6.2.5 GET /api/analysis/summary — 全局汇总统计

成功响应 (200)：
```json
{
    "success": true,
    "data": {
        "totalSteps": 0,
        "explorationRate": 0,
        "efficiency": 0.0,
        "duration": 0,
        "blockCount": 0,
        "idleRate": 0.0,
        "reExploreRate": 0.0,
        "activeCars": 0
    }
}
```

**数据模型**（`SummaryStatistics`）:
```
SummaryStatistics(totalSteps, explorationRate, efficiency, duration, blockCount, idleRate, reExploreRate, activeCars)
```

> 注：当前返回空统计数据。实际汇总计算需从 `simulation_run_stats` 聚合查询或从 Redis 实时计算。

#### 6.2.6 GET /api/analysis/car/{carId} — 单车统计

路径参数: `{carId}` (如 Car001)

成功响应 (200)：
```json
{
    "success": true,
    "data": {
        "carId": "Car001",
        "steps": 0,
        "pathCount": 0,
        "avgPathLength": 0.0,
        "blockCount": 0,
        "idleRate": 0.0,
        "pathHistory": []
    }
}
```

**数据模型**（`CarStatistics`）:
```
CarStatistics(carId, steps, pathCount, avgPathLength, blockCount, idleRate, pathHistory)
```

> 注：当前返回空统计数据。实际数据需从 Redis 黑板实时读取。

#### 6.2.7 GET /api/analysis/leaderboard — 车辆排行榜

成功响应 (200)：
```json
{
    "success": true,
    "leaderboard": [
        {"carId": "Car001", "steps": 0, "coverage": 0, "efficiency": 0.0},
        {"carId": "Car002", "steps": 0, "coverage": 0, "efficiency": 0.0},
        {"carId": "Car003", "steps": 0, "coverage": 0, "efficiency": 0.0}
    ]
}
```

> 注：当前返回固定模板数据。实际排行榜需从 Redis 黑板对各车统计聚合计算。

#### 6.2.8 POST /api/analysis/query — 自定义查询

请求：任意 JSON body（当前忽略）

成功响应 (200)：
```json
{
    "success": true,
    "data": {}
}
```

> 注：预留扩展接口，当前返回空数据。

#### 6.2.9 GET /api/analysis/export — 导出统计数据

成功响应 (200)：
```json
{
    "success": true,
    "message": "导出功能开发中"
}
```

**权限控制**：AuthFilter 拦截 `/api/analysis/export`，仅 admin 角色可访问，非 admin 返回 403 `"权限不足，仅管理员可导出"`。

> 注：导出功能当前为存根实现。

---

### 6.3 SimulationStatsStore — 统计持久化

**类**: `com.substation.common.analysis.SimulationStatsStore`  
**表**: `simulation_run_stats`（外键关联 `simulation_runs`）

| 方法 | 说明 | SQL 要点 |
|------|------|----------|
| `save(runId, savedBy, payload)` | 保存统计记录 | INSERT 17 个字段；2627→重复异常；547→外键异常 |
| `existsForRun(runId)` | 检查统计是否已存在 | `SELECT 1 FROM simulation_run_stats WHERE run_id=?` |
| `listRecent(page, size)` | 列表（概要） | 不含 payload 字段，仅返回统计数据 |
| `findPayloadByRunId(runId)` | 单条详情（含完整 payload） | INNER JOIN simulation_runs |
| `listFullRecords(page, size)` | 列表（含完整 payload） | INNER JOIN simulation_runs, 按 run_id DESC |
| `deleteByRunId(runId)` | 删除统计 | `DELETE FROM simulation_run_stats WHERE run_id=?` |

**save() 的 bindInsert 参数映射**：

| 参数位置 | 数据库列 | 来源 |
|---------|---------|------|
| 1 | run_id | 参数 |
| 2 | saved_by | 参数 |
| 3 | saved_at | `Timestamp.from(Instant.now())` |
| 4 | client_timestamp | `payload.getLongValue("timestamp")` |
| 5 | exploration_rate | `payload.getIntValue("explorationRate")` |
| 6 | tick | `payload.getIntValue("tick")` |
| 7 | duration | `payload.getIntValue("duration")` |
| 8 | total_steps | `payload.getIntValue("totalSteps")` |
| 9 | total_effective_steps | `payload.getIntValue("totalEffectiveSteps")` |
| 10 | efficiency_percent | `payload.getIntValue("efficiencyPercent")` |
| 11 | car_count | `payload.getIntValue("carCount")` |
| 12 | algorithm | `payload.getString("algorithm")` |
| 13 | obstacle_ratio | `payload.getDoubleValue("obstacleRatio")` |
| 14 | map_width | `payload.getIntValue("mapWidth")` |
| 15 | map_height | `payload.getIntValue("mapHeight")` |
| 16 | balance_score | `payload.getDoubleValue("balanceScore")` |
| 17 | payload | `JSON.toJSONString(payload)`（完整 JSON） |

**错误码处理**：
- `2627`（SQL Server 主键冲突）→ `IllegalStateException("该场次统计已存在")`
- `547`（外键约束违反）→ `IllegalArgumentException("场次不存在")`

**mergeRecord 逻辑**：JOIN 查询两表后合并字段，生成包含 `runId`, `savedBy`, `savedAt`, `runStartedBy`, `runStartedAt` 以及完整 payload 的统一 JSON。

---

### 6.4 SimulationRecordService — 保存协调

**类**: `com.substation.common.analysis.SimulationRecordService`

**依赖**: `BlackboardClient`, `RunArchiver`, `SimulationRunStore`, `SimulationStatsStore`

**方法**：

#### saveConfirmed(payload, savedBy) → long (runId)

协调归档 + 统计保存的原子操作：

1. 检查 `blackboard.isSimRunArchived()` → 若已归档抛出 `IllegalStateException`
2. 检查 `blackboard.hasReplayableData()` → 若无数据抛出 `IllegalArgumentException`
3. `runArchiver.archiveIfNeeded()` 归档当前黑板数据到 simulation_runs
4. `statsStore.save(runId, savedBy, payload)` 保存统计
5. **失败回滚**：若步骤 4 抛异常 → `runStore.deleteById(runId)` + `blackboard.clearSimRunArchived()`

#### declineSave()

用户拒绝保存：仅标记黑板 `markSimRunArchived()`，不写入数据库。

#### deleteByRunId(runId) → boolean

删除统计 + 回放：先删 `simulation_run_stats`，再删 `simulation_runs`。

---

## 七、历史回放系统

### 7.1 概述

历史回放系统负责在仿真结束时将 Redis 黑板数据归档到 SQL Server（包含地图位图 Base64、小车轨迹、探索事件等），并提供按场次 ID 查询回放数据的 API。

**核心类**：

| 类 | 职责 |
|----|------|
| `ReplayApiHandler` | HTTP API：场次列表 + 按 ID 回放 |
| `RunArchiver` | 从 Redis 黑板归档当前仿真到 SQL Server |
| `SimulationRunStore` | simulation_runs 表的 CRUD + JOIN 查询 |
| `ReplayDataBuilder` | 构建与前端 REPLAY_DATA 兼容的 JSON |
| `SimulationRunRecord` | 完整场次记录 record |
| `SimulationRunSummary` | 场次列表项 record |
| `SimulationRunStatus` | 枚举：COMPLETED / ABORTED |

### 7.2 ReplayApiHandler — 回放 API 完整接口

**类**: `com.substation.common.replay.ReplayApiHandler`  
**Base Path**: `/api/replay/`

#### 7.2.1 GET /api/replay/runs — 历史场次列表

查询参数: `?page=1&size=50`

成功响应 (200)：
```json
{
    "success": true,
    "runs": [
        {
            "id": 5,
            "startedBy": "simulator1",
            "startedAt": "2026-06-26T14:25:00Z",
            "endedAt": "2026-06-26T14:30:00Z",
            "mapWidth": 30,
            "mapHeight": 30,
            "carCount": 3,
            "algorithm": "astar",
            "maxTick": 120,
            "explorationRate": 85,
            "status": "COMPLETED",
            "hasStats": true
        }
    ]
}
```

**数据模型**（`SimulationRunSummary`）:
```
SimulationRunSummary(id, startedBy, startedAt, endedAt, mapWidth, mapHeight,
                     carCount, algorithm, maxTick, explorationRate, status, hasStats)
```

**SQL 查询**：INNER JOIN simulation_run_stats（只返回已有统计的场次），按 id DESC 排序。

#### 7.2.2 GET /api/replay/runs/{runId} — 按 ID 获取回放数据

路径参数: `{runId}` (整数)

成功响应 (200)：
```json
{
    "success": true,
    "data": {
        "type": "REPLAY_DATA",
        "runId": 5,
        "mapWidth": 30,
        "mapHeight": 30,
        "taskConfig": {
            "mapWidth": "30",
            "mapHeight": "30",
            "algorithm": "astar",
            "obstacleRatio": "0.2",
            "tickInterval": "500"
        },
        "carHistories": {
            "Car001": ["{\"x\":5,\"y\":5,\"tick\":1}", "{\"x\":5,\"y\":6,\"tick\":2}", "..."],
            "Car002": ["{\"x\":15,\"y\":10,\"tick\":1}", "..."]
        },
        "explorationEvents": ["1,5,6", "2,5,7", "..."],
        "mapViewB64": "iVBORw0KGgoAAAANSUhEUg...",
        "mapBlockB64": "iVBORw0KGgoAAAANSUhEUg...",
        "mapSealedB64": "iVBORw0KGgoAAAANSUhEUg...",
        "mapBlock": [[false,false,true,...], [...]],
        "mapSealed": [[false,false,false,...], [...]],
        "maxTick": 120,
        "explorationRate": 85
    }
}
```

失败响应 (404)：
```json
{"success": false, "error": "场次不存在"}
```

失败响应 (400)：
```json
{"success": false, "error": "无效场次 ID"}
```

**处理逻辑**：
1. 解析 `runId` 为 `long`
2. `SimulationRunStore.findById(runId)` 查询数据库
3. `ReplayDataBuilder.fromRecord(record)` 转换为前端兼容格式
4. 位图字段 `mapBlock` 和 `mapSealed` 从 Base64 解码为 `boolean[][]`

---

### 7.3 RunArchiver — 场次归档

**类**: `com.substation.common.replay.RunArchiver`

**方法**: `archiveIfNeeded(BlackboardClient blackboard, SimulationRunStatus status) → Optional<Long>`

**归档流程**：

```
1. 检查 blackboard.isSimRunArchived() → 已归档则返回 Optional.empty()
2. 检查 blackboard.hasReplayableData() → 无数据则返回 Optional.empty()
3. buildDraft(blackboard, status) → 构造 SimulationRunRecord:
   ├─ 从 TaskConfig 读取: mapWidth, mapHeight, algorithm, obstacleRatio, tickInterval
   ├─ 从黑板读取: startedBy, startedAt, carCount, 全部 carHistories, explorationEvents
   ├─ 位图 Base64 编码: mapBlock, mapSealed, mapView
   ├─ maxTick: ReplayDataBuilder.resolveMaxTick(histories, events)
   ├─ explorationRate: isExplorationComplete()?100:getExplorationRate()
   └─ status: COMPLETED / ABORTED
4. SimulationRunStore.insert(draft) → 获取自增 id
5. blackboard.markSimRunArchived() → 设置 Redis 归档标记
6. 返回 runId
```

**buildDraft 数据来源**：

| 字段 | 来源 |
|------|------|
| startedBy | `blackboard.getSimRunStartedBy()` (fallback: "unknown") |
| startedAt | `blackboard.getSimRunStartedAt()` (fallback: Instant.now()) |
| endedAt | `Instant.now()` |
| mapWidth/Height | TaskConfig (fallback: DEFAULT_WIDTH/HEIGHT) |
| carCount | `discoverCarIds().size()` |
| algorithm/obstacleRatio/tickInterval | TaskConfig |
| maxTick | 遍历 carHistories + explorationEvents 找最大值 |
| explorationRate | 100 或计算值 |
| mapBlock/Sealed/View B64 | `Base64.getEncoder().encodeToString(bytes)` |
| carHistories | `getAllCarHistories()` → `Map<String, List<String>>` |
| explorationEvents | `getExplorationEvents()` → `List<String>` |

---

### 7.4 SimulationRunStore — 仿真场次 CRUD

**类**: `com.substation.common.replay.SimulationRunStore`  
**表**: `simulation_runs`

| 方法 | 说明 | SQL 要点 |
|------|------|----------|
| `insert(draft)` | 插入场次记录，返回自增 id | 17 个字段 + `RETURN_GENERATED_KEYS` |
| `listRecent(page, size)` | 分页列表 | INNER JOIN simulation_run_stats, 仅返回有统计的场次 |
| `findById(id)` | 按 ID 查询完整记录 | `SELECT * FROM simulation_runs WHERE id=?` |
| `existsById(id)` | 检查场次是否存在 | `SELECT 1 FROM simulation_runs WHERE id=?` |
| `deleteById(id)` | 删除场次 | `DELETE FROM simulation_runs WHERE id=?` |

**insert 字段映射**：

| 参数位置 | 列 | 值 |
|---------|-----|-----|
| 1 | started_by | `draft.startedBy()` |
| 2 | started_at | `Timestamp.from(draft.startedAt())` |
| 3 | ended_at | `Timestamp.from(draft.endedAt())` |
| 4 | map_width | `draft.mapWidth()` |
| 5 | map_height | `draft.mapHeight()` |
| 6 | car_count | `draft.carCount()` |
| 7 | algorithm | `draft.algorithm()` |
| 8 | obstacle_ratio | `draft.obstacleRatio()` |
| 9 | tick_interval | `draft.tickInterval()` |
| 10 | max_tick | `draft.maxTick()` |
| 11 | exploration_rate | `draft.explorationRate()` |
| 12 | status | `draft.status().name()` |
| 13 | map_block_b64 | `draft.mapBlockB64()` |
| 14 | map_sealed_b64 | `draft.mapSealedB64()` |
| 15 | map_view_final_b64 | `draft.mapViewFinalB64()` |
| 16 | car_histories | `JSON.toJSONString(draft.carHistories())` |
| 17 | exploration_events | `JSON.toJSONString(draft.explorationEvents())` |

---

### 7.5 ReplayDataBuilder — 回放数据构造

**类**: `com.substation.common.replay.ReplayDataBuilder`（工具类，私有构造函数）

**方法**：

| 方法 | 说明 |
|------|------|
| `fromBlackboard(BlackboardClient)` | 从 Redis 黑板构造 REPLAY_DATA JSON（用于实时查看） |
| `fromRecord(SimulationRunRecord)` | 从数据库记录构造 REPLAY_DATA JSON（用于历史回放） |
| `resolveMaxTick(histories, events)` | 遍历所有 carHistories 和 explorationEvents 计算最大 tick |

**fromRecord 输出结构**（与 fromBlackboard 兼容）：

```json
{
    "type": "REPLAY_DATA",
    "runId": 5,
    "mapWidth": 30,
    "mapHeight": 30,
    "taskConfig": {"mapWidth": "30", "mapHeight": "30", "algorithm": "astar", ...},
    "carHistories": {"Car001": [...], "Car002": [...]},
    "explorationEvents": ["1,5,6", "2,5,7", ...],
    "mapViewB64": "base64...",
    "mapBlockB64": "base64...",
    "mapSealedB64": "base64...",
    "maxTick": 120,
    "explorationRate": 85,
    "mapBlock": [[false,true,...], ...],
    "mapSealed": [[false,false,...], ...]
}
```

**resolveMaxTick 算法**：
1. 遍历所有 carHistories 条目，解析每条 `{x, y, tick}` JSON，取最大 tick
2. 遍历所有 explorationEvents（格式 `tick,row,col`），解析前缀取最大 tick
3. 返回全局最大值

---

### 7.6 回放数据格式

完整的仿真快照包含以下数据，足以让前端完全重现一场仿真：

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | String | 固定 `"REPLAY_DATA"` |
| `runId` | int | 场次 ID（fromRecord 时附加） |
| `mapWidth` | int | 地图宽度 |
| `mapHeight` | int | 地图高度 |
| `taskConfig` | Map<String,String> | 任务配置（含算法、障碍比、tick间隔等） |
| `carHistories` | Map<String, List<String>> | 每辆车的完整轨迹（按 tick 排序的 `{x,y,tick}` JSON 列表） |
| `explorationEvents` | List<String> | 探索事件列表（格式 `tick,row,col`） |
| `mapViewB64` | String | 最终探索位图 Base64 |
| `mapBlockB64` | String | 障碍位图 Base64 |
| `mapSealedB64` | String | 密封区位图 Base64 |
| `mapBlock` | boolean[][] | 障碍二维数组（解码后，方便前端直接渲染） |
| `mapSealed` | boolean[][] | 密封区二维数组（解码后） |
| `maxTick` | int | 最大 tick 数 |
| `explorationRate` | int | 探索率（百分比） |

---

## 八、Redis vs SQL Server 存储分工

| 数据 | 存储 | 理由 |
|------|------|------|
| 用户账号、角色、密码 | **SQL Server** (users) | 持久化、查询、搜索 |
| 预设普通用户 | **Redis** (auth:users) | 快速原型、向后兼容 |
| 注册申请 | **SQL Server** (registration_requests) | 审核流、状态变迁 |
| 操作日志 | **SQL Server** (operation_logs) | 审计、历史追溯 |
| 仿真运行记录 | **SQL Server** (simulation_runs) | 长期存储、位图 Base64 |
| 仿真统计数据 | **SQL Server** (simulation_run_stats) | 关系查询、JOIN 关联 |
| **会话 Token** | **Redis** | TTL 自动过期、低延迟 |
| 黑板数据（地图/车状态/路线） | **Redis** | 高频读写、位图操作、LIST 操作 |

---

## 九、系统启动初始化

```java
// DatabaseManager.initDatabase(): 幂等初始化
1. 执行 IF NOT EXISTS ... CREATE TABLE（5张表：users, registration_requests,
   operation_logs, simulation_runs, simulation_run_stats）
2. 清理废弃表: DROP TABLE IF EXISTS simulation_stats
3. insertPresetAdmin(): 检查→不存在则创建（BCrypt 哈希 admin123）
```

```java
// UserStore.initPresetUsers(): 幂等初始化
1. createUserIfAbsent("admin", "admin123", "admin", "管理员")
2. createUserIfAbsent("simulator1", "sim123", "simulator", "仿真员1")
3. createUserIfAbsent("analyst1", "ana123", "analyst", "统计分析员1")
```

> 预设管理员账号由 SQL Server 端初始化（DatabaseManager）；预设普通用户由 Redis 端初始化（UserStore）——两者分别管理各自的存储层。

---

## 十、注册流程（审核制）

```
普通用户
  │
  │ POST /api/auth/register {username, password, role, displayName}
  ▼
┌────────────────────────────────────────────────┐
│  AuthApiHandler.handleRegister()               │
│  1. 校验：用户名/密码非空，密码≥6位              │
│  2. 校验：role 仅限 simulator 或 analyst          │
│  3. 检查 registration_requests 是否有同名 pending │
│     └─ 有 → 返回 "该账号正在审核中"               │
│  4. BCrypt 哈希密码                              │
│  5. INSERT registration_requests                │
│  6. 写操作日志 REGISTER                          │
│  7. 返回 "注册申请已提交，请等待管理员审核"        │
└────────────────────────────────────────────────┘
  │
  │ 管理员登录 → GET /api/admin/registrations?status=pending
  ▼
┌────────────────────────────────────────────────┐
│  AdminApiHandler                                │
│  [通过] POST /api/admin/registrations/{id}/approve
│         1. 查询 pending 记录                    │
│         2. INSERT users                         │
│         3. UPDATE status='approved'             │
│  [拒绝] POST /api/admin/registrations/{id}/reject
│         1. UPDATE status='rejected'             │
└────────────────────────────────────────────────┘
```

**角色约束**：
- `admin` 角色只能由系统预设创建，注册时不可选
- `simulator` / `analyst` 可通过注册+审核获得
- 同一用户名不可重复提交 pending 申请

---

## 十一、环境依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| JDK | 17+ | 编译运行 |
| Redis | 7.x (Docker) | 黑板读写 + 会话管理 + 分布式锁 |
| RabbitMQ | 3.12+ (Docker) | MQ 消息收发 |
| SQL Server | 2022 (Docker) | 用户/注册/日志/仿真数据持久化 |
| Jedis | 5.1.x | Redis 客户端 |
| AMQP Client | 5.21.x | RabbitMQ 客户端 |
| mssql-jdbc | 12.8.1 | SQL Server JDBC 驱动 |
| jBCrypt | 0.4 | 密码哈希 |
| fastjson2 | 2.0.47 | JSON 序列化 |
| SLF4J + Logback | 2.0.x / 1.5.x | 日志 |

---

## 十二、测试覆盖

### Car 模块测试（19 个）

**CarAgentTest（6 个）**：TICK_MOVE 正常处理、路径走完后 ROUTE_DONE、BLOCKED_TIMEOUT IDLE/BLOCKED 状态、非法 JSON 不崩溃、未知消息类型不崩溃

**MoveExecutorTest（13 个）**：正常移动/最后一步移动/障碍阻塞/IDLE 跳过/MOVING 跳过/BLOCKED 跳过/READY 空路由/3×3 点亮/边界裁剪/History 记录/热力图/分布式锁互斥/MOVING 心跳

### Auth 模块测试（22 个）

**UserStoreTest（16 个）**：认证成功/用户不存在/密码错误/改密成功/旧密码错误/账号锁定/注册成功/用户名已存在/密码过短/无效角色

**SessionManagerTest（6 个）**：创建会话/验证有效/验证过期/登出后无效/挤号后旧 Token 失效/新 Token 有效

---

## 十三、常见问题

**Q: 如何添加新车？**
```bash
java -jar car.jar Car006              # 预注册模式（等 5s）
java -jar car.jar Car006 --dynamic    # 动态添加（页面即时添加）
```

**Q: 移动失败怎么办？**
- 锁获取失败 → 跳过本拍，发送 `ackMoveDeferred` 通知 Controller 释放 tick 槽
- 预约/占据连续 3 次 → 切 BLOCKED，等待 Controller 重新规划
- 障碍物 → 清路径/目标，发 BLOCKED

**Q: 用户被挤号后显示什么？**
前端收到 `{"success":false, "kicked":true, "error":"您的账号已在其他设备或窗口登录，当前会话已结束"}`，跳转登录页并显示提示。

**Q: 忘记密码怎么办？**
联系管理员，管理员在用户管理界面通过 `POST /api/admin/users/{username}/reset-password` 重置密码为 `123456`。

**Q: 注册 admin 角色？**
不支持。admin 角色只能由系统预设创建（DatabaseManager.insertPresetAdmin()）。普通用户注册时仅可选 simulator 或 analyst。

**Q: 如何查看历史仿真？**
通过 `GET /api/replay/runs` 获取场次列表，再通过 `GET /api/replay/runs/{runId}` 获取完整回放数据。前端根据 REPLAY_DATA 格式渲染回放动画。

**Q: 统计数据和回放数据的关系？**
两者通过 `run_id`（即 `simulation_runs.id`）一一关联。`simulation_run_stats.run_id` 外键引用 `simulation_runs.id`。保存统计时先归档回放数据，再保存统计摘要。

**Q: 如何导出数据？**
`GET /api/analysis/export` 需要 admin 角色。当前为开发中的存根实现，返回 `"导出功能开发中"`。

---

## 十四、完整 API 端点汇总

### 认证 API（/api/auth/）

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| POST | `/api/auth/login` | 否 | 用户登录，返回 token |
| POST | `/api/auth/register` | 否 | 用户注册（提交审核） |
| POST | `/api/auth/logout` | Token | 用户登出 |
| GET | `/api/auth/me` | Token | 获取当前用户信息 |
| POST | `/api/auth/change-password` | Token | 修改密码 |

### 管理员 API（/api/admin/）

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| GET | `/api/admin/users` | admin | 用户列表（?search&role&page&size） |
| GET | `/api/admin/users/{username}` | admin | 用户详情 |
| POST | `/api/admin/users/{username}/reset-password` | admin | 重置密码为 123456 |
| GET | `/api/admin/registrations` | admin | 注册申请列表（?status=pending&page&size） |
| POST | `/api/admin/registrations/{id}/approve` | admin | 通过注册申请 |
| POST | `/api/admin/registrations/{id}/reject` | admin | 拒绝注册申请 |
| GET | `/api/admin/logs` | admin | 操作日志（?username&action&page&size） |

### 统计分析 API（/api/analysis/）

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| GET | `/api/analysis/records` | Token | 统计记录列表（?page&size） |
| POST | `/api/analysis/records` | Token | 保存统计记录 |
| POST | `/api/analysis/discard` | Token | 拒绝保存当前场次 |
| DELETE | `/api/analysis/records/{runId}` | Token | 删除统计记录 |
| GET | `/api/analysis/summary` | Token | 全局汇总统计 |
| GET | `/api/analysis/car/{carId}` | Token | 单车统计 |
| GET | `/api/analysis/leaderboard` | Token | 车辆排行榜 |
| POST | `/api/analysis/query` | Token | 自定义查询（预留） |
| GET | `/api/analysis/export` | admin | 导出统计数据（开发中） |

### 回放 API（/api/replay/）

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| GET | `/api/replay/runs` | Token | 历史场次列表（?page&size） |
| GET | `/api/replay/runs/{runId}` | Token | 按 ID 获取完整回放数据 |

---

## 十五、代码文件清单

### Car 模块 (car/)

| 文件 | 类/接口 | 行数 | 职责 |
|------|---------|------|------|
| `CarMain.java` | `CarMain` | ~200 | 进程入口、自注册、CLI 解析、shutdown hook |
| `CarAgent.java` | `CarAgent` | ~80 | 消息分发、TICK_MOVE/BLOCKED_TIMEOUT 路由 |
| `MoveExecutor.java` | `MoveExecutor` | ~250 | 14 步原子移动执行器、防碰撞、点亮热力 |

### 认证模块 (common/.../auth/)

| 文件 | 类/接口 | 职责 |
|------|---------|------|
| `AuthApiHandler.java` | `AuthApiHandler` | 5 个认证 HTTP 端点 |
| `AuthFilter.java` | `AuthFilter` | JDK Filter，白名单 + Token 校验 |
| `SessionManager.java` | `SessionManager` | Redis 会话管理，单会话强制、挤号机制 |
| `UserStore.java` | `UserStore` | Redis 用户存储，预设账号、暴力防护 |
| `AuthResponses.java` | `AuthResponses` | 统一 401 响应 JSON 生成工具 |
| `model/LoginResponse.java` | `LoginResponse` | 登录响应 record |
| `model/SessionInfo.java` | `SessionInfo` | 会话信息 record |
| `model/SessionValidation.java` | `SessionValidation` | 会话校验结果 record (VALID/NOT_FOUND/KICKED) |
| `model/UserInfo.java` | `UserInfo` | 用户信息 record |

### SQL 持久化模块 (common/.../sql/)

| 文件 | 类/接口 | 职责 |
|------|---------|------|
| `DatabaseManager.java` | `DatabaseManager` | JDBC 连接管理、DDL 初始化、预设管理员 |
| `SqlUserStore.java` | `SqlUserStore` | users 表 CRUD（认证/查询/重置/插入） |
| `RegistrationStore.java` | `RegistrationStore` | registration_requests 表 CRUD（审核流程） |
| `OperationLogStore.java` | `OperationLogStore` | operation_logs 表写入/查询（含时区转换） |
| `model/UserRecord.java` | `UserRecord` | 用户表数据 record |
| `model/RegistrationRecord.java` | `RegistrationRecord` | 注册申请表数据 record |
| `model/OperationLogRecord.java` | `OperationLogRecord` | 操作日志表数据 record |

### 管理员模块 (common/.../admin/)

| 文件 | 类/接口 | 职责 |
|------|---------|------|
| `AdminApiHandler.java` | `AdminApiHandler` | 7 个管理员 HTTP 端点 |

### 统计分析模块 (common/.../analysis/)

| 文件 | 类/接口 | 职责 |
|------|---------|------|
| `AnalysisApiHandler.java` | `AnalysisApiHandler` | 9 个统计分析 HTTP 端点 |
| `SimulationStatsStore.java` | `SimulationStatsStore` | simulation_run_stats 表 CRUD + JOIN |
| `SimulationRecordService.java` | `SimulationRecordService` | 保存协调器（归档+统计原子操作） |
| `model/SummaryStatistics.java` | `SummaryStatistics` | 全局汇总统计 record |
| `model/CarStatistics.java` | `CarStatistics` | 单车统计 record |
| `model/SimulationStatsSummary.java` | `SimulationStatsSummary` | 统计列表项 record |

### 历史回放模块 (common/.../replay/)

| 文件 | 类/接口 | 职责 |
|------|---------|------|
| `ReplayApiHandler.java` | `ReplayApiHandler` | 2 个回放 HTTP 端点 |
| `RunArchiver.java` | `RunArchiver` | Redis 黑板数据归档到 SQL Server |
| `SimulationRunStore.java` | `SimulationRunStore` | simulation_runs 表 CRUD + JOIN |
| `ReplayDataBuilder.java` | `ReplayDataBuilder` | REPLAY_DATA JSON 构造（Redis/DB 双源） |
| `model/SimulationRunRecord.java` | `SimulationRunRecord` | 完整场次记录 record（20 字段） |
| `model/SimulationRunSummary.java` | `SimulationRunSummary` | 场次列表项 record |
| `model/SimulationRunStatus.java` | `SimulationRunStatus` | 枚举 COMPLETED / ABORTED |
