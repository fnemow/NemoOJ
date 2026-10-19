package com.nemo.OJbackendjudgeservice.judge;
import com.nemo.OJbackendjudgeservice.judge.codesandbox.*;
import com.nemo.OJbackendmodel.model.codesandbox.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PrivateRepairDiagnosticTest {
    @Test void sharedCoreCompileFailureTargetsFunctionButWrapperOnlyFailureDoesNot() {
        for(boolean wrapperOnly:List.of(false,true)) {
            var sandbox=mock(CodeSandBox.class);
            when(sandbox.executeCodeV2(any())).thenAnswer(call->{
                ExecuteCodeRequestV2 request=call.getArgument(0);
                boolean fn=request.getExecutionPlan().getCases().get(0).getStdin().startsWith("{");
                if("java".equals(request.getExecutionPlan().getLanguage()) && (!wrapperOnly || !fn))
                    return ExecuteCodeResponseV2.builder().overallStatus(1).compileMessage("compile diagnostic").cases(List.of()).build();
                var cases=new ArrayList<com.nemo.OJbackendmodel.model.execution.CaseExecutionResult>();
                for(int i=0;i<20;i++)cases.add(com.nemo.OJbackendmodel.model.execution.CaseExecutionResult.builder().caseIndex(i).status(0).stdout(fn?"{\"status\":\"OK\",\"actual\":3}":"3").time(1L).memory(1024L).build());
                return ExecuteCodeResponseV2.builder().overallStatus(0).cases(cases).build();
            });
            var req=PrivateValidationCheckpointTest.request();
            req.getQuestion().setJudgeCase(CanonicalAnswerValidationTest.cases(false));
            req.getQuestion().getProblemSpec().setJudgeCases(CanonicalAnswerValidationTest.cases(true));
            req.setGenerationProtocol("CANONICAL_LINES_V1");req.setOracleSolution("independent oracle");
            req.setReferenceSolutions(new LinkedHashMap<>(Map.of("java:FUNCTION","java core","python:FUNCTION","python core","cpp:FUNCTION","cpp core")));
            var remote=mock(com.nemo.OJserviceclient.QuestionFeignClient.class);
            when(remote.saveValidationCheckpoint(any())).thenReturn(true);
            var validator=PrivateValidationCheckpointTest.validator(sandbox,new ValidationCheckpointStore(remote));
            org.springframework.test.util.ReflectionTestUtils.setField(validator,"combinationConcurrency",2);
            try {
                var result=validator.validate(req);assertEquals("COMPILE",result.get("category"));
                assertEquals(wrapperOnly?"java:ACM":"java:FUNCTION",result.get("combination"));
            } finally {validator.close();}
        }
    }
}
