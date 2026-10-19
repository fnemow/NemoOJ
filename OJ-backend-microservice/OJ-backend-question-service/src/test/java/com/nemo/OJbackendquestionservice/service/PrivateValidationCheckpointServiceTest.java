package com.nemo.OJbackendquestionservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nemo.OJbackendmodel.model.dto.question.PrivateValidationCheckpoint;
import com.nemo.OJbackendcommon.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PrivateValidationCheckpointServiceTest {
    @Test void repairReusesOnlySameTaskSameContractMatchingSuccessfulExecutions() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var json=new ObjectMapper();var service=new PrivateValidationCheckpointService(jdbc,json);var value=evidence();
        org.springframework.test.util.ReflectionTestUtils.setField(service,"repairReuseEnabled",true);
        var before=json.createObjectNode();before.put("contentMarkdown","frozen contract");
        before.putObject("generation").put("protocol","CANONICAL_LINES_V1").put("oracleSolution","independent oracle");
        before.putObject("referenceSolutions").put("java:FUNCTION","bad java").put("python:FUNCTION","good python");
        var after=before.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)after.path("referenceSolutions")).put("java:FUNCTION","fixed java");
        when(jdbc.queryForList(contains("v.status='RUNNING'"),eq(value.getValidationId()))).thenReturn(List.of(Map.of("taskId","same-task","revision",2,"draftJson",after.toString())));
        when(jdbc.queryForList(startsWith("SELECT c.evidenceJson,p.draftJson"),eq("same-task"),eq(2),eq(value.getExecutionHash())))
            .thenReturn(List.of(Map.of("evidenceJson",json.writeValueAsString(value),"draftJson",before.toString())));
        assertNotNull(service.load(value.getValidationId(),value.getExecutionHash()));
        verify(jdbc).queryForList(contains("p.taskId=? AND p.revision<? AND p.status='FAILED'"),eq("same-task"),eq(2),eq(value.getExecutionHash()));
        after.put("contentMarkdown","changed contract");
        when(jdbc.queryForList(contains("v.status='RUNNING'"),eq(value.getValidationId()))).thenReturn(List.of(Map.of("taskId","same-task","revision",2,"draftJson",after.toString())));
        assertNull(service.load(value.getValidationId(),value.getExecutionHash()));
    }
    @Test void onlyLiveSameTaskFullValidationCanReuseMatchingPreflightHash() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var json=new ObjectMapper();var service=new PrivateValidationCheckpointService(jdbc,json);var value=evidence();
        var d=json.createObjectNode();d.putObject("generation").put("protocol","CANONICAL_LINES_V1");d.putObject("referenceSolutions").put("python:FUNCTION","python");
        when(jdbc.queryForList(contains("v.status='RUNNING'"),eq(value.getValidationId()))).thenReturn(List.of(Map.of("taskId","same-task","revision",2,"draftJson",d.toString())));
        when(jdbc.query(startsWith("SELECT c.evidenceJson"),any(RowMapper.class),eq("same-task"),eq(2),eq(PrivateValidationJobs.pythonBinding(d)),eq(value.getExecutionHash()))).thenReturn(List.of(json.writeValueAsString(value)));
        assertEquals(20,service.load(value.getValidationId(),value.getExecutionHash()).getOutputs().size());
        verify(jdbc).query(contains("p.taskId=? AND p.revision<? AND p.status='SUCCEEDED'"),any(RowMapper.class),eq("same-task"),eq(2),anyString(),eq(value.getExecutionHash()));
        var changed=d.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)changed.path("referenceSolutions")).put("python:FUNCTION","changed");
        when(jdbc.queryForList(contains("v.status='RUNNING'"),eq(value.getValidationId()))).thenReturn(List.of(Map.of("taskId","same-task","revision",2,"draftJson",changed.toString())));
        assertNull(service.load(value.getValidationId(),value.getExecutionHash()));
    }
    @Test void expiredOrUnknownJobCannotBorrowOtherTaskEvidence(){
        var jdbc=mock(JdbcTemplate.class);var service=new PrivateValidationCheckpointService(jdbc,new ObjectMapper());var value=evidence();
        assertNull(service.load(value.getValidationId(),value.getExecutionHash()));
        verify(jdbc,never()).query(startsWith("SELECT c.evidenceJson"),any(RowMapper.class),any(),any(),any(),any());
    }
    PrivateValidationCheckpoint evidence(){var value=new PrivateValidationCheckpoint();value.setValidationId(UUID.randomUUID().toString());value.setExecutionHash("a".repeat(64));value.setLeaseToken(UUID.randomUUID().toString());value.setOutputs(Collections.nCopies(20,"3"));value.setTime(1L);value.setMemory(1024L);return value;}
    @Test void rejectsExpiredWorkerBeforeWritingAndBoundsEvidenceSize(){
        var jdbc=mock(JdbcTemplate.class);var service=new PrivateValidationCheckpointService(jdbc,new ObjectMapper());
        var value=evidence();assertThrows(BusinessException.class,()->service.save(value));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
        value.setOutputs(List.of("3"));assertThrows(BusinessException.class,()->service.save(value));
    }
    @Test void durableInsertIsLeaseConditionalAndDoesNotStoreToken() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var json=new ObjectMapper();var service=new PrivateValidationCheckpointService(jdbc,json);var value=evidence();
        when(jdbc.queryForObject(anyString(),eq(Integer.class),eq(value.getValidationId()),eq(value.getLeaseToken()))).thenReturn(1);
        when(jdbc.update(anyString(),any(),any(),any(),any(),any())).thenReturn(1);
        service.save(value);
        var encoded=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("v.leaseToken=? AND v.leaseExpireAt>=NOW()"),eq(value.getValidationId()),eq(value.getExecutionHash()),encoded.capture(),eq(value.getValidationId()),eq(value.getLeaseToken()));
        assertFalse(json.readTree(encoded.getValue()).has("leaseToken"));
        when(jdbc.query(startsWith("SELECT evidenceJson"),any(RowMapper.class),eq(value.getValidationId()),eq(value.getExecutionHash()))).thenReturn(List.of(encoded.getValue()));
        when(jdbc.queryForList(contains("v.status='RUNNING'"),eq(value.getValidationId()))).thenReturn(List.of(Map.of("taskId","same-task","revision",1,"draftJson","{}")));
        assertEquals(20,service.load(value.getValidationId(),value.getExecutionHash()).getOutputs().size());
    }
    @Test void leaseLostBetweenCheckAndInsertNeverAcknowledgesProgress(){
        var jdbc=mock(JdbcTemplate.class);var service=new PrivateValidationCheckpointService(jdbc,new ObjectMapper());var value=evidence();
        when(jdbc.queryForObject(anyString(),eq(Integer.class),eq(value.getValidationId()),eq(value.getLeaseToken()))).thenReturn(1,0);
        assertThrows(BusinessException.class,()->service.save(value));
    }
}
