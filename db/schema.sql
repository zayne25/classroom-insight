-- ============================================================
-- 智课堂 · 大学课堂互动与学情分析系统
-- 数据库：MySQL 8.0     字符集：utf8mb4     引擎：InnoDB
-- 本文件包含：建库 + 20 张业务表 + 17 个索引 + 7 个统计视图
-- 说明：
--   1) MySQL 8.0.16 起 CHECK 约束才被真正强制执行，本设计大量使用 CHECK
--      将业务规则（角色、签到状态、互动类型、分数区间）固化在数据库层；
--   2) InnoDB 支持外键与事务，所有外键显式声明 ON DELETE 行为；
--   3) 统计口径用视图封装，聚合在数据库层完成，应用层只做展示。
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

DROP DATABASE IF EXISTS classroom;
CREATE DATABASE classroom DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE classroom;


-- ============================================================
-- 一、组织层
-- ============================================================

-- 1. 班级表
CREATE TABLE classes (
    class_id    INT AUTO_INCREMENT PRIMARY KEY,
    class_name  VARCHAR(64)  NOT NULL UNIQUE COMMENT '班级名称',
    grade       VARCHAR(32)  NOT NULL COMMENT '年级',
    major       VARCHAR(64)  NOT NULL COMMENT '专业',
    headteacher VARCHAR(32)           COMMENT '班主任',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '班级表';

-- 2. 用户表（教师 / 学生 / 管理员共用，role 字段区分角色）
CREATE TABLE users (
    user_id       INT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(32) NOT NULL UNIQUE COMMENT '登录账号（教师为工号，学生为学号）',
    password_hash VARCHAR(128) NOT NULL COMMENT '密码哈希（BCrypt，禁止明文）',
    real_name     VARCHAR(32) NOT NULL COMMENT '姓名',
    role          VARCHAR(16) NOT NULL COMMENT '角色：teacher / student / admin',
    gender        VARCHAR(4)           COMMENT '性别',
    phone         VARCHAR(20)          COMMENT '联系电话',
    class_id      INT                  COMMENT '所属班级（教师为空）',
    status        VARCHAR(8)   NOT NULL DEFAULT '正常' COMMENT '账号状态：正常 / 停用',
    login_fail_count INT       NOT NULL DEFAULT 0 COMMENT '连续登录失败次数（锁定策略用）',
    locked_until  DATETIME              COMMENT '锁定截止时间（为空表示未锁定）',
    last_login_at DATETIME              COMMENT '最近登录时间',
    last_login_ip VARCHAR(64)           COMMENT '最近登录IP',
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_user_role CHECK (role IN ('teacher', 'student', 'admin')),
    CONSTRAINT ck_user_gender CHECK (gender IS NULL OR gender IN ('男', '女')),
    CONSTRAINT ck_user_status CHECK (status IN ('正常', '停用')),
    CONSTRAINT fk_user_class FOREIGN KEY (class_id) REFERENCES classes (class_id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户表';

-- 3. 课程表
CREATE TABLE courses (
    course_id   INT AUTO_INCREMENT PRIMARY KEY,
    course_code VARCHAR(32) NOT NULL UNIQUE COMMENT '课程代码',
    course_name VARCHAR(64) NOT NULL COMMENT '课程名称',
    credit      DECIMAL(3, 1) NOT NULL DEFAULT 2.0 COMMENT '学分',
    semester    VARCHAR(32) NOT NULL COMMENT '开课学期',
    teacher_id  INT         NOT NULL COMMENT '授课教师',
    description TEXT                 COMMENT '课程简介',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_course_teacher FOREIGN KEY (teacher_id) REFERENCES users (user_id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '课程表';

-- 4. 选课表（课程与学生之间的多对多联系，UNIQUE 防重复选课）
CREATE TABLE enrollments (
    enroll_id   INT AUTO_INCREMENT PRIMARY KEY,
    course_id   INT      NOT NULL,
    student_id  INT      NOT NULL,
    enrolled_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_enroll (course_id, student_id),
    CONSTRAINT fk_enroll_course  FOREIGN KEY (course_id)  REFERENCES courses (course_id) ON DELETE CASCADE,
    CONSTRAINT fk_enroll_student FOREIGN KEY (student_id) REFERENCES users (user_id)     ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '选课表';


-- ============================================================
-- 二、教学层（课堂过程）
-- ============================================================

-- 5. 课时表（一门课程包含多个课时）
CREATE TABLE lessons (
    lesson_id    INT AUTO_INCREMENT PRIMARY KEY,
    course_id    INT         NOT NULL,
    lesson_no    INT         NOT NULL COMMENT '讲次',
    title        VARCHAR(128) NOT NULL COMMENT '课堂主题',
    lesson_date  DATE        NOT NULL COMMENT '上课日期',
    start_time   TIME                 COMMENT '开始时间',
    end_time     TIME                 COMMENT '结束时间',
    checkin_code VARCHAR(8)           COMMENT '4 位签到码（课堂结束后清空）',
    status       VARCHAR(8)  NOT NULL DEFAULT '未开始' COMMENT '未开始/进行中/已结束',
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_lesson_no (course_id, lesson_no),
    CONSTRAINT ck_lesson_status CHECK (status IN ('未开始', '进行中', '已结束')),
    CONSTRAINT fk_lesson_course FOREIGN KEY (course_id) REFERENCES courses (course_id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '课时表';

-- 6. 签到表（UNIQUE(lesson_id, student_id) 保证一名学生一个课时只能签一次）
CREATE TABLE attendances (
    att_id        INT AUTO_INCREMENT PRIMARY KEY,
    lesson_id     INT      NOT NULL,
    student_id    INT      NOT NULL,
    status        VARCHAR(8) NOT NULL COMMENT '正常/迟到/缺勤/请假',
    method        VARCHAR(8) NOT NULL DEFAULT '签到码' COMMENT '签到方式',
    check_in_time DATETIME           COMMENT '签到时间',
    UNIQUE KEY uk_att (lesson_id, student_id),
    CONSTRAINT ck_att_status CHECK (status IN ('正常', '迟到', '缺勤', '请假')),
    CONSTRAINT ck_att_method CHECK (method IN ('签到码', '一键', '补签')),
    CONSTRAINT fk_att_lesson  FOREIGN KEY (lesson_id)  REFERENCES lessons (lesson_id) ON DELETE CASCADE,
    CONSTRAINT fk_att_student FOREIGN KEY (student_id) REFERENCES users (user_id)     ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '课堂签到表';

-- 7. 课堂互动表（抢答 / 随机点名 / 投票 / 提问）
CREATE TABLE interactions (
    inter_id       INT AUTO_INCREMENT PRIMARY KEY,
    lesson_id      INT          NOT NULL,
    type           VARCHAR(16)  NOT NULL COMMENT '抢答/随机点名/投票/提问',
    title          VARCHAR(128) NOT NULL COMMENT '互动标题',
    content        TEXT                  COMMENT '互动正文',
    options_json   VARCHAR(512)          COMMENT '选项，以 | 分隔，如 A.xxx|B.xxx',
    correct_answer VARCHAR(8)            COMMENT '标准答案（投票/提问可为空）',
    gradeable      TINYINT      NOT NULL DEFAULT 1 COMMENT '1=可判对错计入正确率；0=仅计参与',
    score          INT          NOT NULL DEFAULT 2 COMMENT '本次互动分值',
    status         VARCHAR(8)   NOT NULL DEFAULT '已发布' COMMENT '草稿/已发布/已结束',
    published_at   DATETIME              COMMENT '发布时间',
    deadline       DATETIME              COMMENT '截止时间',
    CONSTRAINT ck_inter_type   CHECK (type IN ('抢答', '随机点名', '投票', '提问')),
    CONSTRAINT ck_inter_status CHECK (status IN ('草稿', '已发布', '已结束')),
    CONSTRAINT ck_inter_grade  CHECK (gradeable IN (0, 1)),
    CONSTRAINT ck_inter_score  CHECK (score >= 0),
    CONSTRAINT fk_inter_lesson FOREIGN KEY (lesson_id) REFERENCES lessons (lesson_id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '课堂互动表';

-- 8. 互动记录表（学生对互动的响应，UNIQUE 防重复作答）
CREATE TABLE interaction_records (
    rec_id       INT AUTO_INCREMENT PRIMARY KEY,
    inter_id     INT      NOT NULL,
    student_id   INT      NOT NULL,
    answer       VARCHAR(64)      COMMENT '学生的答案',
    is_correct   TINYINT  NOT NULL DEFAULT 0 COMMENT '是否正确',
    score        INT      NOT NULL DEFAULT 0 COMMENT '本次获得分数',
    responded_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ir (inter_id, student_id),
    CONSTRAINT ck_ir_correct CHECK (is_correct IN (0, 1)),
    CONSTRAINT fk_ir_inter   FOREIGN KEY (inter_id)   REFERENCES interactions (inter_id) ON DELETE CASCADE,
    CONSTRAINT fk_ir_student FOREIGN KEY (student_id) REFERENCES users (user_id)         ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '互动记录表';


-- ============================================================
-- 三、作业层
-- ============================================================

-- 9. 作业表
CREATE TABLE assignments (
    assign_id   INT AUTO_INCREMENT PRIMARY KEY,
    course_id   INT          NOT NULL,
    title       VARCHAR(128) NOT NULL COMMENT '作业标题',
    description TEXT                  COMMENT '作业要求',
    assign_type VARCHAR(16)  NOT NULL DEFAULT '客观题' COMMENT '作业类型：客观题/主观题/编程题/作文',
    ai_rubric   TEXT                  COMMENT '评分标准JSON：分项名称、分值权重、关键词/测试用例',
    total_score INT          NOT NULL DEFAULT 100 COMMENT '满分',
    assigned_at DATE         NOT NULL COMMENT '发布日期',
    deadline    DATE         NOT NULL COMMENT '截止日期',
    CONSTRAINT ck_assign_score CHECK (total_score > 0),
    CONSTRAINT ck_assign_type  CHECK (assign_type IN ('客观题', '主观题', '编程题', '作文')),
    CONSTRAINT fk_assign_course FOREIGN KEY (course_id) REFERENCES courses (course_id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '作业表';

-- 10. 作业提交表（UNIQUE 防重复提交）
CREATE TABLE submissions (
    sub_id       INT AUTO_INCREMENT PRIMARY KEY,
    assign_id    INT           NOT NULL,
    student_id   INT           NOT NULL,
    content      TEXT                   COMMENT '提交内容',
    score        DECIMAL(5, 2)          COMMENT '成绩（NULL 表示未批改）',
    submitted_at DATETIME               COMMENT '提交时间',
    UNIQUE KEY uk_sub (assign_id, student_id),
    CONSTRAINT ck_sub_score CHECK (score IS NULL OR (score >= 0 AND score <= 100)),
    CONSTRAINT fk_sub_assign  FOREIGN KEY (assign_id)  REFERENCES assignments (assign_id) ON DELETE CASCADE,
    CONSTRAINT fk_sub_student FOREIGN KEY (student_id) REFERENCES users (user_id)         ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '作业提交表';

-- 10-1. AI 批改结果表（★ AI 批改作业功能的核心表）
-- 设计要点：AI 初评与教师复核分离保存，ai_score 与 final_score 并存，
--           既保留 AI 原始输出（可审计、可评测准确率），又保证教师拥有最终裁定权。
CREATE TABLE ai_grading_results (
    result_id      INT AUTO_INCREMENT PRIMARY KEY,
    sub_id         INT           NOT NULL,
    assign_id      INT           NOT NULL,
    student_id     INT           NOT NULL,
    assign_type    VARCHAR(16)   NOT NULL COMMENT '作业类型',
    engine         VARCHAR(32)   NOT NULL COMMENT '批改引擎：RULE_OBJECTIVE / LLM_SUBJECTIVE / PROGRAM_JUDGE / RULE_ESSAY',
    model          VARCHAR(64)            COMMENT '模型标识，如 local-heuristic-v1 / qwen-plus',
    ai_score       DECIMAL(5, 2) NOT NULL COMMENT 'AI 初评分数',
    max_score      INT           NOT NULL COMMENT '本次满分',
    confidence     DECIMAL(4, 3) NOT NULL COMMENT '置信度 0~1，低于阈值强制人工复核',
    comment        TEXT                   COMMENT '总体评语',
    detail_json    TEXT                   COMMENT '逐项反馈JSON：得分点/错误定位/修改建议',
    cost_ms        INT                    COMMENT '单份批改耗时(毫秒)',
    review_status  VARCHAR(16)   NOT NULL DEFAULT '待复核' COMMENT '待复核/已确认/已调整',
    final_score    DECIMAL(5, 2)          COMMENT '教师复核后的最终分（教师裁定优先）',
    teacher_comment TEXT                  COMMENT '教师复核评语',
    reviewed_by    INT                    COMMENT '复核教师 user_id',
    reviewed_at    DATETIME               COMMENT '复核时间',
    created_at     DATETIME     NOT NULL  COMMENT '批改时间',
    UNIQUE KEY uk_ai_sub (sub_id) COMMENT '一份提交仅保留一条批改结果',
    KEY idx_ai_assign (assign_id),
    KEY idx_ai_review (review_status),
    CONSTRAINT ck_ai_review  CHECK (review_status IN ('待复核', '已确认', '已调整')),
    CONSTRAINT ck_ai_conf    CHECK (confidence >= 0 AND confidence <= 1),
    CONSTRAINT fk_ai_sub     FOREIGN KEY (sub_id)     REFERENCES submissions (sub_id)     ON DELETE CASCADE,
    CONSTRAINT fk_ai_assign  FOREIGN KEY (assign_id)  REFERENCES assignments (assign_id)  ON DELETE CASCADE,
    CONSTRAINT fk_ai_student FOREIGN KEY (student_id) REFERENCES users (user_id)          ON DELETE CASCADE,
    CONSTRAINT fk_ai_reviewer FOREIGN KEY (reviewed_by) REFERENCES users (user_id)        ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AI批改结果表';


-- ============================================================
-- 四、知识与测评层（支撑知识点掌握度分析）
-- ============================================================

-- 11. 知识点表
CREATE TABLE knowledge_points (
    kp_id       INT AUTO_INCREMENT PRIMARY KEY,
    course_id   INT          NOT NULL,
    kp_name     VARCHAR(64)  NOT NULL COMMENT '知识点名称',
    kp_order    INT          NOT NULL COMMENT '章节顺序',
    weight      DECIMAL(3, 1) NOT NULL DEFAULT 1.0 COMMENT '权重',
    description TEXT                  COMMENT '知识点说明',
    UNIQUE KEY uk_kp_order (course_id, kp_order),
    CONSTRAINT fk_kp_course FOREIGN KEY (course_id) REFERENCES courses (course_id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '知识点表';

-- 12. 题库表
CREATE TABLE questions (
    q_id         INT AUTO_INCREMENT PRIMARY KEY,
    kp_id        INT          NOT NULL,
    q_type       VARCHAR(8)   NOT NULL COMMENT '单选/多选/判断/简答',
    content      TEXT         NOT NULL COMMENT '题干',
    options_json VARCHAR(512)          COMMENT '选项，以 | 分隔',
    answer       VARCHAR(16)  NOT NULL COMMENT '标准答案',
    difficulty   DECIMAL(3, 2) NOT NULL DEFAULT 0.5 COMMENT '难度系数 0~1',
    CONSTRAINT ck_q_type CHECK (q_type IN ('单选', '多选', '判断', '简答')),
    CONSTRAINT ck_q_diff CHECK (difficulty BETWEEN 0 AND 1),
    CONSTRAINT fk_q_kp FOREIGN KEY (kp_id) REFERENCES knowledge_points (kp_id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '题库表';

-- 13. 答题记录表
CREATE TABLE answer_records (
    ans_id      INT AUTO_INCREMENT PRIMARY KEY,
    q_id        INT      NOT NULL,
    student_id  INT      NOT NULL,
    lesson_id   INT               COMMENT '产生该答题的课时',
    is_correct  TINYINT  NOT NULL COMMENT '是否正确',
    answered_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_ar_correct CHECK (is_correct IN (0, 1)),
    CONSTRAINT fk_ar_question FOREIGN KEY (q_id)       REFERENCES questions (q_id)  ON DELETE CASCADE,
    CONSTRAINT fk_ar_student  FOREIGN KEY (student_id) REFERENCES users (user_id)   ON DELETE CASCADE,
    CONSTRAINT fk_ar_lesson   FOREIGN KEY (lesson_id)  REFERENCES lessons (lesson_id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '答题记录表';


-- ============================================================
-- 五、行为日志层
-- ============================================================

-- 14. 学习行为日志表
CREATE TABLE learning_events (
    event_id     INT AUTO_INCREMENT PRIMARY KEY,
    student_id   INT          NOT NULL,
    course_id    INT                   COMMENT '关联课程（可为空）',
    lesson_id    INT                   COMMENT '关联课时（可为空）',
    event_type   VARCHAR(32)  NOT NULL COMMENT '登录/进入课堂/参与互动/提交作业...',
    event_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    payload_json VARCHAR(512)          COMMENT '附加信息',
    CONSTRAINT fk_ev_student FOREIGN KEY (student_id) REFERENCES users (user_id)     ON DELETE CASCADE,
    CONSTRAINT fk_ev_course  FOREIGN KEY (course_id)  REFERENCES courses (course_id) ON DELETE CASCADE,
    CONSTRAINT fk_ev_lesson  FOREIGN KEY (lesson_id)  REFERENCES lessons (lesson_id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '学习行为日志表';


-- ============================================================
-- 六、系统管理层（管理员模块：配置 / 权限 / 审计 / 备份）
-- ============================================================

-- 13. 系统配置表（★ 参数在线可调，修改留痕）
-- 设计要点：值统一以字符串存储，按 config_type 解析；editable=0 表示该项需重启才能生效，
--           避免管理员误以为"保存即生效"。
CREATE TABLE sys_config (
    config_id    INT AUTO_INCREMENT PRIMARY KEY,
    config_key   VARCHAR(64)  NOT NULL UNIQUE COMMENT '配置键，如 risk.red_score',
    config_value VARCHAR(512) NOT NULL          COMMENT '配置值',
    config_type  VARCHAR(16)  NOT NULL DEFAULT 'string' COMMENT '值类型：string/int/decimal/bool',
    config_group VARCHAR(32)  NOT NULL          COMMENT '分组：基础 / 安全 / 预警 / 批改',
    name         VARCHAR(64)  NOT NULL          COMMENT '显示名称',
    description  VARCHAR(256)                   COMMENT '说明（取值范围与生效方式）',
    editable     TINYINT      NOT NULL DEFAULT 1 COMMENT '是否可在线修改（0=只读）',
    updated_by   INT                            COMMENT '最后修改人 user_id',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT ck_cfg_type CHECK (config_type IN ('string', 'int', 'decimal', 'bool'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '系统配置表';

-- 14. 权限点表
CREATE TABLE sys_permission (
    perm_id    INT AUTO_INCREMENT PRIMARY KEY,
    perm_code  VARCHAR(64) NOT NULL UNIQUE COMMENT '权限标识，如 user:create',
    perm_name  VARCHAR(64) NOT NULL        COMMENT '权限名称',
    perm_group VARCHAR(32) NOT NULL        COMMENT '分组：用户管理 / 权限配置 / ...',
    sort_no    INT         NOT NULL DEFAULT 0,
    builtin    TINYINT     NOT NULL DEFAULT 1 COMMENT '内置权限，不允许删除'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '权限点表';

-- 15. 角色-权限关联表（★ 权限分配的实际落点）
-- 说明：角色与权限是多对多，这里用「角色 × 权限 × 开关」三元组表达，
--       管理员在界面上勾选即写本表，无需改代码。
CREATE TABLE role_permission (
    id        INT AUTO_INCREMENT PRIMARY KEY,
    role      VARCHAR(16) NOT NULL COMMENT 'teacher / student / admin',
    perm_code VARCHAR(64) NOT NULL COMMENT '权限标识',
    enabled   TINYINT     NOT NULL DEFAULT 1 COMMENT '是否启用',
    UNIQUE KEY uk_role_perm (role, perm_code),
    CONSTRAINT ck_rp_role CHECK (role IN ('teacher', 'student', 'admin'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '角色权限表';

-- 16. 操作日志表（★ 审计追踪）
-- 设计要点：operator 账号做冗余存储——用户被删除后仍能追溯"谁做的"；
--           同时记录 IP、耗时、改前改后详情，满足审计的"可追溯、可还原"要求。
CREATE TABLE operation_logs (
    log_id      INT AUTO_INCREMENT PRIMARY KEY,
    operator_id INT                            COMMENT '操作人 user_id（系统任务为空）',
    operator    VARCHAR(32)                    COMMENT '操作人账号（冗余，防止用户被删后无法追溯）',
    module      VARCHAR(32) NOT NULL           COMMENT '模块：用户管理 / 权限配置 / 系统配置 / 数据备份 / 认证',
    operation   VARCHAR(64) NOT NULL           COMMENT '操作：新增用户 / 停用账号 / 修改配置 / 执行备份 ...',
    target      VARCHAR(128)                   COMMENT '操作对象，如 用户t001、配置risk.red_score',
    detail      VARCHAR(512)                   COMMENT '变更详情（改前 → 改后）',
    result      VARCHAR(16) NOT NULL DEFAULT '成功' COMMENT '成功 / 失败',
    fail_reason VARCHAR(256)                   COMMENT '失败原因',
    ip          VARCHAR(64)                    COMMENT '操作来源IP',
    user_agent  VARCHAR(256)                   COMMENT '客户端标识',
    cost_ms     INT                            COMMENT '耗时（毫秒）',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_log_result CHECK (result IN ('成功', '失败'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '操作日志表';

-- 17. 备份记录表
CREATE TABLE backup_records (
    backup_id   INT AUTO_INCREMENT PRIMARY KEY,
    file_name   VARCHAR(128) NOT NULL           COMMENT '备份文件名',
    file_path   VARCHAR(256) NOT NULL           COMMENT '存储绝对路径',
    file_size   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
    backup_type VARCHAR(16)  NOT NULL DEFAULT '手动' COMMENT '手动 / 恢复前自动',
    db_name     VARCHAR(64)  NOT NULL           COMMENT '数据库名',
    table_cnt   INT                             COMMENT '表数量',
    row_cnt     INT                             COMMENT '数据行数',
    status      VARCHAR(16)  NOT NULL DEFAULT '成功' COMMENT '成功 / 失败',
    message     VARCHAR(256)                    COMMENT '失败原因或备注',
    cost_ms     INT                             COMMENT '耗时（毫秒）',
    operator_id INT                             COMMENT '操作人 user_id',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_bk_status CHECK (status IN ('成功', '失败'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '数据备份记录表';


-- ============================================================
-- 七、索引（高频查询字段）
-- ============================================================

CREATE INDEX idx_att_lesson    ON attendances (lesson_id);
CREATE INDEX idx_att_student   ON attendances (student_id);
CREATE INDEX idx_ir_inter      ON interaction_records (inter_id);
CREATE INDEX idx_ir_student    ON interaction_records (student_id);
CREATE INDEX idx_ar_student    ON answer_records (student_id);
CREATE INDEX idx_ar_question   ON answer_records (q_id);
CREATE INDEX idx_sub_assign    ON submissions (assign_id);
CREATE INDEX idx_sub_student   ON submissions (student_id);
CREATE INDEX idx_lesson_course ON lessons (course_id);
CREATE INDEX idx_inter_lesson  ON interactions (lesson_id);
CREATE INDEX idx_question_kp   ON questions (kp_id);
CREATE INDEX idx_event_student ON learning_events (student_id);
CREATE INDEX idx_user_role     ON users (role);
CREATE INDEX idx_user_status   ON users (status);
-- 操作日志：审计查询以时间倒序为主，故对 created_at 建索引
CREATE INDEX idx_log_created   ON operation_logs (created_at);
CREATE INDEX idx_log_operator  ON operation_logs (operator_id);
CREATE INDEX idx_log_module    ON operation_logs (module);
CREATE INDEX idx_bk_created    ON backup_records (created_at);


-- ============================================================
-- 八、统计视图（聚合在数据库层完成，体现 SQL 统计能力）
-- ============================================================

-- 视图 1：每课时出勤情况（供出勤趋势图）
CREATE VIEW v_lesson_attendance AS
SELECT
    l.lesson_id,
    l.course_id,
    l.lesson_no,
    l.title,
    l.lesson_date,
    (SELECT COUNT(*) FROM enrollments e WHERE e.course_id = l.course_id)          AS total_stu,
    COALESCE(SUM(CASE WHEN a.status IN ('正常', '迟到') THEN 1 ELSE 0 END), 0)     AS attended_cnt,
    COALESCE(SUM(CASE WHEN a.status = '迟到' THEN 1 ELSE 0 END), 0)                AS late_cnt
FROM lessons l
LEFT JOIN attendances a ON a.lesson_id = l.lesson_id
GROUP BY l.lesson_id, l.course_id, l.lesson_no, l.title, l.lesson_date;


-- 视图 2：学生综合学情指标（核心视图，使用 CTE 分层聚合）
CREATE VIEW v_student_kpi AS
WITH lt AS (   -- 每门课课时总数
    SELECT course_id, COUNT(*) AS lesson_cnt FROM lessons GROUP BY course_id
),
it AS (        -- 每门课已发布互动总数
    SELECT l.course_id, COUNT(*) AS inter_cnt
    FROM interactions i JOIN lessons l ON l.lesson_id = i.lesson_id
    WHERE i.status <> '草稿'
    GROUP BY l.course_id
),
att AS (       -- 每生每课出勤统计
    SELECT l.course_id,
           a.student_id,
           SUM(CASE WHEN a.status IN ('正常', '迟到') THEN 1 ELSE 0 END) AS attended_cnt,
           SUM(CASE WHEN a.status = '迟到' THEN 1 ELSE 0 END)            AS late_cnt
    FROM attendances a JOIN lessons l ON l.lesson_id = a.lesson_id
    GROUP BY l.course_id, a.student_id
),
ir AS (        -- 每生每课互动统计（正确率只统计可计分互动）
    SELECT l.course_id,
           r.student_id,
           COUNT(*)                                         AS part_cnt,
           SUM(CASE WHEN i.gradeable = 1 THEN 1 ELSE 0 END) AS grade_part_cnt,
           SUM(r.is_correct)                                AS correct_cnt,
           SUM(r.score)                                     AS raw_score
    FROM interaction_records r
    JOIN interactions i ON i.inter_id = r.inter_id
    JOIN lessons l      ON l.lesson_id = i.lesson_id
    GROUP BY l.course_id, r.student_id
),
sb AS (        -- 每生每课作业成绩
    SELECT a.course_id,
           s.student_id,
           COUNT(*)    AS sub_cnt,
           AVG(s.score) AS avg_score
    FROM submissions s JOIN assignments a ON a.assign_id = s.assign_id
    WHERE s.score IS NOT NULL
    GROUP BY a.course_id, s.student_id
)
SELECT
    e.course_id,
    e.student_id,
    u.real_name,
    u.username,
    u.class_id,
    cl.class_name,
    COALESCE(lt.lesson_cnt, 0)   AS lesson_cnt,
    COALESCE(att.attended_cnt, 0) AS attended_cnt,
    COALESCE(att.late_cnt, 0)     AS late_cnt,
    -- 出勤率 = (正常 + 迟到) / 总课时
    ROUND(CASE WHEN COALESCE(lt.lesson_cnt, 0) = 0 THEN 0
               ELSE 1.0 * COALESCE(att.attended_cnt, 0) / lt.lesson_cnt END, 4) AS attendance_rate,
    COALESCE(ir.part_cnt, 0) AS interact_cnt,
    -- 互动参与率 = 已响应互动数 / 已发布互动总数
    ROUND(CASE WHEN COALESCE(it.inter_cnt, 0) = 0 THEN 0
               ELSE 1.0 * COALESCE(ir.part_cnt, 0) / it.inter_cnt END, 4) AS part_rate,
    -- 互动正确率 = 可计分互动答对数 / 可计分互动响应数
    ROUND(CASE WHEN COALESCE(ir.grade_part_cnt, 0) = 0 THEN 0
               ELSE 1.0 * COALESCE(ir.correct_cnt, 0) / ir.grade_part_cnt END, 4) AS inter_correct_rate,
    COALESCE(ir.raw_score, 0)     AS interact_raw_score,
    COALESCE(sb.sub_cnt, 0)       AS sub_cnt,
    ROUND(COALESCE(sb.avg_score, 0), 2) AS avg_score,
    -- 综合学情分 = 0.3×出勤分 + 0.3×互动分 + 0.4×作业均分
    -- 其中 互动分 = 100 × (0.5×参与率 + 0.5×正确率)
    ROUND(
        0.3 * (CASE WHEN COALESCE(lt.lesson_cnt, 0) = 0 THEN 0
                    ELSE 100.0 * COALESCE(att.attended_cnt, 0) / lt.lesson_cnt END)
      + 0.3 * (100.0 * (
            0.5 * (CASE WHEN COALESCE(it.inter_cnt, 0) = 0 THEN 0
                        ELSE 1.0 * COALESCE(ir.part_cnt, 0) / it.inter_cnt END)
          + 0.5 * (CASE WHEN COALESCE(ir.grade_part_cnt, 0) = 0 THEN 0
                        ELSE 1.0 * COALESCE(ir.correct_cnt, 0) / ir.grade_part_cnt END)))
      + 0.4 * COALESCE(sb.avg_score, 0)
    , 2) AS composite_score
FROM enrollments e
JOIN users u         ON u.user_id   = e.student_id
LEFT JOIN classes cl ON cl.class_id = u.class_id
LEFT JOIN lt  ON lt.course_id  = e.course_id
LEFT JOIN it  ON it.course_id  = e.course_id
LEFT JOIN att ON att.course_id = e.course_id AND att.student_id = e.student_id
LEFT JOIN ir  ON ir.course_id  = e.course_id AND ir.student_id  = e.student_id
LEFT JOIN sb  ON sb.course_id  = e.course_id AND sb.student_id  = e.student_id;


-- 视图 3：学生学业风险分级（红 / 黄 / 绿）
CREATE VIEW v_student_risk AS
SELECT k.*,
       CASE
           WHEN k.composite_score < 60 OR k.attendance_rate < 0.70 THEN '红'
           WHEN k.composite_score < 75 OR k.attendance_rate < 0.85 THEN '黄'
           ELSE '绿'
       END AS risk_level
FROM v_student_kpi k;


-- 视图 4：知识点班级掌握度
CREATE VIEW v_kp_mastery AS
SELECT
    kp.course_id,
    kp.kp_id,
    kp.kp_name,
    kp.kp_order,
    COUNT(ar.ans_id)                AS answer_cnt,
    COALESCE(SUM(ar.is_correct), 0) AS correct_cnt,
    ROUND(CASE WHEN COUNT(ar.ans_id) = 0 THEN 0
               ELSE 1.0 * SUM(ar.is_correct) / COUNT(ar.ans_id) END, 4) AS correct_rate
FROM knowledge_points kp
LEFT JOIN questions q       ON q.kp_id  = kp.kp_id
LEFT JOIN answer_records ar ON ar.q_id  = q.q_id
GROUP BY kp.kp_id, kp.course_id, kp.kp_name, kp.kp_order;


-- 视图 5：学生个人知识点掌握度
CREATE VIEW v_student_kp_mastery AS
SELECT
    kp.course_id,
    ar.student_id,
    kp.kp_id,
    kp.kp_name,
    kp.kp_order,
    COUNT(*)           AS answer_cnt,
    SUM(ar.is_correct) AS correct_cnt,
    ROUND(1.0 * SUM(ar.is_correct) / COUNT(*), 4) AS correct_rate
FROM answer_records ar
JOIN questions q         ON q.q_id   = ar.q_id
JOIN knowledge_points kp ON kp.kp_id = q.kp_id
GROUP BY kp.kp_id, ar.student_id, kp.course_id, kp.kp_name, kp.kp_order;


-- 视图 6：单次互动的实时统计（供课堂实时看板）
CREATE VIEW v_interaction_stat AS
SELECT
    i.inter_id,
    i.lesson_id,
    l.course_id,
    i.type,
    i.title,
    i.gradeable,
    i.correct_answer,
    i.options_json,
    i.status,
    (SELECT COUNT(*) FROM enrollments e WHERE e.course_id = l.course_id) AS total_stu,
    COUNT(r.rec_id)                AS respond_cnt,
    COALESCE(SUM(r.is_correct), 0) AS correct_cnt
FROM interactions i
JOIN lessons l ON l.lesson_id = i.lesson_id
LEFT JOIN interaction_records r ON r.inter_id = i.inter_id
GROUP BY i.inter_id, l.course_id, i.lesson_id, i.type, i.title,
         i.gradeable, i.correct_answer, i.options_json, i.status;

-- 视图 7：AI 批改统计（供作业管理页展示批改覆盖率、一致率与待复核量）
CREATE VIEW v_ai_grading_stat AS
SELECT
    a.assign_id,
    a.course_id,
    a.assign_type,
    (SELECT COUNT(*) FROM submissions s WHERE s.assign_id = a.assign_id)                        AS sub_cnt,
    COUNT(g.result_id)                                                                           AS graded_cnt,
    ROUND(AVG(g.ai_score), 1)                                                                    AS ai_avg_score,
    ROUND(AVG(g.confidence), 3)                                                                  AS avg_confidence,
    ROUND(AVG(g.cost_ms), 0)                                                                     AS avg_cost_ms,
    SUM(CASE WHEN g.review_status = '待复核' THEN 1 ELSE 0 END)                                  AS pending_cnt,
    SUM(CASE WHEN g.review_status = '已调整' THEN 1 ELSE 0 END)                                  AS adjusted_cnt,
    -- 教师复核后 AI 分与最终分的平均绝对偏差（衡量 AI 初评可用性的核心指标）
    ROUND(AVG(CASE WHEN g.final_score IS NOT NULL
                   THEN ABS(g.final_score - g.ai_score) END), 2)                                 AS avg_deviation
FROM assignments a
LEFT JOIN ai_grading_results g ON g.assign_id = a.assign_id
GROUP BY a.assign_id, a.course_id, a.assign_type;

SET FOREIGN_KEY_CHECKS = 1;
