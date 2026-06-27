package com.substation.common.infra;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InfraConnectionConfigTest {

    @Test
    void localhostDefaultsWhenNoArgs() {
        var config = InfraConnectionConfig.fromArgs(new String[0]);
        assertEquals("localhost", config.redisHost());
        assertEquals(6379, config.redisPort());
        assertEquals("localhost", config.mqHost());
        assertEquals(5672, config.mqPort());
    }

    @Test
    void parsesHostFlagsAlongsideCarId() {
        var config = InfraConnectionConfig.fromArgs(new String[]{
                "Car001",
                "--redis-host", "100.94.124.1",
                "--mq-host", "100.94.124.1",
                "--dynamic"
        });
        assertEquals("100.94.124.1", config.redisHost());
        assertEquals("100.94.124.1", config.mqHost());
    }

    @Test
    void resolveUsesFileWhenCliOmitsHosts() {
        DeployConfig file = new DeployConfig(
                "100.94.124.1", 6379, "100.94.124.1", 5672,
                DeployConfig.ROLE_PLANNER, "localhost", 8887, 8888,
                "localhost", 1433, "substation-patrol", "sa", "Root@1234",
                List.of("Car001"));
        var config = InfraConnectionConfig.resolve(new String[]{"Car001"}, Optional.of(file));
        assertEquals("100.94.124.1", config.redisHost());
        assertEquals("100.94.124.1", config.mqHost());
    }

    @Test
    void resolvePrefersCliOverFile() {
        DeployConfig file = new DeployConfig(
                "1.1.1.1", 6379, "1.1.1.1", 5672,
                DeployConfig.ROLE_CAR, "localhost", 8887, 8888,
                "localhost", 1433, "substation-patrol", "sa", "Root@1234",
                DeployConfig.DEFAULT_CARS);
        var config = InfraConnectionConfig.resolve(
                new String[]{"--redis-host", "2.2.2.2"},
                Optional.of(file));
        assertEquals("2.2.2.2", config.redisHost());
        assertEquals("1.1.1.1", config.mqHost());
    }

    @Test
    void missingHostValueThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> InfraConnectionConfig.fromArgs(new String[]{"--redis-host"}));
    }
}

