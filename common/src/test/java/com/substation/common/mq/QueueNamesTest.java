package com.substation.common.mq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QueueNamesTest {

    @Test
    void carQueue() {
        assertEquals("Car_Car001", QueueNames.carQueue("Car001"));
        assertEquals("Car_Car005", QueueNames.carQueue("Car005"));
    }

    @Test
    void constants() {
        assertEquals("NavigatorCmd", QueueNames.NAVIGATOR_CMD);
        assertEquals("TargetPlannerCmd", QueueNames.TARGET_PLANNER_CMD);
        assertEquals("TaskConfigCmd", QueueNames.TASK_CONFIG_CMD);
        assertEquals("ControllerCmd", QueueNames.CONTROLLER_CMD);
        assertEquals("UpdateView", QueueNames.UPDATE_VIEW_EXCHANGE);
    }
}
