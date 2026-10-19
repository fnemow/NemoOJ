package com.nemo.OJbackendjudgeservice.judge;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import static org.junit.jupiter.api.Assertions.*;

class InfrastructureRetryTest {
    @Test void wrappedConnectionFailureIsInfrastructureFailure() {
        assertTrue(InfrastructureRetry.isUnavailable(new RuntimeException(new java.net.ConnectException())));
        assertTrue(InfrastructureRetry.isUnavailable(new java.net.SocketTimeoutException()));
        assertFalse(InfrastructureRetry.isUnavailable(new IllegalArgumentException("invalid code")));
    }

    @Test void interruptionPreservesMessageAndInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(ImmediateRequeueAmqpException.class,
                    () -> InfrastructureRetry.requeueAfterDelay(new java.net.ConnectException()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
