package com.zhiketang.common;

import lombok.Data;

/**
 * 统一接口响应格式：{code, msg, data}
 * code = 0 表示成功，其余为失败
 */
@Data
public class R<T> {

    private int code;
    private String msg;
    private T data;

    public static <T> R<T> ok() {
        return ok(null, "success");
    }

    public static <T> R<T> ok(T data) {
        return ok(data, "success");
    }

    public static <T> R<T> ok(T data, String msg) {
        R<T> r = new R<>();
        r.code = 0;
        r.msg = msg;
        r.data = data;
        return r;
    }

    public static <T> R<T> fail(String msg) {
        return fail(msg, 1);
    }

    public static <T> R<T> fail(String msg, int code) {
        R<T> r = new R<>();
        r.code = code;
        r.msg = msg;
        r.data = null;
        return r;
    }
}
