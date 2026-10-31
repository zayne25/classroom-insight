package com.zhiketang.common;

/**
 * 业务异常：携带 HTTP 状态码，由全局异常处理器统一转成 JSON
 */
public class BizException extends RuntimeException {

    private final int httpStatus;

    public BizException(String message) {
        this(message, 200);
    }

    public BizException(String message, int httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
