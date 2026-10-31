package com.zhiketang.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录拦截器
 * - API 请求（/api/**）未登录：返回 401 JSON
 * - 页面请求未登录：重定向到登录页
 */
@Component
public class LoginInterceptor implements HandlerInterceptor {

    public static final String SESSION_KEY = "user";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(SESSION_KEY) != null) {
            return true;
        }

        String uri = request.getRequestURI();
        if (uri.startsWith("/api/")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"msg\":\"未登录或登录已失效\",\"data\":null}");
            return false;
        }
        response.sendRedirect(request.getContextPath() + "/login");
        return false;
    }
}
