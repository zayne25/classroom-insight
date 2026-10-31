# 数据库 E-R 设计（智课堂系统）

> 数据库：**MySQL Server 8.0**（InnoDB / utf8mb4） ｜ 共 15 张业务表 + 7 个统计视图
> 建库脚本：`db/schema.sql`（`CREATE DATABASE classroom` + 建表 + 索引 + 视图）

## 一、E-R 图

```mermaid
erDiagram
    CLASS ||--o{ USER : "拥有学生"
    USER  ||--o{ COURSE : "教师讲授"
    COURSE ||--o{ ENROLLMENT : "被选修"
    USER   ||--o{ ENROLLMENT : "选修"
    COURSE ||--o{ LESSON : "包含课时"
    COURSE ||--o{ KNOWLEDGE_POINT : "包含知识点"
    COURSE ||--o{ ASSIGNMENT : "布置作业"
    KNOWLEDGE_POINT ||--o{ QUESTION : "关联题目"
    LESSON ||--o{ ATTENDANCE : "产生签到"
    USER   ||--o{ ATTENDANCE : "签到"
    LESSON ||--o{ INTERACTION : "发起互动"
    INTERACTION ||--o{ INTERACTION_RECORD : "学生响应"
    USER   ||--o{ INTERACTION_RECORD : "参与"
    ASSIGNMENT ||--o{ SUBMISSION : "提交"
    USER   ||--o{ SUBMISSION : "提交"
    QUESTION ||--o{ ANSWER_RECORD : "作答"
    USER   ||--o{ ANSWER_RECORD : "答题"
    USER   ||--o{ LEARNING_EVENT : "行为日志"

    CLASS {
        int class_id PK
        string class_name
        string grade
        string major
        string headteacher
    }
    USER {
        int user_id PK
        string username UK
        string password_hash
        string real_name
        string role "teacher/student/admin"
        int class_id FK
    }
    COURSE {
        int course_id PK
        string course_code UK
        string course_name
        real credit
        string semester
        int teacher_id FK
    }
    ENROLLMENT {
        int enroll_id PK
        int course_id FK
        int student_id FK
    }
    LESSON {
        int lesson_id PK
        int course_id FK
        int lesson_no
        string title
        string lesson_date
        string checkin_code
        string status
    }
    ATTENDANCE {
        int att_id PK
        int lesson_id FK
        int student_id FK
        string status "正常/迟到/缺勤/请假"
        string check_in_time
    }
    INTERACTION {
        int inter_id PK
        int lesson_id FK
        string type "抢答/随机点名/投票/提问"
        string title
        string options_json
        string correct_answer
        int gradeable "1=计分 0=不计分"
        int score
    }
    INTERACTION_RECORD {
        int rec_id PK
        int inter_id FK
        int student_id FK
        string answer
        int is_correct
        int score
    }
    ASSIGNMENT {
        int assign_id PK
        int course_id FK
        string title
        int total_score
        string deadline
    }
    SUBMISSION {
        int sub_id PK
        int assign_id FK
        int student_id FK
        real score
    }
    KNOWLEDGE_POINT {
        int kp_id PK
        int course_id FK
        string kp_name
        int kp_order
        real weight
    }
    QUESTION {
        int q_id PK
        int kp_id FK
        string q_type
        string content
        string answer
        real difficulty
    }
    ANSWER_RECORD {
        int ans_id PK
        int q_id FK
        int student_id FK
        int is_correct
    }
    LEARNING_EVENT {
        int event_id PK
        int student_id FK
        int course_id FK
        string event_type
    }
    AI_GRADING_RESULT {
        int result_id PK
        int sub_id FK
        int assign_id FK
        int student_id FK
        string assign_type
        string engine
        string model
        real ai_score
        int max_score
        real confidence
        string comment
        string detail_json
        int cost_ms
        string review_status
        real final_score
        int reviewed_by FK
    }
```

## 二、实体与联系说明

| 联系 | 类型 | 说明 |
|---|---|---|
| 班级 — 学生 | 1 : N | `users.class_id` 引用 `classes` |
| 教师 — 课程 | 1 : N | `courses.teacher_id` 引用 `users` |
| 课程 — 学生 | M : N | 由联系表 `enrollments` 拆解为两个 1:N |
| 课程 — 课时 | 1 : N | 一门课 16 个课时 |
| 课时 — 签到 | 1 : N | 唯一约束 `(lesson_id, student_id)` 防重复签到 |
| 课时 — 互动 | 1 : N | 抢答 / 随机点名 / 投票 / 提问 |
| 互动 — 互动记录 | 1 : N | 记录学生响应与得分 |
| 课程 — 作业 — 提交 | 1 : N : N | 作业隶属于课程，提交关联作业与学生 |
| 课程 — 知识点 — 题目 — 答题 | 1:N:N:N | 支撑知识点掌握度分析 |
| 学生 — 行为日志 | 1 : N | 记录登录、签到、互动等行为 |
| 提交 — AI 批改结果 | 1 : 1 | 一份提交仅一条批改结果（`UNIQUE(sub_id)`）；`ai_score` 与 `final_score` 并存，保留 AI 原始输出以便评测准确率与审计 |
| 教师 — 批改复核 | 1 : N | `reviewed_by` 记录复核教师，`reviewed_at` 记录复核时间 |

## 三、完整性约束设计

1. **实体完整性**：全部表均设 `INT AUTO_INCREMENT PRIMARY KEY` 主键（InnoDB 聚簇索引）。
2. **参照完整性**：18 处显式外键；`enrollments / attendances / interaction_records` 采用 `ON DELETE CASCADE`，`users.class_id` 采用 `ON DELETE SET NULL`。
3. **域完整性（用户自定义）**：
   - `users.role CHECK IN ('teacher','student','admin')`
   - `attendances.status CHECK IN ('正常','迟到','缺勤','请假')`
   - `interactions.type CHECK IN ('抢答','随机点名','投票','提问')`
   - `submissions.score CHECK BETWEEN 0 AND 100`
   - `questions.difficulty CHECK BETWEEN 0 AND 1`
   - `assignments.assign_type CHECK IN ('客观题','主观题','编程题','作文')`
   - `ai_grading_results.review_status CHECK IN ('待复核','已确认','已调整')`
   - `ai_grading_results.confidence CHECK BETWEEN 0 AND 1`
4. **唯一性约束**：
   - `UNIQUE(course_id, student_id)` 防重复选课
   - `UNIQUE(lesson_id, student_id)` 防重复签到
   - `UNIQUE(inter_id, student_id)` 防重复作答
   - `UNIQUE(assign_id, student_id)` 防重复提交
   - `UNIQUE(sub_id)`（ai_grading_results）保证一份提交只有一条 AI 批改结果
5. **范式说明**：设计满足第三范式（3NF）。为便于统计查询，在数据库层建立 7 个视图（非冗余表），既避免数据冗余，又简化上层查询。

## 四、统计视图

| 视图 | 用途 |
|---|---|
| `v_lesson_attendance` | 每课时出勤人数与迟到人数 → 出勤趋势图 |
| `v_student_kpi` | 学生出勤率、互动参与率、互动正确率、作业均分、综合学情分 |
| `v_student_risk` | 在 `v_student_kpi` 上做红/黄/绿风险分级 |
| `v_kp_mastery` | 班级各知识点正确率 → 雷达图 |
| `v_student_kp_mastery` | 学生个人各知识点正确率 → 个人雷达图 |
| `v_interaction_stat` | 单次互动的实时参与数与正确数 → 实时看板 |
| `v_ai_grading_stat` | AI 批改覆盖率、平均分、平均置信度、平均耗时、待复核量、已调整量、**AI 初评分与最终分的平均绝对偏差** |

## 五、指标计算口径

| 指标 | 计算公式 |
|---|---|
| 出勤率 | (正常 + 迟到) / 总课时数 |
| 互动参与率 | 已响应互动数 / 已发布互动总数 |
| 互动正确率 | 答对互动数 / 已响应互动数 |
| 知识点掌握度 | 该知识点答对数 / 该知识点总答题数 |
| 综合学情分 | 0.3×出勤率×100 + 0.3×[100×(0.5×参与率+0.5×正确率)] + 0.4×作业均分 |
| 风险分级 | 综合分<60 或 出勤率<0.70 → 红；综合分<75 或 出勤率<0.85 → 黄；否则绿 |
| 客观题 AI 判分 | Σ(作答 == `questions.answer` ? 满分/题数 : 0)，置信度固定 1.0 |
| 主观题 AI 判分 | 满分 ×(0.50×得分点覆盖率 + 0.30×篇幅达标度 + 0.20×结构完整性) |
| 作文 AI 判分 | 满分 ×(0.40×立意要点覆盖率 + 0.30×篇幅达标度 + 0.30×篇章连贯性) |
| 编程题 AI 判分 | 满分 ×(0.50×必需结构命中率 + 0.30×代码结构 + 0.20×注释)，置信度 0.65（静态检查） |
| AI 初评可用性 | 平均绝对偏差 = AVG(\|final_score − ai_score\|)，仅统计已复核记录 |
