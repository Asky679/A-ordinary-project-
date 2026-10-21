# 面试题向量库（Interview Question Vector Store）

岗位向量负责“招什么”，本目录负责“面试问什么”。数据来自许可证明确的公开 GitHub 仓库，
只镜像纯文本，构建成统一 JSONL，再写入独立的 Qdrant 集合。

```text
Qdrant collection : careernav_interview_questions
维度 / 距离       : 256 / Cosine
Embedding 服务    : http://127.0.0.1:5002/v1/embeddings
数据目录          : D:\何处是家乡\data\github-interview-datasets
```

## 数据来源与许可证

| 仓库 | 许可证 | 入库条数 | 用途 |
| --- | --- | --- | --- |
| `Snailclimb/JavaGuide` | Apache-2.0 | 1,041 | Java / 数据库 / 网络 / 算法 / AI 问答 |
| `yangshun/tech-interview-handbook` | MIT | 278 | 行为面试题、算法与求职指南 |
| `codeprepdev/codeprep-questions` | CC-BY-4.0 | 108 | 结构化技术题（含解析） |
| `doocs/advanced-java` | CC-BY-SA-4.0 | 81 | 高并发 / 分布式 / 微服务面试题 |

每一条记录都保留 `source`、`sourcePath`、`sourceUrl`、`license`，检索结果可直接回溯原作者。
本地镜像用于检索，不对外再分发原文。

## 文件说明

| 文件 | 作用 |
| --- | --- |
| `fetch_github_interview_sources.py` | 通过 jsDelivr 枚举并下载仓库中的 .md/.json 文本文件 |
| `build_interview_questions.py` | 按来源解析、清洗、去重，输出统一 JSONL 与统计 |
| `index_interview_qdrant.py` | 训练/复用嵌入模型，创建集合，分批 upsert |
| `embedding_server_interview.py` | 查询侧本地嵌入服务（端口 5002，支持模型热重载） |
| `query_interview_qdrant.py` | 命令行检索自检工具 |

## 运行

```powershell
# 1. 镜像文本（只下 .md/.json，跳过图片，约 6.7 MB）
python .\ops\interview\fetch_github_interview_sources.py

# 2. 解析为统一 JSONL
python .\ops\interview\build_interview_questions.py

# 3. 建库（首次或换模型时加 --refit --recreate）
python .\ops\interview\index_interview_qdrant.py --refit --recreate

# 4. 启动查询侧嵌入服务（端口 5002）
python .\ops\interview\embedding_server_interview.py

# 5. 检索自检
python .\ops\interview\query_interview_qdrant.py "MySQL 索引失效"
```

`index_interview_qdrant.py` 会重写模型文件 `tfidf_svd_256_interview.joblib`。如果在服务运行期间重建，
`embedding_server_interview.py` 会按文件修改时间自动重载，避免查询向量与库中向量属于不同向量空间
（这正是本次联调中实际踩到并修掉的一个坑）。

## 记录结构

```json
{
  "id": "JavaGuide:docs/database/mysql/mysql-questions-01.md#索引失效的原因有哪些？:12345",
  "question": "索引失效的原因有哪些？",
  "answer": "1. 创建了组合索引，但查询条件未遵守最左匹配原则; ...",
  "kind": "qa",
  "role": "后端 / 数据库",
  "stage": "backend",
  "skills": ["MySQL", "数据库"],
  "difficulty": "标准",
  "lang": "zh",
  "source": "JavaGuide",
  "sourcePath": "docs/database/mysql/mysql-questions-01.md#索引失效的原因有哪些？",
  "sourceUrl": "https://github.com/Snailclimb/JavaGuide/blob/main/docs/database/mysql/mysql-questions-01.md",
  "license": "Apache-2.0",
  "collectedAt": "2026-10-13"
}
```

`kind` 取值：

- `qa`：题干 + 参考答案
- `question`：只有题干（多为行为面试题，用于生成追问）
- `note`：求职流程类参考知识块

## 检索质量自检

| 查询 | Top-1 命中 | 分数 |
| --- | --- | --- |
| Redis 缓存穿透、雪崩怎么解决 | 了解什么是 Redis 的雪崩、穿透和击穿？ | 0.855 |
| MySQL 索引失效的场景有哪些 | 索引失效的原因有哪些？ | 0.704 |
| Java 线程池的核心参数怎么设置 | 线程池介绍 | 0.729 |
| 说说你在项目里做过的一次性能优化 | 性能优化就是重构吗？ | 0.679 |

## 与 Agent 的连接

Python 侧新增工具 `search_interview_questions_qdrant`（定义在 `interview-agent/app/tools.py`）：

```text
LLM 判断是否需要题库
  -> 组织查询（候选人回答 / 岗位技能）
  -> 5002 生成查询向量
  -> Qdrant 召回 Top-K（可按 stage 过滤，低分时自动放宽）
  -> 返回题目、参考要点、来源与许可证
  -> LLM 结合简历改写成追问
```

可用环境变量覆盖默认地址：

```text
INTERVIEW_QDRANT_URL=http://127.0.0.1:6333
INTERVIEW_COLLECTION=careernav_interview_questions
INTERVIEW_EMBEDDING_URL=http://127.0.0.1:5002/v1/embeddings
```

向量库不可用时，工具返回 `available:false`，Agent 自动退回内置 `search_question_bank`，面试流程不中断。
