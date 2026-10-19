package com.nemo.nemosandbox.runtime;
import com.nemo.nemosandbox.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class MultiLanguageRuntimeTest {
    @Test void policiesRejectInvalidOrUnboundedLimits() {
        assertThrows(IllegalArgumentException.class,()->new LanguagePolicy(0,268435456,1,64,1048576));
        assertThrows(IllegalArgumentException.class,()->new LanguagePolicy(10,268435456,Double.NaN,64,1048576));
        assertThrows(IllegalArgumentException.class,()->new LanguagePolicy(10,268435456,4,64,1048576));
    }    @Test void rejectDuplicateAdapters() {
        var adapter=new PythonRuntimeAdapter();
        assertThrows(IllegalStateException.class,()->new LanguageRuntimeRegistry(List.of(adapter,adapter)));
    }
    @Test void rejectWrongEntrypointExtraFilesAndExtensions() {
        var adapter=new PythonRuntimeAdapter();
        var plan=ExecutionPlan.builder().language("python").mode(ExecutionMode.ACM).entryPoint("main.py")
            .sourceFiles(List.of(new SourceFile("main.py","print(1)"))).build();
        assertDoesNotThrow(()->ExecutionLayout.validate(plan,adapter.descriptor()));
        plan.setEntryPoint("../main.py");
        assertThrows(IllegalArgumentException.class,()->ExecutionLayout.validate(plan,adapter.descriptor()));
        plan.setEntryPoint("main.py");
        plan.setSourceFiles(List.of(new SourceFile("main.py",""),new SourceFile("other.py","")));
        assertThrows(IllegalArgumentException.class,()->ExecutionLayout.validate(plan,adapter.descriptor()));
    }
    @Test void cppCompilesRunnerOnly() {
        var cmd=new CppRuntimeAdapter().compileCommand(new WorkspaceManifest(ExecutionMode.FUNCTION,List.of("/app/runner.cpp","/app/solution.hpp"),"main")).orElseThrow().getCommand();
        assertTrue(Arrays.asList(cmd).contains("-std=c++17"));
        assertTrue(Arrays.asList(cmd).contains("/app/runner.cpp"));
        assertFalse(Arrays.asList(cmd).contains("/app/solution.hpp"));
    }
    @Test void cppSplitProtocolLinksOnlyFunctionMode() throws Exception {
        var adapter = new CppRuntimeAdapter();
        var field = CppRuntimeAdapter.class.getDeclaredField("splitProtocolEnabled");
        field.setAccessible(true);
        field.setBoolean(adapter, true);
        var function = adapter.compileCommand(new WorkspaceManifest(ExecutionMode.FUNCTION,
                List.of("/app/runner.cpp", "/app/solution.hpp"), "main")).orElseThrow().getCommand();
        var acm = adapter.compileCommand(new WorkspaceManifest(ExecutionMode.ACM,
                List.of("/app/main.cpp"), "main")).orElseThrow().getCommand();
        assertTrue(Arrays.asList(function).contains("/opt/nemooj/libjson-lite.a"));
        assertFalse(Arrays.asList(acm).contains("/opt/nemooj/libjson-lite.a"));
    }
    @Test void pythonIsolatedSyntaxCheckDoesNotImportUserCode() {
        var adapter=new PythonRuntimeAdapter();
        var ws=new WorkspaceManifest(ExecutionMode.ACM,List.of("/app/main.py"),"main.py");
        assertTrue(Arrays.asList(adapter.runCommand(ws).getCommand()).contains("-I"));
        assertTrue(String.join(" ",adapter.compileCommand(ws).orElseThrow().getCommand()).contains("compile("));
    }
}
