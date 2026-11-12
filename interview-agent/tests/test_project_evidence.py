import unittest

from app.graph import InterviewAgent, _enforce_round_policy, _primary_skill, _stage_question


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


    def test_job_blueprint_drives_primary_skill_and_scenario(self):
        context = {
            "job": {"skills": ["Legacy"]},
            "job_blueprint": {
                "mustHaveSkills": ["Redis"],
                "coreResponsibilities": ["订单接口开发"],
                "interviewScenarios": ["支付回调与缓存一致性"],
            },
            "project_evidence": {"projectName": "校园二手交易平台"},
        }
        self.assertEqual(_primary_skill(context), "Redis")
        self.assertIn("订单接口开发", _stage_question(1, context))
        self.assertIn("支付回调与缓存一致性", _stage_question(3, context))

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

    def test_eight_project_stages_use_deidentified_evidence(self):
        context = {
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
                    "keyFieldsRelations": "order 一对多 order_item",
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
                    "unimplemented": "没有支付和退款",
                    "knownLimitations": "缓存一致性只做删除缓存",
                    "scalePlan": "订单分表并引入消息队列",
                },
            },
        }
        questions = [_stage_question(index, context) for index in range(8)]
        self.assertEqual(len(questions), 8)
        self.assertEqual(len(set(questions)), 8)
        self.assertIn("企业订单系统", questions[0])
        self.assertIn("后端开发", questions[0])
        self.assertIn("点击提交订单", questions[1])
        self.assertIn("独立实现订单创建接口", questions[0])
        self.assertIn("JWT Filter", questions[1])
        self.assertIn("POST /orders", questions[2])
        self.assertIn("重复请求生成两笔订单", questions[3])
        self.assertIn("订单创建使用事务", questions[4])
        self.assertIn("接口截图和测试记录", questions[5])
        self.assertIn("缓存一致性", questions[6])
        self.assertIn("10倍", questions[7])


if __name__ == "__main__":
    unittest.main()
