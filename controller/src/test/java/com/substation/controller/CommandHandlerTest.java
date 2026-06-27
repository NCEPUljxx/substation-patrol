package com.substation.controller;

import com.alibaba.fastjson2.JSONObject;
import com.substation.common.mq.MessageBuilder;
import com.substation.common.mq.MessageTypes;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CommandHandlerTest {

    @Test
    void handleTaskReady() {
        AtomicBoolean started = new AtomicBoolean();
        StubStatusDispatcher dispatcher = new StubStatusDispatcher() {
            @Override
            public void onTaskReady() {
                started.set(true);
            }
        };
        StubTickScheduler scheduler = new StubTickScheduler();

        CommandHandler handler = new CommandHandler(null, dispatcher, scheduler);
        String msg = MessageBuilder.build(MessageTypes.TASK_READY, 0);
        handler.handle(msg);

        assertTrue(started.get(), "TASK_READY 应触发 onTaskReady");
    }

    @Test
    void handleTargetAssigned() {
        AtomicReference<String> carIdRef = new AtomicReference<>();
        AtomicBoolean successRef = new AtomicBoolean();
        StubStatusDispatcher dispatcher = new StubStatusDispatcher() {
            @Override
            public void onTargetAssigned(String carId, boolean success) {
                carIdRef.set(carId);
                successRef.set(success);
            }
        };

        CommandHandler handler = new CommandHandler(null, dispatcher, new StubTickScheduler());
        JSONObject data = new JSONObject();
        data.put("success", true);
        String msg = MessageBuilder.build(MessageTypes.TARGET_ASSIGNED, 5, "Car001", data);
        handler.handle(msg);

        assertEquals("Car001", carIdRef.get());
        assertTrue(successRef.get());
    }

    @Test
    void handleRoutePlanned() {
        AtomicReference<String> carIdRef = new AtomicReference<>();
        AtomicBoolean routeFoundRef = new AtomicBoolean();
        StubStatusDispatcher dispatcher = new StubStatusDispatcher() {
            @Override
            public void onRoutePlanned(String carId, boolean routeFound) {
                carIdRef.set(carId);
                routeFoundRef.set(routeFound);
            }
        };

        CommandHandler handler = new CommandHandler(null, dispatcher, new StubTickScheduler());
        JSONObject data = new JSONObject();
        data.put("routeFound", true);
        data.put("routeLength", 15);
        String msg = MessageBuilder.build(MessageTypes.ROUTE_PLANNED, 5, "Car001", data);
        handler.handle(msg);

        assertEquals("Car001", carIdRef.get());
        assertTrue(routeFoundRef.get());
    }

    @Test
    void handleTogglePause() {
        StubTickScheduler scheduler = new StubTickScheduler();
        CommandHandler handler = new CommandHandler(null, new StubStatusDispatcher(), scheduler);

        String msg = MessageBuilder.build(MessageTypes.TOGGLE_PAUSE, 0);
        handler.handle(msg);

        assertTrue(scheduler.toggled);
    }

    @Test
    void handleSetTickInterval() {
        StubTickScheduler scheduler = new StubTickScheduler();
        StubStatusDispatcher dispatcher = new StubStatusDispatcher();
        CommandHandler handler = new CommandHandler(null, dispatcher, scheduler);

        JSONObject data = new JSONObject();
        data.put("interval", 200);
        String msg = MessageBuilder.build(MessageTypes.SET_TICK_INTERVAL, 0, null, data);
        handler.handle(msg);

        assertEquals(200, scheduler.interval);
        assertEquals(200, dispatcher.appliedTickInterval);
    }

    @Test
    void handleRouteOverlapReassign() {
        AtomicReference<String> carIdRef = new AtomicReference<>();
        StubStatusDispatcher dispatcher = new StubStatusDispatcher() {
            @Override
            public void onRouteOverlapReassign(String carId) {
                carIdRef.set(carId);
            }
        };

        CommandHandler handler = new CommandHandler(null, dispatcher, new StubTickScheduler());
        JSONObject data = new JSONObject();
        data.put("overlapReassign", true);
        String msg = MessageBuilder.build(MessageTypes.ROUTE_OPTIMIZED, 5, "Car001", data);
        handler.handle(msg);

        assertEquals("Car001", carIdRef.get());
    }

    @Test
    void handleReset() {
        AtomicBoolean reset = new AtomicBoolean();
        StubStatusDispatcher dispatcher = new StubStatusDispatcher() {
            @Override
            public void forwardReset() {
                reset.set(true);
            }
        };

        CommandHandler handler = new CommandHandler(null, dispatcher, new StubTickScheduler());
        handler.handle(MessageBuilder.build(MessageTypes.RESET, 0));

        assertTrue(reset.get());
    }

    private static class StubStatusDispatcher extends StatusDispatcher {
        volatile Integer appliedTickInterval;

        StubStatusDispatcher() {
            super(null, null);
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public void applyTickInterval(int intervalMs) {
            appliedTickInterval = intervalMs;
        }
    }

    private static class StubTickScheduler extends TickScheduler {
        volatile boolean toggled;
        volatile int interval;

        StubTickScheduler() {
            super(null);
        }

        @Override
        public void togglePause() {
            toggled = true;
        }

        @Override
        public void setInterval(int ms) {
            interval = ms;
        }

        @Override
        public void start() {}
    }
}
