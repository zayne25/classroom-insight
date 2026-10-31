package com.zhiketang.service;

import com.zhiketang.common.BizException;
import com.zhiketang.mapper.CourseMapper;
import com.zhiketang.mapper.InteractionMapper;
import com.zhiketang.mapper.LessonMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 课堂服务：课时管理、签到、随机答题
 */
@Service
@RequiredArgsConstructor
public class LessonService {

    private final LessonMapper lessonMapper;
    private final CourseMapper courseMapper;
    private final InteractionMapper interactionMapper;

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public List<Map<String, Object>> listByCourse(int cid) {
        return lessonMapper.listByCourse(cid);
    }

    public Map<String, Object> findById(int lid) {
        Map<String, Object> l = lessonMapper.findById(lid);
        if (l == null) throw new BizException("课时不存在");
        return l;
    }

    /** 创建课时（校验课程归属） */
    public int create(int cid, int teacherId, String title, String date, String start, String end) {
        if (courseMapper.countOwned(cid, teacherId) == 0) throw new BizException("无权在该课程下创建课时", 403);
        int no = lessonMapper.maxLessonNo(cid) + 1;
        Map<String, Object> r = new HashMap<>();
        r.put("course_id", cid);
        r.put("lesson_no", no);
        r.put("title", title);
        r.put("lesson_date", date == null || date.isBlank() ? LocalDate.now().toString() : date);
        r.put("start_time", start == null || start.isBlank() ? "08:00" : start);
        r.put("end_time", end == null || end.isBlank() ? "09:40" : end);
        r.put("status", "未开始");
        lessonMapper.insert(r);
        return ((Number) r.get("lesson_id")).intValue();
    }

    /** 开始课堂：生成 4 位签到码 */
    public String start(int lid, int teacherId) {
        Map<String, Object> l = findById(lid);
        checkOwned(toInt(l.get("course_id")), teacherId);
        String code = String.format("%04d", new Random().nextInt(10000));
        lessonMapper.start(lid, code);
        return code;
    }

    public void end(int lid, int teacherId) {
        Map<String, Object> l = findById(lid);
        checkOwned(toInt(l.get("course_id")), teacherId);
        lessonMapper.end(lid);
    }

    /**
     * 学生签到：校验签到码 + 唯一约束防重复
     */
    public void checkin(int lid, int studentId, String code) {
        Map<String, Object> l = findById(lid);
        if (!"进行中".equals(String.valueOf(l.get("status")))) {
            throw new BizException("该课堂当前未开放签到");
        }
        if (!Objects.equals(String.valueOf(l.get("checkin_code")), code)) {
            throw new BizException("签到码错误");
        }
        int cid = toInt(l.get("course_id"));
        if (courseMapper.countEnrolled(cid, studentId) == 0) {
            throw new BizException("未选修该课程", 403);
        }
        if (lessonMapper.countChecked(lid, studentId) > 0) {
            throw new BizException("本次课堂已签到");
        }

        String now = LocalDateTime.now().format(DTF);
        Map<String, Object> r = new HashMap<>();
        r.put("lesson_id", lid);
        r.put("student_id", studentId);
        r.put("status", "正常");
        r.put("method", "签到码");
        r.put("check_in_time", now);
        lessonMapper.insertAttendance(r);

        Map<String, Object> ev = new HashMap<>();
        ev.put("student_id", studentId);
        ev.put("course_id", cid);
        ev.put("lesson_id", lid);
        ev.put("event_type", "进入课堂");
        ev.put("event_time", now);
        lessonMapper.insertEvent(ev);
    }

    /** 课时出勤明细 + 统计 */
    public Map<String, Object> attendanceDetail(int lid) {
        Map<String, Object> l = findById(lid);
        int cid = toInt(l.get("course_id"));
        List<Map<String, Object>> rows = lessonMapper.attendanceRows(lid, cid);

        Map<String, Integer> stat = new LinkedHashMap<>();
        stat.put("正常", 0);
        stat.put("迟到", 0);
        stat.put("请假", 0);
        stat.put("缺勤", 0);
        stat.put("未签到", 0);
        for (Map<String, Object> r : rows) {
            String s = String.valueOf(r.get("status"));
            stat.merge(s, 1, Integer::sum);
        }
        int total = rows.size();
        int attended = stat.get("正常") + stat.get("迟到") + stat.get("请假");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lesson", l);
        out.put("rows", rows);
        out.put("stat", stat);
        out.put("total", total);
        out.put("attended", attended);
        out.put("rate", total == 0 ? 0 : Math.round(1000.0 * attended / total) / 10.0);
        return out;
    }

    /** 课堂实时看板：出勤 + 各互动实时统计 */
    public Map<String, Object> live(int lid) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lesson", findById(lid));
        out.put("attendance", attendanceDetail(lid));

        List<Map<String, Object>> inters = interactionMapper.listByLesson(lid);
        for (Map<String, Object> i : inters) {
            int total = toInt(i.get("total_stu"));
            int resp = toInt(i.get("respond_cnt"));
            int corr = toInt(i.get("correct_cnt"));
            i.put("respond_rate", total == 0 ? 0 : Math.round(1000.0 * resp / total) / 10.0);
            i.put("correct_rate", resp == 0 ? 0 : Math.round(1000.0 * corr / resp) / 10.0);
        }
        out.put("interactions", inters);
        return out;
    }

    /** 随机抽题（不返回标准答案） */
    public List<Map<String, Object>> randomQuestions(int lid, int n) {
        Map<String, Object> l = findById(lid);
        List<Map<String, Object>> qs = lessonMapper.randomQuestions(toInt(l.get("course_id")), n);
        for (Map<String, Object> q : qs) q.remove("answer");
        return qs;
    }

    /** 提交随堂答题 */
    public Map<String, Object> submitAnswers(int lid, int studentId, List<Map<String, Object>> answers) {
        Map<String, Object> l = findById(lid);
        int cid = toInt(l.get("course_id"));
        if (courseMapper.countEnrolled(cid, studentId) == 0) {
            throw new BizException("未选修该课程", 403);
        }
        String now = LocalDateTime.now().format(DTF);
        int correct = 0;
        for (Map<String, Object> a : answers) {
            Object qid = a.get("q_id");
            if (qid == null) continue;
            String ans = String.valueOf(a.getOrDefault("answer", "")).trim().toUpperCase();
            String std = lessonMapper.findAnswer(toInt(qid));
            boolean ok = std != null && std.trim().toUpperCase().equals(ans);
            if (ok) correct++;
            Map<String, Object> r = new HashMap<>();
            r.put("q_id", toInt(qid));
            r.put("student_id", studentId);
            r.put("lesson_id", lid);
            r.put("is_correct", ok ? 1 : 0);
            r.put("answered_at", now);
            lessonMapper.insertAnswerRecord(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", answers.size());
        out.put("correct", correct);
        return out;
    }

    private void checkOwned(int cid, int teacherId) {
        if (courseMapper.countOwned(cid, teacherId) == 0) throw new BizException("无权操作该课时", 403);
    }

    private int toInt(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }
}
