package com.nemo.OJbackendjudgeservice.judge;
import com.nemo.OJbackendmodel.model.entity.Question;
import com.nemo.OJbackendmodel.model.dto.question.JudgeCase;
import com.nemo.OJbackendmodel.model.dto.question.JudgeConfig;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class PrivateValidationProofTest {
    Map<String,Object> runtime(){return new HashMap<>(Map.of("imageDigest","registry/runtime@sha256:"+"a".repeat(64),"runtimeId","java8-1","sdkVersion","harness-1","verifiedAt",10));}
    Question question(){var q=new Question();q.setTitle("sample");var c=new JudgeCase();c.setInput("1 2");c.setOutput("3");q.setJudgeCase(List.of(c));var limits=new JudgeConfig();limits.setTimeLimit(2000L);limits.setMemoryLimit(262144L);q.setJudgeConfig(limits);return q;}
    @Test void mutableImageNeverReusesProof(){var r=runtime();r.put("imageDigest","java:8");assertNull(PrivateProblemValidationService.proofKey(question(),"java","ACM","source",r));}
    @Test void sourceCasesLimitsAndRuntimeChangesInvalidateProof(){var q=question();var r=runtime();String original=PrivateProblemValidationService.proofKey(q,"java","ACM","source",r);assertNotNull(original);assertNotEquals(original,PrivateProblemValidationService.proofKey(q,"java","ACM","new source",r));q.getJudgeCase().get(0).setOutput("4");assertNotEquals(original,PrivateProblemValidationService.proofKey(q,"java","ACM","source",r));q=question();q.getJudgeConfig().setTimeLimit(3000L);assertNotEquals(original,PrivateProblemValidationService.proofKey(q,"java","ACM","source",r));r.put("sdkVersion","harness-2");assertNotEquals(original,PrivateProblemValidationService.proofKey(question(),"java","ACM","source",r));}
    @Test void probeTimestampsDoNotInvalidateIdenticalPinnedRuntime(){var r=runtime();String original=PrivateProblemValidationService.proofKey(question(),"python","FUNCTION","source",r);r.put("verifiedAt",999);assertEquals(original,PrivateProblemValidationService.proofKey(question(),"python","FUNCTION","source",r));}
}
