import json
import unittest

from app.tools import EXPERIENCE_PATTERNS, search_real_interview_patterns, set_context


class ExperiencePatternTests(unittest.TestCase):
    def test_patterns_are_loaded(self):
        self.assertGreaterEqual(len(EXPERIENCE_PATTERNS), 50)
        self.assertTrue(any("工作流" in json.dumps(item, ensure_ascii=False) for item in EXPERIENCE_PATTERNS))

    def test_java_question_retrieval(self):
        set_context("test-java", {
            "job": {
                "title": "Java 后端开发",
                "skills": ["Java", "Spring", "MySQL", "Redis"],
                "jobRequirements": "熟悉 Java 基础、线程池、Spring 和数据库",
            },
            "resume": {
                "targetRole": "Java 后端开发",
                "skills": ["Java", "Spring Boot", "MySQL"],
                "projects": [{"name": "校园项目", "techStack": "Spring Boot", "description": "负责接口开发"}],
            },
            "round": 1,
        })
        result = json.loads(search_real_interview_patterns.invoke({
            "session_id": "test-java", "query": "Java final finally 线程池 JVM", "stage": "基础概念", "limit": 4,
        }))
        self.assertTrue(result["available"])
        self.assertGreater(result["count"], 0)
        self.assertTrue(any("Java" in json.dumps(item, ensure_ascii=False) for item in result["items"]))
        self.assertNotIn("answer", result["items"][0])
        self.assertTrue(all(item.get("sourceFile") for item in result["items"]))


    def test_new_real_interview_batch_is_loaded(self):
        self.assertGreaterEqual(len(EXPERIENCE_PATTERNS), 400)
        source_files = {item.get("sourceFile") for item in EXPERIENCE_PATTERNS}
        self.assertIn("实战面试_2年经验_面试官很中意.txt", source_files)
        self.assertIn("5年Java高级开发_字节社招一面.txt", source_files)

    def test_new_project_and_followup_patterns_are_retrievable(self):
        set_context("test-new-batch", {
            "job": {"title": "Java 后端开发", "skills": ["Java", "Spring", "MySQL"]},
            "resume": {"projects": [{"name": "电商商城", "description": "搜索、秒杀和订单模块"}]},
            "round": 2,
        })
        result = json.loads(search_real_interview_patterns.invoke({
            "session_id": "test-new-batch", "query": "Solr 搜索索引 商品 秒杀 项目", "stage": "项目实现", "limit": 5,
        }))
        self.assertTrue(result["available"])
        self.assertGreater(result["count"], 0)
        self.assertTrue(any("搜索" in json.dumps(item, ensure_ascii=False) or "项目" in json.dumps(item, ensure_ascii=False) for item in result["items"]))

    def test_project_follow_up_patterns(self):
        set_context("test-project", {
            "job": {"title": "Java 后端开发", "skills": ["Java", "Spring Boot", "MySQL"]},
            "resume": {"projects": [{"name": "工作流系统", "description": "节点、数据库表、审批任务"}]},
            "round": 2,
        })
        result = json.loads(search_real_interview_patterns.invoke({
            "session_id": "test-project", "query": "工作流 节点 数据库表 审批任务", "stage": "项目实现", "limit": 5,
        }))
        self.assertGreater(result["count"], 0)
        joined = json.dumps(result["items"], ensure_ascii=False)
        self.assertTrue("工作流" in joined or "数据库表" in joined)


if __name__ == "__main__":
    unittest.main()
