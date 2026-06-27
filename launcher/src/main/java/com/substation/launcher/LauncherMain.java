package com.substation.launcher;

import com.substation.car.CarMain;
import com.substation.common.infra.DeployConfigLoader;
import com.substation.controller.ControllerMain;
import com.substation.display.DisplayMain;
import com.substation.navigator.NavigatorMain;
import com.substation.targetplanner.TargetPlannerMain;
import com.substation.taskconfigurator.TaskConfiguratorMain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一键启动器 —— 同进程按依赖顺序启动全部 7 个模块，替代手动逐个执行。
 *
 * <h3>启动顺序（不可调换，由数据流向决定）</h3>
 * TaskConfigurator(+500ms) → Navigator → TargetPlanner(+300ms)
 * → Car×N(+200ms/车) → Display(+300ms) → Controller(+1000ms)
 *
 * <h3>线程模型</h3>
 * 每个模块在独立非守护线程中调 {@code new XxxMain(...).start()}，
 * 触发即返回（start 内部已启动后台线程）。JVM 由各模块的非守护线程
 * 保持存活。Ctrl+C 触发逆序优雅关闭。
 *
 * <h3>已完成模块</h3>
 * controller、display 正常启动；car、navigator、target-planner、
 * task-configurator 开发中，打印 WARN 跳过。
 *
 * @author Person D
 */
public final class LauncherMain {

    private static final Logger LOG = LoggerFactory.getLogger(LauncherMain.class);

    // 默认连接参数
    private static final String DEFAULT_REDIS_HOST = "localhost";
    private static final int DEFAULT_REDIS_PORT = 6379;
    private static final String DEFAULT_MQ_HOST = "localhost";
    private static final int DEFAULT_MQ_PORT = 5672;
    private static final int DEFAULT_CAR_COUNT = 5;
    private static final int DEFAULT_HTTP_PORT = 8887;
    private static final int DEFAULT_WS_PORT = 8888;

    // 启动间隔（毫秒）：确保上游模块完成初始化后下游才启动
    private static final int DELAY_AFTER_TASK_CONFIG_MS = 500;
    private static final int DELAY_AFTER_TARGET_PLANNER_MS = 300;
    private static final int DELAY_BETWEEN_CARS_MS = 200;
    private static final int DELAY_AFTER_DISPLAY_MS = 300;
    private static final int DELAY_AFTER_CONTROLLER_MS = 1_000;

    // 前端 web 目录查找顺序（支持从项目根或模块子目录运行）
    private static final List<Path> WEB_ROOT_CANDIDATES = List.of(
            Path.of("display/src/main/resources/web"),
            Path.of("src/main/resources/web"));

    private static final int EXIT_ARG_ERROR = 1;

    /**
     * 允许受检异常的 Runnable，专为模块 start() 设计。
     * 标准库 {@link Runnable} 不支持 checked exception。
     */
    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    // 关闭钩子注册表（按启动顺序存储，shutdown 时逆序调用 close()）
    private static final List<AutoCloseable> SHUTDOWN_HOOKS = new ArrayList<>();
    private static final AtomicBoolean SHUTDOWN_HOOK_REGISTERED = new AtomicBoolean(false);

    /**
     * 不可变启动配置，由命令行参数解析而来。
     */
    record LaunchConfig(
            String redisHost, int redisPort,
            String mqHost, int mqPort,
            int carCount, int httpPort, int wsPort) {

        static LaunchConfig defaults() {
            return new LaunchConfig(DEFAULT_REDIS_HOST, DEFAULT_REDIS_PORT,
                    DEFAULT_MQ_HOST, DEFAULT_MQ_PORT,
                    DEFAULT_CAR_COUNT, DEFAULT_HTTP_PORT, DEFAULT_WS_PORT);
        }
    }

    // ════════════════════════════════════════════════════════════
    // 入口
    // ════════════════════════════════════════════════════════════

    public static void main(String[] args) {
        LaunchConfig config = parseArgs(args);
        printConfig(config);
        registerShutdownHook();
        launchAllModules(config);
        waitForever();
    }

    // ════════════════════════════════════════════════════════════
    // 参数解析：--key value 格式，默认值覆盖
    // ════════════════════════════════════════════════════════════

    static LaunchConfig parseArgs(String[] args) {
        if (args.length == 0) {
            return LaunchConfig.defaults();
        }

        String rh = DEFAULT_REDIS_HOST, mh = DEFAULT_MQ_HOST;
        int rp = DEFAULT_REDIS_PORT, mp = DEFAULT_MQ_PORT;
        int cc = DEFAULT_CAR_COUNT, hp = DEFAULT_HTTP_PORT, wp = DEFAULT_WS_PORT;

        for (int i = 0; i < args.length; i++) {
            String key = args[i];
            switch (key) {
                case "--help"       -> { printHelp(); System.exit(0); }
                case "--redis-host" -> rh = nextArg(args, ++i, key);
                case "--redis-port" -> rp = nextIntArg(args, ++i, key);
                case "--mq-host"    -> mh = nextArg(args, ++i, key);
                case "--mq-port"    -> mp = nextIntArg(args, ++i, key);
                case "--cars"       -> cc = nextIntArg(args, ++i, key);
                case "--http-port"  -> hp = nextIntArg(args, ++i, key);
                case "--ws-port"    -> wp = nextIntArg(args, ++i, key);
                default -> { LOG.error("未知参数: {}", key); printHelp(); System.exit(EXIT_ARG_ERROR); }
            }
        }
        return new LaunchConfig(rh, rp, mh, mp, cc, hp, wp);
    }

    static String nextArg(String[] args, int idx, String key) {
        if (idx >= args.length) { LOG.error("参数 {} 缺少值", key); System.exit(EXIT_ARG_ERROR); }
        return args[idx];
    }

    static int nextIntArg(String[] args, int idx, String key) {
        try {
            return Integer.parseInt(nextArg(args, idx, key));
        } catch (NumberFormatException e) {
            LOG.error("参数 {} 的值 '{}' 不是有效整数", key, args[idx]);
            System.exit(EXIT_ARG_ERROR);
            return 0;
        }
    }

    private static void printHelp() {
        System.out.println("""
                用法: java com.substation.launcher.LauncherMain [选项]
                  --redis-host H   Redis 地址 (默认 localhost)
                  --redis-port P   Redis 端口 (默认 6379)
                  --mq-host H      RabbitMQ 地址 (默认 localhost)
                  --mq-port P      RabbitMQ 端口 (默认 5672)
                  --cars N         小车数量 (默认 5)
                  --http-port P    HTTP 端口 (默认 8887)
                  --ws-port P      WebSocket 端口 (默认 8888)
                  --help           打印帮助
                """);
    }

    // ════════════════════════════════════════════════════════════
    // 启动前准备
    // ════════════════════════════════════════════════════════════

    private static void printConfig(LaunchConfig c) {
        LOG.info("========== 变电站巡检仿真系统 - 一键启动器 ==========");
        LOG.info("Redis: {}:{}  |  MQ: {}:{}  |  车辆: {}  |  HTTP: {}  |  WS: {}",
                c.redisHost, c.redisPort, c.mqHost, c.mqPort,
                c.carCount, c.httpPort, c.wsPort);
    }

    /** 注册 JVM 关闭钩子：逆序调用已启动模块的 close()/stop() */
    private static void registerShutdownHook() {
        if (!SHUTDOWN_HOOK_REGISTERED.compareAndSet(false, true)) {
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("收到终止信号，正在优雅关闭...");
            for (int i = SHUTDOWN_HOOKS.size() - 1; i >= 0; i--) {
                try {
                    SHUTDOWN_HOOKS.get(i).close();
                } catch (Exception e) {
                    LOG.warn("关闭模块失败: {}", e.getMessage());
                }
            }
            LOG.info("全部模块已关闭");
        }, "launcher-shutdown"));
    }

    // ════════════════════════════════════════════════════════════
    // 启动编排：按顺序启动，步间 sleep 确保上游初始化完成
    // ════════════════════════════════════════════════════════════

    private static void launchAllModules(LaunchConfig c) {
        startTaskConfigurator(c);
        sleepMillis(DELAY_AFTER_TASK_CONFIG_MS);

        startNavigator(c);

        startTargetPlanner(c);
        sleepMillis(DELAY_AFTER_TARGET_PLANNER_MS);

        startCarFleet(c);

        startDisplay(c);
        sleepMillis(DELAY_AFTER_DISPLAY_MS);

        startController(c);
        sleepMillis(DELAY_AFTER_CONTROLLER_MS);

        LOG.info("========== 启动序列完成 ==========");
    }

    // ── 各模块启动方法 ──
    // B/C 模块使用 main() 启动（独立进程模式），Launcher 在单独线程中调用 main()
    // Display 和 Controller 使用构造+start() 模式

    /**
     * 启动 TaskConfigurator：订阅 TaskConfigCmd 队列，处理 FORWARD_CONFIG / FORWARD_RESET。
     * main() 无参数，连接 localhost 标准端口。
     */
    private static void startTaskConfigurator(LaunchConfig c) {
        startInThread("task-configurator", () ->
                new TaskConfiguratorMain(c.redisHost(), c.redisPort(), c.mqHost(), c.mqPort()).start());
    }

    /**
     * 启动 Navigator：订阅 NavigatorCmd 队列，处理 PLAN_ROUTE 执行 BFS/A* 路径规划。
     * main() 无参数，连接 localhost 标准端口。
     */
    private static void startNavigator(LaunchConfig c) {
        startInThread("navigator", () ->
                new NavigatorMain(c.redisHost(), c.redisPort(), c.mqHost(), c.mqPort()).start());
    }

    /**
     * 启动 TargetPlanner：订阅 TargetPlannerCmd 队列，处理 ASSIGN_TARGET 分配目标点。
     * main() 无参数，连接 localhost 标准端口。
     */
    private static void startTargetPlanner(LaunchConfig c) {
        startInThread("target-planner", () ->
                new TargetPlannerMain(c.redisHost(), c.redisPort(), c.mqHost(), c.mqPort()).start());
    }

    /**
     * 启动 N 台小车，每台间隔 {@value #DELAY_BETWEEN_CARS_MS}ms 错峰启动。
     * 每台车调用 {@code CarMain.main(new String[]{carId})}，carId 格式 Car001~Car00N。
     */
    private static void startCarFleet(LaunchConfig c) {
        for (int i = 1; i <= c.carCount(); i++) {
            String carId = String.format("Car%03d", i);
            startInThread("car-" + carId, () ->
                    new CarMain(carId, c.redisHost(), c.redisPort(), c.mqHost(), c.mqPort()).start());
            if (i < c.carCount()) {
                sleepMillis(DELAY_BETWEEN_CARS_MS);
            }
        }
    }

    /**
     * 启动 Display 模块（WebSocket 桥接 + HTTP 静态服务）。
     * 使用构造+start() 模式，注册到关闭钩子。
     */
    private static void startDisplay(LaunchConfig c) {
        Path webRoot = findWebRoot();
        var deployConfig = DeployConfigLoader.loadOptional().orElse(null);
        startInThread("display", () -> {
            DisplayMain display = new DisplayMain(
                    c.redisHost, c.redisPort, c.mqHost, c.mqPort,
                    c.httpPort, c.wsPort, webRoot, deployConfig);
            display.start();
            SHUTDOWN_HOOKS.add(display::stop);
        });
    }

    /**
     * 启动 Controller 模块（节拍调度器），必须最后启动。
     * main() 无参数，连接 localhost 标准端口。
     */
    private static void startController(LaunchConfig c) {
        startInThread("controller", () ->
                new ControllerMain(c.redisHost(), c.redisPort(), c.mqHost(), c.mqPort()).start());
    }

    // ════════════════════════════════════════════════════════════
    // 工具方法
    // ════════════════════════════════════════════════════════════

    /** 在新非守护线程中执行启动任务，单模块失败不中断其余模块 */
    private static Thread startInThread(String name, ThrowingRunnable task) {
        Thread t = new Thread(() -> {
            try {
                LOG.info("  [{}] 启动中...", name);
                task.run();
                LOG.info("  [{}] 启动成功", name);
            } catch (Exception e) {
                LOG.error("  [{}] 启动失败: {}", name, e.getMessage(), e);
            }
        }, "launcher-" + name);
        t.setDaemon(false);
        t.start();
        return t;
    }

    /** 带中断保护的 sleep */
    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("启动序列被中断", e);
        }
    }

    /** 查找前端 web 目录，等价于 DisplayMain.findWebRoot()（跨包无法调用） */
    static Path findWebRoot() {
        for (Path candidate : WEB_ROOT_CANDIDATES) {
            if (candidate.toFile().isDirectory()) {
                return candidate;
            }
        }
        LOG.warn("未找到 web 目录，回退到默认路径");
        return WEB_ROOT_CANDIDATES.get(0);
    }

    /** 主线程永久阻塞，等待 Ctrl+C 或 kill 信号 */
    private static void waitForever() {
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
