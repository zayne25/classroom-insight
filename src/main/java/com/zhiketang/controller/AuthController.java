package com.zhiketang.controller;

import com.zhiketang.common.BizException;
import com.zhiketang.common.R;
import com.zhiketang.common.SessionUser;
import com.zhiketang.interceptor.LoginInterceptor;
import com.zhiketang.service.AuditService;
import com.zhiketang.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 认证接口
 *
 * 登录成功与失败都写入审计日志（模块：认证），
 * 这样"谁在什么时候尝试登录、为什么失败"可在管理后台的日志页追溯。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuditService audit;

    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String username = String.valueOf(body.getOrDefault("username", "")).trim();
        String password = String.valueOf(body.getOrDefault("password", ""));
        long t0 = System.currentTimeMillis();
        String ip = clientIp(request);

        SessionUser u;
        try {
            u = authService.login(username, password);
        } catch (BizException e) {
            audit.writeRaw(request, null, username, "认证", "登录", "账号 " + username,
                    "来源 IP " + ip, false, e.getMessage(), System.currentTimeMillis() - t0);
            throw e;
        }

        HttpSession session = request.getSession(true);
        session.setAttribute(LoginInterceptor.SESSION_KEY, u);
        authService.afterLoginSuccess(u.getUserId(), ip);
        audit.writeRaw(request, u.getUserId(), u.getUsername(), "认证", "登录", "账号 " + username,
                "来源 IP " + ip, true, null, System.currentTimeMillis() - t0);

        Map<String, Object> data = new HashMap<>();
        data.put("user_id", u.getUserId());
        data.put("username", u.getUsername());
        data.put("real_name", u.getRealName());
        data.put("role", u.getRole());
        data.put("home", u.isStudent() ? "/student/courses"
                : ("admin".equals(u.getRole()) ? "/admin/monitor" : "/teacher/dashboard"));
        return R.ok(data, "登录成功");
    }

    @PostMapping("/logout")
    public R<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            Object o = session.getAttribute(LoginInterceptor.SESSION_KEY);
            if (o instanceof SessionUser u) {
                audit.write(request, u, "认证", "退出登录", "账号 " + u.getUsername(), null, true, null, 0);
            }
            session.invalidate();
        }
        return R.ok(null, "已退出登录");
    }

    @GetMapping("/profile")
    public R<Map<String, Object>> profile(HttpSession session) {
        Object o = session.getAttribute(LoginInterceptor.SESSION_KEY);
        if (o == null) return R.fail("未登录", 401);
        SessionUser u = (SessionUser) o;
        Map<String, Object> data = new HashMap<>();
        data.put("user_id", u.getUserId());
        data.put("username", u.getUsername());
        data.put("real_name", u.getRealName());
        data.put("role", u.getRole());
        data.put("class_id", u.getClassId());
        return R.ok(data);
    }

    private String clientIp(HttpServletRequest request) {
        String x = request.getHeader("X-Forwarded-For");
        if (x != null && !x.isBlank()) return x.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
