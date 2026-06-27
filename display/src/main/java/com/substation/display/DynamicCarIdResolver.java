package com.substation.display;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** 为动态添加小车选择 CarId：优先补全黑板上无进程的缺口编号。 */
final class DynamicCarIdResolver {

    private DynamicCarIdResolver() {
    }

    /**
     * 选择下一台要启动进程的小车 ID。
     *
     * <p>TaskConfigurator 会按 carCount 预注册 Car001..CarN，但进程可能少于 N。
     * 此时应优先为已注册、尚无 JVM 的编号启动进程，而不是直接 max+1。</p>
     */
    static String resolve(Collection<String> onBoardCarIds,
                          Set<String> externalProcessCarIds,
                          Set<String> displayLaunchedCarIds,
                          Predicate<String> isProcessRunning) {
        List<String> sorted = new ArrayList<>(onBoardCarIds);
        sorted.sort(Comparator.comparingInt(WebSocketBridge::extractCarNumber));

        for (String carId : sorted) {
            if (!hasManagedProcess(carId, externalProcessCarIds, displayLaunchedCarIds, isProcessRunning)) {
                return carId;
            }
        }

        int nextNumber = highestAssignedNumber(onBoardCarIds, externalProcessCarIds, displayLaunchedCarIds) + 1;
        return String.format("Car%03d", nextNumber);
    }

    private static int highestAssignedNumber(Collection<String> onBoardCarIds,
                                             Set<String> externalProcessCarIds,
                                             Set<String> displayLaunchedCarIds) {
        int maxNumber = maxNumberIn(onBoardCarIds);
        maxNumber = Math.max(maxNumber, maxNumberIn(externalProcessCarIds));
        maxNumber = Math.max(maxNumber, maxNumberIn(displayLaunchedCarIds));
        return maxNumber;
    }

    private static int maxNumberIn(Collection<String> carIds) {
        return carIds.stream()
                .mapToInt(WebSocketBridge::extractCarNumber)
                .max()
                .orElse(0);
    }

    private static boolean hasManagedProcess(String carId,
                                             Set<String> externalProcessCarIds,
                                             Set<String> displayLaunchedCarIds,
                                             Predicate<String> isProcessRunning) {
        if (externalProcessCarIds.contains(carId)) {
            return true;
        }
        if (!displayLaunchedCarIds.contains(carId)) {
            return false;
        }
        return isProcessRunning.test(carId);
    }
}
