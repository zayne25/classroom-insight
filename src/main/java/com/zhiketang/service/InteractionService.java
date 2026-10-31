package com.zhiketang.service;

import com.zhiketang.common.BizException;
import com.zhiketang.mapper.CourseMapper;
import com.zhiketang.mapper.InteractionMapper;
import com.zhiketang.mapper.LessonMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 课堂互动服务：发布抢答 / 随机点名 / 投票 / 提问，学生响应与实时统计
 */
@Service
@RequiredArgsConstructor
public class InteractionService {

    private final InteractionMapper interactionMapper;
    private final LessonMapper lessonMapper;
    private final CourseMapper courseMapper;

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String LETTERS = "ABCD";

    /** 发布互动；抢答与随机点名可判对错，投票与提问只计参与 */
    public int create(int lessonId, int teacherId, String type, String title,
                      String content, List<String> options, String correctAnswer) {
        Map<String, Object> l = lessonMapper.findById(lessonId);
        if (l == null) throw new BizException("课时不存在");
        int cid = toInt(l.get("course_id"));
        if (courseMapper.countOwned(cid, teacherId) == 0) throw new BizException("无权操作该课时", 403);

        boolean gradeable = "抢答".equals(type) || "随机点名".equals(type);

        String opts = null;
        if (options != null && !options.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < options.size() && i < 4; i++) {
                if (i > 0) sb.append('|');
                sb.append(LETTERS.charAt(i)).append(". ").append(options.get(i));
            }
            opts = sb.toString();
        }

        Map<String, Object> r = new HashMap<>();
        r.put("lesson_id", lessonId);
        r.put("type", type);
        r.put("title", title);
        r.put("content", content == null ? "" : content);
        r.put("options_json", opts);
        r.put("correct_answer", gradeable ? correctAnswer : null);
        r.put("gradeable", gradeable ? 1 : 0);
        r.put("score", gradeable ? 2 : 1);
        r.put("status", "已发布");
        r.put("published_at", LocalDateTime.now().format(DTF));
        interactionMapper.insert(r);
        return toInt(r.get("inter_id"));
    }

    public void close(int iid) {
        interactionMapper.updateStatus(iid, "已结束");
    }

    /** 学生参与互动 */
    public Map<String, Object> respond(int iid, int studentId, String answer) {
        Map<String, Object> i = interactionMapper.findById(iid);
        if (i == null) throw new BizException("互动不存在");
        if (!"已发布".equals(String.valueOf(i.get("status")))) throw new BizException("该互动已结束");

        int cid = toInt(i.get("course_id"));
        if (courseMapper.countEnrolled(cid, studentId) == 0) throw new BizException("未选修该课程", 403);
        if (interactionMapper.countResponded(iid, studentId) > 0) throw new BizException("已参与过该互动");

        boolean ok = false;
        int got;
        if (toInt(i.get("gradeable")) == 1) {
            String std = String.valueOf(i.getOrDefault("correct_answer", ""));
            ok = std.trim().equalsIgnoreCase(String.valueOf(answer).trim());
            got = ok ? toInt(i.get("score")) : 0;
        } else {
            got = toInt(i.get("score"));
        }

        Map<String, Object> r = new HashMap<>();
        r.put("inter_id", iid);
        r.put("student_id", studentId);
        r.put("answer", answer);
        r.put("is_correct", ok ? 1 : 0);
        r.put("score", got);
        r.put("responded_at", LocalDateTime.now().format(DTF));
        interactionMapper.insertRecord(r);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("is_correct", ok ? 1 : 0);
        out.put("score", got);
        return out;
    }

    /** 某课时的互动列表（附带参与率 / 正确率 / 我的作答） */
    public List<Map<String, Object>> listByLesson(int lid, Integer studentId) {
        List<Map<String, Object>> list = interactionMapper.listByLesson(lid);
        for (Map<String, Object> i : list) {
            int total = toInt(i.get("total_stu"));
            int resp = toInt(i.get("respond_cnt"));
            int corr = toInt(i.get("correct_cnt"));
            i.put("respond_rate", total == 0 ? 0 : Math.round(1000.0 * resp / total) / 10.0);
            i.put("correct_rate", resp == 0 ? 0 : Math.round(1000.0 * corr / resp) / 10.0);
            if (studentId != null) {
                i.put("mine", interactionMapper.findMine(toInt(i.get("inter_id")), studentId));
            }
        }
        return list;
    }

    public Map<String, Object> stats(int iid) {
        Map<String, Object> s = interactionMapper.statById(iid);
        if (s == null) throw new BizException("互动不存在");
        int total = toInt(s.get("total_stu"));
        int resp = toInt(s.get("respond_cnt"));
        int corr = toInt(s.get("correct_cnt"));
        s.put("respond_rate", total == 0 ? 0 : Math.round(1000.0 * resp / total) / 10.0);
        s.put("correct_rate", resp == 0 ? 0 : Math.round(1000.0 * corr / resp) / 10.0);
        return s;
    }

    private int toInt(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }
}
