package com.substation.display;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DynamicCarIdResolverTest {

    private static final java.util.function.Predicate<String> ALL_ALIVE = carId -> true;
    private static final java.util.function.Predicate<String> ALL_DEAD = carId -> false;

    @Test
    void fillsOrphanOnBlackboardBeforeCreatingNewId() {
        List<String> onBoard = List.of("Car001", "Car002", "Car003", "Car004", "Car005", "Car006");
        Set<String> external = Set.of("Car001", "Car002", "Car003");

        assertEquals("Car004", DynamicCarIdResolver.resolve(onBoard, external, Set.of(), ALL_ALIVE));
        assertEquals("Car005", DynamicCarIdResolver.resolve(onBoard, external, Set.of("Car004"), ALL_ALIVE));
    }

    @Test
    void createsNextIdWhenAllOnBoardCarsHaveProcesses() {
        List<String> onBoard = List.of("Car001", "Car002", "Car003");
        Set<String> external = Set.of("Car001", "Car002", "Car003");

        assertEquals("Car004", DynamicCarIdResolver.resolve(onBoard, external, Set.of(), ALL_ALIVE));
    }

    @Test
    void startsAtCar001WhenBlackboardEmpty() {
        assertEquals("Car001", DynamicCarIdResolver.resolve(List.of(), Set.of(), Set.of(), ALL_ALIVE));
    }

    @Test
    void skipsPendingLaunchIdBeforeBoardRegistration() {
        List<String> onBoard = List.of("Car001", "Car002", "Car003");
        Set<String> external = Set.of("Car001", "Car002", "Car003");

        assertEquals("Car004", DynamicCarIdResolver.resolve(onBoard, external, Set.of(), ALL_ALIVE));
        assertEquals("Car005", DynamicCarIdResolver.resolve(onBoard, external, Set.of("Car004"), ALL_ALIVE));
    }

    @Test
    void reclaimsDeadDisplayLaunchBeforeNextNewId() {
        List<String> onBoard = List.of("Car001", "Car002", "Car003", "Car004", "Car005", "Car006");
        Set<String> external = Set.of("Car001", "Car002", "Car003");
        Set<String> deadLaunches = Set.of("Car004", "Car005");

        assertEquals("Car004", DynamicCarIdResolver.resolve(onBoard, external, deadLaunches, ALL_DEAD));
        assertEquals("Car005", DynamicCarIdResolver.resolve(
                onBoard, external, Set.of("Car004", "Car005"), id -> "Car004".equals(id)));
    }
}
