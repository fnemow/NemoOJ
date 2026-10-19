package com.nemo.OJbackendjudgeservice.judge;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nemo.OJbackendjudgeservice.judge.codesandbox.*;
import com.nemo.OJbackendjudgeservice.judge.mode.*;
import com.nemo.OJbackendmodel.model.execution.*;
import com.nemo.OJbackendmodel.model.codesandbox.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrustedValidationReuseTest {
    @TempDir Path dir;
    @Test void warmProofSkipsSandboxButRuntimeHarnessAndUnapprovedChangesForceValidation() throws Exception {
        var factory=mock(CodeSandboxFactory.class);var sandbox=mock(CodeSandBox.class);var caps=mock(RuntimeCapabilitiesClient.class);
        var acm=mock(AcmJudgeModeAdapter.class);var fn=mock(FunctionJudgeModeAdapter.class);var manager=mock(JudgeManager.class);
        String[] digest={"a".repeat(64)},runner={"runner-v1"};
        when(caps.snapshot()).thenAnswer(c->List.of("java","python","cpp").stream().map(l->Map.<String,Object>of("language",l,"runtimeId",l,"imageDigest","runtime@sha256:"+digest[0],"imageReady",true,"modes",List.of("ACM","FUNCTION"))).toList());
        when(factory.newInstance(any())).thenReturn(sandbox);
        for(var adapter:List.of(acm,fn))when(adapter.buildExecutionPlan(any(),any())).thenAnswer(call->{
            com.nemo.OJbackendmodel.model.entity.Question q=call.getArgument(0);com.nemo.OJbackendmodel.model.entity.QuestionSubmit s=call.getArgument(1);
            var plan=new ExecutionPlan();plan.setLanguage(s.getLanguage());plan.setEntryPoint("Solution");plan.setSourceFiles(List.of(new SourceFile("core",s.getCode()),new SourceFile("runner",runner[0])));
            plan.setCases(java.util.stream.IntStream.range(0,20).mapToObj(i->new ExecutionCaseInput(i,q.getJudgeCase().get(i).getInput())).toList());return plan;
        });
        var accepted=new JudgeInfo();accepted.setMessage("accepted");when(manager.doJudgeV2(any(),any())).thenReturn(accepted);
        var fixture=TrustedValidationArtifactsTest.request();var mapper=new ObjectMapper();var expected=new HashMap<String,String>();
        for(int i=0;i<20;i++){String output=TrustedTemplateProperties.expected("binary_first",mapper.readTree(fixture.getQuestion().getProblemSpec().getJudgeCases().get(i).getInput())).toString();expected.put(fixture.getQuestion().getJudgeCase().get(i).getInput(),output);expected.put(fixture.getQuestion().getProblemSpec().getJudgeCases().get(i).getInput(),output);}
        var calls=new AtomicInteger();when(sandbox.executeCodeV2(any())).thenAnswer(call->{
            calls.incrementAndGet();ExecuteCodeRequestV2 r=call.getArgument(0);var values=new ArrayList<>(r.getExecutionPlan().getCases().stream().map(c->CaseExecutionResult.builder().caseIndex(c.getCaseIndex()).status(0).stdout(c.getStdin().startsWith("{")?"{\"status\":\"OK\",\"actual\":"+expected.get(c.getStdin())+"}":expected.get(c.getStdin())+"\n").time(1L).memory(1024L).build()).toList());return ExecuteCodeResponseV2.builder().overallStatus(0).cases(values).build();
        });
        var cache=new TrustedValidationArtifacts();ReflectionTestUtils.setField(cache,"enabled",true);ReflectionTestUtils.setField(cache,"directory",dir.toString());
        var validator=new PrivateProblemValidationService(factory,caps,acm,fn,manager,new MockEnvironment());ReflectionTestUtils.setField(validator,"artifacts",cache);ReflectionTestUtils.setField(validator,"sandboxType","remote");
        try{
            assertEquals(true,validator.validate(TrustedValidationArtifactsTest.request()).get("passed"));int cold=calls.get();assertTrue(cold>0);
            var owned=TrustedValidationArtifactsTest.request();owned.setValidationId(UUID.randomUUID().toString());owned.getQuestion().setTitle("另一个用户的私人标题");var warm=validator.validate(owned);assertEquals(true,warm.get("completeProofReused"));assertEquals(cold,calls.get());
            digest[0]="b".repeat(64);assertEquals(true,validator.validate(TrustedValidationArtifactsTest.request()).get("passed"));assertTrue(calls.get()>cold);int after=calls.get();
            runner[0]="runner-v2";assertEquals(true,validator.validate(TrustedValidationArtifactsTest.request()).get("passed"));assertTrue(calls.get()>after);after=calls.get();
            var changed=TrustedValidationArtifactsTest.request();changed.getReferenceSolutions().put("java:FUNCTION","class Solution { /* different source */ }");validator.validate(changed);assertTrue(calls.get()>after);
        }finally{validator.close();}
    }
}
