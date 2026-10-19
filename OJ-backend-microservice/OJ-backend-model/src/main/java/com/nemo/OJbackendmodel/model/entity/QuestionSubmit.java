package com.nemo.OJbackendmodel.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;

import com.nemo.OJbackendmodel.model.codesandbox.JudgeInfo;
import lombok.Data;

import java.util.Date;

/**
 * 题目提交
 * @TableName question_submit
 */
@TableName(value ="question_submit", autoResultMap = true)
@Data
public class QuestionSubmit {
    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 编程语言
     */
    private String language;

    /** 提交时选择的执行模式：ACM / FUNCTION。 */
    private String executionMode;

    /**
     * 用户代码
     */
    private String code;

    /**
     * 判题信息（json 对象）
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private JudgeInfo judgeInfo;

    /**
     * 判题状态（0 - 待判题、1 - 判题中、2 - 成功、3 - 失败）
     */
    private Integer status;

    /**
     * 题目版本号（提交时刻的快照，保证历史提交可复现）
     */
    private Integer problemVersionId;

    /**
     * Runner/Harness 版本号（提交时刻的快照）
     */
    private Integer harnessVersion;

    /**
     * 题目 id
     */
    private Long questionId;

    /** PUBLIC/PRIVATE separates public leaderboard/statistics from personal exercises. */
    private String problemSource;

    /** Immutable private exercise version when problemSource is PRIVATE. */
    private String privateProblemVersionId;

    // ===== 以下为执行控制字段（T06），对外状态值不变，仅供内部使用 =====

    /**
     * 当前执行代次令牌。每次抢占生成新值，所有写回都必须携带它；
     * 令牌不匹配说明本次执行已被接管或结果已完成，写入会被拒绝。
     */
    private String executionToken;
    /** Internal immutable runtime snapshot selected before enqueueing. */
    private String runtimeMetadata;

    private String clientRequestId;

    private String requestHash;

    /**
     * 执行租约到期时间。执行方需在到期前续租，否则视为失联可被回收重投。
     */
    private Date leaseExpireAt;

    /**
     * 已执行次数。超过上限进入系统错误终态，不再无限重试。
     */
    private Integer attemptCount;

    /**
     * 最后一次执行失败原因，用于人工判断是否值得重投。
     */
    private String lastError;

    /**
     * 绑定的题目版本是否可精确复现。
     * V5 之前产生的历史提交没有版本快照，其判题依据已不可还原，该项为 false。
     * 必须如实标记，不能伪造成"有快照"。
     */
    private Integer versionReproducible;

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
    private Integer isDelete;
}
