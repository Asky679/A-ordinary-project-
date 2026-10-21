"""简历诊断 Agent：由 LLM 自主决定调用哪些工具完成诊断。

设计原则
--------
分数永远来自 Java 侧的确定性规则（`get_rule_diagnosis`），Agent 不能修改分数；
它负责的是「查什么证据」和「怎么表达」：
  * 要不要看相似岗位的市场要求
  * 要不要检索面试题库，找出这些缺口真实的问法
  * 要不要生成学习计划
LLM 只组织建议，不改分、不编造经历。
"""
from __future__ import annotations

import json
import os
from typing import Any

import requests
from langchain_core.messages import HumanMessage
from langchain_core.tools import tool
from langchain_openai import ChatOpenAI
from langgraph.prebuilt import create_react_agent

from .config import settings

API_BASE = os.getenv("CAREERNAV_API_BASE", "http://127.0.0.1:8080/api").rstrip("/")
EMBEDDING_URL = os.getenv("INTERVIEW_EMBEDDING_URL", "http://127.0.0.1:5002/v1/embeddings")
QDRANT_URL = os.getenv("INTERVIEW_QDRANT_URL", "http://127.0.0.1:6333")
QUESTION_COLLECTION = os.getenv("INTERVIEW_COLLECTION", "careernav_interview_questions")
TIMEOUT = 90

SYSTEM_PROMPT = """你是 CareerNav 的简历诊断 Agent。你不是固定模板生成器，而是可以自主选择工具的诊断顾问。

工作方式：
1. 先调用 read_resume 了解候选人。
2. 必须调用 get_rule_diagnosis 拿到规则分——**这些分数是系统算出来的，你不能修改、不能编造**。
3. 根据规则诊断里的失分项和缺失关键词，自主决定还要调用哪些工具：
   - search_similar_jobs：看看市场上同类岗位还要求什么
   - search_interview_questions：检索题库，看这些缺口在面试里会怎么被问
   - generate_learning_plan：生成分阶段学习计划
   不必每次都调用全部工具，按简历实际情况判断。
4. 最后输出诊断建议。建议必须绑定具体证据（哪个维度失分、哪个岗位要求、哪道面试题），
   不要写"提升综合能力"这类空话。
5. 绝对不要编造候选人的经历、项目或数字。

只返回合法 JSON，不要 Markdown：
{
  "summary": "150 字以内的诊断结论",
  "strengths": ["1-3 条优势"],
  "suggestions": [
    {"priority": "P0/P1/P2", "category": "分类", "issue": "具体问题",
     "action": "可执行的动作", "example": "可照抄的示例句"}
  ],
  "learning_focus": ["最该优先补的 2-3 项"],
  "reasoning_summary": "简要说明你为什么选择调用这些工具，不要暴露完整思维链"
}"""


def _get(path: str, params: dict | None = None) -> Any:
    response = requests.get(API_BASE + path, params=params or {}, timeout=TIMEOUT)
    response.raise_for_status()
    body = response.json()
    if isinstance(body, dict) and body.get("success") is False:
        raise RuntimeError(str(body.get("message") or "接口返回失败"))
    return body.get("data") if isinstance(body, dict) and "data" in body else body


def _post(path: str, payload: dict) -> Any:
    response = requests.post(API_BASE + path, json=payload, timeout=TIMEOUT)
    response.raise_for_status()
    body = response.json()
    if isinstance(body, dict) and body.get("success") is False:
        raise RuntimeError(str(body.get("message") or "接口返回失败"))
    return body.get("data") if isinstance(body, dict) and "data" in body else body


def _json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False)


def _brief_job(job: dict) -> dict:
    return {
        "jobId": job.get("id"),
        "title": job.get("title"),
        "company": job.get("company"),
        "city": job.get("city"),
        "salary": job.get("salary"),
        "category": job.get("category"),
        "skills": job.get("skills") or [],
    }


@tool
def read_resume(resume_id: int) -> str:
    """读取简历：目标岗位、学历、技能、项目、实习经历、自我评价。"""
    try:
        data = _get("/resumes/" + str(int(resume_id)))
        return _json({"ok": True, "data": {
            "resumeId": data.get("id"), "targetRole": data.get("targetRole"),
            "city": data.get("city"), "education": data.get("education"),
            "school": data.get("school"), "major": data.get("major"),
            "graduationYear": data.get("graduationYear"), "skills": data.get("skills") or [],
            "projects": data.get("projects") or [], "experiences": data.get("experiences") or [],
            "selfEvaluation": data.get("selfEvaluation"),
        }})
    except Exception as exc:
        return _json({"ok": False, "error": str(exc)})


@tool
def get_rule_diagnosis(resume_id: int, job_id: int = 0) -> str:
    """获取确定性规则诊断结果：六维得分、综合分、缺失关键词、规则建议、学习计划。

    这些分数由系统计算，Agent 必须原样引用，不能修改。
    """
    try:
        payload = {"resumeId": int(resume_id)}
        if job_id:
            payload["jobId"] = int(job_id)
        data = _post("/diagnoses/rule", payload)
        return _json({"ok": True, "data": {
            "targetRole": data.get("targetRole"), "overallScore": data.get("overallScore"),
            "resumeQualityScore": data.get("resumeQualityScore"),
            "jobMatchScore": data.get("jobMatchScore"), "jobMatchLevel": data.get("jobMatchLevel"),
            "level": data.get("level"), "summary": data.get("summary"),
            "dimensions": data.get("dimensions") or [],
            "matchedKeywords": data.get("matchedKeywords") or [],
            "missingKeywords": data.get("missingKeywords") or [],
            "suggestions": data.get("suggestions") or [],
            "learningPlan": data.get("learningPlan"),
        }})
    except Exception as exc:
        return _json({"ok": False, "error": str(exc)})


@tool
def search_similar_jobs(resume_id: int, limit: int = 5) -> str:
    """按简历匹配度检索市场岗位，用来看同类岗位还要求哪些技能。"""
    try:
        rows = _get("/jobs/recommend", {"resumeId": int(resume_id), "limit": max(1, min(limit, 10))})
        return _json({"ok": True, "data": [
            {**_brief_job(row.get("job") or {}), "matchScore": row.get("matchScore"),
             "missingSkills": row.get("missingSkills") or []}
            for row in (rows or [])
        ]})
    except Exception as exc:
        return _json({"ok": False, "error": str(exc)})


@tool
def search_interview_questions(query: str, limit: int = 4) -> str:
    """从面试题库检索与薄弱点相关的真实题目，用于把建议落到面试场景。"""
    try:
        vector = requests.post(EMBEDDING_URL, json={"input": query}, timeout=30).json()["data"][0]["embedding"]
        result = requests.post(
            QDRANT_URL + "/collections/" + QUESTION_COLLECTION + "/points/search",
            json={"vector": vector, "limit": max(1, min(limit, 8)), "with_payload": True},
            timeout=30,
        ).json()["result"]
        return _json({"ok": True, "data": [
            {"question": hit["payload"].get("question"), "stage": hit["payload"].get("stage"),
             "skills": hit["payload"].get("skills"), "source": hit["payload"].get("source")}
            for hit in result
        ]})
    except Exception as exc:
        return _json({"ok": False, "error": str(exc)})


@tool
def generate_learning_plan(resume_id: int, role: str = "") -> str:
    """生成分阶段学习计划（阶段目标、主题、交付物）。"""
    try:
        data = _post("/learning-paths", {"resumeId": int(resume_id), "targetRole": role or None})
        return _json({"ok": True, "data": {
            "readiness": data.get("readiness"), "skillGaps": data.get("skillGaps") or [],
            "modules": data.get("modules") or [], "summary": data.get("summary"),
        }})
    except Exception as exc:
        return _json({"ok": False, "error": str(exc)})


DIAGNOSIS_TOOLS = [read_resume, get_rule_diagnosis, search_similar_jobs,
                   search_interview_questions, generate_learning_plan]


def _extract_json(text: str) -> dict | None:
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        return json.loads(text[start:end + 1])
    except json.JSONDecodeError:
        return None


class DiagnosisAgent:
    def __init__(self) -> None:
        self.llm_enabled = settings.llm_enabled
        self.agent = None
        if self.llm_enabled:
            model = ChatOpenAI(
                model=settings.openai_model,
                api_key=settings.openai_api_key,
                base_url=settings.openai_base_url,
                temperature=0.2,
            )
            self.agent = create_react_agent(model, DIAGNOSIS_TOOLS, prompt=SYSTEM_PROMPT)

    def status(self) -> dict:
        return {
            "mode": "LANGGRAPH_LLM" if self.llm_enabled else "LANGGRAPH_LOCAL_FALLBACK",
            "tools": [tool.name for tool in DIAGNOSIS_TOOLS],
        }

    def analyze(self, resume_id: int, job_id: int | None = None) -> dict:
        if self.agent is None:
            return {"available": False, "reason": "LLM not configured"}

        prompt = (
            f"请诊断简历 {resume_id}"
            + (f"，目标岗位 ID 为 {job_id}。" if job_id else "，暂未指定目标岗位。")
            + " 先读取简历和规则分，再自主决定调用哪些工具补充证据，最后按要求返回 JSON。"
        )
        try:
            result = self.agent.invoke({"messages": [HumanMessage(content=prompt)]},
                                       config={"configurable": {"thread_id": f"diagnosis-{resume_id}-{job_id or 0}"}})
        except Exception as exc:
            return {"available": False, "reason": str(exc)}

        messages = result.get("messages", [])
        tool_calls = [call.get("name") for message in messages
                      for call in (getattr(message, "tool_calls", None) or [])
                      if isinstance(call, dict) and call.get("name")]
        content = str(getattr(messages[-1], "content", "")) if messages else ""
        parsed = _extract_json(content) or {}
        return {
            "available": True,
            "summary": parsed.get("summary"),
            "strengths": parsed.get("strengths") or [],
            "suggestions": parsed.get("suggestions") or [],
            "learningFocus": parsed.get("learning_focus") or [],
            "reasoningSummary": parsed.get("reasoning_summary"),
            "toolCalls": tool_calls,
        }
