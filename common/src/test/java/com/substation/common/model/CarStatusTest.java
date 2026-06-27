package com.substation.common.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CarStatusTest {

    @Test
    void fiveStates() {
        assertEquals(5, CarStatus.values().length);
    }

    @Test
    void chineseNameNotEmpty() {
        for (CarStatus s : CarStatus.values()) {
            assertNotNull(s.chineseName());
            assertFalse(s.chineseName().isBlank());
        }
    }

    @Test
    void colorIsHex() {
        for (CarStatus s : CarStatus.values()) {
            assertTrue(s.color().startsWith("#"));
            assertEquals(7, s.color().length());
        }
    }

    @Test
    void carStatusValueOf() {
        assertEquals(CarStatus.IDLE, CarStatus.valueOf("IDLE"));
        assertEquals(CarStatus.BLOCKED, CarStatus.valueOf("BLOCKED"));
    }
}
