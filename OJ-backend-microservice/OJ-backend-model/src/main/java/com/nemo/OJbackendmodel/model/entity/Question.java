package com.nemo.OJbackendmodel.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.nemo.OJbackendmodel.model.dto.question.JudgeConfig;
import com.nemo.OJbackendmodel.model.dto.question.ProblemSpec;

import com.nemo.OJbackendmodel.model.dto.question.JudgeCase;
import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 题目
 * @TableName question
 */
@TableName(value ="question", autoResultMap = true)
@Data
public class Question {
    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** Stable public natural-number identifier shared by all versions of a logical problem. */
    private Long problemNo;

    /**
     * 标题
     */
    private String title;

    /**
     * 内容
     */
    private String content;

    /**
     * 标签列表（json 数组）
     */
    private String tags;

    /**
     * 题目答案
     */
    private String answer;

    /**
     * 题目提交数
     */
    private Integer submitNum;

    /**
     * 题目通过数
     */
    private Integer acceptedNum;

    /**
     * 判题用例（json 数组）
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<JudgeCase> judgeCase;

    /**
     * 判题配置（json 对象）
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private JudgeConfig judgeConfig;

    /**
     * 判题规格（核心代码模式的函数签名等；null 表示 ACM 模式）
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private ProblemSpec problemSpec;

    /**
     * 题目版本号（发布后递增，保证历史提交可复现）
     */
    private Integer problemVersion;

    /**
     * 是否已发布（已发布不可直接修改）
     */
    private Boolean isPublished;

    /** Optimistic-lock value exposed by admin APIs. */
    private Long rowVersion;

    /** Previous published question when this row is a draft of a new version. */
    private Long sourceQuestionId;

    /**
     * 点赞数
     */
    private Integer thumbNum;

    /**
     * 收藏数
     */
    private Integer favourNum;

    /**
     * 创建用户 id
     */
    private Long userId;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    /**
     * 是否删除
     */
    @TableLogic
    private Integer isDelete;
}
