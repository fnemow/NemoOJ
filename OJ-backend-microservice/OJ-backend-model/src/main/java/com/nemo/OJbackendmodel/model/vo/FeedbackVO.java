package com.nemo.OJbackendmodel.model.vo;

import lombok.Data;

import java.util.Date;

@Data
public class FeedbackVO {
    private Long id;
    private Long userId;
    private String userAccount;
    private String userName;
    private String type;
    private Long problemNo;
    private String title;
    private String content;
    private String status;
    private String adminReply;
    private String agentStatus;
    private String replySource;
    private Long handledBy;
    private Date handledAt;
    private Long rowVersion;
    private Date createTime;
    private Date updateTime;
}
