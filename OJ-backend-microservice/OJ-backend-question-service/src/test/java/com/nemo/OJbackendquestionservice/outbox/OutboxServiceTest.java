package com.nemo.OJbackendquestionservice.outbox;

import com.nemo.OJbackendquestionservice.mapper.OutboxEventMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OutboxServiceTest {

    @Test
    void durableInsertAlsoPublishesLatencyWakeup() {
        OutboxEventMapper mapper = mock(OutboxEventMapper.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        OutboxService service = new OutboxService(mapper, publisher);

        String eventId = service.enqueue(OutboxService.EVENT_TYPE_JUDGE_SUBMIT, 42L, "42");

        ArgumentCaptor<OutboxEvent> row = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(mapper).insert(row.capture());
        assertEquals(eventId, row.getValue().getEventId());
        assertEquals(42L, row.getValue().getBizId());
        assertEquals(OutboxStatus.PENDING, row.getValue().getStatus());

        ArgumentCaptor<Object> signal = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(signal.capture());
        assertNotNull(signal.getValue());
        assertEquals(eventId, ((OutboxEnqueuedEvent) signal.getValue()).eventId());
    }
}
