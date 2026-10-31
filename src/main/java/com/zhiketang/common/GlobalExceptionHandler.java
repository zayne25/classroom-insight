package com.zhiketang.common;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：把业务异常与未知异常统一转成 {code,msg,data} 格式，
 * 并同步设置 HTTP 状态码（如 401 未登录、403 无权限）
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    @ResponseBody
    public R<?> handleBiz(BizException e, HttpServletResponse response) {
        int http = e.getHttpStatus();
        if (http != 200) {
            response.setStatus(http);
        }
        return R.fail(e.getMessage(), http == 200 ? 1 : http);
    }

    @ExceptionHandler(Exception.class)
    @ResponseBody
    public R<?> handleOther(Exception e) {
        log.error("系统异常", e);
        return R.fail("服务器内部错误：" + e.getMessage());
    }
}
