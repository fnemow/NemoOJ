package com.nemo.nemosandbox;

import com.nemo.nemosandbox.model.SourceFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JavaCodeSandBoxTemplate} 源文件校验单元测试。
 * <p>
 * 覆盖用户可控文件名/内容进入沙箱工作目录前的边界校验：路径穿越、扩展名白名单、
 * 数量与体积上限。这些校验是沙箱的第一道防线，完全脱离 Docker，可在任意环境执行。
 */
class JavaCodeSandBoxTemplateTest {

    private final JavaCodeSandBoxTemplate template = new JavaCodeSandBoxTemplate();

    /** 成功用例创建的目录需要清理，避免污染工作区 */
    private final List<File> createdDirs = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (File dir : createdDirs) {
            template.clearWorkDir(dir);
        }
        createdDirs.clear();
    }

    private File saveAndTrack(List<SourceFile> files) {
        File workDir = template.saveSourceFilesToDir(files);
        createdDirs.add(workDir);
        return workDir;
    }

    // ---------- 正常路径 ----------

    @Test
    @DisplayName("合法源文件写入工作目录")
    void savesValidSourceFiles() {
        File workDir = saveAndTrack(Arrays.asList(
                new SourceFile("Main.java", "public class Main {}"),
                new SourceFile("Helper.java", "class Helper {}")));

        assertTrue(workDir.isDirectory(), "工作目录应被创建");
        assertTrue(new File(workDir, "Main.java").isFile(), "Main.java 应已写入");
        assertTrue(new File(workDir, "Helper.java").isFile(), "Helper.java 应已写入");
    }

    @Test
    @DisplayName("工作目录位于 tmpCode 下且每次不同")
    void workDirIsUnique() {
        File first = saveAndTrack(Collections.singletonList(new SourceFile("Main.java", "class Main {}")));
        File second = saveAndTrack(Collections.singletonList(new SourceFile("Main.java", "class Main {}")));

        assertNotNull(first.getParentFile());
        assertEquals(JavaCodeSandBoxTemplate.GLOBAL_CODE_DIR_NAME, first.getParentFile().getName());
        assertTrue(!first.getAbsolutePath().equals(second.getAbsolutePath()),
                "两次执行应使用不同的工作目录，避免互相覆盖");
    }

    // ---------- 路径穿越 ----------

    @Test
    @DisplayName("拒绝含父目录引用的文件名")
    void rejectsParentTraversal() {
        // "../evil.java" 同时含路径分隔符与父目录引用，任一规则拦下即可；
        // 只断言"被拒绝且给出可读原因"，避免测试与校验顺序耦合。
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                template.saveSourceFilesToDir(Collections.singletonList(
                        new SourceFile("../evil.java", "class Evil {}"))));
        assertNotNull(ex.getMessage(), "拒绝时应给出原因");

        // 不含分隔符、仅含 ".." 的形式必须由父目录引用规则拦下
        IllegalArgumentException dotOnly = assertThrows(IllegalArgumentException.class, () ->
                template.saveSourceFilesToDir(Collections.singletonList(
                        new SourceFile("..Main.java", "class Main {}"))));
        assertTrue(dotOnly.getMessage().contains("父目录引用"),
                "错误信息应指明父目录引用: " + dotOnly.getMessage());
    }

    @Test
    @DisplayName("拒绝含路径分隔符的文件名")
    void rejectsPathSeparator() {
        assertThrows(IllegalArgumentException.class, () ->
                template.saveSourceFilesToDir(Collections.singletonList(
                        new SourceFile("sub/Main.java", "class Main {}"))));
        assertThrows(IllegalArgumentException.class, () ->
                template.saveSourceFilesToDir(Collections.singletonList(
                        new SourceFile("sub\\Main.java", "class Main {}"))));
    }

    @Test
    @DisplayName("拒绝绝对路径文件名")
    void rejectsAbsolutePath() {
        assertThrows(IllegalArgumentException.class, () ->
                template.saveSourceFilesToDir(Collections.singletonList(
                        new SourceFile("/etc/passwd.java", "class X {}"))));
    }

    @Test
    @DisplayName("拒绝隐藏的穿越变体：先通过前缀校验再拼接")
    void rejectsTraversalWithSafeExtension() {
        // "....//" 这类变形同样含 ".."，必须被拒绝
        assertThrows(IllegalArgumentException.class, () ->
                template.saveSourceFilesToDir(Collections.singletonList(
                        new SourceFile("....//Main.java", "class Main {}"))));
    }

    // ---------- 文件名与内容校验 ----------

    @Test
    @DisplayName("拒绝空文件名、空内容与 null 条目")
    void rejectsBlankAndNull() {
        assertThrows(IllegalArgumentException.class, () ->
                template.saveSourceFilesToDir(Collections.singletonList(
                        new SourceFile(null, "class Main {}"))));
        assertThrows(IllegalArgumentException.class, () ->
                template.saveSourceFilesToDir(Collections.singletonList(
                        new SourceFile("   ", "class Main {}"))));
        assertThrows(IllegalArgumentException.class, () ->
                template.saveSourceFilesToDir(Collections.singletonList(
                        new SourceFile("Main.java", null))));
        assertThrows(IllegalArgumentException.class, () ->
                template.saveSourceFilesToDir(Collections.singletonList(
                        (SourceFile) null)));
    }

    @Test
    @DisplayName("拒绝重复文件名")
    void rejectsDuplicateFileNames() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                template.saveSourceFilesToDir(Arrays.asList(
                        new SourceFile("Main.java", "class Main {}"),
                        new SourceFile("Main.java", "class Main2 {}"))));
        assertTrue(ex.getMessage().contains("重复"), "错误信息应指明重复: " + ex.getMessage());
    }

    @Test
    @DisplayName("拒绝白名单外的扩展名与无扩展名文件")
    void rejectsDisallowedExtension() {
        assertThrows(IllegalArgumentException.class, () ->
                template.saveSourceFilesToDir(Collections.singletonList(
                        new SourceFile("run.sh", "rm -rf /"))));
        assertThrows(IllegalArgumentException.class, () ->
                template.saveSourceFilesToDir(Collections.singletonList(
                        new SourceFile("Main", "class Main {}"))));
    }

    // ---------- 数量与体积上限 ----------

    @Test
    @DisplayName("拒绝空列表与超过数量的源文件")
    void rejectsTooManyFiles() {
        assertThrows(IllegalArgumentException.class, () ->
                template.saveSourceFilesToDir(Collections.emptyList()));
        assertThrows(IllegalArgumentException.class, () ->
                template.saveSourceFilesToDir(null));

        List<SourceFile> tooMany = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            tooMany.add(new SourceFile("F" + i + ".java", "class F" + i + " {}"));
        }
        assertThrows(IllegalArgumentException.class, () -> template.saveSourceFilesToDir(tooMany));
    }

    @Test
    @DisplayName("拒绝超过单文件大小上限的源文件")
    void rejectsOversizedFile() {
        StringBuilder big = new StringBuilder(600 * 1024);
        while (big.length() < 600 * 1024) {
            big.append("// padding line to exceed limit\n");
        }
        assertThrows(IllegalArgumentException.class, () ->
                template.saveSourceFilesToDir(Collections.singletonList(
                        new SourceFile("Main.java", big.toString()))));
    }

    @Test
    @DisplayName("校验失败不留下工作目录")
    void failedValidationLeavesNoDirectory() {
        String globalDir = System.getProperty("user.dir") + File.separator
                + JavaCodeSandBoxTemplate.GLOBAL_CODE_DIR_NAME;
        int before = countChildren(new File(globalDir));

        assertThrows(IllegalArgumentException.class, () ->
                template.saveSourceFilesToDir(Arrays.asList(
                        new SourceFile("Main.java", "class Main {}"),
                        new SourceFile("../evil.java", "class Evil {}"))));

        assertEquals(before, countChildren(new File(globalDir)),
                "校验失败时不应创建任何工作目录");
    }

    private int countChildren(File dir) {
        String[] children = dir.list();
        return children == null ? 0 : children.length;
    }
}
