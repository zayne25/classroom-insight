package com.zhiketang.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 本地启发式批改引擎（离线可用）
 *
 * 定位说明：这是一个**规则化的评分基线**，用于在无网络 / 无大模型密钥的环境下
 * 跑通"AI 批改 → 教师复核"的完整流程。它对主观题、作文只做关键词覆盖、
 * 篇幅与结构的可解释评分，**不具备语义理解能力**，
 * 因此输出置信度上限设为 0.85，低于复核阈值时会被强制标记为需人工复核。
 *
 * 接入真实大模型请使用 {@link HttpLlmAiProvider}。
 */
@Slf4j
@Component("localHeuristicAiProvider")
public class LocalHeuristicAiProvider implements AiProvider {

    /** 作文常用的衔接词，用于评估篇章连贯性 */
    private static final List<String> CONNECTIVES = Arrays.asList(
            "因为", "所以", "但是", "然而", "因此", "综上", "首先", "其次", "最后", "总之");

    /** 无评分标准时的通用维度，保证任何作业都能给出可解释的反馈 */
    private static final List<String> DEFAULT_KEYWORDS = Arrays.asList(
            "概念", "原理", "步骤", "示例", "结论");

    @Override
    public String id() {
        return "local-heuristic-v1";
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public AiGradeResult grade(String assignType, String content, Map<String, Object> rubric, int maxScore) {
        return "作文".equals(assignType)
                ? gradeEssay(content, rubric, maxScore)
                : gradeSubjective(content, rubric, maxScore);
    }

    /* ------------------------------------------------------------ 主观题 */

    private AiGradeResult gradeSubjective(String content, Map<String, Object> rubric, int maxScore) {
        List<String> keywords = keywords(rubric);
        int minWords = intOf(rubric.get("min_words"), 150);
        String text = content == null ? "" : content;

        int words = words(text);
        double coverage = coverage(text, keywords);
        double lengthScore = clamp(((double) words) / Math.max(minWords, 1), 0, 1);
        double structureScore = structure(text);

        double score = maxScore * (0.50 * coverage + 0.30 * lengthScore + 0.20 * structureScore);

        AiGradeResult r = new AiGradeResult();
        r.setScore(round1(score));
        r.setConfidence(round3(clamp(0.55 + 0.30 * coverage, 0.30, 0.85)));
        r.setComment(comment(score, maxScore, coverage, words, minWords, "主观题"));

        List<AiGradeResult.Item> items = new ArrayList<>();
        double per = maxScore * 0.50 / Math.max(keywords.size(), 1);
        for (String k : keywords) {
            boolean hit = text.contains(k);
            items.add(new AiGradeResult.Item(
                    "得分点：" + k,
                    hit ? round1(per) : 0, round1(per), hit,
                    hit ? "" : "未覆盖得分点「" + k + "」",
                    hit ? "" : "建议围绕「" + k + "」补充论述"));
        }
        items.add(new AiGradeResult.Item(
                "篇幅要求（≥" + minWords + "字）",
                round1(maxScore * 0.30 * lengthScore), round1(maxScore * 0.30),
                lengthScore >= 1,
                lengthScore >= 1 ? "" : "当前 " + words + " 字，未达 " + minWords + " 字要求",
                "建议展开分析过程，补充具体步骤与示例"));
        items.add(new AiGradeResult.Item(
                "结构完整性",
                round1(maxScore * 0.20 * structureScore), round1(maxScore * 0.20),
                structureScore >= 0.8,
                structureScore >= 0.8 ? "" : "缺少分段或结束标点，结构不够完整",
                "建议按「概念—分析—结论」分段书写"));
        r.setItems(items);
        return r;
    }

    /* ------------------------------------------------------------ 作文 */

    private AiGradeResult gradeEssay(String content, Map<String, Object> rubric, int maxScore) {
        List<String> keywords = keywords(rubric);
        int minWords = intOf(rubric.get("min_words"), 400);
        String text = content == null ? "" : content;

        int words = words(text);
        double coverage = coverage(text, keywords);
        double lengthScore = clamp(((double) words) / Math.max(minWords, 1), 0, 1);
        double coherence = coherence(text);

        double score = maxScore * (0.40 * coverage + 0.30 * lengthScore + 0.30 * coherence);

        AiGradeResult r = new AiGradeResult();
        r.setScore(round1(score));
        r.setConfidence(round3(clamp(0.50 + 0.30 * coverage, 0.30, 0.82)));
        r.setComment(comment(score, maxScore, coverage, words, minWords, "作文"));

        List<AiGradeResult.Item> items = new ArrayList<>();
        double per = maxScore * 0.40 / Math.max(keywords.size(), 1);
        for (String k : keywords) {
            boolean hit = text.contains(k);
            items.add(new AiGradeResult.Item(
                    "立意要点：" + k,
                    hit ? round1(per) : 0, round1(per), hit,
                    hit ? "" : "立意未体现「" + k + "」",
                    hit ? "" : "建议在相应段落点明「" + k + "」"));
        }
        items.add(new AiGradeResult.Item(
                "篇幅要求（≥" + minWords + "字）",
                round1(maxScore * 0.30 * lengthScore), round1(maxScore * 0.30),
                lengthScore >= 1,
                lengthScore >= 1 ? "" : "当前 " + words + " 字，篇幅不足",
                "扩充论据与细节描写"));
        items.add(new AiGradeResult.Item(
                "篇章连贯性",
                round1(maxScore * 0.30 * coherence), round1(maxScore * 0.30),
                coherence >= 0.6,
                coherence >= 0.6 ? "" : "衔接词偏少、段落层次不足",
                "多使用「首先/然而/因此/综上」等衔接词"));
        r.setItems(items);
        return r;
    }

    /* ------------------------------------------------------------ 工具 */

    @SuppressWarnings("unchecked")
    private List<String> keywords(Map<String, Object> rubric) {
        if (rubric != null && rubric.get("keywords") instanceof List) {
            List<String> ks = (List<String>) rubric.get("keywords");
            if (!ks.isEmpty()) return ks;
        }
        return DEFAULT_KEYWORDS;
    }

    private double coverage(String text, List<String> keywords) {
        if (keywords.isEmpty()) return 0;
        long hit = keywords.stream().filter(text::contains).count();
        return ((double) hit) / keywords.size();
    }

    /** 中文字数：按去空白后的字符数统计 */
    private int words(String text) {
        return text.replaceAll("\\s", "").length();
    }

    private double structure(String text) {
        double s = 0;
        if (text.contains("\n") || text.length() > 120) s += 0.5;
        if (text.contains("。") || text.contains(".")) s += 0.5;
        return clamp(s, 0, 1);
    }

    private double coherence(String text) {
        double s = 0;
        long used = CONNECTIVES.stream().filter(text::contains).count();
        s += clamp(used / 3.0, 0, 0.5);            // 衔接词
        s += clamp(text.split("\n").length / 4.0, 0, 0.3); // 段落
        if (text.contains("。")) s += 0.2;
        return clamp(s, 0, 1);
    }

    private String comment(double score, int maxScore, double coverage,
                           int words, int minWords, String type) {
        double pct = maxScore == 0 ? 0 : score / maxScore * 100;
        StringBuilder sb = new StringBuilder();
        if (pct >= 85) sb.append("整体质量优秀，");
        else if (pct >= 70) sb.append("整体质量良好，");
        else if (pct >= 60) sb.append("基本达到要求，");
        else sb.append("距离要求尚有差距，");
        sb.append("得分点覆盖 ").append(Math.round(coverage * 100)).append("%");
        if (words < minWords) sb.append("，篇幅偏少（").append(words).append("/").append(minWords).append(" 字）");
        sb.append("。");
        sb.append("【").append(type).append("由本地启发式引擎初评，仅供参考，请以教师复核分为准】");
        return sb.toString();
    }

    private int intOf(Object o, int def) {
        return o == null ? def : ((Number) o).intValue();
    }

    private double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }

    private double round1(double x) {
        return Math.round(x * 10) / 10.0;
    }

    private double round3(double x) {
        return Math.round(x * 1000) / 1000.0;
    }
}
