package com.zhiketang.service;

import com.zhiketang.common.BizException;
import com.zhiketang.mapper.AssignmentMapper;
import com.zhiketang.mapper.CourseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 作业服务：发布、提交、批改
 */
@Service
@RequiredArgsConstructor
public class AssignmentService {

    private final AssignmentMapper assignmentMapper;
    private final CourseMapper courseMapper;

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public List<Map<String, Object>> listByCourse(int cid) {
        return assignmentMapper.listByCourse(cid);
    }

    public int create(int cid, int teacherId, String title, String description, String deadline, String type) {
        if (courseMapper.countOwned(cid, teacherId) == 0) throw new BizException("无权在该课程下发布作业", 403);
        String assignType = normalizeType(type);
        Map<String, Object> r = new HashMap<>();
        r.put("course_id", cid);
        r.put("title", title);
        r.put("description", description == null ? "" : description);
        r.put("assign_type", assignType);
        // 未指定评分标准时给出该类型的默认标准，保证 AI 批改可直接运行
        r.put("ai_rubric", defaultRubric(assignType));
        r.put("total_score", 100);
        r.put("assigned_at", LocalDate.now().toString());
        r.put("deadline", deadline == null || deadline.isBlank()
                ? LocalDate.now().plusDays(7).toString() : deadline);
        assignmentMapper.insert(r);
        return ((Number) r.get("assign_id")).intValue();
    }

    /** 作业类型白名单，非法值回退为客观题 */
    private String normalizeType(String type) {
        if (type == null) return "客观题";
        String t = type.trim();
        for (String v : new String[]{"客观题", "主观题", "编程题", "作文"}) {
            if (v.equals(t)) return v;
        }
        return "客观题";
    }

    private String defaultRubric(String type) {
        return switch (type) {
            case "主观题" -> "{\"min_words\":150,\"keywords\":[\"概念\",\"原理\",\"步骤\",\"示例\",\"结论\"]}";
            case "作文" -> "{\"min_words\":400,\"keywords\":[\"概念\",\"原理\",\"步骤\",\"示例\",\"结论\"]}";
            case "编程题" -> "{\"cases\":5,\"required\":[\"def \",\"for \",\"if \",\"return\"]}";
            default -> "{\"count\":10}";
        };
    }

    public List<Map<String, Object>> submissions(int aid) {
        return assignmentMapper.submissions(aid);
    }

    public int submit(int aid, int studentId, String content) {
        Map<String, Object> a = assignmentMapper.findById(aid);
        if (a == null) throw new BizException("作业不存在");
        int cid = ((Number) a.get("course_id")).intValue();
        if (courseMapper.countEnrolled(cid, studentId) == 0) throw new BizException("未选修该课程", 403);
        if (assignmentMapper.countSubmitted(aid, studentId) > 0) throw new BizException("该作业已提交，请勿重复提交");

        Map<String, Object> r = new HashMap<>();
        r.put("assign_id", aid);
        r.put("student_id", studentId);
        r.put("content", content == null ? "" : content);
        r.put("submitted_at", LocalDateTime.now().format(DTF));
        assignmentMapper.insertSubmission(r);
        return cid;
    }

    /** 批改：校验分数区间与课程归属 */
    public void grade(int subId, BigDecimal score, int teacherId) {
        if (score == null) throw new BizException("缺少分数");
        if (score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(new BigDecimal("100")) > 0) {
            throw new BizException("分数需在 0~100 之间");
        }
        Map<String, Object> c = assignmentMapper.courseOfSubmission(subId);
        if (c == null) throw new BizException("提交记录不存在");
        int cid = ((Number) c.get("course_id")).intValue();
        if (courseMapper.countOwned(cid, teacherId) == 0) throw new BizException("无权批改该作业", 403);
        assignmentMapper.updateScore(subId, score, cid);
    }
}
