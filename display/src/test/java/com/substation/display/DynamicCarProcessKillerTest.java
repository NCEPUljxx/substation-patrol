package com.substation.display;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicCarProcessKillerTest {

    @Test
    void matchesSharedJarDynamicLaunch() {
        String cmd = "java -jar D:\\substation-patrol\\car\\target\\car-1.0-SNAPSHOT.jar Car005 --dynamic";
        assertTrue(DynamicCarProcessKiller.commandLineTargetsCar(cmd, "Car005"));
        assertFalse(DynamicCarProcessKiller.commandLineTargetsCar(cmd, "Car006"));
    }

    @Test
    void matchesTimestampedRuntimeJarDynamicLaunch() {
        String cmd = "java -jar D:\\substation-patrol\\logs\\runtime\\Car004-1782214269601.jar Car004 --dynamic";
        assertTrue(DynamicCarProcessKiller.commandLineTargetsCar(cmd, "Car004"));
        assertEquals("Car004", DynamicCarProcessKiller.extractDynamicCarId(cmd));
    }

    @Test
    void matchesRuntimeJarDynamicLaunch() {
        String cmd = "java -jar D:\\substation-patrol\\logs\\runtime\\Car006.jar Car006 --dynamic";
        assertTrue(DynamicCarProcessKiller.commandLineTargetsCar(cmd, "Car006"));
        assertEquals("Car006", DynamicCarProcessKiller.extractDynamicCarId(cmd));
    }

    @Test
    void ignoresExternalCarWithoutDynamicFlag() {
        String cmd = "java -classpath ... com.substation.car.CarMain Car003";
        assertFalse(DynamicCarProcessKiller.commandLineTargetsCar(cmd, "Car003"));
        assertNull(DynamicCarProcessKiller.extractDynamicCarId(cmd));
    }
}
