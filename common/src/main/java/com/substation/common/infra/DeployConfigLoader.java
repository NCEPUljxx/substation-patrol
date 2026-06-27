package com.substation.common.infra;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 从 {@code deploy/infra.local.json} 或环境变量 {@code CAR_HOMEWORK_CONFIG} 加载部署配置。
 */
public final class DeployConfigLoader {

    public static final String ENV_CONFIG_PATH = "CAR_HOMEWORK_CONFIG";
    public static final String DEFAULT_RELATIVE_PATH = "deploy/infra.local.json";

    private DeployConfigLoader() {
    }

    public static Optional<DeployConfig> loadOptional() {
        return loadFrom(resolveConfigPath());
    }

    public static Optional<DeployConfig> loadFrom(Path path) {
        return readConfig(path);
    }

    public static Path resolveConfigPath() {
        String envPath = System.getenv(ENV_CONFIG_PATH);
        if (envPath != null && !envPath.isBlank()) {
            return Path.of(envPath.trim());
        }
        return Path.of(DEFAULT_RELATIVE_PATH);
    }

    private static Optional<DeployConfig> readConfig(Path path) {
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            JSONObject root = JSON.parseObject(Files.readString(path));
            return Optional.of(parse(root));
        } catch (IOException e) {
            throw new IllegalStateException("无法读取部署配置: " + path.toAbsolutePath(), e);
        }
    }

    static DeployConfig parse(JSONObject root) {
        DeployConfig defaults = DeployConfig.localhostDefaults();
        return new DeployConfig(
                textOrDefault(root, "redisHost", defaults.redisHost()),
                intOrDefault(root, "redisPort", defaults.redisPort()),
                textOrDefault(root, "mqHost", defaults.mqHost()),
                intOrDefault(root, "mqPort", defaults.mqPort()),
                textOrDefault(root, "role", defaults.role()),
                textOrDefault(root, "displayHost", defaults.displayHost()),
                intOrDefault(root, "displayHttpPort", defaults.displayHttpPort()),
                intOrDefault(root, "displayWsPort", defaults.displayWsPort()),
                // —— 数据库配置（可选，未填则使用默认值） ——
                textOrDefault(root, "dbHost", defaults.dbHost()),
                intOrDefault(root, "dbPort", defaults.dbPort()),
                textOrDefault(root, "dbName", defaults.dbName()),
                textOrDefault(root, "dbUser", defaults.dbUser()),
                textOrDefault(root, "dbPassword", defaults.dbPassword()),
                parseCars(root, defaults.cars()));
    }

    private static List<String> parseCars(JSONObject root, List<String> defaultCars) {
        JSONArray cars = root.getJSONArray("cars");
        if (cars == null || cars.isEmpty()) {
            return defaultCars;
        }
        List<String> carIds = new ArrayList<>(cars.size());
        for (int i = 0; i < cars.size(); i++) {
            String carId = cars.getString(i);
            if (carId != null && !carId.isBlank()) {
                carIds.add(carId.trim());
            }
        }
        return carIds.isEmpty() ? defaultCars : List.copyOf(carIds);
    }

    private static String textOrDefault(JSONObject root, String key, String defaultValue) {
        String value = root.getString(key);
        return value != null && !value.isBlank() ? value.trim() : defaultValue;
    }

    private static int intOrDefault(JSONObject root, String key, int defaultValue) {
        Integer value = root.getInteger(key);
        return value != null ? value : defaultValue;
    }
}
