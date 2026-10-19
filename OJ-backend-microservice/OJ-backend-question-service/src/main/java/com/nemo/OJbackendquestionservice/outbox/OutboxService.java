package com.nemo.OJbackendquestionservice.outbox;

import com.nemo.OJbackendcommon.constant.JudgeMqConstant;
import com.nemo.OJbackendquestionservice.mapper.OutboxEventMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;

import java.util.UUID;

/**
 * 发件箱写入。
 * <p>
 * 关键约定：{@link #enqueue} 必须在**调用方的事务内**执行，
 * 与业务数据（提交记录）同一次提交或回滚。调用方负责标注 {@code @Transactional}。
 * 这里刻意不加 {@code REQUIRES_NEW}——那会把事件与业务数据拆成两个事务，
 * 正是要消除的问题。
 */
@Slf4j
@Service
public class OutboxService {

    private final OutboxEventMapper outboxEventMapper;
    private final ApplicationEventPublisher eventPublisher;

    public OutboxService(OutboxEventMapper outboxEventMapper,
                         ApplicationEventPublisher eventPublisher) {
        this.outboxEventMapper = outboxEventMapper;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 写入一条待投递事件。
     *
     * @param eventType 事件类型，如 {@link #EVENT_TYPE_JUDGE_SUBMIT}
     * @param bizId     关联的业务 id（提交 id），供对账按业务维度反查
     * @param payload   消息体（JSON）
     * @return 事件 id（同时是消费者的幂等键）
     */
    public String enqueue(String eventType, long bizId, String payload) {
        String eventId = UUID.randomUUID().toString().replace("-", "");
        OutboxEvent event = new OutboxEvent();
        event.setEventId(eventId);
        event.setBizId(bizId);
        event.setEventType(eventType);
        event.setPayload(payload);
        event.setStatus(OutboxStatus.PENDING);
        event.setRetryCount(0);
        // Let MySQL's CURRENT_TIMESTAMP default use the same clock/time zone as
        // claimDueEvents' NOW(). A JDBC-converted Java Date can be hours ahead
        // of the database session and silently delay an immediately due event.
        outboxEventMapper.insert(event);
        // TransactionalEventListener delays the wake-up until commit. Publishing
        // here therefore cannot expose an uncommitted submission to a consumer.
        eventPublisher.publishEvent(new OutboxEnqueuedEvent(eventId));
        return eventId;
    }

    /** 判题提交事件：消息体为提交 id */
    public static final String EVENT_TYPE_JUDGE_SUBMIT = "JUDGE_SUBMIT";

    /** 该类型事件使用的交换机与路由键 */
    public static final String JUDGE_EXCHANGE = JudgeMqConstant.JUDGE_EXCHANGE;

    public static final String JUDGE_ROUTING_KEY = JudgeMqConstant.JUDGE_ROUTING_KEY;
}
