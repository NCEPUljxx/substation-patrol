package com.substation.common.mq;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MessageBusTest {

    private static final int CONSUMER_READY_MS = 200;
    private static final int RECEIVE_TIMEOUT_SEC = 5;

    private MessageBus bus;
    private String isolatedQueue;

    @BeforeEach
    void setUp() throws Exception {
        bus = new MessageBus("localhost", 5672, "guest", "guest");
        bus.connect();
        isolatedQueue = "MessageBusTest_" + UUID.randomUUID();
        bus.declareQueue(isolatedQueue);
    }

    @AfterEach
    void tearDown() {
        if (bus != null && bus.isConnected()) {
            bus.close();
        }
    }

    @Test
    void connect() {
        assertTrue(bus.isConnected());
    }

    @Test
    void publishAndSubscribe() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        bus.subscribe(isolatedQueue, msg -> {
            received.set(msg);
            latch.countDown();
        });
        Thread.sleep(CONSUMER_READY_MS);

        String testMsg = MessageBuilder.build("TEST", 0);
        bus.publish(isolatedQueue, testMsg);

        boolean completed = latch.await(RECEIVE_TIMEOUT_SEC, TimeUnit.SECONDS);
        assertTrue(completed, "消息应在" + RECEIVE_TIMEOUT_SEC + "秒内收到");
        assertEquals(testMsg, received.get());
    }

    @Test
    void fanout() throws Exception {
        bus.declareFanoutExchange();
        String queue1 = bus.bindFanoutQueue();
        String queue2 = bus.bindFanoutQueue();

        AtomicReference<String> received1 = new AtomicReference<>();
        AtomicReference<String> received2 = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(2);

        bus.subscribe(queue1, msg -> { received1.set(msg); latch.countDown(); });
        bus.subscribe(queue2, msg -> { received2.set(msg); latch.countDown(); });
        Thread.sleep(CONSUMER_READY_MS);

        bus.publishFanout(QueueNames.UPDATE_VIEW_EXCHANGE, MessageBuilder.build("REFRESH_ALL", 1));

        boolean completed = latch.await(RECEIVE_TIMEOUT_SEC, TimeUnit.SECONDS);
        assertTrue(completed, "两个订阅者都应在" + RECEIVE_TIMEOUT_SEC + "秒内收到消息");
        assertNotNull(received1.get());
        assertNotNull(received2.get());
    }
}
