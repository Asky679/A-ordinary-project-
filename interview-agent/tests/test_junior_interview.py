import json
import unittest

from app.graph import (
    INTERVIEW_MODE_OPTIONAL_STAGES,
    INTERVIEW_MODE_REQUIRED_STAGES,
    InterviewAgent,
    _enforce_round_policy,
    _level_guidance,
    _stage_question,
)
from app.junior_reference import (
    JUNIOR_REFERENCE,
    infer_direction,
    junior_guard_question,
    junior_stage_question,
    reconcile_junior_score,
    select_next_junior_stage,
)
from app.tools import (
    evaluate_candidate_answer,
    get_junior_interview_reference,
    search_question_bank,
    search_real_interview_patterns,
    set_context,
)


JUNIOR_CONTEXT = {
    "difficulty": "初级 · 标准",
    "interview_mode": "技术面",
    "job": {
        "title": "Java后端实习",
        "company": "测试公司",
        "skills": ["Java", "MySQL"],
        "jobRequirements": "负责基础接口开发，掌握 Java、MySQL，有测试和调试意识。",
    },
    "resume": {
        "city": "长沙",
        "targetRole": "Java后端实习",
        "skills": ["Java", "MySQL"],
        "projects": [{"name": "校园二手交易平台", "role": "后端开发"}],
    },
    "project_evidence": {
        "projectName": "校园二手交易平台",
        "role": "后端开发",
        "evidenceDetails": {
            "ownedModules": "订单创建接口和商品查询",
            "decisionOneTitle": "订单创建使用事务",
            "decisionOneAlternative": "先扣库存再异步建单",
            "testCoverage": "正常下单、库存不足、重复提交",
        },
    },
    "job_blueprint": {
        "mustHaveSkills": ["Java", "MySQL"],
        "coreResponsibilities": ["订单接口开发"],
        "interviewScenarios": ["用户提交订单"],
    },
    "round": 3,
    "current_stage": "技术实现",
}


class JuniorInterviewTests(unittest.TestCase):
    def test_junior_guidance_explicitly_limits_scope(self):
        guidance = _level_guidance("初级 · 标准")
        self.assertIn("个人实现", guidance)
        self.assertIn("一次只问一个", guidance)
        self.assertIn("不要默认追问", guidance)

    def test_junior_stage_questions_are_short_and_concrete(self):
        questions = [_stage_question(index, JUNIOR_CONTEXT) for index in range(8)]
        self.assertEqual(len(questions), 8)
        self.assertEqual(len(set(questions)), 8)
        for question in questions:
            self.assertLessEqual(len(question), 220)
            self.assertLessEqual(question.count("？") + question.count("?"), 1)
            self.assertNotIn("设计一个高可用方案", question)
            self.assertFalse(any(term in question for term in ("异地多活", "组织级治理")))


    def test_direction_prefers_title_over_generic_testing_requirement(self):
        direction = infer_direction({
            "job": {
                "title": "Java后端开发实习生",
                "skills": ["Java", "MySQL"],
                "jobRequirements": "参与接口开发，能够测试和排查问题。",
            },
            "resume": {"targetRole": "Java后端实习", "skills": ["Java"]},
        })
        self.assertEqual(direction, "Java后端")

    def test_junior_question_bank_is_level_specific(self):
        set_context("junior-bank", JUNIOR_CONTEXT)
        result = json.loads(search_question_bank.invoke({
            "session_id": "junior-bank",
            "keyword": "MySQL",
            "stage": "技术实现",
        }))
        self.assertEqual(result["level"], "初级")
        self.assertTrue(result["questions"])
        self.assertFalse(any("高可用方案" in question for question in result["questions"]))
        self.assertTrue(any("MySQL" in question for question in result["questions"]))
        self.assertTrue(any("是什么" in question for question in result["questions"]))


    def test_real_interview_retrieval_prefers_junior_patterns(self):
        set_context("junior-patterns", JUNIOR_CONTEXT)
        result = json.loads(search_real_interview_patterns.invoke({
            "session_id": "junior-patterns",
            "query": "Redis 实践 Bug 排查",
            "stage": "技术实现",
            "limit": 5,
        }))
        self.assertEqual(result["levelFilter"], "初级")
        self.assertTrue(any(str(item.get("id", "")).startswith("junior-real-") for item in result["items"]))


    def test_non_junior_bank_keeps_deeper_module_questions(self):
        set_context("mid-bank", {
            **JUNIOR_CONTEXT,
            "difficulty": "中级 · 标准",
            "round": 3,
        })
        result = json.loads(search_question_bank.invoke({
            "session_id": "mid-bank",
            "keyword": "Redis",
            "stage": "技术实现",
        }))
        self.assertEqual(result["level"], "中级/高级")
        self.assertTrue(any("性能优化" in question or "并发写入" in question for question in result["questions"]))

    def test_junior_reference_tool_returns_research_and_evidence(self):
        set_context("junior-reference", JUNIOR_CONTEXT)
        result = json.loads(get_junior_interview_reference.invoke({
            "session_id": "junior-reference",
            "query": "Java 后端初级",
            "direction": "Java后端",
        }))
        self.assertEqual(result["selectedLevel"], "初级")
        self.assertGreaterEqual(result["researchSummary"]["official_pages_accessible"], 90)
        self.assertTrue(result["officialEvidence"])
        self.assertTrue(result["basicConceptAreas"])
        self.assertTrue(result["interviewRhythm"])
        self.assertEqual(result["direction"], "Java后端")
        self.assertTrue(result["directionPatterns"])
        self.assertTrue(result["directionStagePatterns"])
        self.assertIn("技术实现", result["directionStagePatterns"])

    def test_junior_scoring_rewards_evidence_not_buzzwords(self):
        set_context("junior-score", JUNIOR_CONTEXT)
        concrete = json.loads(evaluate_candidate_answer.invoke({
            "session_id": "junior-score",
            "answer": (
                "我负责订单创建接口，输入是商品和数量，参数为空会返回错误；"
                "我测了正常下单和库存不足，也查日志定位过重复提交问题。"
            ),
        }))
        buzzword = json.loads(evaluate_candidate_answer.invoke({
            "session_id": "junior-score",
            "answer": "我负责高并发分布式架构设计，使用微服务架构保证系统性能。",
        }))
        self.assertEqual(concrete["evaluationMode"], "JUNIOR_TECHNICAL")
        self.assertGreater(concrete["score"], buzzword["score"])
        self.assertGreaterEqual(concrete["score"], 75)
        self.assertLessEqual(buzzword["score"], 55)


    def test_junior_score_is_lenient_for_concrete_partial_answer(self):
        set_context("junior-lenient", {
            **JUNIOR_CONTEXT,
            "current_stage": "项目职责",
        })
        result = json.loads(evaluate_candidate_answer.invoke({
            "session_id": "junior-lenient",
            "answer": "我负责订单创建接口，输入是商品和数量，参数为空会返回错误；我测了正常下单和库存不足。",
        }))
        self.assertGreaterEqual(result["score"], 70)
        self.assertEqual(result["gaps"], [])


    def test_relevant_project_flow_gets_lenient_floor(self):
        set_context("junior-relevance", {
            **JUNIOR_CONTEXT,
            "current_stage": "业务链路",
        })
        result = json.loads(evaluate_candidate_answer.invoke({
            "session_id": "junior-relevance",
            "answer": "用户从岗位雷达进入后，前端把筛选条件发给 Spring Boot Controller，Service 查 PostgreSQL，再调用 Qdrant 做岗位召回，最后返回岗位和匹配信息。",
        }))
        self.assertGreaterEqual(result["score"], 65)

    def test_irrelevant_answer_does_not_get_relevance_floor(self):
        set_context("junior-irrelevant", {
            **JUNIOR_CONTEXT,
            "current_stage": "技术实现",
        })
        result = json.loads(evaluate_candidate_answer.invoke({
            "session_id": "junior-irrelevant",
            "answer": "我不知道，随便吧，这个我没什么可说的。",
        }))
        self.assertLess(result["score"], 65)

    def test_junior_score_reconciliation_uses_rubric_as_floor(self):
        self.assertEqual(reconcile_junior_score(46, 76), 76)
        self.assertEqual(reconcile_junior_score(82, 76), 82)
        self.assertEqual(reconcile_junior_score(None, 68), 68)

    def test_guard_replaces_advanced_multi_question(self):
        guarded, reasons = junior_guard_question(
            "请设计一个高并发分布式架构，同时说明异地多活方案和成本优化？你如何做？",
            "技术实现",
            JUNIOR_CONTEXT,
        )
        self.assertTrue(reasons)
        self.assertNotIn("异地多活", guarded)
        self.assertLessEqual(guarded.count("？") + guarded.count("?"), 1)

    def test_second_to_eighth_questions_change_by_job_direction(self):
        stages = ("项目职责", "业务链路", "技术实现", "故障与边界", "方案取舍", "结果验证", "迁移验证")
        directions = [
            ("Java后端实习", ["Java", "MySQL"]),
            ("前端开发实习", ["Vue", "JavaScript", "CSS"]),
            ("测试工程师实习", ["Selenium", "接口测试"]),
        ]
        questions_by_stage = {}
        for stage in stages:
            values = []
            for title, skills in directions:
                context = {
                    **JUNIOR_CONTEXT,
                    "job": {**JUNIOR_CONTEXT["job"], "title": title, "skills": skills},
                    "resume": {**JUNIOR_CONTEXT["resume"], "targetRole": title, "skills": skills},
                }
                values.append(junior_stage_question(stage, context))
            questions_by_stage[stage] = values
            self.assertEqual(len(set(values)), 3, f"stage={stage}, questions={values}")

        self.assertIn("Controller", questions_by_stage["项目职责"][0])
        self.assertIn("页面", questions_by_stage["项目职责"][1])
        self.assertIn("测试", questions_by_stage["项目职责"][2])

    def test_adaptive_route_adds_verification_when_answer_has_no_evidence(self):
        context = {
            **JUNIOR_CONTEXT,
            "round": 5,
            "current_stage": "技术实现",
            "answer": "我负责订单接口，用了 Spring 和 MySQL。",
            "stage_route": ["个人背景", "项目职责", "业务链路", "技术实现"],
            "visited_stages": ["个人背景", "项目职责", "业务链路", "技术实现"],
        }
        evaluation = {"score": 70, "gaps": ["没有说明如何验证"]}
        stage, reason = select_next_junior_stage(
            context,
            evaluation,
            INTERVIEW_MODE_REQUIRED_STAGES["技术面"],
            INTERVIEW_MODE_OPTIONAL_STAGES["技术面"],
            8,
        )
        self.assertEqual(stage, "结果验证")
        self.assertIn("验证", reason)

    def test_adaptive_route_inserts_basic_concept_for_weak_answer(self):
        context = {
            **JUNIOR_CONTEXT,
            "round": 2,
            "current_stage": "项目职责",
            "answer": "我参与了这个项目。",
            "stage_route": ["个人背景", "项目职责"],
            "visited_stages": ["个人背景", "项目职责"],
        }
        evaluation = {"score": 60, "gaps": ["个人贡献不够明确"]}
        stage, _ = select_next_junior_stage(
            context,
            evaluation,
            INTERVIEW_MODE_REQUIRED_STAGES["技术面"],
            INTERVIEW_MODE_OPTIONAL_STAGES["技术面"],
            10,
        )
        self.assertEqual(stage, "基础概念")
        self.assertEqual(context["target_rounds"], 9)

    def test_adaptive_route_ends_after_required_coverage(self):
        visited = ["个人背景", "项目职责", "业务链路", "技术实现", "故障与边界", "结果验证"]
        context = {
            **JUNIOR_CONTEXT,
            "round": 6,
            "current_stage": "结果验证",
            "answer": "我用正常、异常和边界用例验证过，日志和接口返回都能对上。",
            "stage_route": visited,
            "visited_stages": visited,
        }
        evaluation = {"score": 88, "gaps": []}
        stage, reason = select_next_junior_stage(
            context,
            evaluation,
            INTERVIEW_MODE_REQUIRED_STAGES["技术面"],
            INTERVIEW_MODE_OPTIONAL_STAGES["技术面"],
            8,
        )
        self.assertEqual(stage, "候选人反问")
        self.assertIn("核心维度", reason)
        self.assertEqual(context["target_rounds"], len(INTERVIEW_MODE_REQUIRED_STAGES["技术面"]))

        context["pending_stage"] = stage
        question, done, _, _ = _enforce_round_policy(context, "answer", "", False)
        self.assertFalse(done)
        self.assertTrue(question)

        context["current_stage"] = "候选人反问"
        context["pending_stage"] = "finish"
        context["answer"] = "我想了解团队如何做代码评审。"
        finish_stage, finish_reason = select_next_junior_stage(
            context,
            {"score": 80, "gaps": []},
            INTERVIEW_MODE_REQUIRED_STAGES["技术面"],
            INTERVIEW_MODE_OPTIONAL_STAGES["技术面"],
            8,
        )
        self.assertEqual(finish_stage, "finish")
        self.assertIn("结束", finish_reason)


    def test_full_junior_flow_uses_variable_route_and_finishes(self):
        agent = InterviewAgent.__new__(InterviewAgent)
        agent.agent = None
        agent.llm_enabled = False
        resume = {
            "targetRole": "Java后端实习", "city": "长沙", "skills": ["Java", "MySQL"],
            "projects": [{"name": "校园二手交易平台", "role": "后端开发",
                          "description": "订单和商品接口", "techStack": "Java、Spring Boot、MySQL"}],
        }
        job = {
            "title": "Java后端实习", "company": "测试公司", "city": "长沙",
            "skills": ["Java", "MySQL"],
            "jobRequirements": "负责基础接口开发，掌握 Java、MySQL，有测试和调试意识。",
        }
        evidence = {
            "projectName": "校园二手交易平台", "role": "后端开发",
            "evidenceDetails": {"ownedModules": "订单接口", "testCoverage": "正常下单、库存不足"},
        }
        session_id = "adaptive-full-flow"
        result = agent.start(session_id, resume, job, "初级 · 标准", "技术面", evidence, {}, [])
        self.assertEqual(result["stage"], "个人背景")
        self.assertLessEqual(result["targetRounds"], 10)

        answers = [
            "我负责订单接口，输入是商品和数量，参数为空会返回错误，我测过正常和库存不足。",
            "请求从Controller进入Service，再通过Mapper写MySQL，我负责订单这一段。",
            "我使用Java和Spring实现接口，事务保证库存和订单一致。",
            "如果重复提交我会用状态校验，数据库超时会返回可重试提示。",
            "我选择事务而不是异步建单，因为当前项目先保证一致性。",
            "我用正常、异常和边界用例验证，接口返回和日志都能对上。",
            "我想了解团队如何做代码评审和新人培养。",
            "我会先补强事务和索引，再参与真实业务。",
            "我在项目中负责接口测试，也参与过需求拆分和进度同步。",
            "如果重来一次，我会更早补充日志和异常用例，并增加回归测试。",
        ]
        for answer in answers:
            result = agent.answer(session_id, answer)
            if result["done"]:
                break

        route = result["route"]
        self.assertTrue(result["done"])
        self.assertIn("候选人反问", route)
        self.assertEqual(len(route), len(set(route)))
        self.assertGreaterEqual(len(route), 6)
        self.assertLessEqual(len(route), 10)


    def test_all_direction_questions_obey_one_main_question_rule(self):
        directions = JUNIOR_REFERENCE.get("direction_stage_patterns") or {}
        self.assertEqual(len(directions), 8)
        for direction, stages in directions.items():
            self.assertGreaterEqual(len(stages), 8, direction)
            for stage, patterns in stages.items():
                self.assertTrue(patterns, f"{direction}/{stage}")
                for question in patterns:
                    self.assertLessEqual(question.count("？") + question.count("?"), 1, question)
                    self.assertNotIn("你的一个技术选择", question)
                    self.assertNotIn("另一个备选方案", question)


    def test_missing_decision_evidence_uses_natural_interviewer_question(self):
        context = {
            **JUNIOR_CONTEXT,
            "difficulty": "中级 · 标准",
            "project_evidence": {
                "projectName": "校园二手交易平台",
                "role": "后端开发",
                "evidenceDetails": {"ownedModules": "订单接口"},
            },
        }
        question = _stage_question(5, context)
        self.assertIn("什么技术选择", question)
        self.assertIn("其他做法", question)
        self.assertNotIn("你的一个技术选择", question)
        self.assertNotIn("另一个备选方案", question)

    def test_junior_decision_question_never_uses_placeholders(self):
        question = junior_stage_question("方案取舍", JUNIOR_CONTEXT)
        self.assertNotIn("你的一个技术选择", question)
        self.assertNotIn("另一个备选方案", question)
        self.assertTrue("技术选择" in question or "技术取舍" in question)

    def test_guard_replaces_placeholder_decision_question(self):
        guarded, reasons = junior_guard_question(
            "请说明为什么选择“你的一个技术选择”而不是“另一个备选方案”？",
            "方案取舍",
            JUNIOR_CONTEXT,
        )
        self.assertIn("placeholder-phrase", reasons)
        self.assertNotIn("你的一个技术选择", guarded)
        self.assertNotIn("另一个备选方案", guarded)
        self.assertLessEqual(guarded.count("？") + guarded.count("?"), 1)


    def test_undo_removes_last_answer_and_restores_previous_question_state(self):
        agent = InterviewAgent.__new__(InterviewAgent)
        agent.agent = None
        agent.llm_enabled = False
        agent.checkpointer = None
        resume = {
            "targetRole": "Java后端实习", "city": "长沙", "skills": ["Java", "MySQL"],
            "projects": [{"name": "校园二手交易平台", "role": "后端开发",
                          "description": "订单和商品接口", "techStack": "Java、Spring Boot、MySQL"}],
        }
        job = {
            "title": "Java后端实习", "company": "测试公司", "city": "长沙",
            "skills": ["Java", "MySQL"],
            "jobRequirements": "负责基础接口开发，掌握 Java、MySQL，有测试和调试意识。",
        }
        evidence = {
            "projectName": "校园二手交易平台", "role": "后端开发",
            "evidenceDetails": {"ownedModules": "订单接口", "testCoverage": "正常下单、库存不足"},
        }
        session_id = "undo-one-turn"
        start = agent.start(session_id, resume, job, "初级 · 标准", "技术面", evidence, {}, [])
        answered = agent.answer(
            session_id,
            "我负责订单接口，输入是商品和数量，参数为空会返回错误，我测过正常和库存不足。",
        )
        self.assertEqual(answered["round"], 2)
        self.assertEqual(answered["stage"], "项目职责")

        undone = agent.undo(session_id)
        self.assertTrue(undone["undone"])
        self.assertEqual(undone["round"], 1)
        self.assertEqual(undone["currentStage"], start["stage"])
        self.assertEqual(undone["route"], [start["stage"]])
        snapshot = agent.snapshot(session_id)
        self.assertEqual(snapshot["history"], [])
        self.assertFalse(snapshot["done"])

        replaced = agent.answer(
            session_id,
            "我主要做了商品页面和订单页面的联调，也写了一些接口测试。",
        )
        self.assertEqual(replaced["round"], 2)
        self.assertNotEqual(replaced["feedback"], answered["feedback"])

    def test_undo_rejects_initial_question_without_answer(self):
        agent = InterviewAgent.__new__(InterviewAgent)
        agent.agent = None
        agent.llm_enabled = False
        agent.checkpointer = None
        resume = {"targetRole": "Java后端实习", "city": "长沙", "skills": ["Java"],
                  "projects": [{"name": "项目A", "role": "后端开发", "description": "接口"}]}
        job = {"title": "Java后端实习", "company": "测试公司", "city": "长沙",
               "skills": ["Java"], "jobRequirements": "Java 接口开发"}
        agent.start("undo-empty", resume, job, "初级 · 标准", "技术面", {"projectName": "项目A"}, {}, [])
        with self.assertRaises(ValueError):
            agent.undo("undo-empty")


    def test_incorrect_answer_triggers_reason_followup_and_ten_round_cap(self):
        context = {
            **JUNIOR_CONTEXT,
            "round": 3,
            "current_stage": "技术实现",
            "answer": "我不会，随便写了一个返回值。",
            "stage_route": ["个人背景", "项目职责", "技术实现"],
            "visited_stages": ["个人背景", "项目职责", "技术实现"],
        }
        stage, reason = select_next_junior_stage(
            context,
            {"score": 45, "gaps": ["个人贡献不够明确", "缺少边界或异常处理"]},
            INTERVIEW_MODE_REQUIRED_STAGES["技术面"],
            INTERVIEW_MODE_OPTIONAL_STAGES["技术面"],
            10,
        )
        self.assertEqual(stage, "错误原因")
        self.assertIn("错误原因", reason)
        self.assertEqual(context["target_rounds"], 10)

    def test_strong_answer_can_finish_with_six_rounds(self):
        required = list(INTERVIEW_MODE_REQUIRED_STAGES["技术面"])
        visited = [stage for stage in required if stage != "候选人反问"]
        context = {
            **JUNIOR_CONTEXT,
            "round": 5,
            "current_stage": "结果验证",
            "answer": "我用正常、异常和边界用例验证，接口返回和日志都一致。",
            "stage_route": visited,
            "visited_stages": visited,
        }
        stage, _ = select_next_junior_stage(
            context,
            {"score": 90, "gaps": []},
            required,
            INTERVIEW_MODE_OPTIONAL_STAGES["技术面"],
            10,
        )
        self.assertEqual(stage, "候选人反问")
        self.assertEqual(context["target_rounds"], 6)



if __name__ == "__main__":
    unittest.main()


