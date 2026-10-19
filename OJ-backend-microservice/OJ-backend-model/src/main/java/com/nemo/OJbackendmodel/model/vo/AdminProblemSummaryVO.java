package com.nemo.OJbackendmodel.model.vo;

import lombok.Data;

import java.util.Date;

@Data
public class AdminProblemSummaryVO {
    private Long problemNo;
    private Long rootQuestionId;
    private Long currentQuestionId;
    private String title;
    private Integer currentVersion;
    private Long currentRowVersion;
    private String status;
    private Long lifecycleVersion;
    private String offlineReason;
    private Long draftId;
    private Integer draftVersion;
    private Date updateTime;
}
