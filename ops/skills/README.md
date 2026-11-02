# Java 技能水平验证

## 当前范围

- 技能：Java
- 题库：40 道单选，覆盖 String、集合框架、面向对象、异常处理、HashMap、Stream API、迭代器、并发编程、并发集合
- 难度：BASIC 14、INTERMEDIATE 15、ADVANCED 11
- 第 1 轮：广度诊断，6 题（基础 2、中级 2、高级 2）
- 第 2 轮：针对低掌握点换题复测，4 题
- 第 3 轮：猜测风险较高或结果不一致时触发，4 题
- 评分：按难度加权，初级 1、中级 2、高级 3
- 测试结果写入 `skill_proficiency`，对应简历技能画像

## 题库来源

- 基础公开题：`ViacheslavChernyshov/java-interview-questions-and-answers`
- Commit：`597e08112ab6a57d6eac8e3c70c070da606dae73`
- License：MIT
- 项目内部补充题：标记为 `CareerNav internal Java assessment bank` / `Project-Internal`
- 所有题目保留来源和许可证字段

## 创建数据库并导入题库

```powershell
python .\ops\skills\seed_java_questions.py
```

脚本会创建或迁移：

- `skill_questions`
- `skill_assessment_sessions`
- `skill_assessment_rounds`
- `skill_assessment_answers`
- `skill_proficiency`
- `skill_mastery_state`

## 接口

多轮接口：

```text
POST /api/skills/tests/java/multi-round
POST /api/skills/tests/{sessionId}/rounds/{roundNo}/submit
GET  /api/skills/tests/proficiency?resumeId=1&skill=Java
```

旧单轮接口保留兼容：

```text
POST /api/skills/tests/java
POST /api/skills/tests/{sessionId}/submit
```

## BKT 强化

每题记录：

- 作答时间 `response_ms`
- 主观把握度 `self_confidence`
- 更新前掌握概率 `bkt_before`
- 更新后掌握概率 `bkt_after`
- 单题疑似猜测风险 `guess_risk`

每轮结束后计算：

- `bktMastery`：按知识点难度权重汇总掌握概率
- `bktConfidence`：结合题量、知识点覆盖、轮次和是否超时
- `consistency`：同一知识点多轮作答的一致性
- `guessRisk`：结合 BKT 先验、快速答对、低把握答对和结果矛盾

### 多轮选题

第 2 轮优先选择：

1. 上一轮答错的知识点
2. BKT 掌握概率最低的知识点
3. 不同知识点换题复测

第 3 轮优先选择：

1. 同一知识点结果矛盾的题目
2. 高猜测风险知识点
3. 低掌握知识点

### 作用与边界

BKT 用于区分一次测试发挥和长期知识状态，并定位具体薄弱知识点。多轮验证会提高猜测识别的证据量，但 `guessRisk` 是风险提示，不是作弊判定；最终技能等级仍由难度加权测试分和规则门槛决定，BKT 不覆盖硬性分数。