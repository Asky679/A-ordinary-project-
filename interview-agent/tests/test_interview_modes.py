import json
import unittest

from app.tools import evaluate_candidate_answer, set_context


class InterviewModeEvaluationTests(unittest.TestCase):
    def test_behavior_stage_does_not_require_technical_keywords(self):
        set_context("behavior-stage", {
            "job": {"title": "Java开发", "skills": ["Java"]},
            "interview_mode": "行为面",
            "current_stage": "团队协作",
        })
        answer = (
            "当时项目进入联调阶段，我和队友在接口分工上出现冲突。"
            "我先列出双方任务和时间节点，然后主动沟通，把能并行的工作拆开，"
            "自己承担了最紧急的联调问题。最后我们按计划完成验收，我也反思到"
            "提前同步接口约定比事后协调更有效。"
        )
        result = json.loads(evaluate_candidate_answer.invoke({
            "session_id": "behavior-stage", "answer": answer,
        }))
        self.assertEqual(result["evaluationMode"], "BEHAVIORAL")
        self.assertNotIn("没有命中岗位技术关键词", result["gaps"])
        self.assertGreaterEqual(result["score"], 65)

    def test_technical_stage_still_checks_technical_evidence(self):
        set_context("technical-stage", {
            "job": {"title": "Java开发", "skills": ["Java", "Redis"]},
            "interview_mode": "技术面",
            "current_stage": "技术实现",
        })
        answer = "我负责订单模块，做了接口和异常处理，最后完成了测试。"
        result = json.loads(evaluate_candidate_answer.invoke({
            "session_id": "technical-stage", "answer": answer,
        }))
        self.assertEqual(result["evaluationMode"], "TECHNICAL")
        self.assertIn("没有命中岗位技术关键词", result["gaps"])


if __name__ == "__main__":
    unittest.main()
