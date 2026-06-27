package com.substation.display;

import com.alibaba.fastjson2.JSONObject;
import com.substation.common.admin.AdminApiHandler;
import com.substation.common.analysis.AnalysisApiHandler;
import com.substation.common.analysis.SimulationRecordService;
import com.substation.common.analysis.SimulationStatsStore;
import com.substation.common.auth.AuthApiHandler;
import com.substation.common.auth.SessionManager;
import com.substation.common.infra.DeployConfig;
import com.substation.common.infra.DeployConfigLoader;
import com.substation.common.mq.MessageBus;
import com.substation.common.mq.QueueNames;
import com.substation.common.redis.BlackboardClient;
import com.substation.common.replay.ReplayApiHandler;
import com.substation.common.replay.RunArchiver;
import com.substation.common.replay.SimulationRunStore;
import com.substation.common.sql.DatabaseManager;
import com.substation.common.sql.OperationLogStore;
import com.substation.common.sql.RegistrationStore;
import com.substation.common.sql.SqlUserStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeoutException;

/**
 * Display 模块入口（SQL Server 版）。
 * 初始化所有组件：黑板、MQ、认证、分析、管理员 API、WebSocket、HTTP。
 */
public class DisplayMain {

    private static final Logger LOG = LoggerFactory.getLogger(DisplayMain.class);
    private static final int DEFAULT_MAP_SIZE = BlackboardClient.DEFAULT_WIDTH;
    private static final int DEFAULT_HTTP_PORT = 8887;
    private static final int DEFAULT_WS_PORT = 8888;

    private final BlackboardClient blackboard;
    private final MessageBus messageBus;
    private final WebSocketBridge wsBridge;
    private final HttpFileServer httpServer;
    private final int httpPort;

    public DisplayMain(String redisHost, int redisPort,
                       String mqHost, int mqPort,
                       int httpPort, int wsPort,
                       Path webRoot, DeployConfig deployConfig) throws IOException {
        this.httpPort = httpPort;
        this.blackboard = new BlackboardClient(redisHost, redisPort, DEFAULT_MAP_SIZE, DEFAULT_MAP_SIZE);
        this.messageBus = new MessageBus(mqHost, mqPort, "guest", "guest");

        // —— SQL Server 初始化（从 infra.local.json 读取数据库配置） ——
        DatabaseManager db = deployConfig != null
                ? new DatabaseManager(deployConfig)
                : new DatabaseManager();
        db.initDatabase();
        SqlUserStore sqlUserStore = new SqlUserStore(db);
        RegistrationStore regStore = new RegistrationStore(db);
        OperationLogStore logStore = new OperationLogStore(db);
        SessionManager sessionManager = new SessionManager(blackboard.getJedisPool());
        AuthApiHandler authApi = new AuthApiHandler(sqlUserStore, regStore, logStore, sessionManager);
        AdminApiHandler adminApi = new AdminApiHandler(sqlUserStore, regStore, logStore);
        SimulationStatsStore statsStore = new SimulationStatsStore(db);
        SimulationRunStore runStore = new SimulationRunStore(db);
        RunArchiver runArchiver = new RunArchiver(runStore);
        SimulationRecordService recordService = new SimulationRecordService(
                blackboard, runArchiver, runStore, statsStore);
        AnalysisApiHandler analysisApi = new AnalysisApiHandler(
                statsStore, runStore, recordService, sessionManager);
        ReplayApiHandler replayApi = new ReplayApiHandler(runStore);

        WebSocketBridge.MqSender mqSender = (queue, message) -> {
            try { messageBus.publish(queue, message); }
            catch (IOException e) { LOG.error("MQ 发送失败: queue={}", queue, e); }
        };

        this.wsBridge = new WebSocketBridge(wsPort, blackboard, mqSender);
        this.wsBridge.setBeforeCarLaunch(carId -> prepareCarQueue(carId));
        this.wsBridge.setOperationLogStore(logStore);
        ReplayCoordinator replayCoordinator = new ReplayCoordinator(blackboard, runStore);
        this.wsBridge.setReplayCoordinator(replayCoordinator);
        this.httpServer = new HttpFileServer(
                httpPort, webRoot, authApi, analysisApi, adminApi, replayApi, sessionManager);

        LOG.info("DisplayMain 初始化完成");
        LOG.info("  Redis:  {}:{}", redisHost, redisPort);
        LOG.info("  MQ:     {}:{}", mqHost, mqPort);
        LOG.info("  HTTP:   port={}", httpPort);
        LOG.info("  WS:     port={}", wsPort);
        LOG.info("  管理员: admin/admin123");
    }

    public void start() throws IOException, TimeoutException {
        messageBus.connect();
        messageBus.declareFanoutExchange();
        messageBus.declareControllerQueue();
        String fanoutQueueName = messageBus.bindFanoutQueue();
        messageBus.subscribe(fanoutQueueName, this::onRefreshAllReceived);
        wsBridge.start();
        httpServer.start();
        LOG.info("========== Display 模块启动完成 ==========");
        LOG.info("  http://localhost:{}/login.html", httpPort);
    }

    public void stop() {
        try { wsBridge.stop(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        httpServer.stop();
        messageBus.close();
        blackboard.close();
    }

    private void prepareCarQueue(String carId) {
        try {
            messageBus.declareCarQueue(carId);
            messageBus.purgeQueue(QueueNames.carQueue(carId));
        } catch (IOException e) {
            LOG.warn("准备小车队列失败: {}", carId, e);
        }
    }

    private void onRefreshAllReceived(String rawMessage) {
        try {
            JSONObject mqMsg = JSONObject.parseObject(rawMessage);
            int tick = mqMsg.getIntValue("tick");
            JSONObject data = mqMsg.getJSONObject("data");
            int explorationRate = data != null ? data.getIntValue("explorationRate") : 0;
            wsBridge.pushSimulationState(tick, explorationRate);
        } catch (Exception e) {
            LOG.warn("解析 REFRESH_ALL 消息失败: {}", rawMessage, e);
        }
    }

    public static void main(String[] args) throws IOException, TimeoutException, InterruptedException {
        var infra = com.substation.common.infra.InfraConnectionConfig.resolve(args);
        var deployConfig = DeployConfigLoader.loadOptional().orElse(null);
        Path webRoot = findWebRoot();
        DisplayMain display = new DisplayMain(
                infra.redisHost(), infra.redisPort(), infra.mqHost(), infra.mqPort(),
                DEFAULT_HTTP_PORT, DEFAULT_WS_PORT, webRoot, deployConfig);
        display.start();
        Thread.currentThread().join();
    }

    static Path findWebRoot() {
        Path[] candidates = {
                Path.of("display/src/main/resources/web"),
                Path.of("src/main/resources/web"),
        };
        for (Path candidate : candidates) {
            if (candidate.toFile().isDirectory()) return candidate;
        }
        return Path.of("display/src/main/resources/web");
    }
}
