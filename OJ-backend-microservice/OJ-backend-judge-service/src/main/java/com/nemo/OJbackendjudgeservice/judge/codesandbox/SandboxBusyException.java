package com.nemo.OJbackendjudgeservice.judge.codesandbox;

import com.nemo.OJbackendcommon.common.ErrorCode;
import com.nemo.OJbackendcommon.exception.BusinessException;

/** A 503 admission rejection: no code has started, so retrying is safe. */
public class SandboxBusyException extends BusinessException {
    public SandboxBusyException(){super(ErrorCode.SYSTEM_ERROR,"沙箱资源繁忙，等待空闲容量");}
}
