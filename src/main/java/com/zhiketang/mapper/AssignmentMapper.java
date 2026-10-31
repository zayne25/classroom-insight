package com.zhiketang.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/**
 * 作业与提交数据访问
 */
@Mapper
public interface AssignmentMapper {

    @Select("SELECT a.*, " +
            " (SELECT COUNT(*) FROM submissions s WHERE s.assign_id = a.assign_id) AS sub_cnt, " +
            " (SELECT ROUND(AVG(s.score), 1) FROM submissions s WHERE s.assign_id = a.assign_id AND s.score IS NOT NULL) AS avg_score " +
            "FROM assignments a WHERE a.course_id = #{cid} ORDER BY a.assigned_at")
    List<Map<String, Object>> listByCourse(@Param("cid") int cid);

    @Select("SELECT * FROM assignments WHERE assign_id = #{aid}")
    Map<String, Object> findById(@Param("aid") int aid);

    @Insert("INSERT INTO assignments(course_id, title, description, assign_type, ai_rubric, total_score, assigned_at, deadline) " +
            "VALUES(#{r.course_id}, #{r.title}, #{r.description}, #{r.assign_type}, #{r.ai_rubric}, " +
            "#{r.total_score}, #{r.assigned_at}, #{r.deadline})")
    @Options(useGeneratedKeys = true, keyProperty = "r.assign_id")
    int insert(@Param("r") Map<String, Object> r);

    @Insert("<script>INSERT INTO assignments(course_id, title, description, assign_type, ai_rubric, total_score, assigned_at, deadline) VALUES " +
            "<foreach collection='list' item='r' separator=','>" +
            "(#{r.course_id}, #{r.title}, #{r.description}, #{r.assign_type}, #{r.ai_rubric}, " +
            "#{r.total_score}, #{r.assigned_at}, #{r.deadline})" +
            "</foreach></script>")
    int batchInsert(@Param("list") List<Map<String, Object>> list);

    @Select("SELECT s.*, u.username, u.real_name FROM submissions s " +
            "JOIN users u ON u.user_id = s.student_id WHERE s.assign_id = #{aid} ORDER BY u.username")
    List<Map<String, Object>> submissions(@Param("aid") int aid);

    @Select("SELECT COUNT(*) FROM submissions WHERE assign_id = #{aid} AND student_id = #{sid}")
    int countSubmitted(@Param("aid") int aid, @Param("sid") int sid);

    @Insert("INSERT INTO submissions(assign_id, student_id, content, submitted_at) " +
            "VALUES(#{r.assign_id}, #{r.student_id}, #{r.content}, #{r.submitted_at})")
    int insertSubmission(@Param("r") Map<String, Object> r);

    @Insert("<script>INSERT INTO submissions(assign_id, student_id, content, score, submitted_at) VALUES " +
            "<foreach collection='list' item='r' separator=','>" +
            "(#{r.assign_id}, #{r.student_id}, #{r.content}, #{r.score}, #{r.submitted_at})" +
            "</foreach></script>")
    int batchInsertSubmissions(@Param("list") List<Map<String, Object>> list);

    /** 批改：通过 sub_id 定位，并校验课程归属 */
    @Update("UPDATE submissions s JOIN assignments a ON a.assign_id = s.assign_id " +
            "SET s.score = #{score} WHERE s.sub_id = #{subId} AND a.course_id = #{cid}")
    int updateScore(@Param("subId") int subId, @Param("score") java.math.BigDecimal score, @Param("cid") int cid);

    @Select("SELECT a.course_id FROM submissions s JOIN assignments a ON a.assign_id = s.assign_id " +
            "WHERE s.sub_id = #{subId}")
    Map<String, Object> courseOfSubmission(@Param("subId") int subId);
}
