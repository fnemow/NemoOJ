package com.nemo.OJbackendjudgeservice.judge;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nemo.OJbackendmodel.model.dto.question.PrivateProblemValidationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class TrustedValidationArtifactsTest {
    @TempDir Path dir;
    static PrivateProblemValidationRequest request() throws Exception {return new ObjectMapper().readValue(TrustedValidationArtifactsTest.class.getResourceAsStream("/trusted-artifact-fixture.json"),PrivateProblemValidationRequest.class);}
    TrustedValidationArtifacts cache(){var c=new TrustedValidationArtifacts();ReflectionTestUtils.setField(c,"enabled",true);ReflectionTestUtils.setField(c,"directory",dir.toString());return c;}
    @Test void registryMatchesRealTypedRequestAndDisplayTitleDoesNotChangeContract() throws Exception {
        var c=cache();var r=request();assertTrue(c.accepts(r));r.getQuestion().setTitle("私人标题");assertTrue(c.accepts(r));
        r.getReferenceSolutions().put("java:FUNCTION","unreviewed model source");assertFalse(c.accepts(r));
        r=request();r.getQuestion().setContent("different problem semantics");assertFalse(c.accepts(r));
    }
    @Test void changedInputExpectedOutputLimitsSignatureOracleAndTemplateVersionAreRejected() throws Exception {
        var c=cache();var r=request();r.getQuestion().getJudgeCase().get(0).setInput("changed");assertFalse(c.accepts(r));
        r=request();r.getQuestion().getProblemSpec().getJudgeCases().get(0).setOutput("999");assertFalse(c.accepts(r));
        r=request();r.getQuestion().getJudgeConfig().setTimeLimit(1L);assertFalse(c.accepts(r));
        r=request();r.getQuestion().getProblemSpec().setMethodName("other");assertFalse(c.accepts(r));
        r=request();r.setOracleSolution("new oracle");assertFalse(c.accepts(r));r=request();r.setTemplateVersion("spoofed");assertFalse(c.accepts(r));
        r=request();r.setGenerationProtocol("CANONICAL_PYTHON_PREFLIGHT_V1");assertFalse(c.accepts(r));
    }
    static Map<String,Object> proof(){return new HashMap<>(Map.of("passed",true,"oracleValidated",true,"runtimeValidated",true,"combinations",List.of("java:ACM","java:FUNCTION","cpp:ACM","cpp:FUNCTION","python:ACM","python:FUNCTION")));}
    @Test void proofSurvivesNewCacheInstanceAndReturnedResultCannotMutateStoredEvidence() throws Exception {
        var c=cache();String key="a".repeat(64);c.save(key,proof());var found=cache().load(key);assertNotNull(found);assertEquals(true,found.get("completeProofReused"));found.put("passed",false);assertEquals(true,c.load(key).get("passed"));
        assertNull(c.load("b".repeat(64)));Files.setLastModifiedTime(dir.resolve(key+".json"),java.nio.file.attribute.FileTime.fromMillis(1));assertNull(c.load(key));
    }
    @Test void failedIncompleteOrCorruptProofNeverHits() throws Exception {
        var c=cache();String key="c".repeat(64);var failed=proof();failed.put("passed",false);c.save(key,failed);assertNull(c.load(key));
        var incomplete=proof();incomplete.put("combinations",List.of("python:FUNCTION"));c.save(key,incomplete);assertNull(c.load(key));
        Files.writeString(dir.resolve(key+".json"),"broken");assertNull(c.load(key));
    }
}
