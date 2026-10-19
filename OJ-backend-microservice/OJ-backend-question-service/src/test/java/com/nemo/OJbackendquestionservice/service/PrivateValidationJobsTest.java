package com.nemo.OJbackendquestionservice.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nemo.OJbackendcommon.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PrivateValidationJobsTest {
    @Test void knownTemplateVersionsReachJudgeButUnknownVersionsAreRejected() throws Exception {
        var jobs=new PrivateValidationJobs(mock(JdbcTemplate.class),new ObjectMapper(),mock(PrivateProblemValidationGateway.class),mock(PlatformTransactionManager.class));
        try {
            var d=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(fixture());
            var references=(com.fasterxml.jackson.databind.node.ObjectNode)d.path("referenceSolutions");
            references.remove(List.of("java:ACM","python:ACM","cpp:ACM"));
            var generation=d.putObject("generation");
            generation.put("protocol","CANONICAL_LINES_V1");generation.put("oracleSolution","class Solution: pass # independent oracle");generation.put("templateId","string_unique");
            for(String version:List.of("trusted-v1","trusted-v2")) {
                generation.put("templateVersion",version);
                assertEquals(version,jobs.precheck(d).getTemplateVersion());
            }
            generation.put("templateVersion","trusted-v3");
            assertThrows(IllegalArgumentException.class,()->jobs.precheck(d));
        } finally {jobs.close();}
    }
    @Test void capacityPauseDoesNotFailJobConsumeAttemptOrStartModelRepair() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var gateway=mock(PrivateProblemValidationGateway.class);
        when(jdbc.update(startsWith("UPDATE private_validation_job SET status='RUNNING'"),anyString(),eq("job"))).thenReturn(1);
        when(jdbc.queryForMap(anyString(),eq("job"))).thenReturn(Map.of("taskId","task","draftJson",fixture(),"attemptCount",3));
        when(gateway.validate(any())).thenAnswer(c->{assertEquals("job",((com.nemo.OJbackendmodel.model.dto.question.PrivateProblemValidationRequest)c.getArgument(0)).getValidationId());return Map.of("passed",false,"paused",true,"category","CAPACITY");});
        var jobs=new PrivateValidationJobs(jdbc,json,gateway,mock(PlatformTransactionManager.class));
        try{ReflectionTestUtils.invokeMethod(jobs,"execute","job");}finally{jobs.close();}
        verify(jdbc).update(contains("attemptCount=GREATEST(0,attemptCount-1)"),eq("job"),anyString());
        verify(jdbc).update(startsWith("UPDATE ai_task SET stage='VALIDATION_QUEUED'"),eq("task"));
        verify(jdbc,never()).update(startsWith("UPDATE private_validation_job SET status=?"),any(),any(),any(),any());
    }
    final ObjectMapper json=new ObjectMapper();
    String fixture() throws Exception{return new String(Objects.requireNonNull(getClass().getResourceAsStream("/private-validation-fixture.json")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
    @Test void sandboxRunsOutsideTransactionsAndStoresDurableEvidence() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var gateway=mock(PrivateProblemValidationGateway.class);var manager=mock(PlatformTransactionManager.class);
        when(jdbc.update(startsWith("UPDATE private_validation_job SET status='RUNNING'"),anyString(),eq("job"))).thenReturn(1);
        when(jdbc.queryForMap(anyString(),eq("job"))).thenReturn(Map.of("taskId","task","draftJson",fixture(),"attemptCount",1));
        when(gateway.validate(any())).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return Map.of("passed",true,"runtimeValidated",true,"combinations",List.of("java:ACM","java:FUNCTION","python:ACM","python:FUNCTION","cpp:ACM","cpp:FUNCTION"));});
        var jobs=new PrivateValidationJobs(jdbc,json,gateway,manager);
        try{ReflectionTestUtils.invokeMethod(jobs,"execute","job");}finally{jobs.close();}
        // Sandbox I/O stays outside the transaction; only the completed-result
        // notification uses a short transaction after the response arrives.
        var order=inOrder(gateway,manager);order.verify(gateway).validate(any());order.verify(manager).getTransaction(any());
        verify(gateway).validate(any());
        verify(jdbc).update(startsWith("UPDATE private_validation_job SET status=?"),eq("SUCCEEDED"),contains("java:FUNCTION"),eq("job"),anyString());
    }
    @Test void unavailableValidatorRetainsSameJobAndDoesNotPublish() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var gateway=mock(PrivateProblemValidationGateway.class);
        when(jdbc.update(startsWith("UPDATE private_validation_job SET status='RUNNING'"),anyString(),eq("job"))).thenReturn(1);
        when(jdbc.queryForMap(anyString(),eq("job"))).thenReturn(Map.of("taskId","task","draftJson",fixture(),"attemptCount",1));
        when(gateway.validate(any())).thenThrow(new IllegalStateException("network unavailable"));
        var jobs=new PrivateValidationJobs(jdbc,json,gateway,mock(PlatformTransactionManager.class));
        try{ReflectionTestUtils.invokeMethod(jobs,"execute","job");}finally{jobs.close();}
        verify(jdbc).update(startsWith("UPDATE private_validation_job SET status='QUEUED'"),eq("job"),anyString());
        verify(jdbc,never()).update(startsWith("INSERT INTO private_problem_version"),any(Object[].class));
    }
    @Test void schemaFailureDoesNotEnterSandbox() throws Exception {
        var jobs=new PrivateValidationJobs(mock(JdbcTemplate.class),json,mock(PrivateProblemValidationGateway.class),mock(PlatformTransactionManager.class));
        try{var d=json.readTree(fixture());((com.fasterxml.jackson.databind.node.ArrayNode)d.path("problemSpec").path("judgeCases")).removeAll();assertThrows(IllegalArgumentException.class,()->jobs.precheck(d));}finally{jobs.close();}
    }
    @Test void publicationRequiresCurrentRevisionProof() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var jobs=new PrivateValidationJobs(jdbc,json,mock(PrivateProblemValidationGateway.class),mock(PlatformTransactionManager.class));
        try{assertThrows(BusinessException.class,()->jobs.proof("task",json.readTree(fixture())));}finally{jobs.close();}
        verify(jdbc).query(contains("revision=(SELECT MAX(revision)"),any(RowMapper.class),eq("task"),anyString(),eq("task"));
    }
    @Test void normalizedPublicationRequiresExactHashAndHidesMaterializedSourcesInProof() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var jobs=new PrivateValidationJobs(jdbc,json,mock(PrivateProblemValidationGateway.class),mock(PlatformTransactionManager.class));
        var d=json.readTree(fixture());var evidence=new LinkedHashMap<String,Object>();evidence.put("passed",true);evidence.put("oracleValidated",true);evidence.put("runtimeValidated",true);evidence.put("combinations",List.of("java:ACM","java:FUNCTION","python:ACM","python:FUNCTION","cpp:ACM","cpp:FUNCTION"));evidence.put("normalizedDraftHash",PrivateValidationJobs.hash(d));evidence.put("normalizedDraft",d);
        when(jdbc.query(startsWith("SELECT resultJson FROM private_validation_job WHERE taskId=? AND status='SUCCEEDED'"),any(RowMapper.class),eq("task"),eq("task"))).thenReturn(List.of(json.writeValueAsString(evidence)));
        try{
            assertFalse(jobs.proof("task",d).containsKey("normalizedDraft"));
            ((com.fasterxml.jackson.databind.node.ObjectNode)d).put("title","tampered title");assertThrows(BusinessException.class,()->jobs.proof("task",d));
        }finally{jobs.close();}
    }
    @Test void unmaterializedCanonicalCandidateCannotUseItsOriginalJobHashToPublish() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var jobs=new PrivateValidationJobs(jdbc,json,mock(PrivateProblemValidationGateway.class),mock(PlatformTransactionManager.class));
        when(jdbc.query(contains("AND draftHash=?"),any(RowMapper.class),eq("task"),anyString(),eq("task"))).thenReturn(List.of("{\"passed\":true,\"normalizedDraftHash\":\"other\"}"));
        try{assertThrows(BusinessException.class,()->jobs.proof("task",json.readTree(fixture())));}finally{jobs.close();}
    }

    @Test void pythonPreflightCannotPublishEvenWhenOriginalDraftHashMatches() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var jobs=new PrivateValidationJobs(jdbc,json,mock(PrivateProblemValidationGateway.class),mock(PlatformTransactionManager.class));
        when(jdbc.query(contains("AND draftHash=?"),any(RowMapper.class),eq("task"),anyString(),eq("task"))).thenReturn(List.of("{\"passed\":true,\"oracleValidated\":true,\"pythonPreflightValidated\":true,\"runtimeValidated\":false}"));
        try{assertThrows(BusinessException.class,()->jobs.proof("task",json.readTree(fixture())));}finally{jobs.close();}
    }

    @Test void bindingExcludesOnlyCrossLanguageSourcesAndProtocol() throws Exception {
        var a=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(fixture());
        a.putObject("generation").put("protocol","CANONICAL_PYTHON_PREFLIGHT_V1").put("oracleSolution","independent");
        var b=a.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)b.path("referenceSolutions")).put("java:FUNCTION","changed java");
        ((com.fasterxml.jackson.databind.node.ObjectNode)b.path("generation")).put("protocol","CANONICAL_LINES_V1");
        assertEquals(PrivateValidationJobs.pythonBinding(a),PrivateValidationJobs.pythonBinding(b));
        ((com.fasterxml.jackson.databind.node.ObjectNode)b.path("referenceSolutions")).put("python:FUNCTION","changed python");
        assertNotEquals(PrivateValidationJobs.pythonBinding(a),PrivateValidationJobs.pythonBinding(b));
        b=a.deepCopy();b.put("title","changed semantic contract");assertNotEquals(PrivateValidationJobs.pythonBinding(a),PrivateValidationJobs.pythonBinding(b));
    }
}
