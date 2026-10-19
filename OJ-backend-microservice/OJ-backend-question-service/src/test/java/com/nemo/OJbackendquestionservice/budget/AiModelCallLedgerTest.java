package com.nemo.OJbackendquestionservice.budget;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class AiModelCallLedgerTest {
    @Test void callLimitIsCheckedBeforeReservationOrProviderAdmission() {
        var jdbc=mock(JdbcTemplate.class);var ledger=new AiModelCallLedger(jdbc);
        org.springframework.test.util.ReflectionTestUtils.setField(ledger,"maxCalls",4);
        String call=java.util.UUID.randomUUID().toString();
        when(jdbc.queryForList(contains("FROM ai_task"),eq("task"),eq("worker")))
                .thenReturn(List.of(Map.of("modelId","test","attemptCount",2)));
        when(jdbc.queryForObject(contains("COUNT(*)"),eq(Integer.class),eq("task"))).thenReturn(4);
        var error=assertThrows(com.nemo.OJbackendcommon.exception.BusinessException.class,
                ()->ledger.start("task",call,"worker"));
        assertEquals("BUDGET_EXHAUSTED",error.getErrorKey());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void successfulRetryPreservesEarlierUncertainReservation() {
        var jdbc=mock(JdbcTemplate.class);var ledger=new AiModelCallLedger(jdbc);
        when(jdbc.queryForObject(contains("COUNT(*)"),eq(Integer.class),eq("task"))).thenReturn(2);
        ledger.settle("task","COMMITTED",100,20,.001);
        verify(jdbc).update("UPDATE ai_usage SET state=? WHERE taskId=? AND state='RESERVED'","COMMITTED","task");
        verify(jdbc,never()).update(contains("reservedCost=0"),any(Object[].class));
    }
    @Test void duplicateKnownReceiptCannotChangeAccountedUsage() {
        var jdbc=mock(JdbcTemplate.class);var ledger=new AiModelCallLedger(jdbc);
        when(jdbc.queryForList(contains("FROM ai_model_call"),eq("call"),eq("task")))
                .thenReturn(List.of(Map.of("state","KNOWN","workerId","worker")));
        ledger.finish("task","call","worker","KNOWN",10,5,new BigDecimal("0.01"));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void receiptCannotSettleAnotherWorkersCall() {
        var jdbc=mock(JdbcTemplate.class);var ledger=new AiModelCallLedger(jdbc);
        when(jdbc.queryForList(contains("FROM ai_model_call"),eq("call"),eq("task")))
                .thenReturn(List.of(Map.of("state","STARTED","workerId","owner")));
        assertThrows(com.nemo.OJbackendcommon.exception.BusinessException.class,
                ()->ledger.finish("task","call","other","KNOWN",1,1,BigDecimal.ZERO));
    }
}
