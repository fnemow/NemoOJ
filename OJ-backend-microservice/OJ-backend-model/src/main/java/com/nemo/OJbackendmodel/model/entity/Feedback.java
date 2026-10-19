package com.nemo.OJbackendmodel.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("feedback")
public class Feedback {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String type;
    private Long problemNo;
    private String title;
    private String content;
    private String status;
    private String adminReply;
    private String agentStatus;
    private String replySource;
    private String agentTaskId;
    private Long handledBy;
    private Date handledAt;
    private Long rowVersion;
    private Date createTime;
    private Date updateTime;
    @TableLogic
    private Integer isDelete;
}
