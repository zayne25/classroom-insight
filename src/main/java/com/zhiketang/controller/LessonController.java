package com.zhiketang.controller;

import com.zhiketang.common.BaseController;
import com.zhiketang.common.R;
import com.zhiketang.common.SessionUser;
import com.zhiketang.service.LessonService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 课堂接口：课时、签到、随堂答题
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class LessonController extends BaseController {

    private final LessonService lessonService;

    @GetMapping("/lessons")
    public R<List<Map<String, Object>>> list(@RequestParam("course_id") int cid) {
        return R.ok(lessonService.listByCourse(cid));
    }

    @PostMapping("/lessons")
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        SessionUser u = cur();
        requireRole("teacher", "admin");
        requirePerm("lesson:manage");
        int cid = ((Number) body.get("course_id")).intValue();
        String title = String.valueOf(body.getOrDefault("title", "")).trim();
        if (title.isBlank()) return R.fail("课时标题不能为空");
        int lid = lessonService.create(cid, u.getUserId(), title,
                str(body.get("lesson_date")), str(body.get("start_time")), str(body.get("end_time")));
        return R.ok(Map.of("lesson_id", lid), "课时创建成功");
    }

    @PostMapping("/lessons/{lid}/start")
    public R<Map<String, Object>> start(@PathVariable int lid) {
        SessionUser u = cur();
        requireRole("teacher", "admin");
        requirePerm("lesson:manage");
        String code = lessonService.start(lid, u.getUserId());
        return R.ok(Map.of("lesson_id", lid, "checkin_code", code), "课堂已开始，签到码已生成");
    }

    @PostMapping("/lessons/{lid}/end")
    public R<Void> end(@PathVariable int lid) {
        SessionUser u = cur();
        requireRole("teacher", "admin");
        requirePerm("lesson:manage");
        lessonService.end(lid, u.getUserId());
        return R.ok(null, "课堂已结束");
    }

    @PostMapping("/attendances")
    public R<Void> checkin(@RequestBody Map<String, Object> body) {
        SessionUser u = cur();
        requireRole("student");
        int lid = ((Number) body.get("lesson_id")).intValue();
        String code = String.valueOf(body.getOrDefault("code", "")).trim();
        if (code.isBlank()) return R.fail("签到码不能为空");
        lessonService.checkin(lid, u.getUserId(), code);
        return R.ok(null, "签到成功");
    }

    @GetMapping("/lessons/{lid}/attendance")
    public R<Map<String, Object>> attendance(@PathVariable int lid) {
        return R.ok(lessonService.attendanceDetail(lid));
    }

    @GetMapping("/lessons/{lid}/live")
    public R<Map<String, Object>> live(@PathVariable int lid) {
        return R.ok(lessonService.live(lid));
    }

    @GetMapping("/lessons/{lid}/questions")
    public R<List<Map<String, Object>>> questions(@PathVariable int lid) {
        requireRole("student");
        return R.ok(lessonService.randomQuestions(lid, 5));
    }

    @PostMapping("/answers")
    public R<Map<String, Object>> answers(@RequestBody Map<String, Object> body) {
        SessionUser u = cur();
        requireRole("student");
        int lid = ((Number) body.get("lesson_id")).intValue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> answers = (List<Map<String, Object>>) body.get("answers");
        if (answers == null || answers.isEmpty()) return R.fail("缺少答题数据");
        Map<String, Object> res = lessonService.submitAnswers(lid, u.getUserId(), answers);
        return R.ok(res, "提交成功，答对 " + res.get("correct") + "/" + res.get("total"));
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
