package com.zhiketang.service;

import com.zhiketang.common.BizException;
import com.zhiketang.mapper.SysMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 权限服务：角色 × 权限点 的读取、校验与分配
 *
 * 设计要点：
 * 1. 权限点（sys_permission）是**内置**的，不提供新增/删除接口——权限集合由代码定义，
 *    管理员只能在"角色 × 权限"矩阵上勾选，避免权限标识被随意篡改导致校验失效。
 * 2. 角色固定为 admin / teacher / student 三种（users.role 的 CHECK 约束已限定），
 *    角色的"权限"存于 role_permission(role, perm_code, enabled)。
 * 3. **admin 角色权限恒为全部且不可修改**（hasPerm 直接返回 true，setPerm 拒绝写入）。
 *    这是一条安全边界：否则管理员可能把自己的权限取消，导致系统无人可管（自锁）。
 * 4. 权限读取走内存缓存，角色权限变更后调用 refresh() 失效重加载。
 * 5. 缓存缺失时回退到「角色默认权限」，保证配置表异常时系统仍可用（Fail-Safe）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermService {

    private final SysMapper sys;

    /** key = role，value = 该角色已启用的权限码集合 */
    private final Map<String, Set<String>> cache = new ConcurrentHashMap<>();

    /** 参与权限矩阵的角色及其显示名 */
    public static final List<String[]> ROLES = Arrays.asList(
            new String[]{"admin", "系统管理员"},
            new String[]{"teacher", "教师"},
            new String[]{"student", "学生"}
    );

    /* ============================================================ 读取 */

    public void refresh() {
        cache.clear();
        try {
            for (Map<String, Object> r : sys.listAllRolePerm()) {
                if (intv(r.get("enabled")) != 1) continue;
                cache.computeIfAbsent(String.valueOf(r.get("role")), k -> new HashSet<>())
                        .add(String.valueOf(r.get("perm_code")));
            }
        } catch (Exception e) {
            log.warn("加载角色权限失败，将使用内置默认权限：{}", e.getMessage());
        }
    }

    /** 判断角色是否拥有某权限 */
    public boolean hasPerm(String role, String code) {
        if (role == null || code == null) return false;
        // 安全边界：管理员恒拥有全部权限，不受配置表影响
        if ("admin".equals(role)) return true;
        Set<String> set = cache.get(role);
        if (set == null || set.isEmpty()) {
            return SysConfigService.ROLE_DEFAULT_PERMS
                    .getOrDefault(role, new ArrayList<>()).contains(code);
        }
        return set.contains(code);
    }

    /** 校验失败抛 403 */
    public void require(String role, String code) {
        if (!hasPerm(role, code)) {
            throw new BizException("当前角色无「" + permName(code) + "」权限", 403);
        }
    }

    public String permName(String code) {
        for (String[] p : SysConfigService.PERM_DEFS) {
            if (p[0].equals(code)) return p[1];
        }
        return code;
    }

    /**
     * 权限矩阵：按权限分组返回，每项含各角色的开关状态
     *
     * @return {roles:[{code,name}], groups:{组名:[{perm_code,perm_name,admin,teacher,student}]}}
     */
    public Map<String, Object> matrix() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> roles = new ArrayList<>();
        for (String[] r : ROLES) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", r[0]);
            m.put("name", r[1]);
            m.put("locked", "admin".equals(r[0]));   // 管理员列锁定，界面上不可勾选
            roles.add(m);
        }

        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (String[] p : SysConfigService.PERM_DEFS) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("perm_code", p[0]);
            row.put("perm_name", p[1]);
            for (String[] r : ROLES) {
                row.put(r[0], hasPerm(r[0], p[0]));
            }
            groups.computeIfAbsent(p[2], k -> new ArrayList<>()).add(row);
        }
        out.put("roles", roles);
        out.put("groups", groups);
        out.put("total", SysConfigService.PERM_DEFS.size());
        return out;
    }

    /* ============================================================ 修改 */

    /**
     * 设置某角色的某权限开关
     *
     * @return 变更详情（用于审计日志）
     */
    public String setPerm(String role, String code, boolean enabled, int operatorId) {
        if (!ROLES.stream().anyMatch(r -> r[0].equals(role))) {
            throw new BizException("角色不存在：" + role);
        }
        if ("admin".equals(role)) {
            throw new BizException("系统管理员默认拥有全部权限，不可修改（避免系统无人可管）");
        }
        boolean known = SysConfigService.PERM_DEFS.stream().anyMatch(p -> p[0].equals(code));
        if (!known) throw new BizException("权限点不存在：" + code);

        // 保护性校验：不允许停掉「系统监控」以外会让自己无法排障的权限？此处只做最小约束：
        // 学生角色不允许开启管理类权限，避免出现"学生可管理用户"这类越权配置。
        if ("student".equals(role) && enabled && (code.startsWith("user:") || code.startsWith("perm:")
                || code.startsWith("config:") || code.startsWith("backup:") || code.startsWith("log:"))) {
            throw new BizException("学生角色不可开启系统管理类权限");
        }

        boolean before = hasPerm(role, code);
        sys.upsertRolePerm(role, code, enabled ? 1 : 0);
        refresh();
        return permName(code) + "：" + (before ? "已启用" : "未启用") + " → " + (enabled ? "已启用" : "未启用");
    }

    private int intv(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }
}
