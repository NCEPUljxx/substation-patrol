package com.substation.display;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WebSocketBridge 纯逻辑单元测试。
 *
 * <p>只测试不依赖 Redis / RabbitMQ / WebSocket 连接的纯函数。
 * 涉及 {@link com.substation.common.redis.BlackboardClient} 的集成测试
 * 需要 Redis 运行，单独执行。</p>
 */
class WebSocketBridgeTest {

    // ═══════════════════════════════════════════════════════
    // extractCarNumber
    // ═══════════════════════════════════════════════════════

    @Test
    void extractCarNumber_standard() {
        assertEquals(1,   WebSocketBridge.extractCarNumber("Car001"));
        assertEquals(12,  WebSocketBridge.extractCarNumber("Car012"));
        assertEquals(123, WebSocketBridge.extractCarNumber("Car123"));
    }

    @Test
    void extractCarNumber_singleDigit() {
        assertEquals(5, WebSocketBridge.extractCarNumber("Car005"));
    }

    @Test
    void extractCarNumber_maxValue() {
        assertEquals(999, WebSocketBridge.extractCarNumber("Car999"));
    }

    @Test
    void extractCarNumber_invalidReturnsZero() {
        // CarID 格式错误时返回 0（防御性编程）
        assertEquals(0, WebSocketBridge.extractCarNumber("CarABC"));
        assertEquals(0, WebSocketBridge.extractCarNumber("Car"));
        assertEquals(0, WebSocketBridge.extractCarNumber(""));
    }

    // ═══════════════════════════════════════════════════════
    // parseIntOrDefault
    // ═══════════════════════════════════════════════════════

    @Test
    void parseIntOrDefault_valid() {
        assertEquals(30,   WebSocketBridge.parseIntOrDefault("30", 100));
        assertEquals(0,    WebSocketBridge.parseIntOrDefault("0", 100));
        assertEquals(-1,   WebSocketBridge.parseIntOrDefault("-1", 100));
    }

    @Test
    void parseIntOrDefault_nullReturnsDefault() {
        assertEquals(30, WebSocketBridge.parseIntOrDefault(null, 30));
    }

    @Test
    void parseIntOrDefault_invalidReturnsDefault() {
        // TaskConfig 初始化前返回值可能为空字符串或非法字符串
        assertEquals(30, WebSocketBridge.parseIntOrDefault("", 30));
        assertEquals(30, WebSocketBridge.parseIntOrDefault("abc", 30));
        assertEquals(30, WebSocketBridge.parseIntOrDefault("12.5", 30));
    }

    @Test
    void parseIntOrDefault_defaultValues() {
        assertEquals(500, WebSocketBridge.parseIntOrDefault(null, 500));
        assertEquals(100, WebSocketBridge.parseIntOrDefault("invalid", 100));
    }

    // ═══════════════════════════════════════════════════════
    // 边界组合：extractCarNumber 间接通过 CarInfo.number 影响前端
    // ═══════════════════════════════════════════════════════

    @Test
    void carNumberConsistency() {
        // 确保从 Car001 到 Car010 的编号提取一致
        for (int i = 1; i <= 10; i++) {
            String carId = String.format("Car%03d", i);
            assertEquals(i, WebSocketBridge.extractCarNumber(carId),
                    "carId=" + carId + " 应提取编号 " + i);
        }
    }
}
