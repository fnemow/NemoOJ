package com.nemo.OJbackendquestionservice.budget;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AiDailyBudgetTest {
    @Test void retainedUncertainCostsCountAgainstNextAdmission() {
        var jdbc=mock(JdbcTemplate.class);var day=LocalDate.of(2026,10,2);
        when(jdbc.query(contains("FROM ai_usage u"),
                org.mockito.ArgumentMatchers.<RowMapper<BigDecimal>>any(),eq(day),isNull(),isNull()))
                .thenReturn(List.of(new BigDecimal("0.40"),new BigDecimal("0.50")));
        assertFalse(AiDailyBudget.canReserve(jdbc,day,new BigDecimal("0.11"),BigDecimal.ONE));
        assertTrue(AiDailyBudget.canReserve(jdbc,day,new BigDecimal("0.10"),BigDecimal.ONE));
        verify(jdbc,times(2)).query(contains("'RELEASED'"),org.mockito.ArgumentMatchers.<RowMapper<BigDecimal>>any(),eq(day),isNull(),isNull());
        verify(jdbc,times(2)).queryForObject(contains("FOR UPDATE"),eq(LocalDate.class),eq(day));
    }
    @Test void knownCostAndUnknownEarlierCallBothConsumeBudget() {
        var jdbc=mock(JdbcTemplate.class);var day=LocalDate.of(2026,10,3);
        when(jdbc.query(contains("FROM ai_model_call WHERE"),org.mockito.ArgumentMatchers.<RowMapper<BigDecimal>>any(),eq(day)))
                .thenReturn(List.of(new BigDecimal("0.70"),new BigDecimal("0.20")));
        assertFalse(AiDailyBudget.canReserve(jdbc,day,new BigDecimal("0.11"),BigDecimal.ONE));
        assertTrue(AiDailyBudget.canReserve(jdbc,day,new BigDecimal("0.10"),BigDecimal.ONE));
    }
    @Test void disabledOrInvalidBudgetFailsClosed() {
        var jdbc=mock(JdbcTemplate.class);
        assertFalse(AiDailyBudget.canReserve(jdbc,LocalDate.now(),BigDecimal.ONE,BigDecimal.ZERO));
        verifyNoInteractions(jdbc);
    }
}
