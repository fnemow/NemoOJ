package com.nemo.nemosandbox;
import com.nemo.nemosandbox.model.*;
import com.nemo.nemosandbox.runtime.*;
import com.nemo.nemosandbox.config.SandboxExecutionMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class BackgroundFailFastTest {
    @Test void errorsStopBackgroundOnlyAndPreserveFirstDiagnostic(){
        for(boolean background:List.of(false,true))for(int error:List.of(2,3,4,5)){
            var adapter=new PythonRuntimeAdapter();ReflectionTestUtils.setField(adapter,"image","image");
            var engine=mock(DockerExecutionEngine.class);
            when(engine.compile(anyString(),anyString(),any(),any(),anyLong(),anyLong(),anyInt())).thenReturn(new DockerExecutionEngine.ExecutionResult(0,"",""));
            when(engine.compile(anyString(),anyString(),any(),any(),anyLong(),anyLong(),anyInt(),eq(true))).thenReturn(new DockerExecutionEngine.ExecutionResult(0,"",""));
            when(engine.executeOneCase(anyString(),anyString(),any(),any(),anyString(),anyLong(),anyLong(),anyInt(),anyInt(),anyBoolean(),anyBoolean()))
                .thenAnswer(c->CaseExecutionResult.builder().status(error).stderr("diagnostic").memory(1024L).time(100L).build());
            var sandbox=new JavaDockerCodeSandBox();
            ReflectionTestUtils.setField(sandbox,"engine",engine);
            ReflectionTestUtils.setField(sandbox,"runtimeRegistry",new LanguageRuntimeRegistry(List.of(adapter)));
            ReflectionTestUtils.setField(sandbox,"enabledLanguages","python");
            ReflectionTestUtils.setField(sandbox,"languageEnvironment",new MockEnvironment());
            ReflectionTestUtils.setField(sandbox,"executionMetrics",mock(SandboxExecutionMetrics.class));
            var plan=ExecutionPlan.builder().language("python").mode(ExecutionMode.ACM).entryPoint("main.py")
                .sourceFiles(List.of(new SourceFile("main.py","print(1)")))
                .cases(List.of(new ExecutionCaseInput(0,"a"),new ExecutionCaseInput(1,"b"))).build();
            var result=sandbox.executeCodeV2(ExecuteCodeRequestV2.builder().backgroundValidation(background).executionPlan(plan).build());
            assertEquals(error,result.getOverallStatus());assertEquals(background?1:2,result.getCases().size());
            assertEquals("diagnostic",result.getCases().get(0).getStderr());
            verify(engine).finishPrewarm(anyString());
        }
    }
}
