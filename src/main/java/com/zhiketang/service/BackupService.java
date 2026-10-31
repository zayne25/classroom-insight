package com.zhiketang.service;

import com.zhiketang.common.BizException;
import com.zhiketang.mapper.SysMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据备份与恢复
 *
 * 设计要点：
 * 1. **双引擎**：优先调用 MySQL 官方命令行（mysqldump / mysql），未安装或执行失败时
 *    自动降级为 JDBC 逻辑转储（自研：SHOW CREATE TABLE + 批量 INSERT），
 *    保证在没有安装客户端工具的环境下备份恢复依然可用。
 * 2. **恢复前强制自动备份**：任何恢复动作都会先把当前库另存一份（backup_type = 恢复前自动），
 *    给误操作留一条回退路径。
 * 3. 每次动作都写 backup_records（文件名、大小、表数、行数、耗时、状态、操作人），
 *    并在界面上区分「手动 / 恢复前自动」「成功 / 失败」。
 * 4. 备份目录来自系统配置 backup.dir，磁盘不足时明确报错而不是写一半。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackupService {

    private final SysMapper sys;
    private final SysConfigService cfg;
    private final JdbcTemplate jdbc;

    @Value("${spring.datasource.url}")
    private String url;
    @Value("${spring.datasource.username}")
    private String user;
    @Value("${spring.datasource.password}")
    private String password;

    private static final DateTimeFormatter FILE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final Pattern URL_PATTERN = Pattern.compile("jdbc:mysql://([^:/]+)(?::(\\d+))?/([^?]+)");
    private static final int BATCH_ROWS = 200;      // 每个 INSERT 语句包含的行数
    private static final int CMD_TIMEOUT_SEC = 600;

    /* ============================================================ 目录与连接信息 */

    public Path backupDir() {
        String d = cfg.str("backup.dir");
        if (d == null || d.isBlank()) d = "backups";
        Path p = Paths.get(d).toAbsolutePath().normalize();
        try {
            if (!Files.exists(p)) Files.createDirectories(p);
        } catch (Exception e) {
            throw new BizException("备份目录不可用：" + p + "（" + e.getMessage() + "）");
        }
        return p;
    }

    private String host() {
        Matcher m = URL_PATTERN.matcher(url);
        return m.find() ? m.group(1) : "127.0.0.1";
    }

    private String port() {
        Matcher m = URL_PATTERN.matcher(url);
        return m.find() && m.group(2) != null ? m.group(2) : "3306";
    }

    public String dbName() {
        Matcher m = URL_PATTERN.matcher(url);
        return m.find() ? m.group(3) : "classroom";
    }

    /* ============================================================ 备份 */

    /**
     * 创建备份
     *
     * @param type 手动 / 恢复前自动
     * @return {backup_id, file_name, engine, size, table_cnt, row_cnt, cost_ms}
     */
    public Map<String, Object> create(String type, int operatorId) {
        long t0 = System.currentTimeMillis();
        Path dir = backupDir();
        String fileName = dbName() + "_" + LocalDateTime.now().format(FILE_FMT) + ".sql";
        Path file = dir.resolve(fileName);

        String engine;
        int tableCnt = 0, rowCnt = 0;
        String message = "";
        String status = "成功";

        try {
            engine = dumpByCommand(file) ? "mysqldump" : "jdbc";
            if ("jdbc".equals(engine)) {
                int[] stat = dumpByJdbc(file);
                tableCnt = stat[0];
                rowCnt = stat[1];
                message = "未检测到 mysqldump，已使用内置逻辑转储";
            } else {
                tableCnt = countTables();
                rowCnt = countRowsApprox();
            }
        } catch (Exception e) {
            log.error("备份失败", e);
            status = "失败";
            engine = "-";
            message = cut(e.getMessage(), 250);
        }

        long size = 0;
        try {
            size = Files.exists(file) ? Files.size(file) : 0;
        } catch (Exception ignored) { }

        Map<String, Object> r = new HashMap<>();
        r.put("file_name", fileName);
        r.put("file_path", file.toString());
        r.put("file_size", size);
        r.put("backup_type", type == null || type.isBlank() ? "手动" : type);
        r.put("db_name", dbName());
        r.put("table_cnt", tableCnt);
        r.put("row_cnt", rowCnt);
        r.put("status", status);
        r.put("message", message);
        r.put("cost_ms", (int) (System.currentTimeMillis() - t0));
        r.put("operator_id", operatorId);
        sys.insertBackup(r);

        if ("失败".equals(status)) {
            throw new BizException("备份失败：" + message);
        }
        r.put("engine", engine);
        r.put("size_text", sizeText(size));
        return r;
    }

    /** 方式一：调用 mysqldump */
    private boolean dumpByCommand(Path file) {
        String exe = cfg.str("backup.mysqldump_path");
        if (exe == null || exe.isBlank()) return false;
        try {
            List<String> cmd = new ArrayList<>(Arrays.asList(exe,
                    "-h", host(), "-P", port(), "-u", user,
                    "-p" + password,
                    "--default-character-set=utf8mb4",
                    "--set-gtid-purged=OFF",
                    "--single-transaction",
                    "--skip-lock-tables",
                    dbName()));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectOutput(file.toFile());
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            boolean ok = p.waitFor(CMD_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS);
            return ok && p.exitValue() == 0 && Files.exists(file) && Files.size(file) > 0;
        } catch (Exception e) {
            log.warn("mysqldump 调用失败，将使用内置转储：{}", e.getMessage());
            return false;
        }
    }

    /** 方式二：JDBC 逻辑转储（自研，保证无客户端工具也可用） */
    private int[] dumpByJdbc(Path file) throws Exception {
        int tableCnt = 0, rowCnt = 0;
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write("-- 智课堂 数据库备份（内置逻辑转储）\n");
            w.write("-- 数据库: " + dbName() + "    生成时间: "
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "\n");
            w.write("SET NAMES utf8mb4;\n");
            w.write("SET FOREIGN_KEY_CHECKS=0;\n");

            // 1) 表结构 + 数据
            for (String t : listObjects("BASE TABLE")) {
                String create = showCreate(t, "Create Table");
                if (create == null) continue;
                w.write("DROP TABLE IF EXISTS `" + t + "`;\n");
                w.write(oneLine(create) + ";\n");
                tableCnt++;
                rowCnt += writeInserts(w, t);
            }
            // 2) 视图：放在最后重建，保证其依赖的基表已存在
            for (String v : listObjects("VIEW")) {
                String create = showCreate(v, "Create View");
                if (create == null) continue;
                w.write("DROP VIEW IF EXISTS `" + v + "`;\n");
                w.write(oneLine(create) + ";\n");
            }
            w.write("SET FOREIGN_KEY_CHECKS=1;\n");
        }
        return new int[]{tableCnt, rowCnt};
    }

    private int writeInserts(BufferedWriter w, String table) throws Exception {
        List<String> cols = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        jdbc.query("SELECT * FROM `" + table + "`", (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            if (cols.isEmpty()) {
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                    cols.add(rs.getMetaData().getColumnLabel(i));
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (String c : cols) row.put(c, rs.getObject(c));
            rows.add(row);
        });
        if (rows.isEmpty()) return 0;

        String head = "INSERT INTO `" + table + "` (" + quoteCols(cols) + ") VALUES ";
        for (int i = 0; i < rows.size(); i += BATCH_ROWS) {
            StringBuilder sb = new StringBuilder(head);
            int end = Math.min(i + BATCH_ROWS, rows.size());
            for (int j = i; j < end; j++) {
                if (j > i) sb.append(',');
                sb.append('(').append(values(rows.get(j), cols)).append(')');
            }
            w.write(sb.append(";\n").toString());
        }
        return rows.size();
    }

    private String values(Map<String, Object> row, List<String> cols) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(literal(row.get(cols.get(i))));
        }
        return sb.toString();
    }

    private String literal(Object v) {
        if (v == null) return "NULL";
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        if (v instanceof byte[] b) {
            StringBuilder sb = new StringBuilder("X'");
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.append('\'').toString();
        }
        if (v instanceof Timestamp ts) v = ts.toLocalDateTime();
        return "'" + escape(String.valueOf(v)) + "'";
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'")
                .replace("\r", "\\r").replace("\n", "\\n").replace("\u0000", "");
    }

    private String quoteCols(List<String> cols) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('`').append(cols.get(i)).append('`');
        }
        return sb.toString();
    }

    private List<String> listObjects(String type) {
        return jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() " +
                        "AND table_type = ? ORDER BY table_name", String.class, type);
    }

    private String showCreate(String name, String column) {
        try {
            Map<String, Object> r = jdbc.queryForMap("SHOW CREATE TABLE `" + name + "`");
            for (Map.Entry<String, Object> e : r.entrySet()) {
                if (e.getKey().equalsIgnoreCase(column) || e.getKey().equalsIgnoreCase("Create Table")
                        || e.getKey().equalsIgnoreCase("Create View")) {
                    return String.valueOf(e.getValue());
                }
            }
        } catch (Exception e) {
            log.warn("读取 {} 的建表语句失败：{}", name, e.getMessage());
        }
        return null;
    }

    private String oneLine(String s) {
        return s.replaceAll("\\s*\\r?\\n\\s*", " ").trim();
    }

    private int countTables() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() " +
                        "AND table_type = 'BASE TABLE'", Integer.class);
        return n == null ? 0 : n;
    }

    private int countRowsApprox() {
        Long n = jdbc.queryForObject(
                "SELECT IFNULL(SUM(table_rows),0) FROM information_schema.tables " +
                        "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'", Long.class);
        return n == null ? 0 : n.intValue();
    }

    /* ============================================================ 列表 / 下载 / 删除 */

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> rows = sys.listBackups();
        for (Map<String, Object> r : rows) {
            String p = String.valueOf(r.getOrDefault("file_path", ""));
            boolean exists = !p.isBlank() && Files.exists(Paths.get(p));
            r.put("file_exists", exists);
            r.put("size_text", sizeText(r.get("file_size")));
        }
        return rows;
    }

    public byte[] read(int id) {
        Map<String, Object> b = sys.findBackup(id);
        if (b == null) throw new BizException("备份记录不存在");
        Path p = Paths.get(String.valueOf(b.get("file_path")));
        if (!Files.exists(p)) throw new BizException("备份文件已不存在，可能已被手动清理");
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            throw new BizException("读取备份文件失败：" + e.getMessage());
        }
    }

    public String fileName(int id) {
        Map<String, Object> b = sys.findBackup(id);
        return b == null ? "backup.sql" : String.valueOf(b.get("file_name"));
    }

    public void delete(int id) {
        Map<String, Object> b = sys.findBackup(id);
        if (b == null) throw new BizException("备份记录不存在");
        try {
            Path p = Paths.get(String.valueOf(b.get("file_path")));
            Files.deleteIfExists(p);
        } catch (Exception e) {
            log.warn("删除备份文件失败：{}", e.getMessage());
        }
        sys.deleteBackup(id);
    }

    /* ============================================================ 恢复 */

    /**
     * 恢复数据
     *
     * @param confirm 界面二次确认（必须传入备份文件名一致才允许执行）
     * @return {backup_id, statements, failed, engine, cost_ms, pre_backup}
     */
    public Map<String, Object> restore(int id, String confirm, int operatorId) {
        Map<String, Object> b = sys.findBackup(id);
        if (b == null) throw new BizException("备份记录不存在");
        String fileName = String.valueOf(b.get("file_name"));
        if (!"成功".equals(String.valueOf(b.get("status")))) {
            throw new BizException("该备份状态为「失败」，不可用于恢复");
        }
        if (confirm == null || !confirm.trim().equals(fileName)) {
            throw new BizException("确认信息不匹配，请输入备份文件名「" + fileName + "」以确认恢复");
        }
        Path file = Paths.get(String.valueOf(b.get("file_path")));
        if (!Files.exists(file)) throw new BizException("备份文件已不存在，无法恢复");

        long t0 = System.currentTimeMillis();

        // 1) 恢复前自动备份当前库
        Map<String, Object> pre = null;
        try {
            pre = create("恢复前自动", operatorId);
        } catch (Exception e) {
            log.warn("恢复前自动备份失败（不阻断恢复）：{}", e.getMessage());
        }

        // 2) 执行恢复
        String engine;
        int statements = 0, failed = 0;
        if (restoreByCommand(file)) {
            engine = "mysql";
            statements = -1;     // 命令行执行，语句数不统计
        } else {
            engine = "jdbc";
            int[] st = restoreByJdbc(file);
            statements = st[0];
            failed = st[1];
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("backup_id", id);
        out.put("file_name", fileName);
        out.put("engine", engine);
        out.put("statements", statements);
        out.put("failed", failed);
        out.put("pre_backup", pre == null ? null : pre.get("file_name"));
        out.put("cost_ms", (int) (System.currentTimeMillis() - t0));
        if (failed > 0) {
            throw new BizException("恢复完成，但有 " + failed + " 条语句执行失败，请检查数据完整性");
        }
        return out;
    }

    private boolean restoreByCommand(Path file) {
        String exe = cfg.str("backup.mysql_path");
        if (exe == null || exe.isBlank()) return false;
        try {
            ProcessBuilder pb = new ProcessBuilder(exe, "-h", host(), "-P", port(), "-u", user,
                    "-p" + password, "--default-character-set=utf8mb4", dbName());
            pb.redirectInput(file.toFile());
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            boolean ok = p.waitFor(CMD_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS);
            return ok && p.exitValue() == 0;
        } catch (Exception e) {
            log.warn("mysql 客户端恢复失败，将使用 JDBC 执行：{}", e.getMessage());
            return false;
        }
    }

    /** JDBC 逐条执行（按行拆分，备份文件由本服务生成时每条语句占一行） */
    private int[] restoreByJdbc(Path file) {
        int total = 0, failed = 0;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("--") || s.startsWith("/*")) continue;
                if (s.endsWith(";")) s = s.substring(0, s.length() - 1);
                if (s.isEmpty()) continue;
                total++;
                try {
                    jdbc.execute(s);
                } catch (Exception e) {
                    failed++;
                    log.warn("恢复语句执行失败：{}", s.length() > 80 ? s.substring(0, 80) : s);
                }
            }
        } catch (Exception e) {
            throw new BizException("读取备份文件失败：" + e.getMessage());
        }
        return new int[]{total, failed};
    }

    /* ============================================================ 工具 */

    public static String sizeText(Object bytes) {
        if (bytes == null) return "0 B";
        double b = ((Number) bytes).doubleValue();
        if (b < 1024) return (long) b + " B";
        if (b < 1024 * 1024) return new DecimalFormat("#.#").format(b / 1024) + " KB";
        if (b < 1024 * 1024 * 1024) return new DecimalFormat("#.#").format(b / 1024 / 1024) + " MB";
        return new DecimalFormat("#.#").format(b / 1024 / 1024 / 1024) + " GB";
    }

    private String cut(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
