package com.substation.common.infra;

import java.util.Optional;

/**
 * Redis / RabbitMQ 连接参数，供各模块 {@code main} 解析命令行或本地配置文件。
 *
 * <p>优先级：命令行显式参数 &gt; {@code deploy/infra.local.json} &gt; localhost 默认值。
 */
public record InfraConnectionConfig(
        String redisHost,
        int redisPort,
        String mqHost,
        int mqPort) {

    public static final String DEFAULT_REDIS_HOST = "localhost";
    public static final int DEFAULT_REDIS_PORT = 6379;
    public static final String DEFAULT_MQ_HOST = "localhost";
    public static final int DEFAULT_MQ_PORT = 5672;

    public static InfraConnectionConfig localhost() {
        return new InfraConnectionConfig(
                DEFAULT_REDIS_HOST, DEFAULT_REDIS_PORT, DEFAULT_MQ_HOST, DEFAULT_MQ_PORT);
    }

    /**
     * 合并配置文件与命令行；命令行显式传入的项覆盖文件。
     */
    public static InfraConnectionConfig resolve(String[] args) {
        return resolve(args, DeployConfigLoader.loadOptional());
    }

    static InfraConnectionConfig resolve(String[] args, Optional<DeployConfig> deployConfig) {
        InfraConnectionConfig base = deployConfig
                .map(DeployConfig::toInfraConnectionConfig)
                .orElseGet(InfraConnectionConfig::localhost);
        ArgOverrides overrides = parseOverrides(args);
        return new InfraConnectionConfig(
                overrides.redisHost().orElse(base.redisHost()),
                overrides.redisPort().orElse(base.redisPort()),
                overrides.mqHost().orElse(base.mqHost()),
                overrides.mqPort().orElse(base.mqPort()));
    }

    /**
     * 仅从 {@code args} 解析；未传入的项使用 localhost 默认值。
     */
    public static InfraConnectionConfig fromArgs(String[] args) {
        ArgOverrides overrides = parseOverrides(args);
        return new InfraConnectionConfig(
                overrides.redisHost().orElse(DEFAULT_REDIS_HOST),
                overrides.redisPort().orElse(DEFAULT_REDIS_PORT),
                overrides.mqHost().orElse(DEFAULT_MQ_HOST),
                overrides.mqPort().orElse(DEFAULT_MQ_PORT));
    }

    private static ArgOverrides parseOverrides(String[] args) {
        Optional<String> redisHost = Optional.empty();
        Optional<Integer> redisPort = Optional.empty();
        Optional<String> mqHost = Optional.empty();
        Optional<Integer> mqPort = Optional.empty();

        for (int i = 0; i < args.length; i++) {
            String key = args[i];
            switch (key) {
                case "--redis-host" -> redisHost = Optional.of(requireValue(args, ++i, key));
                case "--redis-port" -> redisPort = Optional.of(requireInt(args, ++i, key));
                case "--mq-host" -> mqHost = Optional.of(requireValue(args, ++i, key));
                case "--mq-port" -> mqPort = Optional.of(requireInt(args, ++i, key));
                default -> { /* 忽略 Car001、--dynamic 等 */ }
            }
        }
        return new ArgOverrides(redisHost, redisPort, mqHost, mqPort);
    }

    private static String requireValue(String[] args, int index, String key) {
        if (index >= args.length) {
            throw new IllegalArgumentException("参数 " + key + " 缺少值");
        }
        return args[index];
    }

    private static int requireInt(String[] args, int index, String key) {
        try {
            return Integer.parseInt(requireValue(args, index, key));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("参数 " + key + " 的值不是有效整数", e);
        }
    }

    private record ArgOverrides(
            Optional<String> redisHost,
            Optional<Integer> redisPort,
            Optional<String> mqHost,
            Optional<Integer> mqPort) {
    }
}
