package com.nemo.OJbackendquestionservice.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/** Read-only personal calendar aggregation, independent of judge writes. */
public interface SubmissionActivityMapper {
    // createTime is stored in the database's Asia/Shanghai civil time. Keep the
    // range predicate on the raw column; only apply DATE() when grouping.
    @Select("SELECT DATE_FORMAT(s.createTime, '%Y-%m-%d') AS date, COUNT(*) AS submissions, "
            + "COUNT(DISTINCT CASE WHEN s.problemSource='PRIVATE' "
            + "THEN CONCAT('private:',COALESCE(v.privateProblemId,s.privateProblemVersionId)) "
            + "ELSE CONCAT('public:',COALESCE(q.problemNo,s.questionId)) END) AS problems "
            + "FROM question_submit s "
            + "LEFT JOIN question q ON s.problemSource='PUBLIC' AND q.id=s.questionId "
            + "LEFT JOIN private_problem_version v ON s.problemSource='PRIVATE' AND v.id=s.privateProblemVersionId "
            + "WHERE s.userId=#{userId} AND s.isDelete=0 AND s.createTime >= #{start} AND s.createTime < #{end} "
            + "GROUP BY DATE_FORMAT(s.createTime, '%Y-%m-%d') ORDER BY date")
    List<com.nemo.OJbackendquestionservice.model.DailySubmissionCount> dailyActivity(
            @Param("userId") long userId, @Param("start") String start, @Param("end") String end);

}

