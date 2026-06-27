package com.substation.display;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.substation.common.infra.DeployConfigLoader;
import com.substation.common.model.CarStatus;
import com.substation.common.model.Point;
import com.substation.common.model.SimulationState;
import com.substation.common.mq.QueueNames;
import com.substation.common.redis.BlackboardClient;
import com.substation.common.redis.MapBitmapSnapshot;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Base64;

/**
 * WebSocket 桥接器 —— Display 模块的核心。
 *
 * <h3>两大方向</h3>
 * <ol>
 *   <li><b>下行（Java → 浏览器）</b>：
 *       收到 DisplayMain 的触发 → 读取 Redis 黑板全部数据 →
 *       构建 {@link SimulationState} → JSON 序列化 → 广播给所有浏览器</li>
 *   <li><b>上行（浏览器 → Java）</b>：
 *       接收浏览器 WebSocket 消息 → 原样转发到 RabbitMQ ControllerCmd 队列</li>
 * </ol>
 *
 * <h3>关于状态颜色</h3>
 * <p>{@link CarStatus} 枚举自带颜色值，但此处不将颜色写入 JSON。
 * 前端根据 {@code status} 字段名本地查表映射——减少 JSON 传输冗余。</p>
 *
 * <h3>关于动态车辆编号</h3>
 * <p>不预设车辆数量。通过 {@link BlackboardClient#discoverCarIds()} 动态发现，
 * 遍历结果集构建 {@link SimulationState.CarInfo} 列表。</p>
 *
 * @author Person D
 */
public class WebSocketBridge extends WebSocketServer {

    private static final Logger LOG = LoggerFactory.getLogger(WebSocketBridge.class);

    /** 地图默认尺寸（TaskConfig 未设置时回退） */
    private static final int DEFAULT_W = BlackboardClient.DEFAULT_WIDTH;
    private static final int DEFAULT_H = BlackboardClient.DEFAULT_HEIGHT;
    /** 车辆 ID 前缀长度，"Car" 占 3 个字符 */
    private static final int CAR_PREFIX_LENGTH = 3;
    /** 超过此格数时改用 Base64 位图推送，避免 boolean[][] JSON 过大 */
    private static final int COMPACT_MAP_CELL_THRESHOLD = 2500;

    // ────────────────── 依赖 ──────────────────

    /** Redis 黑板读取客户端（只读） */
    private final BlackboardClient blackboard;

    /** MQ 消息发送适配器（转发浏览器命令到 Controller） */
    private final MqSender mqSender;

    /** 当前连接的浏览器客户端集合（线程安全） */
    private final Set<WebSocket> clients = ConcurrentHashMap.newKeySet();

    /** deploy/infra.local.json 中由其他机器启动的小车（如 Person B） */
    private final Set<String> externalProcessCarIds;

    /** 本 Display 已通过 ADD_CAR 启动过进程的小车 */
    private final Set<String> displayLaunchedCarIds = ConcurrentHashMap.newKeySet();

    /** 防止并发 ADD_CAR 分配到重复 CarId */
    private final Object addCarLock = new Object();

    /** 启动动态小车前执行（如清空 MQ 队列） */
    private java.util.function.Consumer<String> beforeCarLaunch = carId -> {};

    // ────────────────── 构造 ──────────────────

    /**
     * @param port       WebSocket 监听端口（默认 8888）
     * @param blackboard Redis 黑板客户端
     * @param mqSender   MQ 发送适配器，用于转发浏览器命令到 ControllerCmd
     */
    public WebSocketBridge(int port, BlackboardClient blackboard, MqSender mqSender) {
        super(new InetSocketAddress(port));
        this.blackboard = blackboard;
        this.mqSender = mqSender;
        this.externalProcessCarIds = Set.copyOf(
                DeployConfigLoader.loadOptional()
                        .map(config -> config.cars())
                        .orElse(List.of()));
        DynamicCarLauncher.setProcessExitListener(this::onDynamicCarProcessExit);
    }

    // ════════════════════════════════════════════════════════════════
    // WebSocket 生命周期回调（由父类 WebSocketServer 驱动）
    // ════════════════════════════════════════════════════════════════

    /** 浏览器连接建立 */
    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        clients.add(conn);
        LOG.info("浏览器连接: {}", conn.getRemoteSocketAddress());
    }

    /** 浏览器连接断开 */
    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        clients.remove(conn);
        LOG.info("浏览器断开: {}", conn.getRemoteSocketAddress());
    }

    /**
     * 收到浏览器发来的消息。
     *
     * <p>不解析消息内容，直接原样转发到 ControllerCmd 队列。
     * 支持的消息类型由 Controller 的 CommandHandler 负责识别和分发：</p>
     * <ul>
     *   <li>SET_CONFIG —— 开始任务</li>
     *   <li>RESET —— 重置</li>
     *   <li>TOGGLE_PAUSE —— 暂停/继续</li>
     *   <li>SET_TICK_INTERVAL —— 调速</li>
     * </ul>
     *
     * @param conn    发送消息的浏览器连接
     * @param message 原始 JSON 字符串
     */
    /** 操作日志存储（SQL Server，可选） */
    private com.substation.common.sql.OperationLogStore operationLogStore;

    /** 场次归档与历史回放 */
    private ReplayCoordinator replayCoordinator;

    /** 最近一次广播的 tick，用于加车后主动刷新 */
    private volatile int lastBroadcastTick;

    public void setOperationLogStore(com.substation.common.sql.OperationLogStore store) {
        this.operationLogStore = store;
    }

    public void setReplayCoordinator(ReplayCoordinator coordinator) {
        this.replayCoordinator = coordinator;
    }

    void setBeforeCarLaunch(java.util.function.Consumer<String> beforeCarLaunch) {
        this.beforeCarLaunch = beforeCarLaunch != null ? beforeCarLaunch : carId -> {};
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            JSONObject msg = JSON.parseObject(message);
            String type = msg.getString("type");

            if ("ADD_CAR".equals(type)) {
                handleAddCar();
            } else if ("REQUEST_REPLAY".equals(type)) {
                handleReplayRequest(conn, msg);
            } else {
                if ("RESET".equals(type) || "SET_CONFIG".equals(type)) {
                    stopAllDynamicCars();
                }
                if (replayCoordinator != null && "SET_CONFIG".equals(type)) {
                    replayCoordinator.beforeSimCommand(type, msg.getJSONObject("data"));
                }
                mqSender.send(QueueNames.CONTROLLER_CMD, message);
            }
        } catch (Exception e) {
            LOG.warn("消息处理失败: {}", message, e);
        }
    }

    private void handleReplayRequest(WebSocket conn, JSONObject msg) {
        if (replayCoordinator == null) {
            conn.send("{\"type\":\"REPLAY_ERROR\",\"error\":\"回放服务未就绪\"}");
            return;
        }
        Long runId = msg.getLong("runId");
        if (runId != null && runId > 0) {
            replayCoordinator.sendStoredReplay(conn, runId);
            return;
        }
        replayCoordinator.sendLiveReplay(conn);
    }

    private void handleAddCar() {
        synchronized (addCarLock) {
            pruneDeadLaunches();
            String carId = resolveNextCarId();
            Path projectRoot = Path.of(".").toAbsolutePath().normalize();

            broadcastEvent("CAR_PENDING", Map.of("carId", carId));

            if (!DynamicCarLauncher.isLaunchAvailable(projectRoot)) {
                String reason = "未找到 car JAR，请先执行: .\\mvnw.cmd package -pl car -am -DskipTests";
                LOG.error("动态添加小车失败 {}: {}", carId, reason);
                broadcastEvent("CAR_LAUNCH_FAILED", Map.of("carId", carId, "reason", reason));
                return;
            }

            if (DynamicCarLauncher.isProcessAlive(carId)) {
                String reason = carId + " 进程已在运行，请勿重复添加";
                LOG.warn("动态添加小车跳过: {}", reason);
                broadcastEvent("CAR_LAUNCH_FAILED", Map.of("carId", carId, "reason", reason));
                return;
            }

            try {
                displayLaunchedCarIds.add(carId);
                DynamicCarLauncher.launchAsync(
                        carId,
                        projectRoot,
                        () -> beforeCarLaunch.accept(carId),
                        this::onCarLaunched,
                        this::onCarLaunchFailed);
                LOG.info("动态添加小车: {} 地图={}×{}", carId,
                    blackboard.getMapWidth(), blackboard.getMapHeight());
            } catch (IllegalStateException e) {
                onCarLaunchFailed(carId, e);
            }
        }
    }

    private void onCarLaunched(String carId) {
        LOG.info("动态小车进程已就绪: {}", carId);
        broadcastEvent("CAR_LAUNCHED", Map.of("carId", carId));
        pushSimulationState(lastBroadcastTick, resolveExplorationRate());
    }

    private void onCarLaunchFailed(String carId, Throwable error) {
        displayLaunchedCarIds.remove(carId);
        String reason = error != null && error.getMessage() != null ? error.getMessage() : "启动失败";
        LOG.warn("动态添加小车失败: {}", carId, error);
        broadcastEvent("CAR_LAUNCH_FAILED", Map.of("carId", carId, "reason", reason));
    }

    private void broadcastEvent(String eventType, Map<String, String> payload) {
        JSONObject json = new JSONObject();
        json.put("type", eventType);
        payload.forEach(json::put);
        broadcast(json.toJSONString());
    }

    private String resolveNextCarId() {
        return DynamicCarIdResolver.resolve(
                blackboard.discoverCarIds(),
                externalProcessCarIds,
                displayLaunchedCarIds,
                DynamicCarLauncher::isProcessAlive);
    }

    private void pruneDeadLaunches() {
        displayLaunchedCarIds.removeIf(carId -> !DynamicCarLauncher.isProcessAlive(carId));
    }

    private void onDynamicCarProcessExit(String carId) {
        displayLaunchedCarIds.remove(carId);
    }

    private void stopAllDynamicCars() {
        DynamicCarProcessKiller.killAllDynamicExcept(externalProcessCarIds);
        for (String carId : Set.copyOf(displayLaunchedCarIds)) {
            DynamicCarLauncher.stopProcess(carId);
        }
        displayLaunchedCarIds.clear();
    }

    /** WebSocket 异常 */
    @Override
    public void onError(WebSocket conn, Exception ex) {
        LOG.error("WebSocket 错误: {}", ex.getMessage());
    }

    /** 服务启动完成 */
    @Override
    public void onStart() {
        LOG.info("WebSocket 服务已启动，端口: {}", getPort());
    }

    // ════════════════════════════════════════════════════════════════
    // 下行：读取黑板 → 构建快照 → 推浏览器
    // ════════════════════════════════════════════════════════════════

    /**
     * 构建当前全局状态快照并推送给所有浏览器。
     *
     * <p>由 {@code DisplayMain.onRefreshAllReceived()} 调用，
     * 触发时机是每次收到 Controller 的 REFRESH_ALL 广播。</p>
     *
     * <p>如果当前没有浏览器连接，直接跳过黑板读取——避免无效 I/O。</p>
     *
     * @param tick            当前节拍号
     * @param explorationRate 当前探索率（0-100）
     */
    public void pushSimulationState(int tick, int explorationRate) {
        if (clients.isEmpty()) {
            return;
        }
        lastBroadcastTick = tick;
        int syncedRate = resolveExplorationRate();
        if (tick % 20 == 0 || tick == 1 || syncedRate >= 100) {
            LOG.info("pushState tick={} rate={}%", tick, syncedRate);
        }
        try {
            broadcast(serializeState(tick, syncedRate));
        } catch (RuntimeException e) {
            LOG.error("推送仿真状态失败 tick={}", tick, e);
        }
    }

    /** 与 mapView 同源：从黑板实时读取探索率，避免 MQ 消息滞后于 Redis 位图 */
    private int resolveExplorationRate() {
        if (blackboard.isExplorationComplete()) {
            return 100;
        }
        return blackboard.getExplorationRate();
    }

    private String resolveRunStartedBy() {
        return blackboard.getSimRunStartedBy().orElse("");
    }

    private String serializeState(int tick, int explorationRate) {
        Map<String, String> config = blackboard.getTaskConfig();
        int mapWidth = parseIntOrDefault(config.get("mapWidth"), DEFAULT_W);
        int mapHeight = parseIntOrDefault(config.get("mapHeight"), DEFAULT_H);
        MapBitmapSnapshot mapBitmaps = blackboard.readMapBitmapSnapshot();
        List<SimulationState.CarInfo> cars = buildCarInfoList();
        String runStartedBy = resolveRunStartedBy();

        if (mapWidth * mapHeight <= COMPACT_MAP_CELL_THRESHOLD) {
            SimulationState state = new SimulationState(
                tick, explorationRate, config, cars,
                toBitmap(mapBitmaps.mapView(), mapWidth, mapHeight),
                toBitmap(mapBitmaps.mapBlock(), mapWidth, mapHeight),
                toBitmap(mapBitmaps.mapSealed(), mapWidth, mapHeight),
                runStartedBy);
            return JSON.toJSONString(state);
        }

        JSONObject json = new JSONObject();
        json.put("tick", tick);
        json.put("explorationRate", explorationRate);
        json.put("taskConfig", config);
        json.put("cars", cars);
        json.put("runStartedBy", runStartedBy);
        json.put("mapViewB64", Base64.getEncoder().encodeToString(mapBitmaps.mapView()));
        json.put("mapBlockB64", Base64.getEncoder().encodeToString(mapBitmaps.mapBlock()));
        json.put("mapSealedB64", Base64.getEncoder().encodeToString(mapBitmaps.mapSealed()));
        return json.toJSONString();
    }

    private static boolean[][] toBitmap(byte[] bytes, int mapWidth, int mapHeight) {
        return BlackboardClient.bytesToBitmap(bytes, mapWidth, mapHeight);
    }

    private List<SimulationState.CarInfo> buildCarInfoList() {
        List<SimulationState.CarInfo> cars = new ArrayList<>();
        for (String carId : discoverActiveCarIds()) {
            buildSingleCarInfo(carId).ifPresent(cars::add);
        }
        cars.sort(Comparator.comparingInt(SimulationState.CarInfo::number));
        return cars;
    }

    private Optional<SimulationState.CarInfo> buildSingleCarInfo(String carId) {
        Optional<Point> positionOpt = blackboard.getCarPosition(carId);
        if (positionOpt.isEmpty()) {
            return Optional.empty();
        }

        int number = extractCarNumber(carId);
        Point target = blackboard.getCarTarget(carId).orElse(null);
        List<Point> route = blackboard.getCarRoute(carId);
        CarStatus status = blackboard.getCarStatus(carId).orElse(CarStatus.IDLE);
        int steps = blackboard.getCarSteps(carId);
        int effectiveSteps = blackboard.getCarEffectiveSteps(carId);

        return Optional.of(new SimulationState.CarInfo(
                carId, number, positionOpt.get(), target, route, status, steps, effectiveSteps));
    }

    private Set<String> discoverActiveCarIds() {
        return blackboard.discoverCarIds();
    }

    /**
     * 从 carId 中提取显示编号。
     *
     * <p>示例: {@code "Car001"} → {@code 1}, {@code "Car012"} → {@code 12}</p>
     */
    static int extractCarNumber(String carId) {
        if (carId == null || carId.length() <= CAR_PREFIX_LENGTH) {
            return 0;
        }
        try {
            return Integer.parseInt(carId.substring(CAR_PREFIX_LENGTH));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ────────────────── 工具方法 ──────────────────

    /**
     * 安全地将字符串解析为 int，解析失败时返回默认值。
     *
     * <p>用于处理 TaskConfig Hash 中的数值字段（mapWidth、mapHeight 等），
     * 这些字段在 TaskConfigurator 未初始化时为 null。</p>
     */
    static int parseIntOrDefault(String value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 内部接口
    // ════════════════════════════════════════════════════════════════

    /**
     * MQ 消息发送适配器。
     *
     * <p>由 DisplayMain 在构造时注入实现（委托给 MessageBus.publish()）。
     * WebSocketBridge 不直接依赖 MessageBus，只依赖此抽象——遵循依赖倒置原则。</p>
     */
    @FunctionalInterface
    public interface MqSender {
        /**
         * @param queue   目标队列名称（如 "ControllerCmd"）
         * @param message 消息体（JSON 字符串）
         */
        void send(String queue, String message);
    }
}
