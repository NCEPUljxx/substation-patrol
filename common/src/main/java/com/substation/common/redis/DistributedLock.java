package com.substation.common.redis;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import java.util.Collections;

/**
 * Redis分布式锁，基于SET NX加锁 + Lua脚本原子释放。
 * 用于多实例场景下对同一辆小车(carId)的并发操作进行互斥。
 */
public class DistributedLock {

    /** 锁key前缀 */
    private static final String LOCK_KEY_PREFIX = "lock:";
    /** 默认超时时间（毫秒） */
    private static final int DEFAULT_TIMEOUT_MS = 5000;

    /** Redis连接池 */
    private final JedisPool pool;
    /** 锁对应的Redis key */
    private final String lockKey;
    /** 锁的值，用于释放时校验身份（线程名 + 时间戳） */
    private final String lockValue;

    /**
     * 根据carId构造分布式锁。
     *
     * @param pool  Redis连接池
     * @param carId 小车ID，作为锁的标识
     */
    public DistributedLock(JedisPool pool, String carId) {
        this.pool = pool;
        this.lockKey = LOCK_KEY_PREFIX + carId;
        this.lockValue = Thread.currentThread().getName() + "-" + System.currentTimeMillis();
    }

    /** 使用默认超时时间尝试获取锁。 */
    public boolean tryLock() {
        return tryLock(DEFAULT_TIMEOUT_MS);
    }

    /**
     * 尝试获取锁，使用SET NX PX原子命令。
     *
     * @param timeoutMs 锁的过期时间（毫秒）
     * @return true表示获取成功，false表示已被其他实例持有
     */
    public boolean tryLock(int timeoutMs) {
        try (Jedis jedis = pool.getResource()) {
            SetParams params = SetParams.setParams().nx().px(timeoutMs);
            String result = jedis.set(lockKey, lockValue, params);
            return "OK".equals(result);
        }
    }

    /**
     * 释放锁，使用Lua脚本保证先校验再删除的原子性。
     * 只有锁的持有者（lockValue匹配）才能释放。
     */
    public void unlock() {
        try (Jedis jedis = pool.getResource()) {
            String script =
                "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                "    return redis.call('del', KEYS[1]) " +
                "else " +
                "    return 0 " +
                "end";
            jedis.eval(script, java.util.Collections.singletonList(lockKey),
                    java.util.Collections.singletonList(lockValue));
        }
    }
}
