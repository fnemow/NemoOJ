package com.nemo.OJbackendcommon.exception;

import com.nemo.OJbackendcommon.common.ErrorCode;

/** Explicit backpressure; callers must not automatically replay a mutation. */
public class ServiceBusyException extends BusinessException {
    private final int retryAfterSeconds;
    public ServiceBusyException(int retryAfterSeconds) {
        super(ErrorCode.OPERATION_ERROR, "判题任务较多，请约 " + retryAfterSeconds + " 秒后再提交", "JUDGE_BUSY");
        this.retryAfterSeconds = retryAfterSeconds;
    }
    public int getRetryAfterSeconds() { return retryAfterSeconds; }
}
