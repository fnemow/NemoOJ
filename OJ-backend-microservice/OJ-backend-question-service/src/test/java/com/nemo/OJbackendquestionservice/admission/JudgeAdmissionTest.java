package com.nemo.OJbackendquestionservice.admission;

import com.nemo.OJbackendcommon.exception.ServiceBusyException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class JudgeAdmissionTest {
    @AfterEach void cleanup() { TransactionSynchronizationManager.clear(); }
    private JudgeAdmission guard(JdbcTemplate jdbc) {
        var guard = new JudgeAdmission(jdbc, new SimpleMeterRegistry());
        ReflectionTestUtils.setField(guard,"maxPending",12);
        ReflectionTestUtils.setField(guard,"maxOldestSeconds",120);
        ReflectionTestUtils.setField(guard,"retryAfterSeconds",5);
        return guard;
    }
    @Test void countsAllPendingRowsWithCurrentReadBeforeAnyInsert() {
        var jdbc=mock(JdbcTemplate.class);var guard=guard(jdbc);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(jdbc.query(contains("status IN (0,1)"),org.mockito.ArgumentMatchers.<RowMapper<Long>>any(),eq(12)))
                .thenReturn(Collections.nCopies(12,1L));
        ServiceBusyException error=assertThrows(ServiceBusyException.class,guard::check);
        assertEquals("JUDGE_BUSY",error.getErrorKey());assertEquals(5,error.getRetryAfterSeconds());
        verify(jdbc).query(contains("FOR UPDATE NOWAIT"),org.mockito.ArgumentMatchers.<RowMapper<Long>>any(),eq(12));
    }
    @Test void ageProtectionAndRecoveryUseAuthoritativeRowsWithoutPermits() {
        var jdbc=mock(JdbcTemplate.class);var guard=guard(jdbc);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<Long>>any(),eq(12)))
                .thenReturn(List.of(120L)).thenReturn(List.of());
        assertThrows(ServiceBusyException.class,guard::check);
        assertDoesNotThrow(guard::check);
    }
    @Test void cannotBeCalledOutsideSubmissionTransaction() {
        var jdbc=mock(JdbcTemplate.class);
        assertThrows(IllegalStateException.class,guard(jdbc)::check);verifyNoInteractions(jdbc);
    }
}
