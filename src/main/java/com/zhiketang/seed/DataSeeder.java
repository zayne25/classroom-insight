package com.zhiketang.seed;

import com.zhiketang.mapper.*;
import com.zhiketang.service.AnalyticsService;
import com.zhiketang.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * 数据初始化（基础数据 + 测试数据）
 *
 * 触发方式：
 *   1) 启动时带上参数 --seed
 *   2) 数据库 users 表为空时自动执行
 *
 * ★ 测试数据设计要点：引入隐藏的"学习能力潜变量" ability（high/mid/low 三档），
 *   学生的【出勤率】【互动参与概率】【互动正确率】【作业分数】均与该潜变量正相关并叠加噪声；
 *   并把 kp_order = 3 与 5 的知识点正确率乘以 0.75，模拟"教学薄弱点"。
 *   这样生成的库才能跑出有解释力的分析结论（相关系数、薄弱知识点、学业预警）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final JdbcTemplate jdbc;
    private final UserMapper userMapper;
    private final CourseMapper courseMapper;
    private final LessonMapper lessonMapper;
    private final InteractionMapper interactionMapper;
    private final AssignmentMapper assignmentMapper;
    private final AnalyticsService analyticsService;
    private final AuthService authService;

    private final Random random = new Random(20261029);

    /* ---------------- 生成参数 ---------------- */
    private static final int INTER_PER_LESSON = 4;      // 每课时互动数
    private static final int QUESTION_PER_LESSON = 5;   // 每课时随堂答题数
    private static final int ASSIGNMENT_COUNT = 8;      // 每门课作业次数
    /** 作业类型：8 次作业按 i/2 取用，即 客观题×2 / 主观题×2 / 编程题×2 / 作文×2 */
    private static final String[] ASSIGN_TYPES = {"客观题", "主观题", "编程题", "作文"};
    private static final int[] WEAK_KP_ORDERS = {3, 5}; // 人为设置的薄弱知识点
    private static final double WEAK_KP_FACTOR = 0.75;  // 薄弱知识点正确率折扣

    /** 能力分档：占比、出勤率基准、互动正确率基准、作业均分基准 */
    private static final double[][] TIERS = {
            {0.30, 0.96, 0.85, 86},
            {0.50, 0.88, 0.68, 74},
            {0.20, 0.72, 0.45, 58},
    };

    private static final String[] CLASS_NAMES = {"计科2023-1班", "计科2023-2班", "软工2023-1班"};
    private static final String[] MAJORS = {"计算机科学与技术", "计算机科学与技术", "软件工程"};
    private static final String[] HEADERS = {"王志远", "陈晓梅", "刘国庆"};

    private static final String[] SURNAMES = {"王", "李", "张", "刘", "陈", "杨", "赵", "黄", "周", "吴",
            "徐", "孙", "马", "朱", "胡", "郭", "何", "高", "林", "罗"};
    private static final String[] GIVEN = {"伟", "芳", "娜", "敏", "静", "强", "磊", "洋", "勇", "艳",
            "杰", "涛", "明", "超", "秀兰", "霞", "平", "刚", "桂英", "子轩", "雨欣", "浩然", "梓涵", "思远"};

    private static final String[][] COURSES = {
            {"CS301", "数据库系统原理", "系统讲解关系数据库的基本原理、SQL 语言、规范化设计与事务并发控制"},
            {"CS205", "数据结构与算法", "讲解线性表、栈与队列、树与图等典型数据结构及常用算法设计策略"},
    };

    private static final String[][] KPS = {
            {"数据库系统概述", "关系模型与关系代数", "SQL 语言基础", "数据库规范化",
                    "事务与并发控制", "数据库安全性", "索引与查询优化", "数据库设计综合"},
            {"算法复杂度分析", "线性表", "栈与队列", "树与二叉树",
                    "图论算法", "排序算法", "查找与散列", "算法设计策略"},
    };

    private static final String[][] LESSON_TITLES = {
            {"第1讲 数据库系统概述", "第2讲 数据库系统体系结构", "第3讲 关系模型基本概念", "第4讲 关系代数运算",
                    "第5讲 SQL 数据定义与更新", "第6讲 SQL 数据查询（一）", "第7讲 SQL 连接与子查询", "第8讲 函数依赖与范式",
                    "第9讲 关系模式分解", "第10讲 数据库设计需求", "第11讲 事务与 ACID 特性", "第12讲 并发控制与封锁",
                    "第13讲 数据库恢复技术", "第14讲 数据库安全性与权限", "第15讲 索引与查询优化", "第16讲 数据库设计综合案例"},
            {"第1讲 算法复杂度分析", "第2讲 渐进记号与复杂度计算", "第3讲 线性表：顺序表", "第4讲 线性表：链表",
                    "第5讲 栈与队列", "第6讲 栈的应用：表达式求值", "第7讲 树与二叉树", "第8讲 二叉搜索树与平衡树",
                    "第9讲 图的遍历算法", "第10讲 最短路与最小生成树", "第11讲 排序算法（一）", "第12讲 排序算法（二）",
                    "第13讲 查找与散列", "第14讲 散列冲突处理", "第15讲 分治与贪心策略", "第16讲 动态规划策略"},
    };

    private static final LocalDate FIRST_MONDAY = LocalDate.of(2026, 9, 7);

    @Override
    public void run(String... args) {
        boolean force = Arrays.asList(args).contains("--seed");
        if (!force && userMapper.count() > 0) {
            log.info("[DataSeeder] 数据库已有数据，跳过初始化（如需重建请加 --seed 参数）");
            return;
        }
        long t0 = System.currentTimeMillis();
        clean();
        seedBase();
        seedMock();
        selfCheck();
        log.info("[DataSeeder] 初始化完成，耗时 {} ms", System.currentTimeMillis() - t0);
    }

    /* ================================================================ 清理 */

    private void clean() {
        String[] tables = {"learning_events", "answer_records", "submissions", "assignments",
                "interaction_records", "interactions", "attendances", "lessons", "questions",
                "knowledge_points", "enrollments", "courses", "users", "classes"};
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String t : tables) jdbc.execute("TRUNCATE TABLE " + t);
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        System.out.println("[DataSeeder] 已清空全部业务表");
    }

    /* ================================================================ 基础数据 */

    private void seedBase() {
        // 1. 班级
        List<Map<String, Object>> cls = new ArrayList<>();
        for (int i = 0; i < CLASS_NAMES.length; i++) {
            Map<String, Object> r = new HashMap<>();
            r.put("class_name", CLASS_NAMES[i]);
            r.put("grade", "2023级");
            r.put("major", MAJORS[i]);
            r.put("headteacher", HEADERS[i]);
            cls.add(r);
        }
        userMapper.batchInsertClasses(cls);
        List<Map<String, Object>> classes = userMapper.listClasses();
        int[] classIds = classes.stream().mapToInt(c -> ((Number) c.get("class_id")).intValue()).toArray();

        // 2. 用户：2 名教师 + 1 名管理员 + 105 名学生
        List<Map<String, Object>> users = new ArrayList<>();
        users.add(user("t001", "张建国", "teacher", null, "男"));
        users.add(user("t002", "李慧敏", "teacher", null, "女"));
        users.add(user("admin", "系统管理员", "admin", null, null));
        for (int ci = 0; ci < classIds.length; ci++) {
            for (int seq = 1; seq <= 35; seq++) {
                String username = String.format("2023%02d%02d", ci + 1, seq);
                String realName = SURNAMES[random.nextInt(SURNAMES.length)] + GIVEN[random.nextInt(GIVEN.length)];
                users.add(user(username, realName, "student", classIds[ci],
                        random.nextBoolean() ? "男" : "女"));
            }
        }
        chunk(users, 50).forEach(userMapper::batchInsert);
        System.out.println("[DataSeeder] 用户生成完成：" + users.size() + " 人");

        int t1 = idOf("t001");
        int t2 = idOf("t002");
        List<Integer> studentIds = userMapper.listStudentIds();

        // 3. 课程
        int[] teacherIds = {t1, t2};
        List<Integer> courseIds = new ArrayList<>();
        for (int i = 0; i < COURSES.length; i++) {
            Map<String, Object> c = new HashMap<>();
            c.put("course_code", COURSES[i][0]);
            c.put("course_name", COURSES[i][1]);
            c.put("credit", 3.0);
            c.put("semester", "2026-2027学年第一学期");
            c.put("teacher_id", teacherIds[i]);
            c.put("description", COURSES[i][2]);
            courseMapper.insert(c);
            courseIds.add(((Number) c.get("course_id")).intValue());
        }

        // 4. 选课：全体学生选修两门课
        List<Map<String, Object>> ens = new ArrayList<>();
        for (int cid : courseIds) {
            for (int sid : studentIds) {
                Map<String, Object> r = new HashMap<>();
                r.put("course_id", cid);
                r.put("student_id", sid);
                ens.add(r);
            }
        }
        chunk(ens, 500).forEach(courseMapper::batchInsertEnrollments);

        // 5. 知识点 + 题库 + 课时
        for (int ci = 0; ci < courseIds.size(); ci++) {
            int cid = courseIds.get(ci);

            List<Map<String, Object>> kps = new ArrayList<>();
            for (int k = 0; k < KPS[ci].length; k++) {
                Map<String, Object> r = new HashMap<>();
                r.put("course_id", cid);
                r.put("kp_name", KPS[ci][k]);
                r.put("kp_order", k + 1);
                r.put("weight", 1.0);
                r.put("description", "第" + (k + 1) + "章 " + KPS[ci][k]);
                kps.add(r);
            }
            userMapper.batchInsertKp(kps);

            List<Map<String, Object>> kpRows = userMapper.listKnowledgePoints(cid);
            List<Map<String, Object>> qs = new ArrayList<>();
            char[] letters = {'A', 'B', 'C', 'D'};
            for (Map<String, Object> kp : kpRows) {
                for (int q = 1; q <= 10; q++) {
                    Map<String, Object> r = new HashMap<>();
                    r.put("kp_id", kp.get("kp_id"));
                    r.put("q_type", "单选");
                    r.put("content", "关于「" + kp.get("kp_name") + "」的第 " + q + " 题");
                    StringBuilder sb = new StringBuilder();
                    for (int o = 0; o < 4; o++) {
                        if (o > 0) sb.append('|');
                        sb.append(letters[o]).append(". 选项").append(letters[o]);
                    }
                    r.put("options_json", sb.toString());
                    r.put("answer", String.valueOf(letters[random.nextInt(4)]));
                    r.put("difficulty", Math.round((0.2 + random.nextDouble() * 0.6) * 100) / 100.0);
                    qs.add(r);
                }
            }
            chunk(qs, 200).forEach(lessonMapper::batchInsertQuestions);

            List<Map<String, Object>> ls = new ArrayList<>();
            for (int l = 0; l < LESSON_TITLES[ci].length; l++) {
                Map<String, Object> r = new HashMap<>();
                r.put("course_id", cid);
                r.put("lesson_no", l + 1);
                r.put("title", LESSON_TITLES[ci][l]);
                r.put("lesson_date", FIRST_MONDAY.plusWeeks(l).toString());
                r.put("start_time", "08:00");
                r.put("end_time", "09:40");
                r.put("status", "已结束");
                ls.add(r);
            }
            lessonMapper.batchInsert(ls);
        }
        System.out.println("[DataSeeder] 基础数据完成：课程 " + courseIds.size()
                + " / 选课 " + ens.size() + " / 知识点 16 / 题库 160 / 课时 32");
    }

    /* ================================================================ 测试数据 */

    private void seedMock() {
        List<Map<String, Object>> courses = jdbc.queryForList(
                "SELECT course_id, course_name FROM courses ORDER BY course_id");

        for (Map<String, Object> course : courses) {
            int cid = ((Number) course.get("course_id")).intValue();
            List<Map<String, Object>> lessons = lessonMapper.listByCourse(cid);
            List<Integer> students = courseMapper.listStudentIds(cid);
            List<Map<String, Object>> kps = userMapper.listKnowledgePoints(cid);
            Map<Integer, Integer> kpOrder = new HashMap<>();
            for (Map<String, Object> k : kps) {
                kpOrder.put(((Number) k.get("kp_id")).intValue(), ((Number) k.get("kp_order")).intValue());
            }
            List<Map<String, Object>> questions = lessonMapper.questionsOfCourse(cid);

            // 学生能力画像（潜变量，不入库）
            Map<Integer, double[]> profile = new HashMap<>();
            for (int uid : students) profile.put(uid, pickTier());

            List<Map<String, Object>> attRows = new ArrayList<>();
            List<Map<String, Object>> recRows = new ArrayList<>();
            List<Map<String, Object>> ansRows = new ArrayList<>();
            List<Map<String, Object>> eventRows = new ArrayList<>();

            for (Map<String, Object> lesson : lessons) {
                int lid = ((Number) lesson.get("lesson_id")).intValue();
                String ltitle = String.valueOf(lesson.get("title"));
                String ldate = String.valueOf(lesson.get("lesson_date"));
                String topic = ltitle.contains(" ") ? ltitle.substring(ltitle.indexOf(' ') + 1) : ltitle;

                // 1. 签到
                List<Integer> attended = new ArrayList<>();
                for (int uid : students) {
                    double[] p = profile.get(uid);
                    String status;
                    String time;
                    if (random.nextDouble() < p[0]) {
                        double x = random.nextDouble();
                        if (x < 0.87) {
                            status = "正常";
                            time = ts(ldate, 7, rnd(35, 58));
                        } else if (x < 0.96) {
                            status = "迟到";
                            time = ts(ldate, 8, rnd(2, 14));
                        } else {
                            status = "请假";
                            time = ts(ldate, 7, rnd(35, 58));
                        }
                        attended.add(uid);
                    } else {
                        status = "缺勤";
                        time = null;
                    }
                    attRows.add(row("lesson_id", lid, "student_id", uid, "status", status,
                            "method", "签到码", "check_in_time", time));
                }

                // 2. 互动（每课时 4 个）
                List<String> types = new ArrayList<>(List.of("抢答", "投票", "随机点名", "提问"));
                Collections.shuffle(types, random);
                List<Object[]> lessonInters = new ArrayList<>();
                for (int k = 0; k < Math.min(INTER_PER_LESSON, types.size()); k++) {
                    String itype = types.get(k);
                    String opts, ans, title;
                    int gradeable, score;
                    if ("抢答".equals(itype) || "随机点名".equals(itype)) {
                        opts = "A. 正确选项一|B. 干扰项二|C. 干扰项三|D. 干扰项四";
                        ans = String.valueOf("ABCD".charAt(random.nextInt(4)));
                        gradeable = 1;
                        score = 2;
                        title = itype + "：" + topic + "随堂检测";
                    } else if ("投票".equals(itype)) {
                        opts = "A. 已完全掌握|B. 基本掌握|C. 仍需讲解";
                        ans = null;
                        gradeable = 0;
                        score = 1;
                        title = "课堂投票：" + topic + "掌握情况";
                    } else {
                        opts = null;
                        ans = null;
                        gradeable = 0;
                        score = 1;
                        title = "随堂提问：关于" + topic + "的疑问";
                    }
                    Map<String, Object> r = row("lesson_id", lid, "type", itype, "title", title,
                            "content", "", "options_json", opts, "correct_answer", ans,
                            "gradeable", gradeable, "score", score, "status", "已结束",
                            "published_at", ts(ldate, 8, 15 + k * 8));
                    interactionMapper.insert(r);
                    lessonInters.add(new Object[]{r.get("inter_id"), gradeable, score, ans, opts});
                }

                // 3. 互动响应
                for (Object[] it : lessonInters) {
                    int interId = ((Number) it[0]).intValue();
                    int gradeable = (Integer) it[1];
                    int score = (Integer) it[2];
                    String ans = (String) it[3];
                    String opts = (String) it[4];
                    for (int uid : attended) {
                        double[] p = profile.get(uid);
                        double partProb = clamp(p[0] * (0.70 + random.nextDouble() * 0.30), 0, 1);
                        if (random.nextDouble() >= partProb) continue;
                        int ok;
                        int got;
                        String answer;
                        if (gradeable == 1) {
                            ok = random.nextDouble() < clamp(p[1], 0, 0.98) ? 1 : 0;
                            got = ok == 1 ? score : 0;
                            if (ok == 1) {
                                answer = ans;
                            } else {
                                List<String> wrong = new ArrayList<>(List.of("A", "B", "C", "D"));
                                wrong.remove(ans);
                                answer = wrong.get(random.nextInt(wrong.size()));
                            }
                        } else {
                            ok = 0;
                            got = score;
                            answer = opts != null ? "ABC".charAt(random.nextInt(3)) + "" : "我对" + topic + "有疑问";
                        }
                        recRows.add(row("inter_id", interId, "student_id", uid, "answer", answer,
                                "is_correct", ok, "score", got, "responded_at", ts(ldate, 8, rnd(16, 50))));
                    }
                }

                // 4. 随堂答题（薄弱知识点正确率打折）
                Collections.shuffle(questions, random);
                for (int qi = 0; qi < Math.min(QUESTION_PER_LESSON, questions.size()); qi++) {
                    Map<String, Object> q = questions.get(qi);
                    int qid = ((Number) q.get("q_id")).intValue();
                    double factor = Arrays.stream(WEAK_KP_ORDERS).anyMatch(
                            o -> o == kpOrder.get(((Number) q.get("kp_id")).intValue())) ? WEAK_KP_FACTOR : 1.0;
                    double diff = ((Number) q.get("difficulty")).doubleValue();
                    for (int uid : attended) {
                        double[] p = profile.get(uid);
                        double prob = clamp(p[1] * factor * (1 - 0.25 * diff), 0.05, 0.98);
                        ansRows.add(row("q_id", qid, "student_id", uid, "lesson_id", lid,
                                "is_correct", random.nextDouble() < prob ? 1 : 0,
                                "answered_at", ts(ldate, 8, rnd(30, 55))));
                    }
                }

                // 5. 进入课堂日志
                for (int uid : attended) {
                    eventRows.add(row("student_id", uid, "course_id", cid, "lesson_id", lid,
                            "event_type", "进入课堂", "event_time", ts(ldate, 7, rnd(40, 59))));
                }
            }

            chunk(attRows, 500).forEach(lessonMapper::batchInsertAttendance);
            chunk(recRows, 500).forEach(interactionMapper::batchInsertRecords);
            chunk(ansRows, 500).forEach(lessonMapper::batchInsertAnswerRecords);
            chunk(eventRows, 500).forEach(lessonMapper::batchInsertEvents);

            // 6. 作业与提交（四类作业，为 AI 批改功能提供可判分的真实样本）
            //    客观题×2 / 主观题×2 / 编程题×2 / 作文×2
            List<String> kpNames = new ArrayList<>();
            for (Map<String, Object> k : kps) kpNames.add(String.valueOf(k.get("kp_name")));

            List<Object[]> assignIds = new ArrayList<>();
            for (int i = 0; i < ASSIGNMENT_COUNT; i++) {
                int idx = Math.min((i + 1) * 2 - 1, lessons.size() - 1);
                String base = String.valueOf(lessons.get(idx).get("lesson_date"));
                String type = ASSIGN_TYPES[i / 2];
                Map<String, Object> r = row("course_id", cid,
                        "title", "第" + (i + 1) + "次作业（" + type + "）",
                        "description", "围绕" + lessons.get(idx).get("title") + "内容的课后练习",
                        "assign_type", type,
                        "ai_rubric", rubricOf(type, kpNames),
                        "total_score", 100, "assigned_at", base, "deadline", base);
                assignmentMapper.insert(r);
                assignIds.add(new Object[]{r.get("assign_id"), base, type});
            }

            List<Map<String, Object>> subRows = new ArrayList<>();
            List<Map<String, Object>> subEvents = new ArrayList<>();
            for (Object[] a : assignIds) {
                int assignId = ((Number) a[0]).intValue();
                String day = (String) a[1];
                String type = (String) a[2];
                for (int uid : students) {
                    double[] p = profile.get(uid);
                    if (random.nextDouble() >= clamp(p[0] * 1.02, 0, 1)) continue;
                    Map<String, Object> sub = submissionOf(type, p, questions, kpOrder, kpNames);
                    sub.put("assign_id", assignId);
                    sub.put("student_id", uid);
                    sub.put("submitted_at", ts(day, rnd(18, 23)));
                    subRows.add(sub);
                    subEvents.add(row("student_id", uid, "course_id", cid, "lesson_id", null,
                            "event_type", "提交作业", "event_time", ts(day, rnd(18, 23))));
                }
            }
            chunk(subRows, 500).forEach(assignmentMapper::batchInsertSubmissions);
            chunk(subEvents, 500).forEach(lessonMapper::batchInsertEvents);

            // 7. 登录日志
            List<Map<String, Object>> loginRows = new ArrayList<>();
            for (int uid : students) {
                for (int n = rnd(15, 35); n > 0; n--) {
                    Map<String, Object> l = lessons.get(random.nextInt(lessons.size()));
                    loginRows.add(row("student_id", uid, "course_id", cid, "lesson_id", null,
                            "event_type", "登录平台", "event_time",
                            ts(String.valueOf(l.get("lesson_date")), rnd(8, 22))));
                }
            }
            chunk(loginRows, 500).forEach(lessonMapper::batchInsertEvents);

            System.out.printf("[DataSeeder] 课程「%s」完成：签到 %d / 互动响应 %d / 答题 %d / 提交 %d%n",
                    course.get("course_name"), attRows.size(), recRows.size(), ansRows.size(), subRows.size());
        }
    }

    /* ================================================================ 自检 */

    private void selfCheck() {
        System.out.println("\n[DataSeeder] 当前数据行数：");
        int total = 0;
        for (String t : new String[]{"users", "courses", "enrollments", "lessons", "attendances",
                "interactions", "interaction_records", "assignments", "submissions",
                "answer_records", "learning_events"}) {
            int n = jdbc.queryForObject("SELECT COUNT(*) FROM " + t, Integer.class);
            total += n;
            System.out.printf("    %-22s %d%n", t, n);
        }
        System.out.println("    过程数据合计 " + total + " 行");

        System.out.println("\n[DataSeeder] ---------- 数据规律自检（决定分析结论是否成立）----------");
        for (Map<String, Object> c : jdbc.queryForList("SELECT course_id, course_name FROM courses ORDER BY course_id")) {
            int cid = ((Number) c.get("course_id")).intValue();
            Map<String, Object> ov = analyticsService.overview(cid);
            Map<String, Object> corr = analyticsService.correlation(cid);
            System.out.printf("  【%s】互动次数 vs 作业均分 皮尔逊 r = %s%n",
                    c.get("course_name"), corr.get("r_interact_score"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> groups = (List<Map<String, Object>>) corr.get("groups");
            if (groups.size() == 3) {
                System.out.printf("      %s 均分 %s  vs  %s 均分 %s%n",
                        groups.get(0).get("name"), groups.get(0).get("value"),
                        groups.get(2).get("name"), groups.get(2).get("value"));
            }
            StringBuilder sb = new StringBuilder("      知识点掌握度：");
            for (Map<String, Object> k : analyticsService.courseKpList(cid)) {
                sb.append(k.get("kp_name")).append(" ").append(k.get("correct_rate")).append("%  ");
            }
            System.out.println(sb);
            @SuppressWarnings("unchecked")
            Map<String, Object> risk = (Map<String, Object>) ov.get("risk");
            System.out.printf("      风险分布：红%s人  黄%s人  绿%s人%n",
                    risk.get("红"), risk.get("黄"), risk.get("绿"));
        }
    }

    /* ================================================================ 工具 */

    private Map<String, Object> user(String username, String realName, String role, Integer classId, String gender) {
        Map<String, Object> r = new HashMap<>();
        r.put("username", username);
        r.put("password_hash", authService.hash("123456"));
        r.put("real_name", realName);
        r.put("role", role);
        r.put("gender", gender);
        r.put("phone", "138" + String.format("%08d", random.nextInt(100000000)));
        r.put("class_id", classId);
        return r;
    }

    /** 按比例抽取能力档位，返回 {出勤率基准, 正确率基准, 作业均分基准}（叠加高斯噪声） */
    private double[] pickTier() {
        double r = random.nextDouble(), acc = 0;
        for (double[] t : TIERS) {
            acc += t[0];
            if (r <= acc) {
                return new double[]{
                        clamp(random.nextGaussian() * 0.05 + t[1], 0.45, 1.0),
                        clamp(random.nextGaussian() * 0.07 + t[2], 0.12, 0.98),
                        random.nextGaussian() * 2.5 + t[3]
                };
            }
        }
        double[] last = TIERS[TIERS.length - 1];
        return new double[]{last[1], last[2], last[3]};
    }

    private Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private int idOf(String username) {
        return ((Number) userMapper.findByUsername(username).get("user_id")).intValue();
    }

    /* ---------------- 作业：评分标准与提交内容 ---------------- */

    /** 按作业类型生成评分标准 JSON */
    private String rubricOf(String type, List<String> kpNames) {
        List<String> kw = kpNames.size() >= 5 ? kpNames.subList(0, 5) : kpNames;
        return switch (type) {
            case "客观题" -> "{\"count\":10}";
            case "主观题" -> "{\"min_words\":150,\"keywords\":" + jsonArray(kw) + "}";
            case "作文" -> "{\"min_words\":400,\"keywords\":" + jsonArray(kw) + "}";
            default -> "{\"cases\":5,\"required\":[\"def \",\"for \",\"if \",\"return\"]}";
        };
    }

    /**
     * 生成一份提交（内容与成绩）。
     * ★ 关键设计：客观题的教师成绩直接由作答正确率计算，因此 AI 规则判分
     *   与该成绩必然一致（准确率可验证为 100%）；主观题/作文/编程题的成绩
     *   由能力潜变量加噪声生成，用于体现 AI 初评与教师给分存在偏差、必须复核。
     */
    private Map<String, Object> submissionOf(String type, double[] p,
                                             List<Map<String, Object>> questions,
                                             Map<Integer, Integer> kpOrder,
                                             List<String> kpNames) {
        return switch (type) {
            case "客观题" -> objectiveSubmission(p, questions, kpOrder);
            // 注意：必须返回可变 Map，调用方后续还要 put assign_id / student_id
            case "作文" -> new HashMap<>(Map.of("content", essayContent(p, kpNames),
                    "score", round1(clamp(random.nextGaussian() * 9 + p[2], 0, 100))));
            case "编程题" -> new HashMap<>(Map.of("content", programContent(p),
                    "score", round1(clamp(random.nextGaussian() * 11 + p[2], 0, 100))));
            default -> new HashMap<>(Map.of("content", subjectiveContent(p, kpNames),
                    "score", round1(clamp(random.nextGaussian() * 9 + p[2], 0, 100))));
        };
    }

    /** 客观题：生成结构化 JSON 作答，正确率由能力潜变量驱动；成绩 = 正确率×100 */
    private Map<String, Object> objectiveSubmission(double[] p,
                                                    List<Map<String, Object>> questions,
                                                    Map<Integer, Integer> kpOrder) {
        List<Map<String, Object>> pool = new ArrayList<>(questions);
        Collections.shuffle(pool, random);
        int n = Math.min(10, pool.size());

        StringBuilder sb = new StringBuilder("{\"answers\":[");
        int correct = 0;
        for (int i = 0; i < n; i++) {
            Map<String, Object> q = pool.get(i);
            int qid = ((Number) q.get("q_id")).intValue();
            String std = String.valueOf(q.get("answer")).trim().toUpperCase();
            int order = kpOrder.getOrDefault(((Number) q.get("kp_id")).intValue(), 1);
            double factor = Arrays.stream(WEAK_KP_ORDERS).anyMatch(o -> o == order) ? WEAK_KP_FACTOR : 1.0;
            double diff = ((Number) q.get("difficulty")).doubleValue();
            // 用「学业水平潜变量」p[2] 而非互动正确率，使客观题得分与其他作业处于同一量纲，
            // 避免拉低班级均分、削弱"作业均分 vs 能力"的相关性
            double prob = clamp((p[2] / 100.0) * factor * (1 - 0.10 * diff), 0.05, 0.98);
            boolean ok = random.nextDouble() < prob;
            if (ok) correct++;
            String ans = ok ? std : wrongLetter(std);
            if (i > 0) sb.append(',');
            sb.append("{\"q_id\":").append(qid).append(",\"answer\":\"").append(ans).append("\"}");
        }
        sb.append("]}");

        Map<String, Object> m = new HashMap<>();
        m.put("content", sb.toString());
        m.put("score", round1(correct * 100.0 / Math.max(n, 1)));
        return m;
    }

    private String wrongLetter(String std) {
        List<String> all = new ArrayList<>(List.of("A", "B", "C", "D"));
        all.remove(std);
        return all.get(random.nextInt(all.size()));
    }

    /** 主观题：按能力决定命中得分点的数量与篇幅 */
    private String subjectiveContent(double[] p, List<String> kws) {
        double ability = p[1];
        StringBuilder sb = new StringBuilder("本题作答：\n");
        List<String> pool = new ArrayList<>(kws);
        Collections.shuffle(pool, random);
        int hit = (int) Math.round(pool.size() * clamp(ability + random.nextGaussian() * 0.08, 0.1, 1.0));
        for (int i = 0; i < hit && i < pool.size(); i++) {
            sb.append("关于「").append(pool.get(i)).append("」，其核心在于理解基本概念，并结合课堂示例进行分析。\n");
        }
        int target = (int) (150 * clamp(ability * 0.9 + random.nextDouble() * 0.45, 0.35, 1.3));
        while (plainLength(sb.toString()) < target) {
            sb.append("进一步分析可知，该部分内容需要结合课后练习加以巩固与验证。\n");
        }
        return sb.toString();
    }

    /** 作文：篇幅更长，能力高的学生衔接词更丰富 */
    private String essayContent(double[] p, List<String> kws) {
        double ability = p[1];
        String[] conn = {"首先", "其次", "然而", "因此", "综上"};
        StringBuilder sb = new StringBuilder();
        List<String> pool = new ArrayList<>(kws);
        Collections.shuffle(pool, random);
        int hit = (int) Math.round(pool.size() * clamp(ability + random.nextGaussian() * 0.08, 0.1, 1.0));
        for (int i = 0; i < hit && i < pool.size(); i++) {
            sb.append(conn[Math.min(i, conn.length - 1)]).append("，从「")
                    .append(pool.get(i)).append("」的角度来看，这一问题具有深入探讨的价值。\n\n");
        }
        int target = (int) (400 * clamp(ability * 0.85 + random.nextDouble() * 0.4, 0.3, 1.25));
        while (plainLength(sb.toString()) < target) {
            sb.append("结合课堂所学，我认为需要在理论与实践之间建立联系，并在后续学习中不断完善自己的理解。\n\n");
        }
        return sb.toString();
    }

    /** 编程题：按能力决定代码结构是否完整 */
    private String programContent(double[] p) {
        StringBuilder sb = new StringBuilder("# 学生提交的代码\n");
        sb.append("def solve(data):\n");
        if (random.nextDouble() < p[1]) sb.append("    result = []\n");
        if (random.nextDouble() < p[1]) {
            sb.append("    for x in data:\n");
            if (random.nextDouble() < p[1]) {
                sb.append("        if x is not None:\n            result.append(x)\n");
            } else {
                sb.append("        result.append(x)\n");
            }
        } else {
            sb.append("    result = list(data)\n");
        }
        if (random.nextDouble() < p[1]) sb.append("    return result\n");
        if (random.nextDouble() < p[1] * 0.9) sb.append("# 思路：遍历输入并过滤后返回结果\n");
        return sb.toString();
    }

    private int plainLength(String s) {
        return s.replaceAll("\\s", "").length();
    }

    private double round1(double x) {
        return Math.round(x * 10) / 10.0;
    }

    private String jsonArray(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(items.get(i)).append('"');
        }
        return sb.append(']').toString();
    }

    private int rnd(int lo, int hi) {
        return lo + random.nextInt(hi - lo + 1);
    }

    /** 构造形如 "2026-09-07 07:45:12" 的时间字符串 */
    private String ts(String day, int hh, int mm) {
        return String.format("%s %02d:%02d:%02d", day, hh, mm, rnd(0, 59));
    }

    /** 只指定小时，分钟与秒随机 */
    private String ts(String day, int hh) {
        return ts(day, hh, rnd(0, 59));
    }

    private double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }

    private <T> List<List<T>> chunk(List<T> list, int size) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            out.add(new ArrayList<>(list.subList(i, Math.min(i + size, list.size()))));
        }
        return out;
    }
}
