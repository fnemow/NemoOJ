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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class PrivateValidationConcurrencyTest {
    @Test void boundedTwoWayValidationStillRequiresAllSixCombinations() throws Exception {check(false);}
    @Test void aFailedCombinationWaitsForItsStartedPeerAndStopsFurtherWork() throws Exception {check(true);}
    void check(boolean fail) throws Exception {
        var sandbox=mock(CodeSandBox.class);var factory=mock(CodeSandboxFactory.class);var capabilities=mock(RuntimeCapabilitiesClient.class);
        var acm=mock(AcmJudgeModeAdapter.class);var fn=mock(FunctionJudgeModeAdapter.class);var manager=mock(JudgeManager.class);
        when(factory.newInstance(anyString())).thenReturn(sandbox);
        when(capabilities.snapshot()).thenReturn(List.of("java","python","cpp").stream().map(l->Map.<String,Object>of("language",l,"runtimeId",l,"imageReady",true,"modes",List.of("ACM","FUNCTION"))).toList());
        when(acm.buildExecutionPlan(any(),any())).thenAnswer(c->plan(false));when(fn.buildExecutionPlan(any(),any())).thenAnswer(c->plan(true));
        var evidence=new JudgeInfo();evidence.setMessage("accepted");when(manager.doJudgeV2(any(),any())).thenReturn(evidence);
        AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger(),calls=new AtomicInteger();CountDownLatch peers=new CountDownLatch(2);
        when(sandbox.executeCodeV2(any())).thenAnswer(c->{
            int running=active.incrementAndGet();peak.accumulateAndGet(running,Math::max);calls.incrementAndGet();peers.countDown();
            try{assertTrue(peers.await(3,TimeUnit.SECONDS));Thread.sleep(15);
                ExecuteCodeRequestV2 request=c.getArgument(0);boolean function=request.getExecutionPlan().getCases().get(0).getStdin().startsWith("{");
                if(fail&&!function)return ExecuteCodeResponseV2.builder().overallStatus(1).compileMessage("synthetic compile failure").cases(List.of()).build();
                var results=new ArrayList<CaseExecutionResult>();for(int i=0;i<20;i++)results.add(CaseExecutionResult.builder().caseIndex(i).status(0).stdout(function?"{\"status\":\"OK\",\"actual\":3}":"3").time(1L).memory(1024L).build());
                return ExecuteCodeResponseV2.builder().overallStatus(0).cases(results).build();
            }finally{active.decrementAndGet();}
        });
        var validator=new PrivateProblemValidationService(factory,capabilities,acm,fn,manager,new MockEnvironment());ReflectionTestUtils.setField(validator,"sandboxType","remote");
        var q=new Question();q.setJudgeCase(cases(false));var cfg=new JudgeConfig();cfg.setTimeLimit(2000L);cfg.setMemoryLimit(262144L);q.setJudgeConfig(cfg);
        var spec=new ProblemSpec("function","Solution","solve",List.of(new ParameterSpec("a","int")),"int","return",new ComparatorSpec("EXACT",null),1);spec.setJudgeCases(cases(true));q.setProblemSpec(spec);
        var request=new PrivateProblemValidationRequest();request.setQuestion(q);Map<String,String> refs=new HashMap<>();for(String l:List.of("java","python","cpp"))for(String m:List.of("ACM","FUNCTION"))refs.put(l+":"+m,"synthetic source");request.setReferenceSolutions(refs);
        try{var result=validator.validate(request);assertEquals(!fail,result.get("passed"));assertEquals(fail?2:6,calls.get());assertEquals(fail?1:6,((List<?>)result.get("combinations")).size());if(fail)assertEquals("COMPILE",result.get("category"));assertEquals(2,peak.get());assertEquals(0,active.get());}finally{validator.close();}
    }
    static List<JudgeCase> cases(boolean fn){var values=new ArrayList<JudgeCase>();for(int i=0;i<20;i++){var c=new JudgeCase();c.setInput(fn?"{\"a\":3}":"3");c.setOutput("3");c.setIsHidden(i>1);values.add(c);}return values;}
    static ExecutionPlan plan(boolean fn){var p=new ExecutionPlan();p.setCases(java.util.stream.IntStream.range(0,20).mapToObj(i->new ExecutionCaseInput(i,fn?"{\"a\":3}":"3")).toList());return p;}
}
