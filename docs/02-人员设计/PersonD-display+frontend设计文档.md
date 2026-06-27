# Display 模块 + 前端页面设计文档

> **负责人**：Person D  
> **职责范围**：Display 模块（WebSocket 桥接 + HTTP 服务 + 动态小车管理 + 回放） + Launcher 一键启动器 + 全部前端 Web 页面  
> **日期**：2026-06-26  
> **状态**：已完成

---

## 一、Person D 职责概述

Person D 负责变电站巡检仿真系统的**可视化展示层**与**用户交互层**，是终端用户与仿真系统之间的桥梁。具体包括：

| 子模块 | 文件数 | 说明 |
|--------|--------|------|
| Display 核心 | 7 个 Java 文件 | WebSocket 实时推送、HTTP 静态服务、动态小车启停、回放协调 |
| Launcher 一键启动 | 1 个 Java 文件 | 按依赖顺序启动全部 7 个模块 |
| 前端页面 | 5 个 HTML + 5 个 JS + 1 个 CSS | 仿真主界面、登录注册、仪表盘、统计分析、用户管理 |
| Unity 3D 集成 | 1 个 JS + WebGL 构建 | 2D/3D 视图切换、摄像机桥接 |

**技术栈**：

| 层级 | 技术 |
|------|------|
| WebSocket 服务 | Java-WebSocket (端口 8888) |
| HTTP 服务 | com.sun.net.httpserver (端口 8887) |
| 消息中间件 | RabbitMQ (ControllerCmd 队列) |
| 数据黑板 | Redis (只读 + 快照读取) |
| 前端渲染 | HTML5 Canvas 双层 + JavaScript ES5 兼容 |
| 3D 视图 | Unity WebGL (iframe 嵌入) |
| 数据库 | SQL Server (用户、统计、回放记录) |

---

## 二、Display 模块详细设计

### 2.1 模块整体架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                          DisplayMain (入口)                          │
│  初始化：DatabaseManager → SqlUserStore → RegistrationStore          │
│         → OperationLogStore → SessionManager → AuthApiHandler       │
│         → AdminApiHandler → SimulationStatsStore                    │
│         → SimulationRunStore → RunArchiver                          │
│         → SimulationRecordService → AnalysisApiHandler              │
│         → ReplayApiHandler → WebSocketBridge → HttpFileServer       │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌──────────────────────┐  ┌──────────────────────────┐           │
│  │   WebSocketBridge     │  │    HttpFileServer          │           │
│  │   端口 8888           │  │   端口 8887                │           │
│  │                      │  │                            │           │
│  │  下行：Redis 快照     │  │  静态文件：resources/web/   │           │
│  │        → JSON 广播    │  │  API 路由：                │           │
│  │  上行：浏览器命令     │  │    /api/auth/*   认证      │           │
│  │        → RabbitMQ     │  │    /api/admin/*  管理      │           │
│  │                      │  │    /api/analysis/* 分析    │           │
│  │  ADD_CAR → 动态启动   │  │    /api/replay/*  回放    │           │
│  │  REQUEST_REPLAY →     │  │                            │           │
│  │  ReplayCoordinator    │  │  Token 认证中间件          │           │
│  └──────────────────────┘  └──────────────────────────┘           │
│                                                                     │
│  ┌──────────────────────┐  ┌──────────────────────────┐           │
│  │ DynamicCarLauncher    │  │   ReplayCoordinator       │           │
│  │   java -jar car.jar   │  │   SET_CONFIG → 记录发起人 │           │
│  │   --dynamic           │  │   实时回放 → 黑板快照     │           │
│  │                       │  │   历史回放 → SQL Server   │           │
│  │ DynamicCarIdResolver  │  │                            │           │
│  │   缺号优先 → max+1    │  │                            │           │
│  └──────────────────────┘  └──────────────────────────┘           │
│                                                                     │
│  ┌──────────────────────┐                                          │
│  │ DynamicCarProcessKiller│                                         │
│  │   清理 OS 遗留 JVM     │                                         │
│  └──────────────────────┘                                          │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.2 DisplayMain -- 模块入口

**文件**：`display/src/main/java/com/substation/display/DisplayMain.java` (151 行)

**启动流程**：

```
1. 解析 infra connection 配置（Redis、RabbitMQ 地址端口）
2. 创建 BlackboardClient(redisHost, redisPort, 30x30)
3. 创建 MessageBus(mqHost, mqPort, "guest", "guest")
4. 初始化 SQL Server 组件链：
   DatabaseManager -> SqlUserStore, RegistrationStore, OperationLogStore
5. 创建 SessionManager (基于 Redis JedisPool)
6. 创建 API 处理器：
   AuthApiHandler, AdminApiHandler, AnalysisApiHandler, ReplayApiHandler
7. 创建仿真统计组件：
   SimulationStatsStore -> SimulationRunStore -> RunArchiver
   -> SimulationRecordService
8. 创建 WebSocketBridge (WS 端口 8888)，注入 MQ 发送适配器
9. 配置 ReplayCoordinator，注入到 WebSocketBridge
10. 创建 HttpFileServer (HTTP 端口 8887)，注入所有 API 处理器
11. start(): 连接 MQ -> 声明 Fanout Exchange -> 订阅 REFRESH_ALL
    -> 启动 WS -> 启动 HTTP
```

**默认端口配置**：
- HTTP 端口：`8887`
- WebSocket 端口：`8888`
- Web 资源目录：`display/src/main/resources/web/`（fallback: `src/main/resources/web/`）

**消息流转**：

```
Controller 发 REFRESH_ALL (Fanout)
  -> DisplayMain.onRefreshAllReceived()
    -> 解析 tick + explorationRate
    -> wsBridge.pushSimulationState(tick, explorationRate)
      -> 读取 Redis 黑板（mapView, car positions, status...）
      -> 构建 SimulationState JSON
      -> broadcast 给所有浏览器
```

### 2.3 WebSocketBridge -- 核心桥接器

**文件**：`display/src/main/java/com/substation/display/WebSocketBridge.java` (444 行)

WebSocketBridge 是整个 Display 模块的中枢，继承 `org.java_websocket.server.WebSocketServer`（端口默认 8888）。

#### 2.3.1 下行通道（Java -> 浏览器）

收到 `DisplayMain.pushSimulationState(tick, explorationRate)` 调用后：

```
1. 检查是否有浏览器连接（clients.isEmpty()？-> 跳过）
2. 调用 resolveExplorationRate() 从 Redis 黑板实时读取探索率
3. 构建 SimulationState：
   a. 读取 TaskConfig (mapWidth, mapHeight, algorithm...)
   b. 读取 mapView / mapBlock / mapSealed 位图（Redis bitmap）
   c. 动态发现所有 carId (discoverCarIds)
   d. 遍历每辆车构建 CarInfo (position, target, route, status, steps, effectiveSteps)
   e. 按 car number 排序
4. 判断地图格数：
   - <= 2500 格 -> 传输 boolean[][]（标准 JSON）
   - > 2500 格 -> Base64 编码位图（mapViewB64/mapBlockB64/mapSealedB64），前端自行解码
5. broadcast(json) 推送给所有连接的浏览器
```

**Base64 位图压缩**：当地图超过 2500 格（如 50x50=2500）时，3 个 boolean[][] 数组的 JSON 体积过大。改用 byte[] -> Base64 字符串传输，前端按 bit 解码还原为 boolean[][]。

**探索率同步**：不从 MQ 消息的 explorationRate 字段取值，而是直接从 Redis 黑板 `resolveExplorationRate()` 读取，避免 MQ 消息滞后于 Redis 位图。

**SimulationState JSON 结构**：

```json
{
  "tick": 150,
  "explorationRate": 42,
  "runStartedBy": "admin",
  "taskConfig": {
    "mapWidth": "30",
    "mapHeight": "30",
    "carCount": "3",
    "obstacleRatio": "0.15",
    "algorithm": "BFS",
    "tickInterval": "500",
    "active": "true",
    "operator": "admin"
  },
  "mapView": [[true,false,...], ...],
  "mapBlock": [[false,true,...], ...],
  "mapSealed": [[false,false,...], ...],
  "cars": [
    {
      "carId": "Car001",
      "number": 1,
      "position": {"x":5, "y":12},
      "target": {"x":20, "y":25},
      "routeList": [{"x":5,"y":12},{"x":6,"y":12},...],
      "status": "MOVING",
      "steps": 45,
      "effectiveSteps": 38
    }
  ]
}
```

#### 2.3.2 上行通道（浏览器 -> Java）

浏览器 WebSocket 消息由 `onMessage()` 接收，按 `type` 字段分发：

| type | 处理方式 | 附加逻辑 |
|------|---------|---------|
| `SET_CONFIG` | 转发到 ControllerCmd 队列 | 停掉所有动态小车；通知 ReplayCoordinator 记录场次 |
| `RESET` | 转发到 ControllerCmd 队列 | 停掉所有动态小车 |
| `TOGGLE_PAUSE` | 转发到 ControllerCmd 队列 | -- |
| `SET_TICK_INTERVAL` | 转发到 ControllerCmd 队列 | -- |
| `TOGGLE_OBSTACLE` | 转发到 ControllerCmd 队列 | -- |
| `ADD_CAR` | 本地处理（不转发 MQ） | 动态启动一台新车 JVM |
| `REQUEST_REPLAY` | 本地处理（不转发 MQ） | 调用 ReplayCoordinator |

#### 2.3.3 ADD_CAR 动态添加小车流程

```
1. 浏览器点击「+ 添加小车」-> WS 发 {"type":"ADD_CAR"}
2. WebSocketBridge.handleAddCar():
   a. synchronized(addCarLock) 加锁防止并发冲突
   b. pruneDeadLaunches() 清理已退出进程的 ID
   c. DynamicCarIdResolver.resolve() 选择下一个 carId
      - 优先：黑板上已注册但无 JVM 进程的编号
      - fallback：当前最大编号 + 1
   d. 广播 CAR_PENDING 事件通知前端
   e. 检查 car JAR 是否存在（car/target/car-1.0-SNAPSHOT.jar）
   f. 检查同 carId 进程是否已运行
   g. DynamicCarLauncher.launchAsync(carId, projectRoot, ...)
      - 复制 JAR 到 logs/runtime/ 目录（避免文件锁冲突）
      - 执行: java -jar <runtimeJar> <carId> --dynamic
      - 输出重定向到 logs/car-<carId>-<timestamp>.log
   h. 启动成功 -> 广播 CAR_LAUNCHED -> 主动推送一次仿真状态
   i. 启动失败 -> 广播 CAR_LAUNCH_FAILED (含原因)
   j. 进程退出监听 -> 从 displayLaunchedCarIds 移除
```

**DynamicCarIdResolver 编号策略**（`DynamicCarIdResolver.java`，67 行）：
- 收集黑板上所有 carId + externalProcessCarIds + displayLaunchedCarIds
- 按编号升序遍历已注册 carId，跳过已有进程的
- 第一个无进程的编号 -> 分配给新车
- 全部已有进程 -> max(carId) + 1

**DynamicCarProcessKiller 进程清理**（`DynamicCarProcessKiller.java`，115 行）：
- 遍历 OS 所有 Java 进程（`ProcessHandle.allProcesses()`）
- 匹配命令行中的 `--dynamic` 标志
- 正则提取 carId：`Car\d{3}` 出现在 `--dynamic` 前，或 `runtime/Car\d{3}*.jar`
- `killAllDynamicExcept(preservedCarIds)` 杀掉非保留的
- 先 `destroy()`，3 秒后 `destroyForcibly()`

#### 2.3.4 客户端管理

```java
private final Set<WebSocket> clients = ConcurrentHashMap.newKeySet();
```

- `onOpen`: 添加到 clients
- `onClose`: 从 clients 移除
- `pushSimulationState`: 如果 clients 为空直接返回（避免无效 Redis I/O）

### 2.4 HttpFileServer -- HTTP 静态服务与 API 路由

**文件**：`display/src/main/java/com/substation/display/HttpFileServer.java` (143 行)

基于 `com.sun.net.httpserver.HttpServer`（JDK 内置，零依赖），监听端口 8887。

#### 路由规则

```
/                         -> 重定向到 /login.html
/api/auth/*               -> AuthApiHandler（登录、注册、改密、获取用户信息）
/api/admin/*              -> AdminApiHandler（需认证，用户管理、注册审核）
/api/analysis/*           -> AnalysisApiHandler（需认证，仿真记录 CRUD）
/api/replay/*             -> ReplayApiHandler（需认证，历史场次查询）
/css/*, /js/*, /unity/*   -> 白名单直通（静态资源）
/login.html, /index.html, /dashboard.html, /analysis.html -> 白名单
其他                       -> 需认证后提供静态文件
```

#### 认证中间件

```java
private boolean checkAuth(HttpExchange exchange, String path) {
    String token = SessionManager.extractToken(
        exchange.getRequestHeaders().getFirst("Authorization"));
    if (token == null || sessionManager.validate(token).isEmpty()) {
        sendJson(exchange, 401, "{\"success\":false,\"error\":\"请先登录\"}");
        return false;
    }
    return true;
}
```

从 `Authorization: Bearer <token>` 头提取 JWT，调用 `SessionManager.validate(token)` 验证。未通过返回 401 JSON。

#### 静态文件服务

- 根路径 `/` -> 返回 `/login.html`
- 文件不存在 -> 404
- 自动检测 Content-Type（支持 html/css/js/json/png/svg/wasm/data/ico）

### 2.5 ReplayCoordinator -- 回放协调器

**文件**：`display/src/main/java/com/substation/display/ReplayCoordinator.java` (46 行)

| 方法 | 功能 |
|------|------|
| `beforeSimCommand("SET_CONFIG", data)` | 记录本次仿真的发起人 operator 到 Redis 黑板 |
| `sendLiveReplay(conn)` | 读取当前 Redis 黑板快照，构建回放数据发送给请求方 |
| `sendStoredReplay(conn, runId)` | 从 SQL Server 查询历史场次，构建回放数据并发送 |

**前端请求流程**：
1. 用户在下拉框选择场次（"当前场次" 或历史 runId）
2. 点击「路径回放」-> 发送 `{"type":"REQUEST_REPLAY", "runId": <id>}`
3. runId 为空/null -> 调用 `sendLiveReplay`（当前黑板数据）
4. runId 有值 -> 调用 `sendStoredReplay`（从 SQL Server 加载）

---

## 三、Launcher 模块 -- 一键启动器

**文件**：`launcher/src/main/java/com/substation/launcher/LauncherMain.java` (333 行)

### 3.1 设计目标

将所有 7 个模块的启动集成到一个命令中，替代手动逐个执行。

### 3.2 启动顺序（不可调换）

数据流向决定了启动顺序：

```
TaskConfigurator (+500ms) -> Navigator -> TargetPlanner (+300ms)
  -> CarxN (每车 +200ms) -> Display (+300ms) -> Controller (+1000ms)
```

| 模块 | 启动方式 | 间隔（毫秒） |
|------|---------|------------|
| TaskConfigurator | `new TaskConfiguratorMain(...).start()` | +500 |
| Navigator | `new NavigatorMain(...).start()` | -- |
| TargetPlanner | `new TargetPlannerMain(...).start()` | +300 |
| Car x N | `new CarMain(carId, ...).start()` | 200/车 |
| Display | `new DisplayMain(...).start()` | +300 |
| Controller | `new ControllerMain(...).start()` | +1000（最后启动） |

### 3.3 线程模型

- 每个模块在独立**非守护线程**中调用 `start()`
- `start()` 触发即返回（内部已启动后台线程、MQ 监听等）
- JVM 由各模块的非守护线程保持存活
- `Ctrl+C` 触发 shutdown hook 逆序优雅关闭

### 3.4 关闭钩子

```java
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    // 逆序遍历 SHUTDOWN_HOOKS，调用 close()/stop()
    for (int i = SHUTDOWN_HOOKS.size() - 1; i >= 0; i--) {
        SHUTDOWN_HOOKS.get(i).close();
    }
}));
```

Display 模块注册了 `display::stop`（停止 WS + HTTP + MQ + Redis），其他模块各自管理自己的资源。

### 3.5 命令行参数

```
用法: java com.substation.launcher.LauncherMain [选项]
  --redis-host H   Redis 地址 (默认 localhost)
  --redis-port P   Redis 端口 (默认 6379)
  --mq-host H      RabbitMQ 地址 (默认 localhost)
  --mq-port P      RabbitMQ 端口 (默认 5672)
  --cars N         小车数量 (默认 5)
  --http-port P    HTTP 端口 (默认 8887)
  --ws-port P      WebSocket 端口 (默认 8888)
  --help           打印帮助
```

### 3.6 错误处理

- 单模块启动失败不中断其余模块（`startInThread` 内部 try-catch）
- 参数解析错误打印帮助并 `System.exit(1)`
- 无参数运行时使用全部默认值

---

## 四、前端页面设计

### 4.1 页面总览

| 页面 | 文件 | 行数 | 功能 | 访问权限 |
|------|------|------|------|---------|
| 登录/注册 | `login.html` | 132 | 用户登录、注册（角色选择） | 匿名 |
| 仪表盘 | `dashboard.html` | 143 | 角色导航、修改密码、退出 | 已登录 |
| 仿真主界面 | `index.html` | 197 | Canvas 地图渲染、车辆控制、回放 | 管理员/仿真员 |
| 统计分析 | `analysis.html` | 88 | 仿真记录查看、KPI 详情、对比 | 管理员/分析员 |
| 用户管理 | `user-management.html` | 377 | 用户列表、注册审核、操作日志 | 管理员 |

### 4.2 仿真主界面 (index.html + app.js)

#### 4.2.1 整体布局结构

HTML 注释中描述的布局：

```
┌──────────────────────────────────────────────────────────────────┐
│  顶栏：标题 + 全局信息（节拍、探索率、耗时、回放指示器）          │
├────────────┬───────────────────────────────┬─────────────────────┤
│  左侧栏    │       中央地图区域              │   右侧栏            │
│  ·任务配置 │   Canvas 双层渲染              │   ·车辆状态卡       │
│  ·排行     │   540x540 px                   │    动态生成         │
│  ·图例     │   30x30 网格                   │                     │
│  ·回放控制 │   + Unity 3D iframe (可选)     │                     │
└────────────┴───────────────────────────────┴─────────────────────┘
```

**CSS 布局类**：
- `body`：`height:100vh; overflow:hidden`
- `.header`：`display:flex; height:52px; flex-shrink:0` (顶栏 52px 固定)
- `.main-container`：`display:flex; flex:1; overflow:hidden` (三列 flex 容器)
- `.sidebar`：`width:240px; min-width:240px; height:calc(100vh - 52px); overflow-y:scroll` (左侧固定 240px)
- `.map-area`：`flex:1; display:flex; overflow:auto; padding:10px` (中央自适应)
- `.cars-panel`：`width:290px; min-width:290px; overflow-y:scroll` (右侧固定 290px)

#### 4.2.2 顶栏 (header 元素)

| DOM ID / 类 | 元素类型 | 功能 |
|-------------|---------|------|
| `header` | `<header>` | 顶栏容器，52px 高，白色背景 |
| `header h1` | `<h1>` | 标题文字 "变电站巡检仿真系统" |
| `#info-tick` | `<span>` | 显示当前 tick 节拍数，格式 "节拍: N" |
| `#info-rate` | `<span>` | 显示探索覆盖率百分比，格式 "探索率: N%" |
| `#info-elapsed` | `<span>` | 显示已用时长计时器，格式 "xx:xx"，用 `setInterval` 每秒更新 |
| `#info-mode` | `<span>` (mode-tag) | 回放模式指示器，背景 #FEF2F2 红色标签，默认 hidden，回放时显示 "回放中(N)" 或 "XX 由 xxx 发起的任务已完成" |
| `.info-divider` | `<span>` | 垂直分隔线 (1px x 20px, #E2E8F0) |
| `#info-user` | `<span>` | 当前登录用户名（由 auth.js `renderNavBar` 填充） |
| `.nav-link` | `<a>` | "返回" 链接到 dashboard.html |

**全局信息更新逻辑**（`updateGlobalInfo` 函数）：
- 仿真未完成时：正常更新 tick、探索率
- 探索率 >= 100% 或 taskConfig.active === 'false' 时：
  - 冻结 tick 显示（不再更新）
  - 若当前用户是该场次发起人：显示 "探索率: 100% x 任务完成" + 弹出保存弹窗
  - 若当前用户是观看者：显示 "探索率: 100% x 任务完成（观看）"

#### 4.2.3 左侧栏 (sidebar 244px)

##### 任务配置面板 (.panel)

**地图尺寸控件**：

| DOM ID | 元素类型 | 默认值 | 范围 | 说明 |
|--------|---------|--------|------|------|
| `#cfg-width` | `<input type="number">` | 30 | 10-100 | 地图宽度 |
| `#cfg-height` | `<input type="number">` | 30 | 10-100 | 地图高度 |

两个数字输入框通过 `.input-pair` 包裹，中间用 "x" 分隔。

**小车数量控件**：

| DOM ID | 元素类型 | 默认值 | 范围 | 说明 |
|--------|---------|--------|------|------|
| `#cfg-carCount` | `<input type="number" readonly>` | 3 | 1-10 | 小车数量，只读（由后端控制在途小车数量），前端通过 `syncCarCountFromLiveData()` 和 `syncCarCountFromLaunchedCar()` 自动同步 |

**障碍物比例控件**：

| DOM ID | 元素类型 | 默认值 | 范围 | 步长 |
|--------|---------|--------|------|------|
| `#cfg-obstacleRatio` | `<input type="range">` | 0.15 | 0-0.5 | 0.05 |
| `#lbl-obstacleRatio` | `<em>` 标签 | 显示 "15%" | -- | -- |

联动：`onObstacleRatioInput()` -> `$lblObstacleRatio.textContent = Math.round(value * 100) + '%'`

**路径算法控件**：

| DOM ID | 元素类型 | 选项 |
|--------|---------|------|
| `#cfg-algorithm` | `<select>` | BFS (value="BFS") / A* (value="ASTAR") |

**节拍间隔控件**：

| DOM ID | 元素类型 | 默认值 | 范围 | 步长 |
|--------|---------|--------|------|------|
| `#cfg-tickInterval` | `<input type="range">` | 500 (ms) | 100-2000 | 100 |
| `#lbl-tickInterval` | `<em>` 标签 | 显示 "500ms" | -- | -- |

联动：`onTickIntervalInput()` -> `$lblTickInterval.textContent = value + 'ms'`

**节拍间隔编辑保护**：使用 `isEditingTickInterval` 标志位，防止用户正在拖动滑块时被后端同步覆盖。监听 pointerdown/pointerup/pointercancel/blur 事件设置/清除标志。

**节拍间隔实时同步**：`syncTickIntervalFromTaskConfig()` 在后端 taskConfig.tickInterval 与本地不一致时，仅在非编辑状态 (`!isEditingTickInterval`) 下同步。

**主控制按钮组** (`.btn-group`)：

| DOM ID | 显示文字 | CSS 类 | 初始状态 | 功能 |
|--------|---------|--------|---------|------|
| `#btn-start` | "开始" | `.btn .btn-primary` | 可选 | 发送 SET_CONFIG 开始仿真 |
| `#btn-pause` | "暂停" | `.btn .btn-warning` | disabled | 发送 TOGGLE_PAUSE 暂停/继续 |
| `#btn-reset` | "重置" | `.btn .btn-danger` | 可选 | 发送 RESET 重置仿真 |

**附加功能按钮组**：

| DOM ID | 显示文字 | CSS 类 | 功能 |
|--------|---------|--------|------|
| `#btn-addcar` | "+ 添加小车" | `.btn .secondary` | 发送 ADD_CAR 动态添加新车 |
| `#btn-unity` | "3D视图" | `.btn` | 切换 Canvas 2D / Unity 3D 视图 |

**回放控制区**：

| DOM ID | 元素类型 | 功能 |
|--------|---------|------|
| `#replay-run-select` | `<select>` | 历史场次下拉，选项由 `loadReplayRunList()` 从 `/api/replay/runs` 加载。默认含 "当前场次（内存）" 选项。 |
| `#btn-replay` | `<button>` | "路径回放" 按钮，触发 `enterReplay()` |
| `#btn-live` | `<button>` | "返回实时" 按钮，默认 hidden，触发 `exitReplay()` |

**回放进度条** (`#replay-controls`，默认 hidden)：

| DOM ID | 元素类型 | 功能 |
|--------|---------|------|
| `#replay-tick-label` | `<span>` | 显示 "Tick N / M" |
| `#replay-slider` | `<input type="range">` | tick 滑块，拖拽跳转到指定帧 |
| `#replay-step-prev` | `<button>` (⏮⏮) | 后退 1 tick |
| `#replay-toggle` | `<button>` | 播放/暂停 (▶ / ⏸) |
| `#replay-step-next` | `<button>` (⏭⏭) | 前进 1 tick |

##### 步数排行榜面板 (.panel)

| DOM ID | 元素类型 | 功能 |
|--------|---------|------|
| `#leaderboard` | `<ol>` | 有序列表，渲染每辆车的 effectiveSteps/totalSteps 排名 |

按 effectiveSteps 降序排列。第 1 名金色 (#D97706)，第 2 名灰色 (#64748B)，第 3 名浅灰 (#94A3B8)。

##### 图例面板 (.panel)

5 个 `.legend-item`，每项包含一个 `.dot` 彩色圆点 (10x10px, border-radius:50%) + 状态文字：

| 颜色 | 状态 | 中文名 |
|------|------|--------|
| #9E9E9E | IDLE | 空闲 |
| #FF9800 | WAITING_ROUTE | 等待路径 |
| #4CAF50 | READY | 就绪 |
| #2196F3 | MOVING | 移动中 |
| #F44336 | BLOCKED | 受阻 |

#### 4.2.4 中央地图区域 (.map-area)

##### 欢迎覆盖层 (`#welcome-overlay`)

包含 `.welcome-box` -> `.welcome-icon` (闪电图标) + h2 标题 + p 提示文字。初始 `display:flex`，仿真开始后隐藏。

##### Canvas 双层渲染 (`#map-stack`)

`#map-stack` 是一个 `position:relative` 的容器，内含两个 Canvas 叠加：

| 层 | Canvas ID | z-index 关系 | 渲染内容 | CSS 属性 | 重绘频率 |
|----|-----------|-------------|---------|----------|---------|
| 地图层 | `#map-canvas` | `display:block` (底层) | 网格背景、已探索区域、障碍物、密封区域、网格线 | `image-rendering: pixelated; background: #D0D0D0` | 每 3 tick 或探索率变化时全量；中间 tick 增量更新 |
| 小车层 | `#car-canvas` | `position:absolute; top:0; left:0;` (上层) | 小车圆形图标 + 编号、规划路线 | `image-rendering: pixelated; pointer-events: none` | 每 tick 全量重绘 |

**5 层渲染顺序**（从底到顶）：

```
1. 网格背景填充          LIGHT.gridBg      #D0D0D0   fillRect 整区域
2. 已探索区域填充        LIGHT.explored    #B0C4DE   按 mapView[row][col] 逐格填充
3. 障碍物填充            LIGHT.obstacleFill #A0A0A0  按 mapBlock 填充，跳过有车的格子
4. 密封区域（任务完成时） LIGHT.sealedFill  #E53935   按 mapSealed 填充（仅在探索率 >= 100% 时）
5. 网格线描边            LIGHT.gridLine    #94A3B8   0.5px 线宽，逐行逐列
6. 小车路线（car-canvas） 半透明彩色线段       每车 routeList 前 10 步
7. 小车图标（car-canvas） 彩色外圆 + 白色内圆 + 编号  每车当前位置
```

**地图层渲染过程** (`renderMapLayer()`，第 408-476 行)：

```
1. clearRect 清空整个 mapCanvas
2. fillStyle = LIGHT.gridBg，fillRect 填充整个网格背景
3. 双层循环遍历 mapView[r][c]，若为 true -> fillRect(c*cs, r*cs, cs, cs) 填充 LIGHT.explored
4. 构建 occupied 集合（有车的位置坐标），遍历 mapBlock -> 跳过 occupied 的格子 -> fillRect 填充 LIGHT.obstacleFill
5. 若 explorationRate >= 100 && mapSealed 存在 -> 遍历 mapSealed -> fillRect 填充 LIGHT.sealedFill
6. 绘制水平+竖直网格线（0.5px, LIGHT.gridLine）
7. syncRenderedMapView() 保存当前探索状态快照供下次增量使用
8. mapLayerDirty = false
```

**小车层渲染过程** (`renderCarsLayer()`，第 479-493 行)：

```
1. clearRect 清空整个 carCanvas
2. 遍历每辆车 -> drawRoute(car, ctx, cs) 绘制路线
3. 遍历每辆车 -> drawCar(car, ctx, cs) 绘制小车
```

**路线绘制** (`drawRoute()`，第 501-531 行)：
- 仅 READY/MOVING/BLOCKED/WAITING_ROUTE 状态绘制
- 颜色 = CAR_COLORS 按 car.number 取模，附加 0x80 alpha（半透明）
- 线宽 = max(2, cs*0.1)
- 寻找起点：从当前位置在 routeList 中查找，从下一个位置开始绘制
- 最多绘制 MAX_ROUTE_DRAW=10 步
- 仅相邻格子（曼哈顿距离 = 1）才连线

**小车绘制** (`drawCar()`，第 547-571 行)：
- 外圆半径 = cs/2 - 1，填充 CAR_COLORS 颜色
- 内圆半径 = max(3, outerR - 2)，填充白色 #FFFFFF
- 居中文字：加粗 Consolas，字号 max(7, floor(cs*0.4))，黑色，显示 car.number

**增量探索更新** (`paintIncrementalExplored()`，第 269-288 行)：
- 维护 `renderedMapView` 二维数组记录已渲染的探索格
- 仅对 mapView[r][c]==true 且 renderedMapView[r][c]==false 的格子执行 fillRect
- 比全量重绘大幅节省性能

**地图层重绘条件** (`shouldRepaintMapLayer()`，第 294-299 行)：
- `mapLayerDirty` 为 true
- tick <= 1
- explorationRate >= 100%
- 距上次全量重绘 >= MAP_RENDER_INTERVAL (3) 个 tick

##### Unity 3D 视图 (`#unity-shell`)

| DOM ID | 元素类型 | 说明 |
|--------|---------|------|
| `#unity-shell` | `<div>` | Unity WebGL 容器，默认 hidden，切换 3D 时显示 |
| `#unity-frame` | `<iframe>` | 加载 unity/index.html 的 iframe，`pointer-events:none`（Unity 不直接接收鼠标） |
| `#unity-input` | `<div>` | 透明输入捕获层，`position:absolute; inset:0; z-index:2`，拦截鼠标/触控事件转发给 Unity 摄像机 |

Unity 3D 相关详见第六章。

#### 4.2.5 右侧栏：车辆状态面板 (`#cars-panel`)

每个小车渲染为一张 `.car-card`：

| CSS 类 | 内容 |
|--------|------|
| `.car-card` | 容器，`background:rgba(248,250,252,0.9); border:1px solid #E2E8F0; border-radius:8px; padding:12px` |
| `.car-card.placeholder` | 初始占位卡，显示 "等待任务启动..." |
| `.car-header` | flex 横排，左侧 `.car-number` (Car N)，右侧 `.car-status-tag` (状态标签) |
| `.car-status-tag` | 圆角背景色标签，背景色 = STATUS_COLORS[car.status]，白色文字 |
| `.car-detail` | 显示位置坐标 "(x, y)"、目标坐标 "(x, y)"、步数 |
| `.steps-bar-wrap` | 步数进度条背景 (`height:4px; background:#E2E8F0`) |
| `.steps-bar-fill` | 步数进度条填充 (`background:#3B82F6; border-radius:2px; transition:width 0.3s`) |

**car-card 数据结构** (`buildCarCard()`，第 586-602 行)：

```
Car N [状态标签]
位置: (x, y)
目标: (x, y) 或 "无"
步数: N
[progress bar]
```

#### 4.2.6 WebSocket 协议

前端通过 WebSocket（`ws://hostname:8888`）与 Display 通信。

**WebSocket 连接管理**：
- `buildWebSocketUrl()` (第 121-124 行)：从 `location.protocol` 推导 ws/wss，拼接 `hostname:8888`
- `connectWebSocket()` (第 126-132 行)：创建 WebSocket 实例，绑定 onopen/onmessage/onclose/onerror
- `onSocketOpen()` (第 134-139 行)：若曾连接过 (`wasEverConnected`)，发送 RESET 重新同步
- `onSocketClose()` (第 339-341 行)：3 秒后自动重连
- `onSocketError()` (第 344 行)：仅 console.error 日志

**下行消息类型**（后端 -> 前端）：

| type 字段 | payload 结构 | 处理函数 | 说明 |
|-----------|-------------|---------|------|
| 无 type（普通 tick） | `{tick, explorationRate, taskConfig, cars, mapView/mapBlock/mapSealed (或 B64 版本), runStartedBy}` | `onSocketMessage` 主分支 | 实时仿真状态推送，驱动 Canvas 渲染 |
| `CAR_PENDING` | `{type, carId}` | `beginAddCarPending(carId)` | 动态小车即将启动，前端按钮显示加载状态 |
| `CAR_LAUNCHED` | `{type, carId}` | `clearAddCarPending(true)` | 动态小车启动成功，按钮显示 "x 已添加" |
| `CAR_LAUNCH_FAILED` | `{type, reason}` | `failAddCarPending(reason)` | 动态小车启动失败，alert 弹窗 |
| `REPLAY_DATA` | `{type, maxTick, mapWidth, mapHeight, carHistories, explorationEvents, mapViewB64?, mapBlock?, mapSealed?, runId?}` | `receiveReplayData(msg)` | 回放数据（当前场次或历史场次） |
| `REPLAY_ERROR` | `{type, error}` | alert 弹窗 | 回放请求失败 |
| `RUN_ARCHIVED` | `{type}` | 静默忽略 | 场次已归档通知 |

**上行消息类型**（前端 -> 后端）：

| type | data 字段 | 发送函数 | 说明 |
|------|----------|---------|------|
| `SET_CONFIG` | `{mapWidth, mapHeight, carCount, obstacleRatio, algorithm, tickInterval, active:"true", operator}` | `beginSimulationStart()` | 开始仿真，先清空本地状态再发送 |
| `RESET` | `{}` | `onResetClick()` | 重置仿真，同时清空本地状态 |
| `TOGGLE_PAUSE` | -- | `onPauseClick()` | 暂停/继续，前端同步按钮文字 |
| `SET_TICK_INTERVAL` | `{interval}` | `onTickIntervalChange()` | 调整节拍间隔，仅在非编辑状态下发送 |
| `ADD_CAR` | -- | `onAddCarClick()` | 动态添加小车，先发送再等待后端 CAR_LAUNCHED/CAR_LAUNCH_FAILED |
| `TOGGLE_OBSTACLE` | `{row, col}` | `onCanvasContextMenu()` | 右键点击 Canvas 格子切换障碍物 |
| `REQUEST_REPLAY` | `{runId?}` | `requestReplayBySelection()` | 请求回放数据，runId 为空=当前场次，有值=历史场次 |

**通用发送函数**：`sendCommand(msg)` (第 853-855 行)，检查 `ws.readyState === WebSocket.OPEN` 后 `ws.send(JSON.stringify(msg))`。

#### 4.2.7 回放系统 (app.js 第 983-1191 行)

**常量**：
- `REPLAY_FRAME_MS = 200` -- 回放帧间隔 (5fps)

**回放状态对象**：

```javascript
var replay = {
  currentTick: 0,    // 当前回放 tick
  maxTick: 0,        // 最大 tick
  playing: false,    // 是否播放中
  timerId: null      // setInterval ID
};
```

**replayData 结构**：

```javascript
{
  maxTick: 1500,
  mapWidth: 30,
  mapHeight: 30,
  runId: 42,       // 历史场次 ID（当前场次时可能缺失）
  carHistories: {   // carId -> [{x,y,tick}, ...] 或 JSON 字符串数组
    "Car001": [{x:0,y:0,tick:0}, {x:1,y:0,tick:10}, ...],
    "Car002": [...]
  },
  explorationEvents: [],  // ["tick,row,col", ...] 或 [{tick,row,col}, ...]
  mapViewB64: "...",      // 最终探索视图的 Base64 位图
  mapBlock: [[...],...],  // 障碍物
  mapSealed: [[...],...], // 密封区域
  // 前端处理后生成的缓存：
  _tickViews: [{/* mapView at tick 0 */}, {/* tick 1 */}, ...],
  _carIndex: { "Car001": [{x,y,tick}, ...], ... },
  _mapBlock: [[...],...],
  _mapSealed: [[...],...]
}
```

**回放入口**（`enterReplay()`，第 1148-1150 行）：
- 调用 `requestReplayBySelection()`

**场次请求与加载**（`requestReplayBySelection()`，第 1018-1054 行）：
- 退出 Unity 3D 视图
- 读取 `#replay-run-select` 的选中值
- "current" -> selectedReplayRunId=null，若已有 replayData 则直接进入回放模式，否则发 WS REQUEST_REPLAY
- 历史 runId -> `fetch('/api/replay/runs/' + selectedReplayRunId)` -> `receiveReplayData(body.data)`

**回放数据接收与预处理**（`receiveReplayData()`，第 1056-1131 行）：

```
1. 设置 replay.maxTick, replay.currentTick=0
2. 根据 mapWidth/mapHeight 计算 CELL_SIZE 并设置 Canvas 尺寸
3. 构建 _carIndex：遍历 carHistories，解析每个 carId 的位置数组 [{x,y,tick}]
4. 构建 _tickViews：遍历 explorationEvents，按 tick 递增构建每个 tick 的探索视图
   - 解析事件格式（逗号分隔字符串或对象）
   - 按 tick 排序
   - 对每个 tick，应用所有该 tick 之前的事件来构建 mapView
5. 若提供 mapViewB64，合并到最终 tick 视图
6. 保存 _mapBlock, _mapSealed
7. 切换到 replay mode
8. 显示回放控件，更新滑块范围，renderReplayFrame()
```

**回放帧渲染**（`renderReplayFrame()`，第 1160-1191 行）：

```
1. 获取当前 tick 的探索视图 _tickViews[tick]
2. 构建 frame 对象（伪 liveData）：
   - mapView: _tickViews[tick]
   - mapBlock: _mapBlock, mapSealed: _mapSealed
   - cars: 从 _carIndex 查询每辆车在当前 tick 的位置
3. 设 liveData=frame，调用 renderMapLayer() + drawCar() 每辆车
4. 更新滑块和标签，重置 liveData=null
```

**回放控制函数**：

| 函数 | 绑定事件 | 功能 |
|------|---------|------|
| `startReplayTimer()` | `#replay-toggle` 点击 (状态为暂停时) | 启动 setInterval(200ms)，设为播放中，按钮变 ⏸ |
| `stopReplayTimer()` | `#replay-toggle` 点击 (状态为播放时) | 清除 setInterval，设为暂停，按钮变 ▶ |
| `replayTickForward()` | setInterval 回调 | `currentTick++`，`renderReplayFrame()`，到达 maxTick 时自动停止 |
| `onReplaySliderInput()` | `#replay-slider` input 事件 | `currentTick = slider.value`，`renderReplayFrame()` |
| `onReplayStepPrev()` | `#replay-step-prev` 点击 | `currentTick = max(0, currentTick - 1)`，`renderReplayFrame()` |
| `onReplayStepNext()` | `#replay-step-next` 点击 | `currentTick = min(maxTick, currentTick + 1)`，`renderReplayFrame()` |
| `exitReplay()` | `#btn-live` 点击 | 退出回放，恢复 live 模式，重绘 Canvas |

**历史场次列表加载**（`loadReplayRunList()`，第 994-1016 行）：
- GET `/api/replay/runs?page=1&size=50`
- 选项格式：`#<id> <algorithm> <explorationRate>% tick<maxTick> (<startedAt>)`
- 支持 `selectRunId` 参数定位选中（保存记录后自动选中新场次）

#### 4.2.8 动态添加小车 UI 反馈 (app.js 第 197-258 行)

**状态对象**：

```javascript
var addCarPending = {
  active: false,       // 是否有待处理的添加请求
  carId: '',           // 目标 carId
  baselineCount: 0,    // 发送请求时的车辆数量
  timerId: null        // 30 秒超时定时器
};
```

**超时常量**：`ADD_CAR_TIMEOUT_MS = 30000` (30 秒)，`ADD_CAR_LABEL_DEFAULT = '+ 添加小车'`

**完整流程**：

```
用户点击 #btn-addcar
  -> onAddCarClick()
    -> 检查 ws 是否连接
    -> $btnAddCar.disabled = true, 文字变为 "请求中..."
    -> sendCommand({type:'ADD_CAR'})

收到 CAR_PENDING
  -> beginAddCarPending(carId)
    -> addCarPending.active = true
    -> 按钮文字变为 "添加 Car00X..."
    -> 启动 30 秒超时定时器

收到 CAR_LAUNCHED
  -> clearAddCarPending(true)
    -> 按钮文字变为 "x 已添加"（1.5 秒后恢复为 "+ 添加小车"）
    -> syncCarCountFromLaunchedCar(carId)
      -> 更新 #cfg-carCount.value

收到 CAR_LAUNCH_FAILED
  -> failAddCarPending(reason)
    -> clearAddCarPending(false)
    -> alert("添加小车失败：" + reason)

30 秒超时
  -> failAddCarPending("超时：请查看 logs/ 下 car-<carId>-*.log")

备选路径：收到 tick 数据时通过 maybeCompleteAddCarPending() 检测
  -> 若 cars.length > baselineCount，自动完成添加
```

**防重复点击**：`onAddCarClick()` 检查 `addCarPending.active` 或 `$btnAddCar.disabled`，防止重复请求。

#### 4.2.9 右键障碍物交互 (app.js 第 1222-1233 行)

`onCanvasContextMenu()` 函数：
- 绑定到 `carCanvas` 的 `contextmenu` 事件
- `e.preventDefault()` 阻止浏览器右键菜单
- 坐标转换：`(e.clientX - rect.left) * scaleX / CELL_SIZE` -> col, row
- 边界检查：row/col 必须在 [0, gridH/gridW) 范围内
- `sendCommand({type: 'TOGGLE_OBSTACLE', data: {row, col}})`
- `mapLayerDirty = true` 触发下次地图层全量重绘

**Canvas 坐标缩放因子**：
```javascript
var scaleX = carCanvas.width / rect.width;   // CSS 显示尺寸 vs 实际像素
var scaleY = carCanvas.height / rect.height;
```

#### 4.2.10 Canvas 尺寸自适应 (app.js 第 368-405 行)

`finalizeCanvas()` 函数：
- 从 `.map-area` 获取可用空间（减去 20px padding）
- 根据 mapWidth/mapHeight 计算适合的 CELL_SIZE：`max(4, min(floor(availW/w), floor(availH/h)))`
- 设置 mapCanvas 和 carCanvas 的 width/height 属性（实际像素尺寸）
- 若 CELL_SIZE 未变化且 Canvas 尺寸相同 -> 跳过重设（优化）
- 设置 `canvasReady = true`，隐藏 welcome overlay

**窗口 resize 事件**（第 1296-1301 行）：
- `canvasReady = false`
- `finalizeCanvas()` 重新计算尺寸
- 若 canvasReady -> `mapLayerDirty = true`, `renderMapLayer()`, `renderCarsLayer()`
- 若 UnityView 处于 3D 模式 -> `UnityView.syncSize()`

#### 4.2.11 控制按钮逻辑

**开始按钮** (`onStartClick()`，第 887-893 行)：
- 检查 WebSocket 连接状态
- `refreshCurrentOperator(beginSimulationStart)` 先获取当前用户身份
- `beginSimulationStart()`：
  - 重置计时器、任务完成标志、画布缓存
  - `liveData` 初始化为 `{tick:0, explorationRate:0, taskConfig: buildTaskConfigFromForm(), cars:[]}`
  - `finalizeCanvas()` + 初始渲染
  - 发送 `{type:'SET_CONFIG', data: liveData.taskConfig}`
  - 禁用开始按钮，启用暂停按钮

**暂停按钮** (`onPauseClick()`，第 895-899 行)：
- 发送 `{type:'TOGGLE_PAUSE'}`
- 切换按钮文字："暂停" <-> "继续"

**重置按钮** (`onResetClick()`，第 901-926 行)：
- 发送 `{type:'RESET'}`
- 清除所有缓存、计时器、回放状态
- 恢复 carCount 输入框为初始值
- `resetCanvas()`：隐藏 Canvas，显示 welcome overlay，清空面板
- Unity 3D 重置（若存在）

**添加小车按钮** (`onAddCarClick()`，第 943-952 行)：
- 检查 WebSocket 连接
- 禁用按钮（防重复）
- 发送 `{type:'ADD_CAR'}`

**按钮文字同步** (`syncControlButtons()`，第 630-646 行)：
- 仿真进行中 -> 开始按钮 disabled，暂停按钮可选
- 仿真完成 -> 开始按钮可选，暂停按钮 disabled
- 未开始 -> 开始按钮可选，暂停按钮 disabled

#### 4.2.12 任务完成弹窗 (app.js 第 656-819 行)

**检测逻辑** (`isSimulationComplete()`，第 617-620 行)：
- `taskConfig.active === 'false'` 或 `explorationRate >= 100`

**弹窗触发** (`showSavePopup()`，第 731-819 行)：
- 创建半透明 overlay (z-index: 9999)
- 显示 "探索完成！探索率: X% | 耗时: Xs | 是否保存？"
- 两个按钮：保存 / 不保存

**保存按钮逻辑**：
- 从 liveData 收集每辆车的 steps、effectiveSteps、status
- 构建记录对象：
  ```javascript
  {
    explorationRate, tick, duration,
    totalSteps, totalEffectiveSteps,
    efficiencyPercent: Math.round(effective/steps*100),
    wastedSteps: steps - effective,
    carCount, algorithm, obstacleRatio, mapWidth, mapHeight,
    balanceScore: computeBalanceScore(cars),
    cars: [{carId, steps, effectiveSteps, status}, ...],
    timestamp: Date.now(), date: new Date().toLocaleString()
  }
  ```
- POST `/api/analysis/records` 保存
- 成功后 reloadReplayRunList(lastSavedRunId)
- alert 显示保存结果

**均衡指数计算** (`computeBalanceScore()`，第 822-839 行)：
- `balanceScore = max(0, 1 - std(effectiveSteps) / avg(effectiveSteps))`
- 越接近 1 表示各车贡献越均匀
- 单辆车返回 1

**不保存按钮逻辑**：
- POST `/api/analysis/discard`

**操作者判断** (`isCurrentRunOperator()`，第 684-694 行)：
- 优先比对 `runStartedBy` 与 `currentOperator`
- 次优比对 `localRunStarter` 与 `runStartedBy`
- 非操作者不弹出保存窗口（观看模式）

#### 4.2.13 计时器 (app.js 第 841-850 行)

- `startElapsedTimer()`：每秒更新 `#info-elapsed` 显示 "xx:xx" 格式
- `stopElapsedTimer()`：清除计时器
- 触发条件：`data.tick >= 1 && !startTimestamp`
- 任务完成时自动停止

#### 4.2.14 密码修改弹窗 (app.js 第 1239-1277 行)

| DOM ID | 元素类型 | 说明 |
|--------|---------|------|
| `#changepw-modal` | `<div>` | 固定在视口中央的半透明弹窗 |
| `#cpw-old` | `<input type="password">` | 旧密码 |
| `#cpw-new` | `<input type="password">` | 新密码（至少 3 位） |
| `#cpw-confirm` | `<input type="password">` | 确认新密码（至少 6 位，index.html 中由 auth.js renderNavBar 使用） |
| `#cpw-err` | `<p>` | 错误提示 |
| `#cpw-submit` | `<button>` | 确认修改，POST `/api/auth/change-password` |
| `#cpw-cancel` | `<button>` | 取消 |

index.html 中自带简化版密码弹窗（仅 old + new，至少 3 位）。auth.js 中的 `renderNavBar()` 会重新绑定更完整的密码弹窗逻辑（含确认密码，至少 6 位）。

#### 4.2.15 权限控制 (auth.js `applyPermissions()`)

```javascript
// analyst 角色
- 隐藏控制按钮：btn-start, btn-pause, btn-reset, btn-addcar
- 禁用配置控件：cfg-obstacleRatio, cfg-algorithm, cfg-tickInterval

// simulator 角色
- 隐藏导航链接：analysis.html

// admin 角色
- 无限制
```

#### 4.2.16 10 分钟心跳 (app.js 第 1310-1315 行)

```javascript
setInterval(function () {
  var token = localStorage.getItem('auth_token');
  if (token) {
    fetch('/api/auth/me', { headers: { 'Authorization': 'Bearer ' + token } });
  }
}, 600000); // 10 分钟
```

#### 4.2.17 Base64 位图解码 (app.js 第 322-337 行)

`decodeBitmapB64(b64, width, height)` 函数：
- `atob(b64)` 解码 Base64
- 逐行逐列按 bit 读取：`offset = row*width + col`, `byteIdx = floor(offset/8)`, `bitIdx = 7 - (offset%8)`
- `(byteVal >> bitIdx) & 1 === 1` 判断该位是否为 true

#### 4.2.18 辅助函数

| 函数 | 所在行 | 功能 |
|------|--------|------|
| `getGridW(data)` | 1235 | 从 taskConfig.mapWidth 获取地图宽度，默认 30 |
| `getGridH(data)` | 1236 | 从 taskConfig.mapHeight 获取地图高度，默认 30 |
| `createEmptyView(w, h)` | 1134 | 创建全 false 的 w*h 二维数组 |
| `cloneView(v)` | 1135 | 深拷贝二维 boolean 数组 |
| `mergeMapViews(eventView, finalView)` | 1136-1146 | 合并两个二维 boolean 数组（OR 操作） |
| `normalizeMapPayload(data)` | 301-320 | 将 Base64 位图数据解码为标准 boolean[][]，缓存 mapBlock/mapSealed |
| `authHeaders()` | 983-986 | 构建带 Bearer token 的请求头 |
| `formatRunLabel(run)` | 988-992 | 格式化历史场次选项：`#<id> <算法> <探索率>% tick<tick> (<时间>)` |
| `resolveRunStarter(data)` | 648-654 | 解析场次发起人：data.runStartedBy -> lastKnownRunStartedBy -> taskConfig.operator |
| `refreshCurrentOperator(done)` | 696-707 | 从 Auth.getCurrentUser() 获取当前操作者身份 |

#### 4.2.19 index.html 初始化流程 (app.js 第 1284-1317 行)

```
1. resetCanvas() -- 显示欢迎界面
2. Auth.checkAuth() -- 检查认证状态
   -> currentOperator, userRole 赋值
   -> Auth.renderNavBar(user) -- 渲染顶栏用户信息和退出/改密按钮
   -> Auth.applyPermissions(user.role) -- 根据角色隐藏/禁用控件
   -> loadReplayRunList() -- 加载历史场次下拉
3. window resize 事件绑定
4. UnityView.init(hooks) -- 注入 Unity 桥接数据回调
5. 10 分钟心跳 setInterval
6. connectWebSocket() -- 建立 WebSocket 连接
```

### 4.3 登录/注册页面 (login.html)

**文件**：`login.html` (132 行)，嵌入内联 `<script>`（第 64-127 行）

#### 4.3.1 HTML 结构

| DOM ID / 类 | 元素类型 | 说明 |
|-------------|---------|------|
| `.login-page` | `<body>` class | 全屏居中布局 |
| `.login-container` | `<div>` | 登录容器，`width:380px; z-index:1` |
| `.login-card` | `<div>` | 半透明毛玻璃卡片，`background:rgba(255,255,255,0.92); border-radius:14px; padding:36px 32px` |
| `#login-username` | `<input type="text">` | 用户名字段，autocomplete="username" |
| `#login-password` | `<input type="password">` | 密码字段，autocomplete="current-password" |
| `#cpw-row` | `<div>` | 注册模式下的确认密码行，默认 `display:none` |
| `#reg-confirm` | `<input type="password">` | 确认密码字段 |
| `#login-mode` | `<div>` | 登录模式区域，含登录按钮 |
| `#login-submit` | `<button>` | "登 录" 按钮，`.btn.primary`，`width:100%` |
| `#register-mode` | `<div>` | 注册模式区域，默认 `display:none` |
| `#reg-display` | `<input type="text">` | 注册显示名称，placeholder "如:张三" |
| `#reg-role` | `<select>` | 角色选择：simulator(仿真员)/analyst(统计分析员) |
| `#register-submit` | `<button>` | "注 册" 按钮，`.btn.info`，`width:100%` |
| `#login-error` | `<p>` | 错误信息显示区域，`.login-error`，红色文字 |
| `.login-footer` | `<p>` | 底部切换链接 |
| `#toggle-link` | `<span>` | 登录/注册模式切换，"没有账号？点击注册" / "已有账号？点击登录" |
| `.spinner` | `<span>` | 加载旋转动画，CSS `@keyframes spin` 实现 |

#### 4.3.2 内联 JS 逻辑

**模式切换** (`$toggle` 点击)：
- 切换 `isReg` 布尔值
- 切换 `#login-mode` / `#register-mode` 的 display
- 切换 `#cpw-row` 的 display（注册时显示确认密码）
- 切换链接文字

**登录流程** (`$btn` 点击)：
```
1. 表单验证：用户名 + 密码非空
2. 按钮禁用 + 显示加载动画（旋转 spinner）
3. await Auth.login(username, password)
   -> POST /api/auth/login
   -> 成功：Auth.onLoginSuccess(token) 设置登录序列号 + token
   -> window.location.href = '/dashboard.html'
4. 失败：显示错误信息
5. finally：恢复按钮状态
```

密码输入框支持 Enter 键触发登录（keydown 事件监听）。

**注册流程** (`$regBtn` 点击)：
```
1. 表单验证：
   - 所有字段（用户名、密码、确认密码、显示名称）必填
   - 密码至少 6 位
   - 两次密码一致
2. 按钮禁用 + 加载动画
3. POST /api/auth/register
   body: {username, password, displayName, role}
4. 成功：alert "操作成功" + 自动切换回登录模式
5. 失败：显示错误信息
```

#### 4.3.3 视觉效果

- 背景粒子动画（`particles.js` 在页面底部加载）
- 登录卡片半透明毛玻璃效果（`rgba(255,255,255,0.92)`）
- 密码字段最小高度 50px

#### 4.3.4 脚本加载顺序

```
1. auth.js?v=11    -- 认证模块
2. 内联 <script>   -- 登录/注册逻辑
3. particles.js    -- 背景粒子动画
```

### 4.4 仪表盘 (dashboard.html)

**文件**：`dashboard.html` (143 行)，嵌入内联 `<style>` + 内联 `<script>`

#### 4.4.1 HTML 结构

| DOM ID / 类 | 元素类型 | 说明 |
|-------------|---------|------|
| `.dashboard-page` | `<body>` class | 全屏居中 flex 布局 |
| `.dashboard-card` | `<div>` | 白色圆角卡片，`max-width:480px; border-radius:16px; padding:48px 56px` |
| `.dash-icon` | `<div>` | 闪电图标，`font-size:56px` |
| `.dash-title` | `<div>` | 标题 "变电站巡检仿真系统" |
| `#dash-role` | `<div>` | 用户角色标签，蓝色背景胶囊 |
| `#dash-btns` | `<div>` | 导航按钮组，`flex-direction:column; gap:12px` |
| `#dash-cpw` | `<button>` | "修改密码" 按钮 |
| `#dash-logout` | `<button>` | "退出登录" 按钮 |

#### 4.4.2 修改密码弹窗 (`#cpw-modal`)

| DOM ID | 元素类型 | 说明 |
|--------|---------|------|
| `#cpw-modal` | `<div>` | 全屏半透明遮罩弹窗 |
| `#cpw-old` | `<input type="password">` | 旧密码 |
| `#cpw-new` | `<input type="password">` | 新密码（至少 6 位） |
| `#cpw-confirm` | `<input type="password">` | 确认新密码 |
| `#cpw-err` | `<p>` | 错误提示 |
| `#cpw-submit` | `<button>` | 确认修改 |
| `#cpw-cancel` | `<button>` | 取消 |

#### 4.4.3 导航按钮类

| CSS 类 | 颜色 | 目标页面 | 显示角色 |
|--------|------|---------|---------|
| `.dash-btn.sim` | 蓝色 (#3B82F6) | index.html (仿真控制) | admin, simulator |
| `.dash-btn.analysis` | 绿色 (#10B981) | analysis.html (统计分析) | admin, analyst |
| `.dash-btn.manage` | 紫色 (#8B5CF6) | user-management.html (用户管理) | admin |

#### 4.4.4 初始化流程 (内联 JS，第 84-137 行)

```
1. Auth.checkAuth() 验证登录态
2. 获取 user 对象 -> 填充角色标签
3. 根据 user.role 构建导航按钮：
   - admin: 仿真控制 + 统计分析 + 用户管理
   - simulator: 仅仿真控制
   - analyst: 仅统计分析
4. 绑定修改密码弹窗事件
5. 绑定退出登录事件 -> Auth.logout()
```

### 4.5 统计分析页面 (analysis.html + analysis.js)

**文件**：`analysis.html` (88 行) + `analysis.js` (655 行)

#### 4.5.1 analysis.html HTML 结构

| DOM ID / 类 | 元素类型 | 说明 |
|-------------|---------|------|
| `header` | `<header>` | 顶栏：标题 "统计分析" + 用户信息 + 返回链接 |
| `.main-container` | `<div>` | 主容器 |
| `.a-wrap` | `<div>` | 滚动内容区，`flex:1; overflow-y:auto; padding:20px` |
| `#list-toolbar` | `<div>` (.list-toolbar) | 工具栏区域，默认 hidden |
| `#import-file` | `<input type="file" hidden>` | 隐藏的文件选择器，accept ".json" |
| `#list-wrap` | `<div>` | 记录列表/卡片容器 |
| `#fsd` | `<div>` (.fsd) | 全屏详情面板，默认 `display:none`，`.fsd.active` 时显示 |
| `#fsd-header` | `<div>` | 详情面板顶栏 |
| `#fsd-title` | `<h3>` | 详情标题 |
| `#fsd-close` | `<button>` (.fsd-close) | 关闭详情按钮 |
| `#fsd-body` | `<div>` | 详情内容区域 |
| `#compare-modal` | `<div>` (.compare-modal) | 对比弹窗，默认 `display:none`，`.compare-modal.active` 时显示为 flex |
| `#compare-panel` | `<div>` (.compare-panel) | 对比弹窗内容面板 |

#### 4.5.2 analysis.js 完整功能

**常量定义**：

| 常量 | 值 | 说明 |
|------|-----|------|
| `MAX_STORED_RECORDS` | 50 | 单页加载最大记录数 |
| `EXPORT_VERSION` | 1 | 导出 JSON 版本号 |
| `EFF_GREEN` | `#10B981` | 有效率绿色 |
| `WASTED_ORANGE` | `#F97316` | 无效步数橙色 |
| `MAX_COMPARE` | 5 | 最大对比记录数 |

**全局状态**：

| 变量 | 类型 | 说明 |
|------|------|------|
| `records` | Array | 从服务器加载的仿真记录列表 |
| `filterAlgo` | String | 当前算法筛选值（"" 表示全部） |
| `selectMode` | Boolean | 是否处于选择对比模式 |
| `selected` | Object | 已选中的记录索引字典 `{index: true}` |
| `loadFailed` | Boolean | 记录加载是否失败 |

**函数清单**：

| 函数 | 行 | 功能 |
|------|-----|------|
| `authHeaders()` | 21-26 | 构建带 Authorization header 的请求头 |
| `init()` | 28-45 | 入口：checkAuth -> 获取 DOM 引用 -> 绑定事件 -> loadList() |
| `loadRecords()` | 47-63 | GET `/api/analysis/records?page=1&size=50` 加载记录 |
| `fmtDate(ts)` | 66-70 | 时间戳转 "YYYY-M-D H:MM:SS" 格式 |
| `getEfficiency(r)` | 73-79 | 获取有效率（优先 efficiencyPercent，否则 totalEffective/totalSteps） |
| `getWasted(r)` | 81-85 | 获取无效步数（优先 wastedSteps，否则 totalSteps - effectiveSteps） |
| `effColor(pct)` | 87-92 | 有效率颜色分级：null/#94A3B8, >60/#10B981, >=40/#F59E0B, <40/#EF4444 |
| `recordPassesFilter(r)` | 94-97 | 检查记录是否符合 filterAlgo 筛选 |
| `filteredIndices()` | 99-105 | 返回所有通过筛选的记录索引 |
| `selectedCount()` | 107-111 | 返回已选中的记录数量 |
| `recordLabel(r, index)` | 113-116 | 格式化记录标签："场次 #N" |
| `recordRunId(r)` | 118-120 | 提取 runId（优先 r.runId，否则 r.id） |
| `loadList()` | 122-139 | 加载记录 -> 渲染工具栏 + 卡片列表，处理空列表/加载失败 |
| `saveRecordToServer(rec)` | 141-152 | POST `/api/analysis/records` 保存单条记录 |
| `deleteRecordOnServer(runId)` | 154-163 | DELETE `/api/analysis/records/:runId` 删除记录 |
| `recordKey(r)` | 165-170 | 生成记录唯一键（优先 runId，其次 timestamp，最后 date+steps） |
| `isValidRecord(item)` | 172-176 | 验证导入数据是否有效 |
| `parseImportPayload(text)` | 178-183 | 解析 JSON 导入数据 |
| `mergeImported(incoming)` | 185-201 | 去重：按 recordKey 对比现有记录，跳过重复的 |
| `exportRecordsJson()` | 203-217 | 导出所有记录为 JSON 文件下载，文件名 `sim_records_<timestamp>.json` |
| `triggerImport()` | 219-222 | 触发文件选择器 |
| `handleImportFileChange()` | 224-245 | 读取文件 -> parse -> merge -> 逐条 saveRecordToServer -> loadList() |
| `pruneSelection()` | 247-256 | 清除与当前筛选不匹配或越界的选中项 |
| `renderToolbar()` | 258-301 | 渲染工具栏（汇总统计 + 算法筛选 + 选择对比 + 导入导出） |
| `renderToolbar()` 内部元素 | -- | `#algo-filter` (select), `#btn-select-mode` (button), `#btn-compare` (button), `#btn-export-json` (button), `#btn-import-json` (button) |
| `toggleSelectMode()` | 303-307 | 切换选择对比模式 |
| `collectAlgorithms()` | 309-316 | 收集所有记录中的算法类型 |
| `computeSummary(indices)` | 318-335 | 计算平均/最佳/最低有效率 |
| `renderCardGrid()` | 337-372 | 渲染记录卡片网格 (`.a-grid` > `.a-card` x N) |
| `bindCardEvents()` | 374-399 | 绑定卡片点击、删除、复选框事件 |
| `toggleSelect(idx, card)` | 402-418 | 切换单条记录选中状态，最多 MAX_COMPARE=5 条 |
| `updateCompareButton()` | 420-425 | 更新对比按钮的文字和 disabled 状态 |
| `openCompareModal()` | 427-462 | 打开对比弹窗，渲染对比表格 |
| `closeCompareModal()` | 464 | 关闭对比弹窗 |
| `delRecord(i)` | 466-483 | 删除记录（先确认，然后 DELETE 到服务器） |
| `closeDetail()` | 486 | 关闭全屏详情面板 |
| `showDetail(i)` | 488-523 | 打开全屏详情：KPI 卡片 + 信息行 + 均衡条 + 图表容器 |
| `kpi(title, value, color)` | 525-527 | 生成 KPI 卡片 HTML |
| `buildBalanceBarsHtml(carData, balancePct)` | 529-543 | 生成各车有效步数均衡条 HTML |
| `getCarData(r)` | 546-569 | 从记录提取车辆数据（labels, effective, wasted） |
| `renderDetailCharts(r, carData)` | 571-575 | 渲染详情页两个图表（环形图 + 堆叠柱状图） |
| `donutC(id, title, effective, wasted)` | 577-607 | Canvas 绘制步数有效率环形图 |
| `stackedBarC(id, title, labels, effectiveVals, wastedVals)` | 609-644 | Canvas 绘制各车有效/无效步数堆叠柱状图 |
| `ensureCanvas(parent, w, h)` | 646-652 | 确保容器内有指定尺寸的 Canvas 元素 |

**记录卡片 (.a-card) 结构**：

```
┌──────────────────────────────┐
│ [复选框] (选择模式)      [x]  │
│ 仿真记录 场次 #N             │
│ 日期 · 保存者                │
│       有效率%                 │  <- .a-rate (36px 大号字体)
│      步数有效率               │  <- .a-rate-label
│  探索覆盖率 X%               │  <- .a-coverage
│ N车 · Xs · 算法 · 障碍 X%    │
└──────────────────────────────┘
```

**全屏详情面板 (#fsd) 结构**：

```
KPI 卡片行 (6 个 .kcard)：
  [步数有效率] [探索覆盖率] [总步数] [有效步数] [无效步数] [耗时]

信息行：
  场次: #N | 节拍: N | 车辆: N | 算法: XXX | 障碍率: X%

均衡条 (.balance-box)：
  Car001 [████████░░░░] N
  Car002 [██████████░░] N
  Car003 [██████░░░░░░] N
  均衡指数 X%（越接近 100% 表示各车贡献越均匀）

图表行 (2 个 .cbox, 各 320px 高)：
  [#d1 环形图]  [#d2 堆叠柱状图]
```

**环形图** (`donutC()`)：
- 外圆半径 = min(w,h)/2 - 34
- 内圆半径 = 外圆 * 0.62
- 背景环：#F1F5F9
- 有效步数弧：按有效率颜色填充，从 -PI/2 开始顺时针
- 中心大字：有效率百分比 + "步数有效率" 标签

**堆叠柱状图** (`stackedBarC()`)：
- 每根柱子分两段：下方绿色（有效步数）+ 上方橙色（无效步数）
- 柱宽 = min(40, pw/n*0.55)
- 柱顶显示总步数，底部显示车标签

**对比弹窗表格**：
- 行指标：步数有效率、探索覆盖率、总步数、有效步数、耗时、节拍 tick、算法、障碍率、均衡指数
- 列：选中的记录（2-5 列）

**工具栏汇总统计**：
- 总记录数
- 平均步数有效率
- 最佳记录（#N 有效率%）
- 最低记录（#N 有效率%）

### 4.6 用户管理页面 (user-management.html)

**文件**：`user-management.html` (377 行)，嵌入内联 `<style>` + `<script>`

#### 4.6.1 HTML 结构

**顶栏** (header)：
- 标题 "用户管理"
- `#info-user` 当前用户
- "返回" 链接到 dashboard.html

**Tab 切换** (.tabs)：

| DOM ID | 说明 |
|--------|------|
| `#tab-users` | "用户列表" Tab，默认 active |
| `#tab-registrations` | "注册审核" Tab，含红点计数 `#pending-count` |
| `#pending-count` | 待审核数量红点徽章，默认 `display:none` |

**用户列表面板** (`#panel-users`)：

| DOM ID | 元素类型 | 说明 |
|--------|---------|------|
| `#search-users` | `<input type="text">` | 搜索用户名或显示名称 |
| `#filter-role` | `<select>` | 角色筛选：全部/仿真员/统计分析员 |
| `.btn.btn-primary` (搜索) | `<button>` | 触发 `loadUsers()` |
| `#users-table-wrap` | `<div>` (.card) | 用户表格容器 |
| `#users-pagination` | `<div>` (.pagination) | 用户分页控件 |

**注册审核面板** (`#panel-registrations`，默认 `display:none`)：

| DOM ID | 元素类型 | 说明 |
|--------|---------|------|
| `#filter-reg-status` | `<select>` | 状态筛选：待审核/已通过/已拒绝 |
| `#reg-table-wrap` | `<div>` (.card) | 注册审核表格容器 |
| `#reg-pagination` | `<div>` (.pagination) | 分页控件 |

**弹窗**：

| DOM ID | 说明 |
|--------|------|
| `#reset-modal` | 重置密码确认弹窗 |
| `#reset-target-name` | 显示被重置用户名的 `<strong>` |
| `#btn-confirm-reset` | 确认重置按钮，POST `/api/admin/users/:username/reset-password` |
| `#log-modal` | 操作日志查看弹窗，`width:700px; max-height:80vh` |
| `#log-target-name` | 显示被查看用户的 `<span>` |
| `#log-table-wrap` | 日志表格容器 |
| `#toast` | Toast 提示浮层，`.toast.success` (绿) / `.toast.error` (红)，2.5 秒自动消失 |

#### 4.6.2 内联 JS 函数清单

| 函数 | 行 | 功能 |
|------|-----|------|
| `authHeaders()` | 147-150 | 构建带 Authorization header 的请求头 |
| `showToast(msg, type)` | 156-159 | 显示 Toast 提示，2.5 秒自动隐藏 |
| `showResetModal(username)` | 162-165 | 显示重置密码确认弹窗 |
| `closeModal()` | 167-170 | 关闭重置密码弹窗 |
| `showLogModal(username)` | 173-199 | 加载并显示用户操作日志（GET `/api/admin/logs?username=&size=100`） |
| `showTab(tab)` | 201-208 | 切换用户列表/注册审核 Tab |
| `loadUsers(page)` | 211-231 | 加载用户列表（GET `/api/admin/users?page=&size=20&search=&role=`）-> 渲染表格 + 分页 |
| `renderUsersTable(users)` | 233-248 | 渲染用户表格（用户名可点击查看日志，操作列有日志+重置密码按钮） |
| `loadRegistrations(page)` | 251-273 | 加载注册审核列表（GET `/api/admin/registrations?page=&size=20&status=`）-> 渲染 + 更新待审核计数 |
| `renderRegTable(list, status)` | 275-296 | 渲染注册审核表格（待审核项显示通过/拒绝按钮） |
| `approve(id)` | 298-303 | 通过注册申请（POST `/api/admin/registrations/:id/approve`） |
| `reject(id)` | 305-309 | 拒绝注册申请（POST `/api/admin/registrations/:id/reject`） |
| `renderPagination(id, page, totalPages, fn)` | 361-370 | 渲染分页控件（上一页/下一页 + 页码按钮）：第 322 行有第一个定义（功能不完整），第 361 行有第二个定义（完整） |
| `esc(s)` | 333-336 | HTML 转义函数（`& < > "`） |
| `init()` | 339-358 | 入口：checkAuth -> 权限检查（非 admin 跳转仪表盘）-> renderNavBar -> loadUsers() -> 加载待审核计数 |

**用户表格列**：用户名（可点击查看日志）、角色、显示名称、状态（正常/已禁用 badge）、注册时间、操作（日志按钮 + 重置密码按钮）

**注册审核表格列**：用户名、角色、显示名称、状态（待审核/已通过/已拒绝 badge）、审核人、申请时间、操作（待审核：通过/拒绝按钮；已审核：审核时间）

**操作日志表格列**：操作类型（LOGIN/LOGOUT/REGISTER/REGISTER_DUPLICATE/APPROVE_REGISTRATION/REJECT_REGISTRATION/RESET_PASSWORD/CHANGE_PASSWORD）、操作对象、详情、时间

**状态徽章 (badge)**：

| CSS 类 | 颜色 | 对应状态 |
|--------|------|---------|
| `.badge-pending` | 黄底棕字 | 待审核 |
| `.badge-active` | 绿底深绿字 | 正常/已通过 |
| `.badge-rejected` | 红底深红字 | 已拒绝 |
| `.badge-disabled` | 灰底灰字 | 已禁用 |

**分页组件**：
- 最大显示 20 页按钮
- 上一页按钮：`<` (page > 1 时可用)
- 下一页按钮：`>` (page < totalPages 时可用)
- 当前页按钮加 `.active` 类（蓝底白字）

---

## 五、认证系统 (auth.js)

**文件**：`display/src/main/resources/web/js/auth.js` (414 行)

### 5.1 全局导出

```javascript
window.Auth = {
  login, logout, register, changePassword,
  checkAuth, getCurrentUser, getToken,
  onLoginSuccess, showKickMessageIfAny,
  applyPermissions, renderNavBar
};
```

### 5.2 常量定义

| 常量 | 值 | 用途 |
|------|-----|------|
| `TOKEN_KEY` | `'auth_token'` | localStorage 键：JWT token |
| `LEADER_KEY` | `'auth_active_tab'` | localStorage 键：活跃 Tab 心跳信息 |
| `LOGIN_SEQ_KEY` | `'auth_login_seq'` | localStorage + sessionStorage 键：登录序列号 |
| `KICK_MSG_KEY` | `'auth_kick_message'` | sessionStorage 键：被踢出消息 |
| `FRESH_LOGIN_KEY` | `'auth_fresh_login'` | sessionStorage 键：新登录标记 |
| `LOGIN_URL` | `'/login.html'` | 登录页路径 |
| `SESSION_POLL_MS` | `3000` | 会话轮询间隔（3 秒） |
| `TAB_HEARTBEAT_MS` | `2000` | Tab 心跳间隔（2 秒） |
| `TAB_STALE_MS` | `5000` | Tab 过期时间（5 秒） |
| `KICKED_MSG` | `'您的账号已在其他设备或窗口登录...'` | 被踢出默认消息 |
| `OTHER_TAB_MSG` | `'当前账号已在其他窗口打开...'` | 多 Tab 冲突消息 |

### 5.3 Tab 标识

- 生成唯一 `tabId`：`'tab_' + Date.now() + '_' + random`
- 存储在 `sessionStorage['auth_tab_id']` 中
- 每次页面加载时若不存在则生成新的

### 5.4 单用户单会话机制

**三种踢出场景**：

| 场景 | 检测方式 | 触发函数 | 消息 |
|------|---------|---------|------|
| 服务端主动踢出 | `/api/auth/me` 返回 401 + `kicked` 字段 | `handleServerKicked()` | KICKED_MSG |
| 新登录挤掉旧会话 | `auth_login_seq` (localStorage) 被更新，与 sessionStorage 不符 | `handleDisplacedByNewLogin()` | KICKED_MSG |
| 同浏览器多 Tab | `auth_active_tab` 心跳过期（超过 TAB_STALE_MS 未更新），且 leader.tabId !== 本 tabId | `handleOtherTabTaken()` | OTHER_TAB_MSG |

**存储键使用矩阵**：

| 键 | localStorage | sessionStorage | 用途 |
|----|-------------|---------------|------|
| `auth_token` | 存储 | -- | JWT token（跨 Tab 共享） |
| `auth_login_seq` | 存储（全局） | 存储（本 Tab） | 新登录覆盖全局值，旧 Tab 通过比对发现被替换 |
| `auth_active_tab` | 存储（全局） | -- | 当前活跃 Tab 的 tabId + 时间戳 |
| `auth_kick_message` | -- | 存储 | 被踢消息（login 页读取展示） |
| `auth_fresh_login` | -- | 存储 | 标记本 Tab 刚登录，跳过 Tab 互斥检查 |
| `auth_tab_id` | -- | 存储 | 本 Tab 唯一 ID |

### 5.5 Tab 心跳与轮询

**心跳定时器**（每 2 秒）：
1. 检查 token 是否存在，login 页跳过
2. `isStaleTab()` 检查 -> 被新登录挤掉
3. `isOtherTabActive()` 检查 -> 同浏览器多 Tab
4. 否则 `claimTabLeadership()` 更新心跳

**会话轮询**（每 3 秒）：
1. `pollSession()` -> GET `/api/auth/me` 验证 token 有效性
2. 401 + kicked -> 服务端踢出

**storage 事件监听**：
- `LOGIN_SEQ_KEY` 或 `TOKEN_KEY` 变化 -> 检查 `isStaleTab()`
- `LEADER_KEY` 变化 -> 检查 `isOtherTabActive()`

### 5.6 核心函数

| 函数 | 行 | 功能 |
|------|-----|------|
| `getToken()` | 31-33 | 从 localStorage 读取 token |
| `setToken(token)` | 35-37 | 写入 localStorage |
| `clearToken()` | 39-41 | 清除 localStorage token |
| `getGlobalLoginSeq()` | 43-45 | 读取全局登录序列号 |
| `getTabLoginSeq()` | 47-49 | 读取本 Tab 登录序列号 |
| `isStaleTab()` | 51-55 | 全局 seq !== 本 Tab seq（被新登录替换） |
| `readLeader()` | 57-63 | 读取活跃 Tab 信息（JSON.parse） |
| `claimTabLeadership()` | 65-71 | 写入本 Tab 的 {tabId, ts, seq} |
| `isOtherTabActive()` | 73-85 | 其他 Tab 在活跃（leader.tabId !== tabId && !stale） |
| `storeKickMessage(message)` | 87-91 | 存储被踢消息到 sessionStorage |
| `showKickMessageIfAny()` | 93-100 | 读取并清除被踢消息，alert 显示 |
| `redirectToLogin(message, wipeSharedSession)` | 103-119 | 跳转 login 页，可选清除共享 session |
| `handleServerKicked(message)` | 121-123 | 服务端踢出：清除共享 session + 跳转 |
| `handleDisplacedByNewLogin(message)` | 125-127 | 被新登录挤出：保留共享 session + 跳转 |
| `handleOtherTabTaken(message)` | 129-131 | 多 Tab 冲突：保留共享 session + 跳转 |
| `parseAuthResponse(resp)` | 133-139 | 安全解析 fetch response JSON |
| `apiCall(method, path, body)` | 141-162 | 通用 API 调用，自动附加 token，401 处理 |
| `login(username, password)` | 164-175 | POST `/api/auth/login` -> 成功调 `onLoginSuccess()` |
| `onLoginSuccess(token)` | 177-185 | 设置登录序列号、存储 token、claim 心跳 |
| `logout()` | 187-195 | POST `/api/auth/logout` -> 清除所有 session -> 跳转 login |
| `register(username, password, displayName, role)` | 197-204 | POST `/api/auth/register` |
| `changePassword(oldPw, newPw)` | 206-211 | POST `/api/auth/change-password` |
| `getCurrentUser()` | 213-215 | GET `/api/auth/me` |
| `shouldBlockThisTab()` | 217-224 | 判断本 Tab 是否应被阻止（非新登录 && (stale || otherActive)） |
| `pollSession()` | 227-250 | 会话轮询 |
| `startSessionWatch()` | 252-284 | 启动心跳 + 轮询 + storage 监听 |
| `checkAuth()` | 286-319 | 入口认证检查：login 页 -> 检查是否已登录；其他页 -> 验证 token -> 启动 session 监控 |
| `applyPermissions(role)` | 323-347 | 根据角色隐藏/禁用 UI 控件 |
| `renderNavBar(user)` | 349-399 | 渲染顶栏用户信息 + 绑定修改密码/退出事件 |

### 5.7 renderNavBar 密码修改弹窗逻辑

```
1. 获取 #cpw-old, #cpw-new, #cpw-confirm, #cpw-err 元素
2. 表单验证：
   - 所有字段必填
   - 新密码至少 6 位
   - 两次新密码一致
3. POST /api/auth/change-password
   { oldPassword, newPassword }
4. 成功：alert "密码修改成功" + 关闭弹窗
5. 失败：显示错误信息
```

### 5.8 角色名称映射

```javascript
var ROLE_NAMES = {
  admin: '管理员',
  simulator: '仿真员',
  analyst: '统计分析员'
};
```

---

## 六、Unity 3D 集成 (unity-view.js)

**文件**：`display/src/main/resources/web/js/unity-view.js` (245 行)

### 6.1 架构

```
┌──────────────────────────────────────────────┐
│              index.html                       │
│  ┌──────────────────────────────────────┐     │
│  │  unity-view.js (桥接层)               │     │
│  │  . init(hooks)  注入数据访问回调       │     │
│  │  . toggleView() 2D/3D 切换           │     │
│  │  . syncSize()   尺寸同步              │     │
│  │  . exit()       退出 3D              │     │
│  │  . resetForNewSimulation()           │     │
│  │  . onCanvasReady()                   │     │
│  └──────────────┬───────────────────────┘     │
│                 │ postMessage / SendMessage    │
│  ┌──────────────▼───────────────────────┐     │
│  │  iframe: unity/index.html             │     │
│  │  . Unity WebGL 构建 (Substation3D)    │     │
│  │  . GameController 对象                │     │
│  │  . OnWebPan / OnWebOrbit / OnWebZoom  │     │
│  │  . ws-redirect.js: WebSocket 地址重写 │     │
│  └──────────────────────────────────────┘     │
└──────────────────────────────────────────────┘
```

### 6.2 常量

| 常量 | 值 | 说明 |
|------|-----|------|
| `DEFAULT_GRID_W` | 30 | 默认地图宽度 |
| `DEFAULT_GRID_H` | 30 | 默认地图高度 |
| `UNITY_BTN_2D` | `'2D视图'` | 切换到 2D 时按钮文字 |
| `UNITY_BTN_3D` | `'3D视图'` | 切换到 3D 时按钮文字 |
| `UNITY_BRIDGE` | `'GameController'` | Unity C# 脚本对象名 |

### 6.3 内部状态

| 变量 | 类型 | 说明 |
|------|------|------|
| `is3DView` | Boolean | 当前是否 3D 视图 |
| `needsFrameReload` | Boolean | 重置仿真后需要重载 Unity iframe |
| `cameraDrag` | Object | 摄像机拖拽状态 `{active, mode('pan'/'orbit'/'none'), lastX, lastY}` |

### 6.4 回调钩子

```javascript
var getLiveData = function () { return null; };      // 获取当前 liveData
var getReplayData = function () { return null; };    // 获取当前 replayData
var isCanvasReady = function () { return false; };   // Canvas 是否就绪
var requestFinalizeCanvas = function () {};          // 请求重新计算 Canvas 尺寸
```

由 app.js 在初始化时通过 `UnityView.init(hooks)` 注入。

### 6.5 DOM 引用

| 变量 | DOM ID | 说明 |
|------|--------|------|
| `$unityShell` | `#unity-shell` | Unity 容器 |
| `$unityFrame` | `#unity-frame` | Unity iframe |
| `$unityInput` | `#unity-input` | 透明输入捕获层 |
| `$btnUnity` | `#btn-unity` | 2D/3D 切换按钮 |
| `$mapStack` | `#map-stack` | Canvas 双层堆栈 |
| `$welcome` | `#welcome-overlay` | 欢迎覆盖层 |

### 6.6 函数清单

| 函数 | 行 | 功能 |
|------|-----|------|
| `init(hooks)` | 29-48 | 初始化：获取 DOM 引用，注入回调，初始化摄像机输入事件 |
| `is3D()` | 50-52 | 返回当前是否 3D 视图 |
| `onCanvasReady()` | 54-61 | Canvas 就绪时显示 map-stack，3D 模式下同步尺寸 |
| `reloadFrame()` | 63-67 | 重载 Unity iframe（附加 `_=<timestamp>` 防缓存） |
| `resetForNewSimulation()` | 70-74 | 仿真重置：退出 3D + 重载 iframe |
| `syncSize()` | 76-101 | 根据地图尺寸计算 Unity iframe 的像素宽高 |
| `resolveUnityInstance()` | 103-110 | 获取 iframe 内的 `unityInstance` 对象 |
| `sendUnityCamera(method, payload)` | 112-117 | 通过 `SendMessage` 调用 Unity C# 方法 |
| `resolveCameraDragMode(button, shiftKey, altKey)` | 118-123 | 解析摄像机操作模式：左键无修饰=pan, Shift+左键或右键=orbit |
| `onUnityInputDown(event)` | 125-138 | 指针按下事件处理，设置拖拽状态和 setPointerCapture |
| `onUnityInputMove(event)` | 139-150 | 指针移动事件处理，计算 dx/dy 发送 `OnWebPan` 或 `OnWebOrbit` |
| `onUnityInputUp(event)` | 152-158 | 指针释放事件处理，清除拖拽状态 |
| `onUnityInputWheel(event)` | 160-165 | 滚轮事件处理，发送 `OnWebZoom` |
| `initUnityCameraInput()` | 167-177 | 绑定指针/滚轮事件监听器 |
| `show2DMapView()` | 179-199 | 显示 2D Canvas 视图，隐藏 Unity Shell |
| `show3DMapView()` | 201-214 | 显示 3D Unity 视图，隐藏 Canvas，添加 `.map-area-unity` class |
| `toggleView()` | 216-228 | 2D/3D 切换入口 |
| `exit()` | 230-235 | 强制退出 3D 视图 |

### 6.7 摄像机控制

Unity iframe 上覆盖透明 `#unity-input` div（`z-index:2, touch-action:none`），拦截鼠标/触控事件：

| 操作 | 调用方法 | payload |
|------|---------|--------|
| 左键拖移 | `GameController.SendMessage("OnWebPan", dx+","+dy)` | `"dx,dy"` |
| Shift+左键 或 右键拖移 | `GameController.SendMessage("OnWebOrbit", dx+","+dy)` | `"dx,dy"` |
| 滚轮 | `GameController.SendMessage("OnWebZoom", -deltaY)` | `"-deltaY"` |

### 6.8 WebSocket 地址重写

`ws-redirect.js` 在 Unity 加载前拦截 `window.WebSocket` 构造函数，将 `localhost` 自动替换为当前浏览器的主机名，使 Unity 内的 WebSocket 连接到正确的 Display 服务器。

### 6.9 全局导出

```javascript
global.UnityView = {
  init: init,
  is3D: is3D,
  onCanvasReady: onCanvasReady,
  syncSize: syncSize,
  exit: exit,
  resetForNewSimulation: resetForNewSimulation
};
```

---

## 七、背景粒子动画 (particles.js)

**文件**：`display/src/main/resources/web/js/particles.js` (60 行)

### 7.1 设计

- 创建独立的 `<canvas>` 元素，CSS 定位为 `position:fixed; top:0; left:0; z-index:0; pointer-events:none`
- `document.body.prepend(canvas)` 插入为 body 的第一个子元素（在内容之下）
- 仅 login、dashboard、analysis、user-management 页面加载（index.html 不加载）

### 7.2 粒子参数

| 参数 | 值 | 说明 |
|------|-----|------|
| `COUNT` | 120 | 粒子总数 |
| 半径 | random(1, 3.5) | 每个粒子的圆半径 |
| 速度 | random(-0.175, 0.175) 各轴 | 每帧位移 |
| 透明度 | random(0.15, 0.6) | 基础 alpha |
| 颜色 | `rgba(59,130,246, alpha)` | 统一蓝色调 |
| 脉冲 | random(0, 2*PI) | sin 波动相位 |

### 7.3 动画逻辑

```
每帧 (requestAnimationFrame)：
1. clearRect 清空
2. 遍历每个粒子：
   a. x += vx; y += vy
   b. 边界循环（穿出右边界从左边重新进入，反之亦然）
   c. pulse += 0.015
   d. alpha = 基础透明度 + sin(pulse)*0.15
   e. ctx.arc + fill 绘制
```

### 7.4 resize 重分布

- 响应 `window.resize`
- 按 `ceil(sqrt(COUNT * w/h))` 列、`ceil(COUNT / cols)` 行均匀分布粒子
- 在每个网格单元内加入随机偏移

---

## 八、样式系统 (style.css)

**文件**：`display/src/main/resources/web/css/style.css` (217 行)

### 8.1 设计系统

#### 颜色系统

| 用途 | 颜色值 | CSS 变量/类 |
|------|--------|------------|
| 主背景 | `#F1F5F9` | `body { background }` |
| 卡片/面板背景 | `rgba(255,255,255,0.92)` | `.sidebar`, `.cars-panel` |
| 半透面板背景 | `rgba(248,250,252,0.9)` | `.panel`, `.car-card` |
| 按钮/输入框背景 | `rgba(255,255,255,0.92)` | `.btn`, `.form-row input` |
| 主要边框 | `#E2E8F0` | `.header`, `.sidebar`, `.panel`, `.car-card` |
| 输入框边框 | `#CBD5E1` | `.form-row input`, `.form-row select`, `.btn` |
| 主色调 | `#3B82F6` (Blue-500) | `.btn-primary` |
| 主色调 hover | `#2563EB` | `.btn-primary:hover` |
| 警告色 | `#F59E0B` (Amber-500) | `.btn-warning` |
| 警告色 hover | `#D97706` | `.btn-warning:hover` |
| 危险色 | `#EF4444` (Red-500) | `.btn-danger` |
| 危险色 hover | `#DC2626` | `.btn-danger:hover` |
| 成功色 | `#10B981` (Emerald-500) | 步数有效率的图表色 |
| 标题文字 | `#1E293B` | `.header h1`, `.panel h3` |
| 正文文字 | `#334155` | `body { color }` |
| 辅助文字 | `#64748B` | `.global-info`, `.car-detail` |
| 浅色文字 | `#94A3B8` | `.car-card.placeholder`, `.legend-item` |
| 更浅文字 | `#CBD5E1` | `.welcome-box`, 部分占位文字 |

#### 字体系统

```css
font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Noto Sans SC', 'Helvetica Neue', Arial, sans-serif;
```

- 标题 (`h1`)：`font-size: 15px; font-weight: 700; letter-spacing: 0.3px`
- 面板标题 (`h3`)：`font-size: 13px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.8px`
- 正文字号：`13px` or `font-size: 13px` 在大部分组件中使用
- 行高：`line-height: 1.6` (body)

#### 间距与圆角

| 元素 | 值 |
|------|-----|
| 主圆角 | `8-16px` |
| 面板圆角 | `10px` |
| 卡片圆角 | `8px` (car-card), `12px` (a-card) |
| 按钮圆角 | `8px` |
| 输入框圆角 | `6px` |
| 登录卡片圆角 | `14px` |

### 8.2 CSS Reset

```css
*, *::before, *::after { margin:0; padding:0; box-sizing:border-box; }
```

### 8.3 组件样式

#### 按钮系统 (.btn)

```css
.btn {
  flex:1; height:36px; padding:0 8px;
  border:1px solid #CBD5E1; border-radius:8px;
  cursor:pointer; font-size:13px; font-weight:600;
  display:flex; align-items:center; justify-content:center; gap:5px;
  transition:all 0.15s; white-space:nowrap;
  background:rgba(255,255,255,0.92); color:#334155;
}
.btn:hover:not(:disabled) { background:#F1F5F9; }
.btn:active:not(:disabled) { transform:scale(0.97); }
.btn:disabled { opacity:0.4; cursor:not-allowed; }
```

按钮颜色变体：

| 类 | 背景 | 边框颜色 | 文字颜色 |
|----|------|---------|---------|
| `.btn-primary` | `#3B82F6` | `#3B82F6` | `#fff` |
| `.btn-warning` | `#F59E0B` | `#F59E0B` | `#fff` |
| `.btn-danger` | `#EF4444` | `#EF4444` | `#fff` |
| 默认 | `rgba(255,255,255,0.92)` | `#CBD5E1` | `#334155` |

#### 小按钮 (.btn-small)

```css
.btn-small {
  height:34px; padding:0 10px;
  background:#F1F5F9; border:1px solid #CBD5E1;
  border-radius:6px; color:#64748B; font-size:13px;
  cursor:pointer;
}
.btn-small:hover { background:#E2E8F0; }
```

用于回放控制按钮组。

#### 表单输入

```css
.form-row input[type="number"], .form-row select {
  width:100%; height:36px; padding:0 10px;
  background:rgba(255,255,255,0.92); border:1px solid #CBD5E1;
  border-radius:6px; color:#334155; font-size:13px;
}
.form-row input[type="number"]:focus-visible, .form-row select:focus-visible {
  outline:none; border-color:#3B82F6; box-shadow:0 0 0 3px #3B82F620;
}
.form-row input[type="range"] {
  width:100%; height:6px; accent-color:#3B82F6; cursor:pointer;
}
```

`.input-pair`：flex 横排，`gap:6px`，`input` 各占 `flex:1`，中间 `span` 显示 "x"。

#### 表单标签

```css
.form-row label {
  display:flex; align-items:center; justify-content:space-between;
  font-size:13px; color:#64748B; margin-bottom:4px;
}
.form-row label em {
  font-style:normal; font-weight:600; color:#3B82F6;
}
```

### 8.4 布局系统

#### 三栏布局 (index.html)

```css
.main-container { display:flex; flex:1; overflow:hidden; }
.sidebar {
  width:240px; min-width:240px;
  height:calc(100vh - 52px);
  background:rgba(255,255,255,0.92);
  border-right:1px solid #E2E8F0;
  padding:14px 10px; display:flex; flex-direction:column; gap:6px;
  overflow-y:scroll;
}
.map-area {
  flex:1; display:flex; justify-content:center; align-items:flex-start;
  overflow:auto; padding:10px; background:#F1F5F9;
}
.cars-panel {
  width:290px; min-width:290px;
  background:rgba(255,255,255,0.92);
  border-left:1px solid #E2E8F0;
  padding:22px 12px; overflow-y:scroll;
  display:flex; flex-direction:column; gap:8px;
}
```

#### 登录页布局

```css
.login-page { display:flex; justify-content:center; align-items:center; background:#F1F5F9; }
.login-container { width:380px; position:relative; z-index:1; }
.login-card {
  background:rgba(255,255,255,0.92); border:1px solid #E2E8F0;
  border-radius:14px; padding:36px 32px;
  box-shadow:0 4px 24px rgba(0,0,0,0.06);
}
```

### 8.5 地图区域

```css
.map-stack { position:relative; flex-shrink:0; }
#map-canvas {
  display:block; background:#D0D0D0;
  image-rendering:pixelated;          /* 像素风格渲染 */
}
#car-canvas {
  position:absolute; top:0; left:0;
  display:block; image-rendering:pixelated;
  pointer-events:none;               /* 点击穿透到下层 */
}
```

### 8.6 Unity 3D 容器

```css
.unity-shell {
  display:none; position:relative; flex-shrink:0;
  box-shadow:0 2px 12px rgba(0,0,0,0.08);
  border-radius:4px; overflow:hidden;
  overscroll-behavior:none; touch-action:none;
}
.unity-shell.active { display:block; }
.unity-frame {
  display:block; width:100%; height:100%;
  border:none; background:#231F20;
  pointer-events:none;               /* Unity 不直接接收鼠标 */
}
.unity-input-layer {
  position:absolute; inset:0; z-index:2;
  touch-action:none; cursor:grab; background:transparent;
}
.unity-input-layer:active { cursor:grabbing; }
.map-area.map-area-unity { overflow:hidden; }
```

### 8.7 车辆状态面板

```css
.car-card {
  background:rgba(248,250,252,0.9); border:1px solid #E2E8F0;
  border-radius:8px; padding:12px;
}
.car-card.placeholder { text-align:center; color:#94A3B8; font-size:13px; }
.car-header { display:flex; justify-content:space-between; align-items:center; margin-bottom:6px; }
.car-number { font-size:13px; font-weight:700; color:#1E293B; }
.car-status-tag {
  padding:2px 8px; border-radius:10px;
  font-size:12px; font-weight:600; color:#fff;
}
.car-detail { color:#64748B; line-height:1.8; font-size:13px; }
.steps-bar-wrap { height:4px; background:#E2E8F0; border-radius:2px; margin-top:4px; }
.steps-bar-fill { height:100%; background:#3B82F6; border-radius:2px; transition:width 0.3s; }
```

### 8.8 排行榜

```css
#leaderboard {
  padding-left:18px; font-size:13px; line-height:2; color:#64748B;
  list-style:decimal; max-height:none; overflow-y:scroll;
}
#leaderboard li:nth-child(1) { color:#D97706; font-weight:700; }  /* 金牌 */
#leaderboard li:nth-child(2) { color:#64748B; font-weight:600; }  /* 银牌 */
#leaderboard li:nth-child(3) { color:#94A3B8; }                   /* 铜牌 */
```

### 8.9 图例

```css
.legend-item {
  display:flex; align-items:center; gap:8px;
  font-size:13px; color:#64748B; margin-bottom:4px;
}
.legend-item .dot {
  width:10px; height:10px; border-radius:50%; flex-shrink:0;
}
```

### 8.10 导航链接

```css
.nav-link {
  color:#64748B; text-decoration:none; font-size:13px;
  padding:4px 8px; border-radius:4px; transition:all 0.15s;
}
.nav-link:hover { color:#1E293B; background:#F1F5F9; }
.nav-link.active { color:#3B82F6; font-weight:600; }
```

### 8.11 回放控制条

```css
.replay-bar {
  margin-top:10px; padding:8px 0 2px;
  display:flex; flex-direction:column; gap:6px;
  border-top:1px solid #E2E8F0;
}
.replay-btns { display:flex; gap:4px; }
```

### 8.12 模式标签

```css
.mode-tag {
  padding:2px 10px; border-radius:12px; font-size:13px; font-weight:600;
  background:#FEF2F2; color:#DC2626; border:1px solid #FECACA;
}
```

### 8.13 欢迎页面

```css
.welcome-overlay {
  flex:1; display:flex; align-items:center; justify-content:center;
  background:rgba(255,255,255,0.92);
}
.welcome-box { text-align:center; padding:40px; }
.welcome-icon { font-size:64px; margin-bottom:20px; opacity:0.2; }
.welcome-box h2 { font-size:22px; color:#CBD5E1; font-weight:400; margin-bottom:12px; }
.welcome-box p { font-size:13px; color:#E2E8F0; }
.welcome-box strong { color:#CBD5E1; }
```

### 8.14 统计分析

```css
.analysis-main {
  flex:1; display:flex; flex-direction:column;
  padding:16px; overflow-y:scroll; gap:12px;
}
.analysis-placeholder {
  flex:1; display:flex; align-items:center; justify-content:center;
  border:2px dashed #E2E8F0; border-radius:12px;
  background:rgba(248,250,252,0.9); min-height:200px;
  color:#94A3B8; font-size:13px;
}
```

### 8.15 滚动条

```css
.sidebar::-webkit-scrollbar,
.cars-panel::-webkit-scrollbar,
#leaderboard::-webkit-scrollbar { width:8px; }

.sidebar::-webkit-scrollbar-thumb,
.cars-panel::-webkit-scrollbar-thumb,
#leaderboard::-webkit-scrollbar-thumb {
  background:#CBD5E1; border-radius:4px;
}
.sidebar::-webkit-scrollbar-thumb:hover,
.cars-panel::-webkit-scrollbar-thumb:hover { background:#94A3B8; }

.sidebar::-webkit-scrollbar-track,
.cars-panel::-webkit-scrollbar-track {
  background:#F1F5F9; border-radius:4px;
}
```

### 8.16 过渡与动画

- 按钮：`transition:all 0.15s`
- 按钮按下：`transform:scale(0.97)`
- 卡片 hover：`transition:all 0.15s`
- 步数进度条：`transition:width 0.3s`
- 导航链接：`transition:all 0.15s`
- 加载 spinner：`@keyframes spin { to { transform:rotate(360deg) } }`，`animation:spin 0.6s linear infinite`

---

## 九、前端 JS 模块依赖

```
particles.js (独立，背景动画)
     |
auth.js (认证模块，所有页面依赖)
     |
+----+-------------+
|                  |
app.js (主仿真页面)  analysis.js (统计分析页)
unity-view.js (Unity 桥接)
```

`auth.js` 导出全局 `window.Auth` 对象，提供 `login/logout/checkAuth/getCurrentUser/getToken/applyPermissions/renderNavBar` 等方法。

`unity-view.js` 导出全局 `window.UnityView` 对象，提供 `init/is3D/onCanvasReady/syncSize/exit/resetForNewSimulation` 等方法。

`app.js` 在初始化时：
- 调用 `Auth.checkAuth()` -> `Auth.renderNavBar()` -> `Auth.applyPermissions()`
- 调用 `UnityView.init({getLiveData, getReplayData, isCanvasReady, requestFinalizeCanvas})`

---

## 十、关键代码片段

### 10.1 WebSocketBridge 状态推送

```java
public void pushSimulationState(int tick, int explorationRate) {
    if (clients.isEmpty()) return;
    // ... 构建 SimulationState ...
    broadcast(serializeState(tick, syncedRate));
}
```

### 10.2 Base64 位图压缩切换阈值

```java
private static final int COMPACT_MAP_CELL_THRESHOLD = 2500;

// 地图超过 2500 格时，切换为 Base64 位图传输
if (mapWidth * mapHeight <= COMPACT_MAP_CELL_THRESHOLD) {
    // 标准 JSON boolean[][]
    return JSON.toJSONString(state);
}
// Compact mode: mapViewB64 / mapBlockB64 / mapSealedB64
```

### 10.3 动态小车 ID 解析（缺号优先）

```java
static String resolve(Collection<String> onBoardCarIds, ...) {
    for (String carId : sorted) {
        if (!hasManagedProcess(carId, ...)) {
            return carId;  // 已注册但无进程 -> 优先补全
        }
    }
    return String.format("Car%03d", highestAssignedNumber(...) + 1);  // fallback
}
```

### 10.4 前端 Canvas 双层渲染

```javascript
// 地图层：每 3 tick 全量重绘
if (shouldRepaintMapLayer(tick)) {
    renderMapLayer();
}
// 中间 tick：仅增量绘制新探索区域
paintIncrementalExplored(liveData.mapView);
// 小车层：每 tick 全量重绘
renderCarsLayer();
```

### 10.5 前端 Base64 位图解码

```javascript
function decodeBitmapB64(b64, width, height) {
    var binary = atob(b64);
    var bitmap = [];
    for (var row = 0; row < height; row++) {
        var rowBits = [];
        for (var col = 0; col < width; col++) {
            var offset = row * width + col;
            var byteIdx = (offset / 8) | 0;
            var bitIdx = 7 - (offset % 8);
            var byteVal = byteIdx < binary.length ? binary.charCodeAt(byteIdx) : 0;
            rowBits.push(((byteVal >> bitIdx) & 1) === 1);
        }
        bitmap.push(rowBits);
    }
    return bitmap;
}
```

### 10.6 Launcher 启动序列

```java
private static void launchAllModules(LaunchConfig c) {
    startTaskConfigurator(c);   sleepMillis(500);
    startNavigator(c);
    startTargetPlanner(c);      sleepMillis(300);
    startCarFleet(c);
    startDisplay(c);            sleepMillis(300);
    startController(c);         sleepMillis(1000);
}
```

### 10.7 HTTP 路由分发

```java
private void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    if (path.startsWith("/api/auth/"))     { authApi.handle(path, exchange); return; }
    if (path.startsWith("/api/admin/"))    { checkAuth(...); adminApi.handle(...); return; }
    if (path.startsWith("/api/analysis/")) { checkAuth(...); analysisApi.handle(...); return; }
    if (path.startsWith("/api/replay/"))   { checkAuth(...); replayApi.handle(...); return; }
    if (isWhitelisted(path))               { serveStatic(...); return; }
    if (!checkAuth(...)) return;
    serveStatic(...);
}
```

### 10.8 单会话 Token 验证

```javascript
function isStaleTab() {
  var globalSeq = getGlobalLoginSeq();
  var tabSeq = getTabLoginSeq();
  return !!(globalSeq && tabSeq && globalSeq !== tabSeq);
}
```

### 10.9 Unity 摄像机控制

```javascript
function sendUnityCamera(method, payload) {
  var instance = resolveUnityInstance();
  if (!instance) return;
  instance.SendMessage(UNITY_BRIDGE, method, payload || '');
}
```

### 10.10 app.js 状态常量

```javascript
var CELL_SIZE = 18;                      // 初始格子像素大小
var DEFAULT_GRID_W = 30;                 // 默认地图宽度
var DEFAULT_GRID_H = 30;                 // 默认地图高度
var RECONNECT_DELAY_MS = 3000;           // WS 重连延迟
var MAX_ROUTE_DRAW = 10;                 // 路线最多绘制步数
var REPLAY_FRAME_MS = 200;              // 回放帧间隔
var MAP_RENDER_INTERVAL = 3;            // 地图层重绘间隔 tick 数
var ADD_CAR_TIMEOUT_MS = 30000;         // 添加小车超时
var MIN_TICK_INTERVAL_MS = 100;         // 最小节拍间隔
var MAX_TICK_INTERVAL_MS = 2000;        // 最大节拍间隔
```

---

## 十一、文件清单

### Display 模块 Java 源码

```
display/src/main/java/com/substation/display/
|-- DisplayMain.java              (151 行)  模块入口
|-- WebSocketBridge.java          (444 行)  WebSocket 桥接核心
|-- HttpFileServer.java           (143 行)  HTTP 服务与路由
|-- DynamicCarLauncher.java       (193 行)  动态小车进程启动
|-- DynamicCarIdResolver.java     (67 行)   编号分配策略
|-- DynamicCarProcessKiller.java  (115 行)  遗留进程清理
|-- ReplayCoordinator.java        (46 行)   回放协调
```

### Launcher 模块

```
launcher/src/main/java/com/substation/launcher/
|-- LauncherMain.java             (333 行)  一键启动器
```

### 前端文件

```
display/src/main/resources/web/
|-- index.html                    (197 行)  仿真主界面
|-- login.html                    (132 行)  登录/注册
|-- dashboard.html                (143 行)  角色仪表盘
|-- analysis.html                 (88 行)   统计分析
|-- user-management.html          (377 行)  用户管理
|-- css/
|   |-- style.css                 (217 行)  全局样式
|-- js/
|   |-- app.js                    (1317 行) 仿真主逻辑
|   |-- auth.js                   (414 行)  认证模块
|   |-- analysis.js               (655 行)  统计分析
|   |-- unity-view.js             (245 行)  Unity 桥接
|   |-- particles.js              (60 行)   背景粒子
|-- unity/
    |-- index.html                         Unity WebGL 入口
    |-- ws-redirect.js                     WebSocket 地址重写
    |-- Build/
        |-- unity.data
        |-- unity.wasm
        |-- unity.framework.js
        |-- unity.loader.js
```

---

## 十二、与其他模块的交互

| 交互方向 | 协议 | 说明 |
|---------|------|------|
| Display <- Controller | RabbitMQ Fanout | 接收 REFRESH_ALL 消息触发状态推送 |
| Display -> Controller | RabbitMQ ControllerCmd | 转发浏览器控制命令（开始/暂停/重置/调速） |
| Display -> Redis | Jedis (只读) | 读取 TaskConfig、mapView、car positions、status |
| Display <-> SQL Server | JDBC | 用户管理、仿真统计、场次记录、操作日志 |
| Browser -> Display | WebSocket (8888) | 仿真控制命令、回放请求 |
| Display -> Browser | WebSocket (8888) | 仿真状态 JSON 推送、回放数据 |
| Browser -> Display | HTTP (8887) | 页面/静态资源请求、API 调用 |
| Dynamic Car <- Display | OS Process | `java -jar car.jar <carId> --dynamic` |

---

## 十三、完整 API 路由表

### 认证 API (/api/auth/*)

| 方法 | 路径 | 认证 | 功能 |
|------|------|------|------|
| POST | `/api/auth/login` | 否 | 用户登录，返回 JWT token |
| POST | `/api/auth/register` | 否 | 用户注册（提交审核） |
| POST | `/api/auth/logout` | 是 | 退出登录，销毁 session |
| GET | `/api/auth/me` | 是 | 获取当前用户信息（心跳验证） |
| POST | `/api/auth/change-password` | 是 | 修改密码 |

### 管理 API (/api/admin/*)

| 方法 | 路径 | 认证 | 功能 |
|------|------|------|------|
| GET | `/api/admin/users?page=&size=&search=&role=` | 是 | 用户列表（分页、搜索、筛选） |
| POST | `/api/admin/users/:username/reset-password` | 是 | 重置用户密码为 123456 |
| GET | `/api/admin/logs?username=&size=` | 是 | 查看用户操作日志 |
| GET | `/api/admin/registrations?page=&size=&status=` | 是 | 注册审核列表 |
| POST | `/api/admin/registrations/:id/approve` | 是 | 通过注册申请 |
| POST | `/api/admin/registrations/:id/reject` | 是 | 拒绝注册申请 |

### 分析 API (/api/analysis/*)

| 方法 | 路径 | 认证 | 功能 |
|------|------|------|------|
| GET | `/api/analysis/records?page=&size=` | 是 | 获取仿真记录列表 |
| POST | `/api/analysis/records` | 是 | 保存仿真记录 |
| DELETE | `/api/analysis/records/:runId` | 是 | 删除仿真记录 |
| POST | `/api/analysis/discard` | 是 | 放弃保存（归档场次） |

### 回放 API (/api/replay/*)

| 方法 | 路径 | 认证 | 功能 |
|------|------|------|------|
| GET | `/api/replay/runs?page=&size=` | 是 | 历史场次列表 |
| GET | `/api/replay/runs/:runId` | 是 | 获取指定历史场次的回放数据 |

### WebSocket 消息 (ws://host:8888)

| 方向 | 类型 | 说明 |
|------|------|------|
| 下行 | 无 type（tick 数据） | 实时仿真状态推送 |
| 下行 | `CAR_PENDING` | 动态小车启动中 |
| 下行 | `CAR_LAUNCHED` | 动态小车启动成功 |
| 下行 | `CAR_LAUNCH_FAILED` | 动态小车启动失败 |
| 下行 | `REPLAY_DATA` | 回放数据 |
| 下行 | `REPLAY_ERROR` | 回放加载失败 |
| 下行 | `RUN_ARCHIVED` | 场次已归档 |
| 上行 | `SET_CONFIG` | 开始仿真 |
| 上行 | `RESET` | 重置仿真 |
| 上行 | `TOGGLE_PAUSE` | 暂停/继续 |
| 上行 | `SET_TICK_INTERVAL` | 调整节拍间隔 |
| 上行 | `ADD_CAR` | 动态添加小车 |
| 上行 | `TOGGLE_OBSTACLE` | 切换障碍物 |
| 上行 | `REQUEST_REPLAY` | 请求回放数据 |

### 静态文件路由

| 路径模式 | 说明 |
|---------|------|
| `/` | 重定向到 `/login.html` |
| `/login.html` | 白名单（无需认证） |
| `/index.html` | 白名单 |
| `/dashboard.html` | 白名单 |
| `/analysis.html` | 白名单 |
| `/css/*` | 白名单 |
| `/js/*` | 白名单 |
| `/unity/*` | 白名单 |
| 其他 | 需认证 |

---

## 十四、app.js 完整函数索引

| 行号范围 | 函数名 | 功能分类 |
|---------|--------|---------|
| 7-13 | (常量定义) | 配置 |
| 16-23 | STATUS_COLORS, STATUS_NAMES | 状态颜色映射 |
| 26 | CAR_COLORS | 车辆固定颜色 |
| 29-36 | LIGHT | 浅色主题颜色 |
| 38-81 | DOM 引用获取 | DOM 绑定 |
| 83-118 | 状态变量声明 | 状态管理 |
| 121-124 | `buildWebSocketUrl()` | WebSocket |
| 126-132 | `connectWebSocket()` | WebSocket |
| 134-139 | `onSocketOpen()` | WebSocket |
| 141-148 | `syncCarCountFromLiveData(data)` | 状态同步 |
| 150-157 | `syncCarCountFromLaunchedCar(carId)` | 状态同步 |
| 159-195 | `onSocketMessage(event)` | WebSocket 消息分发 |
| 197-209 | `beginAddCarPending(carId)` | 动态添加小车 UI |
| 212-228 | `maybeCompleteAddCarPending(data)` | 动态添加小车 UI |
| 230-235 | `failAddCarPending(reason)` | 动态添加小车 UI |
| 237-252 | `clearAddCarPending(wasSuccessful)` | 动态添加小车 UI |
| 254-258 | `clearAddCarPendingTimer()` | 动态添加小车 UI |
| 261-267 | `clearMapCaches()` | Canvas 缓存 |
| 269-288 | `paintIncrementalExplored(mapView)` | Canvas 增量渲染 |
| 290-292 | `syncRenderedMapView(mapView, gridW, gridH)` | Canvas 缓存 |
| 294-299 | `shouldRepaintMapLayer(tick)` | Canvas 重绘判断 |
| 301-320 | `normalizeMapPayload(data)` | Base64 解码/缓存 |
| 322-337 | `decodeBitmapB64(b64, width, height)` | Base64 位图解码 |
| 339-341 | `onSocketClose()` | WebSocket |
| 344 | `onSocketError(err)` | WebSocket |
| 346-357 | `buildTaskConfigFromForm()` | 表单读取 |
| 359-366 | `ensureLiveDataTaskConfig()` | 状态初始化 |
| 369-405 | `finalizeCanvas()` | Canvas 尺寸计算 |
| 408-476 | `renderMapLayer()` | Canvas 地图层渲染 |
| 479-493 | `renderCarsLayer()` | Canvas 小车层渲染 |
| 495-499 | `shouldDrawRoute(car)` | 路线绘制判断 |
| 501-531 | `drawRoute(car, ctx, cs)` | 路线绘制 |
| 533-545 | `findRouteDrawStart(position, routeList)` | 路线起点查找 |
| 547-571 | `drawCar(car, ctx, cs)` | 小车绘制 |
| 574-602 | `renderCarsPanel(data)`, `buildCarCard(car)` | 右侧车辆面板 |
| 604-615 | `renderLeaderboard(data)` | 排行榜渲染 |
| 617-620 | `isSimulationComplete(data)` | 状态判断 |
| 623-628 | `isSimulationInProgress(data)` | 状态判断 |
| 630-646 | `syncControlButtons(data)` | 按钮状态同步 |
| 648-654 | `resolveRunStarter(data)` | 场次发起人解析 |
| 656-694 | `applyTaskCompleteUi(data, tick, rate)`, `isCurrentRunOperator(runStartedBy)` | 任务完成处理 |
| 696-707 | `refreshCurrentOperator(done)` | 操作者身份获取 |
| 709-729 | `updateGlobalInfo(data)` | 顶栏信息更新 |
| 731-819 | `showSavePopup(data, rate)` | 保存弹窗 |
| 822-839 | `computeBalanceScore(cars)` | 均衡指数计算 |
| 841-850 | `startElapsedTimer()`, `stopElapsedTimer()` | 计时器 |
| 853-855 | `sendCommand(msg)` | WebSocket 发送 |
| 857-885 | `beginSimulationStart()` | 开始仿真 |
| 887-893 | `onStartClick()` | 开始按钮事件 |
| 895-899 | `onPauseClick()` | 暂停按钮事件 |
| 901-926 | `onResetClick()` | 重置按钮事件 |
| 928-941 | `resetCanvas()` | Canvas 重置 |
| 943-952 | `onAddCarClick()` | 添加小车事件 |
| 954-955 | `onObstacleRatioInput()`, `onTickIntervalInput()` | 滑块联动 |
| 957-973 | `syncTickIntervalFromTaskConfig(taskConfig)` | 节拍间隔同步 |
| 975-980 | `onTickIntervalChange()` | 节拍间隔变更 |
| 983-1016 | `authHeaders()`, `formatRunLabel(run)`, `loadReplayRunList(selectRunId)` | 回放辅助 |
| 1018-1054 | `requestReplayBySelection()` | 回放请求 |
| 1056-1131 | `receiveReplayData(msg)` | 回放数据接收 |
| 1134-1146 | `createEmptyView(w,h)`, `cloneView(v)`, `mergeMapViews(...)` | 二维数组工具 |
| 1148-1158 | `enterReplay()`, `exitReplay()` | 回放进出 |
| 1160-1191 | `renderReplayFrame()` | 回放帧渲染 |
| 1193-1201 | `lookupReplayPosition(carId, tick)` | 回放位置查询 |
| 1203-1218 | `updateReplayLabel()`, `startReplayTimer()`, `stopReplayTimer()`, `replayTickForward()` | 回放控制 |
| 1222-1233 | `onCanvasContextMenu(e)` | 右键障碍物 |
| 1235-1236 | `getGridW(data)`, `getGridH(data)` | 地图尺寸工具 |
| 1239-1252 | `onPwdSubmit()` | 密码修改 |
| 1255-1282 | 事件绑定 | 初始化 |
| 1279-1282 | `onReplaySliderInput()`, `onReplayToggleClick()`, `onReplayStepPrev()`, `onReplayStepNext()` | 回放控件事件 |
| 1285-1317 | 初始化流程 | 启动 |
