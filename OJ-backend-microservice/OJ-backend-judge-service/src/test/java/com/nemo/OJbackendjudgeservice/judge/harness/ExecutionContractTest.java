package com.nemo.OJbackendjudgeservice.judge.harness;
import com.google.gson.Gson;
import com.nemo.OJbackendmodel.model.codesandbox.ExecuteCodeResponseV2;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class ExecutionContractTest {
    @Test void readsSharedResponseFixtureWithoutDroppingBudgets() throws Exception {
        Path root=Path.of("").toAbsolutePath();
        while(root!=null && !Files.exists(root.resolve("deploy/contracts/execution-response-v2.json"))) root=root.getParent();
        assertNotNull(root);
        String wire=Files.readString(root.resolve("deploy/contracts/execution-response-v2.json"));
        var gson=new Gson(); var response=gson.fromJson(wire,ExecuteCodeResponseV2.class);
        assertEquals(3000L,response.getEffectiveLimits().getTimeLimitMs());
        assertEquals(1500L,response.getCases().get(0).getTime());
        assertEquals(response,gson.fromJson(gson.toJson(response),ExecuteCodeResponseV2.class));
    }

    @Test void functionRunnersReturnActionableRuntimeDiagnostics() {
        String python = HarnessGeneratorRegistry.resource("runner.py");
        String cpp = HarnessGeneratorRegistry.resource("runner.cpp");
        assertTrue(python.contains("traceback.format_exc()"));
        assertTrue(python.contains("'error'"));
        assertTrue(cpp.contains("e.what()"));
        assertTrue(cpp.contains("\"error\""));
        String javaRunner = new JavaHarnessGenerator().generate(new com.nemo.OJbackendmodel.model.dto.question.ProblemSpec(
                "function", "Solution", "solve",
                java.util.List.of(new com.nemo.OJbackendmodel.model.dto.question.ParameterSpec("value", "int")),
                "int", "return", null, 1));
        assertTrue(javaRunner.contains("printStackTrace"));
        assertTrue(javaRunner.contains("\"error\""));
    }

    @Test void splitCppRunnerKeepsTheSameFunctionContractAndDiagnostics() throws Exception {
        var spec = new com.nemo.OJbackendmodel.model.dto.question.ProblemSpec(
                "function", "Solution", "solve",
                java.util.List.of(new com.nemo.OJbackendmodel.model.dto.question.ParameterSpec("value", "int")),
                "int", "return", null, 1);
        var generator = new CppHarnessGenerator();
        var field = CppHarnessGenerator.class.getDeclaredField("splitProtocolEnabled");
        field.setAccessible(true);
        field.setBoolean(generator, true);
        String runner = generator.generate(spec);
        assertTrue(runner.contains("#include <nemo/json-lite.hpp>"));
        assertTrue(runner.contains("solution.solve(arg0)"));
        assertTrue(runner.contains("RUNTIME_ERROR"));
        assertTrue(runner.contains("e.what()"));
        assertFalse(runner.contains("__BODY__"));
    }
}
