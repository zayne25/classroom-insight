package com.zhiketang.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/**
 * 课堂互动与互动记录数据访问
 */
@Mapper
public interface InteractionMapper {

    /** 某课时的全部互动及其统计（来自视图） */
    @Select("SELECT * FROM v_interaction_stat WHERE lesson_id = #{lid} ORDER BY inter_id")
    List<Map<String, Object>> listByLesson(@Param("lid") int lid);

    @Select("SELECT i.*, l.course_id FROM interactions i JOIN lessons l ON l.lesson_id = i.lesson_id " +
            "WHERE i.inter_id = #{iid}")
    Map<String, Object> findById(@Param("iid") int iid);

    @Select("SELECT * FROM v_interaction_stat WHERE inter_id = #{iid}")
    Map<String, Object> statById(@Param("iid") int iid);

    @Insert("INSERT INTO interactions(lesson_id, type, title, content, options_json, correct_answer, " +
            " gradeable, score, status, published_at) " +
            "VALUES(#{r.lesson_id}, #{r.type}, #{r.title}, #{r.content}, #{r.options_json}, #{r.correct_answer}, " +
            " #{r.gradeable}, #{r.score}, #{r.status}, #{r.published_at})")
    @Options(useGeneratedKeys = true, keyProperty = "r.inter_id")
    int insert(@Param("r") Map<String, Object> r);

    @Insert("<script>INSERT INTO interactions(lesson_id, type, title, content, options_json, correct_answer, " +
            " gradeable, score, status, published_at) VALUES " +
            "<foreach collection='list' item='r' separator=','>" +
            "(#{r.lesson_id}, #{r.type}, #{r.title}, #{r.content}, #{r.options_json}, #{r.correct_answer}, " +
            " #{r.gradeable}, #{r.score}, #{r.status}, #{r.published_at})" +
            "</foreach></script>")
    int batchInsert(@Param("list") List<Map<String, Object>> list);

    @Update("UPDATE interactions SET status = #{status} WHERE inter_id = #{iid}")
    int updateStatus(@Param("iid") int iid, @Param("status") String status);

    /* ---------------- 互动记录 ---------------- */

    @Select("SELECT COUNT(*) FROM interaction_records WHERE inter_id = #{iid} AND student_id = #{sid}")
    int countResponded(@Param("iid") int iid, @Param("sid") int sid);

    @Insert("INSERT INTO interaction_records(inter_id, student_id, answer, is_correct, score, responded_at) " +
            "VALUES(#{r.inter_id}, #{r.student_id}, #{r.answer}, #{r.is_correct}, #{r.score}, #{r.responded_at})")
    int insertRecord(@Param("r") Map<String, Object> r);

    @Insert("<script>INSERT INTO interaction_records(inter_id, student_id, answer, is_correct, score, responded_at) VALUES " +
            "<foreach collection='list' item='r' separator=','>" +
            "(#{r.inter_id}, #{r.student_id}, #{r.answer}, #{r.is_correct}, #{r.score}, #{r.responded_at})" +
            "</foreach></script>")
    int batchInsertRecords(@Param("list") List<Map<String, Object>> list);

    @Select("SELECT answer, is_correct, score FROM interaction_records " +
            "WHERE inter_id = #{iid} AND student_id = #{sid}")
    Map<String, Object> findMine(@Param("iid") int iid, @Param("sid") int sid);
}
