package com.substation.common.mq;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MessageBuilderTest {

    @Test
    void buildWithAllFields() {
        Map<String, Object> data = Map.of("carId", "Car001", "target", Map.of("x", 10, "y", 20));
        String json = MessageBuilder.build("TARGET_ASSIGNED", 5, "Car001", data);

        JSONObject msg = JSONObject.parse(json);
        assertEquals("TARGET_ASSIGNED", msg.getString("type"));
        assertEquals(5, msg.getIntValue("tick"));
        assertEquals("Car001", msg.getString("carId"));
        assertTrue(msg.getLongValue("timestamp") > 0);
        assertNotNull(msg.getJSONObject("data"));
    }

    @Test
    void buildWithoutCarId() {
        String json = MessageBuilder.build("REFRESH_ALL", 10);

        JSONObject msg = JSONObject.parse(json);
        assertEquals("REFRESH_ALL", msg.getString("type"));
        assertEquals(10, msg.getIntValue("tick"));
        assertFalse(msg.containsKey("carId"));
        assertTrue(msg.getJSONObject("data").isEmpty());
    }

    @Test
    void buildWithNullData() {
        String json = MessageBuilder.build("TICK_MOVE", 3, "Car001");

        JSONObject msg = JSONObject.parse(json);
        assertEquals("TICK_MOVE", msg.getString("type"));
        assertEquals("Car001", msg.getString("carId"));
        assertTrue(msg.getJSONObject("data").isEmpty());
    }
}
