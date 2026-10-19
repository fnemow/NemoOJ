package com.nemo.OJbackendquestionservice.service;

import com.nemo.OJbackendcommon.common.ErrorCode;
import com.nemo.OJbackendcommon.exception.BusinessException;
import com.nemo.OJbackendquestionservice.mapper.SubmissionActivityMapper;
import com.nemo.OJbackendquestionservice.model.DailySubmissionCount;
import com.nemo.OJbackendquestionservice.model.SubmissionActivityVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

@Service
public class SubmissionActivityService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final SubmissionActivityMapper mapper;
    private final Clock clock;

    @Autowired
    public SubmissionActivityService(SubmissionActivityMapper mapper) { this(mapper, Clock.system(ZONE)); }
    SubmissionActivityService(SubmissionActivityMapper mapper, Clock clock) { this.mapper=mapper; this.clock=clock; }

    public SubmissionActivityVO month(long userId, String value) {
        LocalDate today = LocalDate.now(clock.withZone(ZONE));
        YearMonth current = YearMonth.from(today), selected;
        try {
            if(value != null && !value.matches("\\d{4}-\\d{2}")) throw new DateTimeException("Invalid month");
            selected = value == null ? current : YearMonth.parse(value);
            if(selected.isBefore(YearMonth.of(2000,1)) || selected.isAfter(current)) throw new DateTimeException("Out of range");
        } catch(DateTimeException error) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"请选择有效的当前或历史月份");
        }
        Map<String,DailySubmissionCount> counts = new HashMap<>();
        for(var count:mapper.dailyActivity(userId, selected.atDay(1)+" 00:00:00", selected.plusMonths(1).atDay(1)+" 00:00:00"))
            counts.put(count.getDate(),count);
        List<SubmissionActivityVO.Day> days = new ArrayList<>();
        for(int day=1;day<=selected.lengthOfMonth();day++) {
            String date=selected.atDay(day).toString(); var count=counts.get(date);
            days.add(new SubmissionActivityVO.Day(date,count==null?0:count.getSubmissions(),count==null?0:count.getProblems()));
        }
        return new SubmissionActivityVO(selected.toString(),today.toString(),ZONE.getId(),days);
    }
}
