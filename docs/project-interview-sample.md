# 项目面试助手：真实题单与参考回答

> 本文保留首次测试时暴露的错误题目，便于对照。充值后使用 DeepSeek 实测、修正后的学生试答见 [project-interview-student-trial.md](project-interview-student-trial.md)。

## 测试输入

- 项目：[CareerNav / A-ordinary-project-](https://github.com/Asky679/A-ordinary-project-)
- 源码快照：`9f0aac110e0226a6c627831c7cfcff9d555896b3`，分析了 80 个 Java 文件。
- 演示用项目描述：`负责岗位匹配，QPS 提升 3 倍`。这是一条**人为输入的待核查声明**，不是仓库中已经证实的业绩。
- 生成模式：本地证据模板。测试时 DeepSeek 接口返回余额不足，因此以下是系统实际返回的模板题，不是模型润色后的题。

以下“参考回答”说明源码能够支持的事实。涉及“谁写的”“当时为什么选”“实际压测结果”“线上故障”的部分，源码无法代答，需要候选人给出提交记录、设计材料、日志或压测报告。题目是系统原样输出，没有修饰。

## 一、个人边界

证据：[JobService.cityJobs，121–158 行](https://github.com/Asky679/A-ordinary-project-/blob/9f0aac110e0226a6c627831c7cfcff9d555896b3/src/main/java/com/careernav/service/JobService.java#L121-L158)。调用入口是 [JobController.cityJobs](https://github.com/Asky679/A-ordinary-project-/blob/9f0aac110e0226a6c627831c7cfcff9d555896b3/src/main/java/com/careernav/controller/JobController.java#L91-L107)。

**A1-1 问：** 在 JobService.cityJobs 中，哪些代码和接口决策是你本人完成的？

**参考答：** 这个方法校验城市、限制页大小为 1–200、筛选可见岗位，再按最新时间或匹配分排序并分页。源码可以指出这些决策点，但无法证明由谁完成。候选人应准确指出自己写的代码、接口参数以及对应提交。

**A1-2 问：** 请沿 JobService.cityJobs 的实际执行路径讲清你负责的部分，以及与上下游同学的交接点。

**参考答：** `JobController.cityJobs` 读取 `resumeId/city/keyword/sort/page/size` 等参数并取得简历，服务层查询城市候选岗位、过滤、排序、分页，最后返回 `CityJobPage`。上下游交接点是控制器参数、`JobRepository.findRecommendationCandidates` 查询和页面消费的分页结构。具体人员分工需要候选人举证。

**A1-3 问：** 如果现在要改动 JobService.cityJobs，你会先检查哪些调用方和测试？当时是谁处理这些影响的？

**参考答：** 先查控制器 `/city` 接口及前端调用，检查查询的 `ACTIVE` 与城市过滤、两种排序、页码边界、匹配分和返回字段。现有仓库有 `JobService` 相关测试，但是否覆盖此方法需逐项核对；历史上是谁处理改动，必须查提交或任务记录。

## 二、方案选型

证据：[DiagnosisService.diagnoseRuleInternal，102–194 行](https://github.com/Asky679/A-ordinary-project-/blob/9f0aac110e0226a6c627831c7cfcff9d555896b3/src/main/java/com/careernav/service/DiagnosisService.java#L102-L194)。

**A2-1 问：** 为什么在 DiagnosisService.diagnoseRuleInternal 中采用当前实现，尤其是 对 evaluate 的调用？

**参考答：** `jobId` 不为空才调用 `jobService.evaluate` 和 `targetMatch`；没选目标岗位时只计算简历质量，选了岗位则将岗位匹配分与简历质量分按 60%/40% 合成总分。`evaluate` 还提供匹配技能等信息。代码说明了当前实现，不能证明当初选择它的理由。

**A2-2 问：** 当时比较过哪些替代实现？请结合 DiagnosisService.diagnoseRuleInternal 的实际输入、数据量和约束说取舍依据。

**参考答：** 源码无法证明比较过哪些方案。可讨论的备选包括仅用规则分、仅用 Agent 分，或不在诊断流程内执行岗位匹配；但候选人需给出当时的实际约束、样本规模和试验记录，不能把这些备选说成已发生的历史。

**A2-3 问：** 如果这些约束发生变化，DiagnosisService.diagnoseRuleInternal 中哪一步会先不适用？你会怎样验证并替换它？

**参考答：** 优先检查 `targetMatch` 外部调用、`learningPathService.generate`、`enrichWithAgent` 的耗时，以及 60%/40% 固定权重对不同岗位是否合适。可分别记录各步骤耗时，用有目标岗位和无目标岗位的样本对比评分效果；是否需要异步或改权重，要以测量结果决定。

## 三、实现细节

证据：[ResumeVersionService.create，43–75 行](https://github.com/Asky679/A-ordinary-project-/blob/9f0aac110e0226a6c627831c7cfcff9d555896b3/src/main/java/com/careernav/service/ResumeVersionService.java#L43-L75) 与 [ResumeVersionEntity 唯一约束](https://github.com/Asky679/A-ordinary-project-/blob/9f0aac110e0226a6c627831c7cfcff9d555896b3/src/main/java/com/careernav/entity/ResumeVersionEntity.java#L10-L15)。

**A3-1 问：** 请按真实调用顺序解释 ResumeVersionService.create 如何处理一次请求，特别是 条件 request != null && request.targetJobId() != null。

**参考答：** 先读取简历视图和实体，查询已有版本得到下一版本号。请求带 `targetJobId` 时尝试计算岗位匹配分和岗位标题；该步骤抛异常时被捕获，版本仍继续创建。随后保存标签、目标岗位 ID、简历 JSON 快照、事实锁、备注和创建时间。

**A3-2 问：** 给一个具体输入，逐步说出它经过 ResumeVersionService.create 时关键状态或数据如何变化。

**参考答：** 假设某简历已有最高版本号 2，请求标签为“后端版”且目标岗位 ID 为 10，则下一版本号为 3；若岗位匹配成功，还记录匹配分和岗位标题。若岗位不存在或计算抛异常，仍保存版本 3 和目标岗位 ID，但标题与分数为空。这个例子是代码路径推演，不是实际数据库运行记录。

**A3-3 问：** 这条路径遇到空值、重复请求或并发调用时会怎样？请指出 ResumeVersionService.create 中起作用的代码。

**参考答：** `request == null` 时仍可创建，标签默认“未命名版本”。重复请求会再生成新版本。两个并发请求可能都读取同一最高版本号、计算出相同的 `next`；数据库对 `(resume_id, version_no)` 有唯一约束，因此其中一个可能插入失败。方法上有事务，但代码未见重试或显式锁。

## 四、故障与边界

证据：[InterviewService.evaluate，206–259 行](https://github.com/Asky679/A-ordinary-project-/blob/9f0aac110e0226a6c627831c7cfcff9d555896b3/src/main/java/com/careernav/service/InterviewService.java#L206-L259)。

**A4-1 问：** 从 InterviewService.evaluate 出发，条件 text.length() >= 80 对应的失败路径会怎样处理？

**参考答：** 这题前提错误。`text.length() >= 80` 是加 10 分并加入“内容较完整”信号；不足 80 字只是不得这项加分，不是失败路径，也不会因此抛异常。这暴露了生成器把一个普通条件误判成故障分支。

**A4-2 问：** 若该步骤失败，当前代码会返回什么、记录什么？你实际遇到过的现象和定位步骤是什么？

**参考答：** 若指“不满 80 字”，方法仍按其他条件计算 45–96 的本地分，构造反馈与下一题。它还调用 `agentModelClient.generateJson`；模型没有有效返回时保留本地结果。这个方法本身没有为短回答记日志。实际线上现象和定位步骤不能由源码虚构。

**A4-3 问：** 如果同一故障在高并发下持续发生，InterviewService.evaluate 的哪一处先成为瓶颈？你会用什么日志或指标证实？

**参考答：** “同一故障”仍无明确对象。可以提出假设：同步调用 `agentModelClient.generateJson` 可能拉长响应时间；需测量调用耗时、超时率、线程占用和请求吞吐，才能判断瓶颈。单看源码不能断言它一定最先成为瓶颈。

## 五、结果口径

证据：[LangGraphMatchClient.rerank，46–86 行](https://github.com/Asky679/A-ordinary-project-/blob/9f0aac110e0226a6c627831c7cfcff9d555896b3/src/main/java/com/careernav/service/LangGraphMatchClient.java#L46-L86) 与 [JobService 的规则回退](https://github.com/Asky679/A-ordinary-project-/blob/9f0aac110e0226a6c627831c7cfcff9d555896b3/src/main/java/com/careernav/service/JobService.java#L270-L302)。

**A5-1 问：** 你描述的量化结果与 LangGraphMatchClient.rerank 有什么关系？基线、测量场景和统计口径是什么？

**参考答：** 目前无法建立“QPS 提升 3 倍”和这个方法的因果关系。源码只显示它向 `/v1/match/rerank` 发 HTTP 请求，超时设为 150 秒，非成功响应或异常时返回 `null`。候选人必须先说明哪条接口的 QPS、优化前后版本、基线及测试环境；不能把示例描述当作证据。

**A5-2 问：** 请说明 LangGraphMatchClient.rerank 改动前后的对照条件：数据量、环境、压测工具和样本次数分别是什么？

**参考答：** 仓库中未见这项声称的对照数据。合格回答应提供简历和岗位样本数、缓存冷热状态、并发数、压测工具、硬件与 Agent 服务版本、重复次数，并同时报告 QPS、P95/P99、错误率。缺少这些记录就只能说“尚未验证”。

**A5-3 问：** 如果重做 LangGraphMatchClient.rerank，你会先改哪一处？预计收益如何测量，什么结果会推翻你的判断？

**参考答：** 一个可验证假设是先测 150 秒请求超时与外部服务耗时分布，再评估是否需要更短超时或隔离策略。用相同样本做改动前后对照，同时看 QPS、尾延迟、失败率和规则回退比例；若尾延迟或失败率恶化，就不能声称优化有效。是否真要这么改，还需实测。

## 这轮测试暴露的问题

1. 系统确实生成了带源码位置的 15 题，但固定模板中有重复、泛化的追问。
2. “故障与边界”把 `text.length() >= 80` 当作失败路径，属于语义误判。
3. “结果口径”能追问数据来源，却无法从现有证据证明示例 QPS 与 `rerank` 相关。
4. 代码可以验证执行路径，不能代替候选人证明本人贡献、历史选型和线上经历。
