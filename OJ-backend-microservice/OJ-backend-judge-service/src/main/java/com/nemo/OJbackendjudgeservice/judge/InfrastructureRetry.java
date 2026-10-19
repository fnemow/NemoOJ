package com.nemo.OJbackendjudgeservice.judge;

import feign.FeignException;
import org.springframework.amqp.ImmediateRequeueAmqpException;

/** Keep durable messages unacknowledged during short dependency outages. */
final class InfrastructureRetry {
    private InfrastructureRetry() { }

    static boolean isUnavailable(Throwable error) {
        for (int depth = 0; error != null && depth < 20; depth++, error = error.getCause()) {
            if (error instanceof FeignException feign
                    && (feign.status() == -1 || feign.status() == 502
                    || feign.status() == 503 || feign.status() == 504)) return true;
            if (error instanceof java.net.ConnectException
                    || error instanceof java.net.SocketTimeoutException
                    || error instanceof java.net.UnknownHostException) return true;
        }
        return false;
    }

    static void requeueAfterDelay(Throwable cause) {
        try {
            Thread.sleep(5000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        // Explicit requeue overrides default-requeue-rejected=false, including the DLQ.
        throw new ImmediateRequeueAmqpException("Dependency unavailable; preserve judge message", cause);
    }
}
