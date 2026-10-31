package com.zhiketang.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/**
 * 课时与签到数据访问
 */
@Mapper
public interface LessonMapper {

    @Select("SELECT l.*, COALESCE(a.attended_cnt, 0) AS attended_cnt, COALESCE(a.total_stu, 0) AS total_stu, " +
            " CASE WHEN COALESCE(a.total_stu, 0) > 0 THEN ROUND(100.0 * a.attended_cnt / a.total_stu, 1) ELSE 0 END AS att_rate " +
            "FROM lessons l LEFT JOIN v_lesson_attendance a ON a.lesson_id = l.lesson_id " +
            "WHERE l.course_id = #{cid} ORDER BY l.lesson_no")
    List<Map<String, Object>> listByCourse(@Param("cid") int cid);

    @Select("SELECT * FROM lessons WHERE lesson_id = #{lid}")
    Map<String, Object> findById(@Param("lid") int lid);

    @Insert("INSERT INTO lessons(course_id, lesson_no, title, lesson_date, start_time, end_time, status) " +
            "VALUES(#{r.course_id}, #{r.lesson_no}, #{r.title}, #{r.lesson_date}, #{r.start_time}, #{r.end_time}, #{r.status})")
    @Options(useGeneratedKeys = true, keyProperty = "r.lesson_id")
    int insert(@Param("r") Map<String, Object> r);

    @Insert("<script>INSERT INTO lessons(course_id, lesson_no, title, lesson_date, start_time, end_time, status) VALUES " +
            "<foreach collection='list' item='r' separator=','>" +
            "(#{r.course_id}, #{r.lesson_no}, #{r.title}, #{r.lesson_date}, #{r.start_time}, #{r.end_time}, #{r.status})" +
            "</foreach></script>")
    int batchInsert(@Param("list") List<Map<String, Object>> list);

    @Select("SELECT COALESCE(MAX(lesson_no), 0) FROM lessons WHERE course_id = #{cid}")
    int maxLessonNo(@Param("cid") int cid);

    /** 开始课堂：置为进行中并生成签到码 */
    @Update("UPDATE lessons SET status = '进行中', checkin_code = #{code} WHERE lesson_id = #{lid}")
    int start(@Param("lid") int lid, @Param("code") String code);

    /** 结束课堂：置为已结束并清空签到码 */
    @Update("UPDATE lessons SET status = '已结束', checkin_code = NULL WHERE lesson_id = #{lid}")
    int end(@Param("lid") int lid);

    /* ---------------- 签到 ---------------- */

    /** 某课时的签到明细（未签到的学生也列出，status 记为「未签到」） */
    @Select("SELECT u.user_id, u.username, u.real_name, cl.class_name, " +
            " COALESCE(a.status, '未签到') AS status, a.check_in_time " +
            "FROM enrollments e JOIN users u ON u.user_id = e.student_id " +
            "LEFT JOIN classes cl ON cl.class_id = u.class_id " +
            "LEFT JOIN attendances a ON a.lesson_id = #{lid} AND a.student_id = u.user_id " +
            "WHERE e.course_id = #{cid} ORDER BY u.username")
    List<Map<String, Object>> attendanceRows(@Param("lid") int lid, @Param("cid") int cid);

    @Select("SELECT COUNT(*) FROM attendances WHERE lesson_id = #{lid} AND student_id = #{sid}")
    int countChecked(@Param("lid") int lid, @Param("sid") int sid);

    @Insert("INSERT INTO attendances(lesson_id, student_id, status, method, check_in_time) " +
            "VALUES(#{r.lesson_id}, #{r.student_id}, #{r.status}, #{r.method}, #{r.check_in_time})")
    int insertAttendance(@Param("r") Map<String, Object> r);

    @Insert("<script>INSERT INTO attendances(lesson_id, student_id, status, method, check_in_time) VALUES " +
            "<foreach collection='list' item='r' separator=','>" +
            "(#{r.lesson_id}, #{r.student_id}, #{r.status}, #{r.method}, #{r.check_in_time})" +
            "</foreach></script>")
    int batchInsertAttendance(@Param("list") List<Map<String, Object>> list);

    /* ---------------- 随机抽题 ---------------- */

    @Select("SELECT q.q_id, q.content, q.options_json, q.answer, k.kp_name " +
            "FROM questions q JOIN knowledge_points k ON k.kp_id = q.kp_id " +
            "WHERE k.course_id = #{cid} ORDER BY RAND() LIMIT #{n}")
    List<Map<String, Object>> randomQuestions(@Param("cid") int cid, @Param("n") int n);

    /** 按题目 ID 取标准答案（用于判分） */
    @Select("SELECT answer FROM questions WHERE q_id = #{qid}")
    String findAnswer(@Param("qid") int qid);

    /** 某课程下的全部题目（生成答题记录、客观题作业标准答案用） */
    @Select("SELECT q.q_id, q.kp_id, q.difficulty, q.answer FROM questions q " +
            "JOIN knowledge_points k ON k.kp_id = q.kp_id WHERE k.course_id = #{cid}")
    List<Map<String, Object>> questionsOfCourse(@Param("cid") int cid);

    @Insert("<script>INSERT INTO questions(kp_id, q_type, content, options_json, answer, difficulty) VALUES " +
            "<foreach collection='list' item='r' separator=','>" +
            "(#{r.kp_id}, #{r.q_type}, #{r.content}, #{r.options_json}, #{r.answer}, #{r.difficulty})" +
            "</foreach></script>")
    int batchInsertQuestions(@Param("list") List<Map<String, Object>> list);

    @Insert("<script>INSERT INTO answer_records(q_id, student_id, lesson_id, is_correct, answered_at) VALUES " +
            "<foreach collection='list' item='r' separator=','>" +
            "(#{r.q_id}, #{r.student_id}, #{r.lesson_id}, #{r.is_correct}, #{r.answered_at})" +
            "</foreach></script>")
    int batchInsertAnswerRecords(@Param("list") List<Map<String, Object>> list);

    @Insert("INSERT INTO answer_records(q_id, student_id, lesson_id, is_correct, answered_at) " +
            "VALUES(#{r.q_id}, #{r.student_id}, #{r.lesson_id}, #{r.is_correct}, #{r.answered_at})")
    int insertAnswerRecord(@Param("r") Map<String, Object> r);

    @Insert("<script>INSERT INTO learning_events(student_id, course_id, lesson_id, event_type, event_time) VALUES " +
            "<foreach collection='list' item='r' separator=','>" +
            "(#{r.student_id}, #{r.course_id}, #{r.lesson_id}, #{r.event_type}, #{r.event_time})" +
            "</foreach></script>")
    int batchInsertEvents(@Param("list") List<Map<String, Object>> list);

    @Insert("INSERT INTO learning_events(student_id, course_id, lesson_id, event_type, event_time) " +
            "VALUES(#{r.student_id}, #{r.course_id}, #{r.lesson_id}, #{r.event_type}, #{r.event_time})")
    int insertEvent(@Param("r") Map<String, Object> r);
}
