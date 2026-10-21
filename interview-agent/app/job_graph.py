"""Resume -> job recommendation agent, built as a LangGraph StateGraph.

Why a StateGraph instead of a free ReAct agent
----------------------------------------------
A diagnosis run should be reproducible and explainable. The graph fixes the
shape of the work (understand -> recall -> rank -> reflect -> report) while the
LLM still acts autonomously inside two nodes:

* ``extract_profile`` - turns a resume into a job-seeking profile and 2-3 search
  intents. This is where free-form reasoning genuinely helps: "会 Java/Python"
  has to become "Python 后端开发" to recall the right postings.
* ``reflect`` - looks at what came back and decides whether to search again with
  different wording.

Everything numeric comes from the weighted algorithm in the Spring Boot service,
so a score can always be traced back to its six components.
"""
from __future__ import annotations

import json
import re
from collections import Counter
from typing import Any, TypedDict

from langchain_core.messages import HumanMessage, SystemMessage
from langchain_openai import ChatOpenAI
from langgraph.graph import END, StateGraph

from .config import settings
from .job_tools import (
    filter_jobs,
    finalize_recommendation,
    recommend_jobs,
    search_jobs_hybrid,
    read_resume,
)

STRONG_MATCH_SCORE = 70
MAX_ATTEMPTS = 2
SEMANTIC_BONUS = 5
CANDIDATES_PER_QUERY = 15
FINAL_LIMIT = 12

PROFILE_SYSTEM = """你是计算机专业的职业规划助手。
你的任务是把一份简历翻译成「求职画像」和 2 到 3 条岗位检索语句。

规则：
1. 检索语句要像招聘网站的关键词，写「岗位方向 + 核心技能」，例如「Python 后端 Django MySQL」。
2. 不要直接把整份简历当检索语句，也不要编造简历里没有的技术。
3. 只能返回合法 JSON，不要 Markdown，不要任何解释。

返回格式：
{
  "profile": {
    "targetRole": "目标岗位方向",
    "level": "实习/初级/中级",
    "cities": ["期望城市"],
    "coreSkills": ["简历里真实出现的核心技能，最多 8 个"],
    "strengths": ["从项目里能看出的 1-2 个优势"]
  },
  "queries": ["检索语句 1", "检索语句 2"]
}"""

REFLECT_SYSTEM = """你是岗位检索质量评估助手。
你会看到候选人的求职画像，以及本轮检索到的岗位及其匹配分。
判断这批岗位够不够好：数量是否足够、最高分是否突出、方向是否对口。

只能返回合法 JSON：
{"retry": true/false, "reason": "一句话说明", "queries": ["如果重试，给出与上一轮不同的检索语句"]}

需要重试的信号：高分岗位少于 3 个、岗位方向明显跑偏、结果里有大量非技术岗。
如果重试，检索语句要比上一轮更宽（去掉城市限制、换同义技术词、放宽经验要求）。"""

SUMMARY_SYSTEM = """你是职业发展顾问，正在为一位计算机专业学生写岗位推荐结论。
你会拿到算法算出的匹配分、维度明细、命中技能和缺失技能。

要求：
1. 只能使用给定的事实，绝对不要编造岗位、公司、薪资或技能。
2. 用 150 到 250 字，先给结论，再指出最值得优先补的 1 到 2 项技能，最后给一句行动建议。
3. 不要使用 Markdown，不要罗列 JSON。"""


# Off-target detection is deliberately conservative: a posting is only dropped
# when its title names a clearly different occupation. Words like "开发" are NOT
# treated as proof of an IT role, because "新能源开发岗" also contains it.
OFF_TARGET_TITLE_HINTS = (
    "新能源", "光伏", "储能", "电力", "电气", "化工", "机械", "土木", "建筑", "医药", "生物", "材料",
    "设计", "平面", "视觉", "美工", "室内",
    "销售", "运营", "客服", "市场", "商务", "行政", "人事", "财务", "会计", "招聘",
    "讲师", "老师", "培训", "助教", "教研",
    "物流", "仓储", "司机", "安保", "餐饮",
)

# If one of these appears next to an off-target word the posting is still an IT
# role - e.g. "新能源 后端 开发工程师" is a genuine backend job.
STRONG_IT_MARKERS = (
    "后端", "前端", "全栈", "服务端", "算法工程师", "数据", "运维", "测试工程师",
    "架构", "开发工程师", "研发工程师", "软件开发", "程序员",
)


def _direction_keywords(profile: dict) -> list:
    """The candidate's own direction words (role + skills), used as a second opinion."""
    tokens = []
    role = str(profile.get("targetRole") or "")
    for token in re.split(r"[\s/,、|与和]+", role):
        token = token.strip().lower()
        if len(token) >= 2:
            tokens.append(token)
    for skill in profile.get("coreSkills") or []:
        token = str(skill).strip().lower()
        if token:
            tokens.append(token)
    seen, ordered = set(), []
    for token in tokens:
        if token and token not in seen:
            seen.add(token)
            ordered.append(token)
    return ordered


def _direction_verdict(job: dict, keywords: list) -> tuple:
    """Return (is_on_target, reason) for one job."""
    title = str(job.get("title") or "").lower()
    skills = " ".join(str(skill).lower() for skill in (job.get("skills") or []))
    haystack = title + " " + skills
    for hint in OFF_TARGET_TITLE_HINTS:
        if hint not in title:
            continue
        strong = [marker for marker in STRONG_IT_MARKERS if marker in title]
        specific = [token for token in keywords if token and token in haystack]
        if strong and specific:
            return True, "标题含「" + hint + "」，但同时命中 " + "、".join(strong[:2]) + "，判定为技术岗"
        return False, "标题含「" + hint + "」且未出现技术岗特征词"
    return True, "未发现方向偏离"


class JobAgentState(TypedDict, total=False):
    resume_id: int
    city: str
    excluded_cities: str
    profile: dict
    queries: list
    candidates: dict
    ranked: list
    attempt: int
    retry: bool
    gaps: list
    report: dict
    trace: list


def _extract_json(text: str) -> dict | None:
    start = text.find("{")
    end = text.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        return json.loads(text[start:end + 1])
    except json.JSONDecodeError:
        return None


def _load(tool_obj, payload: dict) -> dict:
    """Invoke a tool and unwrap its {"ok": ...} envelope."""
    try:
        raw = tool_obj.invoke(payload)
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "error": type(exc).__name__ + ": " + str(exc)}
    if isinstance(raw, dict):
        return raw
    try:
        return json.loads(raw)
    except Exception:  # noqa: BLE001
        return {"ok": False, "error": "工具返回了非 JSON 内容"}


class JobRecommendationAgent:
    def __init__(self) -> None:
        self.llm_enabled = settings.llm_enabled
        self.model = None
        if self.llm_enabled:
            self.model = ChatOpenAI(
                model=settings.openai_model,
                api_key=settings.openai_api_key,
                base_url=settings.openai_base_url,
                temperature=0.2,
            )
        self.graph = self._build_graph()

    # ------------------------------------------------------------------ llm
    def _ask_json(self, system: str, human: str) -> dict | None:
        if self.model is None:
            return None
        try:
            message = self.model.invoke([
                SystemMessage(content=system),
                HumanMessage(content=human),
            ])
            content = message.content
            if isinstance(content, list):
                content = " ".join(str(part) for part in content)
            return _extract_json(str(content))
        except Exception:  # noqa: BLE001 - fall back to the deterministic path
            return None

    # ---------------------------------------------------------------- nodes
    def _node_extract_profile(self, state: JobAgentState) -> dict:
        envelope = _load(read_resume, {"resume_id": state["resume_id"]})
        resume = envelope.get("data") or {}
        profile, queries = self._profile_and_queries(resume, state)
        trace = list(state.get("trace") or []) + [
            "extract_profile(resume=" + str(resume.get("resumeId")) + ", queries=" + str(len(queries)) + ")"
        ]
        return {"profile": profile, "queries": queries, "attempt": 0,
                "retry": False, "candidates": {}, "ranked": [], "trace": trace}

    def _profile_and_queries(self, resume: dict, state: JobAgentState) -> tuple[dict, list]:
        target = str(resume.get("targetRole") or "").strip()
        skills = [str(skill) for skill in (resume.get("skills") or []) if skill]
        answer = self._ask_json(
            PROFILE_SYSTEM,
            "简历如下（JSON）：\n" + json.dumps(resume, ensure_ascii=False)[:4000]
            + "\n\n期望城市：" + (state.get("city") or resume.get("city") or "不限"),
        )
        if isinstance(answer, dict) and answer.get("queries"):
            profile = answer.get("profile") or {}
            queries = [str(item).strip() for item in answer["queries"] if str(item).strip()][:3]
            if queries:
                return profile, queries
        # deterministic fallback: no model, or the model returned junk
        core = skills[:4]
        profile = {
            "targetRole": target or "后端开发",
            "level": "实习",
            "cities": [state.get("city") or resume.get("city") or "不限"],
            "coreSkills": skills[:8],
            "strengths": [],
        }
        queries = [" ".join(part for part in [target or "后端开发", *core[:3]] if part)]
        return profile, queries

    def _node_recall(self, state: JobAgentState) -> dict:
        resume_id = state["resume_id"]
        city = state.get("city") or ""
        candidates = dict(state.get("candidates") or {})
        calls = []
        for query in (state.get("queries") or [])[:3]:
            envelope = _load(search_jobs_hybrid, {
                "resume_id": resume_id, "query": query, "city": city,
                "limit": CANDIDATES_PER_QUERY,
            })
            calls.append("search_jobs_hybrid(" + query + ")=" + str(len(envelope.get("data") or [])))
            for job in envelope.get("data") or []:
                if job.get("jobId") is not None:
                    candidates[str(job["jobId"])] = job
        # A city filter can starve the result set; widen once before giving up.
        if len(candidates) < 8:
            envelope = _load(filter_jobs, {
                "resume_id": resume_id,
                "keyword": str((state.get("profile") or {}).get("targetRole") or ""),
                "city": "",
                "limit": 20,
            })
            calls.append("filter_jobs(no-city)=" + str(len(envelope.get("data") or [])))
            for job in envelope.get("data") or []:
                if job.get("jobId") is not None:
                    candidates.setdefault(str(job["jobId"]), job)
        trace = list(state.get("trace") or [])
        trace.append("recall[ attempt=" + str(state.get("attempt", 0)) + " ] " + "; ".join(calls)
                     + " -> 候选 " + str(len(candidates)) + " 条")
        return {"candidates": candidates, "trace": trace}

    def _node_rank(self, state: JobAgentState) -> dict:
        candidates = state.get("candidates") or {}
        envelope = _load(recommend_jobs, {
            "resume_id": state["resume_id"],
            "city": state.get("city") or "",
            "limit": 20,
            "excluded_cities": state.get("excluded_cities") or "",
        })
        if not envelope.get("ok"):
            trace = list(state.get("trace") or []) + ["rank 失败：" + str(envelope.get("error"))]
            return {"ranked": [], "trace": trace}

        ranked = []
        for row in envelope.get("data") or []:
            job = row.get("job") or {}
            job_id = str(job.get("jobId"))
            score = int(row.get("matchScore") or 0)
            semantic_hit = job_id in candidates
            ranked.append({
                "job": job,
                "matchScore": score,
                "matchLevel": row.get("matchLevel"),
                "matchedSkills": row.get("matchedSkills") or [],
                "missingSkills": row.get("missingSkills") or [],
                "reasons": row.get("reasons") or [],
                "scoreBreakdown": row.get("scoreBreakdown") or [],
                "semanticHit": semantic_hit,
                "finalScore": min(100, score + (SEMANTIC_BONUS if semantic_hit else 0)),
                "rankingSource": "algorithm+semantic" if semantic_hit else "algorithm",
            })
        scored_ids = {str(item["job"].get("jobId")) for item in ranked}
        # semantic-only hits keep their place at the tail; they have no score yet
        for job_id, job in candidates.items():
            if job_id in scored_ids:
                continue
            ranked.append({
                "job": job, "matchScore": None, "matchLevel": "未评分",
                "matchedSkills": [], "missingSkills": [], "reasons": [],
                "scoreBreakdown": [], "semanticHit": True, "finalScore": 0,
                "rankingSource": "semantic-only",
            })
        ranked.sort(key=lambda item: (item["finalScore"], item["matchScore"] or 0), reverse=True)
        # The job table holds the same posting imported from several datasets,
        # so collapse identical (title, company, city) triples and keep the best.
        deduped, seen = [], set()
        for item in ranked:
            job = item["job"]
            key = (
                str(job.get("title") or "").strip().lower(),
                str(job.get("company") or "").strip().lower(),
                str(job.get("city") or "").strip().lower(),
            )
            if key in seen:
                continue
            seen.add(key)
            deduped.append(item)
        duplicates = len(ranked) - len(deduped)
        ranked = deduped

        # Direction filter: drop postings that clearly belong to another
        # occupation. If that would leave too few rows we keep them but flag
        # them, so the panel never ends up empty.
        keywords = _direction_keywords(state.get("profile") or {})
        kept, off_target = [], []
        for item in ranked:
            on_target, reason = _direction_verdict(item["job"], keywords)
            item["directionMatch"] = on_target
            item["directionNote"] = reason
            (kept if on_target else off_target).append(item)
        filtered = 0
        if off_target and len(kept) >= 3:
            ranked = kept
            filtered = len(off_target)
        else:
            ranked = kept + off_target
        top = ranked[:FINAL_LIMIT]
        strong = sum(1 for item in top if (item["matchScore"] or 0) >= STRONG_MATCH_SCORE)
        trace = list(state.get("trace") or []) + [
            "rank 精排 " + str(len(ranked)) + " 条（去重 " + str(duplicates) + " 条，方向过滤 "
            + str(filtered) + " 条），其中 >= " + str(STRONG_MATCH_SCORE)
            + " 分 " + str(strong) + " 条，语义命中 " + str(sum(1 for i in top if i["semanticHit"])) + " 条"
        ]
        return {"ranked": top, "trace": trace}

    def _node_reflect(self, state: JobAgentState) -> dict:
        ranked = state.get("ranked") or []
        attempt = int(state.get("attempt", 0))
        strong = [item for item in ranked if (item.get("matchScore") or 0) >= STRONG_MATCH_SCORE]
        if not ranked:
            trace = list(state.get("trace") or []) + ["reflect 无候选，进入报告"]
            return {"retry": False, "trace": trace}
        if attempt >= MAX_ATTEMPTS:
            trace = list(state.get("trace") or []) + ["reflect 已达最大重试次数，进入报告"]
            return {"retry": False, "trace": trace}

        answer = self._ask_json(
            REFLECT_SYSTEM,
            "求职画像：" + json.dumps(state.get("profile") or {}, ensure_ascii=False)
            + "\n上一轮检索语句：" + json.dumps(state.get("queries") or [], ensure_ascii=False)
            + "\n候选岗位（Top " + str(len(ranked)) + "）："
            + json.dumps([{
                "title": item["job"].get("title"),
                "matchScore": item.get("matchScore"),
                "skills": item["job"].get("skills"),
            } for item in ranked[:10]], ensure_ascii=False),
        )
        if isinstance(answer, dict) and "retry" in answer:
            retry = bool(answer.get("retry"))
            queries = [str(q).strip() for q in (answer.get("queries") or []) if str(q).strip()][:3]
            reason = str(answer.get("reason") or "")
        else:
            retry = len(strong) < 3
            queries = self._broaden_queries(state)
            reason = "规则兜底：高分岗位少于 3 条"
        if retry and not queries:
            queries = self._broaden_queries(state)
        trace = list(state.get("trace") or []) + [
            "reflect retry=" + str(retry) + " (" + reason + ")"
        ]
        return {"retry": retry, "queries": queries if retry else (state.get("queries") or []),
                "attempt": attempt + 1 if retry else attempt, "trace": trace}

    def _broaden_queries(self, state: JobAgentState) -> list:
        profile = state.get("profile") or {}
        role = str(profile.get("targetRole") or "后端开发")
        skills = [str(skill) for skill in (profile.get("coreSkills") or [])][:3]
        widened = [" ".join(part for part in [role, *skills] if part)]
        if skills:
            widened.append(" ".join(part for part in [skills[0], "开发", "初级"] if part))
        widened.append("后端开发 应届")
        previous = {str(item) for item in (state.get("queries") or [])}
        fresh = [item for item in widened if item not in previous]
        return fresh[:3] or widened[:3]

    def _node_gap_analysis(self, state: JobAgentState) -> dict:
        counter: Counter = Counter()
        for item in (state.get("ranked") or [])[:5]:
            for skill in item.get("missingSkills") or []:
                counter[str(skill)] += 1
        gaps = [{"skill": skill, "appearsIn": count} for skill, count in counter.most_common(8)]
        trace = list(state.get("trace") or []) + ["gap_analysis 缺口 " + str(len(gaps)) + " 项"]
        return {"gaps": gaps, "trace": trace}

    def _node_compose(self, state: JobAgentState) -> dict:
        ranked = state.get("ranked") or []
        top = ranked[:5]
        summary = self._write_summary(state, top)
        job_ids = [item["job"].get("jobId") for item in top if item["job"].get("jobId") is not None]
        validation = _load(finalize_recommendation, {
            "resume_id": state["resume_id"], "job_ids": job_ids, "summary": summary,
        })
        trace = list(state.get("trace") or []) + [
            "compose 推荐 " + str(len(job_ids)) + " 个岗位，防幻觉校验 "
            + ("通过" if validation.get("ok") else "未通过：" + str(validation.get("error")))
        ]
        report = {
            "resumeId": state["resume_id"],
            "profile": state.get("profile") or {},
            "queries": state.get("queries") or [],
            "attempts": int(state.get("attempt", 0)),
            "summary": summary,
            "recommendations": [{
                "jobId": item["job"].get("jobId"),
                "title": item["job"].get("title"),
                "company": item["job"].get("company"),
                "city": item["job"].get("city"),
                "salary": item["job"].get("salary"),
                "skills": item["job"].get("skills") or [],
                "contentOrigin": item["job"].get("contentOrigin"),
                "matchScore": item.get("matchScore"),
                "matchLevel": item.get("matchLevel"),
                "matchedSkills": item.get("matchedSkills") or [],
                "missingSkills": item.get("missingSkills") or [],
                "reasons": item.get("reasons") or [],
                "scoreBreakdown": item.get("scoreBreakdown") or [],
                "semanticHit": item.get("semanticHit"),
                "rankingSource": item.get("rankingSource"),
                "directionMatch": item.get("directionMatch"),
                "directionNote": item.get("directionNote"),
            } for item in ranked],
            "skillGaps": state.get("gaps") or [],
            "trace": trace,
            "mode": "LANGGRAPH_LLM" if self.llm_enabled else "LANGGRAPH_LOCAL_FALLBACK",
        }
        return {"report": report, "trace": trace}

    def _write_summary(self, state: JobAgentState, top: list) -> str:
        if top:
            best = top[0]
            header = (
                "最匹配的是「" + str(best["job"].get("title") or "") + "」（"
                + str(best["job"].get("company") or "") + "，"
                + str(best["job"].get("city") or "城市未知") + "），匹配度 "
                + str(best.get("matchScore")) + " 分"
                + ("（" + str(best.get("matchLevel")) + "）" if best.get("matchLevel") else "")
                + "。"
            )
            matched = "、".join((best.get("matchedSkills") or [])[:4])
            parts = [header]
            if matched:
                parts.append("已覆盖技能：" + matched + "。")
        else:
            parts = ["本次没有检索到符合条件的岗位，建议放宽城市或岗位方向后重试。"]
        gaps = [item["skill"] for item in (state.get("gaps") or [])[:2]]
        if gaps:
            parts.append("优先补齐：" + "、".join(gaps) + "。")
        parts.append("建议按「先补缺口技能 → 再针对目标岗位改简历关键词 → 投递前模拟面试一轮」的顺序推进。")
        fallback = "".join(parts)
        answer = self._ask_json(
            SUMMARY_SYSTEM,
            "求职画像：" + json.dumps(state.get("profile") or {}, ensure_ascii=False)
            + "\nTop 岗位（含算法分数）：" + json.dumps([{
                "title": item["job"].get("title"),
                "company": item["job"].get("company"),
                "city": item["job"].get("city"),
                "matchScore": item.get("matchScore"),
                "matchLevel": item.get("matchLevel"),
                "matchedSkills": item.get("matchedSkills"),
                "missingSkills": item.get("missingSkills"),
            } for item in top], ensure_ascii=False)
            + "\n技能缺口统计：" + json.dumps(state.get("gaps") or [], ensure_ascii=False),
        )
        if isinstance(answer, dict):
            for key in ("summary", "text", "结论"):
                value = answer.get(key)
                if isinstance(value, str) and len(value.strip()) >= 30:
                    return value.strip()
        return fallback

    # ---------------------------------------------------------------- graph
    def _route_after_reflect(self, state: JobAgentState) -> str:
        return "recall" if state.get("retry") else "gap_analysis"

    def _build_graph(self):
        graph = StateGraph(JobAgentState)
        graph.add_node("extract_profile", self._node_extract_profile)
        graph.add_node("recall", self._node_recall)
        graph.add_node("rank", self._node_rank)
        graph.add_node("reflect", self._node_reflect)
        graph.add_node("gap_analysis", self._node_gap_analysis)
        graph.add_node("compose", self._node_compose)
        graph.set_entry_point("extract_profile")
        graph.add_edge("extract_profile", "recall")
        graph.add_edge("recall", "rank")
        graph.add_edge("rank", "reflect")
        graph.add_conditional_edges("reflect", self._route_after_reflect, {
            "recall": "recall",
            "gap_analysis": "gap_analysis",
        })
        graph.add_edge("gap_analysis", "compose")
        graph.add_edge("compose", END)
        return graph.compile()

    # ----------------------------------------------------------------- entry
    def status(self) -> dict[str, Any]:
        return {
            "mode": "LANGGRAPH_LLM" if self.llm_enabled else "LANGGRAPH_LOCAL_FALLBACK",
            "model": settings.openai_model if self.llm_enabled else "local-planner",
            "maxAttempts": MAX_ATTEMPTS,
            "strongMatchScore": STRONG_MATCH_SCORE,
            "nodes": ["extract_profile", "recall", "rank", "reflect", "gap_analysis", "compose"],
        }

    def run(self, resume_id: int, city: str = "", excluded_cities: str = "", limit: int = 8) -> dict[str, Any]:
        initial: JobAgentState = {
            "resume_id": int(resume_id),
            "city": city or "",
            "excluded_cities": excluded_cities or "",
            "trace": [],
        }
        final = self.graph.invoke(initial)
        report = final.get("report") or {}
        size = max(1, min(int(limit or 8), FINAL_LIMIT))
        report["recommendations"] = (report.get("recommendations") or [])[:size]
        return report
