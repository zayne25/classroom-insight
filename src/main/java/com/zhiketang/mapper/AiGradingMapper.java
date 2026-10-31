package com.zhiketang.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/**
 * AI 批改结果数据访问
 */
@Mapper
public interface AiGradingMapper {

    @Select("SELECT * FROM ai_grading_results WHERE sub_id = #{subId}")
    Map<String, Object> findBySub(@Param("subId") int subId);

    @Select("SELECT * FROM ai_grading_results WHERE result_id = #{id}")
    Map<String, Object> findById(@Param("id") int id);

    /** 作业的全部批改结果（含学生信息与提交内容） */
    @Select("SELECT g.result_id, g.sub_id, g.student_id, g.assign_type, g.engine, g.model, " +
            "g.ai_score, g.max_score, g.confidence, g.comment, g.detail_json, g.cost_ms, " +
            "g.review_status, g.final_score, g.teacher_comment, g.reviewed_by, g.reviewed_at, g.created_at, " +
            "u.username, u.real_name, s.content " +
            "FROM ai_grading_results g " +
            "JOIN users u ON u.user_id = g.student_id " +
            "JOIN submissions s ON s.sub_id = g.sub_id " +
            "WHERE g.assign_id = #{aid} ORDER BY u.username")
    List<Map<String, Object>> listByAssignment(@Param("aid") int aid);

    /**
     * 写入批改结果。一份提交仅保留一条（uk_ai_sub），重复批改时覆盖并重置为「待复核」，
     * 保证 AI 初评与教师复核的对应关系始终清晰。
     */
    @Insert("INSERT INTO ai_grading_results(sub_id, assign_id, student_id, assign_type, engine, model, " +
            "ai_score, max_score, confidence, comment, detail_json, cost_ms, review_status, created_at) " +
            "VALUES(#{r.sub_id}, #{r.assign_id}, #{r.student_id}, #{r.assign_type}, #{r.engine}, #{r.model}, " +
            "#{r.ai_score}, #{r.max_score}, #{r.confidence}, #{r.comment}, #{r.detail_json}, #{r.cost_ms}, " +
            "'待复核', #{r.created_at}) " +
            "ON DUPLICATE KEY UPDATE " +
            "assign_type = VALUES(assign_type), engine = VALUES(engine), model = VALUES(model), " +
            "ai_score = VALUES(ai_score), max_score = VALUES(max_score), confidence = VALUES(confidence), " +
            "comment = VALUES(comment), detail_json = VALUES(detail_json), cost_ms = VALUES(cost_ms), " +
            "review_status = '待复核', final_score = NULL, teacher_comment = NULL, " +
            "reviewed_by = NULL, reviewed_at = NULL, created_at = VALUES(created_at)")
    @Options(useGeneratedKeys = true, keyProperty = "r.result_id")
    int upsert(@Param("r") Map<String, Object> r);

    /** 教师复核：写入最终分与评语，并记录复核人与时间（形成审计轨迹） */
    @Update("UPDATE ai_grading_results SET review_status = #{status}, final_score = #{score}, " +
            "teacher_comment = #{comment}, reviewed_by = #{uid}, reviewed_at = #{time} " +
            "WHERE result_id = #{id}")
    int review(@Param("id") int id, @Param("status") String status,
               @Param("score") java.math.BigDecimal score, @Param("comment") String comment,
               @Param("uid") int uid, @Param("time") String time);

    /** 复核结果同步回提交表的正式成绩 */
    @Update("UPDATE submissions SET score = #{score} WHERE sub_id = #{subId}")
    int syncSubmissionScore(@Param("subId") int subId, @Param("score") java.math.BigDecimal score);

    @Select("SELECT q_id, answer, content FROM questions WHERE q_id = #{qid}")
    Map<String, Object> questionAnswer(@Param("qid") int qid);

    @Select("SELECT * FROM v_ai_grading_stat WHERE assign_id = #{aid}")
    Map<String, Object> statByAssignment(@Param("aid") int aid);

    @Select("SELECT g.confidence FROM ai_grading_results g WHERE g.assign_id = #{aid}")
    List<Object> confidences(@Param("aid") int aid);
}
