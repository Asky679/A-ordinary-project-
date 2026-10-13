import json
import re
from typing import Any

from langchain_core.messages import HumanMessage
from langchain_openai import ChatOpenAI
from langgraph.checkpoint.memory import MemorySaver
from langgraph.prebuilt import create_react_agent

from .config import settings
from .tools import (
    AGENT_TOOLS,
    get_context,
    get_interview_progress,
    evaluate_candidate_answer,
    read_session_context,
    save_interview_memory,
    search_question_bank,
    set_context,
)

SYSTEM_PROMPT = """
你是 CareerNav 的计算机岗位面试 Agent。你不是固定题库机器人，而是可以自主规划和选择工具的面试官。

每次收到任务时，你必须：
1. 自主决定需要调用哪些工具，例如：
   - read_session_context
   - get_job_requirements
   - search_question_bank
   - evaluate_candidate_answer
   - save_interview_memory
   - get_interview_progress
2. 先获取足够的简历、岗位、回答和进度事实，再决定是追问、换阶段还是结束。
3. 追问必须基于候选人上一轮回答中的具体缺口，例如缺少基线、技术取舍、个人贡献、量化结果或故障处理。
4. 不得编造候选人的经历、项目或数据。
5. 一轮只问一个主要问题。
6. 最终只返回合法 JSON，不要 Markdown，不要额外解释。

返回格式：
{
  "question": "下一道面试问题；如果结束则返回空字符串",
  "score": 0到100的整数或null,
  "feedback": "对本轮回答的具体反馈；开始时可为空",
  "stage": "self_intro/project/backend/python/system/behavior/finish",
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


class InterviewAgent:
    def __init__(self) -> None:
        self.llm_enabled = settings.llm_enabled
        self.checkpointer = MemorySaver()
        self.agent = None
        if self.llm_enabled:
            model = ChatOpenAI(
                model=settings.openai_model,
                api_key=settings.openai_api_key,
                base_url=settings.openai_base_url,
                temperature=0.2,
            )
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
                f"开始一场新的模拟面试。session_id={session_id}。"
                "请先读取会话上下文、岗位要求和问题库，然后生成第一道问题。"
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
        result = self.agent.invoke(
            {"messages": [HumanMessage(content=prompt)]},
            config={"configurable": {"thread_id": session_id}},
        )
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
        job_result = tool_result(search_question_bank, {"session_id": session_id, "keyword": "", "stage": ""})
        questions = job_result.get("questions") or []
        question = questions[0] if questions else "请先介绍一下你自己，并重点说明一个和目标岗位最相关的项目。"
        tool_result(save_interview_memory, {
            "session_id": session_id,
            "note": f"目标岗位：{(context.get('job') or {}).get('title', '未指定')}",
        })
        return {
            "question": question,
            "score": None,
            "feedback": "",
            "stage": job_result.get("stage", "self_intro"),
            "done": False,
            "round": 1,
            "toolCalls": ["read_session_context", "search_question_bank", "save_interview_memory"],
            "memory": context.get("memory", []),
            "reasoningSummary": "本地规划器先读取上下文，再从问题库选择开场问题。",
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
            selection = tool_result(search_question_bank, {"session_id": session_id, "keyword": ""})
            questions = selection.get("questions") or []
            next_question = questions[(current_round) % len(questions)] if questions else "请结合项目说明你的技术取舍和量化结果。"
            stage = selection.get("stage", "followup")
            tool_calls.append("search_question_bank")
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
            "reasoningSummary": "本地规划器评估回答、提取薄弱点，再决定下一道问题。",
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