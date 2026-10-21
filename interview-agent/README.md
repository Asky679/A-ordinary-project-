# CareerNav LangGraph Agent 服务

本服务现在跑两个 Agent。Java Spring Boot 负责业务数据、事务和算法，Python 负责自主规划与工具选择。

| Agent | 入口 | 职责 |
| --- | --- | --- |
| 面试 Agent | `POST /v1/interviews/start`、`POST /v1/interviews/{id}/answer` | 四轮递进追问、回答评分、会话记忆 |
| 岗位推荐 Agent | `POST /v1/diagnosis/agent` | 读简历 -> 语义召回岗位 -> 算法精排 -> 反思重试 -> 输出推荐与技能缺口 |

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
