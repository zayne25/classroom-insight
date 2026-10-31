package com.zhiketang.controller;

import com.zhiketang.common.BaseController;
import com.zhiketang.common.R;
import com.zhiketang.common.SessionUser;
import com.zhiketang.service.AdminService;
import com.zhiketang.service.AuditService;
import com.zhiketang.service.BackupService;
import com.zhiketang.service.PermService;
import com.zhiketang.service.SysConfigService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 系统管理员模块
 *
 * 一、页面（服务端渲染，继承 base.html 统一布局）
 *   /admin/monitor  系统监控    /admin/users  用户与角色管理
 *   /admin/perms    权限与访问控制  /admin/config  系统配置
 *   /admin/logs     操作日志与审计  /admin/backup   数据备份与恢复
 *
 * 二、接口（/api/admin/**）
 *   统一约定：先做角色校验（仅 admin），再做权限点校验（role_permission），
 *   执行成功与失败都会写入 operation_logs（见 AuditService.run）。
 *
 * 三、权限边界
 *   1. 管理后台仅 admin 角色可进入，教师 / 学生访问页面重定向到各自首页，调用接口返回 403；
 *   2. admin 角色权限恒为全部且不可修改（避免系统自锁）；
 *   3. 不能停用 / 删除 / 降级自己，也不能移除最后一个启用管理员（AdminService 内强制）。
 */
@Controller
@RequiredArgsConstructor
public class AdminController extends BaseController {

    private final AdminService adminService;
    private final SysConfigService cfg;
    private final AuditService audit;
    private final BackupService backup;
    // permService 由 BaseController 注入（protected）

    /** 操作日志模块名（与界面筛选条件一致） */
    private static final List<String> LOG_MODULES = List.of(
            "用户管理", "权限配置", "系统配置", "操作日志", "数据备份", "认证", "教学业务");

    /* ============================================================
       一、页面路由
       ============================================================ */

    @GetMapping("/admin/monitor")
    public String monitor(Model model) {
        String denied = enter(model, "monitor", "monitor:view");
        if (denied != null) return denied;
        model.addAttribute("overview", adminService.monitor(backup.backupDir()));
        return "admin/monitor";
    }

    @GetMapping("/admin/users")
    public String users(Model model) {
        String denied = enter(model, "users", "user:view");
        if (denied != null) return denied;
        model.addAttribute("classes", adminService.classes());
        return "admin/users";
    }

    @GetMapping("/admin/perms")
    public String perms(Model model) {
        String denied = enter(model, "perms", "perm:view");
        if (denied != null) return denied;
        model.addAttribute("matrix", permService.matrix());
        return "admin/perms";
    }

    @GetMapping("/admin/config")
    public String config(Model model) {
        String denied = enter(model, "config", "config:view");
        if (denied != null) return denied;
        model.addAttribute("cfg", cfg.list());
        return "admin/config";
    }

    @GetMapping("/admin/logs")
    public String logs(Model model) {
        String denied = enter(model, "logs", "log:view");
        if (denied != null) return denied;
        model.addAttribute("modules", LOG_MODULES);
        model.addAttribute("summary", audit.summary());
        return "admin/logs";
    }

    @GetMapping("/admin/backup")
    public String backupPage(Model model) {
        String denied = enter(model, "backup", "backup:view");
        if (denied != null) return denied;
        model.addAttribute("rows", backup.list());
        model.addAttribute("dir", backup.backupDir().toString());
        model.addAttribute("dbName", backup.dbName());
        return "admin/backup";
    }

    /* ============================================================
       二、系统监控
       ============================================================ */

    @GetMapping("/api/admin/monitor/overview")
    @ResponseBody
    public R<Map<String, Object>> overview() {
        require("monitor:view");
        return R.ok(adminService.monitor(backup.backupDir()));
    }

    /* ============================================================
       三、用户与角色管理
       ============================================================ */

    @GetMapping("/api/admin/users")
    @ResponseBody
    public R<Map<String, Object>> listUsers(@RequestParam(defaultValue = "") String role,
                                            @RequestParam(defaultValue = "") String status,
                                            @RequestParam(defaultValue = "") String kw,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "10") int size) {
        require("user:view");
        return R.ok(adminService.listUsers(role, status, kw, page, size));
    }

    @GetMapping("/api/admin/users/{id}")
    @ResponseBody
    public R<Map<String, Object>> userDetail(@PathVariable int id) {
        require("user:view");
        return R.ok(adminService.userDetail(id));
    }

    @PostMapping("/api/admin/users")
    @ResponseBody
    public R<?> createUser(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        require("user:create");
        String username = String.valueOf(body.getOrDefault("username", "")).trim();
        String role = String.valueOf(body.getOrDefault("role", ""));
        return audit.run(req, "用户管理", "新增用户", "账号 " + username,
                () -> {
                    adminService.createUser(body);
                    return Map.of("username", username);
                },
                d -> "新增账号 " + username + "，角色 " + AdminService.roleCn(role));
    }

    @PutMapping("/api/admin/users/{id}")
    @ResponseBody
    public R<?> updateUser(@PathVariable int id, @RequestBody Map<String, Object> body,
                           HttpServletRequest req) {
        require("user:update");
        SessionUser u = cur();
        return audit.run(req, "用户管理", "编辑用户", "用户 #" + id,
                () -> {
                    adminService.updateUser(id, body, u.getUserId());
                    return null;
                },
                d -> "修改字段：" + changed(body));
    }

    @PutMapping("/api/admin/users/{id}/role")
    @ResponseBody
    public R<?> assignRole(@PathVariable int id, @RequestBody Map<String, Object> body,
                           HttpServletRequest req) {
        require("user:assign_role");
        SessionUser u = cur();
        String role = String.valueOf(body.getOrDefault("role", "")).trim();
        return audit.run(req, "用户管理", "分配角色", "用户 #" + id,
                () -> {
                    adminService.assignRole(id, role, u.getUserId());
                    return null;
                },
                d -> "角色变更为 " + AdminService.roleCn(role));
    }

    @PutMapping("/api/admin/users/{id}/status")
    @ResponseBody
    public R<?> setStatus(@PathVariable int id, @RequestBody Map<String, Object> body,
                          HttpServletRequest req) {
        require("user:update");
        SessionUser u = cur();
        String status = String.valueOf(body.getOrDefault("status", "")).trim();
        return audit.run(req, "用户管理", "停用".equals(status) ? "停用账号" : "启用账号", "用户 #" + id,
                () -> {
                    adminService.setStatus(id, status, u.getUserId());
                    return null;
                },
                d -> "状态变更为 " + status);
    }

    @PutMapping("/api/admin/users/{id}/password")
    @ResponseBody
    public R<?> resetPassword(@PathVariable int id, @RequestBody Map<String, Object> body,
                              HttpServletRequest req) {
        require("user:reset_pwd");
        return audit.run(req, "用户管理", "重置密码", "用户 #" + id,
                () -> {
                    adminService.resetPassword(id, String.valueOf(body.getOrDefault("password", "")));
                    return null;
                },
                d -> "重置密码成功（明文不记录）");
    }

    @PostMapping("/api/admin/users/{id}/unlock")
    @ResponseBody
    public R<?> unlock(@PathVariable int id, HttpServletRequest req) {
        require("user:update");
        return audit.run(req, "用户管理", "解除锁定", "用户 #" + id,
                () -> {
                    adminService.unlock(id);
                    return null;
                },
                d -> "清除登录失败计数与锁定截止时间");
    }

    @DeleteMapping("/api/admin/users/{id}")
    @ResponseBody
    public R<?> deleteUser(@PathVariable int id, HttpServletRequest req) {
        require("user:delete");
        SessionUser u = cur();
        return audit.run(req, "用户管理", "删除用户", "用户 #" + id,
                () -> {
                    adminService.deleteUser(id, u.getUserId());
                    return null;
                },
                d -> "删除账号（已通过业务数据引用检查）");
    }

    @GetMapping("/api/admin/users/export")
    public ResponseEntity<byte[]> exportUsers(@RequestParam(defaultValue = "") String role,
                                              @RequestParam(defaultValue = "") String status,
                                              @RequestParam(defaultValue = "") String kw,
                                              HttpServletRequest req) {
        require("user:view");
        audit.run(req, "用户管理", "导出用户", "当前筛选结果", () -> null, null);
        byte[] data = adminService.exportUsers(role, status, kw);
        return csv(data, "用户列表.csv");
    }

    /* ============================================================
       四、权限与访问控制
       ============================================================ */

    @GetMapping("/api/admin/perms/matrix")
    @ResponseBody
    public R<Map<String, Object>> matrix() {
        require("perm:view");
        return R.ok(permService.matrix());
    }

    @PutMapping("/api/admin/perms")
    @ResponseBody
    public R<?> setPerm(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        require("perm:update");
        String role = String.valueOf(body.getOrDefault("role", "")).trim();
        String code = String.valueOf(body.getOrDefault("perm_code", "")).trim();
        boolean enabled = Boolean.parseBoolean(String.valueOf(body.getOrDefault("enabled", false)));
        return audit.run(req, "权限配置", "修改权限分配", "角色 " + role + " · " + code,
                () -> permService.setPerm(role, code, enabled, cur().getUserId()),
                d -> d);
    }

    /* ============================================================
       五、系统配置与参数管理
       ============================================================ */

    @GetMapping("/api/admin/config")
    @ResponseBody
    public R<Map<String, Object>> configList() {
        require("config:view");
        return R.ok(cfg.list());
    }

    @PutMapping("/api/admin/config")
    @ResponseBody
    public R<?> updateConfig(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        require("config:update");
        String key = String.valueOf(body.getOrDefault("config_key", "")).trim();
        String value = body.get("config_value") == null ? "" : String.valueOf(body.get("config_value"));
        return audit.run(req, "系统配置", "修改配置", "配置 " + key,
                () -> cfg.update(key, value, cur().getUserId()),
                d -> d);
    }

    @PostMapping("/api/admin/config/{key}/reset")
    @ResponseBody
    public R<?> resetConfig(@PathVariable String key, HttpServletRequest req) {
        require("config:update");
        return audit.run(req, "系统配置", "恢复默认配置", "配置 " + key,
                () -> cfg.reset(key, cur().getUserId()),
                d -> d);
    }

    /* ============================================================
       六、操作日志与审计追踪
       ============================================================ */

    @GetMapping("/api/admin/logs")
    @ResponseBody
    public R<Map<String, Object>> searchLogs(@RequestParam(defaultValue = "") String module,
                                             @RequestParam(defaultValue = "") String result,
                                             @RequestParam(defaultValue = "") String kw,
                                             @RequestParam(required = false) Integer operatorId,
                                             @RequestParam(defaultValue = "") String from,
                                             @RequestParam(defaultValue = "") String to,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        require("log:view");
        return R.ok(audit.search(module, result, kw, operatorId, from, to, page, size));
    }

    @GetMapping("/api/admin/logs/summary")
    @ResponseBody
    public R<Map<String, Object>> logSummary() {
        require("log:view");
        return R.ok(audit.summary());
    }

    @GetMapping("/api/admin/logs/export")
    public ResponseEntity<byte[]> exportLogs(@RequestParam(defaultValue = "") String module,
                                             @RequestParam(defaultValue = "") String result,
                                             @RequestParam(defaultValue = "") String kw,
                                             @RequestParam(defaultValue = "") String from,
                                             @RequestParam(defaultValue = "") String to,
                                             HttpServletRequest req) {
        require("log:export");
        audit.run(req, "操作日志", "导出日志", "当前筛选结果", () -> null, null);
        byte[] data = audit.exportCsv(module, result, kw, from, to);
        return csv(data, "操作日志.csv");
    }

    @DeleteMapping("/api/admin/logs/purge")
    @ResponseBody
    public R<?> purgeLogs(@RequestParam(defaultValue = "180") int days, HttpServletRequest req) {
        require("log:purge");
        SessionUser u = cur();
        int n = audit.purge(days, req, u);
        return R.ok(Map.of("deleted", n), "已清理 " + n + " 条日志（保留最近 " + days + " 天）");
    }

    /* ============================================================
       七、数据备份与恢复
       ============================================================ */

    @GetMapping("/api/admin/backup/list")
    @ResponseBody
    public R<List<Map<String, Object>>> backupList() {
        require("backup:view");
        return R.ok(backup.list());
    }

    @PostMapping("/api/admin/backup/create")
    @ResponseBody
    public R<?> createBackup(HttpServletRequest req) {
        require("backup:create");
        int uid = cur().getUserId();
        return audit.run(req, "数据备份", "创建备份", "数据库 " + backup.dbName(),
                () -> backup.create("手动", uid),
                d -> "文件 " + d.get("file_name") + "（" + d.get("size_text") + "，引擎 " + d.get("engine") + "）");
    }

    @PostMapping("/api/admin/backup/{id}/restore")
    @ResponseBody
    public R<?> restore(@PathVariable int id, @RequestBody(required = false) Map<String, Object> body,
                        HttpServletRequest req) {
        require("backup:restore");
        String confirm = body == null ? null : String.valueOf(body.getOrDefault("confirm", ""));
        int uid = cur().getUserId();
        return audit.run(req, "数据备份", "恢复数据", "备份 #" + id,
                () -> backup.restore(id, confirm, uid),
                d -> "由 " + d.get("file_name") + " 恢复，引擎 " + d.get("engine")
                        + "，恢复前自动备份：" + (d.get("pre_backup") == null ? "无" : d.get("pre_backup")));
    }

    @GetMapping("/api/admin/backup/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable int id, HttpServletRequest req) {
        require("backup:view");
        audit.run(req, "数据备份", "下载备份", "备份 #" + id, () -> null, null);
        byte[] data = backup.read(id);
        String filename = URLEncoder.encode(backup.fileName(id), StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/octet-stream; charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .body(data);
    }

    @DeleteMapping("/api/admin/backup/{id}")
    @ResponseBody
    public R<?> deleteBackup(@PathVariable int id, HttpServletRequest req) {
        require("backup:delete");
        String name = backup.fileName(id);
        return audit.run(req, "数据备份", "删除备份", "备份 " + name,
                () -> {
                    backup.delete(id);
                    return null;
                },
                d -> "删除备份文件 " + name);
    }

    /* ============================================================
       八、内部工具
       ============================================================ */

    /** 页面进入：角色校验 + 权限点校验 + 公共模型变量 */
    private String enter(Model model, String active, String perm) {
        String denied = requirePageRole("admin");
        if (denied != null) return denied;
        permService.require(cur().getRole(), perm);
        model.addAttribute("active", active);
        return null;
    }

    /** 接口进入：角色校验 + 权限点校验 */
    private void require(String perm) {
        requireRole("admin");
        permService.require(cur().getRole(), perm);
    }

    /** 把请求体中的变更字段拼成审计详情 */
    private String changed(Map<String, Object> body) {
        StringBuilder sb = new StringBuilder();
        for (String k : new String[]{"real_name", "role", "gender", "phone", "class_id"}) {
            if (body.containsKey(k)) {
                if (sb.length() > 0) sb.append("、");
                sb.append(k).append('=').append(body.get(k));
            }
        }
        return sb.length() == 0 ? "无字段变更" : sb.toString();
    }

    private ResponseEntity<byte[]> csv(byte[] data, String name) {
        String filename = URLEncoder.encode(name, StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .body(data);
    }
}
