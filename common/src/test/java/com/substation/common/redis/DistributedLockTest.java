package com.substation.common.redis;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import static org.junit.jupiter.api.Assertions.*;

class DistributedLockTest {

    private JedisPool pool;

    @BeforeEach
    void setUp() {
        pool = new JedisPool("localhost", 6379);
        try (Jedis jedis = pool.getResource()) {
            jedis.flushDB();
        }
    }

    @AfterEach
    void tearDown() {
        try (Jedis jedis = pool.getResource()) {
            jedis.flushDB();
        }
        pool.close();
    }

    @Test
    void tryLock() {
        DistributedLock lock = new DistributedLock(pool, "Car001");
        assertTrue(lock.tryLock());
        lock.unlock();
    }

    @Test
    void cannotAcquireTwice() {
        DistributedLock lock1 = new DistributedLock(pool, "Car001");
        DistributedLock lock2 = new DistributedLock(pool, "Car001");

        assertTrue(lock1.tryLock());
        assertFalse(lock2.tryLock());
        lock1.unlock();
    }

    @Test
    void unlockThenReacquire() {
        DistributedLock lock = new DistributedLock(pool, "Car001");

        assertTrue(lock.tryLock());
        lock.unlock();
        assertTrue(lock.tryLock());
        lock.unlock();
    }

    @Test
    void differentCarsDoNotConflict() {
        DistributedLock lock1 = new DistributedLock(pool, "Car001");
        DistributedLock lock2 = new DistributedLock(pool, "Car002");

        assertTrue(lock1.tryLock());
        assertTrue(lock2.tryLock());
        lock1.unlock();
        lock2.unlock();
    }
}
