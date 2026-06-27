package com.substation.common.mq;

import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * 消息总线，封装RabbitMQ的连接管理、队列/交换机声明、消息发布与订阅。
 * 实现AutoCloseable，使用完毕后需调用close释放连接和通道。
 */
public class MessageBus implements AutoCloseable {

    /** RabbitMQ主机地址 */
    private final String host;
    /** RabbitMQ端口 */
    private final int port;
    /** RabbitMQ用户名 */
    private final String username;
    /** RabbitMQ密码 */
    private final String password;
    /** RabbitMQ连接 */
    private Connection connection;
    /** RabbitMQ通道 */
    private Channel channel;

    /**
     * 构造消息总线，不立即建立连接。
     *
     * @param host     RabbitMQ主机地址
     * @param port     RabbitMQ端口
     * @param username RabbitMQ用户名
     * @param password RabbitMQ密码
     */
    public MessageBus(String host, int port, String username, String password) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
    }

    /**
     * 建立RabbitMQ连接并创建通道，开启自动恢复。
     *
     * @throws IOException      连接失败
     * @throws TimeoutException 连接超时
     */
    public void connect() throws IOException, TimeoutException {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setPort(port);
        factory.setUsername(username);
        factory.setPassword(password);
        factory.setAutomaticRecoveryEnabled(true);
        connection = factory.newConnection();
        channel = connection.createChannel();
    }

    // ==================== 队列声明 ====================

    /** 声明指定小车的持久化队列。 */
    public void declareCarQueue(String carId) throws IOException {
        channel.queueDeclare(QueueNames.carQueue(carId), true, false, false, null);
    }

    /** 声明导航器指令队列。 */
    public void declareNavigatorQueue() throws IOException {
        channel.queueDeclare(QueueNames.NAVIGATOR_CMD, true, false, false, null);
    }

    /** 声明目标规划器指令队列。 */
    public void declareTargetPlannerQueue() throws IOException {
        channel.queueDeclare(QueueNames.TARGET_PLANNER_CMD, true, false, false, null);
    }

    /** 声明任务配置指令队列。 */
    public void declareTaskConfigQueue() throws IOException {
        channel.queueDeclare(QueueNames.TASK_CONFIG_CMD, true, false, false, null);
    }

    /** 声明控制器指令队列。 */
    public void declareControllerQueue() throws IOException {
        channel.queueDeclare(QueueNames.CONTROLLER_CMD, true, false, false, null);
    }

    /**
     * 声明任意名称的持久化队列（测试或扩展模块用）。
     */
    public void declareQueue(String queueName) throws IOException {
        channel.queueDeclare(queueName, true, false, false, null);
    }

    /** 清空指定队列中尚未消费的消息。 */
    public void purgeQueue(String queueName) throws IOException {
        channel.queuePurge(queueName);
    }

    /** 声明策略监督器指令队列。 */
    public void declareStrategySupervisorQueue() throws IOException {
        channel.queueDeclare(QueueNames.STRATEGY_SUPERVISOR_CMD, true, false, false, null);
    }

    /** 声明视图更新Fanout交换机。 */
    public void declareFanoutExchange() throws IOException {
        channel.exchangeDeclare(QueueNames.UPDATE_VIEW_EXCHANGE, BuiltinExchangeType.FANOUT, true);
    }

    /**
     * 声明一个临时匿名队列并绑定到Fanout交换机，用于接收视图更新广播。
     *
     * @return 临时队列名称
     * @throws IOException 声明失败
     */
    public String bindFanoutQueue() throws IOException {
        String queueName = channel.queueDeclare().getQueue();
        channel.queueBind(queueName, QueueNames.UPDATE_VIEW_EXCHANGE, "");
        return queueName;
    }

    // ==================== 发布 ====================

    /**
     * 向指定队列发送消息（点对点模式）。
     *
     * @param queueName 目标队列名
     * @param message   消息体（JSON字符串）
     * @throws IOException 发送失败
     */
    public void publish(String queueName, String message) throws IOException {
        channel.basicPublish("", queueName, null, message.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 向指定交换机广播消息（Fanout模式）。
     *
     * @param exchangeName 交换机名称
     * @param message      消息体（JSON字符串）
     * @throws IOException 发送失败
     */
    public void publishFanout(String exchangeName, String message) throws IOException {
        channel.basicPublish(exchangeName, "", null, message.getBytes(StandardCharsets.UTF_8));
    }

    // ==================== 订阅 ====================

    /**
     * 订阅指定队列的消息，自动ACK。
     *
     * @param queueName 队列名称
     * @param handler   消息处理器，接收消息体字符串
     * @throws IOException 订阅失败
     */
    public void subscribe(String queueName, Consumer<String> handler) throws IOException {
        DeliverCallback callback = (consumerTag, delivery) -> {
            String msg = new String(delivery.getBody(), StandardCharsets.UTF_8);
            handler.accept(msg);
        };
        channel.basicConsume(queueName, true, callback, consumerTag -> {});
    }

    // ==================== 连接管理 ====================

    /**
     * 判断RabbitMQ连接是否已建立且处于打开状态。
     *
     * @return true表示已连接
     */
    public boolean isConnected() {
        return connection != null && connection.isOpen();
    }

    /** 关闭通道和连接，释放资源。 */
    @Override
    public void close() {
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
            if (connection != null && connection.isOpen()) {
                connection.close();
            }
        } catch (IOException | TimeoutException e) {
            throw new UncheckedIOException(new IOException("关闭连接失败", e));
        }
    }
}
