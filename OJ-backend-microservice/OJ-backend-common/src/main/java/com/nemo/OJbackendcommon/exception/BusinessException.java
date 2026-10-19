package com.nemo.OJbackendcommon.exception;


import com.nemo.OJbackendcommon.common.ErrorCode;

/**
 * 自定义异常类
 *
 * @author <a href="https://github.com/liyupi">程序员鱼皮</a>
 * @from <a href="https://yupi.icu">编程导航知识星球</a>
 */
public class BusinessException extends RuntimeException {

    /**
     * 错误码
     */
    private final int code;
    private final String errorKey;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
        this.errorKey = null;
    }

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
        this.errorKey = null;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
        this.errorKey = null;
    }

    public BusinessException(ErrorCode errorCode, String message, String errorKey) {
        super(message);
        this.code = errorCode.getCode();
        this.errorKey = errorKey;
    }

    public int getCode() {
        return code;
    }

    public String getErrorKey() { return errorKey; }
}
