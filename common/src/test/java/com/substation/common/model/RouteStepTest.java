package com.substation.common.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RouteStepTest {

    @Test
    void createAndAccess() {
        Point pos = new Point(3, 7);
        RouteStep step = new RouteStep(pos, 5);

        assertEquals(pos, step.position());
        assertEquals(5, step.stepIndex());
    }
}
