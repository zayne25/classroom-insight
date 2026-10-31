package com.zhiketang.common;

import lombok.Data;

/**
 * 存放在 HttpSession 中的当前登录用户
 */
@Data
public class SessionUser {

    private Integer userId;
    private String username;
    private String realName;
    private String role;
    private Integer classId;

    public boolean isTeacher() {
        return "teacher".equals(role) || "admin".equals(role);
    }

    public boolean isStudent() {
        return "student".equals(role);
    }

    public String getRoleCn() {
        if ("teacher".equals(role)) return "教师";
        if ("student".equals(role)) return "学生";
        if ("admin".equals(role)) return "管理员";
        return role;
    }
}
