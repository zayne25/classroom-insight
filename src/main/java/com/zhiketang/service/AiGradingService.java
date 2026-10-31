package com.zhiketang.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiketang.common.BizException;
import com.zhiketang.mapper.AiGradingMapper;
import com.zhiketang.mapper.AssignmentMapper;
import com.zhiketang.mapper.CourseMapper;
import com.zhiketang.service.ai.AiGradeResult;
import com.zhiketang.service.ai.AiProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * AI 批改作业服务
 *
 * 四类作业对应四种批改引擎，能力与局限均在结果中如实标注：
 * <pre>
 *   客观题  → RULE_OBJECTIVE  标准答案比对，规则判分，结果确定（置信度 1.0）
 *   编程题  → PROGRAM_JUDGE   必需结构静态检查（★ 静态检查不能替代运行，
 *                             接入判题沙箱后方可实跑测试用例）
 *   主观题  → LLM_SUBJECTIVE  本地启发式引擎 / 大模型（取决于配置）
 *   作文    → LLM_ESSAY       本地启发式引擎 / 大模型
 * </pre>
 * 所有结果一律先落库为「待复核」，由教师复核确认或调整后才写入正式成绩。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiGradingService {

    private final AiGradingMapper aiMapper;
    private final AssignmentMapper assignmentMapper;
    private final CourseMapper courseMapper;

    /** 两个批改提供者按名称注入：本地启发式（默认）与大模型（配置后启用） */
    @Autowired
    @Qualifier("localHeuristicAiProvider")
    private AiProvider localProvider;

    @Autowired
    @Qualifier("httpLlmAiProvider")
    private AiProvider httpProvider;

    @Value("${ai.grading.confidence-threshold:0.75}")
    private double confidenceThreshold;

    private final ObjectMapper om = new ObjectMapper();
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /* ============================================================ 批量批改 */

    public Map<String, Object> gradeAssignment(int aid, int teacherId) {
        Map<String, Object> a = assignmentMapper.findById(aid);
        if (a == null) throw new BizException("作业不存在");
        int cid = intv(a.get("course_id"));
        if (courseMapper.countOwned(cid, teacherId) == 0) throw new BizException("无权批改该作业", 403);

        List<Map<String, Object>> subs = assignmentMapper.submissions(aid);
        if (subs.isEmpty()) throw new BizException("该作业暂无提交，无需批改");

        long t0 = System.currentTimeMillis();
        String engine = null, model = null;
        int graded = 0, lowConfidence = 0;

        for (Map<String, Object> s : subs) {
            Map<String, Object> r = gradeOne(a, s, cid);
            graded++;
            if (engine == null) {
                engine = String.valueOf(r.get("engine"));
                model = String.valueOf(r.get("model"));
            }
            if (dbl(r.get("confidence")) < confidenceThreshold) lowConfidence++;
        }

        Map<String, Object> out = new HashMap<>();
        out.put("assign_id", aid);
        out.put("assign_type", a.get("assign_type"));
        out.put("graded", graded);
        out.put("low_confidence", lowConfidence);
        out.put("engine", engine);
        out.put("model", model);
        out.put("total_cost_ms", System.currentTimeMillis() - t0);
        out.put("need_review", lowConfidence > 0 || !"客观题".equals(String.valueOf(a.get("assign_type"))));
        return out;
    }

    /** 批改单份提交 */
    private Map<String, Object> gradeOne(Map<String, Object> a, Map<String, Object> s, int cid) {
        int subId = intv(s.get("sub_id"));
        int aid = intv(a.get("assign_id"));
        int sid = intv(s.get("student_id"));
        String type = String.valueOf(a.get("assign_type"));
        int maxScore = intv(a.get("total_score"));
        String content = s.get("content") == null ? "" : String.valueOf(s.get("content"));
        Map<String, Object> rubric = parseRubric(a.get("ai_rubric"));

        long t = System.currentTimeMillis();
        AiGradeResult res;
        String engine, model;

        switch (type) {
            case "客观题" -> {
                res = gradeObjective(content, maxScore);
                engine = "RULE_OBJECTIVE";
                model = "rule-exact-match";
            }
            case "编程题" -> {
                res = gradeProgram(content, rubric, maxScore);
                engine = "PROGRAM_JUDGE";
                model = "static-analysis-v1";
            }
            default -> {
                // 引擎名如实反映本次实际使用的能力来源：
                // 配置了大模型 → LLM_*；否则是本地启发式 → RULE_*（避免把规则引擎标成 LLM）
                boolean llm = httpProvider.available();
                AiProvider p = llm ? httpProvider : localProvider;
                try {
                    res = p.grade(type, content, rubric, maxScore);
                    engine = "作文".equals(type)
                            ? (llm ? "LLM_ESSAY" : "RULE_ESSAY")
                            : (llm ? "LLM_SUBJECTIVE" : "RULE_SUBJECTIVE");
                    model = p.id();
                } catch (Exception e) {
                    log.warn("大模型批改失败，降级到本地引擎：{}", e.getMessage());
                    res = localProvider.grade(type, content, rubric, maxScore);
                    engine = "作文".equals(type) ? "RULE_ESSAY" : "RULE_SUBJECTIVE";
                    model = localProvider.id();
                }
            }
        }
        long cost = (int) (System.currentTimeMillis() - t);

        Map<String, Object> r = new HashMap<>();
        r.put("sub_id", subId);
        r.put("assign_id", aid);
        r.put("student_id", sid);
        r.put("assign_type", type);
        r.put("engine", engine);
        r.put("model", model);
        r.put("ai_score", BigDecimal.valueOf(res.getScore()));
        r.put("max_score", maxScore);
        r.put("confidence", BigDecimal.valueOf(res.getConfidence()));
        r.put("comment", res.getComment());
        r.put("detail_json", toJson(res));
        r.put("cost_ms", cost);
        r.put("created_at", LocalDateTime.now().format(DTF));
        aiMapper.upsert(r);

        r.put("confidence", res.getConfidence());
        return r;
    }

    /* ============================================================ 客观题：规则判分 */

    /**
     * 客观题规则判分：按 q_id 取标准答案逐题比对。
     * 提交内容需为 {"answers":[{"q_id":1,"answer":"A"},...]}，
     * 若不是结构化作答则无法判分，返回置信度 0 并明确提示（不做猜测评分）。
     */
    private AiGradeResult gradeObjective(String content, int maxScore) {
        AiGradeResult r = new AiGradeResult();
        List<Map<String, Object>> answers = parseAnswers(content);
        if (answers == null) {
            r.setScore(0);
            r.setConfidence(0);
            r.setComment("提交内容不是结构化客观题作答（应为 JSON 答案列表），无法进行规则判分，"
                    + "请学生按客观题作答模板重新提交，或由教师人工批阅。");
            r.setItems(List.of());
            return r;
        }

        double per = answers.isEmpty() ? 0 : ((double) maxScore) / answers.size();
        int correct = 0;
        List<AiGradeResult.Item> items = new ArrayList<>();

        for (int i = 0; i < answers.size(); i++) {
            int qid = intv(answers.get(i).get("q_id"));
            String mine = String.valueOf(answers.get(i).getOrDefault("answer", "")).trim().toUpperCase();
            Map<String, Object> q = aiMapper.questionAnswer(qid);
            String std = q == null ? null : String.valueOf(q.get("answer")).trim().toUpperCase();
            boolean hit = std != null && std.equals(mine);
            if (hit) correct++;
            items.add(new AiGradeResult.Item(
                    "第" + (i + 1) + "题（题号 " + qid + "）",
                    hit ? round1(per) : 0, round1(per), hit,
                    hit ? "" : "作答「" + mine + "」，正确答案「" + std + "」",
                    hit ? "" : "请复习该题对应知识点后重做"));
        }

        double rate = answers.isEmpty() ? 0 : correct * 100.0 / answers.size();
        r.setScore(round1(per * correct));
        r.setConfidence(1.0);
        r.setComment("客观题规则判分：共 " + answers.size() + " 题，答对 " + correct
                + " 题，正确率 " + Math.round(rate) + "%。"
                + "本题型由标准答案精确比对得出，结果确定，可不经过人工复核直接采纳。");
        r.setItems(items);
        return r;
    }

    /* ============================================================ 编程题：静态检查 */

    /**
     * 编程题静态检查：校验评分标准中要求的语法结构是否出现。
     * ★ 局限说明：静态检查无法验证运行结果与边界用例，置信度固定 0.65，
     *   结果中明确提示需接入判题沙箱后才能实跑测试用例。
     */
    @SuppressWarnings("unchecked")
    private AiGradeResult gradeProgram(String content, Map<String, Object> rubric, int maxScore) {
        List<String> required = new ArrayList<>();
        if (rubric.get("required") instanceof List<?> l) {
            for (Object o : l) required.add(String.valueOf(o));
        }
        if (required.isEmpty()) required = List.of("for", "if", "return");

        String code = content == null ? "" : content;
        int hitCount = (int) required.stream().filter(code::contains).count();
        double coverage = ((double) hitCount) / required.size();
        double structure = (code.contains("{") && code.contains("}") ? 0.5 : 0)
                + (code.contains("main") || code.contains("def ") ? 0.3 : 0)
                + (code.contains(";") || code.contains("\n") ? 0.2 : 0);
        double commentScore = code.contains("//") || code.contains("#") ? 1.0 : 0.4;

        double score = maxScore * (0.5 * coverage + 0.3 * structure + 0.2 * commentScore);

        AiGradeResult r = new AiGradeResult();
        r.setScore(round1(score));
        r.setConfidence(0.65);
        r.setComment("编程题静态检查：命中必需结构 " + hitCount + "/" + required.size() + "。"
                + "【注意】静态检查不能替代运行，未接入判题沙箱前无法验证测试用例通过情况，"
                + "本结果仅供参考，请教师复核。");

        List<AiGradeResult.Item> items = new ArrayList<>();
        double per = maxScore * 0.5 / required.size();
        for (String t : required) {
            boolean hit = code.contains(t);
            items.add(new AiGradeResult.Item(
                    "必需结构：" + t, hit ? round1(per) : 0, round1(per), hit,
                    hit ? "" : "代码中未出现「" + t + "」",
                    hit ? "" : "请补充「" + t + "」对应的实现"));
        }
        items.add(new AiGradeResult.Item("代码结构完整性",
                round1(maxScore * 0.3 * structure), round1(maxScore * 0.3),
                structure >= 0.8, structure >= 0.8 ? "" : "代码结构不完整", "检查函数定义与代码块配对"));
        items.add(new AiGradeResult.Item("注释规范性",
                round1(maxScore * 0.2 * commentScore), round1(maxScore * 0.2),
                commentScore >= 1, commentScore >= 1 ? "" : "缺少必要注释", "为关键逻辑添加注释"));
        items.add(new AiGradeResult.Item("运行结果验证", 0, 0, false,
                "未执行测试用例（未接入判题沙箱）", "接入 Judge0 / HUSTOJ 等沙箱后可自动实跑用例"));
        r.setItems(items);
        return r;
    }

    /* ============================================================ 查询 */

    public List<Map<String, Object>> results(int aid, int teacherId) {
        checkOwned(aid, teacherId);
        return aiMapper.listByAssignment(aid);
    }

    public Map<String, Object> stat(int aid, int teacherId) {
        checkOwned(aid, teacherId);
        Map<String, Object> s = aiMapper.statByAssignment(aid);
        if (s == null) s = new HashMap<>();
        s.put("confidence_threshold", confidenceThreshold);
        s.put("engine", currentEngine());
        return s;
    }

    /** 学生查看自己的批改结果（仅本人） */
    public Map<String, Object> myResult(int subId, int studentId) {
        Map<String, Object> r = aiMapper.findBySub(subId);
        if (r == null) throw new BizException("暂无批改结果");
        if (intv(r.get("student_id")) != studentId) throw new BizException("无权查看他人批改结果", 403);
        return r;
    }

    /* ============================================================ 教师复核 */

    /**
     * 教师复核：写入最终分与评语，并同步到 submissions.score。
     * 复核状态：与 AI 初评分不一致 → 「已调整」；一致 → 「已确认」。
     */
    public void review(int resultId, BigDecimal finalScore, String comment, int teacherId) {
        Map<String, Object> r = aiMapper.findById(resultId);
        if (r == null) throw new BizException("批改结果不存在");
        int aid = intv(r.get("assign_id"));
        checkOwned(aid, teacherId);

        if (finalScore == null) throw new BizException("缺少复核分数");
        if (finalScore.compareTo(BigDecimal.ZERO) < 0 || finalScore.compareTo(new BigDecimal("100")) > 0) {
            throw new BizException("分数需在 0~100 之间");
        }

        double ai = dbl(r.get("ai_score"));
        String status = Math.abs(finalScore.doubleValue() - ai) < 0.01 ? "已确认" : "已调整";
        aiMapper.review(resultId, status, finalScore, comment == null ? "" : comment,
                teacherId, LocalDateTime.now().format(DTF));
        aiMapper.syncSubmissionScore(intv(r.get("sub_id")), finalScore);
    }

    /* ============================================================ 导出 */

    /** 导出 CSV（带 UTF-8 BOM，保证 Excel 打开中文不乱码） */
    public byte[] exportCsv(int aid, int teacherId) {
        checkOwned(aid, teacherId);
        List<Map<String, Object>> rows = aiMapper.listByAssignment(aid);
        Map<String, Object> a = assignmentMapper.findById(aid);

        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');
        sb.append("学号,姓名,作业,作业类型,批改引擎,AI初评分,满分,置信度,复核状态,最终分,"
                + "AI评语,主要问题,修改建议,批改耗时ms,批改时间,复核评语\n");
        for (Map<String, Object> r : rows) {
            sb.append(csv(r.get("username"))).append(',')
                    .append(csv(r.get("real_name"))).append(',')
                    .append(csv(a.get("title"))).append(',')
                    .append(csv(r.get("assign_type"))).append(',')
                    .append(csv(r.get("engine"))).append(',')
                    .append(csv(r.get("ai_score"))).append(',')
                    .append(csv(r.get("max_score"))).append(',')
                    .append(csv(r.get("confidence"))).append(',')
                    .append(csv(r.get("review_status"))).append(',')
                    .append(csv(r.get("final_score"))).append(',')
                    .append(csv(r.get("comment"))).append(',')
                    .append(csv(firstError(r))).append(',')
                    .append(csv(firstTip(r))).append(',')
                    .append(csv(r.get("cost_ms"))).append(',')
                    .append(csv(r.get("created_at"))).append(',')
                    .append(csv(r.get("teacher_comment"))).append('\n');
        }
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /* ============================================================ 工具 */

    private void checkOwned(int aid, int teacherId) {
        Map<String, Object> a = assignmentMapper.findById(aid);
        if (a == null) throw new BizException("作业不存在");
        if (courseMapper.countOwned(intv(a.get("course_id")), teacherId) == 0) {
            throw new BizException("无权访问该作业", 403);
        }
    }

    private String currentEngine() {
        return httpProvider.available() ? "大模型（" + httpProvider.id() + "）" : "本地启发式引擎";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseRubric(Object raw) {
        if (raw == null) return new HashMap<>();
        try {
            return om.readValue(String.valueOf(raw), Map.class);
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseAnswers(String content) {
        if (content == null || !content.trim().startsWith("{")) return null;
        try {
            Map<String, Object> m = om.readValue(content, Map.class);
            Object arr = m.get("answers");
            return arr instanceof List ? (List<Map<String, Object>>) arr : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String firstError(Map<String, Object> r) {
        List<String> errs = pick(r, "error");
        return errs.isEmpty() ? "" : String.join("；", errs);
    }

    private String firstTip(Map<String, Object> r) {
        List<String> tips = pick(r, "tip");
        return tips.isEmpty() ? "" : String.join("；", tips);
    }

    @SuppressWarnings("unchecked")
    private List<String> pick(Map<String, Object> r, String field) {
        List<String> out = new ArrayList<>();
        try {
            Map<String, Object> d = om.readValue(String.valueOf(r.get("detail_json")), Map.class);
            Object items = d.get("items");
            if (items instanceof List) {
                for (Object o : (List<?>) items) {
                    Map<String, Object> it = (Map<String, Object>) o;
                    String v = String.valueOf(it.getOrDefault(field, ""));
                    if (!v.isBlank()) out.add(v);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private String toJson(AiGradeResult res) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("score", res.getScore());
            m.put("confidence", res.getConfidence());
            m.put("items", res.getItems());
            return om.writeValueAsString(m);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String csv(Object o) {
        if (o == null) return "";
        String s = String.valueOf(o).replace("\"", "\"\"").replace("\r", " ").replace("\n", " ");
        return s.contains(",") || s.contains("\"") ? "\"" + s + "\"" : s;
    }

    private int intv(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }

    private double dbl(Object o) {
        return o == null ? 0 : ((Number) o).doubleValue();
    }

    private double round1(double x) {
        return Math.round(x * 10) / 10.0;
    }
}
