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

## 6. 扩展建议

- 使用对象存储保存 PDF/Word 简历并增加文档解析
- 将 LangGraph 面试 Agent 作为 Python 副服务接入
- 将单智能体拆成可配置工具注册中心
- 为 MCP 增加 Streamable HTTP/SSE 完整传输层
- 接入学校统一认证、多用户隔离与投递进度管理
- 增加诊断报告历史趋势与 ATS 对比实验