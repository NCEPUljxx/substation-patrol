package com.substation.controller;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** 节拍调度器，以固定间隔驱动 StatusDispatcher 执行状态分派 */
public class TickScheduler {

    /** 默认调度间隔（毫秒） */
    private static final int DEFAULT_INTERVAL_MS = 500;

    /** 状态分派器引用 */
    private final StatusDispatcher dispatcher;
    /** 单线程定时执行器 */
    private final ScheduledExecutorService executor;
    /** 当前定时任务句柄 */
    private ScheduledFuture<?> future;
    /** 是否暂停调度 */
    private volatile boolean paused;
    /** 当前调度间隔（毫秒） */
    private volatile int intervalMs;

    /** 创建节拍调度器，初始化守护线程执行器 */
    public TickScheduler(StatusDispatcher dispatcher) {
        this.dispatcher = dispatcher;
        this.intervalMs = DEFAULT_INTERVAL_MS;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tick-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /** 启动定时调度 */
    public void start() {
        schedule();
    }

    /** 切换暂停/恢复状态 */
    public void togglePause() {
        paused = !paused;
    }

    /** 重置暂停状态（供 forwardReset 调用） */
    public void resetPaused() {
        paused = false;
    }

    /** 查询当前是否处于暂停状态 */
    public boolean isPaused() {
        return paused;
    }

    /** 设置调度间隔并立即应用（重新提交定时任务） */
    public void setInterval(int ms) {
        this.intervalMs = ms;
        if (future != null) {
            schedule();
        }
    }

    /** 停止调度，不关闭线程池（允许重启） */
    public void stop() {
        if (future != null) {
            future.cancel(false);
            future = null;
        }
    }

    /** 关闭线程池（仅 JVM 退出时调用） */
    public void shutdown() {
        stop();
        executor.shutdown();
    }

    /** 重新提交定时任务：先取消旧任务，再以当前间隔提交新任务 */
    private void schedule() {
        if (future != null) {
            future.cancel(false);
        }
        future = executor.scheduleWithFixedDelay(this::tickLoop, 0, intervalMs, TimeUnit.MILLISECONDS);
    }

    /** 每次节拍回调：暂停时跳过，否则执行一次分派 */
    private void tickLoop() {
        if (paused) {
            return;
        }
        dispatcher.dispatch();
    }
}
