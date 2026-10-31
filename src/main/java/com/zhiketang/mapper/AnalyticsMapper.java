package com.zhiketang.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 学情分析数据访问
 *
 * 设计要点：所有指标均在数据库层用 SQL 聚合完成（6 个统计视图 + 分组聚合），
 * Java 层只做结果组装与少量统计计算（皮尔逊相关系数），不做内存循环统计。
 */
@Mapper
public interface AnalyticsMapper {

    /* ---------------- 概览 ---------------- */

    @Select("SELECT " +
            " (SELECT COUNT(*) FROM enrollments WHERE course_id = #{cid}) AS stu_cnt, " +
            " (SELECT COUNT(*) FROM lessons WHERE course_id = #{cid}) AS lesson_cnt, " +
            " (SELECT COUNT(*) FROM interactions i JOIN lessons l ON l.lesson_id = i.lesson_id " +
            "   WHERE l.course_id = #{cid} AND i.status <> '草稿') AS inter_cnt, " +
            " (SELECT COUNT(*) FROM assignments WHERE course_id = #{cid}) AS assign_cnt, " +
            " ROUND(100.0 * COALESCE(AVG(k.attendance_rate), 0), 1) AS avg_attendance, " +
            " ROUND(100.0 * COALESCE(AVG(k.part_rate), 0), 1)       AS avg_participation, " +
            " ROUND(COALESCE(AVG(k.avg_score), 0), 1)               AS avg_score " +
            "FROM v_student_kpi k WHERE k.course_id = #{cid}")
    Map<String, Object> overview(@Param("cid") int cid);

    @Select("SELECT risk_level, COUNT(*) AS c FROM v_student_risk WHERE course_id = #{cid} GROUP BY risk_level")
    List<Map<String, Object>> riskDist(@Param("cid") int cid);

    /** 课程卡片用：平均出勤率、平均分、红色预警数 */
    @Select("SELECT ROUND(100.0 * COALESCE(AVG(attendance_rate), 0), 1) AS avg_att, " +
            "       ROUND(COALESCE(AVG(avg_score), 0), 1)               AS avg_score, " +
            "       SUM(CASE WHEN risk_level = '红' THEN 1 ELSE 0 END)   AS risk_cnt " +
            "FROM v_student_risk WHERE course_id = #{cid}")
    Map<String, Object> courseCardStats(@Param("cid") int cid);

    /* ---------------- 出勤 ---------------- */

    @Select("SELECT lesson_no, title, lesson_date, total_stu, attended_cnt, late_cnt, " +
            " CASE WHEN total_stu > 0 THEN ROUND(100.0 * attended_cnt / total_stu, 1) ELSE 0 END AS att_rate " +
            "FROM v_lesson_attendance WHERE course_id = #{cid} ORDER BY lesson_no")
    List<Map<String, Object>> attendanceTrend(@Param("cid") int cid);

    @Select("SELECT a.status AS name, COUNT(*) AS value " +
            "FROM attendances a JOIN lessons l ON l.lesson_id = a.lesson_id " +
            "WHERE l.course_id = #{cid} GROUP BY a.status")
    List<Map<String, Object>> attendanceStatusDist(@Param("cid") int cid);

    /* ---------------- 互动 ---------------- */

    @Select("SELECT i.type AS name, COUNT(*) AS value " +
            "FROM interactions i JOIN lessons l ON l.lesson_id = i.lesson_id " +
            "WHERE l.course_id = #{cid} GROUP BY i.type")
    List<Map<String, Object>> interactionTypeDist(@Param("cid") int cid);

    @Select("SELECT u.real_name AS name, k.interact_cnt AS value, k.inter_correct_rate AS correct_rate " +
            "FROM v_student_kpi k JOIN users u ON u.user_id = k.student_id " +
            "WHERE k.course_id = #{cid} ORDER BY k.interact_cnt DESC LIMIT 10")
    List<Map<String, Object>> interactionTopActive(@Param("cid") int cid);

    @Select("SELECT l.lesson_no, l.title, " +
            " (SELECT COUNT(*) FROM interactions i WHERE i.lesson_id = l.lesson_id) AS inter_cnt, " +
            " (SELECT COUNT(*) FROM interaction_records r JOIN interactions i2 ON i2.inter_id = r.inter_id " +
            "   WHERE i2.lesson_id = l.lesson_id) AS rec_cnt, " +
            " (SELECT COUNT(*) FROM enrollments e WHERE e.course_id = l.course_id) AS stu_cnt " +
            "FROM lessons l WHERE l.course_id = #{cid} ORDER BY l.lesson_no")
    List<Map<String, Object>> interactionTrend(@Param("cid") int cid);

    /* ---------------- 成绩 ---------------- */

    @Select("SELECT LEAST(FLOOR(s.score / 10), 9) AS b, COUNT(*) AS c " +
            "FROM submissions s JOIN assignments a ON a.assign_id = s.assign_id " +
            "WHERE a.course_id = #{cid} AND s.score IS NOT NULL GROUP BY b")
    List<Map<String, Object>> scoreDist(@Param("cid") int cid);

    @Select("SELECT cl.class_name AS name, ROUND(AVG(k.avg_score), 1) AS value " +
            "FROM v_student_kpi k JOIN users u ON u.user_id = k.student_id " +
            "JOIN classes cl ON cl.class_id = u.class_id " +
            "WHERE k.course_id = #{cid} GROUP BY cl.class_id, cl.class_name")
    List<Map<String, Object>> classCompare(@Param("cid") int cid);

    @Select("SELECT a.title AS name, ROUND(AVG(s.score), 1) AS value " +
            "FROM assignments a LEFT JOIN submissions s ON s.assign_id = a.assign_id AND s.score IS NOT NULL " +
            "WHERE a.course_id = #{cid} GROUP BY a.assign_id, a.title, a.assigned_at ORDER BY a.assigned_at")
    List<Map<String, Object>> assignTrend(@Param("cid") int cid);

    /* ---------------- 知识点 ---------------- */

    @Select("SELECT kp_id, kp_name, kp_order, answer_cnt, ROUND(100.0 * correct_rate, 1) AS correct_rate " +
            "FROM v_kp_mastery WHERE course_id = #{cid} ORDER BY kp_order")
    List<Map<String, Object>> kpMastery(@Param("cid") int cid);

    @Select("SELECT k.student_id, u.real_name, u.username " +
            "FROM v_student_kpi k JOIN users u ON u.user_id = k.student_id " +
            "WHERE k.course_id = #{cid} ORDER BY k.composite_score DESC")
    List<Map<String, Object>> orderedStudents(@Param("cid") int cid);

    @Select("SELECT student_id, kp_id, correct_rate FROM v_student_kp_mastery WHERE course_id = #{cid}")
    List<Map<String, Object>> studentKpMastery(@Param("cid") int cid);

    /* ---------------- 预警 ---------------- */

    @Select("SELECT r.*, u.username, u.class_id, cl.class_name " +
            "FROM v_student_risk r JOIN users u ON u.user_id = r.student_id " +
            "LEFT JOIN classes cl ON cl.class_id = u.class_id " +
            "WHERE r.course_id = #{cid} ORDER BY r.composite_score ASC")
    List<Map<String, Object>> riskList(@Param("cid") int cid);

    /* ---------------- 相关性 ---------------- */

    @Select("SELECT k.student_id, u.real_name, k.interact_cnt, k.avg_score, k.attendance_rate, k.composite_score " +
            "FROM v_student_kpi k JOIN users u ON u.user_id = k.student_id WHERE k.course_id = #{cid}")
    List<Map<String, Object>> correlationPoints(@Param("cid") int cid);

    /* ---------------- 学生个人 ---------------- */

    @Select("SELECT * FROM v_student_risk WHERE course_id = #{cid} AND student_id = #{sid}")
    Map<String, Object> studentKpi(@Param("cid") int cid, @Param("sid") int sid);

    @Select("SELECT l.lesson_no, l.title, l.lesson_date, COALESCE(a.status, '缺勤') AS status " +
            "FROM lessons l LEFT JOIN attendances a ON a.lesson_id = l.lesson_id AND a.student_id = #{sid} " +
            "WHERE l.course_id = #{cid} ORDER BY l.lesson_no")
    List<Map<String, Object>> studentAttendance(@Param("cid") int cid, @Param("sid") int sid);

    @Select("SELECT m.kp_name, m.kp_order, ROUND(100.0 * m.correct_rate, 1) AS mine, " +
            "       ROUND(100.0 * k.correct_rate, 1) AS cls " +
            "FROM v_student_kp_mastery m JOIN v_kp_mastery k ON k.kp_id = m.kp_id " +
            "WHERE m.course_id = #{cid} AND m.student_id = #{sid} ORDER BY m.kp_order")
    List<Map<String, Object>> studentKpCompare(@Param("cid") int cid, @Param("sid") int sid);

    @Select("SELECT a.title AS name, ROUND(s.score, 1) AS mine, " +
            " (SELECT ROUND(AVG(s2.score), 1) FROM submissions s2 " +
            "   WHERE s2.assign_id = a.assign_id AND s2.score IS NOT NULL) AS cls " +
            "FROM assignments a LEFT JOIN submissions s ON s.assign_id = a.assign_id AND s.student_id = #{sid} " +
            "WHERE a.course_id = #{cid} ORDER BY a.assigned_at")
    List<Map<String, Object>> studentScoreTrend(@Param("cid") int cid, @Param("sid") int sid);

    @Select("SELECT COUNT(*) + 1 FROM v_student_kpi WHERE course_id = #{cid} AND composite_score > #{score}")
    int studentRank(@Param("cid") int cid, @Param("score") java.math.BigDecimal score);

    @Select("SELECT COUNT(*) FROM v_student_kpi WHERE course_id = #{cid}")
    int studentTotal(@Param("cid") int cid);

    /* ---------------- 班级维度 ---------------- */

    @Select("SELECT cl.class_name AS name, COUNT(*) AS stu_cnt, " +
            " ROUND(100.0 * AVG(k.attendance_rate), 1) AS att_rate, " +
            " ROUND(100.0 * AVG(k.part_rate), 1)       AS part_rate, " +
            " ROUND(AVG(k.avg_score), 1)               AS avg_score, " +
            " ROUND(AVG(k.composite_score), 1)         AS composite " +
            "FROM v_student_kpi k JOIN users u ON u.user_id = k.student_id " +
            "JOIN classes cl ON cl.class_id = u.class_id " +
            "WHERE k.course_id = #{cid} GROUP BY cl.class_id, cl.class_name")
    List<Map<String, Object>> classSummary(@Param("cid") int cid);
}
