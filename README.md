# 智课堂 · 大学课堂互动与学情分析系统（Spring Boot + MySQL 版）

面向**大学课堂教学**场景的数据库应用系统，以**增强教学互动**、**掌握学员学习情况**为目标，
覆盖"课堂签到 → 课堂互动 → 随堂答题 → 作业测评 → 学情分析 → 学业预警"的完整教学闭环。

> 本目录为**方案 C（Java Spring Boot + MySQL + MyBatis + Thymeleaf）**实现。
> 方案 A（Python Flask + SQLite）版本保留在同级目录 `classroom-insight/` 作为备份。

---

## 一、功能清单

| 模块 | 功能 |
|---|---|
| 统一认证 | 登录 / 登出、角色路由（教师 / 学生 / 管理员）、BCrypt 密码哈希 |
| 课堂签到 | 教师一键开启课堂生成 4 位签到码；学生凭码签到；状态区分 正常 / 迟到 / 请假 / 缺勤；唯一约束防重复签到 |
| 课堂互动 | 抢答、随机点名、投票、提问四类；实时看板 3 秒自动刷新参与率与正确率 |
| 随堂答题 | 按知识点随机抽题，作答后写入答题记录，支撑知识点掌握度分析 |
| 作业测评 | 发布作业（客观题 / 主观题 / 编程题 / 作文四类）、学生提交、教师批改打分 |
| **AI 批改作业** | 四类作业分别调用对应引擎自动批改，输出分数 + 置信度 + 总体评语 + **逐项反馈（得分点 / 错误定位 / 修改建议）**；支持**教师复核与人工调整**、**CSV 导出**与批改统计。详见 **[第七章](#七ai-批改作业功能说明)** |
| 学情分析 | 出勤趋势、签到构成、互动分布、活跃度排行、成绩分布、班级对比、知识点雷达、掌握度热力图、相关性散点、分组对比 |
| 学业预警 | 综合学情分排序，红 / 黄 / 绿三级预警，自动生成归因标签，支持查看个人学情 |
| **系统管理（管理员）** | 系统监控、用户与角色管理、权限与访问控制、系统配置、操作日志与审计、数据备份与恢复（详见 **[第十章](#十系统管理员模块)**） |

> **AI 批改作业**的能力边界（务必先读）：客观题为**规则判分**，与教师成绩完全一致率实测 **100%**；
> 主观题 / 作文 / 编程题在**未接入大模型**时使用本地启发式引擎，只做关键词覆盖、篇幅与结构的可解释评分，
> **不具备语义理解能力**，与教师给分平均偏差 12~14 分，因此**所有结果一律先落库为「待复核」，必须由教师确认或调整后才计入正式成绩**。
> 接入大模型只需在 `application.yml` 填 `endpoint` 与 `api-key`，无需改动业务代码。

---

## 二、技术栈

| 层次 | 选型 |
|---|---|
| 运行环境 | JDK 17（本机另装有 JDK 24，构建统一用 17） |
| 框架 | Spring Boot 3.2.5（内嵌 Tomcat 10） |
| 持久层 | MyBatis 3.0.3（注解方式） |
| 数据库 | **MySQL Server 8.0.46**（InnoDB / utf8mb4） |
| 视图层 | Thymeleaf 3（服务端渲染） |
| 前端 | 自研 `theme.css` + Bootstrap 5 + ECharts 5（全部本地化，离线可用） |
| 构建 | Maven 3.9.6（便携版，位于 `runtime/`） |

第三方依赖仅 4 个：`spring-boot-starter-web`、`spring-boot-starter-thymeleaf`、
`mybatis-spring-boot-starter`、`mysql-connector-j`（另加编译期 Lombok、`spring-security-crypto` 做密码哈希）。

---

## 三、目录结构

```
classroom-insight-java/
├─ pom.xml
├─ run.bat                         一键启动（Windows）
├─ Dockerfile                      ★ 多阶段构建镜像（JDK17 编译 → JRE17 运行，支持 PORT 环境变量）
├─ docker-compose.yml              ★ 应用 + MySQL 8.0 一键编排（自动建库建表 + 演示数据）
├─ render.yaml                     Render Blueprint（连 GitHub 仓库即部署）
├─ railway.toml                    Railway 部署配置（健康检查放宽到 300s）
├─ .dockerignore  .gitignore
├─ README.md
├─ db/
│   ├─ schema.sql                  ★ MySQL 建库建表建视图（20 表 + 7 视图，含系统管理 5 表）
│   └─ er.md                       ★ E-R 图与完整性约束说明
├─ .github/workflows/build.yml     push 后自动编译校验并产出 jar
├─ docs/
│   └─ 系统管理员模块设计.md          ★ 管理员模块完整设计说明
├─ scripts/
│   ├─ smoke_test.ps1              端到端冒烟测试（40 项）
│   └─ admin_smoke.ps1             管理员模块冒烟测试（33 项）
├─ runtime/
│   ├─ apache-maven-3.9.6/         便携版 Maven
│   └─ m2repo/                     本地仓库
└─ src/main/
    ├─ java/com/zhiketang/
    │   ├─ ClassroomApplication.java        启动类
    │   ├─ common/       R(统一响应) SessionUser BizException BaseController GlobalExceptionHandler
    │   ├─ config/       WebConfig（拦截器 + 模板公共变量） SysInitRunner（配置与权限初始化）
    │   ├─ interceptor/  LoginInterceptor（API 401 / 页面重定向）
    │   ├─ mapper/       UserMapper CourseMapper LessonMapper InteractionMapper
    │   │                AssignmentMapper AnalyticsMapper（★ 全部统计 SQL）
    │   │                AiGradingMapper（AI 批改结果） SysMapper（★ 系统管理）
    │   ├─ service/      AuthService LessonService InteractionService
    │   │                AssignmentService AnalyticsService
    │   │                AiGradingService（★ AI 批改）
    │   │                AdminService PermService AuditService BackupService SysConfigService（★ 系统管理）
    │   ├─ service/ai/   AiProvider(接口) LocalHeuristicAiProvider HttpLlmAiProvider AiGradeResult
    │   ├─ controller/   PageController AuthController CourseController
    │   │                LessonController InteractionController
    │   │                AssignmentController AnalyticsController
    │   │                AdminController（★ 管理后台 6 页 + 接口）
    │   └─ seed/         DataSeeder（★ 基础数据 + 测试数据 + 自检）
    └─ resources/
        ├─ application.yml
        ├─ static/  css/theme.css  js/common.js  vendor/{bootstrap,echarts}
        └─ templates/ base.html(统一布局) login.html teacher/* student/* admin/*
```

---

## 四、快速开始

### 方式一：一键启动（推荐）

双击 **`run.bat`**，浏览器访问 <http://127.0.0.1:8080>

脚本会自动：检查 MySQL 服务 → 建库 → 导入表结构 → 启动应用 → 首次启动自动生成数据。

### 方式二：命令行

```powershell
cd classroom-insight-java
$env:JAVA_HOME = "D:\develop\Java\jdk-17"
$MVN = ".\runtime\apache-maven-3.9.6\bin\mvn.cmd"

# 1. 建库建表建视图
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -u root -p123456 --default-character-set=utf8mb4 classroom < db\schema.sql

# 2. 启动（首次会自动生成数据；加 --seed 可强制重建）
& $MVN spring-boot:run
& $MVN spring-boot:run -Dspring-boot.run.arguments=--seed
```

### 数据库连接配置

`src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/classroom?...&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai
    username: root
    password: '123456'
```

> 若本机 MySQL 密码不同，改这里即可。

### 演示账号（密码统一 `123456`）

| 角色 | 账号 |
|---|---|
| 教师 | `t001`（数据库系统原理）、`t002`（数据结构与算法） |
| 学生 | `20230101`~`20230135`、`20230201`~`20230235`、`20230301`~`20230335` |
| 管理员 | `admin`（登录后进入管理后台 `/admin/monitor`） |

---

## 五、推荐演示流程（现场可展示"实时互动"）

1. 浏览器 A 用 `t001` 登录 → **课堂管理** → 对任意课时点「开始课堂」，记录 4 位签到码。
2. 点「实时看板」，页面每 3 秒自动刷新。
3. 浏览器 B（无痕窗口）用 `20230101` 登录 → **我的课程** → 「进入课堂」→ 输入签到码签到。
4. 回到浏览器 A，出勤环形图与名单**实时更新**。
5. 教师切到**课堂互动** → 选该课时发布抢答 → 学生端出现选项按钮 → 作答后教师端实时看板的参与率 / 正确率同步刷新。
6. 打开**学情分析**页，查看 10 张图表与自动生成的分析结论。

---

## 六、数据库设计

- **15 张业务表**：`classes` `users` `courses` `enrollments` `lessons` `attendances` `interactions`
  `interaction_records` `assignments` `submissions` `knowledge_points` `questions` `answer_records`
  `learning_events` **`ai_grading_results`**（★ AI 批改结果）
- **7 个统计视图**：`v_lesson_attendance` `v_student_kpi` `v_student_risk` `v_kp_mastery`
  `v_student_kp_mastery` `v_interaction_stat` **`v_ai_grading_stat`**
- **5 张系统管理表**：`sys_config` `sys_permission` `role_permission`
  **`operation_logs`**（审计） **`backup_records`**（备份），支撑管理员模块。
- **完整性约束**：14 处外键（显式 `ON DELETE`）；`CHECK` 约束限制角色 / 签到状态 / 互动类型 / 分数区间；
  `UNIQUE` 约束防重复选课、重复签到、重复作答、重复提交；12 个索引优化高频查询。
- 全部表使用 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`。
- 详细 E-R 图与范式说明见 **`db/er.md`**。

### 关键指标口径

| 指标 | 计算公式 |
|---|---|
| 出勤率 | (正常 + 迟到) / 总课时数 |
| 互动参与率 | 已响应互动数 / 已发布互动总数 |
| 互动正确率 | 可计分互动答对数 / 可计分互动响应数 |
| 知识点掌握度 | 该知识点答对数 / 该知识点总答题数 |
| 综合学情分 | 0.3×出勤率×100 + 0.3×[100×(0.5×参与率 + 0.5×正确率)] + 0.4×作业均分 |
| 风险分级 | 综合分<60 或 出勤率<70% → 红；综合分<75 或 出勤率<85% → 黄；其余 → 绿 |

> **设计要点**：`interactions.gradeable` 字段区分"可判对错"的抢答/随机点名与"只计参与"的投票/提问。
> 若不区分，投票类互动会稀释互动正确率，该指标将失去意义。

---

## 七、AI 批改作业功能说明

> 对应交付物：
> `db/schema.sql`（`assignments.assign_type` / `ai_rubric`、新增 `ai_grading_results` 表、视图 `v_ai_grading_stat`）、
> `service/ai/`（引擎与适配层）、`service/AiGradingService`、`controller/AiGradingController`、
> `templates/teacher/assignments.html`、`scripts/ai_grading_test.ps1`。

### 7.1 支持的作业类型、批改标准与评分规则

| 作业类型 | 批改引擎 | 批改标准与评分规则 | 置信度 | 局限 |
|---|---|---|---|---|
| **客观题**<br>（单选/多选/判断/填空） | `RULE_OBJECTIVE` | 按 `q_id` 取 `questions.answer` **逐题精确比对**；每题等权（满分 ÷ 题数），答对即得分，答错记 0 | **1.000** | 需学生按结构化模板提交（JSON 答案列表）；非结构化作答**不做猜测评分**，返回置信度 0 并提示重新提交 |
| **主观题**<br>（简答/论述/计算） | `RULE_SUBJECTIVE`（本地）<br>`LLM_SUBJECTIVE`（大模型） | 得分点关键词覆盖 **50%** + 篇幅达标度 **30%** + 结构完整性 **20%** | 0.55 + 0.30×覆盖率<br>（上限 0.85） | 本地引擎**无语义理解**，仅匹配评分标准中的关键词 |
| **编程题** | `PROGRAM_JUDGE` | 必需语法结构命中 **50%** + 代码结构完整性 **30%** + 注释规范性 **20%** | 固定 **0.65** | **静态检查不能替代运行**，无法验证测试用例与边界；需接入判题沙箱（Judge0 / HUSTOJ）后方可实跑 |
| **作文** | `RULE_ESSAY`（本地）<br>`LLM_ESSAY`（大模型） | 立意要点覆盖 **40%** + 篇幅达标度 **30%** + 篇章连贯性 **30%** | 0.50 + 0.30×覆盖率<br>（上限 0.82） | 同上，本地引擎无法评判文采与立意深度 |

- 评分标准存放在 `assignments.ai_rubric`（JSON），可按需配置 `keywords`（得分点）、`min_words`（最低字数）、`required`（必需结构）、`cases`（测试用例数）。
- 客观题提交内容格式：`{"answers":[{"q_id":12,"answer":"A"}, ...]}`。

### 7.2 批改结果的呈现形式

一次批改同时输出四类信息，存于 `ai_grading_results`：

| 呈现项 | 字段 | 说明 |
|---|---|---|
| **分数** | `ai_score` / `max_score` | AI 初评分与本次满分 |
| **置信度** | `confidence` | 0~1；低于阈值 0.75 在页面标红为「需复核」 |
| **总体评语** | `comment` | 如"得分点覆盖 60%，篇幅偏少（102/150 字）" |
| **逐项反馈** | `detail_json` → `items[]` | 每项含：得分点名称、本项得分/满分、是否命中、**错误定位**、**修改建议** |
| **耗时** | `cost_ms` | 单份批改耗时，用于性能观测 |

逐项反馈示例（`detail_json`）：

```json
{
  "score": 62.0, "confidence": 0.757,
  "items": [
    { "name": "得分点：索引",         "got": 10,  "max": 10, "hit": true,  "error": "",                    "tip": "" },
    { "name": "得分点：B+树",         "got": 0,   "max": 10, "hit": false, "error": "未覆盖得分点「B+树」", "tip": "建议围绕「B+树」补充论述" },
    { "name": "篇幅要求（≥150字）",   "got": 9,   "max": 30, "hit": true,  "error": "当前 102 字，未达 150 字要求", "tip": "建议展开分析过程，补充具体步骤与示例" },
    { "name": "结构完整性",           "got": 20,  "max": 20, "hit": true,  "error": "",                    "tip": "" }
  ]
}
```

### 7.3 教师复核与人工调整

**支持，且是强制环节。** 设计原则：AI 只出初稿，教师拥有最终裁定权。

1. 批改结果落库时状态一律为「**待复核**」；
2. 教师在结果弹窗中填写最终分与复核评语，可逐项查看反馈后再定分；
3. 复核时自动判定状态：与 AI 初评分一致 →「**已确认**」，不一致 →「**已调整**」；
4. 复核后同步写入 `submissions.score`（**教师裁定优先于 AI**），并记录 `reviewed_by` / `reviewed_at` 形成审计轨迹；
5. `ai_score` 与 `final_score` **并存保存**——既保证成绩有效，又保留 AI 原始输出用于评测其准确率。

### 7.4 批改结果的导出与统计

- **导出**：作业管理页「导出 CSV」，字段含 学号、姓名、作业类型、批改引擎、AI 初评分、置信度、复核状态、最终分、AI 评语、**主要问题（错误定位）**、**修改建议**、批改耗时、复核评语。文件带 UTF-8 BOM，Excel 打开中文不乱码。
- **统计**：视图 `v_ai_grading_stat` 提供批改覆盖率、AI 平均分、平均置信度、平均耗时、待复核数、已调整数，以及 **AI 初评分与最终分的平均绝对偏差**（衡量 AI 初评可用性的核心指标）。

### 7.5 性能指标（本机实测，非估算）

测试条件：Windows 11 / JDK 17 / MySQL 8.0.46（本地）/ Spring Boot 内嵌 Tomcat，
样本为 2 门课 8 次作业共 **756 份**真实提交，脚本 `scripts/ai_grading_test.ps1`。

| 作业类型 | 引擎 | 单份批改耗时（服务端） | 平均置信度 | 与教师成绩平均偏差 | 完全一致率 |
|---|---|---|---|---|---|
| 客观题 | RULE_OBJECTIVE | **6.0 ~ 8.0 ms** | 1.000 | **0 分** | **100%**（185/185） |
| 主观题 | RULE_SUBJECTIVE | **约 3.2 ms** | 0.757 | 13.60 分 | 3.6% |
| 编程题 | PROGRAM_JUDGE | **约 3.3 ms** | 0.650 | 13.38 分 | 1.1% |
| 作文 | RULE_ESSAY | **约 3.0 ms** | 0.717 | 12.09 分 | 1.6% |

- **单次批量批改**（一个作业 93 份提交）：服务端耗时 **450 ~ 900 ms**；单请求端到端基线 **500 ~ 700 ms**。
- **并发量**：8 路并发压测，**0.8 ~ 0.95 s** 内完成 **747 份**批改，约 **790 ~ 940 份/秒**（多次实测量值在此区间波动）；
  服务端累计耗时约 4.4 s，并行效率约 **5.6×**。
  并发上限受 HikariCP 默认连接池（10）约束，更高并发需调 `spring.datasource.hikari.maximum-pool-size`。

> ⚠️ **关于准确率的必要说明**：
> ① 客观题为标准答案精确比对，一致率 **100% 可复现验证**；
> ② 主观题 / 作文 / 编程题的"偏差"是**本地启发式引擎**与教师给分的差距——
> 该引擎无语义理解能力，实测偏差 12~14 分，这正是本功能**强制要求教师复核**的原因；
> ③ 演示库中教师成绩由种子脚本按能力潜变量生成（非真人阅卷），
> 因此该偏差衡量的是"引擎复现教师给分尺度的能力"，**不等同于真实阅卷准确率**；
> 真实准确率需在接入大模型后用真人复核数据另行评测。

### 7.6 使用权限范围

| 操作 | 教师 | 管理员 | 学生 | 未登录 |
|---|---|---|---|---|
| 触发 AI 批改 | ✅ **仅限本人所授课程** | ✅ | ❌ 403 | ❌ 401 |
| 查看本作业全部批改结果 | ✅ 限本人课程 | ✅ | ❌ | ❌ |
| 教师复核（调整分数/评语） | ✅ 限本人课程 | ✅ | ❌ | ❌ |
| 导出 CSV / 查看批改统计 | ✅ 限本人课程 | ✅ | ❌ | ❌ |
| 查看批改反馈 | ✅ | ✅ | ✅ **仅本人提交的** | ❌ |

归属校验在服务端完成（`courseMapper.countOwned`），学生越权访问返回 403。

### 7.7 接入真实大模型（可选）

批改能力做成**可插拔适配层**（`service/ai/AiProvider`），默认使用本地启发式引擎，
配置后自动切换为大模型，失败自动降级，业务代码无需改动：

```yaml
ai:
  grading:
    provider: http              # local（默认）| http
    endpoint: https://xxx/v1/chat/completions
    api-key: sk-xxx
    model: qwen-plus
    confidence-threshold: 0.75   # 低于该值强制人工复核
```

---

## 八、测试数据设计（决定分析结果是否有解释力）

`src/main/java/com/zhiketang/seed/DataSeeder.java` 没有使用纯随机数据，而是引入**隐藏的能力潜变量**：

- 每名学生被赋予 high / mid / low 三档隐藏能力（占比 30% / 50% / 20%）；
- 其**出勤率、互动参与概率、答题正确率、作业分数**均与该潜变量正相关，并叠加高斯噪声；
- 人为将 `kp_order = 3` 与 `5` 的知识点的正确率乘以 0.75，模拟"教学薄弱点"。

生成规模：3 个班 105 名学生、2 门课程、32 个课时，过程数据合计 **38,085 行**，耗时约 12 秒。

启动时会在控制台打印数据规律自检：

```
【数据库系统原理】互动次数 vs 作业均分 皮尔逊 r = 0.5841
   出勤率≥90% 均分 79.5  vs  出勤率<70% 均分 68.6
   知识点掌握度：…SQL 语言基础 46.4%…事务与并发控制 47.8%…
   风险分布：红22人  黄35人  绿48人
```

---

## 九、分析结论示例（系统根据真实数据自动生成）

以「数据库系统原理」为例：

1. 全班 105 人选修，32 课时，平均出勤率 **82.9%**，作业平均成绩 **74.3** 分。
2. 出勤率 ≥ 90% 的学生平均成绩约 **79.5** 分，出勤率 < 70% 的学生约 **68.6** 分，
   出勤与学业表现显著正相关（r ≈ **0.46**）。
3. 课堂互动参与次数与作业平均成绩相关系数 r = **0.584**，呈中等正相关。
4. 知识点「SQL 语言基础」(46%)、「事务与并发控制」(48%) 掌握度低于 60%，为教学薄弱点。
5. 学业预警：红色 22 人、黄色 35 人、绿色 48 人，建议优先帮扶红色预警学生。

---

## 十、系统管理员模块

管理员（`admin`）登录后进入后台 `/admin/monitor`，侧边栏六个入口：

| 页面 | 作用 | 关键约束 |
|---|---|---|
| `/admin/monitor` 系统监控 | 业务规模、数据库与 JVM 指标、运行状态自检、活跃趋势、表数据量 | 指标单项失败自动降级，不拖垮整页 |
| `/admin/users` 用户与角色 | 账号增删改查、角色分配、启用/停用、解锁、重置密码、导出 CSV | 不能停用/删除/降级自己；必须保留 ≥1 个启用管理员；有业务数据的账号只能停用 |
| `/admin/perms` 权限配置 | 24 个权限点 × 3 个角色的开关矩阵，勾选即生效 | admin 列锁定；学生不可获得管理类权限；教师端写操作真实校验权限点 |
| `/admin/config` 系统配置 | 17 项参数按分组维护，支持恢复默认 | 类型/范围/业务规则校验；只读项标注需重启；修改留审计 |
| `/admin/logs` 操作日志 | 多条件检索、详情（改前→改后、IP、耗时）、导出、按天清理 | 成功与失败同记；写日志失败不影响主业务 |
| `/admin/backup` 数据备份 | 创建/下载/删除备份，输入文件名确认后恢复 | 恢复前强制自动备份当前库；mysqldump 不可用时降级为内置逻辑转储 |

配置项**真正参与业务计算**：预警阈值影响风险分级、密码策略校验新建/重置密码、登录失败锁定策略由配置驱动、系统名称实时改顶栏。

完整设计（操作流程、输入输出、权限边界、异常处理、功能关联、响应式布局）见 **`docs/系统管理员模块设计.md`**。

---

## 十一、验证

```powershell
# 应用已启动的前提下
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke_test.ps1

# 管理员模块专项（默认 8080，其他端口加 -Base http://127.0.0.1:8081）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\admin_smoke.ps1
```

覆盖未登录保护、角色鉴权、6 个教师页面、8 个分析接口、课堂签到（错误码 / 重复签到）、
互动作答、作业提交与批改、非法参数拦截等，共 **40 项检查**。

AI 批改功能另有专项实测脚本（准确率 / 响应时间 / 并发量 / 复核 / 导出，共 11 项）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ai_grading_test.ps1
```

---

## 十二、部署与 GitHub 发布

### 12.1 先回答一个常见问题：传到 GitHub 就能打开吗？

**不能。** GitHub 只负责存代码，本身不运行你的程序：

| 方式 | 能否运行本项目 | 说明 |
|---|---|---|
| GitHub 仓库 | 否 | 只能看源码 |
| GitHub Pages | 否 | 只托管静态 HTML/JS/CSS，无法运行 Java 后端，也无法连 MySQL |
| 服务器 / 容器上运行打包产物 | **是** | 本项目是 Spring Boot + MySQL，必须在**会跑代码的机器**上运行 |
| GitHub Actions | 半 | 可自动编译校验（当前仓库已配置 `.github/workflows/build.yml`），但不是常驻服务 |

所以"随时能打开"= 需要一个常驻运行环境。下面三条路径，按需选择。

### 12.2 最短路径：本机 + 内网穿透（10 分钟，免费）

适合**演示 / 手机随时看**，电脑需保持开机。

1. 本机启动应用（`run.bat` 或 `mvn spring-boot:run`），确认 `http://127.0.0.1:8080` 可访问；
2. 装 Cloudflare Tunnel（`cloudflared`）或 cpolar / 花生壳，把本机 8080 映射成一个公网 HTTPS 地址：
   ```bash
   cloudflared tunnel --url http://127.0.0.1:8080
   ```
3. 得到的 `https://xxx.trycloudflare.com`（或固定域名）发给别人或手机打开即可。
4. Windows 若被防火墙拦住，放行 TCP 8080 入站：
   ```powershell
   netsh advfirewall firewall add rule name="classroom-insight-8080" dir=in action=allow protocol=TCP localport=8080
   ```

### 12.3 最省事：Docker 一把起（推荐，服务器 / 另一台电脑都行）

仓库已提供 `Dockerfile` 与 `docker-compose.yml`（内含 MySQL 8.0，自动执行 `db/schema.sql` 建库建表，
应用首次启动发现 `users` 表为空会自动跑 `DataSeeder` 生成演示数据）：

```bash
docker compose up -d --build     # 首次约 3~6 分钟（含依赖下载）
docker compose ps                # 两个容器 healthy/running
docker compose logs -f app       # 看到 "Started ClassroomApplication" 即就绪
# 浏览器访问 http://服务器IP:8080   admin / 123456
docker compose down              # 停止（数据保留在 mysql-data 卷）
```

> 部署到公网时建议：`docker-compose.yml` 里删掉 MySQL 的 `3306:3306` 端口映射，并把密码改成强密码。

### 12.4 免费云托管（无需买服务器）

代码已做了三项云适配，两个平台都能"连上仓库就跑"：

| 适配项 | 说明 |
|---|---|
| `PORT` | `application.yml` 里 `port: ${PORT:8080}`，Render / Railway / Fly.io 注入的端口自动生效 |
| `DB_URL` `DB_USER` `DB_PASSWORD` | 三件套环境变量覆盖数据库地址，本地不设置时仍走 `127.0.0.1` 的 `root/123456` |
| 演示数据 | 空库首次启动会**自动生成**演示数据（`DataSeeder` 检测到 `users` 为空即执行），不用手动导 SQL |

#### 方案 A：Railway（推荐，MySQL 原生，兼容性最好）

1. 先按 **12.5** 把代码推到 GitHub；
2. 打开 [railway.app](https://railway.app) → 用 GitHub 登录 → **New Project → Deploy from GitHub repo** 选你的仓库（根目录有 `Dockerfile`，自动识别，首次构建约 3~6 分钟）；
3. 同一项目里 **New → Database → Add MySQL**，等 MySQL 变绿；
4. 选中 Web 服务 → **Variables → New Variable**（支持引用变量 `${{服务名.变量名}}`）：

   | Key | Value |
   |---|---|
   | `DB_URL` | `jdbc:mysql://${{MySQL.MYSQL_HOST}}:${{MySQL.MYSQL_PORT}}/${{MySQL.MYSQL_DATABASE}}?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true` |
   | `DB_USER` | `${{MySQL.MYSQL_USER}}` |
   | `DB_PASSWORD` | `${{MySQL.MYSQL_PASSWORD}}` |

5. 保存后服务自动重建；**首次启动会自动生成演示数据，耐心等 1~2 分钟**（`railway.toml` 已把健康检查超时放宽到 300 秒）；
6. Settings → Networking → **Generate Domain**，得到 `https://xxx.up.railway.app`，手机/别人随时打开：`admin / 123456`。

#### 方案 B：Render + TiDB Cloud（零费用、不用绑卡）

1. TiDB Cloud 注册（GitHub/Google 登录即可）→ 建 **Serverless Free** 集群 → 记下主机、端口 `4000`、用户名、密码 → 在集群的 IP 白名单里加 `0.0.0.0/0`；
2. Render → **New → Blueprint** → 连接仓库（仓库根目录 `render.yaml` 会被自动识别）→ 触发部署；
3. 在 Web 服务的 **Environment** 面板填：

   | Key | Value |
   |---|---|
   | `DB_URL` | `jdbc:mysql://<TiDB主机>:4000/test?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=true&enabledTLSProtocols=TLSv1.2&allowPublicKeyRetrieval=true` |
   | `DB_USER` | `<TiDB 用户名>` |
   | `DB_PASSWORD` | `<TiDB 密码>` |

4. 部署完成后访问 `https://classroom-insight-xxxx.onrender.com`，同样自动生成演示数据。

> **两者的取舍**：Railway 用原生 MySQL 8，本项目所有 SQL（CHECK 约束、视图、窗口函数）100% 兼容，但免费额度可能需绑卡验证；Render + TiDB 完全免费且不用绑卡，代价是免费档 15 分钟无访问休眠（冷启动约 50 秒），且 TiDB 与 MySQL 8 存在少量语法差异风险。顺带一说，若演示时偶发建表异常，换方案 A 即可。

#### 云上的已知限制

- **数据只有一份**：所有人访问看到同一套数据，适合演示，不适合当生产用；
- **备份文件不持久**：Render / Railway 容器重启后 `/app/backups` 会清空（`docker-compose` 方式则挂在持久卷里）。管理后台的备份/恢复功能仍可演示，但重启后文件会消失；
- **免费实例资源小**：首次访问 + 数据初始化较慢，看日志出现 `Started ClassroomApplication` 才算就绪。

#### 方案 C：低价云服务器（最稳定）

阿里云 / 腾讯云轻量应用服务器（学生机约 ¥24/月）或 Oracle Cloud 免费 ARM：装 Docker Compose 后跑 **12.3** 的方案，数据库用容器内置 MySQL，数据持久保留，最省心。

### 12.5 发布到 GitHub

```bash
cd classroom-insight-java
git init
git add .
git commit -m "feat: 大学课堂互动与学情分析系统（含系统管理员模块）"
git branch -M main
git remote add origin https://github.com/<你的账号>/<仓库名>.git
git push -u origin main
```

仓库已配 `.gitignore`：`target/`、`runtime/`（70MB 便携 Maven）、`backups/`、`*.log` 与临时 SQL 均不会入库。
推送后 GitHub Actions 会自动 `mvn clean package`，在仓库页面看到绿色 ✓ 即说明代码可构建。

---

## 十三、实施过程与提示词记录

见上级目录 **`classroom-insight/实施过程与提示词记录.md`**，
其中包含各阶段提示词模板、关键环节决策与踩坑记录。

---

## 十四、常见问题

**Q：为什么自带 `runtime/apache-maven-3.9.6`？**
A：本机未安装 Maven。便携版 + 项目内本地仓库 `runtime/m2repo` 可保证离线构建，
且已配置阿里云镜像加速依赖下载。

**Q：数据能否重新生成？**
A：可以。`run.bat --seed` 或 `mvn spring-boot:run -Dspring-boot.run.arguments=--seed`
会清空全部业务表并重新生成（随机种子固定为 `20261029`，结果可复现）。

**Q：启动时报 `Unknown initial character set index '255'`。**
A：JDBC URL 里 `characterEncoding` 必须写 `UTF-8`，不能写 `utf8mb4`（驱动不识别后者）。

**Q：Windows PowerShell 5.1 执行中文脚本乱码？**
A：PS 5.1 默认按 ANSI 读取 `.ps1`。本项目 `smoke_test.ps1` 已改为纯 ASCII，
中文常量用 `[char]0xXXXX` 码点构造，规避编码问题。
