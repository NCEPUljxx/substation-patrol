package com.substation.display;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** 清理操作系统中遗留的动态小车 JVM，避免同 carId 多进程抢同一 MQ 队列。 */
final class DynamicCarProcessKiller {

    private static final Logger LOG = LoggerFactory.getLogger(DynamicCarProcessKiller.class);
    private static final String DYNAMIC_FLAG = "--dynamic";
    private static final long TERMINATE_TIMEOUT_SEC = 3L;
    private static final Pattern CAR_ID_BEFORE_DYNAMIC =
            Pattern.compile("\\b(Car\\d{3})\\s+" + Pattern.quote(DYNAMIC_FLAG) + "\\b");
    private static final Pattern RUNTIME_JAR_CAR_ID =
            Pattern.compile("[/\\\\]runtime[/\\\\](Car\\d{3})(?:-\\d+)?\\.jar");

    private DynamicCarProcessKiller() {
    }

    static void killProcessesForCar(String carId) {
        killProcesses(findProcessesForCar(carId));
    }

    static void killAllDynamicExcept(Set<String> preservedCarIds) {
        killProcesses(findAllDynamicProcesses(preservedCarIds));
    }

    static boolean commandLineTargetsCar(String commandLine, String carId) {
        if (commandLine == null || commandLine.isBlank() || !commandLine.contains(DYNAMIC_FLAG)) {
            return false;
        }
        return commandLine.contains(carId + ".jar")
                || commandLine.matches("(?s).*\\s" + Pattern.quote(carId) + "\\s+.*");
    }

    static String extractDynamicCarId(String commandLine) {
        if (commandLine == null || !commandLine.contains(DYNAMIC_FLAG)) {
            return null;
        }
        Matcher beforeDynamic = CAR_ID_BEFORE_DYNAMIC.matcher(commandLine);
        if (beforeDynamic.find()) {
            return beforeDynamic.group(1);
        }
        Matcher runtimeJar = RUNTIME_JAR_CAR_ID.matcher(commandLine);
        if (runtimeJar.find()) {
            return runtimeJar.group(1);
        }
        return null;
    }

    private static List<ProcessHandle> findProcessesForCar(String carId) {
        long currentPid = ProcessHandle.current().pid();
        List<ProcessHandle> matches = new ArrayList<>();
        ProcessHandle.allProcesses()
                .filter(handle -> handle.pid() != currentPid)
                .forEach(handle -> handle.info().commandLine().ifPresent(commandLine -> {
                    if (commandLineTargetsCar(commandLine, carId)) {
                        matches.add(handle);
                    }
                }));
        return matches;
    }

    private static List<ProcessHandle> findAllDynamicProcesses(Set<String> preservedCarIds) {
        long currentPid = ProcessHandle.current().pid();
        List<ProcessHandle> matches = new ArrayList<>();
        ProcessHandle.allProcesses()
                .filter(handle -> handle.pid() != currentPid)
                .forEach(handle -> handle.info().commandLine().ifPresent(commandLine -> {
                    String carId = extractDynamicCarId(commandLine);
                    if (carId != null && !preservedCarIds.contains(carId)) {
                        matches.add(handle);
                    }
                }));
        return matches;
    }

    private static void killProcesses(List<ProcessHandle> processes) {
        for (ProcessHandle process : processes) {
            terminateProcess(process);
        }
    }

    private static void terminateProcess(ProcessHandle process) {
        long pid = process.pid();
        String commandLine = process.info().commandLine().orElse("");
        LOG.info("终止遗留动态小车进程 pid={} cmd={}", pid, abbreviate(commandLine));
        process.destroy();
        try {
            if (!process.onExit().get(TERMINATE_TIMEOUT_SEC, TimeUnit.SECONDS).isAlive()) {
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.debug("等待进程退出超时 pid={}", pid, e);
        }
        process.destroyForcibly();
    }

    private static String abbreviate(String commandLine) {
        if (commandLine.length() <= 160) {
            return commandLine;
        }
        return commandLine.substring(0, 157) + "...";
    }
}
