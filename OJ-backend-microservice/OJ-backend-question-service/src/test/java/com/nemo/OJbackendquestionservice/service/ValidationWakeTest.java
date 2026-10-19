package com.nemo.OJbackendquestionservice.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nemo.OJbackendcommon.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class ValidationWakeTest {
    @Test void suspendRequiresOwnedLatestJobAndRetainsAbsoluteDeadline(){
        var jdbc=mock(JdbcTemplate.class);var manager=mock(PlatformTransactionManager.class);
        when(jdbc.queryForList(contains("FOR UPDATE"),eq("task"))).thenReturn(List.of(Map.of("status","RUNNING","leaseOwner","worker","stage","VALIDATING")));
        when(jdbc.queryForObject(startsWith("SELECT COUNT(*)"),eq(Integer.class),any(Object[].class))).thenReturn(1);
        when(jdbc.update(startsWith("UPDATE ai_task SET stage='WAIT_VALIDATION'"),eq("task"),eq("worker"))).thenReturn(1);
        var jobs=new PrivateValidationJobs(jdbc,new ObjectMapper(),mock(PrivateProblemValidationGateway.class),manager);
        try{jobs.suspend("task","worker","job");}
        finally{jobs.close();}
        verify(jdbc).update(contains("leaseOwner=NULL,leaseExpireAt=DATE_ADD(startedAt,INTERVAL 900 SECOND)"),eq("task"),eq("worker"));
    }
    @Test void mismatchedOrMissingJobCannotRelinquishLease(){
        var jdbc=mock(JdbcTemplate.class);var jobs=new PrivateValidationJobs(jdbc,new ObjectMapper(),mock(PrivateProblemValidationGateway.class),mock(PlatformTransactionManager.class));
        try{assertThrows(BusinessException.class,()->jobs.suspend("task","worker","other"));}finally{jobs.close();}
        verify(jdbc,never()).update(startsWith("UPDATE ai_task SET stage='WAIT_VALIDATION'"),any(),any());
    }
    @Test void duplicateWakeProducesOneRevisionSpecificOutboxEvent(){
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.update(startsWith("UPDATE ai_task t SET t.status='QUEUED'"),eq("task"))).thenReturn(1,0);
        when(jdbc.queryForObject(contains("SELECT MAX(revision)"),eq(Integer.class),eq("task"))).thenReturn(2);
        var jobs=new PrivateValidationJobs(jdbc,new ObjectMapper(),mock(PrivateProblemValidationGateway.class),mock(PlatformTransactionManager.class));
        try{ReflectionTestUtils.invokeMethod(jobs,"wakeOne","task");ReflectionTestUtils.invokeMethod(jobs,"wakeOne","task");}finally{jobs.close();}
        verify(jdbc,times(1)).update(startsWith("INSERT INTO ai_outbox"),eq("task"),eq("VALIDATION_2"));
    }
}
