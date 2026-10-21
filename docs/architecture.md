# 架构设计说明

## 1. 核心目标

平台不是独立的简历评分器，而是围绕“简历诊断”建立职业发展闭环：

```text
简历事实 -> 岗位需求 -> 技能差距 -> 面试表达 -> 诊断优化 -> 学习行动
```

单智能体负责选择工具、串联工具结果，并向用户输出统一建议。

## 2. 分层设计

### 展示层

- 原生单页应用，无前端构建依赖
- REST 接口统一使用 `ApiResponse<T>`
- 页面包含总览、简历、岗位、面试、诊断、学习六个工作区

### 应用层

- `ResumeService`：结构化简历与 JSON 字段转换
- `JobService`：岗位检索、技能标准化、匹配评分
- `InterviewService`：问题生成、回答评分、会话状态推进
- `DiagnosisService`：六维简历诊断与修改建议生成
- `LearningPathService`：技能差距与阶段路线
- `SkillGraphService`：岗位技能图谱与需求热度

### 智能体层

`AgentOrchestrator` 负责工具链选择和编排：

1. `resume.profile`：读取简历事实
2. `job.match`：计算岗位匹配和技能缺口
3. `interview.start`：创建岗位模拟面试
4. `resume.diagnose`：生成六维诊断报告
5. `learning.generate`：生成学习与项目交付路线

若配置了 `OPENAI_API_KEY`，编排器再调用 LangChain4j `ChatLanguageModel` 生成自然语言总结；模型异常时自动回退到确定性总结，不阻断主链路。

### MCP 层

- `McpJobGateway`：作为 MCP Client，向远程端点发送 `tools/call`
- `McpServerController`：作为本地 MCP Server，支持 `initialize`、`tools/list`、`tools/call`
- `JobRefreshService`：按配置周期刷新岗位
- 远程 MCP 不可用时保留本地岗位库，前端不受影响

## 3. 岗位匹配算法

默认权重为：

- 技能匹配 35
- 岗位方向 20
- 工作经验 15
- 学历背景 10
- 城市偏好 5
- 项目证据 5

系统先按用户设置归一化权重，再计算综合分。同时设置硬性上限：

- 核心技能覆盖率低于 50%：最高 69 分
- 岗位方向差异较大：最高 70 分
- 工作经验明显不足：最高 65 分
- 学历不满足：最高 70 分
- 城市不匹配：最高 75 分

用户可以在岗位雷达页面调整六项权重，也可以设置不接受城市。评分结果返回分项贡献和解释原因。

## 4. 简历诊断模型

简历诊断拆成两个独立分数：

```text
综合竞争力 = 岗位匹配 × 60% + 简历质量 × 40%
```

简历质量维度：

| 维度 | 权重 |
| --- | ---: |
| 内容完整性 | 20% |
| 成果量化 | 20% |
| 技术深度 | 15% |
| 表达与 ATS | 20% |
| 教育基础 | 10% |
| 项目可信度 | 15% |

岗位匹配直接复用岗位雷达的六维算法。系统同时输出综合分、简历质量、岗位匹配、分项贡献、失分原因和 P0/P1/P2 修改建议。简历更新后，旧诊断报告会自动失效。

## 5. 数据持久化

业务数据使用 PostgreSQL 18.6：

- 岗位、简历、面试会话、诊断报告和采集策略
- 复杂项目、技能、报告结构使用 JSON 文本保存
- PostgreSQL 不保存向量，不再依赖 pgvector

岗位向量使用 Qdrant 1.19.2：

- HTTP API：`http://127.0.0.1:6333`
- Collection：`careernav_jobs`
- 向量维度：256
- 距离：Cosine
- 当前索引岗位：4,110

本地 Embedding 服务：

```text
http://127.0.0.1:5001/v1
tfidf-svd-256
```

Spring Boot 通过 Qdrant REST API 创建 collection、批量写入 points、执行向量检索和返回 Qdrant 状态。

## 5.1 面试题向量库

岗位向量解决“招什么”，面试题向量解决“问什么”，两者分属不同集合、不同嵌入空间，互不干扰。

```text
careernav_jobs                    4,110 条   岗位检索（Java LangChain4j / JobVectorSearchService）
careernav_interview_questions     1,508 条   题库检索（Python LangGraph search_interview_questions_qdrant）
```

数据链路：

```text
4 个公开 GitHub 仓库（MIT / Apache-2.0 / CC-BY-4.0 / CC-BY-SA-4.0）
  -> 只镜像 .md/.json 文本（约 6.7 MB，跳过图片）
  -> 按来源解析为统一 JSONL（题干 / 答案 / 阶段 / 技能 / 来源 / 许可证）
  -> 本地 TF-IDF + TruncatedSVD 256 维（端口 5002）
  -> upsert 到 Qdrant，payload 建索引：source / stage / role / kind / lang / difficulty / skills
```

检索时的自主编排：

```text
LLM 判断是否需要外部题库
  -> 组织查询（候选人回答 / 岗位技能）
  -> 5002 生成查询向量
  -> Qdrant Top-K（stage 过滤，Top-1 分数低于 0.35 时自动放宽过滤）
  -> 返回题干 + 参考要点 + sourceUrl + license
  -> LLM 结合简历改写成追问，并写入面试记忆
```

设计取舍：

- **独立集合**：岗位与题库的向量语义完全不同，混在一起会互相污染召回结果。
- **独立嵌入模型**：题库模型在题库语料上重新拟合，短查询（如“缓存雪崩”）命中率显著优于复用岗位模型。
- **模型热重载**：重建索引会改写模型文件，嵌入服务按 mtime 自动重载，避免查询向量与库中向量属于不同向量空间。
- **降级不阻塞**：Qdrant 或嵌入服务不可用时工具返回 `available:false`，Agent 退回内置模板，面试流程继续。
- **署名可追溯**：每条记录携带 `sourceUrl` 与 `license`，检索结果向使用者展示来源。

## 6. 扩展建议

- 使用对象存储保存 PDF/Word 简历并增加文档解析
- 将 LangGraph 面试 Agent 作为 Python 副服务接入
- 将单智能体拆成可配置工具注册中心
- 为 MCP 增加 Streamable HTTP/SSE 完整传输层
- 接入学校统一认证、多用户隔离与投递进度管理
- 增加诊断报告历史趋势与 ATS 对比实验