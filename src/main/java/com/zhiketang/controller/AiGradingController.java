package com.zhiketang.controller;

import com.zhiketang.common.BaseController;
import com.zhiketang.common.R;
import com.zhiketang.common.SessionUser;
import com.zhiketang.service.AiGradingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * AI 批改作业接口
 *
 * 权限约定：
 *   教师 / 管理员 —— 可触发批改、查看本作业全部结果、复核、导出、查看统计
 *   学生          —— 仅可查看本人提交的批改结果（分数、评语、逐项反馈）
 */
@RestController
@RequestMapping("/api/ai/grading")
@RequiredArgsConstructor
public class AiGradingController extends BaseController {

    private final AiGradingService aiGradingService;

    /** 触发一次批量批改（教师/管理员） */
    @PostMapping("/assignments/{aid}")
    public R<Map<String, Object>> grade(@PathVariable int aid) {
        SessionUser u = cur();
        requireRole("teacher", "admin");
        requirePerm("ai:grade");
        return R.ok(aiGradingService.gradeAssignment(aid, u.getUserId()), "批改完成");
    }

    /** 某作业的全部批改结果（教师/管理员） */
    @GetMapping("/assignments/{aid}")
    public R<List<Map<String, Object>>> results(@PathVariable int aid) {
        SessionUser u = cur();
        requireRole("teacher", "admin");
        return R.ok(aiGradingService.results(aid, u.getUserId()));
    }

    /** 批改统计：覆盖率、平均分、平均置信度、待复核量、AI与最终分偏差（教师/管理员） */
    @GetMapping("/assignments/{aid}/stat")
    public R<Map<String, Object>> stat(@PathVariable int aid) {
        SessionUser u = cur();
        requireRole("teacher", "admin");
        return R.ok(aiGradingService.stat(aid, u.getUserId()));
    }

    /** 导出批改结果 CSV（教师/管理员） */
    @GetMapping("/assignments/{aid}/export")
    public ResponseEntity<byte[]> export(@PathVariable int aid) {
        SessionUser u = cur();
        requireRole("teacher", "admin");
        byte[] data = aiGradingService.exportCsv(aid, u.getUserId());
        String filename = URLEncoder.encode("AI批改结果_" + aid + ".csv", StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .body(data);
    }

    /** 查看单份提交的批改结果（学生仅本人） */
    @GetMapping("/submissions/{subId}")
    public R<Map<String, Object>> resultOf(@PathVariable int subId) {
        SessionUser u = cur();
        requireRole("student", "teacher", "admin");
        return R.ok(aiGradingService.myResult(subId, u.getUserId()));
    }

    /** 教师复核：写入最终分与评语（教师/管理员） */
    @PutMapping("/{resultId}/review")
    public R<Void> review(@PathVariable int resultId, @RequestBody Map<String, Object> body) {
        SessionUser u = cur();
        requireRole("teacher", "admin");
        requirePerm("ai:grade");
        Object s = body.get("score");
        if (s == null) return R.fail("缺少复核分数");
        BigDecimal score;
        try {
            score = new BigDecimal(String.valueOf(s));
        } catch (NumberFormatException e) {
            return R.fail("分数格式不正确");
        }
        aiGradingService.review(resultId, score,
                body.get("comment") == null ? null : String.valueOf(body.get("comment")), u.getUserId());
        return R.ok(null, "复核完成");
    }
}
