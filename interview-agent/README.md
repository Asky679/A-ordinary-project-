# CareerNav LangGraph Agent 服务

本服务现在跑两个 Agent。Java Spring Boot 负责业务数据、事务和算法，Python 负责自主规划与工具选择。

| Agent | 入口 | 职责 |
| --- | --- | --- |
| 面试 Agent | `POST /v1/interviews/start`、`POST /v1/interviews/{id}/answer` | 五轮技能验证、回答评分、技能证据、会话记忆 |
| 岗位推荐 Agent | `POST /v1/diagnosis/agent` | 读简历 -> 语义召回岗位 -> 算法精排 -> 反思重试 -> 输出推荐与技能缺口 |

## 面试技能证据

面试不是独立的小游戏，而是简历诊断的一部分。开始前可选择五种面试类型：

```text
综合面：求职动机 -> 项目职责 -> 业务链路 -> 技术实现
      -> 故障与取舍 -> 团队协作 -> 失败与学习 -> 候选人反问
技术面：个人背景 -> 项目职责 -> 业务链路 -> 技术实现
      -> 故障与边界 -> 方案取舍 -> 结果验证 -> 候选人反问
HR面 / 主管面 / 行为面：按动机、协作、冲突、失败、规划等维度推进
```

LangGraph 会按固定维度计划控制轮次。同一技术主题不会无限追问，行为题不会因为缺少技术关键词被低分。

Agent 根据岗位技能和上一轮回答选择下一问；完成后 Java 侧会把完整面试转录转换为技能证据，写入 `interview_sessions.skill_evidence_json`。低分核心技能会限制岗位适配度，不能靠 Agent 调权绕过。

## 启动

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8001
```

服务会自动读取同目录下的 `.env`（真实环境变量优先），所以不再依赖 `start.ps1` 注入配置。

健康检查会同时报告两个 Agent 的状态：

```powershell
curl http://127.0.0.1:8001/health
```

## 配置在线模型

```powershell
OPENAI_API_KEY=your-key
OPENAI_BASE_URL=https://api.deepseek.com/v1
OPENAI_MODEL=deepseek-chat
```

没有 API Key 时使用本地规则回退，两个 Agent 仍可跑通，只是不做语言理解与润色。

---

## 岗位推荐 Agent（简历诊断方向）

### 设计原则

**大模型负责理解与表达，算法负责打分。**

模型不允许给出匹配分——每个分数都来自 Java 侧已有的加权算法（技能/方向/经验/学历/城市/证据六个维度），
所以结果可复现、可解释，答辩时能逐项讲清分数来源。模型只做两件事：

1. 把简历翻译成「求职画像 + 2~3 条检索语句」（`extract_profile`）
2. 判断这轮召回质量够不够，要不要换说法重搜（`reflect`）

### 状态图

```text
extract_profile ──► recall ──► rank ──► reflect ──┐
   (LLM 读简历        (语义召回     (加权算法      │ retry
    出画像和检索        + 结构化过滤)  精排 + 去重)   │
    语句)                                        ▼
                        ▲───────────────────────┘
                        │
                        └──► gap_analysis ──► compose ──► END
                             (技能缺口统计)    (LLM 写结论 + 防幻觉校验)
```

`reroute` 条件边最多重试 2 次；如果高分对口岗位少于 3 个，或结果里混入明显跑偏的岗位，
模型会换一组更宽的检索语句重来一轮。

### 六个工具

| 工具 | 作用 |
| --- | --- |
| `read_resume(resume_id)` | 读简历（手机号/邮箱不下发，保护隐私） |
| `search_jobs_hybrid(resume_id, query, city)` | 语义检索岗位 |
| `filter_jobs(resume_id, keyword, city)` | 结构化过滤 |
| `recommend_jobs(resume_id, city, excluded_cities)` | 调用加权算法精排，返回分数与维度明细 |
| `get_job_detail(job_id)` | 读岗位职责与任职要求，含 `contentOrigin` 标记 |
| `finalize_recommendation(resume_id, job_ids, summary)` | 防幻觉闸门：只接受本次真实检索过的岗位 ID |

### 调用示例

```powershell
curl -X POST "http://127.0.0.1:8080/api/diagnoses/agent?resumeId=1&city=长沙&limit=6"
```

返回体包含 `recommendations`（岗位 + 分数 + 维度明细）、`skillGaps`（技能缺口统计）和
`trace`（可读的决策轨迹，能看到它换了几次检索语句、每轮召回多少条）。

### 实测

简历 `id=1`（Python 方向、长沙）：

```text
extract_profile  -> 生成 3 条检索语句
recall attempt=0 -> 候选 18 条
rank             -> 精排 13 条（去重 9 条），>=70 分 4 条
reflect          -> retry=True（高分对口岗位不足 3 个，且混入新能源/设计师岗位）
recall attempt=1 -> 候选 21 条
reflect          -> retry=True（仍不足）
recall attempt=2 -> 候选 24 条，语义命中 12 条
compose          -> 防幻觉校验通过
```

全程约 6 秒，去重后 Top1 命中「Python后端开发实习生（甲骨易，长沙）」，匹配度 90。

### 环境变量

```text
CAREERNAV_API_BASE=http://127.0.0.1:8080/api   Java 业务接口地址
CAREERNAV_API_TIMEOUT=60                        单次接口超时（秒）
```

---

## 岗位匹配权重 Agent

`POST /v1/match/rerank` 不返回 LLM 直接分数，只返回每个岗位的：

- 六维受限权重：`skills / role / experience / education / city / evidence`
- 调权理由
- 最多 8 条岗位要求证据

Python 侧会校验并归一化权重；Java 侧再次校验范围，用确定性规则因子计算最终分。这样同一简历和岗位的分数可复现，Agent 不可用时仍能回退到默认规则。

```text
LLM 理解岗位
  -> 选择受限权重
  -> Java 规则引擎计算
  -> 硬性条件检查
  -> 最终岗位适配度
```

## 已知边界

- 加权算法会给少量方向不符的岗位打出中等分数（例如「新能源开发岗」79 分），
  这是算法里「岗位方向」维度基于关键词匹配导致的，不是 Agent 的问题。
  **已在 `rank` 之后加了方向一致性过滤**：标题命中「新能源/设计/销售/讲师…」等非技术职业词、
  且未出现「后端/前端/全栈/开发工程师」等技术岗特征词时判定为跑偏并剔除；
  如果剔除后剩余不足 3 条，则保留但打上 `directionMatch=false` 标记，面板不会变空。
  判定依据会写进 `directionNote`，并在 `trace` 中记录本轮过滤了几条。
- `job_ids` 的防幻觉校验按 `resumeId` 分桶，单机演示足够；多租户部署需要改成按请求隔离。
- Qdrant 的 `careernav_jobs` payload 只存了 job_id/title/company/city 等少量字段，
  所以召回后必须回 PostgreSQL 取详情——这一层由 Java 的 `/api/jobs/hybrid` 已经完成。
## 真实面试模式库

当前 `data/interview-experiences/patterns.jsonl` 从 33 份真实面试对话/面经材料中提炼出 407 条提问模式。2026-11-12 新增 13 份 Java 实战面试对话，新增 187 条模式，覆盖项目职责、业务链路、技术实现、故障边界、方案取舍和结果验证。

题库只保存问题、追问链、阶段、标签和来源元数据，不把候选人答案当作标准答案。原始对话数据位于本地 `data/` 目录，不随公共仓库发布；使用时应保留来源、遵守授权范围，并避免传播个人信息。

