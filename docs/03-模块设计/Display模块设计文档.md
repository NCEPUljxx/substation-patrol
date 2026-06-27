# Display 模块设计文档

> **模块**：`display`（`com.substation.display`）
> **Maven 坐标**：`com.substation:car-homework-display`
> **入口类**：`DisplayMain`
> **Java 文件数**：7（DisplayMain、WebSocketBridge、HttpFileServer、DynamicCarLauncher、DynamicCarIdResolver、DynamicCarProcessKiller、ReplayCoordinator）
> **测试文件数**：5
> **前端文件数**：12（5 HTML + 5 JS + 1 CSS + 1 ws-redirect.js + Unity WebGL 资源）
> **角色**：系统唯一可视化前端——提供 Web GUI、WebSocket 实时数据推送、HTTP API 网关、Unity 3D 视图桥接、动态车辆进程管理、仿真场次回放

---

## 1. 模块概述

Display 模块是**变电站巡检仿真系统**的唯一可视化前端，承担四个核心职责：

1. **WebSocket 桥接器**（WebSocketBridge）：从 Redis 黑板拉取每 tick 仿真快照，构建 `SimulationState` JSON 并通过 WebSocket 实时广播至所有浏览器；同时接收浏览器上行命令，分流处理（`ADD_CAR` 本地处理、`REQUEST_REPLAY` 回放处理、其余转发至 RabbitMQ `ControllerCmd` 队列）。
2. **HTTP 文件服务器**（HttpFileServer）：基于 JDK `com.sun.net.httpserver` 的嵌入式 HTTP 服务器，服务静态前端资源（HTML/CSS/JS/WebGL），并提供 REST API 路由（认证、管理、分析、回放），带 Bearer Token 鉴权过滤器。
3. **动态小车启动器**（DynamicCarLauncher）：通过 `ADD_CAR` 命令动态启动新的小车 JVM 进程，管理进程生命周期（JAR 复制、异步启动、进程存活检测、退出回调）。
4. **回放协调器**（ReplayCoordinator）：支持实时场次回放和历史场次回放，从 Redis 黑板或 SQL Server `simulation_runs` 表读取数据并序列化为前端可消费的 JSON。

### 1.1 模块结构

```
com.substation.display
├── DisplayMain                  入口：组装所有组件、绑定 MQ 监听、启动服务
├── WebSocketBridge              核心：WebSocket 协议 (port 8888)、上下行消息桥接、
│                                动态小车管理（ADD_CAR）、回放请求路由
├── HttpFileServer               嵌入式 HTTP 服务器 (port 8887)、静态文件 + API 路由、
│                                Token 鉴权、白名单放行
├── DynamicCarLauncher           通过 car JAR 异步启动/停止动态小车进程、进程存活检测
├── DynamicCarIdResolver         小车 ID 分配策略（优先填补黑板已注册但无进程的缺口编号）
├── DynamicCarProcessKiller      统一结束动态小车进程（OS 进程扫描 + 命令行匹配）
└── ReplayCoordinator            场次回放协调：live replay（黑板快照）、stored replay（DB 查询）
```

```
display/src/main/resources/web/
├── login.html                   登录页
├── index.html                   主仪表盘（2D 地图 + 车辆面板 + 参数配置 + 回放控制）
├── dashboard.html               仪表盘（角色路由中枢）
├── analysis.html                历史数据分析页
├── user-management.html         用户管理页（管理员专有）
├── unity/index.html             Unity WebGL 容器页（内嵌 iframe，含摄像机拖拽控制）
├── css/style.css                全局样式（浅色工业风主题）
├── js/
│   ├── app.js                   主逻辑（1317 行）：Canvas 双缓冲渲染、WebSocket 客户端、
│   │                            2D 地图（动态尺寸适应）、小车动画、回放播放器、保存弹窗
│   ├── auth.js                  认证逻辑（414 行）：登录/注册/修改密码/Token 管理、
│   │                            单用户单会话、Tab 互斥、session 被踢处理
│   ├── analysis.js              分析页逻辑（655 行）：历史场次卡片网格、筛选/对比、
│   │                            详情展开（Donut 图 + 堆叠柱状图）、JSON 导入/导出
│   ├── unity-view.js            Unity 3D 视图桥接（245 行）：2D/3D 切换、
│   │                            摄像机控制（Pan/Orbit/Zoom）、iframe 生命周期管理
│   └── particles.js             粒子特效（60 行）：Canvas 背景装饰动画（120 颗粒子）
├── unity/Build/
│   ├── unity.framework.js       Unity WebGL 运行时
│   ├── unity.loader.js          Unity WebGL 加载器
│   ├── unity.data               Unity 场景数据（binary）
│   ├── unity.wasm               Unity WebAssembly 模块
│   └── ws-redirect.js           Unity WebSocket 重定向适配（localhost → 动态主机名）
└── unity/TemplateData/          Unity 构建模板资源（图标、进度条图片、样式）
```

### 1.2 与其他模块的消息交互

```
                                 ┌──────────────────────────────┐
  Controller ──REFRESH_ALL─────→│                              │
    (fanout, tick + rate)       │         Display              │
                                │                              │
  Browser ──SET_CONFIG─────────→│  WebSocketBridge (port 8888) │──→ ControllerCmd queue
  Browser ──RESET───────────────→│    ↓上行转发 / 下行广播↑      │    (MQ 原样转发)
  Browser ──TOGGLE_PAUSE───────→│                              │
  Browser ──SET_TICK_INTERVAL──→│  DynamicCarLauncher           │──→ 启动新 car JVM 进程
  Browser ──TOGGLE_OBSTACLE────→│  DynamicCarIdResolver        │    └─ JAR 复制: car/target → logs/runtime
  Browser ──ADD_CAR────────────→│  DynamicCarProcessKiller     │──→ 清理 OS 遗留 JVM 进程
  Browser ──REQUEST_REPLAY─────→│  ReplayCoordinator            │──→ SimulationRunStore (SQL)
  Browser ──api/auth/*─────────→│  HttpFileServer (port 8887)   │──→ AuthApiHandler
  Browser ──api/admin/*────────→│    /api/auth/* 白名单放行      │──→ AdminApiHandler
  Browser ──api/analysis/*─────→│    /api/admin/* Token 鉴权    │──→ AnalysisApiHandler
  Browser ──api/replay/*───────→│    /api/analysis/* Token 鉴权 │──→ ReplayApiHandler
                                │    /api/replay/* Token 鉴权   │
  Browser ←──HTTP 静态资源──────│                              │
  Browser ←──WebSocket JSON────│  ← Redis 黑板 (read-only)     │
                                └──────────────────────────────┘
```

**消息流说明**：
- **下行**（Java → 浏览器）：Controller 每 tick 通过 RabbitMQ fanout exchange `UpdateView` 广播 `REFRESH_ALL` 消息（`{tick, data: {explorationRate}}`），DisplayMain 的 `onRefreshAllReceived()` 接收后调用 `WebSocketBridge.pushSimulationState()`，后者从 Redis 黑板读取完整快照并序列化为 JSON，通过 WebSocket 广播给所有连接浏览器。无浏览器连接时直接跳过，避免无效 Redis I/O。
- **上行**（浏览器 → Java）：浏览器通过 WebSocket 发送 JSON 命令（`SET_CONFIG`、`RESET`、`TOGGLE_PAUSE`、`ADD_CAR`、`REQUEST_REPLAY` 等），WebSocketBridge 根据消息类型分流处理——`ADD_CAR` 触发本地动态启动逻辑（含 `DynamicCarIdResolver` 分配 ID、`DynamicCarLauncher` 启动 JVM），`REQUEST_REPLAY` 由 `ReplayCoordinator` 处理（按 runId 有无区分 live/stored），其余原样转发到 RabbitMQ `ControllerCmd` 队列。
- **HTTP API**：浏览器通过 HTTP 访问 `/api/auth/*`（白名单放行，无需认证）、`/api/admin/*`、`/api/analysis/*`、`/api/replay/*`（需 Bearer Token 认证）。

---

## 2. DisplayMain —— 模块主入口

**文件**：`DisplayMain.java`（151 行）

**职责**：初始化全部 15 个组件依赖并完成装配、连接 RabbitMQ 并绑定 fanout 队列监听、启动 WebSocket 和 HTTP 服务。

### 2.1 构造参数

```java
public DisplayMain(String redisHost, int redisPort,
                   String mqHost, int mqPort,
                   int httpPort, int wsPort,
                   Path webRoot) throws IOException
```

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `redisHost` | Redis 地址 | localhost |
| `redisPort` | Redis 端口 | 6379 |
| `mqHost` | RabbitMQ 地址 | localhost |
| `mqPort` | RabbitMQ 端口 | 5672 |
| `httpPort` | HTTP 服务端口 | 8887 |
| `wsPort` | WebSocket 服务端口 | 8888 |
| `webRoot` | 前端静态资源根目录 | `display/src/main/resources/web` |

MQ 用户名密码固定为 `guest/guest`。地图尺寸固定为 `BlackboardClient.DEFAULT_WIDTH × DEFAULT_HEIGHT`（30×30），在 BlackboardClient 构造时传入。

### 2.2 组件初始化清单（15 个组件 + 5 个辅助对象）

DisplayMain 构造函数中按依赖顺序初始化以下 20 个对象：

**数据库层（6 个）**：
```
 1. DatabaseManager()                                   ← SQL Server 连接管理，initDatabase() 建表
 2. SqlUserStore(db)                                    ← 用户 CRUD
 3. RegistrationStore(db)                               ← 注册记录存储
 4. OperationLogStore(db)                               ← 操作日志存储（登录/登出/注册/审核/密码变更）
 5. SimulationStatsStore(db)                            ← 仿真统计存储
 6. SimulationRunStore(db)                              ← 仿真场次存储
```

**认证与会话层（2 个）**：
```
 7. SessionManager(blackboard.getJedisPool())           ← Session Token 管理（Redis 存储，30 min 过期）
 8. BlackboardClient(redisHost, redisPort, 30, 30)     ← Redis 黑板读取客户端（mapView/mapBlock/mapSealed/carInfo）
```

**API 处理器层（4 个）**：
```
 9. AuthApiHandler(sqlUserStore, regStore, logStore, sessionManager)     ← 认证 API（login/register/change-password/me/logout）
10. AdminApiHandler(sqlUserStore, regStore, logStore)                    ← 管理 API（users CRUD, reg approve/reject, logs, reset-password）
11. AnalysisApiHandler(statsStore, runStore, recordService, sessionManager) ← 分析 API（records CRUD, export/import）
12. ReplayApiHandler(runStore)                                           ← 回放 API（runs listing, run detail）
```

**设施层（3 个）**：
```
13. RunArchiver(runStore)                               ← 场次归档器
14. SimulationRecordService(blackboard, runArchiver, runStore, statsStore) ← 仿真记录服务（begin/archive/discard）
15. MessageBus(mqHost, mqPort, "guest", "guest")       ← RabbitMQ 消息总线
```

**辅助对象（5 个）**：
```
16. WebSocketBridge.MqSender (lambda)                   ← MQ 发送适配器，委托给 MessageBus.publish()，异常时记录日志
17. WebSocketBridge(wsPort, blackboard, mqSender)       ← WebSocket 核心桥接器
18. ReplayCoordinator(blackboard, runStore)             ← 回放协调器
19. HttpFileServer(httpPort, webRoot, authApi, analysisApi, adminApi, replayApi, sessionManager) ← 嵌入式 HTTP 服务器
20. beforeCarLaunch callback (lambda)                   ← prepareCarQueue(carId): 声明并清空新车专属 MQ 队列
```

**装配关系**：
- `wsBridge.setBeforeCarLaunch(this::prepareCarQueue)` — 注册动态加车前回调
- `wsBridge.setOperationLogStore(logStore)` — 注入操作日志存储
- `wsBridge.setReplayCoordinator(replayCoordinator)` — 注入回放协调器
- `DynamicCarLauncher.setProcessExitListener(wsBridge::onDynamicCarProcessExit)` — 注册进程退出回调

### 2.3 依赖关系图（完整）

```
DisplayMain
├── BlackboardClient (Redis: host:port, 30×30)
├── MessageBus (RabbitMQ: host:port, guest/guest)
├── DatabaseManager (SQL Server)
│   ├── SqlUserStore
│   ├── RegistrationStore
│   ├── OperationLogStore
│   ├── SimulationStatsStore
│   └── SimulationRunStore
├── SessionManager (Redis JedisPool)
├── AuthApiHandler
│   ├── SqlUserStore
│   ├── RegistrationStore
│   ├── OperationLogStore
│   └── SessionManager
├── AdminApiHandler
│   ├── SqlUserStore
│   ├── RegistrationStore
│   └── OperationLogStore
├── RunArchiver
│   └── SimulationRunStore
├── SimulationRecordService
│   ├── BlackboardClient
│   ├── RunArchiver
│   ├── SimulationRunStore
│   └── SimulationStatsStore
├── AnalysisApiHandler
│   ├── SimulationStatsStore
│   ├── SimulationRunStore
│   ├── SimulationRecordService
│   └── SessionManager
├── ReplayApiHandler
│   └── SimulationRunStore
├── WebSocketBridge ← 核心桥接
│   ├── MqSender (→ MessageBus.publish)
│   ├── beforeCarLaunch (→ DisplayMain.prepareCarQueue)
│   ├── operationLogStore (→ OperationLogStore)
│   ├── DynamicCarLauncher (static utility)
│   ├── DynamicCarIdResolver (static utility)
│   ├── DynamicCarProcessKiller (static utility)
│   └── ReplayCoordinator
│       ├── BlackboardClient
│       └── SimulationRunStore
└── HttpFileServer
    ├── webRoot (静态文件服务)
    ├── AuthApiHandler
    ├── AnalysisApiHandler
    ├── AdminApiHandler
    ├── ReplayApiHandler
    └── SessionManager (Token 鉴权)
```

### 2.4 启动流程 (`start()`)

```
 1. messageBus.connect()                              ← 连接 RabbitMQ
 2. messageBus.declareFanoutExchange()                ← 声明 "UpdateView" fanout exchange
 3. messageBus.declareControllerQueue()               ← 声明 "ControllerCmd" 队列（确保存在）
 4. fanoutQueueName = messageBus.bindFanoutQueue()    ← 绑定临时独占队列到 fanout exchange
 5. messageBus.subscribe(fanoutQueueName, this::onRefreshAllReceived)  ← 订阅 REFRESH_ALL 消息
 6. wsBridge.start()                                  ← WebSocket 开始监听 port 8888
 7. httpServer.start()                                ← HTTP 开始监听 port 8887
 8. 日志输出：http://localhost:{httpPort}/login.html
```

### 2.5 消息监听：onRefreshAllReceived()

```java
private void onRefreshAllReceived(String rawMessage) {
    JSONObject mqMsg = JSONObject.parseObject(rawMessage);
    int tick = mqMsg.getIntValue("tick");
    JSONObject data = mqMsg.getJSONObject("data");
    int explorationRate = data != null ? data.getIntValue("explorationRate") : 0;
    wsBridge.pushSimulationState(tick, explorationRate);
}
```

Controller 每 tick 结束后通过 `UpdateView` fanout exchange 广播 `REFRESH_ALL` 消息（JSON 格式：`{"tick":42,"data":{"explorationRate":67}}`）。DisplayMain 解析 `tick` 和 `explorationRate`，驱动 WebSocketBridge 执行一次完整的 Redis 黑板快照读取 + WebSocket 广播。解析失败时记录 WARN 日志，不中断循环。

### 2.6 prepareCarQueue() —— 动态加车前置回调

动态加车前，为新车声明专属 MQ 队列并清空积压旧消息：

```java
private void prepareCarQueue(String carId) {
    messageBus.declareCarQueue(carId);
    messageBus.purgeQueue(QueueNames.carQueue(carId));
}
```

通过 `wsBridge.setBeforeCarLaunch(this::prepareCarQueue)` 注册为回调。此操作确保新车进程启动后不会消费到旧的积压消息。

### 2.7 stop() 方法

```java
public void stop() {
    try { wsBridge.stop(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    httpServer.stop();
    messageBus.close();
    blackboard.close();
}
```

### 2.8 main() 入口

```java
public static void main(String[] args) throws IOException, TimeoutException, InterruptedException {
    var infra = com.substation.common.infra.InfraConnectionConfig.resolve(args);
    Path webRoot = findWebRoot();
    DisplayMain display = new DisplayMain(
            infra.redisHost(), infra.redisPort(), infra.mqHost(), infra.mqPort(),
            DEFAULT_HTTP_PORT, DEFAULT_WS_PORT, webRoot);
    display.start();
    Thread.currentThread().join();
}
```

`findWebRoot()` 按优先级探测两个候选路径：`display/src/main/resources/web`（开发环境）、`src/main/resources/web`（备用），返回第一个存在的目录。均不存在时回退到开发路径。

---

## 3. WebSocketBridge —— WebSocket 桥接器

**文件**：`WebSocketBridge.java`（444 行）

**基类**：`org.java_websocket.server.WebSocketServer`（Java-WebSocket 库）

**职责**：Display 模块的核心——承担上下行消息桥接。下行从 Redis 黑板读取每 tick 完整快照并序列化为 JSON 广播；上行接收浏览器命令，分流处理 `ADD_CAR`（本地进程管理）和 `REQUEST_REPLAY`（回放协调），其余原样转发至 RabbitMQ `ControllerCmd` 队列。

### 3.1 构造参数

```java
public WebSocketBridge(int port, BlackboardClient blackboard, MqSender mqSender)
```

| 参数 | 说明 |
|------|------|
| `port` | WebSocket 监听端口（默认 8888） |
| `blackboard` | Redis 黑板读取客户端（只读使用） |
| `mqSender` | MQ 发送适配器（`@FunctionalInterface`），用于转发浏览器命令到 ControllerCmd |

构造时还执行：
- `externalProcessCarIds` = 从 `DeployConfigLoader.loadOptional()` 读取 deploy 配置中 `cars` 字段（由 Person B 等外部机器启动的车辆），转换为 `Set.copyOf()`
- `DynamicCarLauncher.setProcessExitListener(this::onDynamicCarProcessExit)` — 注册进程退出回调

### 3.2 核心常量

| 常量 | 值 | 说明 |
|------|-----|------|
| `DEFAULT_W / DEFAULT_H` | 30 | 地图默认尺寸（TaskConfig 未设置时回退） |
| `CAR_PREFIX_LENGTH` | 3 | 车辆 ID 前缀长度（"Car" 占 3 字符） |
| `COMPACT_MAP_CELL_THRESHOLD` | 2500 | 超过此格数（如 50×50=2500 格为临界值）时改用 Base64 位图推送 |

### 3.3 下行（Java → 浏览器）：pushSimulationState()

**调用链**：`DisplayMain.onRefreshAllReceived()` → `WebSocketBridge.pushSimulationState(tick, explorationRate)`

```java
public void pushSimulationState(int tick, int explorationRate) {
    if (clients.isEmpty()) { return; }   // 无浏览器连接时跳过，避免无效 I/O
    lastBroadcastTick = tick;
    int syncedRate = resolveExplorationRate();
    // 每 20 tick、第 1 tick、或探索率 100% 时打印 INFO 日志
    try {
        broadcast(serializeState(tick, syncedRate));
    } catch (RuntimeException e) {
        LOG.error("推送仿真状态失败 tick={}", tick, e);
    }
}
```

**resolveExplorationRate() —— 同步探索率**：不从 MQ 消息中读取探索率（MQ 可能滞后于 Redis 位图），而是直接从黑板实时读取。若 `blackboard.isExplorationComplete()` 为 true 则返回 100，否则返回 `blackboard.getExplorationRate()`。

**serializeState() 核心逻辑**：

```
 1. 从 Redis 黑板读取 TaskConfig (Map<String,String>: mapWidth/mapHeight/carCount/algorithm/obstacleRatio/tickInterval)
 2. 读取地图位图快照 (MapBitmapSnapshot):
    ├─ mapView()     → 已探索格位图 (Redis BITFIELD GET, byte[])
    ├─ mapBlock()    → 障碍物格位图 (byte[])
    └─ mapSealed()   → 封闭区域位图 (byte[])
 3. 构建车辆信息列表: discoverActiveCarIds() → 遍历每辆车读取:
    ├─ position (Point)    ← blackboard.getCarPosition(carId)
    ├─ target (Point|null) ← blackboard.getCarTarget(carId)
    ├─ route (List<Point>) ← blackboard.getCarRoute(carId)
    ├─ status (CarStatus)  ← blackboard.getCarStatus(carId)，无数据时默认为 IDLE
    ├─ steps (int)         ← blackboard.getCarSteps(carId)
    └─ effectiveSteps (int)← blackboard.getCarEffectiveSteps(carId)
    └─ 按 carNumber 升序排序
 4. 读取场次发起人: blackboard.getSimRunStartedBy() (Optional<String>)
 5. 序列化策略 (按格数阈值分叉):
    ├─ ≤ 2500 格: 构建 SimulationState 对象 → JSON.toJSONString()
    │   (mapView/mapBlock/mapSealed 转为 boolean[][] → 标准 JSON 布尔数组)
    └─ > 2500 格: 手动构建 JSONObject
        mapViewB64/mapBlockB64/mapSealedB64 → Base64.getEncoder().encodeToString(byte[])
        前端不再收到 boolean[][]，而是 Base64 字符串，由前端 decodeBitmapB64() 解码
```

**broadcast() 广播**：遍历 `clients` 集合，对每个 WebSocket 连接调用 `conn.send(jsonString)`。连接可能在遍历过程中断开，故包裹 try-catch 忽略单连接发送失败。

**广播的 JSON 结构**（≤ 2500 格）：

```json
{
  "tick": 42,
  "explorationRate": 67,
  "taskConfig": {
    "mapWidth": "30", "mapHeight": "30", "carCount": "3",
    "obstacleRatio": "0.15", "algorithm": "ASTAR",
    "tickInterval": "500", "active": "true"
  },
  "mapView": [[false,true,...],...],
  "mapBlock": [[false,false,...],...],
  "mapSealed": [[false,false,...],...],
  "runStartedBy": "admin",
  "cars": [
    {
      "carId": "Car001", "number": 1,
      "position": {"x":5,"y":12},
      "target": {"x":28,"y":3},
      "route": [{"x":5,"y":12},{"x":5,"y":11},...],
      "status": "MOVING",
      "steps": 142, "effectiveSteps": 118
    }
  ]
}
```

大图模式（> 2500 格）下 `mapView`/`mapBlock`/`mapSealed` 由 `mapViewB64`/`mapBlockB64`/`mapSealedB64` String 字段替代，不再包含 `boolean[][]` 数组。

**Base64 位图编码决策**：`boolean[100][100]` JSON 序列化约 ~30KB（每个 true/false 约 4-5 字节 JSON），而 Base64 编码的 byte[]（10000 bit = 1250 byte → Base64 约 1667 字符）仅 ~1.7KB。对于 `boolean[50][50]`（2500 格）为临界值，JSON ≈ ~7.5KB，Base64 ≈ ~440 字符。阈值 2500 精准切在"JSON 位图开始明显膨胀"的拐点。

### 3.4 上行（浏览器 → Java）：onMessage()

```java
@Override
public void onMessage(WebSocket conn, String message) {
    JSONObject msg = JSON.parseObject(message);
    String type = msg.getString("type");

    if ("ADD_CAR".equals(type)) {
        handleAddCar();                          // 本地处理，不转发 MQ
    } else if ("REQUEST_REPLAY".equals(type)) {
        handleReplayRequest(conn, msg);           // 回放协调处理
    } else {
        if ("RESET".equals(type) || "SET_CONFIG".equals(type)) {
            stopAllDynamicCars();                // 重置/新任务前停掉所有动态小车
        }
        if (replayCoordinator != null && "SET_CONFIG".equals(type)) {
            replayCoordinator.beforeSimCommand(type, msg.getJSONObject("data"));
        }
        mqSender.send(QueueNames.CONTROLLER_CMD, message);  // 原样转发至 Controller
    }
}
```

**支持的全部上行消息类型（7 种）**：

| 消息类型 | 处理方式 | 说明 |
|----------|----------|------|
| `SET_CONFIG` | 转发 ControllerCmd | 开始任务；先行调用 `stopAllDynamicCars()` + `replayCoordinator.beforeSimCommand()` 记录场次操作人 |
| `RESET` | 转发 ControllerCmd | 重置系统；先行调用 `stopAllDynamicCars()` 清理动态小车进程 |
| `TOGGLE_PAUSE` | 转发 ControllerCmd | 暂停/继续仿真 |
| `SET_TICK_INTERVAL` | 转发 ControllerCmd | 调速（前端滑块值 100-2000ms） |
| `TOGGLE_OBSTACLE` | 转发 ControllerCmd | 切换障碍格状态（右键点击画布格） |
| `ADD_CAR` | 本地处理（不转发） | 动态启动新车 JVM 进程 |
| `REQUEST_REPLAY` | 本地处理（不转发） | 请求实时/历史回放数据 |

### 3.5 动态加车：handleAddCar()

**完整流程**（synchronized 锁保护）：

```
 1. synchronized (addCarLock)           ← 防止并发 ADD_CAR 分配到重复 CarId
 2. pruneDeadLaunches()                 ← 从 displayLaunchedCarIds 中清理已死亡的进程记录
 3. resolveNextCarId()                  ← DynamicCarIdResolver.resolve():
    ├─ blackboard.discoverCarIds()      ← 当前 Redis 中已注册的活跃 CarId 集合
    ├─ externalProcessCarIds            ← deploy 配置中外部进程（如 Person B）
    ├─ displayLaunchedCarIds            ← 本 Display 已启动的小车
    └─ DynamicCarLauncher::isProcessAlive ← 过滤已死亡的进程
    └─ 从 Car001 递增，优先填补黑板已注册但无进程的缺口编号
 4. Path projectRoot = Path.of(".").toAbsolutePath().normalize()
 5. broadcastEvent("CAR_PENDING", Map.of("carId", carId))   ← 通知前端开始等待
 6. DynamicCarLauncher.isLaunchAvailable(projectRoot)       ← 检查 car/target/car-1.0-SNAPSHOT.jar 是否存在
    └─ 不存在 → broadcastEvent("CAR_LAUNCH_FAILED", ...) + return
 7. DynamicCarLauncher.isProcessAlive(carId)                ← 检查同名进程是否已运行
    └─ 已在运行 → broadcastEvent("CAR_LAUNCH_FAILED", "...进程已在运行，请勿重复添加") + return
 8. displayLaunchedCarIds.add(carId)
 9. DynamicCarLauncher.launchAsync(carId, projectRoot, beforeLaunch, onSuccess, onFailure):
    ├─ beforeLaunch.run()               ← prepareCarQueue(carId): 声明 + 清空新车 MQ 队列
    ├─ onCarLaunched(carId)             ← broadcastEvent("CAR_LAUNCHED") + pushSimulationState()
    └─ onCarLaunchFailed(carId, error)  ← displayLaunchedCarIds.remove(carId) + broadcastEvent("CAR_LAUNCH_FAILED")
```

**防重复机制**：
- `synchronized (addCarLock)` 保证同一时刻只有一个线程执行加车逻辑
- `resolveNextCarId()` 综合考虑 Redis 活跃车辆 + deploy 外部小车 + 本进程已启动小车 + 进程存活检测（`DynamicCarLauncher.isProcessAlive()`），避免 ID 冲突
- `isProcessAlive()` 的二次检查（第 7 步）防止两次 ADD_CAR 被分配到同一个刚启动但尚未在 `displayLaunchedCarIds` 中反映的 CarId
- `displayLaunchedCarIds` 使用 `ConcurrentHashMap.newKeySet()` 保证线程安全

### 3.6 RESET / SET_CONFIG 时的进程清理

`stopAllDynamicCars()` 在收到 `RESET` 或 `SET_CONFIG` 时被调用：

```java
private void stopAllDynamicCars() {
    DynamicCarProcessKiller.killAllDynamicExcept(externalProcessCarIds);
    // 先杀所有 OS 层面的动态小车进程（排除 deploy 配置的外部小车）
    for (String carId : Set.copyOf(displayLaunchedCarIds)) {
        DynamicCarLauncher.stopProcess(carId);
        // 逐一 stop 本进程管理的进程（destroy() → waitFor(3s) → destroyForcibly()）
    }
    displayLaunchedCarIds.clear();
}
```

### 3.7 handleReplayRequest() —— 回放请求路由

```java
private void handleReplayRequest(WebSocket conn, JSONObject msg) {
    if (replayCoordinator == null) {
        conn.send("{\"type\":\"REPLAY_ERROR\",\"error\":\"回放服务未就绪\"}");
        return;
    }
    Long runId = msg.getLong("runId");
    if (runId != null && runId > 0) {
        replayCoordinator.sendStoredReplay(conn, runId);   // 历史场次（SQL Server）
    } else {
        replayCoordinator.sendLiveReplay(conn);             // 当前实时场次（Redis 黑板）
    }
}
```

### 3.8 辅助事件广播方法

```java
private void broadcastEvent(String eventType, Map<String, String> payload) {
    JSONObject json = new JSONObject();
    json.put("type", eventType);
    payload.forEach(json::put);
    broadcast(json.toJSONString());
}
```

**事件类型汇总**：

| 事件类型 | payload | 触发时机 |
|----------|---------|----------|
| `CAR_PENDING` | `{carId}` | 加车流程开始时，通知前端显示等待状态 |
| `CAR_LAUNCHED` | `{carId}` | 新车进程成功启动并就绪 |
| `CAR_LAUNCH_FAILED` | `{carId, reason}` | JAR 不存在、进程已运行、启动失败 |

### 3.9 WebSocket 生命周期回调

| 回调 | 行为 |
|------|------|
| `onOpen(conn, handshake)` | `clients.add(conn)`，记录连接 IP 日志 |
| `onClose(conn, code, reason, remote)` | `clients.remove(conn)`，记录断开日志 |
| `onError(conn, ex)` | 仅记录 `LOG.error("WebSocket 错误: {}", ex.getMessage())`，不抛出异常 |
| `onStart()` | 记录 "WebSocket 服务已启动，端口: XXXX" |

### 3.10 MqSender 接口（DIP 依赖倒置）

```java
@FunctionalInterface
public interface MqSender {
    void send(String queue, String message);
}
```

WebSocketBridge 不直接依赖 `MessageBus`，只依赖此函数式接口。DisplayMain 通过 lambda 注入：
```java
WebSocketBridge.MqSender mqSender = (queue, message) -> {
    try { messageBus.publish(queue, message); }
    catch (IOException e) { LOG.error("MQ 发送失败: queue={}", queue, e); }
};
```

**设计收益**：WebSocketBridge 可脱离 RabbitMQ 进行单元测试（注入 Mock MqSender）。

### 3.11 extractCarNumber() —— 车号提取工具

```java
static int extractCarNumber(String carId) {
    if (carId == null || carId.length() <= 3) return 0;
    try {
        return Integer.parseInt(carId.substring(3));
    } catch (NumberFormatException e) {
        return 0;
    }
}
```

示例：`"Car001"` → 1，`"Car012"` → 12，`"Car"` → 0，`"CarABC"` → 0。

### 3.12 parseIntOrDefault() —— 安全数值解析

```java
static int parseIntOrDefault(String value, int defaultValue) {
    if (value == null) return defaultValue;
    try {
        return Integer.parseInt(value);
    } catch (NumberFormatException e) {
        return defaultValue;
    }
}
```

用于处理 TaskConfig Hash 中的数值字段（mapWidth、mapHeight 等），这些字段在 TaskConfigurator 未初始化时为 null 或空字符串。

---

## 4. HttpFileServer —— 嵌入式 HTTP 服务器

**文件**：`HttpFileServer.java`（143 行）

**技术栈**：JDK 内置 `com.sun.net.httpserver.HttpServer`（零外部 Web 框架依赖，JAR 体积小）

**职责**：服务静态前端资源（HTML/CSS/JS/WebGL），并提供 REST API 路由和 Bearer Token 鉴权。

**包可见性**：`final class`（package-private），不对外暴露。

### 4.1 核心设计 — 单一 Context 路由

所有请求注册到 `"/"` context，由 `handle()` 方法按路径前缀分发：

```java
private void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();

    if (path.startsWith("/api/auth/"))      { authApi.handle(path, exchange); return; }
    if (path.startsWith("/api/admin/"))     { checkAuth→adminApi.handle(path, exchange); return; }
    if (path.startsWith("/api/analysis/"))  { checkAuth→analysisApi.handle(path, exchange); return; }
    if (path.startsWith("/api/replay/"))    { checkAuth→replayApi.handle(path, exchange); return; }
    if (isWhitelisted(path))                { serveStatic(exchange, path); return; }
    if (!checkAuth(exchange, path)) return;  // 未登录返回 401
    serveStatic(exchange, path);
}
```

**路由优先级**：
1. `/api/auth/*` — 无需认证（login/register 在白名单中，change-password/me/logout 由 AuthApiHandler 内部鉴权）
2. `/api/admin/*` — 需 Token 认证
3. `/api/analysis/*` — 需 Token 认证
4. `/api/replay/*` — 需 Token 认证
5. 白名单路径 — 直接服务静态资源，无需认证
6. 其他路径 — 先鉴权，通过后服务静态资源

### 4.2 白名单机制（9 个路径前缀）

```java
private static final Set<String> WHITELIST = Set.of(
    "/login.html", "/index.html", "/dashboard.html", "/analysis.html",
    "/css/", "/js/", "/unity/", "/api/auth/login", "/api/auth/register",
    "/favicon.ico", "/");
```

| 类别 | 路径 | 说明 |
|------|------|------|
| 页面 | `/login.html` `/index.html` `/dashboard.html` `/analysis.html` | 所有 HTML 页面免认证（页面内 JS 自行检查登录态） |
| 静态资源 | `/css/` `/js/` `/unity/` `/favicon.ico` | CSS/JS/WebGL 资源免认证 |
| auth API | `/api/auth/login` `/api/auth/register` | 认证接口本身免认证 |
| 根路径 | `/` | 重定向到 `/login.html` |

注意：`user-management.html` 不在白名单中，但可通过 `isWhitelisted("/user-management.html")` 为 false 时进入 checkAuth 分支，通过后仍可服务。实际上因白名单使用 `startsWith`，`/user-management.html` 不匹配任何前缀，会先检查认证，认证通过后才 serveStatic。这保证了非登录用户无法直接访问管理页面。

### 4.3 Token 认证过滤器

```java
private boolean checkAuth(HttpExchange exchange, String path) throws IOException {
    String token = SessionManager.extractToken(
            exchange.getRequestHeaders().getFirst("Authorization"));
    if (token == null || sessionManager.validate(token).isEmpty()) {
        sendJson(exchange, 401, "{\"success\":false,\"error\":\"请先登录\"}");
        return false;
    }
    return true;
}
```

- 从 HTTP 请求头 `Authorization: Bearer <token>` 字段提取 Token
- Token 由 `SessionManager` 校验（Redis 存储，默认 30 分钟过期）
- 校验失败返回 `401` + JSON 错误消息

### 4.4 静态文件服务

```java
private void serveStatic(HttpExchange exchange, String path) throws IOException {
    String resolvedPath = "/".equals(path) ? "/login.html" : path;
    Path filePath = webRoot.resolve(resolvedPath.substring(1));
    if (Files.isRegularFile(filePath)) { serveFile(exchange, filePath); }
    else { sendNotFound(exchange); }
}
```

根路径 `/` 自动重定向到登录页。

**serveFile() 实现**：
```java
private void serveFile(HttpExchange exchange, Path filePath) throws IOException {
    String contentType = detectContentType(filePath);
    byte[] content = Files.readAllBytes(filePath);
    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.sendResponseHeaders(200, content.length);
    try (OutputStream body = exchange.getResponseBody()) { body.write(content); }
}
```

### 4.5 MIME 类型映射（9 种）

```java
private static final Map<String, String> CONTENT_TYPES = Map.of(
    "html", "text/html; charset=utf-8",
    "css",  "text/css; charset=utf-8",
    "js",   "application/javascript; charset=utf-8",
    "json", "application/json",
    "png",  "image/png",
    "svg",  "image/svg+xml",
    "wasm", "application/wasm",
    "data", "application/octet-stream",
    "ico",  "image/x-icon"
);
```

`detectContentType()` 通过文件扩展名（`lastDot` 之后的小写部分）查表，未匹配时回退到 `application/octet-stream`。无扩展名时（如 README）也回退到 `application/octet-stream`。

**已知限制**：扩展名匹配是大小写敏感的（`.HTML` ≠ `.html`），大写扩展名回退为 octet-stream。

### 4.6 API 路由完整清单

| 路径前缀 | 处理器 | 需要认证 | 处理的主要端点 |
|----------|--------|----------|---------------|
| `/api/auth/` | `AuthApiHandler` | 否（白名单放行 login/register；其余内部鉴权） | `POST /login`, `POST /register`, `POST /change-password`, `GET /me`, `POST /logout` |
| `/api/admin/` | `AdminApiHandler` | 是 | `GET /users?page=&size=&search=&role=`, `POST /users/{username}/reset-password`, `GET /registrations?page=&size=&status=`, `POST /registrations/{id}/approve`, `POST /registrations/{id}/reject`, `GET /logs?username=&size=` |
| `/api/analysis/` | `AnalysisApiHandler` | 是 | `GET /records?page=&size=`, `POST /records`, `DELETE /records/{runId}`, `POST /discard` |
| `/api/replay/` | `ReplayApiHandler` | 是 | `GET /runs?page=&size=`, `GET /runs/{runId}` |

---

## 5. DynamicCarLauncher —— 动态小车进程启动器

**文件**：`DynamicCarLauncher.java`（193 行）

**包可见性**：`final class`（package-private），仅被 `WebSocketBridge` 使用。

**职责**：通过已打包的 `car/target/car-1.0-SNAPSHOT.jar` 异步启动动态添加的小车 JVM 进程，管理进程生命周期（启动、停止、存活检测、退出回调）。

### 5.1 核心常量与方法

| 常量/方法 | 值/说明 |
|-----------|---------|
| `CAR_JAR_RELATIVE` | `"car/target/car-1.0-SNAPSHOT.jar"`（相对于项目根目录） |
| `DYNAMIC_FLAG` | `"--dynamic"`（启动参数标记，使 car JAR 以动态模式运行） |
| `RUNTIME_JAR_DIR` | `"logs/runtime"`（复制 JAR 到运行目录，隔离源 JAR 文件锁） |
| `LAUNCH_GAP_MS` | 800ms（两次启动间隔，给上一个进程 JVM 启动留时间） |
| `STOP_TIMEOUT_SEC` | 3s（优雅停止超时，超时后 destroyForcibly） |
| `LAUNCH_LOCK` | 静态 `Object`，确保同时只启动一个进程（防止端口/文件冲突） |
| `RUNNING_PROCESSES` | `ConcurrentHashMap<String, Process>`，carId → Process |
| `processExitListener` | `volatile Consumer<String>`，进程退出时回调（通知 WebSocketBridge 清理） |

### 5.2 launchAsync() 三层异步结构

```
launchAsync(carId, projectRoot, beforeLaunch, onSuccess, onFailure)
  └─ new Thread("car-launch-" + carId, daemon).start()
       └─ launchWithCallback(carId, projectRoot, beforeLaunch, onSuccess, onFailure)
            ├─ launchBlocking(carId, projectRoot, beforeLaunch)   ← 抛异常则到 onFailure
            └─ onSuccess.accept(carId)                            ← 成功则到 onSuccess
```

### 5.3 launchBlocking() 完整流程（synchronized 块）

```
 1. synchronized (LAUNCH_LOCK)
 2. DynamicCarProcessKiller.killProcessesForCar(carId)
    └─ 扫描 OS 所有进程，终止同名遗留 JVM（防止 MQ 队列冲突）
 3. stopExistingProcess(carId)
    └─ 从 RUNNING_PROCESSES 移除旧 Process → destroy() → waitFor(3s) → destroyForcibly()
 4. waitForRuntimeJarUnlock(projectRoot, carId)
    └─ 检查 logs/runtime/{carId}.jar 是否存在
    └─ 若存在，尝试删除（最多重试 5 次，每次间隔 200ms），释放文件锁
 5. beforeLaunch.run()     ← 清空 MQ 队列等前置操作
 6. launchOnce(carId, projectRoot):
    ├─ 验证源 JAR: car/target/car-1.0-SNAPSHOT.jar 存在
    ├─ 创建 logDir: logs/
    ├─ 复制 JAR: car/target/car-1.0-SNAPSHOT.jar → logs/runtime/{carId}-{timestamp}.jar
    │   (带时间戳，避免同名文件锁冲突)
    ├─ 构建命令:
    │   {java.home}/bin/java.exe -jar logs/runtime/Car003-1782214269601.jar Car003 --dynamic
    │   (Windows: java.exe, Unix: java, 回退: "java")
    │   工作目录: projectRoot
    │   输出重定向: logs/car-Car003-{timestamp}.log (redirectErrorStream=true)
    ├─ ProcessBuilder.start() → Process 存入 RUNNING_PROCESSES
    ├─ watchProcessExit(carId, process):
    │   process.onExit().thenRun(() → RUNNING_PROCESSES.remove + processExitListener.accept)
    └─ 日志: "已启动小车进程 Car003，JAR: logs/runtime/Car003-1782214269601.jar，日志: logs/car-Car003-1782214269601.log"
 7. Thread.sleep(800ms)     ← LAUNCH_GAP_MS，给进程 JVM 启动留时间
```

### 5.4 进程生命周期管理

**进程存储**：`ConcurrentHashMap<String, Process>`，carId → Process

**存活检测**：
```java
static boolean isProcessAlive(String carId) {
    Process process = RUNNING_PROCESSES.get(carId);
    return process != null && process.isAlive();
}
```

**进程退出监听**：
```java
private static void watchProcessExit(String carId, Process process) {
    process.onExit().thenRun(() -> {
        RUNNING_PROCESSES.remove(carId, process);
        processExitListener.accept(carId);
        LOG.info("小车进程已退出: {}", carId);
    });
}
```

`processExitListener` 由 DisplayMain 在构造时通过 `DynamicCarLauncher.setProcessExitListener(wsBridge::onDynamicCarProcessExit)` 设置，回调到 WebSocketBridge 的 `onDynamicCarProcessExit` 方法清理 `displayLaunchedCarIds`。

**停止进程**：
```java
private static void stopExistingProcess(String carId) {
    Process process = RUNNING_PROCESSES.remove(carId);
    if (process == null || !process.isAlive()) return;
    process.destroy();
    if (!process.waitFor(STOP_TIMEOUT_SEC, TimeUnit.SECONDS)) {
        process.destroyForcibly();
    }
}
```

### 5.5 isLaunchAvailable() —— JAR 可用性检查

```java
static boolean isLaunchAvailable(Path projectRoot) {
    return Files.isRegularFile(projectRoot.resolve(CAR_JAR_RELATIVE));
}
```

### 5.6 JAR 复制设计

每次启动创建一个带时间戳的 JAR 副本（`logs/runtime/Car003-1782214269601.jar`），原因：
- 隔离源 JAR 文件锁（Maven 重新打包时源 JAR 可能被占用）
- 同 CarId 的多次启动不会因文件锁冲突失败
- 便于排查：日志中包含具体 JAR 路径

---

## 6. DynamicCarIdResolver —— 动态车号分配器

**文件**：`DynamicCarIdResolver.java`（67 行）

**包可见性**：`final class`（package-private）。

**职责**：为动态添加小车选择可用的 CarId，优先填补黑板已注册但无进程的缺口编号（gap-filling algorithm），而非简单 max+1。

### 6.1 核心算法：resolve()

```java
static String resolve(Collection<String> onBoardCarIds,
                      Set<String> externalProcessCarIds,
                      Set<String> displayLaunchedCarIds,
                      Predicate<String> isProcessRunning)
```

**步骤**：

```
 1. 将 onBoardCarIds 按 CarNumber 升序排序
 2. 遍历排序后的列表：
    对每个 carId，检查 hasManagedProcess():
    ├─ externalProcessCarIds.contains(carId) → true（外部占用，跳过）
    ├─ !displayLaunchedCarIds.contains(carId) → false（Display 未启动过，即为空闲）
    └─ displayLaunchedCarIds 中有但 isProcessRunning.test(carId) 为 false → false（进程已死，可用）
    └─ 返回第一个 hasManagedProcess = false 的 carId（即"黑洞"——黑板有但无进程）
 3. 若所有黑板 CarId 都有进程，则最高编号 + 1：
    highestAssignedNumber = max(maxCarNumber(onBoard), maxCarNumber(external), maxCarNumber(display))
    → String.format("Car%03d", highestAssignedNumber + 1)
```

**测试覆盖的场景**（DynamicCarIdResolverTest）：
- 黑板有 Car001-Car006，外部有 Car001-Car003（Person B），剩余 Car004-Car006 无进程 → 返回 Car004
- 黑板有 Car001-Car003，全部被外部占用 → 返回 Car004（创建新号）
- 黑板为空 → 返回 Car001（从头开始）
- 后续加车：Car004 已分配 → 返回 Car005
- 死进程回收：Car004 进程已死（isProcessAlive = false）但仍在 displayLaunchedCarIds 中 → 返回 Car004（回收）

### 6.2 hasManagedProcess() —— 进程归属判定

```java
private static boolean hasManagedProcess(String carId,
                                         Set<String> externalProcessCarIds,
                                         Set<String> displayLaunchedCarIds,
                                         Predicate<String> isProcessRunning) {
    if (externalProcessCarIds.contains(carId)) return true;          // 外部管理
    if (!displayLaunchedCarIds.contains(carId)) return false;        // 从未启动，空闲
    return isProcessRunning.test(carId);                             // 已启动，检查是否存活
}
```

### 6.3 highestAssignedNumber() —— 跨三来源取最大编号

```java
private static int highestAssignedNumber(...) {
    int maxNumber = maxNumberIn(onBoardCarIds);
    maxNumber = Math.max(maxNumber, maxNumberIn(externalProcessCarIds));
    maxNumber = Math.max(maxNumber, maxNumberIn(displayLaunchedCarIds));
    return maxNumber;
}
```

---

## 7. DynamicCarProcessKiller —— OS 进程扫描与终止

**文件**：`DynamicCarProcessKiller.java`（115 行）

**包可见性**：`final class`（package-private）。

**职责**：扫描操作系统中带 `--dynamic` 标记的 Java 进程，按需终止指定车辆或全部动态小车的 JVM 进程。防止同 carId 多进程抢占同一 MQ 队列。

### 7.1 核心方法

| 方法 | 说明 |
|------|------|
| `killProcessesForCar(carId)` | 扫描并终止所有命令行中包含指定 carId 的动态进程 |
| `killAllDynamicExcept(preservedCarIds)` | 终止所有 `--dynamic` 进程（排除 preservedCarIds），用于 RESET/SET_CONFIG 时清理 |
| `commandLineTargetsCar(commandLine, carId)` | 判断进程命令行是否属于指定车辆 |
| `extractDynamicCarId(commandLine)` | 从命令行中提取车辆 ID |

### 7.2 命令行匹配策略

**匹配条件**：
1. 命令行必须包含 `--dynamic` 标志
2. 且满足以下任一条件：
   - 命令行包含 `{carId}.jar`（匹配 timestamped JAR 路径）
   - 命令行匹配正则 `(?s).*\sCar\d{3}\s+.*`（独立单词 CarID）

**测试覆盖**：
- 共享 JAR 启动：`java -jar car/target/car-1.0-SNAPSHOT.jar Car005 --dynamic` → 匹配 Car005
- Timestamped JAR 启动：`java -jar logs/runtime/Car004-1782214269601.jar Car004 --dynamic` → 匹配 Car004
- Runtime JAR 启动：`java -jar logs/runtime/Car006.jar Car006 --dynamic` → 匹配 Car006
- 非动态进程：`java -classpath ... com.substation.car.CarMain Car003`（无 --dynamic）→ 不匹配

### 7.3 extractDynamicCarId() —— 从命令行提取 CarId

```java
static String extractDynamicCarId(String commandLine) {
    // 策略1: 正则 "\b(Car\d{3})\s+--dynamic\b" 匹配 "--dynamic" 前的 CarID
    // 策略2: 正则 "[/\\]runtime[/\\](Car\d{3})(?:-\d+)?\.jar" 匹配 runtime JAR 路径中的 CarID
}
```

**两个正则引擎**：
- `CAR_ID_BEFORE_DYNAMIC`：`\b(Car\d{3})\s+--dynamic\b` — 匹配 `--dynamic` 参数紧前方的 CarID
- `RUNTIME_JAR_CAR_ID`：`[/\\\\]runtime[/\\\\](Car\d{3})(?:-\\d+)?\\.jar` — 从 runtime JAR 路径提取 CarID（如 `Car004-1782214269601.jar` → `Car004`）

### 7.4 进程终止流程

```
 1. 扫描: ProcessHandle.allProcesses()
    ├─ 排除当前进程（handle.pid() != currentPid）
    ├─ 对每个进程: handle.info().commandLine().ifPresent(commandLine → ...)
    └─ 匹配条件（含 --dynamic 且匹配 carId）
 2. 终止每个匹配进程:
    ├─ process.destroy()                        ← 优雅终止
    ├─ process.onExit().get(3, SECONDS)         ← 等待最多 3 秒
    └─ 超时 → process.destroyForcibly()         ← 强制终止
```

### 7.5 调用时机

| 调用上下文 | 调用方法 | 说明 |
|------------|----------|------|
| DynamicCarLauncher.launchBlocking() | `killProcessesForCar(carId)` | 启动新车前先清理同名遗留进程 |
| WebSocketBridge.stopAllDynamicCars() | `killAllDynamicExcept(externalProcessCarIds)` | RESET/SET_CONFIG 时清理所有动态小车（排除外部） |

---

## 8. ReplayCoordinator —— 回放协调器

**文件**：`ReplayCoordinator.java`（46 行）

**包可见性**：`final class`（package-private）。

**职责**：协调仿真场次回放——支持实时场次（从 Redis 黑板读取当前运行快照）和历史场次（从 SQL Server `simulation_runs` 表按 `runId` 查询）。

### 8.1 构造参数

```java
ReplayCoordinator(BlackboardClient blackboard, SimulationRunStore store)
```

- `blackboard`：读取当前实时场次的 Redis 黑板数据
- `store`：查询历史场次的 `SimulationRunStore`（SQL Server）

### 8.2 核心方法

| 方法 | 说明 |
|------|------|
| `beforeSimCommand(type, data)` | `SET_CONFIG` 开始新任务时调用 `blackboard.beginSimRun(operator)`，从 data JSON 中提取 operator，写入 Redis 记录场次操作人 |
| `sendLiveReplay(conn)` | 从黑板实时读取当前场次完整数据：`ReplayDataBuilder.fromBlackboard(blackboard)` 构建 JSON（包含 maxTick、mapWidth、mapHeight、carHistories、explorationEvents、mapViewB64、mapBlock、mapSealed），通过 WebSocket 发送到请求浏览器 |
| `sendStoredReplay(conn, runId)` | 按 `runId` 从 SQL `simulation_runs` 表查询：`store.findById(runId)`。若存在 → `ReplayDataBuilder.fromRecord(record)` 序列化后发送；若不存在 → 发送 `REPLAY_ERROR` |

### 8.3 回放请求路由（在 WebSocketBridge 中）

```java
if ("REQUEST_REPLAY".equals(type)) {
    Long runId = msg.getLong("runId");
    if (runId != null && runId > 0) {
        replayCoordinator.sendStoredReplay(conn, runId);   // 历史场次
    } else {
        replayCoordinator.sendLiveReplay(conn);             // 当前实时场次
    }
}
```

### 8.4 数据流

**Live Replay 数据流**：
```
WebSocketBridge.onMessage("REQUEST_REPLAY")
  → replayCoordinator.sendLiveReplay(conn)
    → ReplayDataBuilder.fromBlackboard(blackboard)
      → 读取黑板 carHistories, explorationEvents, mapViewB64, mapBlock, mapSealed 等
      → 构建 JSONObject {maxTick, mapWidth, mapHeight, carHistories, explorationEvents, ...}
    → conn.send(payload.toJSONString())
```

**Stored Replay 数据流**：
```
浏览器: fetch('/api/replay/runs/{runId}')  ← HTTP API（带 Token）
  → ReplayApiHandler. 从 SimulationRunStore 查询
  → 返回 {success:true, data:{maxTick, mapWidth, mapHeight, carHistories, explorationEvents, ...}}
  → 前端 receiveReplayData(body.data)
```

或者通过 WebSocket：
```
WebSocketBridge.onMessage("REQUEST_REPLAY" + runId)
  → replayCoordinator.sendStoredReplay(conn, runId)
    → ReplayDataBuilder.fromRecord(record)
    → conn.send(payload.toJSONString())
```

---

## 9. 前端架构

### 9.1 页面清单（5 个 HTML）

| 页面 | 文件 | 说明 |
|------|------|------|
| 登录页 | `login.html` | 用户登录/注册入口，默认首页。双模式切换（登录 ↔ 注册），支持 Enter 键提交 |
| 主仪表盘 | `index.html` | 核心页面：双层 Canvas 地图（30×30 自适应网格）、左侧配置面板（地图尺寸/小车数量/障碍比/算法/节拍间隔）、车辆状态面板、控制按钮、步数排行榜、图例、回放控制器、修改密码弹窗 |
| 仪表盘 | `dashboard.html` | 角色路由中枢：根据用户角色（admin/simulator/analyst）动态显示可访问页面链接，含修改密码 + 退出登录 |
| 分析页 | `analysis.html` | 历史场次卡片网格展示、筛选（按算法）、选择对比模式（最多 5 条）、详情展开（KPI 卡片 + Donut 图 + 堆叠柱状图 + 均衡条）、JSON 导入/导出、记录删除 |
| 用户管理页 | `user-management.html` | 管理员专有：Tab 切分用户列表（搜索/角色筛选/分页/操作日志弹窗/重置密码弹窗）+ 注册审核（通过/拒绝/状态筛选/分页） |
| Unity WebGL 容器 | `unity/index.html` | iframe 内嵌页面，加载 Unity WebGL 场景，含自带摄像机控制（pointer 事件 Pan/Orbit/Zoom） |

### 9.2 JavaScript 文件（5 个）

| 文件 | 行数 | 职责 |
|------|------|------|
| `app.js` | 1317 | 核心：WebSocket 客户端（自动重连 3s）、双层 Canvas 渲染（map 层 + car 层）、控制面板交互（SET_CONFIG/RESET/TOGGLE_PAUSE/SET_TICK_INTERVAL/TOGGLE_OBSTACLE/ADD_CAR）、回放播放器、小车面板（含步数进度条）、排行榜、任务完成保存弹窗（计算效率/均衡分）、Base64 位图解码、增量探索格绘制 |
| `auth.js` | 414 | 认证：登录/注册/修改密码/登出/getCurrentUser/Token 管理、单用户单会话强制互斥（loginSeq + Tab Leader 心跳 2s + Session 轮询 3s）、被踢提示、storage 事件监听、角色权限控制（analyst 隐藏仿真控制按钮，simulator 隐藏分析链接） |
| `analysis.js` | 655 | 分析：从 `/api/analysis/records` 加载记录 → 卡片网格展示步数有效率（颜色分级：绿>60% / 黄40-60% / 红<40%）、算法筛选、选择对比模式、详情全屏展开（KPI 卡片：有效率/覆盖率/总步数/有效步数/无效步数/耗时、Donut 环形图、堆叠柱状图、均衡条）、记录删除、JSON 导入/导出（含去重合并） |
| `unity-view.js` | 245 | Unity 桥接：`UnityView` 全局对象，提供 init/is3D/onCanvasReady/syncSize/exit/resetForNewSimulation。2D/3D 视图切换（toggleView）、Unity 摄像机控制（Pan: 左键拖拽 → GameController.OnWebPan、Orbit: Shift+左键或中键拖拽 → GameController.OnWebOrbit、Zoom: 滚轮 → GameController.OnWebZoom）、仿真重置时 iframe reload（带时间戳防缓存） |
| `particles.js` | 60 | 粒子特效：创建 fixed position Canvas 元素（z-index:0, pointer-events:none），120 颗粒子（蓝色调 rgba(59,130,246,α)），正弦脉冲呼吸效果，均匀分布网格布局（resize 时重新分布），requestAnimationFrame 驱动 |

### 9.3 样式文件

**`css/style.css`**（216 行）：
- 全局 reset（`*,*::before,*::after` 盒模型）
- 浅色工业风主题（背景 `#F1F5F9`，卡片 `rgba(255,255,255,0.92)`）
- Header：52px 高，白色背景，`#E2E8F0` 底部边框
- 三列布局：左侧栏 240px + 中央地图 flex:1 + 右侧车辆面板 290px
- 按钮系统：`.btn-primary`（蓝）、`.btn-warning`（橙）、`.btn-danger`（红）、次级按钮（灰白）
- 表单控件：数字输入框、下拉选择、滑块（accent-color:#3B82F6）
- 地图区：Canvas 双层叠放（`#map-canvas` + `#car-canvas` 绝对定位）
- Unity 外壳：`.unity-shell` 隐藏/显示，`.unity-input-layer` 覆盖透明捕获层
- 登录页：居中卡片（max-width 380px）、大输入框（50px 高）
- 分析页：卡片网格 `.a-grid`、筛选工具栏 `.filter-row`、详情全屏 `.fsd`、对比弹窗 `.compare-modal`
- 用户管理页：Tab 导航、表格、分页、badge 状态标签、toast 通知
- 欢迎遮罩：仿真未开始时显示引导文案
- 回放控制：进度条、播放/暂停/步进按钮
- 排行榜：第 1 名金色 `#D97706`、第 2 名灰色、第 3 名浅灰
- 自定义滚动条（WebKit，8px 宽，`#CBD5E1` 滑块）

### 9.4 Unity WebSocket 重定向适配器

**`unity/ws-redirect.js`**（42 行）：
- Unity WebGL 构建时 WebSocket 地址写死为 `localhost`
- 在 Unity 加载前（`<script>` 在 `unity.loader.js` 之前），拦截原生 `window.WebSocket` 构造函数
- 通过 `PatchedWebSocket` 包装：若 URL 含 `localhost` 或 `127.0.0.1`，替换为当前页面的动态主机名（`window.location.hostname`）
- 端口通过查询参数 `?wsPort=` 或默认 8888
- 保持 `NativeWebSocket` 的原型链和静态常量（CONNECTING/OPEN/CLOSING/CLOSED）

### 9.5 Canvas 双层渲染架构

前端采用 **双层 Canvas 叠放** 策略，将静态地图背景与动态小车动画解耦：

```
┌──────────────────────────────────┐
│  #map-stack (position: relative) │
│  ┌────────────────────────────┐  │
│  │  #map-canvas (z-index: 1)  │  │  ← 地图层：网格、已探索格(#B0C4DE)、障碍格(#A0A0A0)、
│  │  - 动态 cell 尺寸适应容器   │  │     封闭格(#E53935)、网格线(#94A3B8)
│  │  - 仅 tick 数据到达时重绘   │  │     探索率 100% 时红色填充密封区
│  │  - image-rendering:pixelated│  │     每 MAP_RENDER_INTERVAL(3) tick 全量重绘一次
│  └────────────────────────────┘  │     增量模式：轻量 paintIncrementalExplored()
│  ┌────────────────────────────┐  │
│  │ #car-canvas (z-index: 2)   │  │  ← 小车层：彩色圆点(外圈 + 白内圈 + 编号) + 路线
│  │  - pointer-events:none      │  │     路线最多绘制 MAX_ROUTE_DRAW(10) 步
│  │  - 每 tick 清空重绘         │  │     路线起点从当前位置 + 1 步开始（排除已过点）
│  └────────────────────────────┘  │     按 carNumber 从 8 色调色板取色
│  ┌────────────────────────────┐  │
│  │ #unity-shell (z-index: 3)  │  │  ← Unity 3D 视图（2D/3D 切换）
│  │  iframe → unity/index.html │  │
│  │  #unity-input 透明捕获层    │  │     摄像机 Pan/Orbit/Zoom
│  └────────────────────────────┘  │
└──────────────────────────────────┘
```

**Canvas 尺寸自适应**（`finalizeCanvas()`）：
1. 从 `liveData.taskConfig` 读取 `mapWidth` × `mapHeight`
2. 计算可用区域：`.map-area` 的 clientWidth - 20, clientHeight - 20
3. `cellSize = max(4, min(availW/width, availH/height))` — 取高宽中较小方向，最小 4px
4. Canvas 实际像素 = `width × cellSize` × `height × cellSize`
5. 尺寸不变时跳过重设（`canvasReady` 标志 + 像素级比对）

**渲染优化**：
- **地图层重绘策略**：`shouldRepaintMapLayer(tick)` 判断是否需要全量重绘——
  - `mapLayerDirty == true`（新仿真开始时）
  - `tick <= 1`
  - `explorationRate >= 100`
  - `tick - lastMapRenderTick >= MAP_RENDER_INTERVAL(3)`（每 3 tick 一次全量）
- **增量探索格绘制**：不满足全量重绘条件时，调用 `paintIncrementalExplored(mapView)` 仅绘制新探索的格子，对比 `renderedMapView` 差异，大幅减少重绘开销
- **地图层缓存**：`cachedMapBlock` 和 `cachedMapSealed`（障碍物/密封区不变）缓存避免重复 Base64 解码
- **小车层**：每 tick 全量清空重绘（路线和位置变化频繁，缓存无意义）

**路线绘制**（`drawRoute()`）：
- 只在 `READY` / `MOVING` / `BLOCKED` / `WAITING_ROUTE` 状态下绘制
- 从车当前位置之后的第一个路由点开始（`findRouteDrawStart`），最多绘制 10 步
- 跳过非法移动（非曼哈顿距离 != 1 的跳跃）
- 颜色：半透明（`+ '80'`，即 50% 不透明度）的车色

### 9.6 WebSocket 客户端（app.js）

**连接管理**：
- 连接 URL：`ws://<host>:8888`（与当前页面同主机，端口固定 8888）
- 重连后首次 `onOpen` 时如果 `wasEverConnected`，自动发送 `RESET` 消息重新同步状态
- 断线重连：3 秒间隔自动重连（`RECONNECT_DELAY_MS = 3000`）

**下行消息处理（`onSocketMessage` 回调，按 type 分配）**：

| 消息 type | 处理逻辑 |
|-----------|----------|
| `REPLAY_DATA` | `receiveReplayData(msg)` — 构建 carIndex + tickViews 索引，显示回放控件 |
| `REPLAY_ERROR` | `alert(msg.error)` |
| `RUN_ARCHIVED` | 忽略（仅记录，无 UI 动作） |
| `CAR_PENDING` | `beginAddCarPending(msg.carId)` — 禁用按钮，显示 "添加 Car00X..."，30s 超时 |
| `CAR_LAUNCHED` | `clearAddCarPending(true)` + `syncCarCountFromLaunchedCar(msg.carId)` — 恢复按钮，同步车辆数 |
| `CAR_LAUNCH_FAILED` | `failAddCarPending(msg.reason)` — 恢复按钮，alert 失败原因 |
| 其他（即 SimulationState） | `normalizeMapPayload(msg)`（Base64 解码）+ Canvas 渲染 + 面板更新 + 全局信息更新 + 可能有 `maybeCompleteAddCarPending` 兜底 |

**上行消息发送（通过 `sendCommand()` 发送 JSON）**：

| 消息 type | data | 触发 UI |
|-----------|------|---------|
| `SET_CONFIG` | `{mapWidth, mapHeight, carCount, obstacleRatio, algorithm, tickInterval, active, operator}` | ▶ 开始按钮 |
| `RESET` | `{}` | ↺ 重置按钮 |
| `TOGGLE_PAUSE` | — | ⏯ 暂停按钮 |
| `SET_TICK_INTERVAL` | `{interval}` | 节拍间隔滑块 change 事件 |
| `TOGGLE_OBSTACLE` | `{row, col}` | Canvas 右键点击 |
| `ADD_CAR` | — | + 添加小车按钮 |
| `REQUEST_REPLAY` | 可选 `{runId}` | 回放下拉选择 + 回放按钮 |

**任务完成弹窗（`showSavePopup()`）**：
- 触发条件：`explorationRate >= 100` 或 `taskConfig.active === 'false'`
- 仅当 `isCurrentRunOperator(startedBy)`（当前用户是场次发起人）时弹窗
- 计算统计：总步数 = Σ car.steps、有效步数 = Σ car.effectiveSteps、有效率 = effective/total、均衡分 = 1 - std(各车 effectiveSteps)/avg
- 保存：`POST /api/analysis/records` → `alert` 通知 + 刷新回放列表
- 不保存：`POST /api/analysis/discard`（归档丢弃）

### 9.7 回放播放器

**核心参数**：`REPLAY_FRAME_MS = 200`（每帧回放间隔 200ms）。

**控件**：
- `$replayRunSelect`：历史场次下拉选择（通过 `GET /api/replay/runs?page=1&size=50` 加载，格式化为 `#{id} {algorithm} {rate}% tick{maxTick} ({startedAt})`）
- `$replaySlider`：回放进度滑动条（范围：0 ~ maxTick）
- `$replayTickLbl`：当前回放 tick 标签（"Tick N / M"）
- `$replayToggle`：播放/暂停按钮（⏸ / ▶）
- `$btnLive`：回到实时模式按钮（默认隐藏，回放时显示）

**receiveReplayData() 数据索引构建**：
1. 解析 `carHistories`：为每辆车构建 `carId → [{x,y,tick},...]` 映射（`_carIndex`），按 tick 排序
2. 解析 `explorationEvents`：事件格式 `"tick,row,col"`，按 tick 排序后逐 tick 构建 `_tickViews[tick]`（每 tick 一个 boolean[][] mapView 快照）
3. 若消息含 `mapViewB64`，将最终 mapView 合并到 `_tickViews[maxTick]`
4. 存储 `_mapBlock`、`_mapSealed` 用于每帧渲染

**renderReplayFrame() 单帧渲染**：
- 根据 `replay.currentTick` 查找对应的 `_tickViews[tick]`（探索格快照）和每车 `_carIndex[carId]` 的位置
- 构建合成 `frame` 对象（含 mapView、mapBlock、mapSealed、cars）
- 调用 `renderMapLayer()` 渲染地图层，手动绘制小车层

**历史场次回放（HTTP 路径）**：
1. 用户选择具体历史场次（非 "current"）
2. `fetch('/api/replay/runs/' + selectedReplayRunId)` → 获取 `{success, data}` 
3. `receiveReplayData(body.data)` — 同 WebSocket 路径

### 9.8 auth.js 完整特性

**核心存储键**：
| localStorage Key | 用途 |
|-----------------|------|
| `auth_token` | JWT/Bearer Token |
| `auth_login_seq` | 登录序列号（挤掉旧会话） |
| `auth_active_tab` | JSON `{tabId, ts, seq}` — Tab 互斥 Leader |
| `auth_kick_message` (sessionStorage) | 被踢消息（跨页面传递） |
| `auth_fresh_login` (sessionStorage) | 标记是否为刚登录的 Tab |
| `auth_tab_id` (sessionStorage) | 唯一 Tab ID（`tab_<timestamp>_<random>`） |

**三层互斥机制**：
1. **跨设备互斥**（Server-kicked）：`pollSession()` 每 3s 调用 `GET /api/auth/me`，若返回 401 + kicked 标记 → `handleServerKicked()`
2. **跨 Tab 互斥**（Other-tab）：`isOtherTabActive()` 检查 `auth_active_tab` Leader 记录（心跳 2s，5s 过期），非本 Tab 且 loginSeq 一致的 Leader 存在 → `handleOtherTabTaken()`
3. **新登录挤旧**（Displaced-by-new-login）：`isStaleTab()` 比较 `localStorage.loginSeq` 与 `sessionStorage.loginSeq`，不一致 → `handleDisplacedByNewLogin()`

**storage 事件监听**：当其他 Tab 修改 `auth_login_seq`、`auth_token` 或 `auth_active_tab` 时，立即触发互斥检查。

**角色权限控制（`applyPermissions()`）**：
- `admin`：无限制
- `analyst`：隐藏开始/暂停/重置/添加小车按钮，禁用障碍比/算法/节拍间隔控件
- `simulator`：隐藏统计分析链接

**暴露的全局 API**：`window.Auth = { login, logout, register, changePassword, checkAuth, getCurrentUser, getToken, onLoginSuccess, showKickMessageIfAny, applyPermissions, renderNavBar }`

### 9.9 analysis.js 完整特性

**数据来源**：`GET /api/analysis/records?page=1&size=50`（带 Token 认证头）

**卡片网格（`renderCardGrid()`）**：
- 每张卡片显示：场次编号、日期、操作人、步数有效率（大字，颜色分级）、探索覆盖率、车辆数/耗时/算法/障碍率
- 选择模式：每张卡片左侧出现 checkbox，最多勾选 5 条，自动更新对比按钮文字
- 删除按钮：`confirm()` 确认后 `DELETE /api/analysis/records/{runId}`

**详情全屏（`showDetail()`）**：
- KPI 卡片行：步数有效率、探索覆盖率、总步数、有效步数、无效步数、耗时
- 信息行：场次号、节拍、车辆数、算法、障碍率
- 均衡条：每车有效步数水平条（相对于最多步数的百分比），标注均衡指数
- Donut 环形图：有效 vs 无效步数比例（Canvas 手绘 arc）
- 堆叠柱状图：每车绿色（有效步数）+ 橙色（无效步数）堆叠

**筛选**：算法下拉选择（BFS/ASTAR/全部），由 `recordPassesFilter()` 判断，切换时重新渲染

**JSON 导入/导出**：
- 导出：`exportRecordsJson()` — 构建 `{version, exportedAt, records}` → Blob 下载
- 导入：文件选择器 → `parseImportPayload()` 解析 → `mergeImported()` 按 runId 去重 → 逐条 `POST /api/analysis/records` → 刷新列表

**对比弹窗**：表格展示选中记录的步数有效率、探索覆盖率、总步数、有效步数、耗时、tick、算法、障碍率、均衡指数

### 9.10 unity-view.js 完整特性

**UnityView 全局对象 API**：
```javascript
window.UnityView = {
    init: function(hooks),       // 初始化 DOM 引用 + 摄像机输入 + 2D/3D 按钮绑定
    is3D: function(),            // 查询当前是否为 3D 视图
    onCanvasReady: function(),   // Canvas 准备就绪回调（app.js 最终化后调用）
    syncSize: function(),        // 根据地图尺寸同步 Unity 外壳容器尺寸
    exit: function(),            // 强制退出 3D 视图回到 2D
    resetForNewSimulation: function()  // 仿真重置：退出 3D + 标记 needsFrameReload + 重载 iframe
};
```

**init(hooks)** 接收 4 个钩子函数：
- `getLiveData()` — 获取当前仿真数据（用于同步尺寸）
- `getReplayData()` — 获取回放数据
- `isCanvasReady()` — 查询 Canvas 是否就绪
- `requestFinalizeCanvas()` — 请求最终化 Canvas

**2D/3D 切换（`toggleView()`）**：
- 按钮文字：`🎮 3D视图` ↔ `📐 2D视图`
- 3D 模式：隐藏 `#welcome` 和 `#map-stack`，显示 `#unity-shell`，调用 `syncSize()` 同步容器尺寸
- 2D 模式：隐藏 `#unity-shell`，恢复 `#map-stack`（若 `isCanvasReady()`），否则显示 `#welcome`

**摄像机控制（`#unity-input` 透明覆盖层）**：

| 操作 | 模式 | Unity 方法 | dx/dy 符号 |
|------|------|-----------|-----------|
| 左键拖拽 | `pan` | `GameController.OnWebPan(dx,dy)` | 正方向 |
| Shift+左键 / 中键 / 右键拖拽 | `orbit` | `GameController.OnWebOrbit(dx,dy)` | 正方向 |
| 滚轮 | `zoom` | `GameController.OnWebZoom(-deltaY)` | deltaY 取反 |

- 事件绑定：`pointerdown` / `pointermove` / `pointerup` / `pointercancel` / `wheel` / `contextmenu`（阻止默认右键菜单）
- `setPointerCapture` / `releasePointerCapture` 防止拖出元素后丢失事件
- 仅在 `is3DView && !$unityShell.hidden` 时处理

**iframe 重载（`resetForNewSimulation()`）**：
- 若当前在 3D 视图，先退出（`exit()`）
- 设置 `needsFrameReload = true`
- 调用 `reloadFrame()`：`$unityFrame.src = 'unity/index.html?_=' + Date.now()`（时间戳防缓存，强制 Unity 重新加载并清空内部缓存）

### 9.11 particles.js 完整特性

- 创建 fullscreen fixed Canvas（`z-index: 0`，`pointer-events: none`）插入到 `document.body` 首位
- 120 颗粒子，初始按 `ceil(sqrt(120 × aspectRatio))` 列网格均匀分布（每格内随机微调）
- 每帧更新：位置漂移（`vx/vy` 0.35），边界循环（穿出右侧从左侧入）
- 呼吸效果：`alpha + sin(pulse) * 0.15`（pulse 每帧 +0.015）
- 颜色：`rgba(59,130,246, α)`（主题蓝色调）
- `resize` 时按新窗口比例重新分布粒子

---

## 10. 配置文件与部署

### 10.1 基础设施配置

`InfraConnectionConfig.resolve(args)` 解析命令行参数或环境变量，提供 `redisHost/redisPort/mqHost/mqPort`。

`DeployConfigLoader.loadOptional()` 读取 `deploy/infra.local.json`，提取 `cars` 字段（JSON 字符串数组，如 `["Car001","Car002","Car003"]`），列出由其他机器（如 Person B）启动的外部车辆进程。WebSocketBridge 在构造时加载此列表，保证 `ADD_CAR` 不会分配到已被外部占用的 CarId。

### 10.2 启动顺序

```
1. 启动 Redis（端口 6379）
2. 启动 RabbitMQ（端口 5672，管理界面 15672）
3. 确保 SQL Server 可访问（连接串见 DatabaseManager）
4. 启动 Controller（调度中枢）
5. 启动 Display（本模块）
   → http://localhost:8887/login.html
   → 默认管理员账号：admin / admin123
6. 可选：启动外部小车（如 Person B 的 car JVM，需在 deploy/infra.local.json 中声明）
```

---

## 11. 关键设计决策

| 决策 | 理由 |
|------|------|
| 嵌入式 HTTP 服务器（`com.sun.net.httpserver`）而非 Spring Boot / Tomcat | Display 模块定位为轻量级可视化前端，零外部 Web 框架依赖，JAR 体积小（~40KB vs Spring Boot 的 ~20MB） |
| 双层 Canvas 而非单层 Canvas 或 DOM | 地图格子只在新 tick 到来时变化（低频），小车位置每帧平滑过渡（高频），分层减少不必要的地图层重绘；增量探索格绘制进一步降低开销 |
| WebSocket JSON 而非二进制协议（如 Protobuf） | 便于前端直接 `JSON.parse()`，调试友好；Base64 位图已解决大图体积问题（>2500 格时压缩率约 20:1） |
| >2500 格时切换 Base64 位图编码 | `boolean[100][100]` JSON ≈ 30KB，Base64 byte[] ≈ 1.7KB；`boolean[50][50]` 为临界值 ≈ 7.5KB vs 0.44KB |
| `ADD_CAR` 在 Display 本地处理而非转发 Controller | `ADD_CAR` 本质是进程管理操作（启动新 JVM、复制 JAR、声明 MQ 队列），不属于仿真调度范畴；转发 Controller 会增加不必要的耦合和延迟 |
| DIP（依赖倒置）：WebSocketBridge 依赖 MqSender 接口而非 MessageBus | WebSocketBridge 可脱离 RabbitMQ 进行单元测试（注入 Mock MqSender），且符合软件体系结构课程要求 |
| 地图探索率从 Redis 黑板实时读取（`resolveExplorationRate()`） | 避免 MQ fanout 消息到达延迟导致的探索率显示滞后，确保与前端地图颜色渲染（从 Redis 位图直读）严格同步 |
| 无浏览器连接时跳过黑板读取 | 减少无效 Redis I/O（BITFIELD GET 是 O(n) 操作），降低系统负载 |
| 动态加车同步锁（`synchronized(addCarLock)` + `LAUNCH_LOCK`） | 双锁机制：addCarLock 防止并发分配重复 CarId；LAUNCH_LOCK 防止并发启动导致文件锁/端口冲突 |
| JAR 复制策略（带时间戳副本到 `logs/runtime/`） | 隔离源 JAR 文件锁（Maven 重新打包时源 JAR 可能被占用）；同 CarId 多次启动不冲突；有日志可追踪 |
| auth.js 三层互斥（Server-kicked + Tab Leader + Login Sequence） | 避免多设备/多 Tab 同时操作同一账号导致数据竞争和状态混乱 |
| Unity WebSocket 重定向（ws-redirect.js） | Unity WebGL 构建时 WebSocket 地址写死 localhost，部署到其他主机时需动态替换；通过原型链包装避免破坏 Unity 内部检测 |
| 前端 Base64 位图解码 | 大图模式下避免 JSON 体积膨胀；JavaScript `atob()` 原生高效，位运算解码在 10 万格（316×316）内性能可接受 |
| 任务完成保存弹窗（仅场次发起人可见） | 避免观看者误保存不属于自己的场次记录；通过 `runStartedBy`（Redis）与 `currentOperator`（Auth.getCurrentUser）比对 |
| Canvas 自适应尺寸 + `image-rendering: pixelated` | 任意地图比例（如 30×30 ↔ 100×100）下保持网格清晰；像素缩放保证格子风格一致 |
