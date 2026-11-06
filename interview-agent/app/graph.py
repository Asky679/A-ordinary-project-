import json
import logging
import re
from typing import Any

from langchain_core.messages import HumanMessage
from langgraph.checkpoint.memory import MemorySaver
from langgraph.prebuilt import create_react_agent

from .config import settings
from .llm import create_chat_model
from .tools import (
    AGENT_TOOLS,
    get_context,
    get_interview_progress,
    evaluate_candidate_answer,
    read_session_context,
    save_interview_memory,
    search_interview_questions_qdrant,
    search_real_interview_patterns,
    search_question_bank,
    set_context,
)

logger = logging.getLogger(__name__)

SYSTEM_PROMPT = """
你是 CareerNav 的计算机岗位面试 Agent。你不是固定题库机器人，而是可以自主规划和选择工具的面试官。

每次收到任务时，你必须：
1. 自主决定需要调用哪些工具，例如：
   - read_session_context
   - get_job_requirements
   - search_real_interview_patterns（真实面试对话与公开面经，优先学习其追问方式）
   - search_interview_questions_qdrant（本地面试题向量库，补充知识型问题）
   - search_question_bank（内置小型模板，仅作兜底）
   - evaluate_candidate_answer
   - save_interview_memory
   - get_interview_progress
2. 先获取足够的简历、岗位、回答和进度事实，再决定是追问、换阶段还是结束。
3. 一场技能验证必须按以下顺序推进，不能一直停留在自我介绍：
   基础概念 -> 项目实现 -> 故障与边界 -> 方案取舍 -> 结果验证。
4. 岗位需求优先级最高：每轮先选一条岗位核心技能、职责或任职要求作为考察目标。简历只用于选择项目载体和判断证据，不能让简历中的其他技能覆盖岗位方向。
5. 第一轮直接问岗位核心技能的基础原理、适用场景和易错边界，不要用自我介绍占轮次。如果候选人没有岗位要求的某项技能，应问“现在如何设计或如何迁移已有能力”，不要转而只问简历里熟悉但与岗位无关的内容。
6. 生成问题前优先调用 search_real_interview_patterns，学习真实面试的主问题、追问链和措辞，但必须结合当前岗位、简历和候选人回答改写，不能生搬硬套。
7. 追问必须基于候选人上一轮回答中的具体缺口，例如概念错误、缺少个人贡献、缺少边界处理、缺少替代方案或缺少验证结果。
8. 真实面试常见顺序是“定义 -> 具体项目 -> 场景设计 -> 最差情况/替代方案 -> 验证结果”。候选人说没做过时，可以转为“如果现在让你设计”，同时降低事实性判定。
9. 评分必须判断技术正确性和证据质量，不能因为回答长或关键词多就给高分；只有定义、简历复述或团队成果都不算个人掌握证据。
10. 不得编造候选人的经历、项目或数据，也不得预设学生项目有线上流量、压测或生产故障。
11. 一次只问一个主要问题；可以包含一到两个具体子问题，但不能变成整组笔试。
12. 最终只返回合法 JSON，不要 Markdown，不要额外解释。

返回格式：
{
  "question": "下一道面试问题；如果结束则返回空字符串",
  "score": 0到100的整数或null,
  "feedback": "对本轮回答的具体反馈；开始时可为空",
  "stage": "基础概念/项目实现/故障与边界/方案取舍/结果验证/finish",
  "done": false,
  "memory": ["需要长期记住的候选人薄弱点或事实"],
  "reasoning_summary": "简要说明你为什么在这里选择这个工具和问题，不要暴露逐步推理链"
}
"""


def extract_json(text: str) -> dict[str, Any] | None:
    if not text:
        return None
    start = text.find("{")
    end = text.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        return json.loads(text[start:end + 1])
    except json.JSONDecodeError:
        return None


def tool_result(tool_obj, payload: dict[str, Any]) -> dict[str, Any]:
    result = tool_obj.invoke(payload)
    if isinstance(result, str):
        try:
            return json.loads(result)
        except json.JSONDecodeError:
            return {"raw": result}
    return result if isinstance(result, dict) else {"raw": str(result)}


def _primary_skill(context: dict[str, Any]) -> str:
    job = context.get("job") or {}
    for skill in job.get("skills") or []:
        value = str(skill).strip()
        if value:
            return value
    resume = context.get("resume") or {}
    for skill in resume.get("skills") or []:
        value = str(skill).strip()
        if value:
            return value
    return "岗位核心技能"


def _stage_question(stage_index: int, context: dict[str, Any]) -> str:
    skill = _primary_skill(context)
    resume = context.get("resume") or {}
    projects = resume.get("projects") or []
    project = str(projects[0].get("name") or "你的代表项目") if projects and isinstance(projects[0], dict) else "你的代表项目"
    specific_concepts = {
        "java": "Java 中 final 和 finally 有什么区别？请分别说明使用场景，并举出一个容易出错的边界。",
        "spring": "Spring 的 IOC 和 AOP 分别解决什么问题？请结合一个接口调用过程说明它们在哪里生效。",
        "spring boot": "Spring Boot 自动配置的核心流程是什么？如果自定义 Starter，你会怎样控制生效条件？",
        "mysql": "MySQL 索引为什么通常选择 B+ 树？请说明范围查询、回表和最左前缀各对应什么边界。",
        "redis": "Redis 缓存与数据库不一致时，你会怎样处理？请说明并发窗口、失败重试和业务容忍度。",
        "python": "Python 异步编程和线程模型有什么区别？请说明 GIL、适用场景和一个容易踩坑的边界。",
        "microservices": "微服务调用中，重复请求、超时和下游重试可能带来什么问题？你会如何设计幂等和降级？",
        "distributed systems": "请解释分布式系统中一致性与可用性的取舍。结合一个具体调用链说明你会怎样避免重复处理。",
        "design patterns": "请举一个你在项目中实际使用过的设计模式，说明不用它会有什么问题，以及它的边界。",
        "rag": "RAG 的召回、重排和生成三个阶段分别可能在哪里失真？请说明一个你实际做过的验证方法。",
        "agent": "一个 Agent 选择工具失败时，你会如何设计重试、参数校验和终止条件？请说明如何避免死循环。",
    }
    normalized_skill = skill.strip().lower()
    concept_question = next(
        (question for key, question in specific_concepts.items() if key in normalized_skill),
        f"请解释 {skill} 的核心机制、适用场景和一个容易出错的边界，并尽量结合真实代码。",
    )
    questions = [
        concept_question,
        f"请以“{project}”为例，说明 {skill} 具体用在哪段流程、你本人负责什么、关键代码或数据如何流转。",
        f"如果使用 {skill} 的这条流程遇到异常输入、并发冲突或依赖不可用，你会如何定位并处理？请说明判断依据。",
        f"在 {skill} 的实现中你做过什么技术选择？请对比至少一个替代方案，并说明为什么选当前方案。",
        f"你如何证明 {skill} 相关功能确实有效？请说明验证方法、基线、结果和你个人能负责的范围；没有量化数据就说明功能验证过程。",
    ]
    return questions[max(0, min(stage_index, len(questions) - 1))]


class InterviewAgent:
    def __init__(self) -> None:
        self.llm_enabled = settings.llm_enabled
        self.checkpointer = MemorySaver()
        self.agent = None
        if self.llm_enabled:
            model = create_chat_model(temperature=0.2)
            self.agent = create_react_agent(
                model,
                AGENT_TOOLS,
                checkpointer=self.checkpointer,
                prompt=SYSTEM_PROMPT,
            )

    def status(self) -> dict[str, Any]:
        return {
            "mode": "LANGGRAPH_LLM" if self.llm_enabled else "LANGGRAPH_LOCAL_FALLBACK",
            "model": settings.openai_model if self.llm_enabled else "local-planner",
            "maxRounds": settings.max_rounds,
            "tools": [tool.name for tool in AGENT_TOOLS],
        }
    def start(self, session_id: str, resume: dict[str, Any], job: dict[str, Any], difficulty: str) -> dict[str, Any]:
        set_context(session_id, {
            "resume": resume or {},
            "job": job or {},
            "difficulty": difficulty or "标准",
            "round": 1,
            "max_rounds": settings.max_rounds,
            "memory": [],
            "history": [],
        })
        if self.agent is not None:
            prompt = (
                f"开始一场新的技能验证面试。session_id={session_id}。"
                "请先读取会话上下文、岗位要求和问题库，然后直接提出基础概念问题。"
                "第一问必须能验证岗位核心技能的原理、使用场景或易错边界，不能问自我介绍。"
                "必须调用工具后再给出 JSON。"
            )
            return self._invoke_llm(session_id, prompt, phase="start")
        return self._rule_start(session_id)

    def answer(self, session_id: str, answer: str) -> dict[str, Any]:
        context = get_context(session_id)
        context["answer"] = answer
        context.setdefault("history", []).append({"role": "candidate", "content": answer})
        if self.agent is not None:
            prompt = (
                f"候选人第 {context.get('round', 1)} 轮回答如下：\n<<<{answer}>>>\n"
                "请自主选择工具评估回答、更新记忆并读取进度，然后决定追问、换阶段或结束。"
                "当前轮次达到最大轮次时必须结束。"
            )
            return self._invoke_llm(session_id, prompt, phase="answer")
        return self._rule_answer(session_id, answer)

    def _invoke_llm(self, session_id: str, prompt: str, phase: str) -> dict[str, Any]:
        try:
            result = self.agent.invoke(
                {"messages": [HumanMessage(content=prompt)]},
                config={"configurable": {"thread_id": session_id}},
            )
        except Exception as exc:  # noqa: BLE001 - always keep the interview available
            logger.warning("LLM interview turn failed, using local planner: %s", exc)
            context = get_context(session_id)
            if phase == "start":
                return self._rule_start(session_id)
            return self._rule_answer(session_id, str(context.get("answer") or ""))
        messages = result.get("messages", [])
        tool_calls: list[str] = []
        for message in messages:
            for call in getattr(message, "tool_calls", None) or []:
                name = call.get("name") if isinstance(call, dict) else None
                if name:
                    tool_calls.append(name)
        content = str(getattr(messages[-1], "content", "")) if messages else ""
        parsed = extract_json(content) or {}
        question = parsed.get("question") or parsed.get("nextQuestion") or ""
        feedback = parsed.get("feedback") or ""
        score = parsed.get("score")
        done = bool(parsed.get("done", False))
        context = get_context(session_id)
        if score is not None:
            try:
                score = int(score)
            except (TypeError, ValueError):
                score = None
        if score is not None:
            context.setdefault("history", []).append({"role": "assistant", "score": score, "feedback": feedback})
        for note in parsed.get("memory", []) or []:
            tool_result(save_interview_memory, {"session_id": session_id, "note": str(note)})
        round_number = int(context.get("round", 1))
        if done:
            context["done"] = True
        elif phase == "answer":
            context["round"] = round_number + 1
        return {
            "question": question,
            "score": score,
            "feedback": feedback,
            "stage": parsed.get("stage") or ("finish" if done else "followup"),
            "done": done,
            "round": int(context.get("round", 1)),
            "toolCalls": tool_calls,
            "memory": context.get("memory", []),
            "reasoningSummary": parsed.get("reasoning_summary", ""),
        }

    def _rule_start(self, session_id: str) -> dict[str, Any]:
        context = get_context(session_id)
        context_result = tool_result(read_session_context, {"session_id": session_id})
        tool_calls = ["read_session_context", "search_real_interview_patterns", "search_interview_questions_qdrant", "save_interview_memory"]
        job_skill = _primary_skill(context)
        tool_result(search_real_interview_patterns, {
            "session_id": session_id, "query": job_skill + " 项目 基础概念", "stage": "基础概念", "limit": 3,
        })
        tool_result(search_interview_questions_qdrant, {
            "session_id": session_id, "query": job_skill + " 基础原理 使用场景 易错点", "limit": 3,
        })
        stage = "基础概念"
        question = _stage_question(0, context)
        tool_result(save_interview_memory, {
            "session_id": session_id,
            "note": f"目标岗位：{(context.get('job') or {}).get('title', '未指定')}",
        })
        return {
            "question": question,
            "score": None,
            "feedback": "",
            "stage": stage,
            "done": False,
            "round": 1,
            "toolCalls": tool_calls,
            "memory": context.get("memory", []),
            "reasoningSummary": "本地规划器先读取岗位与简历上下文，再生成基础概念验证题。",
        }

    def _rule_answer(self, session_id: str, answer: str) -> dict[str, Any]:
        context = get_context(session_id)
        evaluation = tool_result(evaluate_candidate_answer, {"session_id": session_id, "answer": answer})
        score = int(evaluation.get("score", 50))
        feedback = evaluation.get("feedback", "")
        current_round = int(context.get("round", 1))
        max_rounds = int(context.get("max_rounds", settings.max_rounds))
        done = current_round >= max_rounds
        next_question = ""
        stage = "finish" if done else "followup"
        tool_calls = ["evaluate_candidate_answer", "get_interview_progress"]
        if not done:
            context["round"] = current_round + 1
            stage_names = ["基础概念", "项目实现", "故障与边界", "方案取舍", "结果验证"]
            stage_index = min(current_round, len(stage_names) - 1)
            stage = stage_names[stage_index]
            tool_result(search_real_interview_patterns, {
                "session_id": session_id, "query": answer[:300], "stage": stage, "limit": 3,
            })
            tool_calls.append("search_real_interview_patterns")
            tool_result(search_interview_questions_qdrant, {
                "session_id": session_id, "query": answer[:300], "limit": 4,
            })
            tool_calls.append("search_interview_questions_qdrant")
            next_question = _stage_question(stage_index, context)
        notes = evaluation.get("gaps") or []
        for note in notes[:2]:
            tool_result(save_interview_memory, {"session_id": session_id, "note": str(note)})
        tool_calls.append("save_interview_memory")
        context.setdefault("history", []).append({"role": "assistant", "score": score, "feedback": feedback})
        return {
            "question": next_question,
            "score": score,
            "feedback": feedback,
            "stage": stage,
            "done": done,
            "round": int(context.get("round", 1)),
            "toolCalls": tool_calls,
            "memory": context.get("memory", []),
            "reasoningSummary": "本地规划器评估回答，并按基础概念、项目实现、故障边界、方案取舍、结果验证五层推进。",
        }

    def snapshot(self, session_id: str) -> dict[str, Any]:
        context = get_context(session_id)
        return {
            "sessionId": session_id,
            "round": context.get("round", 1),
            "maxRounds": context.get("max_rounds", settings.max_rounds),
            "memory": context.get("memory", []),
            "history": context.get("history", []),
            "done": bool(context.get("done", False)),
        }