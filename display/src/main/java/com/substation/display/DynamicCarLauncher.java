package com.substation.display;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** 通过已打包的 car JAR 异步启动动态添加的小车进程 */
final class DynamicCarLauncher {

    private static final Logger LOG = LoggerFactory.getLogger(DynamicCarLauncher.class);

    static final String CAR_JAR_RELATIVE = "car/target/car-1.0-SNAPSHOT.jar";
    static final String DYNAMIC_FLAG = "--dynamic";
    private static final String RUNTIME_JAR_DIR = "logs/runtime";
    private static final long LAUNCH_GAP_MS = 800L;
    private static final long STOP_TIMEOUT_SEC = 3L;
    private static final Object LAUNCH_LOCK = new Object();
    private static final Map<String, Process> RUNNING_PROCESSES = new ConcurrentHashMap<>();
    private static volatile Consumer<String> processExitListener = carId -> {};

    private DynamicCarLauncher() {
    }

    static void setProcessExitListener(Consumer<String> listener) {
        processExitListener = listener != null ? listener : carId -> {};
    }

    static void stopProcess(String carId) {
        stopExistingProcess(carId);
    }

    static boolean isLaunchAvailable(Path projectRoot) {
        return Files.isRegularFile(projectRoot.resolve(CAR_JAR_RELATIVE));
    }

    static boolean isProcessAlive(String carId) {
        Process process = RUNNING_PROCESSES.get(carId);
        return process != null && process.isAlive();
    }

    static void launchAsync(String carId, Path projectRoot, Runnable beforeLaunch,
                            Consumer<String> onSuccess,
                            java.util.function.BiConsumer<String, Throwable> onFailure) {
        Thread launcher = new Thread(
                () -> launchWithCallback(carId, projectRoot, beforeLaunch, onSuccess, onFailure),
                "car-launch-" + carId);
        launcher.setDaemon(true);
        launcher.start();
    }

    private static void launchWithCallback(String carId, Path projectRoot, Runnable beforeLaunch,
                                           Consumer<String> onSuccess,
                                           java.util.function.BiConsumer<String, Throwable> onFailure) {
        try {
            launchBlocking(carId, projectRoot, beforeLaunch);
            onSuccess.accept(carId);
        } catch (RuntimeException e) {
            onFailure.accept(carId, e);
        }
    }

    private static void launchBlocking(String carId, Path projectRoot, Runnable beforeLaunch) {
        synchronized (LAUNCH_LOCK) {
            try {
                DynamicCarProcessKiller.killProcessesForCar(carId);
                stopExistingProcess(carId);
                waitForRuntimeJarUnlock(projectRoot, carId);
                if (beforeLaunch != null) {
                    beforeLaunch.run();
                }
                launchOnce(carId, projectRoot);
                Thread.sleep(LAUNCH_GAP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("启动小车被中断: " + carId, e);
            }
        }
    }

    private static void waitForRuntimeJarUnlock(Path projectRoot, String carId) throws InterruptedException {
        Path legacyJar = projectRoot.resolve(RUNTIME_JAR_DIR).resolve(carId + ".jar").normalize();
        if (!Files.exists(legacyJar)) {
            return;
        }
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                Files.deleteIfExists(legacyJar);
                return;
            } catch (IOException e) {
                Thread.sleep(200L);
            }
        }
    }

    private static void launchOnce(String carId, Path projectRoot) {
        Path sourceJar = projectRoot.resolve(CAR_JAR_RELATIVE).normalize();
        if (!Files.isRegularFile(sourceJar)) {
            throw new IllegalStateException(
                    "未找到 car JAR: " + sourceJar
                            + "，请先执行 .\\mvnw.cmd package -pl car -am -DskipTests");
        }

        Path logDir = projectRoot.resolve("logs");
        try {
            Files.createDirectories(logDir);
        } catch (IOException e) {
            throw new IllegalStateException("创建日志目录失败: " + logDir, e);
        }

        Path runtimeDir = projectRoot.resolve(RUNTIME_JAR_DIR).normalize();
        Path runtimeJar = runtimeDir.resolve(carId + "-" + System.currentTimeMillis() + ".jar");
        try {
            Files.createDirectories(runtimeDir);
            Files.copy(sourceJar, runtimeJar);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "复制 car JAR 失败: " + carId + "（若该编号小车仍在运行，请先重置仿真）", e);
        }

        Path logFile = logDir.resolve("car-" + carId + "-" + System.currentTimeMillis() + ".log");
        List<String> command = buildJarCommand(carId, runtimeJar);

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(projectRoot.toFile());
            processBuilder.redirectErrorStream(true);
            processBuilder.redirectOutput(logFile.toFile());
            Process process = processBuilder.start();
            RUNNING_PROCESSES.put(carId, process);
            watchProcessExit(carId, process);
            LOG.info("已启动小车进程 {}，JAR: {}，日志: {}", carId, runtimeJar, logFile);
        } catch (IOException e) {
            throw new IllegalStateException("启动小车进程失败: " + carId, e);
        }
    }

    private static List<String> buildJarCommand(String carId, Path runtimeJar) {
        List<String> command = new ArrayList<>();
        command.add(resolveJavaExecutable());
        command.add("-jar");
        command.add(runtimeJar.toString());
        command.add(carId);
        command.add(DYNAMIC_FLAG);
        return command;
    }

    private static void watchProcessExit(String carId, Process process) {
        process.onExit().thenRun(() -> {
            RUNNING_PROCESSES.remove(carId, process);
            processExitListener.accept(carId);
            LOG.info("小车进程已退出: {}", carId);
        });
    }

    private static void stopExistingProcess(String carId) {
        Process process = RUNNING_PROCESSES.remove(carId);
        if (process == null || !process.isAlive()) {
            return;
        }
        LOG.info("停止旧的小车进程: {}", carId);
        process.destroy();
        try {
            if (!process.waitFor(STOP_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static String resolveJavaExecutable() {
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path windowsJava = javaHome.resolve("bin").resolve("java.exe");
        if (Files.exists(windowsJava)) {
            return windowsJava.toString();
        }
        Path unixJava = javaHome.resolve("bin").resolve("java");
        if (Files.exists(unixJava)) {
            return unixJava.toString();
        }
        return "java";
    }
}
