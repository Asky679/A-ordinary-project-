"""岗位匹配 Agent：按每个岗位自身的任职要求评估匹配度。

与旧版的区别
------------
旧版用固定的六维权重（技能 35 / 方向 20 / ...）算分，Agent 没有参与。
新版由 LLM 阅读岗位的真实要求，判断「这个岗位到底看重什么」，再对照简历打分：
  * 岗位写「精通 Redis」→ Redis 就是这个岗位的硬指标
  * 岗位写「了解 K8s 优先」→ K8s 只是加分项
分数与分项都可追溯到具体的一条岗位要求。

最终分由 Java 侧按 `Agent 0.7 + 规则 0.3` 融合，规则分作为兜底，避免模型抽风。
"""
from __future__ import annotations

import json
import os
from typing import Any, TypedDict

from langchain_core.messages import HumanMessage, SystemMessage
from langchain_openai import ChatOpenAI
from langgraph.graph import END, StateGraph

from .config import settings

SYSTEM_PROMPT = """你是岗位匹配评估 Agent。给定一位候选人的简历和一组岗位，请逐个评估匹配度。

评分规则：
1. 综合岗位的 jobRequirements、jobDescription、skills，以及岗位标题、行业、公司规模，理解这个岗位真正看重什么。
   - 公司与行业背景只能用于解释岗位语境，不能凭空推断公司文化、团队情况或未给出的要求。
2. 区分「硬性要求」和「加分项」：
   - 出现「必须 / 精通 / 熟练掌握 / 要求」→ 硬性
   - 出现「优先 / 了解 / 加分 / 有…经验者优先」→ 加分
3. 逐条对照简历：
   - 项目或实习中有对应实践 → "满足"
   - 只在技能栏出现、没有项目证据 → "部分满足"
   - 完全没有 → "不满足"
4. 按该岗位自身的要求分布给分（0-100），不要套固定权重：
   岗位 70% 的要求集中在 Java 并发，Java 并发就该占主要权重。
5. 分数必须可解释，每一条 breakdown 要写清 requirement / importance / status / evidence。
6. 绝对不能编造简历里没有的经历、技术或数字。
7. 输入中的每一个 jobId 都必须返回一条结果，不能遗漏。
8. 只返回合法 JSON，不要 Markdown，不要解释。

返回格式：
{
  "items": [
    {
      "jobId": 123,
      "score": 78,
      "verdict": "可以投递 / 需要补强 / 不建议投递",
      "reason": "一句话说明主要优势与主要缺口",
      "breakdown": [
        {"requirement": "精通 Java", "importance": "硬性", "status": "部分满足",
         "evidence": "技能栏有 Java，项目中用 Spring Boot"}
      ]
    }
  ]
}"""


class MatchState(TypedDict, total=False):
    resume: dict
    jobs: list
    results: list
    error: str


def _extract_json(text: str) -> dict | None:
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        return json.loads(text[start:end + 1])
    except json.JSONDecodeError:
        return None


class MatchAgent:
    def __init__(self) -> None:
        self.llm_enabled = settings.llm_enabled
        self.model = None
        if self.llm_enabled:
            self.model = ChatOpenAI(
                model=settings.openai_model,
                api_key=settings.openai_api_key,
                base_url=settings.openai_base_url,
                temperature=0,
            )
        self.graph = self._build()

    # ---------------------------------------------------------------- nodes
    def _node_score(self, state: MatchState) -> dict:
        if self.model is None:
            return {"results": [], "error": "LLM not configured"}
        jobs = state.get("jobs") or []
        if not jobs:
            return {"results": []}

        # 单次最多评估 20 个岗位，控制上下文长度
        batch = jobs[:20]
        payload = {
            "resume": state.get("resume") or {},
            "jobs": batch,
        }
        try:
            message = self.model.invoke([
                SystemMessage(content=SYSTEM_PROMPT),
                HumanMessage(content=json.dumps(payload, ensure_ascii=False)),
            ])
            content = message.content
            if isinstance(content, list):
                content = " ".join(str(part) for part in content)
            parsed = _extract_json(str(content)) or {}
            return {"results": parsed.get("items") or []}
        except Exception as exc:  # noqa: BLE001
            return {"results": [], "error": str(exc)}

    def _node_validate(self, state: MatchState) -> dict:
        allowed = {str(job.get("jobId")) for job in (state.get("jobs") or [])}
        cleaned = []
        for item in state.get("results") or []:
            job_id = str(item.get("jobId"))
            if job_id not in allowed:
                continue
            try:
                score = max(0, min(100, int(round(float(item.get("score", 0))))))
            except (TypeError, ValueError):
                score = 0
            breakdown = []
            for row in (item.get("breakdown") or [])[:8]:
                breakdown.append({
                    "requirement": str(row.get("requirement") or "")[:80],
                    "importance": str(row.get("importance") or "加分"),
                    "status": str(row.get("status") or "未知"),
                    "evidence": str(row.get("evidence") or "")[:120],
                })
            cleaned.append({
                "jobId": item.get("jobId"),
                "score": score,
                "verdict": str(item.get("verdict") or "")[:40],
                "reason": str(item.get("reason") or "")[:200],
                "breakdown": breakdown,
            })
        return {"results": cleaned}

    def _build(self):
        graph = StateGraph(MatchState)
        graph.add_node("score", self._node_score)
        graph.add_node("validate", self._node_validate)
        graph.set_entry_point("score")
        graph.add_edge("score", "validate")
        graph.add_edge("validate", END)
        return graph.compile()

    # ---------------------------------------------------------------- entry
    def status(self) -> dict[str, Any]:
        return {
            "mode": "LANGGRAPH_LLM" if self.llm_enabled else "LANGGRAPH_LOCAL_FALLBACK",
            "model": settings.openai_model if self.llm_enabled else "none",
            "maxJobsPerCall": 20,
        }

    def rerank(self, resume: dict, jobs: list) -> dict:
        if self.model is None:
            return {"available": False, "reason": "LLM not configured", "items": []}
        final = self.graph.invoke({"resume": resume or {}, "jobs": jobs or []})
        items = final.get("results") or []
        return {
            "available": bool(items),
            "items": items,
            "error": final.get("error"),
        }
