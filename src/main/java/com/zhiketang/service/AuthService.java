package com.zhiketang.service;

import com.zhiketang.common.BizException;
import com.zhiketang.common.SessionUser;
import com.zhiketang.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 认证服务：密码使用 BCrypt 哈希，禁止明文存储
 *
 * 与系统管理模块的联动点（管理员的配置与操作在这里真正生效）：
 * 1. 账号「停用」后禁止登录；
 * 2. 连续登录失败达到 security.max_login_fail 后锁定 security.lock_minutes 分钟，
 *    管理员可「解锁」立即恢复；
 * 3. 管理员重置密码后自动解锁（见 AdminService.resetPassword）。
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final SysConfigService cfg;

    /** BCrypt 强度：10 轮，安全性与性能折中 */
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    public String hash(String raw) {
        return encoder.encode(raw);
    }

    /**
     * 登录校验，成功返回会话用户
     */
    public SessionUser login(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new BizException("用户名与密码不能为空");
        }
        Map<String, Object> u = userMapper.findByUsername(username.trim());
        if (u == null) throw new BizException("用户名或密码错误");

        // 1) 账号状态
        if (!"正常".equals(String.valueOf(u.get("status")))) {
            throw new BizException("账号已停用，请联系系统管理员");
        }
        // 2) 锁定状态
        LocalDateTime locked = parse(u.get("locked_until"));
        if (locked != null && locked.isAfter(LocalDateTime.now())) {
            throw new BizException("账号已锁定，请于 " + locked.format(FMT) + " 后重试，或联系管理员解锁");
        }

        // 3) 密码校验（失败计数与锁定策略）
        if (!encoder.matches(password, String.valueOf(u.get("password_hash")))) {
            int id = ((Number) u.get("user_id")).intValue();
            userMapper.incrLoginFail(id);
            int max = cfg.intOf("security.max_login_fail");
            int fails = ((Number) u.getOrDefault("login_fail_count", 0)).intValue() + 1;
            if (max > 0 && fails >= max) {
                int minutes = cfg.intOf("security.lock_minutes");
                LocalDateTime until = LocalDateTime.now().plusMinutes(Math.max(minutes, 1));
                userMapper.lockUntil(id, until.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                throw new BizException("密码错误已达 " + max + " 次，账号锁定至 " + until.format(FMT)
                        + "，可联系管理员解锁");
            }
            throw new BizException("用户名或密码错误（还可尝试 " + Math.max(max - fails, 0) + " 次）");
        }

        String role = String.valueOf(u.get("role"));
        if (!"teacher".equals(role) && !"student".equals(role) && !"admin".equals(role)) {
            throw new BizException("该账号无权登录");
        }

        SessionUser s = new SessionUser();
        s.setUserId(toInt(u.get("user_id")));
        s.setUsername(String.valueOf(u.get("username")));
        s.setRealName(String.valueOf(u.get("real_name")));
        s.setRole(role);
        Object cid = u.get("class_id");
        s.setClassId(cid == null ? null : toInt(cid));
        return s;
    }

    /** 登录成功后由控制器调用：记录时间、IP 并清零失败计数 */
    public void afterLoginSuccess(int userId, String ip) {
        userMapper.touchLogin(userId,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), ip);
    }

    private LocalDateTime parse(Object o) {
        if (o == null) return null;
        try {
            return LocalDateTime.parse(String.valueOf(o).replace(' ', 'T'));
        } catch (Exception e) {
            return null;
        }
    }

    private int toInt(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }
}
