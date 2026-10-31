package com.zhiketang.controller;

import com.zhiketang.common.BaseController;
import com.zhiketang.common.R;
import com.zhiketang.common.SessionUser;
import com.zhiketang.service.AssignmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 作业接口
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AssignmentController extends BaseController {

    private final AssignmentService assignmentService;

    @GetMapping("/assignments")
    public R<List<Map<String, Object>>> list(@RequestParam("course_id") int cid) {
        return R.ok(assignmentService.listByCourse(cid));
    }

    @PostMapping("/assignments")
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        SessionUser u = cur();
        requireRole("teacher", "admin");
        requirePerm("assignment:manage");
        int cid = ((Number) body.get("course_id")).intValue();
        String title = String.valueOf(body.getOrDefault("title", "")).trim();
        if (title.isBlank()) return R.fail("作业标题不能为空");
        Object typeObj = body.get("assign_type") != null ? body.get("assign_type") : body.get("type");
        int aid = assignmentService.create(cid, u.getUserId(), title,
                str(body.get("description")), str(body.get("deadline")), str(typeObj));
        return R.ok(Map.of("assign_id", aid), "作业发布成功");
    }

    @GetMapping("/assignments/{aid}/submissions")
    public R<List<Map<String, Object>>> submissions(@PathVariable int aid) {
        return R.ok(assignmentService.submissions(aid));
    }

    @PostMapping("/submissions")
    public R<Void> submit(@RequestBody Map<String, Object> body) {
        SessionUser u = cur();
        requireRole("student");
        int aid = ((Number) body.get("assign_id")).intValue();
        assignmentService.submit(aid, u.getUserId(), str(body.get("content")));
        return R.ok(null, "提交成功");
    }

    @PutMapping("/submissions/{subId}/score")
    public R<Void> grade(@PathVariable int subId, @RequestBody Map<String, Object> body) {
        SessionUser u = cur();
        requireRole("teacher", "admin");
        requirePerm("assignment:manage");
        Object s = body.get("score");
        if (s == null) return R.fail("缺少分数");
        BigDecimal score;
        try {
            score = new BigDecimal(String.valueOf(s));
        } catch (NumberFormatException e) {
            return R.fail("分数格式不正确");
        }
        assignmentService.grade(subId, score, u.getUserId());
        return R.ok(null, "批改成功");
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
