package com.nemo.OJbackendjudgeservice.judge;

import com.fasterxml.jackson.databind.ObjectMapper;
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

class PrivateValidationCheckpointTest {
    @Test void busyThenRestartResumesEvidenceAndChangedPlanOrJobDoesNotReuseIt() throws Exception {
        var question=mock(com.nemo.OJserviceclient.QuestionFeignClient.class);Map<String,String> disk=new HashMap<>();var json=new ObjectMapper();
        doAnswer(c->{PrivateValidationCheckpoint value=c.getArgument(0);disk.put(value.getValidationId()+":"+value.getExecutionHash(),json.writeValueAsString(value));return true;}).when(question).saveValidationCheckpoint(any());
        when(question.getValidationCheckpoint(anyString(),anyString())).thenAnswer(c->{String v=disk.get(c.getArgument(0)+":"+c.getArgument(1));return v==null?null:json.readValue(v,PrivateValidationCheckpoint.class);});
        var sandbox=mock(CodeSandBox.class);AtomicInteger calls=new AtomicInteger();
        when(sandbox.executeCodeV2(any())).thenAnswer(c->{
            int count=calls.incrementAndGet();if(count==3)throw new SandboxBusyException();
            ExecuteCodeRequestV2 r=c.getArgument(0);assertTrue(r.isBackgroundValidation());
            boolean fn=r.getExecutionPlan().getCases().get(0).getStdin().startsWith("{");
            var rows=new ArrayList<CaseExecutionResult>();for(int i=0;i<20;i++)rows.add(CaseExecutionResult.builder().caseIndex(i).status(0).stdout(fn?"{\"status\":\"OK\",\"actual\":3}":"3").time(1L).memory(1024L).build());
            return ExecuteCodeResponseV2.builder().overallStatus(0).cases(rows).build();
        });
        var req=request();var first=validator(sandbox,new ValidationCheckpointStore(question));
        try{assertEquals(true,first.validate(req).get("paused"));assertEquals(2,disk.size());}finally{first.close();}
        // New store and service instances reconstruct evidence through JSON, not memory.
        var resumed=validator(sandbox,new ValidationCheckpointStore(question));
        try{
            assertEquals(true,resumed.validate(req).get("passed"));assertEquals(7,calls.get());
            req.getReferenceSolutions().put("java:ACM","changed source");
            assertEquals(true,resumed.validate(req).get("passed"));assertEquals(8,calls.get());
            req.getQuestion().getJudgeCase().get(0).setOutput("4");
            assertEquals("WRONG_ANSWER",resumed.validate(req).get("category"));assertEquals(8,calls.get());
            req.getQuestion().getJudgeCase().get(0).setOutput("3");
            req.setValidationId(UUID.randomUUID().toString());
            assertEquals(true,resumed.validate(req).get("passed"));assertEquals(14,calls.get());
        }finally{resumed.close();}
    }
    static PrivateProblemValidationRequest request(){
        var q=new Question();q.setJudgeCase(PrivateValidationConcurrencyTest.cases(false));
        var cfg=new JudgeConfig();cfg.setTimeLimit(2000L);cfg.setMemoryLimit(262144L);q.setJudgeConfig(cfg);
        var spec=new ProblemSpec("function","Solution","solve",List.of(new ParameterSpec("a","int")),"int","return",new ComparatorSpec("EXACT",null),1);spec.setJudgeCases(PrivateValidationConcurrencyTest.cases(true));q.setProblemSpec(spec);
        var req=new PrivateProblemValidationRequest();req.setQuestion(q);req.setValidationId(UUID.randomUUID().toString());
        Map<String,String> refs=new HashMap<>();for(String l:List.of("java","python","cpp"))for(String m:List.of("ACM","FUNCTION"))refs.put(l+":"+m,"synthetic source");req.setReferenceSolutions(refs);return req;
    }
    static PrivateProblemValidationService validator(CodeSandBox sandbox,ValidationCheckpointStore store){
        var factory=mock(CodeSandboxFactory.class);when(factory.newInstance(anyString())).thenReturn(sandbox);
        var caps=mock(RuntimeCapabilitiesClient.class);when(caps.snapshot()).thenReturn(List.of("java","python","cpp").stream().map(l->Map.<String,Object>of("language",l,"runtimeId",l,"imageReady",true,"imageDigest","runtime@sha256:"+"a".repeat(64),"modes",List.of("ACM","FUNCTION"))).toList());
        var acm=mock(AcmJudgeModeAdapter.class);var fn=mock(FunctionJudgeModeAdapter.class);
        when(acm.buildExecutionPlan(any(),any())).thenAnswer(c->plan(c.getArgument(1),false));when(fn.buildExecutionPlan(any(),any())).thenAnswer(c->plan(c.getArgument(1),true));
        var manager=mock(JudgeManager.class);when(manager.doJudgeV2(any(),any())).thenAnswer(c->{
            com.nemo.OJbackendjudgeservice.judge.strategy.JudgeContext context=c.getArgument(0);
            var result=new JudgeInfo();result.setMessage(context.getJudgeCaseList().stream().allMatch(item->"3".equals(item.getOutput()))?"accepted":"wrong_answer");return result;
        });
        var validator=new PrivateProblemValidationService(factory,caps,acm,fn,manager,new MockEnvironment());
        ReflectionTestUtils.setField(validator,"sandboxType","remote");ReflectionTestUtils.setField(validator,"combinationConcurrency",1);ReflectionTestUtils.setField(validator,"checkpoints",store);return validator;
    }
    static ExecutionPlan plan(QuestionSubmit submit,boolean fn){var p=PrivateValidationConcurrencyTest.plan(fn);p.setSourceFiles(List.of(new SourceFile("source",submit.getCode())));p.setLanguage(submit.getLanguage());return p;}
}
