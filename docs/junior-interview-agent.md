# 初级程序员面试 Agent 集成说明

> 集成日期：2026-11-20  
> 目标：让初级程序员的问法、追问和评分与中级/高级真正分开，并基于企业官方招聘页和真实面试对话校准。

## 1. 数据位置

新增目录：

`interview-agent/data/junior-engineer/`

包含：

- `reference.json`：初级面试规则、方向重点、真实问法、公司规模差异和官方证据摘要。
- `official_career_pages.csv`：129 个官方招聘入口的核验结果。
- `baidu_campus_jobs.csv`：百度官方校园招聘岗位样本。
- `tencent_0_1_jobs.csv`：腾讯官方经验不限/一年以上技术岗位样本。
- `amazon_entry_jobs.csv`：Amazon entry/university 岗位样本。
- `interview-agent/data/interview-experiences/patterns.jsonl`：累计新增 62 条初级真实面试问法，覆盖 Java 基础、集合、并发、Spring、MyBatis、MySQL、Redis、Linux、MQ、手写算法、项目边界和动机等。

这些数据的来源是 2026-11-20 直接访问的企业官方招聘页、官方招聘系统和官方招聘接口，不以项目岗位库作为初级规则的主要依据。

## 2. 运行时模块

新增：

`interview-agent/app/junior_reference.py`

职责：

1. 判断当前难度是否为初级/实习/校招/应届；
2. 根据岗位标题、技能和简历推断方向；
3. 生成初级阶段的短问题；
4. 检测并替换过于架构化、过长或包含多个问题的初级提问；
5. 对初级回答执行独立评分。

## 3. 题库分层

`interview-agent/app/tools.py`：

- `search_question_bank` 现在会读取当前会话的 `difficulty`。
- 初级走 `junior_question_candidates()`，不再返回“高可用方案设计”这类默认问题。
- 中级/高级继续使用原问题库。
- 新增 `get_junior_interview_reference` 工具，AI 可主动读取：
  - 初级能力边界；
  - 各阶段问题模板；
  - Java、前端、测试、运维、数据/AI、嵌入式、安全、Go 方向重点；
  - 大公司/中型公司/小公司差异；
  - 官方招聘证据摘要。

## 4. 初级问题守卫

`interview-agent/app/graph.py`：

- `_level_guidance()` 对初级给出了明确约束。
- `_stage_question()` 在初级模式下直接使用 `junior_stage_question()`。
- LLM 生成问题后，再经过 `junior_guard_question()`：
  - 一次出现多个问号：替换；
  - 超过 220 字：替换；
  - 默认出现高并发、异地多活、组织级治理等高级词且没有项目证据：替换。

即使模型临时“问偏”，最终返回给候选人的问题也会落回具体的项目/边界/测试问题。

本轮追加参考的初级对话包括：

- 深圳法本 Java 初级面试；
- 另一份 Java 初级面试录音整理稿；
- 阿里达摩院初级程序员面试；
- 应届生初级开发模拟面试。

## 4.1 新补充的真实初级节奏

结合四份新的初级面试对话，真实面试往往不是只挖一个项目，而是：

```text
自我介绍 → 1 个项目 → 3-8 个短基础题 → 1 个真实 Bug/项目追问 → 动机或反问
```

因此 `reference.json` 新增了：

- `interview_rhythm`：真实初级面试节奏和推荐问题分布；
- `basic_concept_areas`：Java、集合、并发、Spring、MyBatis、MySQL、Redis、Linux、MQ、算法等基础题范围。

初级 Agent 可以从“短基础题”进入，再根据回答转到项目证据和边界追问。

## 4.2 初级评分放宽

面向刚毕业学生，初级评分采用更宽松的锚点：

- 基础分从 38 提高到 44；
- 65–74 表示有培养潜力；
- 75–84 表示符合初级基本要求；
- 没有生产经验本身不重罚；
- 具体、诚实、能验证的回答优先给 65 分以上；
- LLM 评分与初级确定性评分取较高值，避免模型用中高级标准压分；
- 回答包含项目、接口、数据库、测试、日志等事实且不是乱答时，至少进入 65 分档。

## 5. 初级评分

`score_junior_answer()` 重点奖励：

- 是否说明个人行动；
- 是否讲清输入输出和边界；
- 是否说明测试或验证；
- 是否描述排查过程；
- 是否给出日志、截图、测试记录等证据；
- 是否如实说明没做过、不是自己负责或只了解；
- 是否结合岗位相关技能。

初级回答不会因为堆“高并发、分布式、微服务、架构设计”等词而拿高分；没有测试/验证证据时会被限制在较低分数。

## 6. Java 兜底

`src/main/java/com/careernav/service/InterviewService.java`：

- 初级第一问和后续兜底问题改为短问法；
- LangGraph/LLM 不可用时，Java 本地兜底也会使用初级问题；
- 初级兜底评分与 Python 初级评分方向一致。

## 7. 验证

已通过：

```text
Python：33 个单元测试通过
Java：mvnw test 通过
```

新增测试：

`interview-agent/tests/test_junior_interview.py`

覆盖：

- 初级问题不默认问高可用/异地多活；
- 每题不超过一个主要问句；
- 初级题库使用独立等级；
- 初级参考数据可读取；
- 真实面试检索优先返回初级问法；
- 具体证据回答得分高于架构词堆砌；
- 高级问题会被守卫替换；
- 方向判断优先看岗位标题，不会被 JD 中的“测试”误判为测试岗。
