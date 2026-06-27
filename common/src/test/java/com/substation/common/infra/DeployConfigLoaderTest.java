package com.substation.common.infra;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeployConfigLoaderTest {

    @Test
    void parsesExampleFields() {
        JSONObject root = JSONObject.of(
                "redisHost", "100.1.2.3",
                "mqHost", "100.1.2.3",
                "role", "car",
                "cars", new String[]{"Car001", "Car004"});
        DeployConfig config = DeployConfigLoader.parse(root);

        assertEquals("100.1.2.3", config.redisHost());
        assertEquals("100.1.2.3", config.mqHost());
        assertEquals("car", config.role());
        assertEquals(2, config.cars().size());
        assertEquals("Car004", config.cars().get(1));
    }

    @Test
    void loadFromFile(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("infra.local.json");
        Files.writeString(configFile, """
                {
                  "redisHost": "10.0.0.8",
                  "mqHost": "10.0.0.8"
                }
                """);

        var loaded = DeployConfigLoader.loadFrom(configFile);
        assertTrue(loaded.isPresent());
        assertEquals("10.0.0.8", loaded.get().redisHost());
    }
}
