package com.substation.common.mq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessageTypesTest {

    @Test
    void allTypesDefined() {
        assertNotNull(MessageTypes.ASSIGN_TARGET);
        assertNotNull(MessageTypes.TARGET_ASSIGNED);
        assertNotNull(MessageTypes.PLAN_ROUTE);
        assertNotNull(MessageTypes.ROUTE_PLANNED);
        assertNotNull(MessageTypes.TICK_MOVE);
        assertNotNull(MessageTypes.MOVED);
        assertNotNull(MessageTypes.ROUTE_DONE);
        assertNotNull(MessageTypes.BLOCKED);
        assertNotNull(MessageTypes.BLOCKED_TIMEOUT);
        assertNotNull(MessageTypes.REFRESH_ALL);
        assertNotNull(MessageTypes.SET_CONFIG);
        assertNotNull(MessageTypes.FORWARD_CONFIG);
        assertNotNull(MessageTypes.RESET);
        assertNotNull(MessageTypes.FORWARD_RESET);
        assertNotNull(MessageTypes.TASK_READY);
        assertNotNull(MessageTypes.TOGGLE_PAUSE);
        assertNotNull(MessageTypes.SET_TICK_INTERVAL);
    }
}
