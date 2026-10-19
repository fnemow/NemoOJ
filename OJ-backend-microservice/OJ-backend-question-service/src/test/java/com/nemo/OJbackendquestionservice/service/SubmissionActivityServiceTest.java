package com.nemo.OJbackendquestionservice.service;

import com.nemo.OJbackendcommon.exception.BusinessException;
import com.nemo.OJbackendquestionservice.mapper.SubmissionActivityMapper;
import com.nemo.OJbackendquestionservice.model.DailySubmissionCount;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SubmissionActivityServiceTest {
    private final SubmissionActivityMapper mapper=mock(SubmissionActivityMapper.class);
    private final SubmissionActivityService service=new SubmissionActivityService(mapper,
            Clock.fixed(Instant.parse("2026-09-30T16:01:00Z"),ZoneOffset.UTC));

    @Test void defaultsToShanghaiMonthAndFillsEveryMissingDay() {
        var row=new DailySubmissionCount();row.setDate("2026-10-01");row.setSubmissions(5);row.setProblems(2);
        when(mapper.dailyActivity(42L,"2026-10-01 00:00:00","2026-11-01 00:00:00")).thenReturn(List.of(row));
        var result=service.month(42L,null);
        assertEquals("2026-10",result.month());assertEquals("2026-10-01",result.today());
        assertEquals("Asia/Shanghai",result.timezone());assertEquals(31,result.days().size());
        assertEquals(5,result.days().get(0).submissions());assertEquals(2,result.days().get(0).problems());
        assertEquals(0,result.days().get(1).submissions());assertEquals("2026-10-31",result.days().get(30).date());
        verify(mapper).dailyActivity(42L,"2026-10-01 00:00:00","2026-11-01 00:00:00");
    }
    @Test void supportsHistoricLeapMonthsAndYearBoundaries() {
        when(mapper.dailyActivity(anyLong(),anyString(),anyString())).thenReturn(List.of());
        assertEquals(29,service.month(42L,"2024-02").days().size());
        assertEquals(31,service.month(42L,"2025-12").days().size());
        verify(mapper).dailyActivity(42L,"2025-12-01 00:00:00","2026-01-01 00:00:00");
    }
    @Test void rejectsMalformedFutureAndOutOfRangeMonthsBeforeReadingDatabase() {
        for(var month:List.of("2026-11","2026-00","2026-1","1999-12","","2026-10' OR 1=1"))
            assertThrows(BusinessException.class,()->service.month(42L,month));
        verifyNoInteractions(mapper);
    }
}
