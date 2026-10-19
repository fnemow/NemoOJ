package com.nemo.OJbackendquestionservice.outbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class ValidationDeadlineTest {
    @Test void queuedResumeRetainsDeadlineAndUsesExistingFailureSettlement(){
        var jdbc=mock(JdbcTemplate.class);var reaper=new AiTaskLeaseReaper(jdbc,new ObjectMapper());
        reaper.recoverExpiredLeases();
        var order=inOrder(jdbc);
        order.verify(jdbc).update(contains("status='QUEUED' AND stage='VALIDATION_READY' AND startedAt<="));
        order.verify(jdbc).query(startsWith("SELECT id,type,ownerUserId"),any(org.springframework.jdbc.core.RowMapper.class),anyInt());
    }
}
