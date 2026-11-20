import json
import unittest

from app.graph import _level_guidance, _stage_question
from app.junior_reference import infer_direction, junior_guard_question, reconcile_junior_score
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


if __name__ == "__main__":
    unittest.main()
