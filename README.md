# 职航 CareerNav

> 单智能体协同的计算机学生职业发展智能导航平台

本项目以 **“简历诊断”为核心**，完整打通：

```text
编写简历 -> 推荐并选择岗位 -> 完成岗位面试 -> 面试证据回流 -> 生成简历诊断与学习路线
```

前端使用原生 HTML/CSS/JavaScript，后端使用 Spring Boot，智能体采用 LangChain4j 单智能体编排，并通过 MCP JSON-RPC 工具协议接入招聘岗位数据源。未配置大模型密钥或远程 MCP 服务时，系统自动使用本地推理与内置岗位源，所有主流程仍可完整演示。

## 已实现能力

| 模块 | 能力 |
| --- | --- |
| 简历工坊 | 结构化编辑基础信息、技能、项目、自我评价；支持 PDF / 扫描件 / 图片导入、OCR 识别和实时预览 |
| 岗位雷达 | 岗位检索、城市筛选、技能与目标方向匹配、薪资展示、MCP 岗位同步 |
| 岗位推荐 | 技能覆盖率、岗位方向、城市、学历等综合评分，输出匹配技能和待补技能 |
| 模拟面试 | 独立页面，先选择简历和目标岗位，填写脱敏项目证据卡后完成八轮项目面试；面试完成后在本页面内解锁简历诊断 |
| 项目面试助手 | 读取公开 Java 仓库或上传 ZIP，用 JavaParser 定位真实源码片段，生成五维分层追问 |
| 简历诊断 | 联合简历质量、岗位要求、项目证据和真实面试证据计算可解释评分，并给出失分原因 |
| 学习路线 | 根据目标岗位技能图谱定位差距，输出阶段目标、主题和交付物 |
| 技能图谱 | Java 后端、前端、AI/大模型方向节点、依赖关系和岗位需求热度 |
| 单智能体 | 自主编排 `resume.profile -> job.match -> interview.start -> resume.diagnose -> learning.generate` 工具链 |
| MCP | 内置 `/mcp` JSON-RPC 服务端，同时提供可配置的远程 MCP 招聘工具客户端 |
| 面试题库 | 从 4 个公开 GitHub 仓库抽取 1,508 道面试题，写入独立 Qdrant 集合，面试 Agent 按会话上下文实时检索真实题目与参考要点 |
| 岗位推荐 Agent | 读简历后自主规划检索、必要时换说法重试，再用加权算法精排岗位，输出推荐清单与技能缺口；分数全部可追溯 |

## 技术架构

```text
Browser (HTML / CSS / JavaScript)
              |
              v
Spring Boot REST API
  |-- ResumeService
  |-- JobService / MCP Job Gateway
  |-- InterviewService
  |-- DiagnosisService  <-- 核心
  |-- LearningPathService / SkillGraphService
  |-- AgentOrchestrator
              |
       LangChain4j ChatLanguageModel
              |
  OpenAI-compatible LLM (可选，OPENAI_API_KEY)
              |
        MCP JSON-RPC source (可选)

业务数据：PostgreSQL
岗位向量：Qdrant -> careernav_jobs（4,110 条）
面试题向量：Qdrant -> careernav_interview_questions（1,508 条）
本地向量生成：TF-IDF + TruncatedSVD 256 维（岗位 5001 / 面试题 5002）
```

详细设计见 [docs/architecture.md](docs/architecture.md)。

## 快速启动

环境要求：

- JDK 21+
- 无需预装 Maven，项目已包含 Maven Wrapper

运行前必须启动 PostgreSQL、Qdrant 和本地 Embedding 服务。PostgreSQL 只保存业务数据，Qdrant 专门保存岗位向量。具体命令见下方运行说明。

浏览器地址：<http://localhost:8080>

## PostgreSQL + Qdrant 运行方式

项目当前将业务数据保存到 PostgreSQL 18.6，将岗位向量保存到 Qdrant 1.19.2，不再使用 pgvector。

```powershell
.\ops\postgres\start.ps1
```

另开终端启动本地 Embedding API：

```powershell
python .\ops\postgres\embedding_server.py
```

再启动 Qdrant：

```powershell
.\ops\qdrant\start.ps1
```

Qdrant 默认 collection：

```text
careernav_jobs
```

启动 Spring Boot：

```powershell
$env:DB_URL="jdbc:postgresql://127.0.0.1:5432/careernav"
$env:DB_USERNAME="careernav"
$env:QDRANT_URL="http://127.0.0.1:6333"
$env:QDRANT_COLLECTION="careernav_jobs"
$env:EMBEDDING_API_KEY="local"
$env:EMBEDDING_BASE_URL="http://127.0.0.1:5001/v1"
$env:EMBEDDING_MODEL="tfidf-svd-256"
$env:EMBEDDING_DIMENSIONS="256"
.\mvnw.cmd spring-boot:run
```

当前数据库包含 4,110 条岗位：

- 3,588 条 GitHub 统一岗位数据
- 522 条 `boss_python_jobs.csv` 原始岗位数据

岗位向量已全部写入 Qdrant：

```text
http://127.0.0.1:6333/collections/careernav_jobs
```

向量索引重建接口：

```text
POST /api/jobs/vector/reindex?limit=5000
```
## LangGraph 面试 Agent

项目同时提供 Java LangChain4j 和 Python LangGraph 两层能力：

- Spring Boot 负责简历、岗位、面试会话和数据库事务
- LangGraph 负责自主规划、工具选择、回答评估、追问和会话记忆
- 没有在线模型密钥时，LangGraph 使用本地规划器回退
- Java 侧 `LANGGRAPH_ENABLED=false` 时继续使用原有 LangChain4j 面试逻辑

启动 LangGraph 服务：

```powershell
cd interview-agent
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
powershell -ExecutionPolicy Bypass -File .\start.ps1
```

然后以启用 LangGraph 的方式启动 Spring Boot：

```powershell
.\mvnw.cmd -DskipTests package
java -jar target\career-navigation-platform-1.0.0.jar `
  --app.langgraph.enabled=true `
  --app.langgraph.base-url=http://127.0.0.1:8001
```

工具链：

```text
read_session_context
get_job_requirements
search_question_bank          # 内置小型模板，兜底
search_interview_questions_qdrant   # 面试题向量库，优先
evaluate_candidate_answer
save_interview_memory
get_interview_progress
```
## 岗位推荐 Agent（简历诊断）

填完简历后，Agent 会自己在岗位库里找匹配岗位。设计原则是**大模型负责理解与调权，规则引擎负责打分**：
六维基准权重由 AHP 层次分析法生成，全局基准为技能 34、方向 22、经验 16、学历 10、城市 4、证据 14，
并通过 `CR<0.1` 的一致性校验。选择具体岗位后，系统会根据岗位的技能密度、经验级别、学历要求和工程深度
实时生成岗位条件化 AHP 权重；六项归一化权重之和严格为 1。Agent 在此基础上继续生成受限权重，但不直接
输出匹配分；最终分由 Java 规则引擎根据可复现的维度因子和硬性条件计算。

```text
GET /api/jobs/weights/ahp       全局 AHP 基准
GET /api/jobs/{id}/weights/ahp 所选岗位的实时调整权重
```

```text
extract_profile -> recall -> rank -> reflect --(重试最多 2 次)--> gap_analysis -> compose
   LLM 出画像       语义召回    算法精排     LLM 决定是否重搜         技能缺口        LLM 写结论 + 防幻觉校验
```

调用方式：

```text
POST /api/diagnoses/agent?resumeId=1&city=长沙&limit=6
```

返回 `recommendations`（岗位 + 分数 + 维度明细）、`skillGaps`（技能缺口）、`trace`（决策轨迹）。
实现位于 `interview-agent/app/job_graph.py` 与 `job_tools.py`，详见
[interview-agent/README.md](interview-agent/README.md)。

## 简历 PDF / 图片导入

简历工坊支持从已有 PDF、扫描件、图片或 DOCX 生成结构化简历草稿：

```text
数字版 PDF -> 提取文本层
扫描 PDF  -> PDF.js 渲染页面 -> 浏览器 OCR
PNG/JPG/WebP -> 浏览器 OCR
DOCX/TXT -> 后端文本提取
-> Agent 结构化姓名、教育、技能、项目、经历
-> 用户预览并核对
-> 确认后填入编辑器
-> 用户点击保存后才写入数据库
```

导入增强：

- PDF.js 根据文字坐标重建行和段落，避免把整页压缩成一段；
- 规则解析按项目标题、日期、公司、岗位拆分多条项目与实习/工作经历；
- Agent 结果与规则结果合并，模型只返回部分条目时不会覆盖其他已解析经历；
- 兼容 PDF 常见兼容汉字、全角半角符号和“技术栈：”“成果：”等字段；
- 预览页分别展示项目经历和实习/工作经历数量、角色、技术栈、岗位及时间。

接口：

```text
POST /api/resumes/import
multipart:
  file
  extractedText（浏览器 OCR / PDF 文本，可选）
  extractionMethod（PDF_TEXT / PDF_OCR / OCR_IMAGE / SERVER_TEXT）
```

安全边界：

- OCR 在浏览器本地执行，后端接收用户选择的文件和识别文本；配置在线模型时只发送已隐藏手机号和邮箱的文本用于结构化；
- 导入不会自动保存，也不会直接覆盖数据库中的已有简历；
- OCR 可能误识别姓名、电话、日期、学校和数字，保存前必须人工核对；
- 最多处理前 8 页，超过部分需要手动补充。

## 脱敏项目证据卡与八轮项目面试

企业项目通常不能上传源码或 ZIP。因此面试主流程不依赖代码，而是要求用户填写脱敏项目证据卡：

- 项目名称、担任角色、团队规模
- 核心业务链路
- 技术栈与关键实现
- 故障、难点和处理过程
- 成果与验证方式
- 信息范围（企业保密 / 个人公开 / 课程项目）

面试 Agent 会固定围绕所选项目推进八轮追问：

```text
职责边界 -> 业务链路 -> 技术实现 -> 故障与边界
-> 方案取舍 -> 结果验证 -> 证据补强 -> 迁移验证
```

简历里即使有多个项目，本场面试也只展示证据卡中选中的项目。面试后页面会生成项目追问复盘，技能证据评估也会使用项目证据卡校验回答，不再要求查看源码。 面试技能证据只覆盖本场岗位要求或面试问答中实际触及的技能；简历里存在但本场没有考察的技能，不会显示为未通过，也不参与本场技能扣分。

### JD 面试蓝图

选择目标岗位后，系统会使用岗位库中的技能、职责、`job_description`、`job_requirements`、经验、学历和行业数据生成 JD 面试蓝图，包括必备技能、核心职责、面试场景、硬性条件和八轮覆盖计划。蓝图会保存到面试会话，并同时用于问题规划、追问和技能证据评估。

```text
GET /api/interviews/blueprint?jobId=147953
```

预览接口只生成蓝图，不创建面试。真正开始面试时会把蓝图快照写入会话，后续即使岗位数据变化，本场面试仍可追溯。

## 学历真实性核验

用户上传学信网报告后，系统会识别二维码，并辅助比对姓名、学校、学历、专业和毕业年份。只要检测到学信网官方二维码，本次材料就按合格学历处理；字段差异只作为提示，不再阻断合格结论。未核验或没有学信网官方二维码时，学历维度仍按未通过处理，岗位适配度最高限制为 55 分。简历学历相关字段修改后，旧核验记录自动失效。用户也可以在核验结果中主动取消验证；取消后只删除核验记录并刷新岗位匹配缓存，不会清空已经填写的教育背景。

系统还会从 PDF/图片中自动识别二维码：

- 支持普通官方链接二维码和“学信网报告在线验证”微信小程序码；
- 自动提取在线验证码并重新生成可扫描二维码；
- 只要识别到学信网官方二维码，就通过材料校验并计为合格学历；字段差异仅作提示；
- 将报告中的姓名、学校、学历、专业、毕业年份、学历类别、学制、学习形式、分院、系所、入学日期、学籍状态和预计毕业日期回填到简历编辑器；
- 二维码识别用于确认材料来源和合格状态，不等同于学信网实时在线状态查询。

```text
POST /api/resumes/{id}/education-verification  上传并核验
GET  /api/resumes/{id}/education-verification  查询最近核验结果
DELETE /api/resumes/{id}/education-verification 取消验证并删除核验记录
```

## 独立模拟面试与受控简历诊断

“模拟面试”和“简历诊断”都有独立页面，但诊断页默认锁定：

```text
选择简历和目标岗位
-> 完成八轮项目面试
-> 形成技能证据
-> 在面试页或简历诊断页解锁最终诊断
```

- 未选择岗位时不能开始模拟面试；
- 每次重新开始或切换简历/岗位都会清空上一轮诊断；
- 诊断入口始终可见，但面试完成前只显示锁定说明，不显示分数和最终内容；
- 只有对应简历和岗位存在已完成面试时才允许生成诊断；
- 后端 `/api/diagnoses` 同样检查面试证据，没有证据时直接拒绝出分；
- 独立面试页面便于后续接入数字人。

## 真实面试模式库

面试 Agent 除题库检索外，还会学习真实面试的提问结构。资料被整理为 `interview-agent/data/interview-experiences/patterns.jsonl`，目前包含 220 条面试模式，来源包括：

- 24 届双非自学 Java 项目面试对话整理稿；
- 南京小厂 Java 后端面试实录；
- AI Agent / Java 后端公开面经合集；
- 17 份 Java 实习、校招和社招真实面试对话（快手、深圳小厂、启明信息、MES、智慧农业等）。

每条记录只保留主问题、追问链、面试阶段、考察标签和提问方式，不把候选人的回答当作标准答案。Agent 通过 `search_real_interview_patterns` 工具检索相似场景，再结合当前简历、岗位和上一轮回答改写下一问。

```text
用户回答
-> 检索相似真实面试片段
-> 学习追问顺序和措辞
-> 结合当前岗位生成下一问
-> 检查是否重复或脱离简历事实
```

这不是模型微调，而是可更新、可追溯的检索增强。新增资料后重新运行：

```powershell
python interview-agent/scripts/build_experience_patterns.py
```

## 面试驱动的技能验证

系统不再要求用户做 Java 选择题。岗位匹配阶段先看简历技能清单是否覆盖岗位要求：命中即计为满足，全部命中时技能因子为 1.0；面试评分用于后续诊断中的能力深度、证据质量和补强建议，不反向否定已填写的技能。用户仍需选择岗位、填写脱敏项目证据卡并完成八轮项目面试，才能生成最终诊断。

```text
职责边界 -> 业务链路 -> 技术实现 -> 故障与边界 -> 方案取舍 -> 结果验证 -> 证据补强 -> 迁移验证
```

证据规则：

- 只有回答能说明原理、实现、边界或个人验证过程，才可能形成“已验证”技能；
- 只有定义、关键词、简历复述或团队成果，不视为个人掌握证据；
- 简历已声明的技能命中岗位要求时直接计为满足，全部命中时技能因子为 1.0；
- 面试评分不反向压低岗位技能覆盖，只用于最终诊断、能力深度解释和补强建议；
- 面试中已验证且达到 60 分的额外技能，可以补充简历未声明的技能覆盖；
- 面试评分是可解释的求职准备证据，不是官方技能认证。

```text
POST /api/interviews                         创建八轮项目面试
POST /api/interviews/{id}/answers            提交回答并获取下一问
GET  /api/interviews/evidence?resumeId=&jobId=  读取最近面试技能证据
```

面试完成后，诊断页会展示每项技能的面试评分、等级、置信度和回答证据，并允许“重新验证技能”或进入“项目源码深挖”。

## 面试题向量库（GitHub 公开题库）

岗位向量库只覆盖“招什么”，面试 Agent 还需要“问什么”。因此项目额外构建了独立的面试题向量库，
数据来自 4 个许可证明确的公开 GitHub 仓库，只用纯文本（Markdown / JSON），不镜像图片。

| 来源仓库 | 许可证 | 入库题目 |
| --- | --- | --- |
| `Snailclimb/JavaGuide` | Apache-2.0 | 1,041 |
| `yangshun/tech-interview-handbook` | MIT | 278 |
| `codeprepdev/codeprep-questions` | CC-BY-4.0 | 108 |
| `doocs/advanced-java` | CC-BY-SA-4.0 | 81 |

合计 1,508 条，覆盖 Java 后端、计算机基础、算法、系统设计、行为面试等阶段；每条记录都保留
`sourceUrl` 和 `license`，可随时回溯原作者。

```text
Qdrant collection : careernav_interview_questions
向量维度          : 256（Cosine）
Embedding         : 本地 TF-IDF + TruncatedSVD，服务端口 5002
数据文件          : D:\何处是家乡\data\github-interview-datasets\interview_questions.jsonl
```

重建流程（三步）：

```powershell
# 1. 镜像 4 个仓库的纯文本文件（走 jsDelivr，跳过图片）
python .\ops\interview\fetch_github_interview_sources.py

# 2. 解析成统一 JSONL
python .\ops\interview\build_interview_questions.py

# 3. 训练/复用嵌入模型并写入 Qdrant（--refit 重新训练，--recreate 重建集合）
python .\ops\interview\index_interview_qdrant.py --refit --recreate
```

启动查询侧嵌入服务（重建模型后必须重启，或依赖服务内置的模型热重载）：

```powershell
Start-Process python -ArgumentList "D:\何处是家乡\new pj\ops\interview\embedding_server_interview.py" -WindowStyle Hidden
```

检索自检：

```powershell
python .\ops\interview\query_interview_qdrant.py "Redis 缓存穿透怎么解决"
```

Agent 侧通过 `search_interview_questions_qdrant` 工具调用它：LLM 先判断是否需要题库，
Qdrant 按岗位技能与候选人回答召回 Top-K，再由 LLM 结合简历改写成追问。需要具体技术细节时走向量库，
只需要通用开场时退回内置模板，向量库不可用时不阻塞整个面试流程。

## 配置大模型

不配置密钥时使用“LangChain4j 本地推理 + 工具链”，不依赖外网即可运行。接入 OpenAI 兼容接口：

```powershell
$env:OPENAI_API_KEY="your-api-key"
$env:OPENAI_BASE_URL="https://api.openai.com/v1"
$env:OPENAI_MODEL="gpt-4o-mini"
.\mvnw.cmd spring-boot:run
```

支持任意 OpenAI-compatible 服务，只需设置 `OPENAI_BASE_URL` 和 `OPENAI_MODEL`。
### DeepSeek 配置

若使用 DeepSeek，可使用其 OpenAI-compatible 接口：

```powershell
$env:OPENAI_API_KEY="你的 DeepSeek API Key"
$env:OPENAI_BASE_URL="https://api.deepseek.com"
$env:OPENAI_MODEL="deepseek-chat"
.\mvnw.cmd spring-boot:run
```

密钥仅通过环境变量注入，不要写入源码、配置文件或提交到版本库。


## 接入远程 MCP 招聘工具

项目实现 JSON-RPC 2.0 MCP 客户端，期望远程端点支持：

- `initialize`
- `tools/list`
- `tools/call`

目标工具调用形式：

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "search_jobs",
    "arguments": {
      "keyword": "Java",
      "city": "杭州",
      "limit": 30
    }
  }
}
```

启动配置：

```powershell
$env:MCP_ENABLED="true"
$env:MCP_ENDPOINT="https://your-mcp-server.example.com/mcp"
$env:MCP_JOB_TOOL="search_jobs"
$env:MCP_TOKEN="optional-token"
.\mvnw.cmd spring-boot:run
```

内置 MCP 服务位于 `POST /mcp`，可用于联调：

```powershell
$body = @{jsonrpc="2.0";id=1;method="tools/call";params=@{name="search_jobs";arguments=@{city="杭州";limit=5}}} | ConvertTo-Json -Depth 6
Invoke-RestMethod -Uri http://localhost:8080/mcp -Method Post -ContentType "application/json" -Body $body
```

> 实际招聘平台通常有反爬、授权和合规要求。生产环境应通过有合法授权的招聘数据服务或平台官方 MCP 工具接入，不应绕过平台访问控制。

## 合规采集与更新策略

项目默认按以下周期运行数据维护任务：

| 任务 | 默认时间 | 说明 |
| --- | --- | --- |
| 每日过期检查 | 每天 03:10 | 只读取本地数据库，不抓取全站 |
| 每周增量采集 | 每周一 03:20 | 仅处理已启用的授权来源 |
| 每月全量重扫 | 每月 1 日 03:20 | 重新核对授权来源岗位 |
| 每月技能与薪资快照 | 每月 1 日 04:30 | 生成城市、技能和薪资统计 |

当前启用的 BOSS 公开页适配器只访问以下无查询参数页面：

```text
https://www.zhipin.com/changsha/
```

不会访问用户提供的 `?seoRefer=...` 地址、岗位详情页、登录页、验证码页或内部搜索接口。只解析公开岗位卡片中的岗位名、公司、城市、经验、学历和标签，不保存招聘者姓名、手机号、私人邮箱或简历附件。

所有 HTTP 数据源默认遵守：

- 域名白名单
- 单域名单并发
- 2～5 秒请求间隔
- `Crawl-delay` 与 `Retry-After`
- HTTP 403 自动阻断来源
- HTTP 429 自动长时间退避
- 登录、验证码和访问受限页面自动阻断
- 不使用代理轮换、App 伪造或签名逆向
- 保存采集运行日志、来源策略版本和授权状态

管理接口：

```text
GET  /api/admin/collection/policies
PUT  /api/admin/collection/policies/{sourceCode}/enabled?enabled=true
PUT  /api/admin/collection/policies/{sourceCode}/feed?feedUrl=...&intervalMs=5000
GET  /api/admin/collection/runs
POST /api/admin/collection/run?type=INCREMENTAL
POST /api/admin/collection/run?type=FULL
POST /api/admin/collection/run?type=EXPIRY_CHECK
POST /api/admin/collection/run?type=MARKET_STATS
GET  /api/admin/collection/market/latest
```

## 主要接口

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/dashboard` | 总览统计、推荐岗位、最近诊断、智能体状态 |
| GET/POST | `/api/resumes` | 简历列表与新建 |
| POST | `/api/resumes/import` | PDF/OCR 简历识别并返回结构化草稿 |
| PUT/DELETE | `/api/resumes/{id}` | 更新与删除简历 |
| GET | `/api/jobs` | 岗位列表与筛选 |
| GET | `/api/jobs/recommend?resumeId=` | 个性化岗位推荐 |
| POST | `/api/jobs/refresh` | 从远程 MCP 刷新岗位 |
| POST | `/api/interviews` | 创建模拟面试 |
| POST | `/api/interviews/{id}/answers` | 提交回答并获取下一问 |
| GET | `/api/interviews/evidence?resumeId=&jobId=` | 读取最近面试形成的技能证据 |
| POST | `/api/project-questions` | 从公开 GitHub Java 仓库生成源码绑定问题 |
| POST | `/api/project-questions/upload` | 从上传 ZIP 生成源码绑定问题 |
| POST | `/api/diagnoses` | 执行六维简历诊断 |
| GET | `/api/diagnoses/latest/{resumeId}` | 最近一次诊断 |
| POST | `/api/learning-paths` | 生成学习路线 |
| GET | `/api/skills/graph` | 获取技能图谱 |
| POST | `/api/agent/run` | 单智能体全链路编排 |
| POST | `/mcp` | 内置 MCP JSON-RPC 服务端 |

## 构建与测试

```powershell
.\mvnw.cmd test
.\mvnw.cmd package
java -jar target\career-navigation-platform-1.0.0.jar
```

## 项目结构

```text
src/main/java/com/careernav
  config              LangChain4j 运行配置
  common              统一响应与异常处理
  controller          REST 与 MCP 接口
  domain              API 数据模型
  entity              JPA 持久化实体
  repository          数据访问
  service             简历、岗位、面试、诊断、学习、智能体与 MCP 服务
src/main/resources
  application.yml
  static/index.html
  static/styles.css
  static/app.js
```


