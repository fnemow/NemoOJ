package com.nemo.nemosandbox;

import com.nemo.nemosandbox.model.*;
import com.nemo.nemosandbox.runtime.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TrustedCompileArtifactCacheTest {
    @TempDir Path root;
    @Test void keyBindsSourcesSdkCommandPolicyAndImmutableImageButNotInputs() throws Exception {
        var cache = new TrustedCompileArtifactCache(root.resolve("cache"), Clock.systemUTC(), 8, 1048576, 60000);
        var work = Files.createDirectories(root.resolve("work")); var request = request(); var adapter = adapter();
        var cmd = new CommandSpec(new String[]{"javac", "/app/Main.java"});
        String key = cache.key(request, adapter, cmd, work);
        assertNotNull(key);
        request.getExecutionPlan().setCases(List.of(new ExecutionCaseInput(0, "different input")));
        assertEquals(key, cache.key(request, adapter, cmd, work));
        assertNotEquals(key, cache.key(request, adapter, new CommandSpec(new String[]{"javac", "-g", "/app/Main.java"}), work));
        Files.createDirectories(work.resolve("lib")); Files.writeString(work.resolve("lib/sdk.jar"), "sdk-v1");
        String sdkKey = cache.key(request, adapter, cmd, work); assertNotEquals(key, sdkKey);
        Files.writeString(work.resolve("lib/sdk.jar"), "sdk-v2"); assertNotEquals(sdkKey, cache.key(request, adapter, cmd, work));
        request.getExecutionPlan().setSourceFiles(List.of(new SourceFile("Main.java", "changed source")));
        assertNotEquals(key, cache.key(request, adapter, cmd, work));
    }
    @Test void interactiveFreeGenerationPythonAndMutableImagesCannotUseCache() throws Exception {
        var cache = new TrustedCompileArtifactCache(root, Clock.systemUTC(), 8, 1048576, 60000);
        var request = request(); var cmd = new CommandSpec(new String[]{"javac"});
        request.setBackgroundValidation(false); assertNull(cache.key(request, adapter(), cmd, root));
        request.setBackgroundValidation(true); request.setTrustedCompileScope(null); assertNull(cache.key(request, adapter(), cmd, root));
        request.setTrustedCompileScope("trusted-v1:prefix_matrix");
        var mutable = new JavaRuntimeAdapter(); org.springframework.test.util.ReflectionTestUtils.setField(mutable,"imageRef","mutable:latest"); org.springframework.test.util.ReflectionTestUtils.invokeMethod(mutable,"init");
        assertNull(cache.key(request, mutable, cmd, root));
        request.getExecutionPlan().setLanguage("python"); assertNull(cache.key(request, adapter(), cmd, root));
    }
    @Test void restoresOnlyCompileOutputsAsIndependentFilesAndRejectsCorruption() throws Exception {
        var cache = new TrustedCompileArtifactCache(root.resolve("cache"), Clock.systemUTC(), 8, 1048576, 60000);
        var work = Files.createDirectories(root.resolve("work")); Files.writeString(work.resolve("Main.class"), "compiled");
        Files.writeString(work.resolve("Main.java"), "source"); Files.writeString(work.resolve("secret.txt"), "must not cache");
        String key = "a".repeat(64); assertTrue(cache.store(key, "java", work));
        var second = Files.createDirectories(root.resolve("second")); assertTrue(cache.restore(key, "java", second));
        assertEquals("compiled", Files.readString(second.resolve("Main.class"))); assertFalse(Files.exists(second.resolve("secret.txt")));
        Files.writeString(second.resolve("Main.class"), "mutated");
        var third = Files.createDirectories(root.resolve("third")); assertTrue(cache.restore(key, "java", third));
        assertEquals("compiled", Files.readString(third.resolve("Main.class")));
        Files.writeString(root.resolve("cache").resolve(key).resolve("Main.class"), "corrupted");
        assertFalse(cache.restore(key, "java", Files.createDirectories(root.resolve("fourth"))));
    }
    @Test void evictsExpiredAndExcessEntriesAndDeclinesOversizedArtifacts() throws Exception {
        Instant now = Instant.now();
        var cache = new TrustedCompileArtifactCache(root.resolve("cache"), Clock.fixed(now,ZoneOffset.UTC), 1, 1024, 1000);
        var work = Files.createDirectories(root.resolve("work")); Files.writeString(work.resolve("Main.class"), "compiled");
        assertTrue(cache.store("a".repeat(64), "java", work)); assertTrue(cache.store("b".repeat(64), "java", work));
        assertTrue(cache.entryCount() <= 1);
        var expired = new TrustedCompileArtifactCache(root.resolve("cache"), Clock.fixed(now.plusSeconds(2),ZoneOffset.UTC), 1, 1024, 1000);
        assertFalse(expired.restore("b".repeat(64), "java", work));
        Files.write(work.resolve("Main.class"), new byte[2048]); assertFalse(cache.store("c".repeat(64), "java", work));
    }
    @Test void cacheHitSkipsCompilerButStillRunsEveryCaseAndClearsWorkspaces() throws Exception {
        var cache=new TrustedCompileArtifactCache(root.resolve("cache"),Clock.systemUTC(),8,1048576,60000);
        var engine=org.mockito.Mockito.mock(DockerExecutionEngine.class); var runtime=adapter();
        org.mockito.Mockito.when(engine.compile(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.eq(true)))
            .thenAnswer(call->{Files.writeString(Path.of(call.getArgument(1,String.class)).resolve("Main.class"),"compiled");return new DockerExecutionEngine.ExecutionResult(0,"","");});
        org.mockito.Mockito.when(engine.executeOneCase(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.eq(true),org.mockito.ArgumentMatchers.anyBoolean()))
            .thenAnswer(call->{assertTrue(Files.exists(Path.of(call.getArgument(1,String.class)).resolve("Main.class")));return CaseExecutionResult.builder().status(0).stdout("ok").time(1L).memory(1L).build();});
        var sandbox=new JavaDockerCodeSandBox();
        org.springframework.test.util.ReflectionTestUtils.setField(sandbox,"engine",engine);
        org.springframework.test.util.ReflectionTestUtils.setField(sandbox,"runtimeRegistry",new LanguageRuntimeRegistry(List.of(runtime)));
        org.springframework.test.util.ReflectionTestUtils.setField(sandbox,"compileCache",cache);
        org.springframework.test.util.ReflectionTestUtils.setField(sandbox,"executionMetrics",org.mockito.Mockito.mock(com.nemo.nemosandbox.config.SandboxExecutionMetrics.class));
        org.springframework.test.util.ReflectionTestUtils.setField(sandbox,"languageEnvironment",new org.springframework.mock.env.MockEnvironment());
        var request=request();request.getExecutionPlan().setCases(List.of(new ExecutionCaseInput(0,"a"),new ExecutionCaseInput(1,"b")));
        assertEquals(0,sandbox.executeCodeV2(request).getOverallStatus());assertEquals(0,sandbox.executeCodeV2(request).getOverallStatus());
        org.mockito.Mockito.verify(engine,org.mockito.Mockito.times(1)).compile(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.eq(true));
        org.mockito.Mockito.verify(engine,org.mockito.Mockito.times(4)).executeOneCase(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.eq(true),org.mockito.ArgumentMatchers.anyBoolean());
        var paths=org.mockito.ArgumentCaptor.forClass(String.class);org.mockito.Mockito.verify(engine,org.mockito.Mockito.times(2)).finishPrewarm(paths.capture());
        for(String path:paths.getAllValues())assertFalse(Files.exists(Path.of(path)));
    }
    static ExecuteCodeRequestV2 request() {
        return ExecuteCodeRequestV2.builder().backgroundValidation(true).trustedCompileScope("trusted-v1:prefix_matrix")
            .executionPlan(ExecutionPlan.builder().language("java").mode(ExecutionMode.ACM).entryPoint("Main")
                .sourceFiles(List.of(new SourceFile("Main.java", "public class Main{}"))).cases(List.of(new ExecutionCaseInput(0,""))).build()).build();
    }
    static JavaRuntimeAdapter adapter() {
        var adapter = new JavaRuntimeAdapter(); org.springframework.test.util.ReflectionTestUtils.setField(adapter,"imageRef","runtime@sha256:"+"a".repeat(64)); org.springframework.test.util.ReflectionTestUtils.invokeMethod(adapter,"init"); return adapter;
    }
}
