# 变电站巡检仿真系统

> Substation Inspection Simulation System — 多小车协作探索仿真平台，基于**黑板架构（Blackboard Architecture Pattern）**实现。
> 软件体系结构课程项目，9 模块 Maven 多进程分布式仿真。

[![Java](https://img.shields.io/badge/Java-17-blue)](https://openjdk.org/projects/jdk/17/)
[![Maven](https://img.shields.io/badge/Maven-3.9+-C71A36)](https://maven.apache.org/)
[![Redis](https://img.shields.io/badge/Redis-7-red)](https://redis.io/)
[![RabbitMQ](https://img.shields.io/badge/RabbitMQ-3.12-orange)](https://www.rabbitmq.com/)
[![License](https://img.shields.io/badge/License-MIT-green)](./LICENSE)

---

## 环境要求

| 依赖项 | 版本要求 | 用途 | 是否必需 |
|--------|---------|------|---------|
| **JDK** | 17+ | 编译与运行 Java 模块 | 必需 |
| **Maven** | 3.9+ (含 mvnw wrapper) | 项目构建 | 必需 |
| **Docker** | 20.10+ | 启动 Redis + RabbitMQ 基础设施 | 必需（也可手动安装） |
| **Redis** | 7 (Alpine) | 黑板共享存储 | 必需 |
| **RabbitMQ** | 3.12 (Management) | 消息总线 | 必需 |
| **SQL Server** | 2019+ | 用户认证与仿真归档持久化 | 可选（仅限登录功能） |
| **浏览器** | Chrome / Edge / Firefox 最新版 | 前端可视化 | 必需 |
| **Git** | 2.30+ | 版本控制 | 开发必需 |

> **操作系统**：Windows 10/11、Linux（Ubuntu 20.04+）、macOS 12+ 均可运行。

### 快速环境检查

```bash
java --version          # 应输出 17 或更高
docker compose version  # 应正常输出版本号
```

---

## 快速开始

### 方式一：一键启动（推荐）

```bash
# 1. 启动基础设施（Redis + RabbitMQ）
docker compose up -d

# 2. 编译项目
./mvnw clean install -DskipTests

# 3. 一键启动全部模块
./start_all.bat

# 浏览器访问 http://localhost:8887
```

### 方式二：分步启动（分布式部署 / 调试）

```bash
# 1. 基础设施
docker compose up -d

# 2. 编译
./mvnw clean install -DskipTests

# 3. TaskConfigurator（必须最先启动，初始化地图）
java -cp task-configurator/target/* com.substation.taskconfigurator.TaskConfiguratorMain

# 4. 规划模块（可并行启动）
java -cp navigator/target/* com.substation.navigator.NavigatorMain
java -cp target-planner/target/* com.substation.targetplanner.TargetPlannerMain
java -cp strategy-supervisor/target/* com.substation.strategysupervisor.StrategySupervisorMain

# 5. 小车执行端（可多实例，CarId 不同即可）
java -cp car/target/* com.substation.car.CarMain Car001
java -cp car/target/* com.substation.car.CarMain Car002
java -cp car/target/* com.substation.car.CarMain Car003

# 6. 前端服务
java -cp display/target/* com.substation.display.DisplayMain

# 7. 控制器（最后启动，开始节拍驱动）
java -cp controller/target/* com.substation.controller.ControllerMain
```

> **注意**：SQL Server 需要单独安装，连接串为 `jdbc:sqlserver://localhost:1433;databaseName=substation-patrol`。用户认证功能依赖 SQL Server；若仅运行仿真，统计分析走前端 localStorage，SQL Server 非必需。

### 分布式部署

多机部署时，在各机器上创建 `deploy/infra.local.json` 配置文件指定远程 Redis / RabbitMQ / Display 地址，详见 [启动部署指南](./docs/04-启动部署/启动部署指南.md)。

---

## 技术栈

| 层次 | 技术 | 版本 | 说明 |
|------|------|------|------|
| **语言** | Java | 17 | record、sealed class、pattern matching、text blocks |
| **构建工具** | Maven (mvnw) | 3.9+ | 多模块 POM 聚合，car 模块 shade fat jar |
| **黑板存储** | Redis | 7 (Alpine) | Jedis 5.1.5 客户端，SET/GET/HSET/Lua 脚本原子操作 |
| **消息总线** | RabbitMQ | 3.12 (Management) | amqp-client 5.21.0，Direct + Fanout 混合拓扑 |
| **JSON 序列化** | fastjson2 | 2.0.47 | 消息体与黑板数据的序列化/反序列化 |
| **实时推送** | Java-WebSocket | 1.5.6 | Display:8888 通过 WebSocket 向浏览器推送仿真帧 |
| **关系数据库** | SQL Server | 2019+ | mssql-jdbc 12.8.1，用户表、注册表、操作日志 |
| **密码加密** | jBCrypt | 0.4 | 用户密码的加盐哈希 |
| **日志框架** | SLF4J + Logback | 2.0.16 / 1.5.12 | 控制台 UTF-8 输出，按模块区分日志 |
| **测试框架** | JUnit Jupiter | 5.10.5 | 单元测试，含 BlackboardClient / MessageBus / 路径规划测试 |
| **3D 可视化** | Unity 3D | WebGL | 通过 iframe 嵌入仿真页，实时展示 3D 地图与车辆 |
| **前端** | HTML5 Canvas + WebSocket | — | 三栏布局：左侧配置面板 / 中央 Canvas 分层渲染 / 右侧车辆状态卡 |
| **容器化** | Docker Compose | 3.8 | Redis + RabbitMQ 一键启动，数据卷持久化 |
| **内网穿透** 
| **HTTP 服务** | JDK `com.sun.net.httpserver` | — | Display 内嵌 HTTP 服务器（8887），无第三方 Web 框架依赖 |

---

## 架构图

```
                           ┌──────────────────────────┐
                           │        Browser            │
                           │   WebSocket + HTTP        │
                           └────────────┬─────────────┘
                                        │
┌───────────────────────────────────────┴──────────────────────────────────────┐
│                              display (前端服务)                               │
│                  HttpFileServer:8887    WebSocketBridge:8888                  │
└───────────────────────────────┬──────────────────────────────────────────────┘
                                │  Fanout Exchange "UpdateView"
┌───────────────────────────────┴──────────────────────────────────────────────┐
│                           RabbitMQ (Message Bus)                              │
│   ControllerCmd │ TargetPlannerCmd │ NavigatorCmd │ StrategySupervisorCmd    │
│   TaskConfigCmd │ Car_{carId} × N │ UpdateView (Fanout)                      │
└───────────────────────────────┬──────────────────────────────────────────────┘
                                │
┌───────────────────────────────┴───────┬───────────────┬──────────────────────┐
│           controller                  │               │                      │
│    ┌──────────────────┐              │               │                      │
│    │  TickScheduler    │              │               │                      │
│    │  StatusDispatcher │              │               │                      │
│    │  CommandHandler   │              │               │                      │
│    └──────────────────┘              │               │                      │
│       调度大脑（唯一调度者）            │               │                      │
└───┬─────────────┬─────────────┬──────┴───────┬───────┴──────────────────────┘
    │             │             │              │
┌───┴───┐   ┌────┴────┐  ┌────┴─────┐  ┌─────┴──────┐   ┌──────────────────┐
│target │   │navigator│  │ strategy │  │   task     │   │  car × N         │
│planner│   │ BFS/A*  │  │supervisor│  │configurator│   │  CarAgent +      │
│贪心前沿│   │ 路径规划│  │ 路线监督 │  │ 任务初始化 │   │  MoveExecutor    │
└───┬───┘   └────┬────┘  └────┬─────┘  └─────┬──────┘   └────────┬─────────┘
    │            │            │              │                   │
┌───┴────────────┴────────────┴──────────────┴───────────────────┴────────────┐
│                          Redis (Blackboard)                                   │
│  mapView │ mapBlock │ mapSealed │ mapHeat │ TaskConfig                       │
│  Car*:Position │ Car*:Target │ Car*:RouteList │ Car*:Status │ Car*:Steps     │
└──────────────────────────────────────────────────────────────────────────────┘

    知识源 (Knowledge Sources)  ──  仅与黑板交互，不直接互相调用
    Controller                ──  唯一调度器，按固定节拍驱动知识源
    Redis                     ──  共享黑板，存放全部共享状态
```

**架构三要素：**

| 要素 | 实现 | 职责 |
|------|------|------|
| **黑板（Blackboard）** | Redis 实例 | 存放地图状态、车辆信息、任务配置、探索统计等全部共享数据 |
| **知识源（Knowledge Sources）** | 各业务模块 | TargetPlanner、Navigator、StrategySupervisor、Car，各自独立对黑板读写 |
| **调度器（Controller）** | Controller 模块 | 唯一控制节点，按固定节拍（500ms）驱动整车群状态机，是所有消息的中转枢纽 |

**关键约束：**
- 知识源之间**不允许直接通信**，必须通过 Controller 中转消息
- 知识源**仅通过 Redis 黑板共享数据**，不直接读写彼此模块内的状态
- 所有 RabbitMQ 队列声明在 `common/QueueNames.java`，消息类型定义在 `common/MessageTypes.java`

---

## 模块概览

本项目采用 Maven 9 模块结构，所有业务模块仅依赖 `common`，互不依赖。模块间通信仅通过 RabbitMQ 消息 + Redis 黑板。

### 公共基础设施

| 模块 | Maven Artifact | 入口 | 职责 |
|------|---------------|------|------|
| **common** | `car-homework-common` | *(公共库)* | BlackboardClient（Redis 操作）、MessageBus（MQ 收发）、共享模型（Point / CarStatus / RouteStep / SimulationState）、认证与会话管理、统计分析引擎、仿真回放与归档、SQL Server 用户管理、地图工具（前沿搜索 / 聚类 / 出生点选择 / 加权路径） |

### 业务模块（知识源）

| 模块 | Maven Artifact | 入口类 | 职责 |
|------|---------------|--------|------|
| **controller** | `car-homework-controller` | `ControllerMain` | **调度大脑**：TickScheduler 按 500ms 节拍驱动、StatusDispatcher 状态收集与分派、CommandHandler 消息路由。启动时声明所有 MQ 队列，是所有知识源消息的唯一中转站 |
| **car** | `car-homework-car` | `CarMain` | **小车执行端**：5 态状态机（IDLE → WAITING_ROUTE → READY → MOVING → BLOCKED）、移动执行与碰撞检测、点亮地图、步数统计。支持多实例运行（通过 CarId 区分） |
| **navigator** | `car-homework-navigator` | `NavigatorMain` | **路径规划**：BFS（最短路径保证）与 A*（启发式搜索）双算法，从车辆当前位置计算到目标点的最优路径，返回 RouteStep 列表 |
| **target-planner** | `car-homework-target-planner` | `TargetPlannerMain` | **目标分配**：贪心前沿探索，两阶段策略——探索率 <85% 逐格前沿分配，>=85% 区域块聚类收尾。为每辆空闲小车选择最优探索目标 |
| **strategy-supervisor** | `car-homework-strategy-supervisor` | `StrategySupervisorMain` | **路线监督**：检测多车路线重叠与绕路，用加权路径（偏好未探索区域）替换低效路线，提升全局探索效率 |
| **task-configurator** | `car-homework-task-configurator` | `TaskConfiguratorMain` | **任务初始化**：生成地图障碍物、计算密封区（不可达区域）、分配小车出生点、写入 TaskConfig 到 Redis。必须最先启动 |

### 入口与展示

| 模块 | Maven Artifact | 入口类 | 职责 |
|------|---------------|--------|------|
| **display** | `car-homework-display` | `DisplayMain` | **前端服务**：内嵌 HTTP 服务器（8887）提供静态页面，WebSocket 服务器（8888）实时推送仿真帧。支持动态添加小车、仿真回放协调、地图交互 |
| **launcher** | `car-homework-launcher` | `LauncherMain` | **一键启动器**：按依赖顺序启动全部模块进程并管理生命周期，支持优雅关闭 |

### 模块依赖关系

```
common  ←──  controller
  ↑         (调度大脑，依赖 common 访问黑板和消息总线)
  │
  ├── car          (小车执行端)
  ├── navigator    (路径规划)
  ├── target-planner  (目标分配)
  ├── strategy-supervisor  (路线监督)
  ├── task-configurator   (任务初始化)
  ├── display      (前端服务)
  └── launcher     (一键启动器)
```

---

## 核心特性

- **黑板架构**：Redis 共享状态 + RabbitMQ 消息解耦，模块间零直接代码依赖，符合黑板模式三要素
- **5 态状态机**：IDLE / WAITING_ROUTE / READY / MOVING / BLOCKED，Controller 统一按节拍驱动全场车辆状态流转
- **双路径算法**：BFS（最短路径保证，适合开阔区域）+ A*（启发式搜索，适合障碍物密集区），前端实时切换
- **两阶段探索策略**：探索率 <85% 逐格前沿贪心分配，>=85% 区域块聚类收尾，避免车辆绕远路
- **路线监督优化**：检测多车路线重叠与绕路，用加权路径（偏好未探索区域）替换低效路线
- **分布式设计**：各模块可独立部署在不同机器，通过 `deploy/infra.local.json` 指定远程中间件地址
- **动态添加小车**：运行时可点击添加新小车，自动选择最优出生点并注册到系统
- **仿真回放**：完整记录探索事件与车辆轨迹，支持历史场次回放查看
- **统计分析**：步数有效率、探索覆盖率、均衡度评分、多场次对比、JSON 导入导出
- **Unity 3D 视图**：可切换 3D 视角观察地图与小车的实时状态（Unity WebGL 通过 iframe 嵌入）
- **用户认证**：登录/注册/会话管理，基于 SQL Server + jBCrypt，角色权限控制

---

## 文档索引

### 01-总体设计

| 文档 | 路径 | 说明 |
|------|------|------|
| 项目总体设计文档 | [`文档/01-总体设计/项目总体设计文档.md`](./docs/01-总体设计/项目总体设计文档.md) | 完整架构设计、核心组件、数据结构、关键流程、部署架构 |

### 02-人员设计

| 文档 | 路径 | 负责人员 |
|------|------|----------|
| Person A 设计文档 | [`docs/02-人员设计/PersonA-common+controller设计文档.md`](./docs/02-人员设计/PersonA-common+controller设计文档.md) | Person A — common + controller |
| Person B 设计文档 | [`docs/02-人员设计/PersonB-car+auth设计文档.md`](./docs/02-人员设计/PersonB-car+auth设计文档.md) | Person B — car + 认证模块 |
| Person C 设计文档 | [`docs/02-人员设计/PersonC-规划模块设计文档.md`](./docs/02-人员设计/PersonC-规划模块设计文档.md) | Person C — navigator + target-planner + task-configurator |
| Person D 设计文档 | [`docs/02-人员设计/PersonD-display+frontend设计文档.md`](./docs/02-人员设计/PersonD-display+frontend设计文档.md) | Person D — display + launcher |

### 03-模块设计

| 文档 | 路径 | 说明 |
|------|------|------|
| Common 模块设计 | [`文档/03-模块设计/Common模块设计文档.md`](./docs/03-模块设计/Common模块设计文档.md) | 公共库设计与 API |
| Controller 模块设计 | [`文档/03-模块设计/Controller模块设计文档.md`](./docs/03-模块设计/Controller模块设计文档.md) | 调度器详细设计 |
| Car 模块设计 | [`文档/03-模块设计/Car模块设计文档.md`](./docs/03-模块设计/Car模块设计文档.md) | 小车状态机与移动执行 |
| Navigator 模块设计 | [`文档/03-模块设计/Navigator模块设计文档.md`](./docs/03-模块设计/Navigator模块设计文档.md) | 路径规划算法设计 |
| TargetPlanner 模块设计 | [`文档/03-模块设计/TargetPlanner模块设计文档.md`](./docs/03-模块设计/TargetPlanner模块设计文档.md) | 目标分配策略设计 |
| StrategySupervisor 模块设计 | [`文档/03-模块设计/StrategySupervisor模块设计文档.md`](./docs/03-模块设计/StrategySupervisor模块设计文档.md) | 路线监督优化设计 |
| TaskConfigurator 模块设计 | [`文档/03-模块设计/TaskConfigurator模块设计文档.md`](./docs/03-模块设计/TaskConfigurator模块设计文档.md) | 任务初始化设计 |
| Display 模块设计 | [`文档/03-模块设计/Display模块设计文档.md`](./docs/03-模块设计/Display模块设计文档.md) | 前端服务与 WebSocket 推送 |

### 04-启动部署

| 文档 | 路径 | 说明 |
|------|------|------|
| 启动部署指南 | [`文档/04-启动部署/启动部署指南.md`](./docs/04-启动部署/启动部署指南.md) | 本地与分布式启动完整步骤、环境准备、故障排查 |

---

## 团队与分工

| 角色 | Git 分支 | 负责模块 | 核心工作 |
|------|----------|----------|----------|
| **Person A** | — | common + controller | 公共库、调度核心、分布式 CLI、联调集成、统计 UI 增强 |
| **Person B** | — | car | 小车执行端、状态机、移动执行、步数统计、认证模块 |
| **Person C** | — | navigator + target-planner + task-configurator | 路径规划、贪心目标分配、地图初始化 |
| **Person D** | — | display + launcher | 前端页面、WebSocket 推送、一键启动器、动态添加小车 |

**各角色机器需求速查**：

| 需要安装的软件 | Person A | Person B | Person C | Person D |
|---------------|:--------:|:--------:|:--------:|:--------:|
| JDK 17 + 项目源码 | ✅ | ✅ | ✅ | ✅ |
| Docker Desktop | ✅ | ❌ | ❌ | ❌ |
| SQL Server | ❌ | ❌ | ❌ | ✅ |

> **关键**：Person B 和 Person C **只需要 JDK + 源码**，不需要 Docker、Redis、RabbitMQ、SQL Server。所有中间件通过 `infra.local.json` 配置 Person A/D 的 IP 远程连接。细节参见 [启动部署指南](./docs/04-启动部署/启动部署指南.md#32-各角色机器需要安装什么)。

**主分支**：`main` — 集成与发布分支，所有功能分支合并至此。

---

## 项目结构

```
substation-patrol/
├── pom.xml                          # 父 POM（聚合 9 模块 + 依赖管理）
├── docker-compose.yml               # Redis + RabbitMQ 容器编排
├── mvnw / mvnw.cmd / mvnw.ps1       # Maven Wrapper（无需安装 Maven）
├── start_all.bat                    # Windows 一键启动脚本
├── deploy/
│   └── infra.local.json             # 分布式部署配置文件示例
├── common/                          # 公共库（所有模块唯一依赖）
│   └── src/main/java/com/substation/common/
│       ├── redis/BlackboardClient.java      # Redis 黑板操作
│       ├── mq/MessageBus.java               # RabbitMQ 收发封装
│       ├── model/                           # Point / CarStatus / RouteStep 等共享模型
│       ├── auth/                            # 用户认证与会话管理
│       ├── analysis/                        # 统计分析引擎
│       ├── replay/                          # 仿真回放与归档
│       ├── sql/                             # SQL Server 数据访问
│       ├── map/                             # 地图算法（前沿搜索 / 聚类 / 加权路径）
│       └── infra/                           # 分布式配置加载
├── controller/                      # 调度器
│   └── src/main/java/.../
│       ├── TickScheduler.java       # 节拍驱动（500ms）
│       ├── StatusDispatcher.java    # 状态收集与分派
│       └── CommandHandler.java      # 消息路由中转
├── car/                             # 小车执行端
│   └── src/main/java/.../
│       ├── CarMain.java             # 入口 + 启动参数解析
│       ├── CarAgent.java            # 5 态状态机
│       └── MoveExecutor.java        # 逐步移动 + 碰撞检测
├── navigator/                       # 路径规划
│   └── src/main/java/.../
│       └── NavigatorMain.java       # BFS / A* 算法调度
├── target-planner/                  # 目标分配
│   └── src/main/java/.../
│       └── TargetPlannerMain.java   # 两阶段贪心前沿探索
├── strategy-supervisor/             # 路线监督
│   └── src/main/java/.../
│       └── StrategySupervisorMain.java  # 重叠检测 + 加权路径替换
├── task-configurator/               # 任务初始化
│   └── src/main/java/.../
│       └── TaskConfiguratorMain.java    # 地图生成 + 密封区计算 + 出生点分配
├── display/                         # 前端服务
│   └── src/main/java/.../
│       └── DisplayMain.java         # HTTP:8887 + WebSocket:8888
├── launcher/                        # 一键启动器
│   └── src/main/java/.../
│       └── LauncherMain.java        # 按序启动全部模块
└── docs/                            # 项目文档
    ├── 01-总体设计/
    ├── 02-人员设计/
    ├── 03-模块设计/
    └── 04-启动部署/
```

---

## License

MIT License — 详见 [LICENSE](./LICENSE)。
