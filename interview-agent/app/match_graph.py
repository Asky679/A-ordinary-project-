"""岗位匹配 Agent：识别岗位要求、生成受限权重并给出逐条证据。

Agent 不直接输出匹配分数。它根据岗位真实要求选择六维权重，Java 后端再用
确定性规则因子和硬性条件计算最终岗位适配度。这样既保留 Agent 的岗位理解能力，
又保证同一简历与岗位可以得到可复现、可解释的分数。
"""
from __future__ import annotations

import json
import os
from typing import Any, TypedDict

from langchain_core.messages import HumanMessage, SystemMessage
from langgraph.graph import END, StateGraph

from .config import settings
from .llm import create_chat_model

SYSTEM_PROMPT = """你是岗位匹配权重规划 Agent。给定一位候选人的简历和一组岗位，请逐个岗位分析要求，并给出受限的六维权重、判断理由和逐条证据。

你不能直接给候选人打总分，也不能返回 score 字段。最终分由后端确定性规则引擎计算。

工作步骤：
1. 综合岗位的 jobRequirements、jobDescription、skills，以及岗位标题、行业和公司规模，理解岗位真正的侧重点。
   - 公司和行业只能用于解释岗位语境，不能凭空推断公司文化、团队情况或未给出的要求。
2. 区分「硬性要求」和「加分项」：
   - 出现「必须 / 精通 / 熟练掌握 / 要求」→ 硬性
   - 出现「优先 / 了解 / 加分 / 有经验者优先」→ 加分
3. 逐条对照简历：
   - 项目或实习中有对应实践 → "满足"
   - 只在技能栏出现、没有项目证据 → "部分满足"
   - 完全没有 → "不满足"
   - interviewSkillEvidence 是系统面试形成的技能证据，优先级高于技能自述和项目描述：
     * 面试评分 < 45 或“待验证” → 不得标记为满足，最高为“不满足”
     * 面试评分 45～59 或“初级” → 最高为“部分满足”
     * 面试评分 60～79 或“中级” → 最高为“部分满足”
     * 面试评分 ≥ 80 或“高级” → 可标记为“满足”
     * bktMastery 是跨轮知识点掌握概率；guessRisk 是疑似猜测风险；consistency 是同知识点多轮一致性。
     * 若 roundsCompleted ≥ 2 且（bktMastery < 0.40 或 bktConfidence < 0.50 或 guessRisk ≥ 0.60 或 consistency < 0.50），即使测试分较高，也最高标记为“部分满足”，并在 evidence 中说明需要复测。
   - 简历写了某项技能但面试未通过时，不能因为项目文字提到该技术就忽略测试结果。
4. 只输出六维权重，不输出总分。权重必须是整数，并尽量让六项之和等于 100：
   - skills：20～50
   - role：10～35
   - experience：0～30
   - education：0～20
   - city：0～15
   - evidence：0～25
   岗位越强调技术栈、核心技能或项目证据，对应权重越高；校招岗位应降低 experience；高级岗位可提高 experience 和 evidence。
5. 权重必须体现岗位要求，不能为了让候选人分数更高或更低而随意调整。
6. 硬性条件不能通过降低权重绕过。后端会独立执行技能覆盖率、岗位方向、经验、学历等硬性上限。
7. 每条 breakdown 写清 requirement / importance / status / evidence，最多 8 条。
8. 绝对不能编造简历里没有的经历、技术或数字。
9. 输入中的每一个 jobId 都必须返回一条结果，不能遗漏。
10. 只返回合法 JSON，不要 Markdown，不要解释。

返回格式：
{
  "items": [
    {
      "jobId": 123,
      "weights": {"skills": 40, "role": 20, "experience": 10, "education": 5, "city": 5, "evidence": 20},
      "reason": "一句话说明该岗位为什么采用这组权重，以及候选人的主要优势与缺口",
      "breakdown": [
        {"requirement": "精通 Java", "importance": "硬性", "status": "部分满足",
         "evidence": "技能栏有 Java，项目中用 Spring Boot"}
      ]
    }
  ]
}"""


WEIGHT_KEYS = ("skills", "role", "experience", "education", "city", "evidence")
DEFAULT_WEIGHTS = {
    "skills": 35,
    "role": 20,
    "experience": 15,
    "education": 10,
    "city": 5,
    "evidence": 15,
}
WEIGHT_BOUNDS = {
    "skills": (20, 50),
    "role": (10, 35),
    "experience": (0, 30),
    "education": (0, 20),
    "city": (0, 15),
    "evidence": (0, 25),
}


def _normalize_weights(raw: Any) -> dict[str, int]:
    """Clamp and rebalance Agent weights so Java always receives six valid rel weights."""
    if not isinstance(raw, dict) or any(key not in raw for key in WEIGHT_KEYS):
        return dict(DEFAULT_WEIGHTS)
    parsed: dict[str, int] = {}
    for key in WEIGHT_KEYS:
        try:
            parsed[key] = int(round(float(raw.get(key, 0))))
        except (TypeError, ValueError):
            parsed[key] = 0
    if sum(max(0, value) for value in parsed.values()) <= 0:
        return dict(DEFAULT_WEIGHTS)
    values: dict[str, int] = {}
    for key in WEIGHT_KEYS:
        low, high = WEIGHT_BOUNDS[key]
        values[key] = max(low, min(high, parsed[key]))
    total = sum(values.values())

    scaled = {key: values[key] * 100.0 / total for key in WEIGHT_KEYS}
    result = {key: int(round(scaled[key])) for key in WEIGHT_KEYS}
    for key in WEIGHT_KEYS:
        low, high = WEIGHT_BOUNDS[key]
        result[key] = max(low, min(high, result[key]))

    # Keep the display and Java-side computation stable at exactly 100.
    diff = 100 - sum(result.values())
    ordered = sorted(WEIGHT_KEYS, key=lambda key: scaled[key], reverse=diff > 0)
    guard = 0
    while diff != 0 and guard < 500:
        moved = False
        for key in ordered:
            low, high = WEIGHT_BOUNDS[key]
            if diff > 0 and result[key] < high:
                result[key] += 1
                diff -= 1
                moved = True
            elif diff < 0 and result[key] > low:
                result[key] -= 1
                diff += 1
                moved = True
            if diff == 0:
                break
        if not moved:
            break
        guard += 1
    return result


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
            self.model = create_chat_model(temperature=0)
        self.graph = self._build()

    # ---------------------------------------------------------------- nodes
    def _score_batch(self, resume: dict, jobs: list[dict]) -> tuple[list[dict], str | None]:
        """Score one small batch and return the model's raw items plus an error."""
        payload = {"resume": resume, "jobs": jobs}
        try:
            message = self.model.invoke([
                SystemMessage(content=SYSTEM_PROMPT),
                HumanMessage(content=json.dumps(payload, ensure_ascii=False)),
            ])
            content = message.content
            if isinstance(content, list):
                content = " ".join(str(part) for part in content)
            parsed = _extract_json(str(content)) or {}
            items = parsed.get("items")
            if not isinstance(items, list):
                return [], "LLM returned no valid items"
            return [item for item in items if isinstance(item, dict)], None
        except Exception as exc:  # noqa: BLE001
            return [], str(exc)

    def _node_score(self, state: MatchState) -> dict:
        if self.model is None:
            return {"results": [], "error": "LLM not configured"}
        jobs = state.get("jobs") or []
        if not jobs:
            return {"results": []}

        # Score at most 20 jobs in the first pass to control context length.
        batch = jobs[:20]
        resume = state.get("resume") or {}
        results, error = self._score_batch(resume, batch)

        # A large response can be truncated or omit jobs. Retry only the missing
        # IDs in small batches so a partial model response cannot make the whole
        # Agent look unavailable.
        expected_ids = {str(job.get("jobId")) for job in batch}
        seen_ids = {
            str(item.get("jobId")) for item in results
            if str(item.get("jobId")) in expected_ids
        }
        missing = [job for job in batch if str(job.get("jobId")) not in seen_ids]
        for start in range(0, len(missing), 5):
            chunk = missing[start:start + 5]
            chunk_ids = {str(job.get("jobId")) for job in chunk}
            retried, retry_error = self._score_batch(resume, chunk)
            accepted = [
                item for item in retried
                if str(item.get("jobId")) in chunk_ids
            ]
            results.extend(accepted)
            seen_ids.update(str(item.get("jobId")) for item in accepted)
            if retry_error and not error:
                error = retry_error

        valid_results = [
            item for item in results
            if str(item.get("jobId")) in expected_ids
        ]
        return {
            "results": valid_results,
            "error": None if valid_results else error,
        }

    def _node_validate(self, state: MatchState) -> dict:
        allowed = {str(job.get("jobId")) for job in (state.get("jobs") or [])}
        cleaned = []
        for item in state.get("results") or []:
            job_id = str(item.get("jobId"))
            if job_id not in allowed:
                continue
            weights = _normalize_weights(item.get("weights"))
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
                "weights": weights,
                "reason": str(item.get("reason") or "")[:240],
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
            "mode": "LANGGRAPH_LLM_WEIGHTED_RULE" if self.llm_enabled else "LANGGRAPH_LOCAL_FALLBACK",
            "model": settings.openai_model if self.llm_enabled else "none",
            "maxJobsPerCall": 20,
            "llmDirectScore": False,
            "weightKeys": list(WEIGHT_KEYS),
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
