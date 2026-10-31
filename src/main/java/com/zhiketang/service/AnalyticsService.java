package com.zhiketang.service;

import com.zhiketang.mapper.AnalyticsMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/**
 * 学情分析服务
 *
 * 设计要点：指标全部由数据库层的视图与聚合 SQL 计算完成（见 AnalyticsMapper），
 * 本类只负责结果组装、单位换算（比例 → 百分比）以及皮尔逊相关系数计算。
 */
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final AnalyticsMapper m;

    private static final String[] BUCKETS = {"<60", "60-69", "70-79", "80-89", "90-100"};

    /* ---------------- 概览 ---------------- */

    public Map<String, Object> overview(int cid) {
        Map<String, Object> o = new LinkedHashMap<>(m.overview(cid));
        Map<String, Object> risk = new LinkedHashMap<>();
        risk.put("红", 0);
        risk.put("黄", 0);
        risk.put("绿", 0);
        for (Map<String, Object> r : m.riskDist(cid)) {
            risk.put(String.valueOf(r.get("risk_level")), intv(r.get("c")));
        }
        o.put("risk", risk);
        return o;
    }

    public Map<String, Object> courseCardStats(int cid) {
        return m.courseCardStats(cid);
    }

    /* ---------------- 出勤 ---------------- */

    public Map<String, Object> attendance(int cid) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trend", m.attendanceTrend(cid));
        out.put("status_dist", m.attendanceStatusDist(cid));
        return out;
    }

    /* ---------------- 互动 ---------------- */

    public Map<String, Object> interaction(int cid) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type_dist", m.interactionTypeDist(cid));
        out.put("top_active", m.interactionTopActive(cid));

        List<Map<String, Object>> trend = new ArrayList<>();
        for (Map<String, Object> r : m.interactionTrend(cid)) {
            int interCnt = intv(r.get("inter_cnt"));
            int stuCnt = intv(r.get("stu_cnt"));
            int recCnt = intv(r.get("rec_cnt"));
            double denom = (double) interCnt * stuCnt;
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("lesson_no", r.get("lesson_no"));
            t.put("title", r.get("title"));
            t.put("rate", denom == 0 ? 0 : round1(100.0 * recCnt / denom));
            trend.add(t);
        }
        out.put("trend", trend);
        return out;
    }

    /* ---------------- 成绩 ---------------- */

    public Map<String, Object> score(int cid) {
        int[] counts = new int[5];
        for (Map<String, Object> r : m.scoreDist(cid)) {
            int b = intv(r.get("b"));
            int idx = b <= 5 ? 0 : b - 5;
            counts[idx] += intv(r.get("c"));
        }
        List<Map<String, Object>> dist = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("name", BUCKETS[i]);
            d.put("value", counts[i]);
            dist.add(d);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dist", dist);
        out.put("class_compare", m.classCompare(cid));
        out.put("assign_trend", m.assignTrend(cid));
        return out;
    }

    /* ---------------- 知识点 ---------------- */

    public Map<String, Object> knowledge(int cid) {
        List<Map<String, Object>> kps = m.kpMastery(cid);
        List<Integer> kpIds = new ArrayList<>();
        for (Map<String, Object> k : kps) kpIds.add(intv(k.get("kp_id")));

        List<Map<String, Object>> ordered = m.orderedStudents(cid);
        int step = Math.max(1, ordered.size() / 40);
        List<Map<String, Object>> sample = new ArrayList<>();
        for (int i = 0; i < ordered.size() && sample.size() < 40; i += step) {
            sample.add(ordered.get(i));
        }

        Map<String, Double> mp = new HashMap<>();
        for (Map<String, Object> r : m.studentKpMastery(cid)) {
            mp.put(intv(r.get("student_id")) + "_" + intv(r.get("kp_id")), dbl(r.get("correct_rate")));
        }

        List<List<Object>> cells = new ArrayList<>();
        List<String> yNames = new ArrayList<>();
        for (int yi = 0; yi < sample.size(); yi++) {
            int sid = intv(sample.get(yi).get("student_id"));
            yNames.add(String.valueOf(sample.get(yi).get("real_name")));
            for (int xi = 0; xi < kpIds.size(); xi++) {
                Double v = mp.get(sid + "_" + kpIds.get(xi));
                if (v != null) {
                    List<Object> cell = new ArrayList<>();
                    cell.add(xi);
                    cell.add(yi);
                    cell.add(round1(v * 100));
                    cells.add(cell);
                }
            }
        }

        List<Map<String, Object>> weak = new ArrayList<>();
        for (Map<String, Object> k : kps) {
            if (dbl(k.get("correct_rate")) < 60) weak.add(k);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kps", kps);
        out.put("heat_cells", cells);
        out.put("heat_y", yNames);
        out.put("weak", weak);
        return out;
    }

    /* ---------------- 预警 ---------------- */

    public List<Map<String, Object>> risk(int cid) {
        List<Map<String, Object>> rows = m.riskList(cid);
        for (Map<String, Object> r : rows) {
            double att = dbl(r.get("attendance_rate")) * 100;
            double part = dbl(r.get("part_rate")) * 100;
            double corr = dbl(r.get("inter_correct_rate")) * 100;
            double avg = dbl(r.get("avg_score"));
            r.put("attendance_rate", round1(att));
            r.put("part_rate", round1(part));
            r.put("inter_correct_rate", round1(corr));

            List<String> reasons = new ArrayList<>();
            if (att < 70) reasons.add("出勤率偏低");
            if (part < 60) reasons.add("课堂互动不足");
            if (avg < 60) reasons.add("作业成绩不及格");
            if (corr < 50) reasons.add("随堂正确率低");
            if (reasons.isEmpty()) reasons.add("综合表现良好");
            r.put("reasons", reasons);
        }
        return rows;
    }

    /* ---------------- 相关性 ---------------- */

    public Map<String, Object> correlation(int cid) {
        List<Map<String, Object>> rows = m.correlationPoints(cid);

        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        List<Double> atts = new ArrayList<>();
        List<Map<String, Object>> points = new ArrayList<>();

        for (Map<String, Object> r : rows) {
            double x = dbl(r.get("interact_cnt"));
            double y = dbl(r.get("avg_score"));
            double a = dbl(r.get("attendance_rate")) * 100;
            xs.add(x);
            ys.add(y);
            atts.add(a);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("x", x);
            p.put("y", y);
            p.put("att", round1(a));
            p.put("name", String.valueOf(r.get("real_name")));
            points.add(p);
        }

        List<Map<String, Object>> groups = new ArrayList<>();
        groups.add(group("出勤率 ≥ 90%", rows, r -> dbl(r.get("attendance_rate")) >= 0.90));
        groups.add(group("出勤率 70%~90%", rows,
                r -> dbl(r.get("attendance_rate")) >= 0.70 && dbl(r.get("attendance_rate")) < 0.90));
        groups.add(group("出勤率 < 70%", rows, r -> dbl(r.get("attendance_rate")) < 0.70));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("points", points);
        out.put("r_interact_score", pearson(xs, ys));
        out.put("r_att_score", pearson(atts, ys));
        out.put("groups", groups);
        return out;
    }

    /* ---------------- 学生个人 ---------------- */

    public Map<String, Object> studentReport(int cid, int sid) {
        Map<String, Object> kpi = m.studentKpi(cid, sid);
        if (kpi == null) return null;

        Map<String, Object> k = new LinkedHashMap<>(kpi);
        k.put("attendance_rate", round1(dbl(kpi.get("attendance_rate")) * 100));
        k.put("part_rate", round1(dbl(kpi.get("part_rate")) * 100));
        k.put("inter_correct_rate", round1(dbl(kpi.get("inter_correct_rate")) * 100));
        k.put("rank", m.studentRank(cid, (BigDecimal) kpi.get("composite_score")));
        k.put("total", m.studentTotal(cid));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kpi", k);
        out.put("lessons", m.studentAttendance(cid, sid));
        out.put("kp_compare", m.studentKpCompare(cid, sid));
        out.put("score_trend", m.studentScoreTrend(cid, sid));
        return out;
    }

    /** 单个学生的学情指标（含风险等级），用于课程卡片 */
    public Map<String, Object> studentKpi(int cid, int sid) {
        return m.studentKpi(cid, sid);
    }

    /** 知识点掌握度列表（自检与页面共用） */
    public List<Map<String, Object>> courseKpList(int cid) {
        return m.kpMastery(cid);
    }

    public List<Map<String, Object>> classSummary(int cid) {
        return m.classSummary(cid);
    }

    /* ---------------- 工具方法 ---------------- */

    private Map<String, Object> group(String name, List<Map<String, Object>> rows,
                                      java.util.function.Predicate<Map<String, Object>> cond) {
        double sum = 0;
        int cnt = 0;
        for (Map<String, Object> r : rows) {
            if (cond.test(r)) {
                sum += dbl(r.get("avg_score"));
                cnt++;
            }
        }
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("name", name);
        g.put("value", cnt == 0 ? 0 : round1(sum / cnt));
        g.put("cnt", cnt);
        return g;
    }

    /** 皮尔逊相关系数 */
    private double pearson(List<Double> xs, List<Double> ys) {
        int n = xs.size();
        if (n < 2) return 0;
        double mx = xs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double my = ys.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double num = 0, dx = 0, dy = 0;
        for (int i = 0; i < n; i++) {
            double a = xs.get(i) - mx, b = ys.get(i) - my;
            num += a * b;
            dx += a * a;
            dy += b * b;
        }
        return dx == 0 || dy == 0 ? 0 : Math.round(num / (Math.sqrt(dx) * Math.sqrt(dy)) * 10000) / 10000.0;
    }

    private double dbl(Object o) {
        return o == null ? 0 : ((Number) o).doubleValue();
    }

    private int intv(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }

    private double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
