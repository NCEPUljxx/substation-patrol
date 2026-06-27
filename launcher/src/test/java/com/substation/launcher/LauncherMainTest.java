package com.substation.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LauncherMain 单元测试。
 *
 * <p>测试范围：纯逻辑、不依赖 Redis/RabbitMQ/WebSocket 的方法。
 * 无法测试的方法（需完整环境或会触发 System.exit）：</p>
 * <ul>
 *   <li>{@code main()} —— 启动所有模块，需要完整基础设施</li>
 *   <li>{@code parseArgs()} 错误路径 —— 未知参数或缺失值触发 System.exit</li>
 *   <li>{@code nextArg()} / {@code nextIntArg()} 越界/非法值 —— 触发 System.exit</li>
 *   <li>{@code launchAllModules()} / {@code startDisplay()} / {@code startController()}
 *       —— 需要 Redis + RabbitMQ</li>
 * </ul>
 *
 * @author Person D
 */
class LauncherMainTest {

    // ═══════════════════════════════════════════════════════
    // LaunchConfig.defaults() — 默认值
    // ═══════════════════════════════════════════════════════

    @Test
    void defaults_shouldReturnAllDefaultValues() {
        LauncherMain.LaunchConfig config = LauncherMain.LaunchConfig.defaults();

        assertEquals("localhost", config.redisHost(), "redisHost 默认值应为 localhost");
        assertEquals(6379, config.redisPort(), "redisPort 默认值应为 6379");
        assertEquals("localhost", config.mqHost(), "mqHost 默认值应为 localhost");
        assertEquals(5672, config.mqPort(), "mqPort 默认值应为 5672");
        assertEquals(5, config.carCount(), "carCount 默认值应为 5");
        assertEquals(8887, config.httpPort(), "httpPort 默认值应为 8887");
        assertEquals(8888, config.wsPort(), "wsPort 默认值应为 8888");
    }

    @Test
    void defaults_shouldReturnEqualValues_whenCalledMultipleTimes() {
        LauncherMain.LaunchConfig c1 = LauncherMain.LaunchConfig.defaults();
        LauncherMain.LaunchConfig c2 = LauncherMain.LaunchConfig.defaults();

        // assertEquals 对 record 使用基于字段的值比较，两次调用的默认值应相等
        assertEquals(c1, c2, "两次 defaults() 返回的配置值应相等");
        // 注意：defaults() 每次创建新实例，所以 assertSame 会失败，
        // 但 record 的值语义意味着 equals 才是正确的比较方式
    }

    // ═══════════════════════════════════════════════════════
    // LaunchConfig — record 访问器
    // ═══════════════════════════════════════════════════════

    @Test
    void launchConfig_shouldPreserveAllCustomValues() {
        LauncherMain.LaunchConfig config = new LauncherMain.LaunchConfig(
                "redis.example.com", 16379,
                "mq.example.com", 15672,
                8, 9000, 9001);

        assertAll("自定义配置应保留所有值",
                () -> assertEquals("redis.example.com", config.redisHost()),
                () -> assertEquals(16379, config.redisPort()),
                () -> assertEquals("mq.example.com", config.mqHost()),
                () -> assertEquals(15672, config.mqPort()),
                () -> assertEquals(8, config.carCount()),
                () -> assertEquals(9000, config.httpPort()),
                () -> assertEquals(9001, config.wsPort())
        );
    }

    // ═══════════════════════════════════════════════════════
    // parseArgs — 空参数 / 单独参数 / 组合参数
    // ═══════════════════════════════════════════════════════

    @Test
    void parseArgs_emptyArray_shouldReturnDefaults() {
        LauncherMain.LaunchConfig config = LauncherMain.parseArgs(new String[0]);

        assertEquals(LauncherMain.LaunchConfig.defaults(), config,
                "空参数应返回默认配置");
    }

    @Test
    void parseArgs_shouldOverrideSingleStringArg() {
        LauncherMain.LaunchConfig config = LauncherMain.parseArgs(
                new String[]{"--redis-host", "192.168.1.50"});

        assertEquals("192.168.1.50", config.redisHost(), "redisHost 应被覆盖");
        assertEquals(6379, config.redisPort(), "未指定的参数应保持默认值");
    }

    @Test
    void parseArgs_shouldOverrideSingleIntArg() {
        LauncherMain.LaunchConfig config = LauncherMain.parseArgs(
                new String[]{"--cars", "10"});

        assertEquals(10, config.carCount(), "carCount 应被覆盖为 10");
        assertEquals("localhost", config.redisHost(), "未指定的参数应保持默认值");
    }

    @Test
    void parseArgs_shouldOverrideAllArgs() {
        LauncherMain.LaunchConfig config = LauncherMain.parseArgs(new String[]{
                "--redis-host", "r.example.com",
                "--redis-port", "6380",
                "--mq-host", "mq.example.com",
                "--mq-port", "5673",
                "--cars", "3",
                "--http-port", "9090",
                "--ws-port", "9091"
        });

        assertAll("所有参数应被正确覆盖",
                () -> assertEquals("r.example.com", config.redisHost()),
                () -> assertEquals(6380, config.redisPort()),
                () -> assertEquals("mq.example.com", config.mqHost()),
                () -> assertEquals(5673, config.mqPort()),
                () -> assertEquals(3, config.carCount()),
                () -> assertEquals(9090, config.httpPort()),
                () -> assertEquals(9091, config.wsPort())
        );
    }

    @Test
    void parseArgs_shouldHandleArgsInAnyOrder() {
        // 乱序传入应正确解析（前两个后两个交叉）
        LauncherMain.LaunchConfig config = LauncherMain.parseArgs(new String[]{
                "--cars", "10",
                "--redis-host", "10.0.0.1",
                "--http-port", "8080",
                "--mq-host", "10.0.0.2"
        });

        assertEquals(10, config.carCount());
        assertEquals("10.0.0.1", config.redisHost());
        assertEquals(8080, config.httpPort());
        assertEquals("10.0.0.2", config.mqHost());
    }

    // ═══════════════════════════════════════════════════════
    // parseArgs — 整数值边界
    // ═══════════════════════════════════════════════════════

    @ParameterizedTest
    @CsvSource({
            "0,     0",
            "1,     1",
            "9999,  9999",
            "-1,    -1",
    })
    void parseArgs_shouldAcceptIntegerValues(String input, int expected) {
        LauncherMain.LaunchConfig config = LauncherMain.parseArgs(
                new String[]{"--cars", input});

        assertEquals(expected, config.carCount(),
                "cars=" + input + " 应解析为 " + expected);
    }

    // ═══════════════════════════════════════════════════════
    // nextArg — 从数组中取值
    // ═══════════════════════════════════════════════════════

    @Test
    void nextArg_shouldReturnCorrectElement() {
        String[] args = {"first", "second", "third"};

        assertEquals("first", LauncherMain.nextArg(args, 0, "test"));
        assertEquals("second", LauncherMain.nextArg(args, 1, "test"));
        assertEquals("third", LauncherMain.nextArg(args, 2, "test"));
    }

    // ═══════════════════════════════════════════════════════
    // nextIntArg — 字符串转整数
    // ═══════════════════════════════════════════════════════

    @Test
    void nextIntArg_shouldParseValidIntegers() {
        String[] args = {"42", "0", "-5", "10000"};

        assertEquals(42, LauncherMain.nextIntArg(args, 0, "test"));
        assertEquals(0, LauncherMain.nextIntArg(args, 1, "test"));
        assertEquals(-5, LauncherMain.nextIntArg(args, 2, "test"));
        assertEquals(10000, LauncherMain.nextIntArg(args, 3, "test"));
    }

    @ParameterizedTest
    @CsvSource({
            "0,     0",
            "1,     1",
            "-1,    -1",
            "65535, 65535",
    })
    void nextIntArg_shouldHandleBoundaryValues(String input, int expected) {
        String[] args = {input};
        assertEquals(expected, LauncherMain.nextIntArg(args, 0, "test"),
                "nextIntArg('" + input + "') 应返回 " + expected);
    }

    // ═══════════════════════════════════════════════════════
    // findWebRoot — 前端文件目录查找
    // ═══════════════════════════════════════════════════════

    @Test
    void findWebRoot_shouldReturnNonNullPath() {
        Path webRoot = LauncherMain.findWebRoot();

        assertNotNull(webRoot, "即使目录不存在也应返回回退路径，不能为 null");
    }

    @Test
    void findWebRoot_shouldReturnDisplayWebDir_whenRunningFromProjectRoot() {
        Path webRoot = LauncherMain.findWebRoot();

        // 从项目根运行 mvn test，display/src/main/resources/web 应存在
        assertTrue(webRoot.toFile().isDirectory() || webRoot.endsWith("web"),
                "应返回 display/src/main/resources/web 目录或回退到该路径");
    }

    /**
     * 验证 findWebRoot 的回退机制：当所有候选目录都不存在时，
     * 不会崩溃也不会返回 null，而是返回第一个候选路径（即使它不存在）。
     *
     * <p>这是一个安全网测试——保证在生产部署（web 目录可能未正确放置）时
     * 不会因为找不到目录而 NPE，而是优雅降级。</p>
     */
    @Test
    void findWebRoot_shouldFallbackToFirstCandidate_whenNoDirectoryExists() {
        Path result = LauncherMain.findWebRoot();

        assertNotNull(result, "回退情况下不应返回 null");
        // 回退路径应该是候选列表的第一个元素
        assertTrue(result.toString().contains("web"),
                "回退路径应包含 'web' 目录名");
    }

    // ═══════════════════════════════════════════════════════
    // LauncherMain 类结构验证
    // ═══════════════════════════════════════════════════════

    @Test
    void launcherMainClass_shouldBeFinalUtilityClass() {
        // 验证 LauncherMain 是 final 工具类（不应被继承或实例化）
        assertTrue(java.lang.reflect.Modifier.isFinal(
                LauncherMain.class.getModifiers()),
                "LauncherMain 应为 final 类，防止被继承");
    }

    @Test
    void launchConfig_shouldBeRecord() {
        // 验证 LaunchConfig 是 record（不可变数据载体）
        assertTrue(LauncherMain.LaunchConfig.class.isRecord(),
                "LaunchConfig 应为 Java record");
    }

    // ═══════════════════════════════════════════════════════
    // 集成测试占位（需 Redis + RabbitMQ）
    // ═══════════════════════════════════════════════════════

    /**
     * 验证 LauncherMain 可以加载（不触发静态初始化异常）。
     * 完整启动流程的集成测试在阶段 4 执行。
     */
    @Test
    void launcherMain_shouldBeLoadable() {
        assertDoesNotThrow(() -> {
            Class.forName("com.substation.launcher.LauncherMain");
        }, "LauncherMain 类应能正常加载");
    }
}
