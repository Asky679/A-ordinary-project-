import unittest

from app.graph import (
    INTERVIEW_MODE_PLANS,
    InterviewAgent,
    _enforce_round_policy,
    _level_guidance,
    _primary_skill,
    _stage_question,
)


class ProjectEvidenceQuestionTests(unittest.TestCase):

    def test_model_cannot_end_before_eight_rounds(self):
        context = {
            "round": 4,
            "max_rounds": 8,
            "job": {"skills": ["Java"]},
            "project_evidence": {"projectName": "校园二手交易平台"},
        }
        question, done, round_number, max_rounds = _enforce_round_policy(
            context, "answer", "", True)
        self.assertFalse(done)
        self.assertTrue(question)
        self.assertEqual(round_number, 4)
        self.assertEqual(max_rounds, 8)

    def test_interview_ends_at_eight_rounds(self):
        context = {"round": 8, "max_rounds": 8, "project_evidence": {"projectName": "项目"}}
        question, done, _, _ = _enforce_round_policy(context, "answer", "", False)
        self.assertTrue(done)
        self.assertEqual(question, "")

    def test_interview_level_changes_question_scope(self):
        self.assertIn("个人实现", _level_guidance("初级 · 标准"))
        self.assertIn("可靠性", _level_guidance("高级 · 标准"))
        self.assertIn("系统边界", _level_guidance("架构师 · 压力"))

    def test_job_blueprint_drives_primary_skill_and_scenario(self):
        context = {
            "interview_mode": "技术面",
            "job": {"skills": ["Legacy"]},
            "job_blueprint": {
                "mustHaveSkills": ["Redis"],
                "coreResponsibilities": ["订单接口开发"],
                "interviewScenarios": ["支付回调与缓存一致性"],
            },
            "project_evidence": {"projectName": "校园二手交易平台"},
        }
        self.assertEqual(_primary_skill(context), "Redis")
        self.assertIn("订单接口开发", _stage_question(2, context))
        self.assertIn("支付回调与缓存一致性", _stage_question(4, context))

    def test_interview_modes_cover_behavior_and_motivation(self):
        self.assertIn("求职动机", INTERVIEW_MODE_PLANS["综合面"])
        self.assertIn("团队协作", INTERVIEW_MODE_PLANS["综合面"])
        self.assertIn("失败与学习", INTERVIEW_MODE_PLANS["综合面"])
        self.assertIn("候选人反问", INTERVIEW_MODE_PLANS["综合面"])
        for plan in INTERVIEW_MODE_PLANS.values():
            self.assertEqual(len(plan), 8)
            self.assertEqual(len(set(plan)), 8)

    def test_comprehensive_interview_has_non_technical_rounds(self):
        context = {
            "interview_mode": "综合面",
            "job": {"title": "Java开发", "company": "测试公司", "skills": ["Java"]},
            "resume": {"city": "长沙", "projects": [{"name": "校园二手交易平台"}]},
            "project_evidence": {"projectName": "校园二手交易平台", "role": "后端开发"},
        }
        questions = [_stage_question(index, context) for index in range(8)]
        self.assertEqual(len(questions), 8)
        self.assertEqual(len(set(questions)), 8)
        self.assertIn("为什么选择", questions[0])
        self.assertIn("校园二手交易平台", questions[1])
        self.assertIn("队友", questions[5])
        self.assertIn("失败", questions[6])
        self.assertIn("反问", questions[7])

    def test_story_bank_prompts_candidate_to_change_repeated_example(self):
        context = {
            "interview_mode": "行为面",
            "job": {"title": "Java开发", "skills": ["Java"]},
            "resume": {"projects": [{"name": "校园二手交易平台"}]},
            "project_evidence": {"projectName": "校园二手交易平台", "role": "后端开发"},
            "story_bank": [{"title": "订单库存冲突处理", "projectName": "校园二手交易平台"}],
        }
        question = _stage_question(1, context)
        self.assertIn("之前", question)
        self.assertIn("不同的例子", question)
    def test_selected_project_filters_out_other_resume_projects(self):
        agent = InterviewAgent.__new__(InterviewAgent)
        resume = {"projects": [
            {"name": "校园二手交易平台", "role": "全栈"},
            {"name": "企业订单系统", "role": "后端"},
        ]}
        evidence = {
            "projectName": "企业订单系统", "role": "后端开发",
            "businessFlow": "下单到支付", "techStack": "Java、Redis",
            "challenge": "重复回调", "resultEvidence": "响应时间降低30%",
        }
        focused = agent._focus_resume_project(resume, evidence)
        self.assertEqual([item["name"] for item in focused["projects"]], ["企业订单系统"])
        self.assertEqual(focused["projects"][0]["description"], "下单到支付")

    def test_technical_interview_uses_deidentified_evidence_and_changes_dimensions(self):
        context = {
            "interview_mode": "技术面",
            "job": {"skills": ["Redis"]},
            "resume": {"projects": [{"name": "兜底项目"}]},
            "project_evidence": {
                "projectName": "企业订单系统",
                "role": "后端开发",
                "businessFlow": "下单到支付",
                "implementation": "订单服务和幂等处理",
                "challenge": "支付回调重复",
                "resultEvidence": "响应时间降低30%",
                "evidenceDetails": {
                    "ownedModules": "独立实现订单创建接口和幂等处理",
                    "notOwnedModules": "支付渠道接入不是我负责",
                    "entryPoint": "点击提交订单",
                    "processingChain": "JWT Filter → OrderController → OrderService → MySQL 事务",
                    "dataChange": "写入 order 表并扣减库存",
                    "responsePath": "返回订单号和状态",
                    "coreApi": "POST /orders 创建订单",
                    "apiContract": "校验库存、登录权限和重复请求",
                    "coreTables": "order、order_item、product",
                    "stateTransitions": "CREATED → PAID → CANCELLED",
                    "decisionOneTitle": "订单创建使用事务",
                    "decisionOneAlternative": "先扣库存再异步建单",
                    "decisionOneTradeoff": "保证一致性但事务更长",
                    "decisionTwoTitle": "使用幂等键",
                    "decisionTwoAlternative": "只依赖前端防重复点击",
                    "decisionTwoTradeoff": "增加存储成本但可阻止重复提交",
                    "faultSymptom": "重复请求生成两笔订单",
                    "faultDiagnosis": "接口没有幂等控制",
                    "faultFixVerification": "增加唯一约束并完成重复请求回归",
                    "testCoverage": "正常下单、库存不足、重复提交",
                    "evidenceAvailable": "接口截图和测试记录",
                    "unmeasuredData": "没有线上并发数据",
                    "knownLimitations": "缓存一致性只做删除缓存",
                    "scalePlan": "订单分表并引入消息队列",
                },
            },
        }
        questions = [_stage_question(index, context) for index in range(8)]
        self.assertEqual(len(questions), 8)
        self.assertEqual(len(set(questions)), 8)
        self.assertIn("企业订单系统", questions[1])
        self.assertIn("后端开发", questions[1])
        self.assertIn("独立实现订单创建接口", questions[1])
        self.assertIn("点击提交订单", questions[2])
        self.assertIn("JWT Filter", questions[2])
        self.assertIn("POST /orders", questions[3])
        self.assertIn("重复请求生成两笔订单", questions[4])
        self.assertIn("订单创建使用事务", questions[5])
        self.assertIn("接口截图和测试记录", questions[6])
        self.assertIn("反问", questions[7])

    def test_adaptive_route_applies_to_non_junior_interviews(self):
        agent = InterviewAgent.__new__(InterviewAgent)
        agent.agent = None
        agent.llm_enabled = False
        agent.checkpointer = None
        resume = {
            "targetRole": "Java后端开发", "city": "长沙", "skills": ["Java", "MySQL"],
            "projects": [{"name": "校园二手交易平台", "role": "后端开发", "description": "订单接口"}],
        }
        job = {"title": "Java后端开发", "company": "测试公司", "city": "长沙",
               "skills": ["Java", "MySQL"], "jobRequirements": "负责接口设计、故障处理和优化"}
        evidence = {"projectName": "校园二手交易平台", "role": "后端开发",
                    "evidenceDetails": {"ownedModules": "订单接口", "testCoverage": "正常和异常用例"}}
        result = agent.start("adaptive-mid", resume, job, "中级 · 标准", "技术面", evidence, {}, [])
        self.assertLessEqual(result["targetRounds"], 10)
        self.assertEqual(result["round"], 1)
        snapshot = agent.snapshot("adaptive-mid")
        self.assertNotEqual(snapshot["plan"], ["个人背景", "项目职责", "业务链路", "技术实现", "故障与边界", "方案取舍", "结果验证", "候选人反问"])
        self.assertEqual(snapshot["requiredStages"], ["个人背景", "项目职责", "业务链路", "技术实现", "结果验证", "候选人反问"])



if __name__ == "__main__":
    unittest.main()
