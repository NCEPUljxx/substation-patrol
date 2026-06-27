package com.substation.common.infra;

import java.util.List;

/**
 * 分布式部署本地配置（{@code deploy/infra.local.json}）。
 * <p>数据库字段仅在 Person D（Display 所在机器）上需要，其他角色可留空使用默认值。
 */
public record DeployConfig(
        String redisHost,
        int redisPort,
        String mqHost,
        int mqPort,
        String role,
        String displayHost,
        int displayHttpPort,
        int displayWsPort,
        // —— 数据库配置（仅 Person D 使用） ——
        String dbHost,
        int dbPort,
        String dbName,
        String dbUser,
        String dbPassword,
        List<String> cars) {

    public static final String ROLE_INFRA = "infra";
    public static final String ROLE_PLANNER = "planner";
    public static final String ROLE_CAR = "car";
    public static final String ROLE_DISPLAY = "display";

    public static final List<String> DEFAULT_CARS = List.of("Car001", "Car002", "Car003");

    // —— 数据库默认值 ——
    public static final String DEFAULT_DB_HOST = "localhost";
    public static final int DEFAULT_DB_PORT = 1433;
    public static final String DEFAULT_DB_NAME = "substation-patrol";
    public static final String DEFAULT_DB_USER = "sa";
    public static final String DEFAULT_DB_PASSWORD = "Root@1234";

    public static DeployConfig localhostDefaults() {
        return new DeployConfig(
                InfraConnectionConfig.DEFAULT_REDIS_HOST,
                InfraConnectionConfig.DEFAULT_REDIS_PORT,
                InfraConnectionConfig.DEFAULT_MQ_HOST,
                InfraConnectionConfig.DEFAULT_MQ_PORT,
                ROLE_INFRA,
                "localhost",
                8887,
                8888,
                DEFAULT_DB_HOST,
                DEFAULT_DB_PORT,
                DEFAULT_DB_NAME,
                DEFAULT_DB_USER,
                DEFAULT_DB_PASSWORD,
                DEFAULT_CARS);
    }

    /** 构建 JDBC 连接 URL */
    public String buildJdbcUrl() {
        return "jdbc:sqlserver://" + dbHost + ":" + dbPort
                + ";databaseName=" + dbName
                + ";encrypt=false;trustServerCertificate=true";
    }

    public InfraConnectionConfig toInfraConnectionConfig() {
        return new InfraConnectionConfig(redisHost, redisPort, mqHost, mqPort);
    }
}
