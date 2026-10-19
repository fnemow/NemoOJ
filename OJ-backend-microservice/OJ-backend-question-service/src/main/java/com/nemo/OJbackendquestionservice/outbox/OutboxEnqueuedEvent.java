package com.nemo.OJbackendquestionservice.outbox;

/**
 * Signals that a durable outbox row was written.
 *
 * <p>The listener runs after the surrounding transaction commits. The event is
 * only a latency hint: the scheduled outbox scan remains the source of truth
 * and repairs a missed or failed wake-up.</p>
 */
public record OutboxEnqueuedEvent(String eventId) {
}
