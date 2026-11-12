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
3. 一场项目面试必须按以下八层推进，不能一直停留在自我介绍：
   职责边界 -> 端到端业务链路 -> 核心技术实现 -> 故障与边界 -> 方案取舍 -> 结果验证 -> 证据补强 -> 迁移验证。
4. 本场面试固定使用项目证据卡中的项目。简历即使有多个项目，也不得切换到其他项目；证据卡包含个人责任地图、端到端调用链、核心接口、数据模型、两项技术决策、故障档案、结果证据和能力边界。必须优先引用这些结构化事实追问，不得要求源码、ZIP、公司内部数据或客户信息。
5. 第一轮必须问项目背景、团队规模、个人职责和模块边界；不允许用“请自我介绍”或八股题占用第一轮。
6. 如果项目证据卡字段为空，先用一个具体问题补齐最关键的缺口，再继续深挖；不能凭空假设候选人做过高并发、线上流量、压测或故障。
7. 岗位 JD 已经转换为结构化“JD 面试蓝图”，其中必须覆盖必备技能、核心职责和面试场景。每轮先从蓝图中选择尚未覆盖的要求，再用项目证据卡中的事实作为提问载体；不得把蓝图中的推测项说成候选人已经做过。
8. 生成问题前优先调用 search_real_interview_patterns，学习真实面试的主问题、追问链和措辞，但必须结合当前项目证据卡、岗位蓝图和候选人回答改写，不能生搬硬套。
9. 追问必须基于上一轮回答中的具体缺口：个人贡献不清、业务链路断裂、技术实现含糊、缺少异常处理、缺少替代方案或结果不可验证。
10. 每轮只问一个主要问题；如果回答很弱，可以在同一主题追加一次澄清，但一场面试最多 8 个主轮次。
11. 评分判断技术正确性和证据质量，不因回答长或关键词多而高分；团队成果、简历复述和无法核实的数字不能算个人证据。
12. 不得编造候选人的经历、项目或数据；企业项目只使用脱敏后的模块和业务描述。
13. 最终只返回合法 JSON，不要 Markdown，不要额外解释。

返回格式：
{
  "question": "下一道面试问题；如果结束则返回空字符串",
  "score": 0到100的整数或null,
  "feedback": "对本轮回答的具体反馈；开始时可为空",
  "stage": "职责边界/业务链路/技术实现/故障与边界/方案取舍/结果验证/证据补强/迁移验证/finish",
  "done": false,
  "memory": ["需要长期记住的候选人薄弱点或事实"],
  "reasoning_summary": "简要说明你为什么在这里选择这个工具和问题，不要暴露逐步推理链"
}
"""


def _enforce_round_policy(context: dict[str, Any], phase: str, question: str, done: bool) -> tuple[str, bool, int, int]:
    """The interview may end only after max_rounds answers, never on model whim."""
    round_number = int(context.get("round", 1))
    max_rounds = int(context.get("max_rounds", settings.max_rounds))
    if phase == "start":
        return question, False, round_number, max_rounds
    if round_number >= max_rounds:
        return "", True, round_number, max_rounds
    if not question:
        question = _stage_question(round_number, context)
    return question, False, round_number, max_rounds

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
    blueprint = context.get("job_blueprint") or {}
    for skill in blueprint.get("mustHaveSkills") or []:
        value = str(skill).strip()
        if value:
            return value
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
    evidence = context.get("project_evidence") or {}
    details = evidence.get("evidenceDetails") or {}
    blueprint = context.get("job_blueprint") or {}
    project = str(evidence.get("projectName") or (projects[0].get("name") if projects and isinstance(projects[0], dict) else "") or "你的代表项目")
    responsibilities = blueprint.get("coreResponsibilities") or []
    scenarios = blueprint.get("interviewScenarios") or []
    responsibility = str(responsibilities[0] if responsibilities else "岗位核心职责")
    scenario = str(scenarios[0] if scenarios else "岗位典型业务场景")
    role = str(evidence.get("role") or "项目成员")
    owned = str(details.get("ownedModules") or evidence.get("implementation") or "你本人负责的模块")
    excluded = str(details.get("notOwnedModules") or "明确不是由你负责的工作")
    entry = str(details.get("entryPoint") or "用户入口")
    processing = str(details.get("processingChain") or "请求处理链路")
    data_change = str(details.get("dataChange") or "数据变化")
    response = str(details.get("responsePath") or "返回结果")
    core_api = str(details.get("coreApi") or "核心接口")
    api_contract = str(details.get("apiContract") or "接口契约")
    core_tables = str(details.get("coreTables") or "核心表")
    state_transitions = str(details.get("stateTransitions") or "状态流转")
    fault_symptom = str(details.get("faultSymptom") or "一个真实故障现象")
    fault_diagnosis = str(details.get("faultDiagnosis") or "定位过程与根因")
    fault_fix = str(details.get("faultFixVerification") or "修复与回归验证")
    decision_title = str(details.get("decisionOneTitle") or "一个关键技术决策")
    decision_alt = str(details.get("decisionOneAlternative") or "未采用的替代方案")
    decision_tradeoff = str(details.get("decisionOneTradeoff") or "选择原因与副作用")
    decision2_title = str(details.get("decisionTwoTitle") or "第二个技术决策")
    decision2_alt = str(details.get("decisionTwoAlternative") or "第二个替代方案")
    test_coverage = str(details.get("testCoverage") or "测试范围")
    evidence_available = str(details.get("evidenceAvailable") or "可提供的证据")
    unmeasured = str(details.get("unmeasuredData") or "没有统计的数据")
    known_limitations = str(details.get("knownLimitations") or "已知问题")
    scale_plan = str(details.get("scalePlan") or "规模扩大后的调整方案")
    questions = [
        f"请围绕“{project}”说明你的责任边界。你担任{role}；证据卡中你填写独立完成了：{owned}；并明确“{excluded}”不是你负责的。请挑其中一项，讲清你亲手写了什么、与谁协作，以及你当时能决定到什么程度。",
        f"请选择一条与岗位职责「{responsibility}」最接近的调用链，并讲成一次完整请求：从“{entry}”进入，经过“{processing}”，造成“{data_change}”，最后以“{response}”返回。请重点说明你负责的环节，以及任何一环失败时如何处理。",
        f"围绕岗位必备技能 {skill}，请以核心接口“{core_api}”为例，说明请求与返回、参数校验、权限、异常，以及“{core_tables}”中的状态如何从“{state_transitions}”流转。证据卡中的接口契约是：{api_contract}",
        f"结合岗位场景「{scenario}」，请复盘证据卡中的故障：“{fault_symptom}”。你当时如何定位？根因是“{fault_diagnosis}”吗？修复与验证是“{fault_fix}”吗？请说明排查顺序和你实际执行的步骤。",
        f"请解释两个技术取舍。第一项是“{decision_title}”，替代方案“{decision_alt}”，原因是“{decision_tradeoff}”；第二项是“{decision2_title}”，替代方案“{decision2_alt}”。请说明这两项选择分别牺牲了什么，以及你会如何验证取舍是否正确。",
        f"请说明结果与证据。你填写的测试范围是“{test_coverage}”，可提供证据是“{evidence_available}”，没有统计的是“{unmeasured}”。请区分哪些结果属于个人贡献，哪些只是团队或流程验证。",
        f"结合岗位场景「{scenario}」，请补强证据卡中最薄弱的一处，尤其是个人贡献、接口边界或故障验证。你填写的已知问题是：“{known_limitations}”。",
        f"如果项目规模扩大10倍，请基于“{scale_plan}”继续说明：你先改哪一层，为什么先改这里，如何验证改造后没有破坏现有业务？",
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
    def start(self, session_id: str, resume: dict[str, Any], job: dict[str, Any], difficulty: str,
              project_evidence: dict[str, Any] | None = None,
              job_blueprint: dict[str, Any] | None = None) -> dict[str, Any]:
        evidence = project_evidence or {}
        interview_resume = self._focus_resume_project(resume or {}, evidence)
        set_context(session_id, {
            "resume": interview_resume,
            "job": job or {},
            "project_evidence": evidence,
            "job_blueprint": job_blueprint or {},
            "difficulty": difficulty or "标准",
            "round": 1,
            "max_rounds": settings.max_rounds,
            "memory": [],
            "history": [],
        })
        if self.agent is not None:
            prompt = (
                f"开始一场新的项目驱动面试。session_id={session_id}。"
                f"本场唯一允许讨论的项目是：{evidence.get('projectName') or '证据卡项目'}。"
                f"本场结构化项目证据是：{json.dumps(evidence.get('evidenceDetails') or {}, ensure_ascii=False)}。"
                f"本场 JD 面试蓝图是：{json.dumps(job_blueprint or {}, ensure_ascii=False)}。"
                "请先读取会话上下文、岗位要求和结构化项目证据卡，然后直接提出第一轮职责边界问题。"
                "后续问题必须优先覆盖蓝图中的必备技能、核心职责和面试场景，不能只按简历技能提问。"
                "第一问必须明确引用这个项目名称，围绕项目背景、团队规模、个人角色和模块边界，不能问自我介绍，也不能先问八股。"
                "项目可能是企业保密项目，禁止要求源码、ZIP、公司内部数据或客户信息。"
                "必须调用工具后再给出 JSON。"
            )
            result = self._invoke_llm(session_id, prompt, phase="start")
            return self._guard_selected_project(session_id, result)
        return self._rule_start(session_id)

    def _focus_resume_project(self, resume: dict[str, Any], evidence: dict[str, Any]) -> dict[str, Any]:
        selected_name = str(evidence.get("projectName") or "").strip()
        if not selected_name:
            return resume
        projects = resume.get("projects") or []
        selected = None
        for project in projects:
            if isinstance(project, dict) and str(project.get("name") or "").strip() == selected_name:
                selected = dict(project)
                break
        if selected is None:
            selected = {"name": selected_name}
        selected.update({
            "name": selected_name,
            "role": evidence.get("role") or selected.get("role") or "",
            "techStack": evidence.get("techStack") or selected.get("techStack") or "",
            "description": evidence.get("businessFlow") or selected.get("description") or "",
            "challenge": evidence.get("challenge") or selected.get("challenge") or "",
            "highlight": evidence.get("resultEvidence") or selected.get("highlight") or "",
        })
        focused = dict(resume)
        focused["projects"] = [selected]
        return focused

    def _guard_selected_project(self, session_id: str, result: dict[str, Any]) -> dict[str, Any]:
        context = get_context(session_id)
        evidence = context.get("project_evidence") or {}
        project_name = str(evidence.get("projectName") or "").strip()
        question = str(result.get("question") or "")
        if project_name and project_name not in question:
            result = dict(result)
            result["question"] = _stage_question(0, context)
            result["stage"] = "职责边界"
            result["toolCalls"] = list(result.get("toolCalls") or []) + ["project_evidence_guard"]
        return result

    def answer(self, session_id: str, answer: str) -> dict[str, Any]:
        context = get_context(session_id)
        context["answer"] = answer
        context.setdefault("history", []).append({"role": "candidate", "content": answer})
        if self.agent is not None:
            prompt = (
                f"候选人第 {context.get('round', 1)} 轮回答如下：\n<<<{answer}>>>\n"
                "请自主选择工具评估回答、更新记忆并读取进度，然后决定追问或换阶段。"
                "只有当前轮次达到最大轮次时才允许结束，任何提前结束都会被系统修正。"
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
        question, done, round_number, max_rounds = _enforce_round_policy(context, phase, question, done)
        if done:
            context["done"] = True
        elif phase == "answer":
            context["round"] = round_number + 1
        return {
            "question": question,
            "score": score,
            "feedback": feedback,
            "stage": "finish" if done else (parsed.get("stage") or "followup"),
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
            "session_id": session_id, "query": job_skill + " 项目 职责边界", "stage": "职责边界", "limit": 3,
        })
        tool_result(search_interview_questions_qdrant, {
            "session_id": session_id, "query": job_skill + " 基础原理 使用场景 易错点", "limit": 3,
        })
        stage = "职责边界"
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
            "reasoningSummary": "本地规划器先读取岗位、简历和脱敏项目证据，再生成项目职责验证题。",
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
            stage_names = ["职责边界", "业务链路", "技术实现", "故障与边界", "方案取舍", "结果验证", "证据补强", "迁移验证"]
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
            "reasoningSummary": "本地规划器评估回答，并按职责边界、业务链路、技术实现、故障边界、方案取舍、结果验证、证据补强、迁移验证八层推进。",
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