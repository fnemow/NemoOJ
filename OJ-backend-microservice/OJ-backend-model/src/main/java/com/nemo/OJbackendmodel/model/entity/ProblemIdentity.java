package com.nemo.OJbackendmodel.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** Stable public identity and publication pointer for one logical problem. */
@Data
@TableName("question_public_number")
public class ProblemIdentity {
    @TableId(type = IdType.AUTO)
    private Long problemNo;
    private Long questionId;
    private Long currentQuestionId;
    private String status;
    private Long lifecycleVersion;
    private Long lastOperatorId;
    private String offlineReason;
    private Date updateTime;
}
