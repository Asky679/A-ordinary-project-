# 项目面试助手：学生视角实测

## 测试条件

- 项目：[CareerNav](https://github.com/Asky679/A-ordinary-project-)，提交 `9f0aac110e0226a6c627831c7cfcff9d555896b3`，分析 80 个 Java 文件。
- 输入描述：`学生课程项目：我参与实现简历版本管理、岗位匹配和模拟面试。没有线上用户或压测数据。`
- 本地 `DEEPSEEK_API_KEY` 调用 DeepSeek 成功。以下 15 题是最终实测输出；部分题由源码模板兜底，模型题按完整方法名和源码原文短语校验后才采用。
- 下方学生回答均为**模拟试答**，只解释代码行为，不代表仓库作者的真实分工或经历。

## 实测题单

### 个人边界

证据：[InterviewService.start](https://github.com/Asky679/A-ordinary-project-/blob/9f0aac110e0226a6c627831c7cfcff9d555896b3/src/main/java/com/careernav/service/InterviewService.java)。

1. 围绕 InterviewService.start 中的条件 `langGraphClient.available()`，哪些实现或决策是你本人完成的？请指出能核对的提交或测试。
2. 请用一次具体输入，沿 InterviewService.start 的这条路径讲清你负责的步骤、输出和与其他模块的交接点。
3. 如果现在改动 InterviewService.start 的这条路径，你会检查哪些调用方和测试？哪些影响在你的负责范围内？

### 方案选型

证据：[JobService.cityJobs，第 121–158 行](https://github.com/Asky679/A-ordinary-project-/blob/9f0aac110e0226a6c627831c7cfcff9d555896b3/src/main/java/com/careernav/service/JobService.java#L121-L158)。

1. 在 JobService.cityJobs 中，条件 `"latest".equalsIgnoreCase(safe(sort))` 对结果有什么影响？你知道这项设计对应的需求或约束吗？
2. 给 JobService.cityJobs 一组会进入这条分支的输入；如果改用另一种实现，结果或成本会怎样变化？
3. 如果项目规模或需求变化，JobService.cityJobs 中的这项设计会先遇到什么限制？你会用什么测试比较替代方案？

### 实现细节

证据：[ResumeVersionService.create，第 43–75 行](https://github.com/Asky679/A-ordinary-project-/blob/9f0aac110e0226a6c627831c7cfcff9d555896b3/src/main/java/com/careernav/service/ResumeVersionService.java#L43-L75)。

1. 请按真实调用顺序解释 ResumeVersionService.create 如何处理一次请求，特别是条件 `request != null && request.targetJobId() != null`。
2. 给一个具体输入，逐步说出它经过 ResumeVersionService.create 时关键状态或数据如何变化。
3. 这条路径遇到空值、重复请求或并发调用时会怎样？请指出 ResumeVersionService.create 中起作用的代码。

### 故障与边界

证据：[LangGraphMatchClient.rerank，第 46–86 行](https://github.com/Asky679/A-ordinary-project-/blob/9f0aac110e0226a6c627831c7cfcff9d555896b3/src/main/java/com/careernav/service/LangGraphMatchClient.java#L46-L86)。

1. 在 LangGraphMatchClient.rerank 中，`catch (Exception exception)` 会怎样影响返回结果？
2. 请给出触发 LangGraphMatchClient.rerank 这条异常路径的具体输入或依赖响应，并逐步说明现有代码怎么处理。
3. 你会怎样写一个可重复的测试，确认 LangGraphMatchClient.rerank 的异常处理没有掩盖问题？

### 结果口径

证据：[LangGraphInterviewClient.start，第 56–69 行](https://github.com/Asky679/A-ordinary-project-/blob/9f0aac110e0226a6c627831c7cfcff9d555896b3/src/main/java/com/careernav/service/LangGraphInterviewClient.java#L56-L69)。

1. 在 LangGraphInterviewClient.start 中，返回值由 `parse(post(...))` 决定；你如何定义并验证一次 start 调用的成功结果口径？
2. 在 LangGraphInterviewClient.start 中，若 `enabled` 为 true、桩服务对 `/v1/interviews/start` 返回合法 JSON，`sessionId` 为 1、`difficulty` 为标准，你期望 `parse(post(...))` 返回什么类型的 `AgentTurn`？
3. 若希望用功能测试验证 LangGraphInterviewClient.start 在桩服务抛异常时进入 `catch (Exception exception)` 并返回 `null`，你会怎样设计这个验证？

## 模拟学生试答与动态追问

**面试官，A3-3：** 简历版本创建遇到并发调用会怎样？

**模拟学生：** 方法先按版本号倒序查已有版本，再用最高版本号加 1。两个请求如果同时读到同一个最高版本号，可能计算出相同的 `versionNo`。[实体上的 `(resume_id, version_no)` 唯一约束](https://github.com/Asky679/A-ordinary-project-/blob/9f0aac110e0226a6c627831c7cfcff9d555896b3/src/main/java/com/careernav/entity/ResumeVersionEntity.java#L10-L15)会阻止两个重复记录都保存成功。当前方法没有显式重试；我没有做过并发实测，因此这只是代码路径推断，需要用两个并发请求和数据库结果验证。

**面试官，A4-1：** `rerank` 异常时返回什么？

**模拟学生初答：** 这是课程项目，没有线上故障。我只在本地模拟过依赖返回错误，观察到代码进入兜底路径；目前还没有做并发压测。

**系统实测动态追问：** 你说在本地模拟过依赖返回错误并观察到兜底路径；针对 `LangGraphMatchClient.rerank`，请具体说明当时传入的 `resume`、`jobs`，以及依赖返回的 `statusCode/body` 或抛出的异常分别是什么，并说明你如何确认执行到 `catch (Exception exception)` 并返回 `null`，而不是在更早的 `enabled/null/jobs` 空判断或 `statusCode >= 300` 分支返回？

**模拟学生修正：** 我刚才把“兜底路径”说得太笼统。如果桩服务只是返回 HTTP 500，代码走的是第 61–64 行的状态码判断并返回 `null`，不会进入第 82 行的 `catch`。要验证 `catch`，应让 HTTP 调用抛异常或让响应解析失败，再检查返回值和 warn 日志。这里是测试方案，不声称我已经实际运行过。

**面试官，A5-1：** 学生项目没有线上指标，如何说明结果？

**模拟学生：** 我会给 `LangGraphInterviewClient.start` 配置启用状态和一个本地桩服务，传入固定的会话、简历、岗位与难度，记录 POST 的路径和请求体，再用固定 JSON 响应验证返回的 `AgentTurn`。关闭功能时应直接返回 `null`。这些是可复现的功能验证口径，不能称为线上成功率或性能提升。

## 这次试答发现的价值与限制

- 动态追问能把“进入兜底”这种含糊说法拆成具体分支，学生必须纠正 HTTP 500 与异常的区别。
- 没有线上数据时，题目改问可复现的功能验证，不要求虚构 QPS、故障和团队分工。
- 代码证据只能验证行为；个人贡献、历史决策和实际测量仍需提交记录、测试结果或学生本人说明。
