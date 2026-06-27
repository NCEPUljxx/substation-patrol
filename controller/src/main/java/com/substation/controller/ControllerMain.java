package com.substation.controller;

import com.substation.common.mq.MessageBus;
import com.substation.common.mq.QueueNames;
import com.substation.common.redis.BlackboardClient;

/** 控制器主入口，负责初始化所有组件并绑定消息监听 */
public class ControllerMain {

    private static final int MAP_W = BlackboardClient.DEFAULT_WIDTH;
    private static final int MAP_H = BlackboardClient.DEFAULT_HEIGHT;
    /** RabbitMQ 用户名 */
    private static final String MQ_USER = "guest";
    /** RabbitMQ 密码 */
    private static final String MQ_PASS = "guest";

    private final String redisHost;
    private final int redisPort;
    private final String mqHost;
    private final int mqPort;
    private BlackboardClient bb;
    private MessageBus bus;
    private TickScheduler scheduler;

    /** 供 Launcher 调用的构造函数 */
    public ControllerMain(String redisHost, int redisPort, String mqHost, int mqPort) {
        this.redisHost = redisHost;
        this.redisPort = redisPort;
        this.mqHost = mqHost;
        this.mqPort = mqPort;
    }

    /** 启动控制器：连接中间件、声明队列、绑定消息监听、注册关闭钩子 */
    public void start() throws Exception {
        bb = new BlackboardClient(redisHost, redisPort, MAP_W, MAP_H);
        if (!bb.acquireControllerLock()) {
            System.err.println("[Controller] 已有实例在运行，退出");
            System.exit(1);
        }

        bus = new MessageBus(mqHost, mqPort, MQ_USER, MQ_PASS);
        bus.connect();
        bus.declareControllerQueue();
        bus.purgeQueue(QueueNames.CONTROLLER_CMD);
        bus.declareTargetPlannerQueue();
        bus.declareNavigatorQueue();
        bus.declareTaskConfigQueue();
        bus.declareStrategySupervisorQueue();
        bus.declareFanoutExchange();

        StatusDispatcher dispatcher = new StatusDispatcher(bb, bus);
        scheduler = new TickScheduler(dispatcher);
        dispatcher.setScheduler(scheduler);
        CommandHandler handler = new CommandHandler(bus, dispatcher, scheduler);

        bus.subscribe(QueueNames.CONTROLLER_CMD, handler::handle);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduler.shutdown();
            bb.releaseControllerLock();
            bus.close();
            bb.close();
        }));

        System.out.println("[Controller] 控制器已启动，等待任务配置...");
    }

    /** 独立运行入口（使用默认 localhost 参数） */
    public static void main(String[] args) throws Exception {
        var infra = com.substation.common.infra.InfraConnectionConfig.resolve(args);
        new ControllerMain(infra.redisHost(), infra.redisPort(), infra.mqHost(), infra.mqPort()).start();
        synchronized (ControllerMain.class) {
            try {
                ControllerMain.class.wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
