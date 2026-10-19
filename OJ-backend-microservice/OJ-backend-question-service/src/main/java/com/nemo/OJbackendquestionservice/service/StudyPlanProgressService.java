package com.nemo.OJbackendquestionservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/** Completes only the owner's active plan items bound to an accepted snapshot. */
@Slf4j
@Service
public class StudyPlanProgressService {
    private static final String SNAPSHOT_MATCH = "((i.source='PUBLIC' AND q.problemSource='PUBLIC' "
            + "AND i.publicQuestionId=q.questionId AND i.publicVersion=q.problemVersionId) "
            + "OR (i.source='PRIVATE' AND q.problemSource='PRIVATE' "
            + "AND i.privateVersionId=q.privateProblemVersionId COLLATE utf8mb4_unicode_ci))";

    private final JdbcTemplate jdbc;

    public StudyPlanProgressService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The TODO predicate makes duplicate judge-completion events idempotent. */
    public int completeForSubmission(long submitId) {
        return jdbc.update("UPDATE study_plan_item i JOIN study_plan p ON p.id=i.planId "
                        + "JOIN question_submit q ON q.id=? AND q.userId=p.userId "
                        + "SET i.itemStatus='DONE',i.completedBySubmitId=q.id,i.completedAt=q.updateTime "
                        + "WHERE i.itemStatus='TODO' AND p.status='ACTIVE' AND q.status=2 "
                        + "AND q.isDelete=0 AND q.createTime>=i.createTime AND " + SNAPSHOT_MATCH,
                submitId);
    }

    /** Repairs a crash between the verdict commit and progress update. */
    @Scheduled(fixedDelayString = "${ai.plan.progress-reconcile-ms:60000}")
    public void reconcile() {
        try {
            List<Long> submitIds = jdbc.query("SELECT DISTINCT q.id FROM question_submit q "
                            + "JOIN study_plan p ON p.userId=q.userId AND p.status='ACTIVE' "
                            + "JOIN study_plan_item i ON i.planId=p.id AND i.itemStatus='TODO' "
                            + "WHERE q.status=2 AND q.isDelete=0 AND q.createTime>=i.createTime AND "
                            + SNAPSHOT_MATCH + " ORDER BY q.id LIMIT 100",
                    (rs, row) -> rs.getLong(1));
            for (Long submitId : submitIds) {
                try {
                    completeForSubmission(submitId);
                } catch (Exception failure) {
                    log.warn("题单进度补偿失败, submitId={}", submitId, failure);
                }
            }
        } catch (Exception failure) {
            log.warn("题单进度补偿扫描失败", failure);
        }
    }
}
