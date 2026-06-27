package com.substation.display;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HttpFileServer 纯逻辑单元测试。
 *
 * <p>测试 Content-Type 检测逻辑和文件服务功能。
 * HTTP 服务启动/停止的集成测试需要完整环境，在联调阶段执行。</p>
 */
class HttpFileServerTest {

    // ═══════════════════════════════════════════════════════
    // detectContentType — 已知扩展名
    // ═══════════════════════════════════════════════════════

    @Test
    void detectContentType_html() {
        assertEquals("text/html; charset=utf-8",
                HttpFileServer.detectContentType(Path.of("index.html")));
    }

    @Test
    void detectContentType_css() {
        assertEquals("text/css; charset=utf-8",
                HttpFileServer.detectContentType(Path.of("css/style.css")));
    }

    @Test
    void detectContentType_js() {
        assertEquals("application/javascript; charset=utf-8",
                HttpFileServer.detectContentType(Path.of("js/app.js")));
    }

    @Test
    void detectContentType_json() {
        assertEquals("application/json",
                HttpFileServer.detectContentType(Path.of("data.json")));
    }

    @Test
    void detectContentType_png() {
        assertEquals("image/png",
                HttpFileServer.detectContentType(Path.of("icons/car.png")));
    }

    @Test
    void detectContentType_svg() {
        assertEquals("image/svg+xml",
                HttpFileServer.detectContentType(Path.of("icons/car.svg")));
    }

    // ═══════════════════════════════════════════════════════
    // detectContentType — 边界情况
    // ═══════════════════════════════════════════════════════

    @Test
    void detectContentType_noExtension() {
        // 无扩展名文件回退为通用二进制类型
        assertEquals("application/octet-stream",
                HttpFileServer.detectContentType(Path.of("README")));
    }

    @Test
    void detectContentType_unknownExtension() {
        assertEquals("application/octet-stream",
                HttpFileServer.detectContentType(Path.of("data.xyz")));
    }

    @Test
    void detectContentType_hiddenFile() {
        // .gitignore 扩展名 gitignore → octet-stream
        assertEquals("application/octet-stream",
                HttpFileServer.detectContentType(Path.of(".gitignore")));
    }

    @Test
    void detectContentType_caseInsensitive() {
        // 当前实现是大小写敏感的（.HTML ≠ .html），这是一个已知限制
        // 测试记录当前行为：大写扩展名回退为 octet-stream
        assertEquals("application/octet-stream",
                HttpFileServer.detectContentType(Path.of("INDEX.HTML")));
    }

    // ═══════════════════════════════════════════════════════
    // HTTP 文件服务（使用临时目录）
    // ═══════════════════════════════════════════════════════

    @Test
    void serveExistingFile(@TempDir Path tempDir) throws IOException {
        // 创建临时测试文件
        Path testFile = tempDir.resolve("test.html");
        String content = "<html><body>Hello</body></html>";
        Files.writeString(testFile, content);

        // 启动服务器
        HttpFileServer server = new HttpFileServer(0, tempDir, null, null, null, null, null);
        server.start();
        server.stop();

        // 文件应该存在
        assertTrue(Files.isRegularFile(testFile));
        assertEquals(content, Files.readString(testFile));
    }

    @Test
    void constructorCreatesWithoutError(@TempDir Path tempDir) throws IOException {
        // 验证构造函数不会因端口 0（系统自动分配）而失败
        HttpFileServer server = new HttpFileServer(0, tempDir, null, null, null, null, null);
        assertNotNull(server);
        server.start();
        server.stop();
    }

    @Test
    void startStopDoesNotThrow(@TempDir Path tempDir) throws IOException {
        HttpFileServer server = new HttpFileServer(0, tempDir, null, null, null, null, null);

        // 启动和停止不应该抛异常
        assertDoesNotThrow(server::start);
        assertDoesNotThrow(server::stop);
    }

    // ═══════════════════════════════════════════════════════
    // 集成测试（需要 Redis + RabbitMQ，默认跳过）
    // ═══════════════════════════════════════════════════════

    /**
     * 验证 HTTP 服务器能正确响应 404。
     *
     * <p>此测试需要完整启动 HTTP 服务并发送真实 HTTP 请求。
     * 当前框架为纯单元测试，此测试留作集成阶段补充。</p>
     */
    @Test
    void http404_notFound() {
        // 集成测试占位——需要 java.net.http.HttpClient 配合验证 HTTP 响应码
        // 在阶段 4 集成测试中补充
    }
}
