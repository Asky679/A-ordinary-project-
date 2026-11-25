import copy
import json
import logging
import re
from typing import Any

from langchain_core.messages import HumanMessage
from langgraph.checkpoint.memory import MemorySaver
from langgraph.checkpoint.postgres import PostgresSaver
from langgraph.prebuilt import create_react_agent

from .config import settings
from .llm import create_chat_model
from .junior_reference import (
    is_junior_level,
    junior_guard_question,
    junior_stage_question,
    reconcile_junior_score,
    select_next_junior_stage,
)
from .tools import (
    AGENT_TOOLS,
    get_context,
    persist_context,
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

INTERVIEW_MODE_PLANS: dict[str, tuple[str, ...]] = {
    "综合面": ("求职动机", "项目职责", "业务链路", "技术实现", "故障与取舍", "团队协作", "失败与学习", "候选人反问"),
    "技术面": ("个人背景", "项目职责", "业务链路", "技术实现", "故障与边界", "方案取舍", "结果验证", "候选人反问"),
    "HR面": ("个人背景", "求职动机", "项目概述", "团队协作", "冲突处理", "岗位意愿", "职业规划", "候选人反问"),
    "主管面": ("个人背景", "项目职责", "业务链路", "技术判断", "故障与边界", "方案取舍", "团队协作", "候选人反问"),
    "行为面": ("个人背景", "团队协作", "冲突处理", "失败与学习", "推动改变", "压力与优先级", "职业规划", "候选人反问"),
}
INTERVIEW_MODE_REQUIRED_STAGES: dict[str, tuple[str, ...]] = {
    "综合面": ("求职动机", "项目职责", "技术实现", "团队协作", "失败与学习", "候选人反问"),
    "技术面": ("个人背景", "项目职责", "业务链路", "技术实现", "结果验证", "候选人反问"),
    "HR面": ("个人背景", "求职动机", "团队协作", "岗位意愿", "职业规划", "候选人反问"),
    "主管面": ("个人背景", "项目职责", "业务链路", "技术判断", "团队协作", "候选人反问"),
    "行为面": ("个人背景", "团队协作", "冲突处理", "失败与学习", "职业规划", "候选人反问"),
}
INTERVIEW_MODE_OPTIONAL_STAGES: dict[str, tuple[str, ...]] = {
    "综合面": ("错误原因", "基础概念", "故障与边界", "方案取舍", "证据补强", "迁移验证", "压力与优先级"),
    "技术面": ("错误原因", "基础概念", "故障与边界", "故障与取舍", "方案取舍", "证据补强", "迁移验证"),
    "HR面": ("错误原因", "压力与优先级", "失败与学习", "推动改变", "冲突处理"),
    "主管面": ("错误原因", "故障与边界", "方案取舍", "结果验证", "压力与优先级"),
    "行为面": ("错误原因", "推动改变", "压力与优先级", "失败与学习"),
}
INTERVIEW_MODE_DESCRIPTIONS = {
    "综合面": "校招和实习最常用：项目、动机、协作、反思都会覆盖",
    "技术面": "围绕岗位和项目验证技术深度、故障处理与方案取舍",
    "HR面": "重点看求职动机、稳定性、沟通、规划和岗位意愿",
    "主管面": "重点看项目负责、业务判断、协作、结果与成长",
    "行为面": "用真实经历验证协作、冲突、失败、推动改变和抗压能力",
}
TECHNICAL_STAGES = {
    "项目职责", "项目概述", "业务链路", "技术实现", "基础概念", "故障与取舍",
    "故障与边界", "方案取舍", "结果验证", "技术判断", "证据补强", "迁移验证", "错误原因",
}

SYSTEM_PROMPT = """
你是 CareerNav 的计算机岗位面试 Agent。你不是固定题库机器人，而是可以自主规划和选择工具的面试官。

每次收到任务时，你必须：
1. 先读取面试类型 interview_mode、required_stages、optional_stages、visited_stages、next_stage、当前轮次和历史回答。
2. 当 adaptive_route=true 时，面试维度顺序由回答证据动态决定，不是固定轮次表；你必须严格围绕 next_stage 提问。
3. required_stages 是本场必须覆盖的考察底线，optional_stages 是根据回答缺口临时追加的追问池。
4. 综合面、技术面、HR面、主管面、行为面都必须遵守各自 required_stages，不能把所有面试都变成技术深挖。
5. 已经访问过的维度不得重复；除非 next_stage 明确要求，否则不能在同一技术点上连续追问。
6. 每轮只问一个主要问题。初级技术问题必须优先使用当前岗位方向的方向级问法。
7. 每轮只问一个主要问题。问题要具体、自然、像真实面试官，不要一次列出多个小问。
8. 项目问题优先引用脱敏项目证据；不得要求源码、ZIP、公司内部数据或客户信息。
9. 不能编造候选人的经历、项目或数据。候选人没有说过的内容只能追问，不能替其补全。证据字段为空时，不得把字段名当作候选人事实，不得输出“你的一个技术选择”“另一个备选方案”等占位词，必须改成自然、开放的真实面试官问法。
10. 评分要符合当前轮次维度：技术问题看正确性和证据；行为/HR问题看真实经历、行动、沟通、反思和结果，不能因没有技术关键词就低分。初级评分要面向刚毕业学生：没有生产经验本身不应重罚；65-74 表示有培养潜力，75-84 表示符合初级基本要求，85 以上表示证据充分；不要用中高级标准压分。
11. difficulty 可能形如“初级 · 标准”“中级 · 标准”“高级 · 压力”“架构师 · 标准”。级别决定问题深度：初级重点问个人实现、基础正确性、测试、调试、沟通和边界意识；中级重点问模块责任、接口数据、异常与取舍；高级重点问可靠性、复杂故障和演进；架构师重点问系统边界、治理、成本、风险和长期演进。
12. 初级模式必须像真实面试官一样先问事实再追问：一次只问一个主要问题，优先使用“你在项目里具体做了什么”“输入输出是什么”“如果参数为空/重复提交/超时会怎样”“你怎么测试/定位”“哪部分不是你做的”。真实初级面试也常用短基础题快问（定义、区别、项目里怎么用），答完后必须回到项目证据；不要把基础题追问成完整架构设计。除非岗位JD明确要求，否则不要默认问大规模高并发、异地多活、组织级治理或从零设计分布式事务。
13. 生成初级问题前优先调用 get_junior_interview_reference 和 search_real_interview_patterns；技术阶段可补充 Qdrant 题库；行为和 HR 阶段优先使用真实经历问题。
14. story_bank 是候选人在之前面试中讲过的真实故事，只作为记忆和追问线索，不能视为已经验证的事实。行为或项目问题中如果候选人重复使用同一个故事，要明确提醒换一个例子；如果旧故事缺少结果、个人行动或反思，优先追问补齐。
15. 最终只返回合法 JSON，不要 Markdown，不要额外解释。

返回格式：
{
  "question": "下一道面试问题；如果结束则返回空字符串",
  "score": 0到100的整数或null,
  "feedback": "对本轮回答的具体反馈；开始时可为空",
  "stage": "必须等于本轮 next_stage；结束时填 finish",
  "done": false,
  "memory": ["需要长期记住的候选人薄弱点或事实"],
  "reasoning_summary": "简要说明你为什么选择这个维度和问题，不要暴露逐步推理链"
}
"""

def _enforce_round_policy(context: dict[str, Any], phase: str, question: str, done: bool) -> tuple[str, bool, int, int]:
    """Adaptive routes may end after required coverage; otherwise max_rounds remains a hard cap."""
    round_number = int(context.get("round", 1))
    max_rounds = int(context.get("max_rounds", settings.max_rounds))
    if phase == "start":
        return question, False, round_number, max_rounds
    if context.get("adaptive_route") and context.get("pending_stage") == "finish":
        return "", True, round_number, max_rounds
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


def _level_guidance(difficulty: str) -> str:
    value = str(difficulty or "")
    if "架构师" in value:
        return "按架构师评估：优先追问系统边界、非功能需求、治理、成本、风险、跨团队影响和长期演进；要求说明取舍、约束和演进路线"
    if "高级" in value:
        return "按高级工程师评估：优先追问可靠性、性能、复杂故障、跨模块影响和方案演进；不接受只描述调用流程"
    if is_junior_level(value):
        return (
            "按初级工程师评估：先确认个人实现和事实边界，再问基础正确性、输入输出、边界异常、测试验证、调试排查和学习能力。"
            "一次只问一个主要问题，问题要短、具体、可从简历或项目证据回答；允许回答没做过或只了解。"
            "不要默认追问大规模高并发、异地多活、组织级治理或分布式系统设计；如果候选人自己提到高级概念，也只追问他在项目里的具体用法和验证。"
            "评分面向应届生：允许缺少生产经验，具体、诚实、能验证的回答应优先给到65分以上。"
        )
    return "按中级工程师评估：重点验证独立负责模块、接口与数据设计、故障处理、方案取舍和可验证结果"


def _normalize_mode(mode: str | None) -> str:
    value = str(mode or "").strip()
    return value if value in INTERVIEW_MODE_PLANS else "综合面"


def _mode_plan(mode: str | None) -> tuple[str, ...]:
    return INTERVIEW_MODE_PLANS[_normalize_mode(mode)]


def _required_stages(mode: str | None) -> tuple[str, ...]:
    return INTERVIEW_MODE_REQUIRED_STAGES[_normalize_mode(mode)]


def _optional_stages(mode: str | None) -> tuple[str, ...]:
    return INTERVIEW_MODE_OPTIONAL_STAGES[_normalize_mode(mode)]


def _expected_stage(context: dict[str, Any], phase: str) -> str:
    if context.get("adaptive_route"):
        if phase == "answer":
            return str(context.get("pending_stage") or "finish")
        return str(context.get("pending_stage") or (context.get("stage_route") or ["个人背景"])[0])
    return _planned_stage(context, 1 if phase == "start" else int(context.get("round", 1)))


def _planned_stage(context: dict[str, Any], round_number: int) -> str:
    if context.get("adaptive_route"):
        route = tuple(context.get("stage_route") or context.get("required_stages") or _required_stages(context.get("interview_mode")))
        index = max(0, min(len(route) - 1, int(round_number) - 1))
        return route[index]
    plan = tuple(context.get("interview_plan") or _mode_plan(context.get("interview_mode")))
    index = max(0, min(len(plan) - 1, int(round_number) - 1))
    return plan[index]


def _stage_matches(reported: str, expected: str) -> bool:
    if not reported:
        return False
    if reported == expected:
        return True
    aliases = {
        "业务链路": {"业务实现", "项目实现", "端到端链路"},
        "技术实现": {"项目实现", "技术细节", "实现细节"},
        "故障与边界": {"故障与取舍", "故障边界", "异常与边界"},
        "故障与取舍": {"故障与边界", "故障处理", "方案取舍"},
        "方案取舍": {"技术取舍", "方案选择"},
        "结果验证": {"结果与证据", "验证方式"},
        "团队协作": {"协作沟通", "团队合作"},
        "冲突处理": {"分歧处理", "冲突与沟通"},
        "失败与学习": {"失败复盘", "反思与成长"},
        "岗位意愿": {"求职意愿", "稳定性"},
        "压力与优先级": {"抗压能力", "优先级管理"},
        "候选人反问": {"反问", "提问环节"},
    }
    return reported in aliases.get(expected, set()) or expected in reported or reported in expected

def _stage_question(stage_index: int, context: dict[str, Any]) -> str:
    mode = _normalize_mode(context.get("interview_mode"))
    if context.get("adaptive_route") and context.get("pending_stage"):
        stage = str(context["pending_stage"])
    else:
        plan = _mode_plan(mode)
        stage = plan[max(0, min(stage_index, len(plan) - 1))]
    if is_junior_level(context.get("difficulty")):
        return junior_stage_question(stage, context)
    skill = _primary_skill(context)
    resume = context.get("resume") or {}
    job = context.get("job") or {}
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
    decision_title = str(details.get("decisionOneTitle") or "").strip()
    decision_alt = str(details.get("decisionOneAlternative") or "").strip()
    test_coverage = str(details.get("testCoverage") or "测试范围")
    evidence_available = str(details.get("evidenceAvailable") or "可提供的证据")
    unmeasured = str(details.get("unmeasuredData") or "没有统计的数据")
    known_limitations = str(details.get("knownLimitations") or "已知问题")
    scale_plan = str(details.get("scalePlan") or "规模扩大后的调整方案")
    title = str(job.get("title") or resume.get("targetRole") or "目标岗位")
    company = str(job.get("company") or "目标公司")
    city = str(resume.get("city") or "目标城市")

    if decision_title:
        decision_question = f"你在“{project}”里当时为什么选择“{decision_title}”"
        if decision_alt:
            decision_question += f"，而没有采用“{decision_alt}”"
        decision_question += "？主要是受什么条件限制，后来有没有发现副作用？"
    else:
        decision_question = f"你在“{project}”里做过什么技术选择？当时为什么选它，有没有考虑其他做法？"

    technical_questions = [
        f"请围绕“{project}”说明你的责任边界。你担任{role}；证据卡中你填写独立完成了：{owned}；并明确“{excluded}”不是你负责的。请挑其中一项，讲清你亲手写了什么、与谁协作，以及你当时能决定到什么程度。",
        f"请选择一条与岗位职责「{responsibility}」最接近的调用链，并讲成一次完整请求：从“{entry}”进入，经过“{processing}”，造成“{data_change}”，最后以“{response}”返回。请重点说明你负责的环节，以及任何一环失败时如何处理。",
        f"围绕岗位必备技能 {skill}，请以核心接口“{core_api}”为例，说明请求与返回、参数校验、权限、异常，以及“{core_tables}”中的状态如何从“{state_transitions}”流转。证据卡中的接口契约是：{api_contract}",
        f"结合岗位场景「{scenario}」，请复盘证据卡中的故障：“{fault_symptom}”。你当时如何定位？根因是“{fault_diagnosis}”吗？修复与验证是“{fault_fix}”吗？请说明排查顺序和你实际执行的步骤。",
        decision_question,
        f"请说明结果与证据。你填写的测试范围是“{test_coverage}”，可提供证据是“{evidence_available}”，没有统计的是“{unmeasured}”。请区分哪些结果属于个人贡献，哪些只是团队或流程验证。",
        f"结合岗位场景「{scenario}」，请补强证据卡中最薄弱的一处，尤其是个人贡献、接口边界或故障验证。你填写的已知问题是：“{known_limitations}”。",
        f"如果项目规模扩大10倍，请基于“{scale_plan}”继续说明：你先改哪一层，为什么先改这里，如何验证改造后没有破坏现有业务？",
    ]
    technical_by_stage = {
        "项目职责": technical_questions[0],
        "项目概述": f"请用两分钟介绍“{project}”：项目解决什么问题、团队如何分工、你本人负责哪一部分，以及目前完成到什么程度。",
        "业务链路": technical_questions[1],
        "技术实现": technical_questions[2],
        "故障与取舍": technical_questions[3] + " 同时说明其中一个关键方案取舍。",
        "故障与边界": technical_questions[3],
        "方案取舍": technical_questions[4],
        "结果验证": technical_questions[5],
        "技术判断": technical_questions[4],
        "证据补强": technical_questions[6],
        "迁移验证": technical_questions[7],
    }
    if stage in technical_by_stage:
        return technical_by_stage[stage]

    story_bank = context.get("story_bank") or []
    recent_story_title = str((story_bank[0] or {}).get("title") or "").strip() if story_bank else ""
    behavior_questions = {
        "求职动机": f"你为什么选择“{title}”这个方向？结合你目前的技术积累，说明这个岗位最吸引你的一点，以及你希望入职后前六个月学到什么。",
        "个人背景": f"请用两分钟介绍你的技术方向和一个与“{title}”最相关的项目，重点说明你具体负责什么、做出了什么结果。",
        "团队协作": f"在“{project}”里，讲一次你和队友分工不一致、意见冲突或需要互相补位的经历。你具体如何沟通，最后怎样推进？",
        "冲突处理": "讲一次你的方案或工作被同学、老师或队友质疑的经历。你当时如何回应、如何判断谁对，最后结果是什么？",
        "失败与学习": "讲一次你在学习或项目中失败、返工或判断错误的经历。根因是什么，你后来改变了什么做法，之后有没有验证改进？",
        "岗位意愿": f"你为什么选择“{company}”的“{title}”？请同时说明你对工作地点“{city}”、到岗时间和工作安排的接受程度。",
        "职业规划": "未来两到三年，你希望成为什么方向的工程师？这个岗位在你的成长路径中承担什么作用？",
        "推动改变": "讲一次你发现团队流程或项目做法效率不高，并主动推动改变的经历。你如何争取支持，遇到了什么阻力？",
        "压力与优先级": "当多个任务同时压来、时间不够时，你通常如何排优先级？请用一个真实经历说明你放弃了什么、保证了什么。",
        "候选人反问": "本场面试接近尾声。你有什么问题想反问面试官？可以问岗位职责、团队协作、成长反馈或技术环境。",
    }
    question = behavior_questions.get(
        stage,
        f"请结合“{project}”和岗位职责“{responsibility}”，说明一个最能证明你能力的真实经历。",
    )
    if recent_story_title and stage in {"团队协作", "冲突处理", "失败与学习", "压力与优先级"}:
        question += f" 你在之前的面试中讲过“{recent_story_title}”，如果这次是同一件事，请换一个不同的例子。"
    return question

class InterviewAgent:
    def __init__(self) -> None:
        self.llm_enabled = settings.llm_enabled
        self._checkpointer_context = None
        self.checkpointer_backend = "memory"
        self.checkpointer = MemorySaver()
        if settings.state_persistence:
            try:
                self._checkpointer_context = PostgresSaver.from_conn_string(settings.database_url)
                self.checkpointer = self._checkpointer_context.__enter__()
                self.checkpointer.setup()
                self.checkpointer_backend = "postgres"
            except Exception as exc:  # noqa: BLE001 - keep the interview available
                logger.warning("PostgreSQL checkpointer unavailable; using MemorySaver: %s", exc)
                self._checkpointer_context = None
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
            "stateBackend": self.checkpointer_backend,
        }
    def start(self, session_id: str, resume: dict[str, Any], job: dict[str, Any], difficulty: str,
              interview_mode: str = "综合面",
              project_evidence: dict[str, Any] | None = None,
              job_blueprint: dict[str, Any] | None = None,
              story_bank: list[dict[str, Any]] | None = None,
              interview_plan: list[str] | tuple[str, ...] | None = None) -> dict[str, Any]:
        mode = _normalize_mode(interview_mode)
        adaptive = True
        required = _required_stages(mode) if adaptive else (tuple(interview_plan) if interview_plan else _mode_plan(mode))
        optional = _optional_stages(mode) if adaptive else ()
        plan = tuple(required)
        evidence = project_evidence or {}
        interview_resume = self._focus_resume_project(resume or {}, evidence)
        set_context(session_id, {
            "resume": interview_resume,
            "job": job or {},
            "project_evidence": evidence,
            "job_blueprint": job_blueprint or {},
            "story_bank": story_bank or [],
            "difficulty": difficulty or "标准",
            "interview_mode": mode,
            "adaptive_route": adaptive,
            "interview_plan": list(plan),
            "required_stages": list(required),
            "optional_stages": list(optional),
            "stage_route": [plan[0]],
            "visited_stages": [plan[0]],
            "pending_stage": plan[0],
            "target_rounds": len(required) if adaptive else settings.max_rounds,
            "route_reason": "开场先确认候选人背景与岗位方向",
            "current_stage": plan[0],
            "round": 1,
            "max_rounds": settings.max_rounds,
            "memory": [],
            "history": [],
            "undo_stack": [],
        })
        if self.agent is not None:
            prompt = (
                f"开始一场新的模拟面试。session_id={session_id}。"
                f"本场面试类型：{mode}。面试类型说明：{INTERVIEW_MODE_DESCRIPTIONS[mode]}。"
                f"本场 adaptive_route：{str(adaptive).lower()}。"
                f"本场必须覆盖的 required_stages：{json.dumps(list(required), ensure_ascii=False)}。"
                f"本场可选追问池 optional_stages：{json.dumps(list(optional), ensure_ascii=False)}。"
                f"当前第一轮 next_stage：{plan[0]}。"
                f"本场唯一允许讨论的项目是：{evidence.get('projectName') or '证据卡项目'}。"
                f"本场结构化项目证据是：{json.dumps(evidence.get('evidenceDetails') or {}, ensure_ascii=False)}。"
                f"本场 JD 面试蓝图：{json.dumps(job_blueprint or {}, ensure_ascii=False)}。"
                f"候选人跨场次故事库：{json.dumps((story_bank or [])[:8], ensure_ascii=False)}。"
                f"本场 difficulty 原值：{difficulty or '标准'}。"
                f"本场目标级别与提问要求：{_level_guidance(difficulty)}。"
                "如果本场是初级，请先调用 get_junior_interview_reference，读取岗位方向级问题，再读取会话上下文、岗位要求和结构化项目证据卡。"
                "只提出 next_stage 对应的问题，stage 字段必须填写 next_stage。"
                "adaptive_route=true 时不要按固定八轮顺序出题；required_stages 只定义底线，具体顺序由后续回答证据决定。"
                "如果 next_stage 不是项目或技术维度，不要强行加入技术细节；如果是项目维度，应引用项目名称和证据卡事实。"
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
        stage = _expected_stage(context, "start")
        project_stages = {"项目职责", "项目概述", "业务链路", "技术实现", "故障与取舍", "故障与边界", "方案取舍", "结果验证", "技术判断", "证据补强", "迁移验证"}
        if project_name and stage in project_stages and project_name not in question:
            result = dict(result)
            result["question"] = _stage_question(int(context.get("round", 1)) - 1, context)
            result["stage"] = stage
            result["toolCalls"] = list(result.get("toolCalls") or []) + ["project_evidence_guard"]
        return result
    def answer(self, session_id: str, answer: str) -> dict[str, Any]:
        context = get_context(session_id)
        snapshot = copy.deepcopy({
            "round": context.get("round", 1),
            "current_stage": context.get("current_stage", ""),
            "pending_stage": context.get("pending_stage", ""),
            "history": context.get("history", []),
            "memory": context.get("memory", []),
            "stage_route": context.get("stage_route", []),
            "visited_stages": context.get("visited_stages", []),
            "target_rounds": context.get("target_rounds", context.get("max_rounds", settings.max_rounds)),
            "route_reason": context.get("route_reason", ""),
            "last_evaluation": context.get("last_evaluation"),
            "done": False,
        })
        stack = context.setdefault("undo_stack", [])
        stack.append(snapshot)
        del stack[:-8]
        context["answer"] = answer
        context.setdefault("history", []).append({"role": "candidate", "content": answer})
        current_round = int(context.get("round", 1))
        current_stage = str(context.get("current_stage") or _planned_stage(context, current_round))
        if context.get("adaptive_route"):
            rubric = tool_result(evaluate_candidate_answer, {"session_id": session_id, "answer": answer})
            context["last_evaluation"] = rubric
            next_stage, route_reason = select_next_junior_stage(
                context,
                rubric,
                context.get("required_stages") or _required_stages(context.get("interview_mode")),
                context.get("optional_stages") or _optional_stages(context.get("interview_mode")),
                int(context.get("max_rounds", settings.max_rounds)),
            )
            context["pending_stage"] = next_stage
            context["route_reason"] = route_reason
        else:
            next_stage = "finish" if current_round >= int(context.get("max_rounds", settings.max_rounds)) else _planned_stage(context, current_round + 1)
            context["pending_stage"] = next_stage
            context["route_reason"] = "固定模式按预设维度推进"
        if self.agent is not None:
            prompt = (
                f"候选人第 {current_round} 轮回答如下：\n<<<{answer}>>>\n"
                f"本场面试类型：{_normalize_mode(context.get('interview_mode'))}。"
                f"本场 difficulty：{context.get('difficulty', '标准')}。"
                f"本轮已问维度：{current_stage}。下一轮 next_stage：{next_stage}。"
                f"本次自适应选择原因：{context.get('route_reason', '')}。"
                f"已经访问维度：{json.dumps(context.get('visited_stages') or [], ensure_ascii=False)}。"
                "如果是初级，请调用 get_junior_interview_reference 和 evaluate_candidate_answer，按初级标准判断个人实现、边界、测试、调试和学习，不要用架构词数量代替证据。"
                "评分必须符合本轮维度：技术/项目维度看正确性和证据；行为/HR维度看真实经历、行动、沟通、反思和结果。"
                "下一问必须严格围绕 next_stage；如果是 finish，question 返回空字符串。"
                "已经访问过的维度不能重复；本题优先使用当前岗位方向的方向级问法。"
            )
            return self._invoke_llm(session_id, prompt, phase="answer")
        return self._rule_answer(session_id, answer)

    def undo(self, session_id: str) -> dict[str, Any]:
        context = get_context(session_id)
        stack = context.get("undo_stack") or []
        if not stack:
            raise ValueError("还没有可撤回的回答")
        snapshot = stack.pop()
        for key, value in snapshot.items():
            context[key] = value
        context["answer"] = ""
        context["done"] = False
        context["route_reason"] = "已撤回上一轮回答，恢复到上一题"
        context.pop("last_evaluation", None)
        if hasattr(self, "checkpointer") and self.checkpointer is not None:
            try:
                self.checkpointer.delete_thread(str(session_id))
            except Exception as exc:  # noqa: BLE001 - DB state remains authoritative
                logger.warning("Could not clear LangGraph thread after undo: %s", exc)
        persist_context(session_id)
        return {
            "undone": True,
            "round": int(context.get("round", 1)),
            "currentStage": context.get("current_stage", ""),
            "targetRounds": int(context.get("target_rounds", settings.max_rounds)),
            "route": list(context.get("stage_route") or []),
            "routeReason": context.get("route_reason", ""),
        }

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
        requested_done = bool(parsed.get("done", False))
        context = get_context(session_id)
        if phase == "answer" and is_junior_level(context.get("difficulty")):
            rubric = context.get("last_evaluation") or tool_result(evaluate_candidate_answer, {
                "session_id": session_id,
                "answer": str(context.get("answer") or ""),
            })
            rubric_score = int(rubric.get("score", 45))
            llm_score = None
            if score is not None:
                try:
                    llm_score = int(score)
                except (TypeError, ValueError):
                    llm_score = None
            score = reconcile_junior_score(llm_score, rubric_score)
            if not feedback:
                feedback = str(rubric.get("feedback") or "")
            tool_calls.append("junior_score_reconcile")
        elif score is not None:
            try:
                score = int(score)
            except (TypeError, ValueError):
                score = None
        if score is not None:
            context.setdefault("history", []).append({"role": "assistant", "score": score, "feedback": feedback})
        for note in parsed.get("memory", []) or []:
            tool_result(save_interview_memory, {"session_id": session_id, "note": str(note)})

        expected_stage = _expected_stage(context, phase)
        question, done, round_number, max_rounds = _enforce_round_policy(context, phase, question, requested_done)
        reported_stage = str(parsed.get("stage") or "").strip()
        if not done:
            if not question or not _stage_matches(reported_stage, expected_stage):
                question = _stage_question(0 if phase == "start" else int(context.get("round", 1)), context)
                tool_calls.append("adaptive_stage_guard")
            if is_junior_level(context.get("difficulty")):
                guarded_question, guard_reasons = junior_guard_question(question, expected_stage, context)
                if guarded_question != question:
                    question = guarded_question
                    tool_calls.append("junior_question_guard")
                if guard_reasons:
                    tool_calls.extend("junior_guard:" + reason for reason in guard_reasons)
            context["current_stage"] = expected_stage
        if done:
            context["done"] = True
            context["current_stage"] = "finish"
            context["target_rounds"] = round_number
        elif phase == "answer":
            context["round"] = round_number + 1
            route = context.setdefault("stage_route", [])
            visited = context.setdefault("visited_stages", [])
            if expected_stage not in route:
                route.append(expected_stage)
            if expected_stage not in visited:
                visited.append(expected_stage)
        persist_context(session_id)
        return {
            "question": question,
            "score": score,
            "feedback": feedback,
            "stage": "finish" if done else expected_stage,
            "done": done,
            "round": int(context.get("round", 1)),
            "targetRounds": int(context.get("target_rounds", max_rounds)),
            "route": list(context.get("stage_route") or []),
            "toolCalls": tool_calls,
            "memory": context.get("memory", []),
            "reasoningSummary": (
                str(parsed.get("reasoning_summary") or "")
                + (f"；自适应路由：{context.get('route_reason', '')}" if context.get("adaptive_route") else "")
            ).strip("；"),
        }

    def _rule_start(self, session_id: str) -> dict[str, Any]:
        context = get_context(session_id)
        stage = str(context.get("pending_stage") or (context.get("stage_route") or ["个人背景"])[0])
        context["current_stage"] = stage
        tool_result(read_session_context, {"session_id": session_id})
        tool_calls = ["read_session_context", "search_real_interview_patterns", "save_interview_memory"]
        job_skill = _primary_skill(context)
        tool_result(search_real_interview_patterns, {
            "session_id": session_id,
            "query": f"{stage} {job_skill} {context.get('interview_mode', '综合面')}",
            "stage": stage,
            "limit": 3,
        })
        if stage in TECHNICAL_STAGES:
            tool_result(search_interview_questions_qdrant, {
                "session_id": session_id,
                "query": job_skill + " 基础原理 使用场景 易错点",
                "limit": 3,
            })
            tool_calls.append("search_interview_questions_qdrant")
        question = _stage_question(0, context)
        tool_result(save_interview_memory, {
            "session_id": session_id,
            "note": f"面试类型：{context.get('interview_mode', '综合面')}；目标岗位：{(context.get('job') or {}).get('title', '未指定')}",
        })
        persist_context(session_id)
        return {
            "question": question,
            "score": None,
            "feedback": "",
            "stage": stage,
            "done": False,
            "round": 1,
            "targetRounds": int(context.get("target_rounds", settings.max_rounds)),
            "route": list(context.get("stage_route") or []),
            "toolCalls": tool_calls,
            "memory": context.get("memory", []),
            "reasoningSummary": f"本地规划器开始自适应面试，第一问进入{stage}。",
        }

    def _rule_answer(self, session_id: str, answer: str) -> dict[str, Any]:
        context = get_context(session_id)
        evaluation = context.get("last_evaluation") or tool_result(evaluate_candidate_answer, {"session_id": session_id, "answer": answer})
        score = int(evaluation.get("score", 50))
        feedback = evaluation.get("feedback", "")
        current_round = int(context.get("round", 1))
        max_rounds = int(context.get("max_rounds", settings.max_rounds))
        pending = str(context.get("pending_stage") or "")
        if not pending:
            if context.get("adaptive_route"):
                pending, reason = select_next_junior_stage(
                    context,
                    evaluation,
                    context.get("required_stages") or _required_stages(context.get("interview_mode")),
                    context.get("optional_stages") or _optional_stages(context.get("interview_mode")),
                    max_rounds,
                )
                context["route_reason"] = reason
            else:
                pending = "finish" if current_round >= max_rounds else _planned_stage(context, current_round + 1)
            context["pending_stage"] = pending

        done = pending == "finish" or current_round >= max_rounds
        next_question = ""
        stage = "finish" if done else pending
        tool_calls = ["evaluate_candidate_answer", "get_interview_progress"]
        if not done:
            context["round"] = current_round + 1
            context["current_stage"] = stage
            route = context.setdefault("stage_route", [])
            visited = context.setdefault("visited_stages", [])
            if stage not in route:
                route.append(stage)
            if stage not in visited:
                visited.append(stage)
            tool_result(search_real_interview_patterns, {
                "session_id": session_id, "query": answer[:300], "stage": stage, "limit": 3,
            })
            tool_calls.append("search_real_interview_patterns")
            if stage in TECHNICAL_STAGES:
                tool_result(search_interview_questions_qdrant, {
                    "session_id": session_id, "query": answer[:300], "limit": 4,
                })
                tool_calls.append("search_interview_questions_qdrant")
            next_question = _stage_question(current_round, context)
        else:
            context["done"] = True
            context["current_stage"] = "finish"
            context["target_rounds"] = current_round
        notes = evaluation.get("gaps") or []
        for note in notes[:2]:
            tool_result(save_interview_memory, {"session_id": session_id, "note": str(note)})
        tool_calls.append("save_interview_memory")
        context.setdefault("history", []).append({"role": "assistant", "score": score, "feedback": feedback})
        persist_context(session_id)
        return {
            "question": next_question,
            "score": score,
            "feedback": feedback,
            "stage": stage,
            "done": done,
            "round": int(context.get("round", 1)),
            "targetRounds": int(context.get("target_rounds", max_rounds)),
            "route": list(context.get("stage_route") or []),
            "toolCalls": tool_calls,
            "memory": context.get("memory", []),
            "reasoningSummary": f"本地规划器根据回答证据选择下一维度：{context.get('route_reason', '')}。",
        }

    def snapshot(self, session_id: str) -> dict[str, Any]:
        context = get_context(session_id)
        return {
            "sessionId": session_id,
            "round": context.get("round", 1),
            "maxRounds": context.get("max_rounds", settings.max_rounds),
            "interviewMode": context.get("interview_mode", "综合面"),
            "currentStage": context.get("current_stage", ""),
            "plan": context.get("stage_route") or context.get("interview_plan", []),
            "requiredStages": context.get("required_stages", []),
            "optionalStages": context.get("optional_stages", []),
            "routeReason": context.get("route_reason", ""),
            "targetRounds": context.get("target_rounds", context.get("max_rounds", settings.max_rounds)),
            "memory": context.get("memory", []),
            "history": context.get("history", []),
            "done": bool(context.get("done", False)),
        }