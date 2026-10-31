package com.zhiketang.common;

import com.zhiketang.service.PermService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;

import java.util.Arrays;

/**
 * 控制器基类：提供当前用户读取、角色校验与权限点校验
 */
public abstract class BaseController {

    @Autowired
    @Lazy
    protected HttpSession session;

    @Autowired
    @Lazy
    protected PermService permService;

    protected SessionUser cur() {
        Object o = session.getAttribute("user");
        if (o == null) {
            throw new BizException("未登录或登录已失效", 401);
        }
        return (SessionUser) o;
    }

    /** 角色校验，不匹配抛 403 */
    protected void requireRole(String... roles) {
        SessionUser u = cur();
        if (!Arrays.asList(roles).contains(u.getRole())) {
            throw new BizException("无访问权限", 403);
        }
    }

    /**
     * 权限点校验：先按角色粗筛，再按 role_permission 配置的权限点细筛。
     * 管理员角色恒拥有全部权限（PermService 中的安全边界），因此只对教师 / 学生生效。
     */
    protected void requirePerm(String code) {
        SessionUser u = cur();
        permService.require(u.getRole(), code);
    }

    /** 页面级角色校验，不匹配返回重定向指令 */
    protected String requirePageRole(String... roles) {
        SessionUser u = cur();
        if (!Arrays.asList(roles).contains(u.getRole())) {
            return "redirect:/";
        }
        return null;
    }
}
