package com.zhiketang.controller;

import com.zhiketang.common.BaseController;
import com.zhiketang.common.SessionUser;
import com.zhiketang.interceptor.LoginInterceptor;
import com.zhiketang.mapper.AssignmentMapper;
import com.zhiketang.mapper.CourseMapper;
import com.zhiketang.mapper.LessonMapper;
import com.zhiketang.service.AnalyticsService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * 页面路由（Thymeleaf 服务端渲染，全部继承统一布局 base.html）
 */
@Controller
@RequiredArgsConstructor
public class PageController extends BaseController {

    private final CourseMapper courseMapper;
    private final LessonMapper lessonMapper;
    private final AssignmentMapper assignmentMapper;
    private final AnalyticsService analyticsService;

    @GetMapping("/")
    public String index() {
        SessionUser u = cur();
        if (u.isStudent()) return "redirect:/student/courses";
        if ("admin".equals(u.getRole())) return "redirect:/admin/monitor";
        return "redirect:/teacher/dashboard";
    }

    @GetMapping("/login")
    public String login(HttpSession session) {
        if (session.getAttribute(LoginInterceptor.SESSION_KEY) != null) return "redirect:/";
        return "login";
    }

    /* ------------------------- 教师端 ------------------------- */

    @GetMapping("/teacher/dashboard")
    public String dashboard(Model model) {
        String denied = requirePageRole("teacher", "admin");
        if (denied != null) return denied;

        List<Map<String, Object>> courses = courseMapper.listByTeacher(cur().getUserId());
        for (Map<String, Object> c : courses) {
            int cid = intv(c.get("course_id"));
            Map<String, Object> s = analyticsService.courseCardStats(cid);
            c.put("avg_att", s.get("avg_att"));
            c.put("avg_score", s.get("avg_score"));
            c.put("risk_cnt", s.get("risk_cnt"));
        }
        // 顶部指标卡的聚合值在服务端算好，模板只负责展示
        int totalStu = 0, totalRisk = 0;
        double attSum = 0;
        for (Map<String, Object> c : courses) {
            totalStu += intv(c.get("stu_cnt"));
            totalRisk += intv(c.get("risk_cnt"));
            attSum += c.get("avg_att") == null ? 0 : ((Number) c.get("avg_att")).doubleValue();
        }
        model.addAttribute("courses", courses);
        model.addAttribute("totalStu", totalStu);
        model.addAttribute("totalRisk", totalRisk);
        model.addAttribute("avgAtt", courses.isEmpty() ? 0 : Math.round(attSum / courses.size() * 10) / 10.0);
        model.addAttribute("active", "dashboard");
        return "teacher/dashboard";
    }

    @GetMapping("/teacher/lessons")
    public String lessons(Model model, @RequestParam(value = "course_id", required = false) Integer cid) {
        String denied = requirePageRole("teacher", "admin");
        if (denied != null) return denied;

        List<Map<String, Object>> courses = courseMapper.listByTeacher(cur().getUserId());
        int current = pickCid(courses, cid);
        model.addAttribute("courses", courses);
        model.addAttribute("currentCid", current);
        model.addAttribute("lessons", current == 0 ? List.of() : lessonMapper.listByCourse(current));
        model.addAttribute("active", "lessons");
        return "teacher/lessons";
    }

    @GetMapping("/teacher/live/{lid}")
    public String live(Model model, @PathVariable int lid) {
        String denied = requirePageRole("teacher", "admin");
        if (denied != null) return denied;

        Map<String, Object> lesson = lessonMapper.findById(lid);
        if (lesson == null) return "redirect:/teacher/lessons";
        model.addAttribute("lesson", lesson);
        model.addAttribute("course", courseMapper.findById(intv(lesson.get("course_id"))));
        model.addAttribute("active", "lessons");
        return "teacher/live";
    }

    @GetMapping("/teacher/interactions")
    public String interactions(Model model, @RequestParam(value = "course_id", required = false) Integer cid) {
        String denied = requirePageRole("teacher", "admin");
        if (denied != null) return denied;

        List<Map<String, Object>> courses = courseMapper.listByTeacher(cur().getUserId());
        int current = pickCid(courses, cid);
        model.addAttribute("courses", courses);
        model.addAttribute("currentCid", current);
        model.addAttribute("lessons", current == 0 ? List.of() : lessonMapper.listByCourse(current));
        model.addAttribute("active", "interactions");
        return "teacher/interactions";
    }

    @GetMapping("/teacher/assignments")
    public String assignments(Model model, @RequestParam(value = "course_id", required = false) Integer cid) {
        String denied = requirePageRole("teacher", "admin");
        if (denied != null) return denied;

        List<Map<String, Object>> courses = courseMapper.listByTeacher(cur().getUserId());
        int current = pickCid(courses, cid);
        model.addAttribute("courses", courses);
        model.addAttribute("currentCid", current);
        model.addAttribute("assigns", current == 0 ? List.of() : assignmentMapper.listByCourse(current));
        model.addAttribute("active", "assignments");
        return "teacher/assignments";
    }

    @GetMapping("/teacher/analytics")
    public String analytics(Model model, @RequestParam(value = "course_id", required = false) Integer cid) {
        String denied = requirePageRole("teacher", "admin");
        if (denied != null) return denied;

        List<Map<String, Object>> courses = courseMapper.listByTeacher(cur().getUserId());
        model.addAttribute("courses", courses);
        model.addAttribute("currentCid", pickCid(courses, cid));
        model.addAttribute("active", "analytics");
        return "teacher/analytics";
    }

    @GetMapping("/teacher/students")
    public String students(Model model, @RequestParam(value = "course_id", required = false) Integer cid) {
        String denied = requirePageRole("teacher", "admin");
        if (denied != null) return denied;

        List<Map<String, Object>> courses = courseMapper.listByTeacher(cur().getUserId());
        model.addAttribute("courses", courses);
        model.addAttribute("currentCid", pickCid(courses, cid));
        model.addAttribute("active", "students");
        return "teacher/students";
    }

    /* ------------------------- 学生端 ------------------------- */

    @GetMapping("/student/courses")
    public String studentCourses(Model model) {
        String denied = requirePageRole("student");
        if (denied != null) return denied;

        int uid = cur().getUserId();
        List<Map<String, Object>> courses = courseMapper.listByStudent(uid);
        for (Map<String, Object> c : courses) {
            Map<String, Object> kpi = analyticsService.studentKpi(intv(c.get("course_id")), uid);
            if (kpi != null) {
                // 出勤率在库中以 0~1 的比例存储，这里换算成百分比供页面展示
                kpi.put("attendance_pct", Math.round(dbl(kpi.get("attendance_rate")) * 1000) / 10.0);
            }
            c.put("kpi", kpi);
        }
        model.addAttribute("courses", courses);
        model.addAttribute("active", "courses");
        return "student/courses";
    }

    @GetMapping("/student/lesson/{lid}")
    public String studentLesson(Model model, @PathVariable int lid) {
        String denied = requirePageRole("student");
        if (denied != null) return denied;

        Map<String, Object> lesson = lessonMapper.findById(lid);
        if (lesson == null) return "redirect:/student/courses";
        model.addAttribute("lesson", lesson);
        model.addAttribute("course", courseMapper.findById(intv(lesson.get("course_id"))));
        model.addAttribute("active", "courses");
        return "student/lesson";
    }

    @GetMapping("/student/report")
    public String studentReport(Model model, @RequestParam(value = "course_id", required = false) Integer cid) {
        String denied = requirePageRole("student");
        if (denied != null) return denied;

        List<Map<String, Object>> courses = courseMapper.listByStudent(cur().getUserId());
        model.addAttribute("courses", courses);
        model.addAttribute("currentCid", pickCid(courses, cid));
        model.addAttribute("active", "report");
        return "student/report";
    }

    /* ------------------------- 工具 ------------------------- */

    /** 从查询串取 course_id，未指定或不合法时取第一个（页面级课程切换） */
    private int pickCid(List<Map<String, Object>> courses, Integer cid) {
        if (cid != null) {
            for (Map<String, Object> c : courses) {
                if (intv(c.get("course_id")) == cid) return cid;
            }
        }
        return courses.isEmpty() ? 0 : intv(courses.get(0).get("course_id"));
    }

    private int intv(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }

    private double dbl(Object o) {
        return o == null ? 0 : ((Number) o).doubleValue();
    }
}
