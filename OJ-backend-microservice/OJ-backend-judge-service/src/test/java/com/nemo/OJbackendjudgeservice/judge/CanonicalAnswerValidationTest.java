package com.nemo.OJbackendjudgeservice.judge;
import com.nemo.OJbackendjudgeservice.judge.codesandbox.*;
import com.nemo.OJbackendjudgeservice.judge.mode.*;
import com.nemo.OJbackendmodel.model.codesandbox.*;
import com.nemo.OJbackendmodel.model.dto.question.*;
import com.nemo.OJbackendmodel.model.entity.*;
import com.nemo.OJbackendmodel.model.execution.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class CanonicalAnswerValidationTest {
    @Test void ignoresModelExpectedAnswersAndRequiresIndependentAgreementBeforeAllSixChecks(){check(false);}
    @Test void disagreeingProgramsNeverProduceAnswersOrStartOtherLanguageValidation(){check(true);}
    @Test void mutableImagesCannotReuseExecutionEvidence(){check(false,false,false);}
    @Test void changedHarnessFilesCannotReuseExecutionEvidence(){check(false,true,true);}
    void check(boolean disagree){check(disagree,true,false);}
    @Test void pythonPreflightDoesNotCompileOtherLanguagesOrProducePublishableProof(){check(false,true,false,true);}
    void check(boolean disagree,boolean immutable,boolean changedHarness){check(disagree,immutable,changedHarness,false);}
    void check(boolean disagree,boolean immutable,boolean changedHarness,boolean preflight){
        var sandbox=mock(CodeSandBox.class);var factory=mock(CodeSandboxFactory.class);var caps=mock(RuntimeCapabilitiesClient.class);
        var acm=mock(AcmJudgeModeAdapter.class);var fn=mock(FunctionJudgeModeAdapter.class);var manager=mock(JudgeManager.class);
        when(factory.newInstance(anyString())).thenReturn(sandbox);
        when(caps.snapshot()).thenReturn(List.of("java","python","cpp").stream().map(l->Map.<String,Object>of("language",l,"runtimeId",l,"imageDigest",immutable?"runtime@sha256:"+"a".repeat(64):"runtime:mutable","imageReady",true,"modes",List.of("ACM","FUNCTION"))).toList());
        var built=new AtomicInteger();
        when(acm.buildExecutionPlan(any(),any())).thenAnswer(c->plan(false));when(fn.buildExecutionPlan(any(),any())).thenAnswer(c->{var p=plan(true);int n=built.incrementAndGet();if(changedHarness)p.setSourceFiles(List.of(new SourceFile("runner.py","version="+n)));return p;});
        var accepted=new JudgeInfo();accepted.setMessage("accepted");when(manager.doJudgeV2(any(),any())).thenReturn(accepted);
        AtomicInteger calls=new AtomicInteger();
        when(sandbox.executeCodeV2(any())).thenAnswer(c->{
            int n=calls.incrementAndGet();ExecuteCodeRequestV2 req=c.getArgument(0);
            boolean function=req.getExecutionPlan().getCases().get(0).getStdin().startsWith("{");
            var outputs=new ArrayList<CaseExecutionResult>();for(int i=0;i<20;i++)outputs.add(CaseExecutionResult.builder().caseIndex(i).status(0).stdout(function?"{\"status\":\"OK\",\"actual\":"+(disagree&&n==2?4:3)+"}":"3\n").time(1L).memory(1024L).build());
            return ExecuteCodeResponseV2.builder().overallStatus(0).cases(outputs).build();
        });
        var q=new Question();var cfg=new JudgeConfig();cfg.setTimeLimit(2000L);cfg.setMemoryLimit(262144L);q.setJudgeConfig(cfg);q.setJudgeCase(cases(false));
        var spec=new ProblemSpec("function","Solution","solve",List.of(new ParameterSpec("a","int")),"int","return",new ComparatorSpec("EXACT",null),1);spec.setJudgeCases(cases(true));q.setProblemSpec(spec);
        var request=new PrivateProblemValidationRequest();request.setQuestion(q);request.setGenerationProtocol("CANONICAL_LINES_V1");request.setOracleSolution("class Solution:\n def solve(self,a): return sum([a])");
        request.setReferenceSolutions(new LinkedHashMap<>(Map.of("java:FUNCTION","class Solution {public int solve(int a){return a;}}","python:FUNCTION","class Solution:\n def solve(self,a): return a","cpp:FUNCTION","class Solution {public:int solve(int a){return a;}};")));
        if(preflight)request.setGenerationProtocol(PrivateProblemValidationService.PYTHON_PREFLIGHT);
        var validator=new PrivateProblemValidationService(factory,caps,acm,fn,manager,new MockEnvironment());ReflectionTestUtils.setField(validator,"sandboxType","remote");
        try{
            boolean reused=immutable&&!changedHarness;
            var result=validator.validate(request);assertEquals(!disagree,result.get("passed"));assertEquals(disagree||preflight?2:reused?7:8,calls.get());
            if(preflight){assertEquals(true,result.get("pythonPreflightValidated"));assertEquals(false,result.get("runtimeValidated"));verifyNoInteractions(manager);assertEquals(2,((List<?>)result.get("combinations")).size());return;}
            if(disagree){assertEquals("ORACLE_DISAGREEMENT",result.get("category"));verifyNoInteractions(manager);assertEquals("999",spec.getJudgeCases().get(0).getOutput());assertFalse(result.containsKey("referenceSolutions"));}
            else{assertEquals(true,result.get("oracleValidated"));assertEquals(reused,result.get("pythonEvidenceReused"));var contexts=org.mockito.ArgumentCaptor.forClass(com.nemo.OJbackendjudgeservice.judge.strategy.JudgeContext.class);verify(manager,times(6)).doJudgeV2(contexts.capture(),any());assertTrue(contexts.getAllValues().stream().allMatch(c->c.getJudgeInfo().getExecutedTime()==1L&&c.getJudgeInfo().getExecutedMemory()==1024L));assertEquals(6,((List<?>)result.get("combinations")).size());assertEquals("3",spec.getJudgeCases().get(0).getOutput());assertEquals("3\n",q.getJudgeCase().get(0).getOutput());}
        }finally{validator.close();}
    }
    static List<JudgeCase> cases(boolean fn){var cases=new ArrayList<JudgeCase>();for(int i=0;i<20;i++){var c=new JudgeCase();c.setInput(fn?"{\"a\":3}":"3\n");c.setOutput("999");c.setIsHidden(i>=2);cases.add(c);}return cases;}
    static ExecutionPlan plan(boolean fn){var p=new ExecutionPlan();p.setCases(java.util.stream.IntStream.range(0,20).mapToObj(i->new ExecutionCaseInput(i,fn?"{\"a\":3}":"3\n")).toList());return p;}
}
