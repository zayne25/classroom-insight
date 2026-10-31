package com.zhiketang.controller;

import com.zhiketang.common.BaseController;
import com.zhiketang.common.R;
import com.zhiketang.common.SessionUser;
import com.zhiketang.service.InteractionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 课堂互动接口
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class InteractionController extends BaseController {

    private final InteractionService interactionService;

    @PostMapping("/interactions")
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        SessionUser u = cur();
        requireRole("teacher", "admin");
        requirePerm("interaction:manage");
        int lessonId = ((Number) body.get("lesson_id")).intValue();
        String type = String.valueOf(body.getOrDefault("type", "抢答"));
        String title = String.valueOf(body.getOrDefault("title", "")).trim();
        if (title.isBlank()) return R.fail("互动标题不能为空");

        @SuppressWarnings("unchecked")
        List<String> options = (List<String>) body.get("options");
        String answer = body.get("correct_answer") == null ? null : String.valueOf(body.get("correct_answer"));

        int iid = interactionService.create(lessonId, u.getUserId(), type, title,
                str(body.get("content")), options, answer);
        return R.ok(Map.of("inter_id", iid), "互动已发布");
    }

    @PostMapping("/interactions/{iid}/close")
    public R<Void> close(@PathVariable int iid) {
        requireRole("teacher", "admin");
        requirePerm("interaction:manage");
        interactionService.close(iid);
        return R.ok(null, "互动已结束");
    }

    @PostMapping("/interactions/{iid}/respond")
    public R<Map<String, Object>> respond(@PathVariable int iid, @RequestBody Map<String, Object> body) {
        SessionUser u = cur();
        requireRole("student");
        String answer = String.valueOf(body.getOrDefault("answer", "")).trim();
        if (answer.isBlank()) return R.fail("请作答后再提交");
        return R.ok(interactionService.respond(iid, u.getUserId(), answer), "参与成功");
    }

    @GetMapping("/interactions/{iid}/stats")
    public R<Map<String, Object>> stats(@PathVariable int iid) {
        return R.ok(interactionService.stats(iid));
    }

    @GetMapping("/lessons/{lid}/interactions")
    public R<List<Map<String, Object>>> listByLesson(@PathVariable int lid) {
        SessionUser u = cur();
        Integer sid = u.isStudent() ? u.getUserId() : null;
        return R.ok(interactionService.listByLesson(lid, sid));
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
