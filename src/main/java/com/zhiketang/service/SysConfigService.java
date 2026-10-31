package com.zhiketang.service;

import com.zhiketang.common.BizException;
import com.zhiketang.mapper.SysMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 系统配置服务
 *
 * 设计要点：
 * 1. 配置项**真正参与业务计算**，不是"存着好看"——
 *    预警阈值会影响风险分级、安全策略会校验密码与登录锁定、
 *    AI 阈值决定哪些批改结果强制复核、系统名称会实时改顶栏。
 * 2. 值统一以字符串存储，按 config_type 解析；解析失败回退默认值，不让配置错误拖垮系统。
 * 3. 读取走内存缓存（ConcurrentHashMap），避免每条业务查询都打库。
 * 4. editable = 0 的项（如会话超时）需要重启 JVM 才生效，界面上明确标注，避免误解。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysConfigService {

    private final SysMapper sys;

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /** 兜底默认值：库里没有该键时使用，保证系统永远能跑起来 */
    private static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry("app.name", "智课堂"),
            Map.entry("app.semester", "2026-2027学年第一学期"),
            Map.entry("security.password_min_len", "6"),
            Map.entry("security.password_require_special", "false"),
            Map.entry("security.max_login_fail", "5"),
            Map.entry("security.lock_minutes", "10"),
            Map.entry("security.session_timeout_min", "30"),
            Map.entry("risk.red_score", "60"),
            Map.entry("risk.yellow_score", "75"),
            Map.entry("risk.red_attendance", "0.70"),
            Map.entry("risk.yellow_attendance", "0.85"),
            Map.entry("ai.confidence_threshold", "0.75"),
            Map.entry("ai.engine_provider", "local"),
            Map.entry("log.retention_days", "180"),
            Map.entry("backup.dir", "backups"),
            Map.entry("backup.mysqldump_path", "mysqldump"),
            Map.entry("backup.mysql_path", "mysql")
    );

    /** 内置配置的完整定义（用于初始化与重置） */
    private static final List<String[]> DEFS = Arrays.asList(
            new String[]{"app.name", "智课堂", "string", "基础", "系统名称", "显示在顶栏与登录页，保存后立即生效", "1"},
            new String[]{"app.semester", "2026-2027学年第一学期", "string", "基础", "当前学期", "用于展示与归档标识", "1"},
            new String[]{"security.password_min_len", "6", "int", "安全", "密码最小长度", "取值范围 4~32，新建/重置密码时校验", "1"},
            new String[]{"security.password_require_special", "false", "bool", "安全", "密码须含特殊字符", "开启后新建/重置密码必须包含非字母数字字符", "1"},
            new String[]{"security.max_login_fail", "5", "int", "安全", "登录失败锁定阈值", "连续失败达到该次数后锁定账号，0 表示不锁定", "1"},
            new String[]{"security.lock_minutes", "10", "int", "安全", "锁定时长（分钟）", "取值范围 1~1440", "1"},
            new String[]{"security.session_timeout_min", "30", "int", "安全", "会话超时（分钟）", "由容器控制，修改后需重启服务才生效", "0"},
            new String[]{"risk.red_score", "60", "int", "预警", "红色预警分数线", "综合分低于该值判为红色预警，保存后立即生效", "1"},
            new String[]{"risk.yellow_score", "75", "int", "预警", "黄色预警分数线", "综合分低于该值判为黄色预警（须大于红色线）", "1"},
            new String[]{"risk.red_attendance", "0.70", "decimal", "预警", "红色预警出勤率", "出勤率低于该比例判为红色预警（0~1）", "1"},
            new String[]{"risk.yellow_attendance", "0.85", "decimal", "预警", "黄色预警出勤率", "出勤率低于该比例判为黄色预警（0~1）", "1"},
            new String[]{"ai.confidence_threshold", "0.75", "decimal", "批改", "AI 复核置信度阈值", "AI 初评置信度低于该值时强制标记为需人工复核", "1"},
            new String[]{"ai.engine_provider", "local", "string", "批改", "AI 批改引擎", "local=本地启发式引擎；http=大模型（需配置接口）", "1"},
            new String[]{"log.retention_days", "180", "int", "基础", "操作日志保留天数", "执行清理时删除早于该天数的日志，取值范围 7~1095", "1"},
            new String[]{"backup.dir", "backups", "string", "备份", "备份文件目录", "相对路径基于应用工作目录，目录不可写时备份会直接失败", "1"},
            new String[]{"backup.mysqldump_path", "mysqldump", "string", "备份", "mysqldump 路径", "留空则始终使用内置逻辑转储；命令不可用时自动降级", "1"},
            new String[]{"backup.mysql_path", "mysql", "string", "备份", "mysql 客户端路径", "恢复时优先使用；命令不可用时自动降级为 JDBC 执行", "1"}
    );

    /** 内置权限点（code, name, group, sort） */
    public static final List<String[]> PERM_DEFS = Arrays.asList(
            new String[]{"monitor:view", "查看系统监控", "系统监控", "10"},
            new String[]{"user:view", "查看用户", "用户管理", "20"},
            new String[]{"user:create", "新增用户", "用户管理", "21"},
            new String[]{"user:update", "编辑用户", "用户管理", "22"},
            new String[]{"user:delete", "删除用户", "用户管理", "23"},
            new String[]{"user:reset_pwd", "重置密码", "用户管理", "24"},
            new String[]{"user:assign_role", "分配角色", "用户管理", "25"},
            new String[]{"perm:view", "查看权限配置", "权限配置", "30"},
            new String[]{"perm:update", "修改权限分配", "权限配置", "31"},
            new String[]{"config:view", "查看系统配置", "系统配置", "40"},
            new String[]{"config:update", "修改系统配置", "系统配置", "41"},
            new String[]{"log:view", "查看操作日志", "操作日志", "50"},
            new String[]{"log:export", "导出操作日志", "操作日志", "51"},
            new String[]{"log:purge", "清理操作日志", "操作日志", "52"},
            new String[]{"backup:view", "查看备份", "数据备份", "60"},
            new String[]{"backup:create", "创建备份", "数据备份", "61"},
            new String[]{"backup:restore", "恢复数据", "数据备份", "62"},
            new String[]{"backup:delete", "删除备份", "数据备份", "63"},
            new String[]{"course:manage", "课程与班级管理", "教学业务", "70"},
            new String[]{"lesson:manage", "课堂管理", "教学业务", "71"},
            new String[]{"interaction:manage", "课堂互动", "教学业务", "72"},
            new String[]{"assignment:manage", "作业管理", "教学业务", "73"},
            new String[]{"ai:grade", "AI 批改作业", "教学业务", "74"},
            new String[]{"analytics:view", "学情分析", "教学业务", "75"}
    );

    /** 角色默认权限：admin 全部；teacher 教学业务；student 仅查看本人 */
    public static final Map<String, List<String>> ROLE_DEFAULT_PERMS = Map.of(
            "admin", PERM_DEFS.stream().map(a -> a[0]).toList(),
            "teacher", Arrays.asList("course:manage", "lesson:manage", "interaction:manage",
                    "assignment:manage", "ai:grade", "analytics:view", "monitor:view"),
            "student", new ArrayList<>()
    );

    /* ============================================================ 初始化 */

    /** 写入内置配置与权限（幂等：已存在则跳过，不覆盖管理员的修改） */
    public void ensureDefaults() {
        for (String[] d : DEFS) {
            Map<String, Object> r = new HashMap<>();
            r.put("config_key", d[0]);
            r.put("config_value", d[1]);
            r.put("config_type", d[2]);
            r.put("config_group", d[3]);
            r.put("name", d[4]);
            r.put("description", d[5]);
            r.put("editable", Integer.parseInt(d[6]));
            r.put("updated_by", null);
            sys.upsertConfigKeepValue(r);
        }
        for (String[] p : PERM_DEFS) {
            Map<String, Object> r = new HashMap<>();
            r.put("perm_code", p[0]);
            r.put("perm_name", p[1]);
            r.put("perm_group", p[2]);
            r.put("sort_no", Integer.parseInt(p[3]));
            sys.upsertPermission(r);
        }
        for (Map.Entry<String, List<String>> e : ROLE_DEFAULT_PERMS.entrySet()) {
            for (String code : PERM_DEFS.stream().map(a -> a[0]).toList()) {
                sys.upsertRolePerm(e.getKey(), code, e.getValue().contains(code) ? 1 : 0);
            }
        }
        refresh();
    }

    /** 重新加载缓存（配置变更后、数据初始化后调用） */
    public void refresh() {
        cache.clear();
        try {
            for (Map<String, Object> r : sys.listConfig()) {
                cache.put(String.valueOf(r.get("config_key")),
                        r.get("config_value") == null ? "" : String.valueOf(r.get("config_value")));
            }
        } catch (Exception e) {
            log.warn("加载系统配置失败，将使用内置默认值：{}", e.getMessage());
        }
    }

    /* ============================================================ 读取 */

    public String str(String key) {
        String v = cache.get(key);
        return v != null ? v : DEFAULTS.getOrDefault(key, "");
    }

    public int intOf(String key) {
        try {
            return Integer.parseInt(str(key).trim());
        } catch (Exception e) {
            return Integer.parseInt(DEFAULTS.getOrDefault(key, "0"));
        }
    }

    public double decOf(String key) {
        try {
            return Double.parseDouble(str(key).trim());
        } catch (Exception e) {
            return Double.parseDouble(DEFAULTS.getOrDefault(key, "0"));
        }
    }

    public boolean boolOf(String key) {
        String v = str(key);
        return "true".equalsIgnoreCase(v) || "1".equals(v) || "yes".equalsIgnoreCase(v);
    }

    public Map<String, Object> list() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> all = sys.listConfig();
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> c : all) {
            // 值为 NULL 的列不会出现在 Map 结果里，这里补回键，避免模板取值时报属性不存在
            c.putIfAbsent("updated_by", null);
            c.putIfAbsent("updated_at", null);
            grouped.computeIfAbsent(String.valueOf(c.get("config_group")), k -> new ArrayList<>()).add(c);
        }
        out.put("groups", grouped);
        out.put("total", all.size());
        return out;
    }

    /* ============================================================ 修改 */

    /**
     * 修改配置。校验：只读项拒绝、类型校验、取值范围校验。
     *
     * @return 变更详情（改前 → 改后），供审计日志使用
     */
    public String update(String key, String value, int operatorId) {
        Map<String, Object> c = sys.findConfig(key);
        if (c == null) throw new BizException("配置项不存在：" + key);
        if (intv(c.get("editable")) == 0) {
            throw new BizException("该配置项为只读，需修改配置文件并重启服务");
        }
        String type = String.valueOf(c.get("config_type"));
        String v = value == null ? "" : value.trim();

        // 类型与范围校验
        if ("int".equals(type)) {
            int i;
            try {
                i = Integer.parseInt(v);
            } catch (NumberFormatException e) {
                throw new BizException("配置值必须为整数");
            }
            checkRange(key, i);
        } else if ("decimal".equals(type)) {
            double d;
            try {
                d = Double.parseDouble(v);
            } catch (NumberFormatException e) {
                throw new BizException("配置值必须为数字");
            }
            checkRange(key, d);
        } else if ("bool".equals(type) && !v.equals("true") && !v.equals("false")) {
            throw new BizException("配置值必须为 true 或 false");
        } else if ("string".equals(type) && v.isBlank()) {
            throw new BizException("配置值不能为空");
        }

        // 业务规则校验：黄色线必须高于红色线
        if ("risk.yellow_score".equals(key) && Integer.parseInt(v) <= intOf("risk.red_score")) {
            throw new BizException("黄色预警分数线必须大于红色预警分数线（当前 " + intOf("risk.red_score") + "）");
        }
        if ("risk.red_score".equals(key) && Integer.parseInt(v) >= intOf("risk.yellow_score")) {
            throw new BizException("红色预警分数线必须小于黄色预警分数线（当前 " + intOf("risk.yellow_score") + "）");
        }

        String old = str(key);
        sys.updateConfig(key, v, operatorId);
        refresh();
        return old + " → " + v;
    }

    /** 恢复单项配置为内置默认值，返回变更详情供审计 */
    public String reset(String key, int operatorId) {
        Map<String, Object> c = sys.findConfig(key);
        if (c == null) throw new BizException("配置项不存在：" + key);
        if (intv(c.get("editable")) == 0) {
            throw new BizException("该配置项为只读，不可重置");
        }
        String def = DEFAULTS.get(key);
        if (def == null) {
            for (String[] d : DEFS) {
                if (d[0].equals(key)) def = d[1];
            }
        }
        if (def == null) throw new BizException("该配置项没有内置默认值，请手工修改");
        String old = str(key);
        sys.updateConfig(key, def, operatorId);
        refresh();
        return old + " → " + def + "（恢复默认值）";
    }

    private void checkRange(String key, double v) {
        switch (key) {
            case "security.password_min_len" -> {
                if (v < 4 || v > 32) throw new BizException("密码最小长度需在 4~32 之间");
            }
            case "security.max_login_fail" -> {
                if (v < 0 || v > 20) throw new BizException("登录失败锁定阈值需在 0~20 之间（0 表示不锁定）");
            }
            case "security.lock_minutes" -> {
                if (v < 1 || v > 1440) throw new BizException("锁定时长需在 1~1440 分钟之间");
            }
            case "risk.red_score", "risk.yellow_score" -> {
                if (v < 0 || v > 100) throw new BizException("预警分数线需在 0~100 之间");
            }
            case "risk.red_attendance", "risk.yellow_attendance" -> {
                if (v < 0 || v > 1) throw new BizException("出勤率阈值需在 0~1 之间");
            }
            case "ai.confidence_threshold" -> {
                if (v < 0 || v > 1) throw new BizException("置信度阈值需在 0~1 之间");
            }
            case "log.retention_days" -> {
                if (v < 7 || v > 1095) throw new BizException("日志保留天数需在 7~1095 之间");
            }
            default -> { }
        }
    }

    private int intv(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }

    /** 供页面展示的阈值快照 */
    public Map<String, Object> riskThresholds() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("red_score", intOf("risk.red_score"));
        m.put("yellow_score", intOf("risk.yellow_score"));
        m.put("red_attendance", BigDecimal.valueOf(decOf("risk.red_attendance")));
        m.put("yellow_attendance", BigDecimal.valueOf(decOf("risk.yellow_attendance")));
        return m;
    }
}
