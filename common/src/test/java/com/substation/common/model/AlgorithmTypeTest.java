package com.substation.common.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AlgorithmTypeTest {

    @Test
    void twoTypes() {
        assertEquals(2, AlgorithmType.values().length);
    }

    @Test
    void valueOf() {
        assertEquals(AlgorithmType.BFS, AlgorithmType.valueOf("BFS"));
        assertEquals(AlgorithmType.ASTAR, AlgorithmType.valueOf("ASTAR"));
    }
}
