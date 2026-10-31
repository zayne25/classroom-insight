package com.zhiketang.service;

import com.zhiketang.common.BizException;
import com.zhiketang.mapper.SysMapper;
import com.zhiketang.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 管理员服务：用户与角色管理 + 系统监控统计
 *
 * 权限边界（服务端强制，界面上不依赖隐藏按钮）：
 * 1. 不能停用 / 删除 / 降级**自己**——否则会把自己锁在系统外；
 * 2. 不能停用或删除**最后一个启用状态的管理员**——保证系统始终有人可管；
 * 3. 已产生业务数据的账号只能停用、不能删除（外键 RESTRICT / 数据完整性）；
 * 4. 密码一律 BCrypt 哈希，重置密码须满足系统配置的密码策略。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserMapper users;
    private final SysMapper sys;
    private final SysConfigService cfg;
    private final AuthService auth;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final List<String> ROLES = Arrays.asList("admin", "teacher", "student");

    /* ============================================================ 用户查询 */

    public Map<String, Object> listUsers(String role, String status, String kw, int page, int size) {
        page = Math.max(page, 1);
        size = Math.min(Math.max(size, 10), 100);
        int offset = (page - 1) * size;
        List<Map<String, Object>> rows = users.searchUsers(role, status, kw, size, offset);
        int total = users.countUsers(role, status, kw);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("total", total);
        out.put("page", page);
        out.put("size", size);
        out.put("pages", (int) Math.ceil(total * 1.0 / size));
        return out;
    }

    public Map<String, Object> userDetail(int id) {
        Map<String, Object> u = users.findById(id);
        if (u == null) throw new BizException("用户不存在");
        u.remove("password_hash");               // 绝不外泄密码哈希
        u.put("refs", users.refCount(id));
        return u;
    }

    public List<Map<String, Object>> classes() {
        return users.listClasses();
    }

    /* ============================================================ 用户新增 / 编辑 */

    /** 新增用户：账号唯一、密码符合策略、角色合法、学生须有班级 */
    public int createUser(Map<String, Object> body) {
        String username = String.valueOf(body.getOrDefault("username", "")).trim();
        String realName = String.valueOf(body.getOrDefault("real_name", "")).trim();
        String role = String.valueOf(body.getOrDefault("role", "")).trim();
        String password = String.valueOf(body.getOrDefault("password", "")).trim();

        if (!username.matches("[A-Za-z0-9_]{3,32}")) {
            throw new BizException("账号必须为 3~32 位字母、数字或下划线");
        }
        if (realName.isBlank()) throw new BizException("姓名不能为空");
        if (!ROLES.contains(role)) throw new BizException("角色不合法");
        if (users.findByUsername(username) != null) throw new BizException("账号已存在：" + username);
        checkPassword(password);

        Map<String, Object> r = new HashMap<>();
        r.put("username", username);
        r.put("real_name", realName);
        r.put("role", role);
        r.put("gender", blankToNull(body.get("gender")));
        r.put("phone", blankToNull(body.get("phone")));
        r.put("class_id", classIdOf(body.get("class_id"), role));
        r.put("password_hash", auth.hash(password));
        users.insert(r);
        return 1;
    }

    /** 编辑用户：账号不可改；不能改自己的角色或状态 */
    public void updateUser(int id, Map<String, Object> body, int operatorId) {
        Map<String, Object> u = users.findById(id);
        if (u == null) throw new BizException("用户不存在");

        String realName = String.valueOf(body.getOrDefault("real_name", "")).trim();
        String role = String.valueOf(body.getOrDefault("role", "")).trim();
        if (realName.isBlank()) throw new BizException("姓名不能为空");
        if (!ROLES.contains(role)) throw new BizException("角色不合法");

        String oldRole = String.valueOf(u.get("role"));
        if (id == operatorId && !role.equals(oldRole)) {
            throw new BizException("不能修改自己的角色，避免失去管理权限");
        }
        // 降级最后一个管理员：停用/降级都会导致系统无人可管
        if (id != operatorId && "admin".equals(oldRole) && !"admin".equals(role)
                && users.countActiveAdmin() <= 1) {
            throw new BizException("系统必须保留至少一个启用状态的管理员");
        }

        users.updateUser(id, realName, role, blankToNull(body.get("gender")),
                blankToNull(body.get("phone")), classIdOf(body.get("class_id"), role));
    }

    /** 单独分配角色（与编辑共用校验） */
    public void assignRole(int id, String role, int operatorId) {
        if (!ROLES.contains(role)) throw new BizException("角色不合法");
        Map<String, Object> u = users.findById(id);
        if (u == null) throw new BizException("用户不存在");
        if (id == operatorId) throw new BizException("不能修改自己的角色");
        if ("admin".equals(String.valueOf(u.get("role"))) && !"admin".equals(role)
                && users.countActiveAdmin() <= 1) {
            throw new BizException("系统必须保留至少一个启用状态的管理员");
        }
        users.updateUser(id, String.valueOf(u.get("real_name")), role,
                (String) u.get("gender"), (String) u.get("phone"),
                u.get("class_id") == null ? null : ((Number) u.get("class_id")).intValue());
    }

    /** 启用 / 停用 */
    public void setStatus(int id, String status, int operatorId) {
        if (!"正常".equals(status) && !"停用".equals(status)) throw new BizException("状态不合法");
        Map<String, Object> u = users.findById(id);
        if (u == null) throw new BizException("用户不存在");
        if (id == operatorId) throw new BizException("不能停用自己当前登录的账号");
        if ("停用".equals(status) && "admin".equals(String.valueOf(u.get("role")))
                && users.countActiveAdmin() <= 1) {
            throw new BizException("系统必须保留至少一个启用状态的管理员");
        }
        users.setStatus(id, status);
    }

    /** 重置密码（须满足密码策略） */
    public void resetPassword(int id, String password) {
        Map<String, Object> u = users.findById(id);
        if (u == null) throw new BizException("用户不存在");
        checkPassword(password);
        users.setPasswordHash(id, auth.hash(password));
        users.unlock(id);   // 重置密码顺带解锁，避免用户因锁定无法登录
    }

    /** 解除登录锁定 */
    public void unlock(int id) {
        if (users.findById(id) == null) throw new BizException("用户不存在");
        users.unlock(id);
    }

    /** 删除用户：先做引用检查，有业务数据一律拒绝 */
    public void deleteUser(int id, int operatorId) {
        Map<String, Object> u = users.findById(id);
        if (u == null) throw new BizException("用户不存在");
        if (id == operatorId) throw new BizException("不能删除自己当前登录的账号");
        if ("admin".equals(String.valueOf(u.get("role"))) && users.countActiveAdmin() <= 1) {
            throw new BizException("系统必须保留至少一个启用状态的管理员");
        }
        Map<String, Object> ref = users.refCount(id);
        int total = 0;
        for (Object v : ref.values()) total += v == null ? 0 : ((Number) v).intValue();
        if (total > 0) {
            throw new BizException("该账号已关联 " + total + " 条业务数据（课程 / 选课 / 作业 / 考勤等），"
                    + "为保证数据完整性不能删除；建议改为「停用」");
        }
        users.deleteUser(id);
    }

    /** 导出用户 CSV */
    public byte[] exportUsers(String role, String status, String kw) {
        List<Map<String, Object>> rows = users.searchUsers(role, status, kw, 5000, 0);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            bos.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            bos.write("用户ID,账号,姓名,角色,性别,手机,班级,状态,最近登录,最近登录IP\n"
                    .getBytes(StandardCharsets.UTF_8));
            for (Map<String, Object> r : rows) {
                StringBuilder sb = new StringBuilder();
                sb.append(r.get("user_id")).append(',')
                        .append(AuditService.csv(str(r.get("username")))).append(',')
                        .append(AuditService.csv(str(r.get("real_name")))).append(',')
                        .append(AuditService.csv(roleCn(str(r.get("role"))))).append(',')
                        .append(AuditService.csv(str(r.get("gender")))).append(',')
                        .append(AuditService.csv(str(r.get("phone")))).append(',')
                        .append(AuditService.csv(str(r.get("class_name")))).append(',')
                        .append(AuditService.csv(str(r.get("status")))).append(',')
                        .append(AuditService.csv(fmt(r.get("last_login_at")))).append(',')
                        .append(AuditService.csv(str(r.get("last_login_ip")))).append('\n');
                bos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            log.warn("导出用户失败：{}", e.getMessage());
        }
        return bos.toByteArray();
    }

    public static String roleCn(String role) {
        return switch (role == null ? "" : role) {
            case "admin" -> "管理员";
            case "teacher" -> "教师";
            case "student" -> "学生";
            default -> role;
        };
    }

    /* ============================================================ 系统监控 */

    /**
     * 监控总览：业务规模 + 数据库状态 + 运行指标 + 健康检查结果
     * 任何一项统计失败都降级为空值并标注，不让监控页整体崩掉。
     */
    public Map<String, Object> monitor(Path backupDir) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("biz", filled(safe(sys::bizSummary),
                "user_cnt", "student_cnt", "teacher_cnt", "disabled_cnt",
                "course_cnt", "lesson_cnt", "att_cnt", "inter_cnt", "sub_cnt", "event_cnt"));

        Map<String, Object> db = filled(safe(sys::dbStatus),
                "version", "max_conn", "conn_now", "uptime_sec");
        // performance_schema 的状态值是字符串，需兼容 String / Number 两种类型
        long upSec = toLong(db.get("uptime_sec"));
        db.put("uptime_text", String.format("%d天%02d小时%02d分", upSec / 86400, upSec % 86400 / 3600, upSec % 3600 / 60));
        out.put("db", db);

        out.put("dbSize", filled(safe(sys::dbSize), "size_mb"));
        out.put("tables", safeList(sys::tableSizes));
        out.put("trend", safeList(sys::activeTrend));
        out.put("log", auditSummary());
        out.put("jvm", jvm());
        out.put("disk", disk(backupDir));
        out.put("health", health(out));
        out.put("time", LocalDateTime.now().format(FMT));
        return out;
    }

    private Map<String, Object> auditSummary() {
        try {
            Map<String, Object> s = sys.logSummary();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("total", s.get("total_cnt"));
            m.put("today", s.get("today_cnt"));
            m.put("fail", s.get("fail_cnt"));
            return m;
        } catch (Exception e) {
            return Map.of("total", 0, "today", 0, "fail", 0);
        }
    }

    /** JVM 运行指标：内存、线程、运行时长 */
    private Map<String, Object> jvm() {
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory(), total = rt.totalMemory(), free = rt.freeMemory();
        long used = total - free;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("heap_used_mb", used / 1024 / 1024);
        m.put("heap_max_mb", max / 1024 / 1024);
        m.put("heap_pct", max == 0 ? 0 : Math.round(used * 100.0 / max * 10) / 10.0);
        m.put("processors", rt.availableProcessors());
        m.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
        long up = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
        m.put("uptime_sec", up);
        m.put("uptime_text", String.format("%d天%02d小时%02d分", up / 86400, up % 86400 / 3600, up % 3600 / 60));
        return m;
    }

    /** 备份目录所在磁盘的可用空间 */
    private Map<String, Object> disk(Path dir) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            Path p = dir == null ? Paths.get(".").toAbsolutePath() : dir.toAbsolutePath();
            if (!Files.exists(p)) Files.createDirectories(p);
            FileStore fs = Files.getFileStore(p);
            long total = fs.getTotalSpace(), usable = fs.getUsableSpace();
            m.put("dir", p.toString());
            m.put("total_gb", Math.round(total / 1024.0 / 1024 / 1024 * 10) / 10.0);
            m.put("free_gb", Math.round(usable / 1024.0 / 1024 / 1024 * 10) / 10.0);
            m.put("used_pct", total == 0 ? 0 : Math.round((total - usable) * 100.0 / total * 10) / 10.0);
        } catch (Exception e) {
            m.put("dir", String.valueOf(dir));
            m.put("free_gb", -1);
            m.put("used_pct", -1);
        }
        return m;
    }

    /** 健康检查：把分散指标翻译成"正常 / 警告 / 异常"，便于一眼判断 */
    private List<Map<String, Object>> health(Map<String, Object> data) {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(item("应用服务", "正常", "ok", "Spring Boot 运行中"));

        @SuppressWarnings("unchecked")
        Map<String, Object> db = (Map<String, Object>) data.get("db");
        boolean dbOk = db != null && db.get("version") != null;
        list.add(item("数据库连接", dbOk ? "正常" : "异常", dbOk ? "ok" : "err",
                dbOk ? "MySQL " + db.get("version") + "，当前连接 " + db.getOrDefault("conn_now", "-")
                        + "/" + db.getOrDefault("max_conn", "-") : "无法获取数据库状态"));

        double heapPct = 0;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> jvm = (Map<String, Object>) data.get("jvm");
            heapPct = ((Number) jvm.get("heap_pct")).doubleValue();
        } catch (Exception ignored) { }
        list.add(item("JVM 内存", heapPct >= 85 ? "偏高" : "正常", heapPct >= 85 ? "warn" : "ok",
                "堆内存使用率 " + heapPct + "%"));

        double diskPct = -1, freeGb = -1;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> d = (Map<String, Object>) data.get("disk");
            diskPct = ((Number) d.get("used_pct")).doubleValue();
            freeGb = ((Number) d.get("free_gb")).doubleValue();
        } catch (Exception ignored) { }
        boolean diskWarn = diskPct >= 90 || (freeGb >= 0 && freeGb < 1);
        list.add(item("备份磁盘", diskPct < 0 ? "未知" : (diskWarn ? "不足" : "正常"),
                diskPct < 0 ? "warn" : (diskWarn ? "err" : "ok"),
                diskPct < 0 ? "无法读取磁盘信息" : "已用 " + diskPct + "%，可用 " + freeGb + " GB"));

        int fail = 0;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> lg = (Map<String, Object>) data.get("log");
            fail = ((Number) lg.get("fail")).intValue();
        } catch (Exception ignored) { }
        list.add(item("近期失败操作", fail > 0 ? fail + " 条待核查" : "无", fail > 0 ? "warn" : "ok",
                "审计日志中记录的失败操作数"));
        return list;
    }

    private Map<String, Object> item(String name, String status, String level, String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("status", status);
        m.put("level", level);      // ok / warn / err
        m.put("desc", desc);
        return m;
    }

    private Map<String, Object> safe(Supplier0 s) {
        try {
            Map<String, Object> v = s.get();
            return v == null ? new LinkedHashMap<>() : v;
        } catch (Exception e) {
            log.warn("监控指标获取失败：{}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /**
     * 补齐键位：MyBatis 不会把值为 NULL 的列放进 Map，
     * 而 Thymeleaf 取不存在的键会直接报错，这里统一补成 null。
     */
    private Map<String, Object> filled(Map<String, Object> src, String... keys) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for (String k : keys) m.put(k, src.getOrDefault(k, null));
        return m;
    }

    /** 列表型指标同样做降级保护，避免单项失败导致整页不可用 */
    private List<Map<String, Object>> safeList(java.util.function.Supplier<List<Map<String, Object>>> s) {
        try {
            List<Map<String, Object>> v = s.get();
            return v == null ? List.of() : v;
        } catch (Exception e) {
            log.warn("监控指标获取失败：{}", e.getMessage());
            return List.of();
        }
    }

    private interface Supplier0 {
        Map<String, Object> get();
    }

    /* ============================================================ 校验与工具 */

    /** 密码策略：长度与特殊字符要求取自系统配置，真正参与校验 */
    private void checkPassword(String pwd) {
        if (pwd == null || pwd.isBlank()) throw new BizException("密码不能为空");
        int min = cfg.intOf("security.password_min_len");
        if (pwd.length() < min) throw new BizException("密码长度不能少于 " + min + " 位");
        if (cfg.boolOf("security.password_require_special") && pwd.matches("[A-Za-z0-9]+")) {
            throw new BizException("密码必须包含字母数字以外的特殊字符");
        }
    }

    private Integer classIdOf(Object o, String role) {
        if (o == null || String.valueOf(o).isBlank()) return null;
        int cid = o instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(o).trim());
        if ("student".equals(role) && cid <= 0) throw new BizException("学生账号必须指定班级");
        return cid <= 0 ? null : cid;
    }

    private String blankToNull(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private long toLong(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private String fmt(Object o) {
        if (o == null) return "";
        if (o instanceof LocalDateTime t) return t.format(FMT);
        return String.valueOf(o);
    }
}
