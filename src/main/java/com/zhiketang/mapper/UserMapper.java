package com.zhiketang.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/**
 * 用户 / 班级 / 知识点 基础数据访问
 */
@Mapper
public interface UserMapper {

    @Select("SELECT * FROM users WHERE username = #{username}")
    Map<String, Object> findByUsername(@Param("username") String username);

    @Select("SELECT * FROM users WHERE user_id = #{id}")
    Map<String, Object> findById(@Param("id") int id);

    @Select("SELECT COUNT(*) FROM users")
    int count();

    @Select("SELECT user_id FROM users WHERE role = 'student' ORDER BY user_id")
    List<Integer> listStudentIds();

    /* ---------------- 管理员：用户管理 ---------------- */

    /** 分页 + 多条件检索（角色 / 状态 / 关键词） */
    @Select("<script>SELECT u.user_id, u.username, u.real_name, u.role, u.gender, u.phone, u.status, " +
            "u.class_id, u.last_login_at, u.last_login_ip, u.locked_until, u.created_at, " +
            "c.class_name, " +
            "(SELECT COUNT(*) FROM courses x WHERE x.teacher_id = u.user_id) AS course_cnt " +
            "FROM users u LEFT JOIN classes c ON c.class_id = u.class_id " +
            "WHERE 1 = 1 " +
            "<if test='role != null and role != \"\"'> AND u.role = #{role}</if>" +
            "<if test='status != null and status != \"\"'> AND u.status = #{status}</if>" +
            "<if test='kw != null and kw != \"\"'> AND (u.username LIKE CONCAT('%', #{kw}, '%') " +
            "   OR u.real_name LIKE CONCAT('%', #{kw}, '%'))</if>" +
            " ORDER BY FIELD(u.role, 'admin', 'teacher', 'student'), u.user_id " +
            "LIMIT #{limit} OFFSET #{offset}</script>")
    List<Map<String, Object>> searchUsers(@Param("role") String role, @Param("status") String status,
                                          @Param("kw") String kw, @Param("limit") int limit,
                                          @Param("offset") int offset);

    @Select("<script>SELECT COUNT(*) FROM users u WHERE 1 = 1 " +
            "<if test='role != null and role != \"\"'> AND u.role = #{role}</if>" +
            "<if test='status != null and status != \"\"'> AND u.status = #{status}</if>" +
            "<if test='kw != null and kw != \"\"'> AND (u.username LIKE CONCAT('%', #{kw}, '%') " +
            "   OR u.real_name LIKE CONCAT('%', #{kw}, '%'))</if>" +
            "</script>")
    int countUsers(@Param("role") String role, @Param("status") String status, @Param("kw") String kw);

    @Update("UPDATE users SET real_name = #{realName}, role = #{role}, gender = #{gender}, " +
            "phone = #{phone}, class_id = #{classId} WHERE user_id = #{id}")
    int updateUser(@Param("id") int id, @Param("realName") String realName, @Param("role") String role,
                   @Param("gender") String gender, @Param("phone") String phone,
                   @Param("classId") Integer classId);

    @Update("UPDATE users SET status = #{status} WHERE user_id = #{id}")
    int setStatus(@Param("id") int id, @Param("status") String status);

    @Update("UPDATE users SET password_hash = #{hash} WHERE user_id = #{id}")
    int setPasswordHash(@Param("id") int id, @Param("hash") String hash);

    @Update("UPDATE users SET login_fail_count = 0, locked_until = NULL WHERE user_id = #{id}")
    int unlock(@Param("id") int id);

    @Update("UPDATE users SET login_fail_count = login_fail_count + 1 WHERE user_id = #{id}")
    int incrLoginFail(@Param("id") int id);

    @Update("UPDATE users SET locked_until = #{until} WHERE user_id = #{id}")
    int lockUntil(@Param("id") int id, @Param("until") String until);

    @Update("UPDATE users SET last_login_at = #{t}, last_login_ip = #{ip}, " +
            "login_fail_count = 0, locked_until = NULL WHERE user_id = #{id}")
    int touchLogin(@Param("id") int id, @Param("t") String t, @Param("ip") String ip);

    @Delete("DELETE FROM users WHERE user_id = #{id}")
    int deleteUser(@Param("id") int id);

    /** 删除前检查：该用户是否仍被业务数据引用（避免外键级联误删） */
    @Select("SELECT " +
            " (SELECT COUNT(*) FROM courses WHERE teacher_id = #{id})            AS course_cnt, " +
            " (SELECT COUNT(*) FROM enrollments WHERE student_id = #{id})        AS enroll_cnt, " +
            " (SELECT COUNT(*) FROM submissions WHERE student_id = #{id})        AS sub_cnt, " +
            " (SELECT COUNT(*) FROM attendances WHERE student_id = #{id})        AS att_cnt, " +
            " (SELECT COUNT(*) FROM interaction_records WHERE student_id = #{id}) AS inter_cnt, " +
            " (SELECT COUNT(*) FROM learning_events WHERE student_id = #{id})    AS event_cnt")
    Map<String, Object> refCount(@Param("id") int id);

    @Select("SELECT COUNT(*) FROM users WHERE role = 'admin' AND status = '正常'")
    int countActiveAdmin();

    @Insert("INSERT INTO users(username, password_hash, real_name, role, gender, phone, class_id) " +
            "VALUES(#{r.username}, #{r.password_hash}, #{r.real_name}, #{r.role}, #{r.gender}, #{r.phone}, #{r.class_id})")
    int insert(@Param("r") Map<String, Object> r);

    @Insert("<script>INSERT INTO users(username, password_hash, real_name, role, gender, phone, class_id) VALUES " +
            "<foreach collection='list' item='r' separator=','>" +
            "(#{r.username}, #{r.password_hash}, #{r.real_name}, #{r.role}, #{r.gender}, #{r.phone}, #{r.class_id})" +
            "</foreach></script>")
    int batchInsert(@Param("list") List<Map<String, Object>> list);

    /* ---------------- 班级 ---------------- */

    @Select("SELECT cl.*, (SELECT COUNT(*) FROM users u WHERE u.class_id = cl.class_id) AS stu_cnt " +
            "FROM classes cl ORDER BY cl.class_id")
    List<Map<String, Object>> listClasses();

    @Insert("<script>INSERT INTO classes(class_name, grade, major, headteacher) VALUES " +
            "<foreach collection='list' item='r' separator=','>" +
            "(#{r.class_name}, #{r.grade}, #{r.major}, #{r.headteacher})" +
            "</foreach></script>")
    int batchInsertClasses(@Param("list") List<Map<String, Object>> list);

    /* ---------------- 知识点 ---------------- */

    @Select("SELECT * FROM knowledge_points WHERE course_id = #{cid} ORDER BY kp_order")
    List<Map<String, Object>> listKnowledgePoints(@Param("cid") int cid);

    @Insert("<script>INSERT INTO knowledge_points(course_id, kp_name, kp_order, weight, description) VALUES " +
            "<foreach collection='list' item='r' separator=','>" +
            "(#{r.course_id}, #{r.kp_name}, #{r.kp_order}, #{r.weight}, #{r.description})" +
            "</foreach></script>")
    int batchInsertKp(@Param("list") List<Map<String, Object>> list);
}
