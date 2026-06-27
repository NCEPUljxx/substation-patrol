package com.substation.display;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicCarLauncherTest {

    @Test
    void isLaunchAvailable_falseWhenMissing(@TempDir Path projectRoot) {
        assertFalse(DynamicCarLauncher.isLaunchAvailable(projectRoot));
    }

    @Test
    void isLaunchAvailable_trueWhenJarExists(@TempDir Path projectRoot) throws Exception {
        Path jarDir = projectRoot.resolve("car/target");
        Files.createDirectories(jarDir);
        Files.createFile(jarDir.resolve("car-1.0-SNAPSHOT.jar"));
        assertTrue(DynamicCarLauncher.isLaunchAvailable(projectRoot));
    }
}
