package com.nemo.OJbackendquestionservice.outbox;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 事务性发件箱事件。
 * <p>
 * 注意：本表**不使用逻辑删除**。已投递/永久失败的记录是投递历史的唯一凭证，
 * 被逻辑删除后无法解释"某条提交到底投递过没有"。
 */
@TableName("outbox_event")
@Data
public class OutboxEvent implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 事件唯一 id（随机串），同时作为消费者幂等键 */
    private String eventId;

    /** 关联的业务 id（如提交 id）；用数值列而非字符串拼接，避免排序规则冲突 */
    private Long bizId;

    private String eventType;

    private String payload;

    /** 见 {@link OutboxStatus} */
    private Integer status;

    private Integer retryCount;

    private Date nextAttemptAt;

    /** 投递租约持有者；值为 {@code 实例标识#每轮唯一标记}，用于精确取回本轮抢到的任务 */
    private String leaseOwner;

    private Date leaseExpireAt;

    private String lastError;

    private Date createTime;

    private Date updateTime;

    /** Age calculated by MySQL in the same clock domain as createTime. */
    @TableField(exist = false)
    private Long dispatchAgeMicros;

    private static final long serialVersionUID = 1L;
}
