package com.nemo.OJbackendcommon.exception;
import com.nemo.OJbackendcommon.common.BaseResponse;
import com.nemo.OJbackendcommon.common.ErrorCode;
import com.nemo.OJbackendcommon.common.ResultUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 *
 * @author <a href="https://github.com/liyupi">程序员鱼皮</a>
 * @from <a href="https://yupi.icu">编程导航知识星球</a>
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ServiceBusyException.class)
    public org.springframework.http.ResponseEntity<BaseResponse<?>> busyExceptionHandler(ServiceBusyException e) {
        return org.springframework.http.ResponseEntity.status(503)
                .header("Retry-After", Integer.toString(e.getRetryAfterSeconds()))
                .body(ResultUtils.error(e.getCode(), e.getMessage(), e.getErrorKey()));
    }

    @ExceptionHandler(BusinessException.class)
    public BaseResponse<?> businessExceptionHandler(BusinessException e) {
        log.error("BusinessException", e);
        return ResultUtils.error(e.getCode(), e.getMessage(), e.getErrorKey());
    }

    /**
     * 唯一键冲突兜底。业务代码应各自给出更贴切的提示（例如注册时的"账号重复"），
     * 这里是防止它落到 RuntimeException 分支变成一句无信息的"系统错误"。
     * 不回显数据库原始报错，避免泄漏表名/索引名等结构信息。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public BaseResponse<?> duplicateKeyExceptionHandler(DuplicateKeyException e) {
        log.warn("唯一约束冲突: {}", e.getMostSpecificCause().getMessage());
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, "数据已存在，请勿重复提交");
    }

    @ExceptionHandler(RuntimeException.class)
    public BaseResponse<?> runtimeExceptionHandler(RuntimeException e) {
        log.error("RuntimeException", e);
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "系统错误");
    }
}
