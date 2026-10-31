package com.zhiketang.controller;

import com.zhiketang.common.BaseController;
import com.zhiketang.common.R;
import com.zhiketang.common.SessionUser;
import com.zhiketang.mapper.CourseMapper;
import com.zhiketang.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 学情分析接口（图表数据源）
 */
@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController extends BaseController {

    private final AnalyticsService analyticsService;
    private final CourseMapper courseMapper;

    /** 权限校验：学生只能看自己选修的课程，教师只能看自己的课程 */
    private void checkAccess(int cid) {
        SessionUser u = cur();
        if (u.isStudent()) {
            if (courseMapper.countEnrolled(cid, u.getUserId()) == 0) {
                throw new com.zhiketang.common.BizException("无权访问该课程", 403);
            }
        } else if (courseMapper.countOwned(cid, u.getUserId()) == 0) {
            throw new com.zhiketang.common.BizException("无权访问该课程", 403);
        }
    }

    @GetMapping("/course/{cid}/overview")
    public R<Map<String, Object>> overview(@PathVariable int cid) {
        checkAccess(cid);
        return R.ok(analyticsService.overview(cid));
    }

    @GetMapping("/course/{cid}/attendance")
    public R<Map<String, Object>> attendance(@PathVariable int cid) {
        checkAccess(cid);
        return R.ok(analyticsService.attendance(cid));
    }

    @GetMapping("/course/{cid}/interaction")
    public R<Map<String, Object>> interaction(@PathVariable int cid) {
        checkAccess(cid);
        return R.ok(analyticsService.interaction(cid));
    }

    @GetMapping("/course/{cid}/score")
    public R<Map<String, Object>> score(@PathVariable int cid) {
        checkAccess(cid);
        return R.ok(analyticsService.score(cid));
    }

    @GetMapping("/course/{cid}/knowledge")
    public R<Map<String, Object>> knowledge(@PathVariable int cid) {
        checkAccess(cid);
        return R.ok(analyticsService.knowledge(cid));
    }

    @GetMapping("/course/{cid}/risk")
    public R<List<Map<String, Object>>> risk(@PathVariable int cid) {
        checkAccess(cid);
        return R.ok(analyticsService.risk(cid));
    }

    @GetMapping("/course/{cid}/correlation")
    public R<Map<String, Object>> correlation(@PathVariable int cid) {
        checkAccess(cid);
        return R.ok(analyticsService.correlation(cid));
    }

    @GetMapping("/course/{cid}/classes")
    public R<List<Map<String, Object>>> classSummary(@PathVariable int cid) {
        checkAccess(cid);
        return R.ok(analyticsService.classSummary(cid));
    }

    @GetMapping("/student")
    public R<Map<String, Object>> student(@RequestParam("course_id") int cid,
                                          @RequestParam(value = "student_id", required = false) Integer sid) {
        SessionUser u = cur();
        int studentId = u.isStudent() ? u.getUserId() : (sid == null ? 0 : sid);
        if (studentId == 0) return R.fail("缺少 student_id");
        checkAccess(cid);
        Map<String, Object> data = analyticsService.studentReport(cid, studentId);
        if (data == null) return R.fail("未找到该学生的学情数据");
        return R.ok(data);
    }
}
