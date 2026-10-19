package com.nemo.OJbackendquestionservice.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nemo.OJbackendmodel.model.dto.question.AdminProblemQueryRequest;
import com.nemo.OJbackendmodel.model.entity.ProblemIdentity;
import com.nemo.OJbackendmodel.model.entity.Question;
import com.nemo.OJbackendmodel.model.vo.AdminProblemSummaryVO;

import java.util.Map;

public interface ProblemLifecycleService {
    ProblemIdentity createIdentity(Question rootQuestion);
    ProblemIdentity findByNumber(long problemNo);
    Question resolveOnline(long key);
    Page<AdminProblemSummaryVO> listAdminProblems(AdminProblemQueryRequest request);
    Map<String, Object> publish(long questionId, long expectedRowVersion, long operatorId);
    long createDraft(long sourceQuestionId, long expectedRowVersion, long operatorId);
    ProblemIdentity takeOffline(long problemNo, long expectedLifecycleVersion, String reason, long operatorId);
    ProblemIdentity putOnline(long problemNo, long expectedLifecycleVersion, long operatorId);
}
