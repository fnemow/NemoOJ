package com.nemo.OJbackendjudgeservice.judge;

import com.nemo.OJbackendjudgeservice.judge.codesandbox.*;
import com.nemo.OJbackendjudgeservice.judge.mode.*;
import com.nemo.OJbackendmodel.model.codesandbox.*;
import com.nemo.OJbackendmodel.model.dto.question.*;
import com.nemo.OJbackendmodel.model.entity.Question;
import com.nemo.OJbackendmodel.model.execution.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class TryRunServiceTest {
    @Test void runsOnlyCallerInputWithBoundedResourcesAndRetainsMeasurements() {
        var acm=mock(AcmJudgeModeAdapter.class);var function=mock(FunctionJudgeModeAdapter.class);var factory=mock(CodeSandboxFactory.class);
        var sandbox=mock(CodeSandBox.class);when(factory.newInstance("remote")).thenReturn(sandbox);
        var plan=ExecutionPlan.builder().cases(List.of(new ExecutionCaseInput(0,"hidden")))
            .limits(new ResourceLimit(5000L,524288L,1048576L)).build();
        when(acm.buildExecutionPlan(any(),any())).thenReturn(plan);
        when(sandbox.executeCodeV2(any())).thenReturn(ExecuteCodeResponseV2.builder().cases(List.of(CaseExecutionResult.builder().status(0).stdout("42").time(12L).memory(1024L).exitCode(0).build())).build());
        var service=new TryRunService(acm,function,factory);ReflectionTestUtils.setField(service,"sandboxType","remote");
        var request=new TryRunExecutionRequest();request.setQuestion(new Question());request.setLanguage("java");request.setExecutionMode("ACM");request.setInput("public");request.setRuntimeId("java8");
        var result=service.run(request);assertEquals("Completed",result.getVerdict());assertEquals(12L,result.getExecutedTime());assertEquals("42",result.getOutput());
        var captured=ArgumentCaptor.forClass(ExecuteCodeRequestV2.class);verify(sandbox).executeCodeV2(captured.capture());
        assertEquals(1,captured.getValue().getExecutionPlan().getCases().size());assertEquals("public",plan.getCases().get(0).getStdin());
        assertEquals(2000L,plan.getLimits().getTimeLimitMs());assertEquals(262144L,plan.getLimits().getMemoryLimitKB());assertEquals(65536L,plan.getLimits().getOutputLimitBytes());
    }
    @Test void retriesOnlyExplicitPreExecutionBusyResponses() {
        var acm=mock(AcmJudgeModeAdapter.class);var factory=mock(CodeSandboxFactory.class);var sandbox=mock(CodeSandBox.class);
        when(factory.newInstance("remote")).thenReturn(sandbox);
        when(acm.buildExecutionPlan(any(),any())).thenAnswer(invocation->ExecutionPlan.builder().limits(new ResourceLimit(2000L,262144L,65536L)).build());
        var success=ExecuteCodeResponseV2.builder().cases(List.of(CaseExecutionResult.builder().status(0).stdout("ok").build())).build();
        when(sandbox.executeCodeV2(any())).thenThrow(new com.nemo.OJbackendcommon.exception.BusinessException(com.nemo.OJbackendcommon.common.ErrorCode.SYSTEM_ERROR,"沙箱服务异常, status=503")).thenReturn(success);
        var service=new TryRunService(acm,mock(FunctionJudgeModeAdapter.class),factory);ReflectionTestUtils.setField(service,"sandboxType","remote");
        var request=new TryRunExecutionRequest();request.setQuestion(new Question());request.setLanguage("java");request.setExecutionMode("ACM");request.setInput("");
        assertEquals("Completed",service.run(request).getVerdict());verify(sandbox,times(2)).executeCodeV2(any());
        reset(sandbox);when(sandbox.executeCodeV2(any())).thenThrow(new com.nemo.OJbackendcommon.exception.BusinessException(com.nemo.OJbackendcommon.common.ErrorCode.SYSTEM_ERROR,"沙箱响应不明确"));
        assertEquals("System Error",service.run(request).getVerdict());verify(sandbox,times(1)).executeCodeV2(any());
    }
}
