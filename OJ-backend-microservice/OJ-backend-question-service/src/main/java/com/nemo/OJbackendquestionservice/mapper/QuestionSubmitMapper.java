package com.nemo.OJbackendquestionservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nemo.OJbackendmodel.model.entity.QuestionSubmit;
import com.nemo.OJbackendquestionservice.model.ProblemSubmissionStats;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 题目提交数据访问。
 * <p>
 * 判题相关写操作全部是**单条条件 UPDATE**，把"校验状态/令牌"和"写入"放在同一条语句里。
 * 不用"先查询再更新"：那中间的窗口足以让旧执行者覆盖新结果，
 * 而本项目此前正是因此产生"迟到结果覆盖已完成结果"的问题。
 */
public interface QuestionSubmitMapper extends BaseMapper<QuestionSubmit> {

    @Select({"<script>",
            "SELECT q.problemNo, COALESCE(SUM(s.submitNum),0) AS submitNum,",
            "COALESCE(SUM(s.acceptedNum),0) AS acceptedNum FROM question q",
            "LEFT JOIN question_submission_stats s ON s.questionId=q.id WHERE q.problemNo IN",
            "<foreach collection='problemNos' item='n' open='(' separator=',' close=')'>#{n}</foreach>",
            "GROUP BY q.problemNo", "</script>"})
    List<ProblemSubmissionStats> readStatsByProblemNos(@Param("problemNos") List<Long> problemNos);

    /**
     * Read live totals for logical problems. A public number may have several immutable
     * question rows, so aggregating through question.problemNo keeps version publication
     * from resetting the visible statistics.
     */
    @Select({
            "<script>",
            "SELECT q.problemNo AS problemNo, COUNT(s.id) AS submitNum,",
            "COALESCE(SUM(CASE WHEN JSON_UNQUOTE(JSON_EXTRACT(s.judgeInfo, '$.message')) = 'accepted' THEN 1 ELSE 0 END), 0) AS acceptedNum",
            "FROM question q",
            "LEFT JOIN question_submit s ON s.questionId = q.id AND s.isDelete = 0",
            "WHERE q.problemNo IN",
            "<foreach collection='problemNos' item='problemNo' open='(' separator=',' close=')'>#{problemNo}</foreach>",
            "GROUP BY q.problemNo",
            "</script>"
    })
    List<ProblemSubmissionStats> aggregateByProblemNos(@Param("problemNos") List<Long> problemNos);

    /**
     * 原子抢占待判题提交。
     * 只有当前状态为 WAITING（0）才能成功，同时生成新令牌并增加执行次数。
     */
    @Update("UPDATE question_submit SET "
            + "  status = 1, "
            + "  executionToken = #{token}, "
            + "  leaseExpireAt = DATE_ADD(NOW(), INTERVAL #{leaseSeconds} SECOND), "
            + "  attemptCount = attemptCount + 1, "
            + "  lastError = NULL "
            + "WHERE id = #{submitId} AND status = 0 AND isDelete = 0")
    int claimForJudging(@Param("submitId") long submitId,
                        @Param("token") String token,
                        @Param("leaseSeconds") int leaseSeconds);

    /**
     * 续租。状态与令牌都必须匹配——租约被回收后令牌已变，续租会失败，
     * 调用方据此停止写回。
     */
    @Update("UPDATE question_submit SET "
            + "  leaseExpireAt = DATE_ADD(NOW(), INTERVAL #{leaseSeconds} SECOND) "
            + "WHERE id = #{submitId} AND status = 1 "
            + "  AND executionToken = #{token} AND isDelete = 0")
    int renewLease(@Param("submitId") long submitId,
                   @Param("token") String token,
                   @Param("leaseSeconds") int leaseSeconds);

    /**
     * 写回终态结果。状态必须仍为 UNDER_JUDGING 且令牌匹配，
     * 因此旧执行的迟到写回、以及对已完成结果的重复写回都会被拒绝。
     */
    @Update("UPDATE question_submit SET "
            + "  status = #{status}, "
            + "  judgeInfo = #{judgeInfo}, "
            + "  executionToken = NULL, "
            + "  leaseExpireAt = NULL, "
            + "  lastError = NULL "
            + "WHERE id = #{submitId} AND status = 1 "
            + "  AND executionToken = #{token} AND isDelete = 0")
    int finishWithToken(@Param("submitId") long submitId,
                        @Param("token") String token,
                        @Param("status") Integer status,
                        @Param("judgeInfo") String judgeInfo);

    /**
     * 执行异常回退到 WAITING。状态与令牌匹配才生效，
     * 避免把已被接管或已完成的任务改回待判题。
     */
    @Update("UPDATE question_submit SET "
            + "  status = 0, "
            + "  executionToken = NULL, "
            + "  leaseExpireAt = NULL, "
            + "  lastError = #{reason} "
            + "WHERE id = #{submitId} AND status = 1 "
            + "  AND executionToken = #{token} AND isDelete = 0")
    int revertToWaitingWithToken(@Param("submitId") long submitId,
                                 @Param("token") String token,
                                 @Param("reason") String reason);

    /**
     * 取出租约已过期的执行中提交（回收器用）。
     * 只取 id 等必要字段，避免把大字段 code/judgeInfo 拉回来。
     */
    @Select("SELECT id, executionToken, attemptCount, createTime FROM question_submit "
            + "WHERE status = 1 AND isDelete = 0 "
            + "  AND leaseExpireAt IS NOT NULL AND leaseExpireAt < NOW() "
            + "ORDER BY leaseExpireAt ASC LIMIT #{batchSize}")
    List<QuestionSubmit> listExpiredLeases(@Param("batchSize") int batchSize);

    /** 回收：退回 WAITING 等待重投。令牌匹配才生效。 */
    @Update("UPDATE question_submit SET "
            + "  status = 0, executionToken = NULL, leaseExpireAt = NULL, lastError = #{reason} "
            + "WHERE id = #{submitId} AND status = 1 "
            + "  AND executionToken = #{token} AND isDelete = 0")
    int reclaimToWaiting(@Param("submitId") long submitId,
                         @Param("token") String token,
                         @Param("reason") String reason);

    /**
     * 死信兜底：仅当仍为 WAITING 时判为系统错误终态。
     * 条件里限定 status = 0，因此已完成的提交不会被覆盖，
     * 正在执行的提交也不会被误杀（那属于租约回收器的职责）。
     */
    @Update("UPDATE question_submit SET "
            + "  status = 3, executionToken = NULL, leaseExpireAt = NULL, "
            + "  judgeInfo = #{judgeInfo}, lastError = #{reason} "
            + "WHERE id = #{submitId} AND status = 0 AND isDelete = 0")
    int markFailedIfWaiting(@Param("submitId") long submitId,
                            @Param("reason") String reason,
                            @Param("judgeInfo") String judgeInfo);

    /**
     * 回收：执行次数超限，判为系统错误终态。
     * 终态后不再重投，避免无限循环消耗资源。
     */
    @Update("UPDATE question_submit SET "
            + "  status = 3, executionToken = NULL, leaseExpireAt = NULL, lastError = #{reason} "
            + "WHERE id = #{submitId} AND status = 1 "
            + "  AND executionToken = #{token} AND isDelete = 0")
    int markSystemErrorTerminal(@Param("submitId") long submitId,
                                @Param("token") String token,
                                @Param("reason") String reason);
}
