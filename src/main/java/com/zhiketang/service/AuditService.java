package com.zhiketang.service;

import com.zhiketang.common.BizException;
import com.zhiketang.common.R;
import com.zhiketang.common.SessionUser;
import com.zhiketang.mapper.SysMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 审计服务：操作日志的写入、查询、导出与清理
 *
 * 设计要点：
 * 1. **成功与失败都记录**（result = 成功 / 失败），失败时保存 fail_reason，
 *    这是"审计追踪"与"普通日志"的本质区别：要能回答"谁、在什么时间、对什么对象、
 *    做了什么、结果如何、为什么失败"。
 * 2. operator（账号）做冗余存储，用户被删除后仍可追溯操作人。
 * 3. 记录 IP / UA / 耗时，支撑安全审计与性能观测。
 * 4. 写日志失败**绝不影响主业务**（异常吞掉并打印 warn），避免审计拖垮业务。
 * 5. run(...) 是统一入口：自动计时、自动区分成功失败、异常继续向外抛出由全局异常处理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final SysMapper sys;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /* ============================================================ 写入 */

    public void write(HttpServletRequest req, SessionUser u, String module, String operation,
                      String target, String detail, boolean ok, String failReason, long costMs) {
        writeRaw(req, u == null ? null : u.getUserId(), u == null ? "系统" : u.getUsername(),
                module, operation, target, detail, ok, failReason, costMs);
    }

    /** 指定操作人写入（登录失败等场景下会话尚未建立，只能按输入账号记录） */
    public void writeRaw(HttpServletRequest req, Integer operatorId, String operator, String module,
                         String operation, String target, String detail, boolean ok,
                         String failReason, long costMs) {
        try {
            Map<String, Object> r = new HashMap<>();
            r.put("operator_id", operatorId);
            r.put("operator", cut(operator, 32));
            r.put("module", cut(module, 32));
            r.put("operation", cut(operation, 64));
            r.put("target", cut(target, 128));
            r.put("detail", cut(detail, 512));
            r.put("result", ok ? "成功" : "失败");
            r.put("fail_reason", ok ? null : cut(failReason, 256));
            r.put("ip", req == null ? null : ip(req));
            r.put("user_agent", req == null ? null : cut(ua(req), 256));
            r.put("cost_ms", (int) Math.min(costMs, Integer.MAX_VALUE));
            sys.insertLog(r);
        } catch (Exception e) {
            log.warn("写入操作日志失败：{}", e.getMessage());
        }
    }

    /**
     * 统一的"执行 + 审计"入口
     *
     * @param detailFn 由返回值生成变更详情（如 "60 → 65"），可为 null
     */
    public <T> R<T> run(HttpServletRequest req, String module, String operation, String target,
                        Supplier<T> fn, Function<T, String> detailFn) {
        SessionUser u = user(req);
        long t0 = System.currentTimeMillis();
        try {
            T data = fn.get();
            String detail = detailFn == null ? null : detailFn.apply(data);
            write(req, u, module, operation, target, detail, true, null, System.currentTimeMillis() - t0);
            return R.ok(data);
        } catch (BizException e) {
            write(req, u, module, operation, target, null, false, e.getMessage(),
                    System.currentTimeMillis() - t0);
            throw e;
        } catch (Exception e) {
            write(req, u, module, operation, target, null, false, e.getMessage(),
                    System.currentTimeMillis() - t0);
            throw e;
        }
    }

    private SessionUser user(HttpServletRequest req) {
        if (req == null) return null;
        Object o = req.getSession(false) == null ? null : req.getSession(false).getAttribute("user");
        return o == null ? null : (SessionUser) o;
    }

    /* ============================================================ 查询 */

    public Map<String, Object> search(String module, String result, String kw, Integer operatorId,
                                      String from, String to, int page, int size) {
        to = normTo(to);
        page = Math.max(page, 1);
        size = Math.min(Math.max(size, 10), 200);
        int offset = (page - 1) * size;

        Map<String, Object> out = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> rows = sys.searchLogs(module, result, operatorId, kw, from, to, size, offset);
            int total = sys.countLogs(module, result, operatorId, kw, from, to);
            out.put("rows", rows);
            out.put("total", total);
            out.put("page", page);
            out.put("size", size);
            out.put("pages", (int) Math.ceil(total * 1.0 / size));
        } catch (Exception e) {
            log.warn("查询操作日志失败：{}", e.getMessage());
            out.put("rows", List.of());
            out.put("total", 0);
            out.put("page", page);
            out.put("size", size);
            out.put("pages", 0);
        }
        return out;
    }

    /** 审计概览：总数 / 今日 / 失败数 + 模块分布 */
    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> s = sys.logSummary();
        out.put("total", intv(s == null ? null : s.get("total_cnt")));
        out.put("today", intv(s == null ? null : s.get("today_cnt")));
        out.put("fail", intv(s == null ? null : s.get("fail_cnt")));
        out.put("modules", sys.logModuleDist());
        return out;
    }

    /** 按保留天数清理（清理动作本身也会留下一条审计记录） */
    public int purge(int days, HttpServletRequest req, SessionUser u) {
        if (days < 7 || days > 1095) throw new BizException("保留天数需在 7~1095 之间");
        long t0 = System.currentTimeMillis();
        int n = sys.purgeLogs(days);
        write(req, u, "操作日志", "清理日志", "保留 " + days + " 天", "删除 " + n + " 条", true, null,
                System.currentTimeMillis() - t0);
        return n;
    }

    /** 导出 CSV（带 UTF-8 BOM，Excel 打开不乱码） */
    public byte[] exportCsv(String module, String result, String kw, String from, String to) {
        to = normTo(to);
        List<Map<String, Object>> rows = sys.searchLogs(module, result, null, kw, from, to, 5000, 0);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            bos.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            bos.write("日志ID,时间,操作人,模块,操作,对象,结果,失败原因,IP,耗时(ms)\n".getBytes(StandardCharsets.UTF_8));
            for (Map<String, Object> r : rows) {
                StringBuilder sb = new StringBuilder();
                sb.append(r.get("log_id")).append(',')
                        .append(csv(fmtTime(r.get("created_at")))).append(',')
                        .append(csv(str(r.get("operator")))).append(',')
                        .append(csv(str(r.get("module")))).append(',')
                        .append(csv(str(r.get("operation")))).append(',')
                        .append(csv(str(r.get("target")))).append(',')
                        .append(csv(str(r.get("result")))).append(',')
                        .append(csv(str(r.get("fail_reason")))).append(',')
                        .append(csv(str(r.get("ip")))).append(',')
                        .append(r.get("cost_ms") == null ? "" : r.get("cost_ms")).append('\n');
                bos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            log.warn("导出操作日志失败：{}", e.getMessage());
        }
        return bos.toByteArray();
    }

    /* ============================================================ 工具 */

    /** 结束日期补到当天 23:59:59，避免"查不到截止日当天"的偏差 */
    private String normTo(String to) {
        if (to == null || to.isBlank()) return null;
        String t = to.trim();
        return t.length() == 10 ? t + " 23:59:59" : t;
    }

    private String fmtTime(Object o) {
        if (o == null) return "";
        if (o instanceof LocalDateTime t) return t.format(FMT);
        return String.valueOf(o);
    }

    public static String csv(String s) {
        if (s == null) return "";
        String v = s.replace("\"", "\"\"");
        return v.contains(",") || v.contains("\n") || v.contains("\"") ? "\"" + v + "\"" : v;
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private String cut(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private String ip(HttpServletRequest req) {
        String x = req.getHeader("X-Forwarded-For");
        if (x != null && !x.isBlank()) return x.split(",")[0].trim();
        return req.getRemoteAddr();
    }

    private String ua(HttpServletRequest req) {
        String s = req.getHeader("User-Agent");
        return s == null ? "" : s;
    }

    private int intv(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }
}
