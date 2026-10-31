package com.zhiketang.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/**
 * 系统管理数据访问：配置 / 权限 / 操作日志 / 备份记录 / 监控统计
 */
@Mapper
public interface SysMapper {

    /* ============================================================ 系统配置 */

    @Select("SELECT * FROM sys_config ORDER BY config_group, config_id")
    List<Map<String, Object>> listConfig();

    @Select("SELECT * FROM sys_config WHERE config_key = #{k}")
    Map<String, Object> findConfig(@Param("k") String key);

    /** 保存配置：不存在则插入，已存在则更新值并记录修改人 */
    @Insert("INSERT INTO sys_config(config_key, config_value, config_type, config_group, name, description, editable, updated_by) " +
            "VALUES(#{r.config_key}, #{r.config_value}, #{r.config_type}, #{r.config_group}, " +
            "#{r.name}, #{r.description}, #{r.editable}, #{r.updated_by}) " +
            "ON DUPLICATE KEY UPDATE config_value = VALUES(config_value), updated_by = VALUES(updated_by)")
    int upsertConfig(@Param("r") Map<String, Object> r);

    @Insert("INSERT INTO sys_config(config_key, config_value, config_type, config_group, name, description, editable, updated_by) " +
            "VALUES(#{r.config_key}, #{r.config_value}, #{r.config_type}, #{r.config_group}, " +
            "#{r.name}, #{r.description}, #{r.editable}, #{r.updated_by}) " +
            "ON DUPLICATE KEY UPDATE config_value = VALUES(config_value)")
    int upsertConfigKeepValue(@Param("r") Map<String, Object> r);

    @Update("UPDATE sys_config SET config_value = #{v}, updated_by = #{uid} WHERE config_key = #{k}")
    int updateConfig(@Param("k") String key, @Param("v") String value, @Param("uid") int uid);

    /* ============================================================ 权限 */

    @Select("SELECT * FROM sys_permission ORDER BY perm_group, sort_no, perm_id")
    List<Map<String, Object>> listPermissions();

    @Select("SELECT perm_code FROM role_permission WHERE role = #{role} AND enabled = 1")
    List<String> enabledPermsOfRole(@Param("role") String role);

    @Select("SELECT role, perm_code, enabled FROM role_permission ORDER BY role, perm_code")
    List<Map<String, Object>> listAllRolePerm();

    @Insert("INSERT INTO role_permission(role, perm_code, enabled) VALUES(#{role}, #{code}, #{enabled}) " +
            "ON DUPLICATE KEY UPDATE enabled = VALUES(enabled)")
    int upsertRolePerm(@Param("role") String role, @Param("code") String code, @Param("enabled") int enabled);

    @Insert("INSERT INTO sys_permission(perm_code, perm_name, perm_group, sort_no, builtin) " +
            "VALUES(#{r.perm_code}, #{r.perm_name}, #{r.perm_group}, #{r.sort_no}, 1) " +
            "ON DUPLICATE KEY UPDATE perm_name = VALUES(perm_name)")
    int upsertPermission(@Param("r") Map<String, Object> r);

    /* ============================================================ 操作日志 */

    @Insert("INSERT INTO operation_logs(operator_id, operator, module, operation, target, detail, " +
            "result, fail_reason, ip, user_agent, cost_ms) " +
            "VALUES(#{r.operator_id}, #{r.operator}, #{r.module}, #{r.operation}, #{r.target}, #{r.detail}, " +
            "#{r.result}, #{r.fail_reason}, #{r.ip}, #{r.user_agent}, #{r.cost_ms})")
    @Options(useGeneratedKeys = true, keyProperty = "r.log_id")
    int insertLog(@Param("r") Map<String, Object> r);

    /** 审计查询：按操作人 / 模块 / 结果 / 关键字 / 时间范围组合过滤 */
    @Select("<script>SELECT l.*, u.real_name AS operator_name " +
            "FROM operation_logs l LEFT JOIN users u ON u.user_id = l.operator_id " +
            "WHERE 1 = 1 " +
            "<if test='module != null and module != \"\"'> AND l.module = #{module}</if>" +
            "<if test='result != null and result != \"\"'> AND l.result = #{result}</if>" +
            "<if test='operatorId != null'> AND l.operator_id = #{operatorId}</if>" +
            "<if test='kw != null and kw != \"\"'> AND (l.operator LIKE CONCAT('%', #{kw}, '%') " +
            "   OR l.operation LIKE CONCAT('%', #{kw}, '%') OR l.target LIKE CONCAT('%', #{kw}, '%') " +
            "   OR l.detail LIKE CONCAT('%', #{kw}, '%'))</if>" +
            "<if test='from != null and from != \"\"'> AND l.created_at &gt;= #{from}</if>" +
            "<if test='to != null and to != \"\"'> AND l.created_at &lt;= #{to}</if>" +
            " ORDER BY l.log_id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<Map<String, Object>> searchLogs(@Param("module") String module, @Param("result") String result,
                                         @Param("operatorId") Integer operatorId, @Param("kw") String kw,
                                         @Param("from") String from, @Param("to") String to,
                                         @Param("limit") int limit, @Param("offset") int offset);

    @Select("<script>SELECT COUNT(*) FROM operation_logs l WHERE 1 = 1 " +
            "<if test='module != null and module != \"\"'> AND l.module = #{module}</if>" +
            "<if test='result != null and result != \"\"'> AND l.result = #{result}</if>" +
            "<if test='operatorId != null'> AND l.operator_id = #{operatorId}</if>" +
            "<if test='kw != null and kw != \"\"'> AND (l.operator LIKE CONCAT('%', #{kw}, '%') " +
            "   OR l.operation LIKE CONCAT('%', #{kw}, '%') OR l.target LIKE CONCAT('%', #{kw}, '%'))</if>" +
            "<if test='from != null and from != \"\"'> AND l.created_at &gt;= #{from}</if>" +
            "<if test='to != null and to != \"\"'> AND l.created_at &lt;= #{to}</if>" +
            "</script>")
    int countLogs(@Param("module") String module, @Param("result") String result,
                  @Param("operatorId") Integer operatorId, @Param("kw") String kw,
                  @Param("from") String from, @Param("to") String to);

    /** 审计概览：失败操作数、今日操作数、高频模块 */
    @Select("SELECT " +
            " SUM(CASE WHEN result = '失败' THEN 1 ELSE 0 END) AS fail_cnt, " +
            " SUM(CASE WHEN DATE(created_at) = CURDATE() THEN 1 ELSE 0 END) AS today_cnt, " +
            " COUNT(*) AS total_cnt " +
            "FROM operation_logs")
    Map<String, Object> logSummary();

    @Select("SELECT module, COUNT(*) AS c FROM operation_logs GROUP BY module ORDER BY c DESC LIMIT 8")
    List<Map<String, Object>> logModuleDist();

    /** 日志保留策略：按天数清理（管理员可配置） */
    @Delete("DELETE FROM operation_logs WHERE created_at < DATE_SUB(NOW(), INTERVAL #{days} DAY)")
    int purgeLogs(@Param("days") int days);

    /* ============================================================ 备份 */

    @Insert("INSERT INTO backup_records(file_name, file_path, file_size, backup_type, db_name, " +
            "table_cnt, row_cnt, status, message, cost_ms, operator_id) " +
            "VALUES(#{r.file_name}, #{r.file_path}, #{r.file_size}, #{r.backup_type}, #{r.db_name}, " +
            "#{r.table_cnt}, #{r.row_cnt}, #{r.status}, #{r.message}, #{r.cost_ms}, #{r.operator_id})")
    @Options(useGeneratedKeys = true, keyProperty = "r.backup_id")
    int insertBackup(@Param("r") Map<String, Object> r);

    @Select("SELECT b.*, u.real_name AS operator_name FROM backup_records b " +
            "LEFT JOIN users u ON u.user_id = b.operator_id ORDER BY b.backup_id DESC LIMIT 100")
    List<Map<String, Object>> listBackups();

    @Select("SELECT * FROM backup_records WHERE backup_id = #{id}")
    Map<String, Object> findBackup(@Param("id") int id);

    @Delete("DELETE FROM backup_records WHERE backup_id = #{id}")
    int deleteBackup(@Param("id") int id);

    /* ============================================================ 监控 */

    /** 各表数据量（information_schema 统计，体现对元数据字典表的运用） */
    @Select("SELECT table_name, table_rows, table_comment FROM information_schema.tables " +
            "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' " +
            "ORDER BY table_rows DESC")
    List<Map<String, Object>> tableSizes();

    /** 业务总览 */
    @Select("SELECT " +
            " (SELECT COUNT(*) FROM users)                        AS user_cnt, " +
            " (SELECT COUNT(*) FROM users WHERE role = 'student') AS student_cnt, " +
            " (SELECT COUNT(*) FROM users WHERE role = 'teacher') AS teacher_cnt, " +
            " (SELECT COUNT(*) FROM users WHERE status = '停用')  AS disabled_cnt, " +
            " (SELECT COUNT(*) FROM courses)                      AS course_cnt, " +
            " (SELECT COUNT(*) FROM lessons)                      AS lesson_cnt, " +
            " (SELECT COUNT(*) FROM attendances)                  AS att_cnt, " +
            " (SELECT COUNT(*) FROM interaction_records)          AS inter_cnt, " +
            " (SELECT COUNT(*) FROM submissions)                  AS sub_cnt, " +
            " (SELECT COUNT(*) FROM learning_events)              AS event_cnt")
    Map<String, Object> bizSummary();

    /** 近 7 日活跃趋势（按天聚合学生行为） */
    @Select("SELECT DATE(event_time) AS d, COUNT(*) AS c, COUNT(DISTINCT student_id) AS stu " +
            "FROM learning_events WHERE event_time >= DATE_SUB(CURDATE(), INTERVAL 6 DAY) " +
            "GROUP BY DATE(event_time) ORDER BY d")
    List<Map<String, Object>> activeTrend();

    /** MySQL 运行指标 */
    @Select("SELECT VERSION() AS version, " +
            " (SELECT @@max_connections) AS max_conn, " +
            " (SELECT COUNT(*) FROM information_schema.processlist) AS conn_now, " +
            " (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME = 'Uptime') AS uptime_sec")
    Map<String, Object> dbStatus();

    /** 库容量 */
    @Select("SELECT ROUND(SUM(data_length + index_length) / 1024 / 1024, 1) AS size_mb " +
            "FROM information_schema.tables WHERE table_schema = DATABASE()")
    Map<String, Object> dbSize();
}
