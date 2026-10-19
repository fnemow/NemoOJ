package com.nemo.nemosandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行库准备测试。
 * <p>
 * 对应 T08 的第一条：修复运行库准备方式，支持可执行 Boot JAR 中的嵌套依赖；
 * 缺少关键依赖时给出系统错误，不能继续导致用户代码被误判为编译错误。
 * <p>
 * 这里的断言直接针对旧实现的失效点：旧实现通过 Gson 类的 CodeSource URL 反查 JAR 路径，
 * 在可执行 Boot JAR 中该 URL 指向 {@code BOOT-INF/lib/...} 这样的**嵌套路径**，
 * 并不是真实文件，转换 File 必然失败。
 */
class RuntimeLibPreparationTest {

    @Test
    @DisplayName("从类路径资源准备运行库，且内容非空")
    void preparesRuntimeLibFromClasspathResource(@TempDir Path tempDir) throws Exception {
        JavaCodeSandBoxTemplate template = new JavaCodeSandBoxTemplate();
        File workDir = tempDir.toFile();

        template.prepareLibDir(workDir);

        File lib = new File(workDir, "lib/" + JavaCodeSandBoxTemplate.RUNTIME_LIB_FILENAME);
        assertTrue(lib.isFile(), "运行库应被写入 lib/ 目录");
        assertTrue(lib.length() > 0, "运行库不应为空文件");

        // 必须是一个可用的 JAR（ZIP 魔数 PK）
        byte[] head = Files.readAllBytes(lib.toPath());
        assertNotNull(head);
        assertTrue(head.length > 2);
        assertEquals('P', (char) head[0], "JAR 应以 PK 魔数开头");
        assertEquals('K', (char) head[1], "JAR 应以 PK 魔数开头");
    }

    @Test
    @DisplayName("重复准备会覆盖旧文件而不是失败")
    void preparationIsIdempotent(@TempDir Path tempDir) throws Exception {
        JavaCodeSandBoxTemplate template = new JavaCodeSandBoxTemplate();
        File workDir = tempDir.toFile();

        template.prepareLibDir(workDir);
        long firstLength = new File(workDir, "lib/" + JavaCodeSandBoxTemplate.RUNTIME_LIB_FILENAME).length();
        template.prepareLibDir(workDir);

        assertEquals(firstLength,
                new File(workDir, "lib/" + JavaCodeSandBoxTemplate.RUNTIME_LIB_FILENAME).length(),
                "重复准备应得到同样的结果");
    }

    @Test
    @DisplayName("运行库路径使用类路径资源常量，不再依赖 CodeSource URL")
    void doesNotDependOnCodeSourceLocation() throws Exception {
        // 旧实现依赖 Gson.class.getProtectionDomain().getCodeSource().getLocation()，
        // 这里确认资源确实位于 BOOT-INF/classes 下的固定位置（构建期由
        // maven-dependency-plugin 复制），无论是否运行在嵌套 Boot JAR 中都可读取。
        try (var in = JavaCodeSandBoxTemplate.class.getResourceAsStream("/sandbox-libs/gson.jar")) {
            assertNotNull(in,
                    "运行库资源缺失：请确认构建时 maven-dependency-plugin 已执行");
            assertTrue(in.read() >= 0, "资源应可读");
        }
    }

}
