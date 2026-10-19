package com.nemo.OJbackendquestionservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nemo.OJbackendquestionservice.version.QuestionVersion;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface QuestionVersionMapper extends BaseMapper<QuestionVersion> {


    /** 取当前最大版本号（无版本时为 0） */
    @Select("SELECT COALESCE(MAX(version), 0) FROM question_version WHERE questionId = #{questionId}")
    int maxVersion(@Param("questionId") long questionId);
}
