package com.substation.car;

import com.substation.common.infra.InfraConnectionConfig;
import com.substation.common.map.SpawnPositionSelector;
import com.substation.common.model.CarStatus;
import com.substation.common.model.Point;
import com.substation.common.mq.MessageBus;
import com.substation.common.mq.QueueNames;
import com.substation.common.redis.BlackboardClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;

import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * Car 模块入口。
 *
 * <p>独立运行用法：
 * <pre>{@code
 * java -jar car.jar Car001              # TaskConfigurator 预注册车辆
 * java -jar car.jar Car004 --dynamic    # 页面动态添加，跳过 5 秒注册等待
 * java -jar car.jar Car002 6380         # 指定 Redis 端口
 * }</pre>
 *
 * <p>Launcher 调用方式：
 * <pre>{@code
 * new CarMain("Car001", "localhost", 6379, "localhost", 5672).start();
 * }</pre>
 */
public class CarMain {

    private static final Logger log = LoggerFactory.getLogger(CarMain.class);

    private static final int FALLBACK_W = BlackboardClient.DEFAULT_WIDTH;
    private static final int FALLBACK_H = BlackboardClient.DEFAULT_HEIGHT;
    private static final int EDGE_MARGIN = 1;
    private static final int REGISTER_WAIT_MS = 200;
    private static final int REGISTER_WAIT_ATTEMPTS = 25;
    private static final String DYNAMIC_FLAG = "--dynamic";
    private static final Random SPAWN_RANDOM = new Random();

    private final String carId;
    private final String redisHost;
    private final int redisPort;
    private final String mqHost;
    private final int mqPort;
    private final boolean dynamicAdd;
    private BlackboardClient bb;
    private MessageBus mb;

    public CarMain(String carId, String redisHost, int redisPort, String mqHost, int mqPort) {
        this(carId, redisHost, redisPort, mqHost, mqPort, false);
    }

    public CarMain(String carId, String redisHost, int redisPort, String mqHost, int mqPort,
                   boolean dynamicAdd) {
        this.carId = carId;
        this.redisHost = redisHost;
        this.redisPort = redisPort;
        this.mqHost = mqHost;
        this.mqPort = mqPort;
        this.dynamicAdd = dynamicAdd;
    }

    /** 启动小车：连接中间件、自注册、订阅 TICK_MOVE。返回不阻塞 */
    public void start() throws IOException, TimeoutException {
        bb = new BlackboardClient(redisHost, redisPort, FALLBACK_W, FALLBACK_H);

        int mapW = bb.getMapWidth();
        int mapH = bb.getMapHeight();

        selfRegister(bb, carId, mapW, mapH, dynamicAdd);

        JedisPool sharedPool = bb.getJedisPool();

        mb = new MessageBus(mqHost, mqPort, "guest", "guest");
        mb.connect();
        mb.declareCarQueue(carId);

        MoveExecutor moveExecutor = new MoveExecutor(carId, bb, mb, sharedPool);
        CarAgent agent = new CarAgent(carId, bb, moveExecutor);

        mb.subscribe(QueueNames.carQueue(carId), agent::handleMessage);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("[{}] 收到关闭信号，清理资源...", carId);
            mb.close();
            bb.close();
        }));

        log.info("[{}] 启动完成，等待 TICK_MOVE 消息", carId);
    }

    /** 独立运行入口（阻塞等待 Ctrl+C） */
    public static void main(String[] args) throws IOException, TimeoutException {
        String carId = parseCarId(args);
        var infra = InfraConnectionConfig.resolve(args);
        boolean dynamicAdd = isDynamicAdd(args);

        log.info("╔══════════════════════════════════════╗");
        log.info("║  {} 模块启动中...", padRight(carId, 24));
        log.info("║  Redis: {}:{}", padRight(infra.redisHost(), 10), infra.redisPort());
        log.info("║  RabbitMQ: {}:{}", padRight(infra.mqHost(), 10), infra.mqPort());
        log.info("╚══════════════════════════════════════╝");

        CarMain car = new CarMain(carId,
                infra.redisHost(), infra.redisPort(), infra.mqHost(), infra.mqPort(), dynamicAdd);
        car.start();

        while (car.mb != null && car.mb.isConnected()) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.info("[{}] 连接断开，退出", carId);
    }

    // ==================== CLI 参数解析 ====================

    static boolean isDynamicAdd(String[] args) {
        return Arrays.stream(args).anyMatch(DYNAMIC_FLAG::equals);
    }

    private static String parseCarId(String[] args) {
        for (String arg : args) {
            if (isOptionArg(arg)) {
                continue;
            }
            if (!arg.isBlank()) {
                String id = arg.trim();
                if (!id.startsWith("Car")) {
                    id = "Car" + id;
                }
                return id;
            }
        }
        return "Car001";
    }

    private static boolean isOptionArg(String arg) {
        return arg != null && arg.startsWith("--");
    }

    // ==================== 自注册 ====================

    static void selfRegister(BlackboardClient bb, String carId,
                              int mapWidth, int mapHeight, boolean dynamicAdd) {
        if (dynamicAdd) {
            if (bb.getCarStatus(carId).isPresent()) {
                CarStatus status = bb.getCarStatus(carId).orElse(CarStatus.IDLE);
                log.info("[{}] 动态添加：接管黑板上已有注册，原状态: {}", carId, status.chineseName());
                bb.setCarStatus(carId, CarStatus.IDLE);
                bb.clearCarTarget(carId);
                bb.clearRoute(carId);
                return;
            }
            log.info("[{}] 动态添加：跳过 TaskConfigurator 等待，直接自初始化", carId);
        } else if (awaitTaskConfiguratorRegistration(bb, carId)) {
            CarStatus status = bb.getCarStatus(carId).orElse(CarStatus.IDLE);
            log.info("[{}] 已由 TaskConfigurator 注册，状态: {}", carId, status.chineseName());
            return;
        } else {
            log.info("[{}] 未在黑板注册（等待 {}ms 后仍无记录），执行自初始化...", carId,
                REGISTER_WAIT_MS * REGISTER_WAIT_ATTEMPTS);
        }

        Optional<Point> spawn = findSpawnPosition(bb, carId, mapWidth, mapHeight);
        if (spawn.isEmpty()) {
            log.error("[{}] 无法在 {}×{} 地图内找到可用出生点", carId, mapWidth, mapHeight);
            return;
        }
        Point pos = spawn.get();
        bb.setCarPosition(carId, pos);
        bb.setCarStatus(carId, CarStatus.IDLE);
        bb.setCarSteps(carId, 0);
        bb.setCarEffectiveSteps(carId, 0);
        illuminateAndHeat(bb, pos, mapWidth, mapHeight);
        bb.appendCarHistory(carId, pos, 0);
        log.info("[{}] 自初始化完成，初始位置: ({},{})", carId, pos.x(), pos.y());
    }

    static Optional<Point> findSpawnPosition(BlackboardClient bb, String carId,
                                              int mapWidth, int mapHeight) {
        boolean[][] obstacles = bb.loadObstacleBitmap();
        boolean[][] explored = BlackboardClient.bytesToBitmap(
            bb.getMapViewBytes(), mapWidth, mapHeight);
        boolean[][] sealed = BlackboardClient.bytesToBitmap(
            bb.getMapSealedBytes(), mapWidth, mapHeight);
        boolean[][] occupied = buildOccupiedGrid(bb, carId, mapWidth, mapHeight);

        Optional<Point> interior = SpawnPositionSelector.selectBest(
            obstacles, explored, occupied, sealed, EDGE_MARGIN, SPAWN_RANDOM);
        if (interior.isPresent()) {
            return interior;
        }
        return SpawnPositionSelector.selectBest(
            obstacles, explored, occupied, sealed, 0, SPAWN_RANDOM);
    }

    private static boolean[][] buildOccupiedGrid(BlackboardClient bb, String carId,
                                                  int mapWidth, int mapHeight) {
        boolean[][] occupied = new boolean[mapHeight][mapWidth];
        Set<String> existingCarIds = bb.discoverCarIds();
        for (String otherId : existingCarIds) {
            if (otherId.equals(carId)) {
                continue;
            }
            bb.getCarPosition(otherId).ifPresent(pos -> occupied[pos.y()][pos.x()] = true);
        }
        return occupied;
    }

    private static boolean awaitTaskConfiguratorRegistration(BlackboardClient bb, String carId) {
        for (int attempt = 0; attempt < REGISTER_WAIT_ATTEMPTS; attempt++) {
            if (bb.getCarStatus(carId).isPresent()) {
                return true;
            }
            try {
                Thread.sleep(REGISTER_WAIT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return bb.getCarStatus(carId).isPresent();
            }
        }
        return bb.getCarStatus(carId).isPresent();
    }

    static void illuminateAndHeat(BlackboardClient bb, Point center,
                                   int mapWidth, int mapHeight) {
        int r = center.y(), c = center.x();
        if (r >= 0 && r < mapHeight && c >= 0 && c < mapWidth) {
            bb.recordExploration(0, r, c);
            bb.incrementMapHeat(r, c);
        }
    }

    private static String padRight(String s, int n) {
        return String.format("%-" + n + "s", s);
    }
}
