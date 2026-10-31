package com.zhiketang.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/**
 * 课程与选课数据访问
 */
@Mapper
public interface CourseMapper {

    /** 教师视角：我的课程（含选课人数、课时数） */
    @Select("SELECT c.*, " +
            " (SELECT COUNT(*) FROM enrollments e WHERE e.course_id = c.course_id) AS stu_cnt, " +
            " (SELECT COUNT(*) FROM lessons l WHERE l.course_id = c.course_id) AS lesson_cnt " +
            "FROM courses c WHERE c.teacher_id = #{tid} ORDER BY c.course_id")
    List<Map<String, Object>> listByTeacher(@Param("tid") int tid);

    /** 学生视角：我的课程（含授课教师、课时数） */
    @Select("SELECT c.*, u.real_name AS teacher_name, " +
            " (SELECT COUNT(*) FROM lessons l WHERE l.course_id = c.course_id) AS lesson_cnt " +
            "FROM courses c JOIN enrollments e ON e.course_id = c.course_id " +
            "JOIN users u ON u.user_id = c.teacher_id " +
            "WHERE e.student_id = #{sid} ORDER BY c.course_id")
    List<Map<String, Object>> listByStudent(@Param("sid") int sid);

    @Select("SELECT c.*, u.real_name AS teacher_name FROM courses c " +
            "JOIN users u ON u.user_id = c.teacher_id WHERE c.course_id = #{cid}")
    Map<String, Object> findById(@Param("cid") int cid);

    /** 校验教师是否拥有该课程 */
    @Select("SELECT COUNT(*) FROM courses WHERE course_id = #{cid} AND teacher_id = #{tid}")
    int countOwned(@Param("cid") int cid, @Param("tid") int tid);

    /** 校验学生是否选修该课程 */
    @Select("SELECT COUNT(*) FROM enrollments WHERE course_id = #{cid} AND student_id = #{sid}")
    int countEnrolled(@Param("cid") int cid, @Param("sid") int sid);

    /** 课程学生名单（来自学情视图） */
    @Select("SELECT u.user_id, u.username, u.real_name, u.gender, cl.class_name, " +
            " k.attendance_rate, k.interact_cnt, k.avg_score, k.composite_score, k.risk_level " +
            "FROM v_student_risk k JOIN users u ON u.user_id = k.student_id " +
            "LEFT JOIN classes cl ON cl.class_id = u.class_id " +
            "WHERE k.course_id = #{cid} ORDER BY u.username")
    List<Map<String, Object>> courseStudents(@Param("cid") int cid);

    @Insert("INSERT INTO courses(course_code, course_name, credit, semester, teacher_id, description) " +
            "VALUES(#{r.course_code}, #{r.course_name}, #{r.credit}, #{r.semester}, #{r.teacher_id}, #{r.description})")
    @Options(useGeneratedKeys = true, keyProperty = "r.course_id")
    int insert(@Param("r") Map<String, Object> r);

    @Insert("<script>INSERT INTO enrollments(course_id, student_id) VALUES " +
            "<foreach collection='list' item='r' separator=','>(#{r.course_id}, #{r.student_id})</foreach></script>")
    int batchInsertEnrollments(@Param("list") List<Map<String, Object>> list);

    @Select("SELECT student_id FROM enrollments WHERE course_id = #{cid} ORDER BY student_id")
    List<Integer> listStudentIds(@Param("cid") int cid);
}
