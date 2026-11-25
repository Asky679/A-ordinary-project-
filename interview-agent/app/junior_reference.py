from __future__ import annotations

import json
import re
from collections import defaultdict
from pathlib import Path
from typing import Any


REFERENCE_PATH = Path(__file__).resolve().parents[1] / "data" / "junior-engineer" / "reference.json"


def _load_reference() -> dict[str, Any]:
    try:
        return json.loads(REFERENCE_PATH.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}


JUNIOR_REFERENCE = _load_reference()
JUNIOR_LEVEL_MARKERS = ("初级", "实习", "校招", "应届", "entry", "junior")
ADVANCED_TERMS = (
    "高并发", "大规模分布式", "异地多活", "分库分表", "跨团队治理",
    "组织级", "架构治理", "Service Mesh", "技术选型体系", "容量规划",
)
DIRECTION_KEYWORDS: dict[str, tuple[str, ...]] = {
    "前端": ("前端", "javascript", "typescript", "vue", "react", "css", "html", "小程序"),
    "测试": ("测试", "qa", "selenium", "自动化测试", "测开"),
    "运维": ("运维", "devops", "sre", "linux", "docker", "kubernetes", "k8s", "nginx"),
    "数据/AI": ("数据", "算法", "机器学习", "深度学习", "大模型", "llm", "ai", "pytorch", "tensorflow", "nlp"),
    "嵌入式/C++": ("嵌入式", "单片机", "c++", "硬件", "驱动", "rtos", "stm32"),
    "网络安全": ("安全", "渗透", "漏洞", "owasp", "等保", "攻防"),
    "Go后端": ("golang", " go ", "go语言"),
    "Java后端": ("java", "spring", "mybatis", "后端", "backend"),
}


def is_junior_level(difficulty: str | None) -> bool:
    value = str(difficulty or "").lower()
    return any(marker in value for marker in JUNIOR_LEVEL_MARKERS)


def infer_direction(context: dict[str, Any]) -> str:
    job = context.get("job") or {}
    resume = context.get("resume") or {}
    title_text = " ".join([
        str(job.get("title") or ""),
        str(resume.get("targetRole") or ""),
    ]).lower()
    for direction, keywords in DIRECTION_KEYWORDS.items():
        if any(keyword in title_text for keyword in keywords):
            return direction
    skill_text = " ".join([
        " ".join(str(item) for item in (job.get("skills") or [])),
        " ".join(str(item) for item in (resume.get("skills") or [])),
    ]).lower()
    for direction, keywords in DIRECTION_KEYWORDS.items():
        if any(keyword in skill_text for keyword in keywords):
            return direction
    full_text = " ".join([
        title_text,
        skill_text,
        str(job.get("description") or ""),
        str(job.get("jobRequirements") or ""),
    ]).lower()
    for direction, keywords in DIRECTION_KEYWORDS.items():
        if any(keyword in full_text for keyword in keywords):
            return direction
    return "Java后端"


def _defaults(context: dict[str, Any]) -> dict[str, str]:
    resume = context.get("resume") or {}
    job = context.get("job") or {}
    evidence = context.get("project_evidence") or {}
    details = evidence.get("evidenceDetails") or {}
    blueprint = context.get("job_blueprint") or {}
    projects = resume.get("projects") or []
    fallback_project = "你的代表项目"
    if projects and isinstance(projects[0], dict):
        fallback_project = str(projects[0].get("name") or fallback_project)
    project = str(evidence.get("projectName") or fallback_project)
    skill = str(
        (blueprint.get("mustHaveSkills") or [None])[0]
        or (job.get("skills") or [None])[0]
        or "目标技能"
    )
    ownership = str(details.get("ownedModules") or evidence.get("implementation") or "你本人负责的模块")
    return {
        "project": project,
        "skill": skill,
        "owned": ownership,
        "decision_title": str(details.get("decisionOneTitle") or "").strip(),
        "decision_alt": str(details.get("decisionOneAlternative") or "").strip(),
        "test_coverage": str(details.get("testCoverage") or "你的测试范围"),
    }


def _render(template: str, values: dict[str, str]) -> str:
    safe = defaultdict(str, values)
    try:
        return template.format_map(safe)
    except (KeyError, ValueError):
        return template



_STAGE_ALIASES = {
    "self_intro": "个人背景",
    "project": "项目职责",
    "backend": "技术实现",
    "python": "技术实现",
    "system": "故障与边界",
    "behavior": "团队协作",
    "motivation": "求职动机",
    "conflict": "冲突处理",
    "failure": "失败与学习",
    "career": "职业规划",
    "reverse": "候选人反问",
    "技术判断": "方案取舍",
    "证据补强": "结果验证",
    "迁移验证": "方案取舍",
    "推动改变": "团队协作",
}
_TECHNICAL_STAGES = {
    "项目职责", "项目概述", "业务链路", "技术实现", "基础概念", "故障与取舍",
    "故障与边界", "方案取舍", "结果验证", "技术判断", "证据补强", "迁移验证", "错误原因",
}


def _canonical_stage(stage: str) -> str:
    return _STAGE_ALIASES.get(stage, stage)


def _direction_stage_candidates(stage: str, context: dict[str, Any]) -> list[str]:
    direction = infer_direction(context)
    patterns = ((JUNIOR_REFERENCE.get("direction_stage_patterns") or {}).get(direction) or {}).get(stage) or []
    values = _defaults(context)
    return [_render(str(item), values) for item in patterns if str(item).strip()]


def junior_question_candidates(
    stage: str,
    context: dict[str, Any],
    keyword: str = "",
    limit: int = 6,
) -> list[str]:
    pattern_stage = _canonical_stage(stage)
    values = _defaults(context)
    direction = infer_direction(context)
    direction_stage = _direction_stage_candidates(pattern_stage, context)
    patterns = (JUNIOR_REFERENCE.get("stage_patterns") or {}).get(pattern_stage) or []
    keyword = (keyword or "").strip()
    keyword_questions = []
    if keyword:
        keyword_questions = [
            f"请先用一两句话说明 {keyword} 是什么，再结合项目说你实际怎么用。",
            f"如果使用 {keyword} 的这段代码遇到空值、重复操作或超时，会发生什么？",
            f"你当时怎么测试或确认 {keyword} 这部分确实工作正常？",
        ]
    candidates = keyword_questions + direction_stage + [_render(str(item), values) for item in patterns]
    direction_patterns = (JUNIOR_REFERENCE.get("direction_patterns") or {}).get(direction) or []
    if direction_patterns:
        focus = str(direction_patterns[(int(context.get("round", 1)) - 1) % len(direction_patterns)])
        candidates.append(f"请结合你的项目，讲一个和“{focus}”有关的真实做法。你当时具体做了什么，怎么验证？")
    voice = JUNIOR_REFERENCE.get("real_interview_voice") or []
    if pattern_stage in {"项目职责", "业务链路", "技术实现", "故障与边界", "故障与取舍"} and voice:
        candidates.append(str(voice[(int(context.get("round", 1)) - 1) % len(voice)]))
    deduped: list[str] = []
    for question in candidates:
        if question and question not in deduped:
            deduped.append(question)
    return deduped[:max(1, limit)]


def junior_stage_question(stage: str, context: dict[str, Any]) -> str:
    pattern_stage = _canonical_stage(stage)
    direction_stage = _direction_stage_candidates(pattern_stage, context)
    if direction_stage:
        return direction_stage[0]
    values = _defaults(context)
    raw_patterns = (JUNIOR_REFERENCE.get("stage_patterns") or {}).get(pattern_stage) or []
    candidates = [_render(str(item), values) for item in raw_patterns]
    if candidates:
        offset = sum(ord(char) for char in stage) % len(candidates)
        return candidates[offset]
    candidates = junior_question_candidates(stage, context, limit=4)
    if not candidates:
        return "请挑一个你亲手完成的功能，说明你具体做了什么、输入输出是什么，以及你怎么验证。"
    return candidates[0]


def select_next_junior_stage(
    context: dict[str, Any],
    evaluation: dict[str, Any],
    required_stages: list[str] | tuple[str, ...],
    optional_stages: list[str] | tuple[str, ...],
    max_rounds: int,
) -> tuple[str, str]:
    """Choose the next dimension from answer evidence rather than a fixed round table."""
    current_stage = str(context.get("current_stage") or "")
    route = list(context.get("stage_route") or context.get("visited_stages") or [])
    visited = set(context.get("visited_stages") or route)
    if current_stage and current_stage not in visited:
        visited.add(current_stage)
    round_number = int(context.get("round", 1))
    answer = str(context.get("answer") or "")
    score = int(evaluation.get("score") or 55)
    gaps = [str(item) for item in (evaluation.get("gaps") or [])]
    gap_text = "；".join(gaps)
    required = [str(item) for item in required_stages]
    optional = [str(item) for item in optional_stages]
    reverse = "候选人反问"

    if current_stage == reverse:
        return "finish", "候选人反问已回答，本场结束"

    if score >= 84:
        desired_rounds = 6
    elif score >= 74:
        desired_rounds = 7
    elif score >= 64:
        desired_rounds = 8
    elif score >= 55:
        desired_rounds = 9
    else:
        desired_rounds = 10
    desired_rounds = max(6, len(required), min(int(max_rounds), desired_rounds))
    context["target_rounds"] = desired_rounds

    if round_number >= int(max_rounds) - 1:
        return reverse, "达到最大轮次上限，保留候选人反问后结束"

    remaining_required = [stage for stage in required if stage not in visited and stage != reverse]
    if score < 58 and "错误原因" in optional and "错误原因" not in visited and round_number < desired_rounds - 1:
        return "错误原因", "回答与预期存在明显偏差，先追问理解依据和错误原因，再进入下一维度"
    if current_stage in {"个人背景", "求职动机", "项目概述"} and "项目职责" in remaining_required:
        return "项目职责", "先确认候选人本人做了什么，再进入后续技术追问"

    gap_priority: list[tuple[tuple[str, ...], str, str]] = [
        (("个人贡献", "责任边界"), "项目职责", "上一轮没有讲清个人贡献"),
        (("没有说明如何验证", "测试", "验证"), "结果验证", "上一轮缺少测试或验证证据"),
        (("边界", "异常", "空值", "重复", "超时"), "故障与边界", "上一轮没有说明边界或异常处理"),
        (("架构类词", "缺少具体证据"), "证据补强", "回答出现概括性词汇但缺少项目证据"),
    ]
    for terms, stage, reason in gap_priority:
        if stage in remaining_required and any(term in gap_text for term in terms):
            return stage, reason

    if remaining_required:
        if score < 62 and "基础概念" in optional and "基础概念" not in visited and round_number < desired_rounds - 1:
            return "基础概念", "回答基础信息不足，先补一个基础概念再继续项目追问"
        seed = sum(ord(char) for char in answer[:120]) + round_number * 17 + score
        if current_stage == "项目职责":
            phase_one = [stage for stage in ("业务链路", "技术实现") if stage in remaining_required]
            if phase_one:
                return phase_one[seed % len(phase_one)], "先展开业务链路或技术实现，具体先问哪一项取决于当前回答"
        if current_stage in {"业务链路", "技术实现"}:
            other = "技术实现" if current_stage == "业务链路" else "业务链路"
            if other in remaining_required:
                return other, "补齐项目业务链路与技术实现两项核心证据"
            if "故障与边界" in remaining_required:
                return "故障与边界", "核心技术链路已覆盖，继续验证异常与边界意识"
        if current_stage == "故障与边界" and "结果验证" in remaining_required:
            return "结果验证", "故障边界之后验证测试、结果与证据"
        stage = remaining_required[seed % len(remaining_required)]
        return stage, "按当前回答缺口和本场覆盖要求选择下一维度"

    if round_number + 1 < desired_rounds and score < 82:
        optional_remaining = [stage for stage in optional if stage not in visited]
        if optional_remaining:
            preferred: str | None = None
            if "架构类词" in gap_text and "方案取舍" in optional_remaining:
                preferred = "方案取舍"
            elif score < 62 and "基础概念" in optional_remaining:
                preferred = "基础概念"
            elif "验证" in gap_text and "证据补强" in optional_remaining:
                preferred = "证据补强"
            seed = sum(ord(char) for char in answer[:120]) + round_number * 23 + score
            return preferred or optional_remaining[seed % len(optional_remaining)], "回答仍有证据缺口，追加一个针对性追问"

    return reverse, "核心维度已经覆盖，进入候选人反问"



def junior_guard_question(
    question: str,
    stage: str,
    context: dict[str, Any],
) -> tuple[str, list[str]]:
    text = re.sub(r"\s+", " ", str(question or "")).strip()
    reasons: list[str] = []
    advanced = [term for term in ADVANCED_TERMS if term in text]
    if advanced and not any(marker in text for marker in ("你项目", "你实际", "怎么用", "如何验证", "复现", "边界")):
        reasons.append("advanced-topic-without-project-evidence:" + ",".join(advanced))
    if text.count("？") + text.count("?") > 1:
        reasons.append("multiple-questions-in-one-turn")
    if len(text) > 220:
        reasons.append("question-too-long")
    placeholders = ("你的一个技术选择", "另一个备选方案", "一个关键技术决策", "未采用的替代方案",
                    "第二个技术决策", "第二个替代方案")
    if any(term in text for term in placeholders):
        reasons.append("placeholder-phrase")
    if reasons:
        return junior_stage_question(stage, context), reasons
    return text, reasons


def junior_reference_payload(context: dict[str, Any], query: str = "", direction: str = "") -> dict[str, Any]:
    selected_direction = direction if direction in (JUNIOR_REFERENCE.get("direction_patterns") or {}) else infer_direction(context)
    level_rules = JUNIOR_REFERENCE.get("level_rules") or {}
    company_patterns = JUNIOR_REFERENCE.get("company_size_patterns") or {}
    return {
        "version": JUNIOR_REFERENCE.get("version"),
        "researchSummary": JUNIOR_REFERENCE.get("research_summary") or {},
        "selectedLevel": "初级",
        "direction": selected_direction,
        "levelRules": level_rules.get("初级") or {},
        "otherLevelBoundaries": {
            "中级": level_rules.get("中级") or {},
            "高级": level_rules.get("高级") or {},
            "架构师": level_rules.get("架构师") or {},
        },
        "stagePatterns": JUNIOR_REFERENCE.get("stage_patterns") or {},
        "directionPatterns": (JUNIOR_REFERENCE.get("direction_patterns") or {}).get(selected_direction) or [],
        "directionStagePatterns": (JUNIOR_REFERENCE.get("direction_stage_patterns") or {}).get(selected_direction) or {},
        "basicConceptAreas": JUNIOR_REFERENCE.get("basic_concept_areas") or {},
        "interviewRhythm": JUNIOR_REFERENCE.get("interview_rhythm") or {},
        "companySizePatterns": company_patterns,
        "scoringPolicy": JUNIOR_REFERENCE.get("scoring_policy") or {},
        "officialEvidence": (JUNIOR_REFERENCE.get("official_evidence") or [])[:6],
        "realInterviewVoice": JUNIOR_REFERENCE.get("real_interview_voice") or [],
        "query": query,
        "usageNote": "初级问题必须锚定真实项目或基础概念，一次只问一个主要问题；优先使用当前岗位方向的方向级问题，并根据上一轮证据缺口动态选择下一维度。",
    }


def reconcile_junior_score(llm_score: int | None, rubric_score: int) -> int:
    """For fresh graduates, a concrete junior rubric acts as a floor, not a ceiling."""
    rubric = max(45, min(94, int(rubric_score)))
    if llm_score is None:
        return rubric
    try:
        model = int(llm_score)
    except (TypeError, ValueError):
        return rubric
    return max(45, min(94, max(model, rubric)))


def score_junior_answer(answer: str, stage: str, job_skills: list[str], context: dict[str, Any] | None = None) -> dict[str, Any]:
    text = re.sub(r"\s+", " ", str(answer or "")).strip()
    lower = text.lower()
    signals: list[str] = []
    gaps: list[str] = []
    score = 44
    project_name = str((context or {}).get("project_evidence", {}).get("projectName") or "")
    title = str((context or {}).get("job", {}).get("title") or "")
    specific_terms = (
        "项目", "接口", "服务", "数据库", "测试", "用例", "用户", "代码", "日志",
        "Controller", "Service", "PostgreSQL", "Qdrant", "LangGraph", "Java",
        "Spring", "MyBatis", "Redis", "MySQL", "Linux", "API", "部署",
    )
    relevance_hits = [term for term in specific_terms if term.lower() in lower or term in text]
    if project_name and project_name in text:
        relevance_hits.append(project_name)
    if title and title in text:
        relevance_hits.append(title)
    if len(text) >= 50 and len(relevance_hits) >= 2:
        score += 8
    elif len(text) >= 35 and len(relevance_hits) >= 1:
        score += 5
    technical_stages = {
        "项目职责", "项目概述", "业务链路", "技术实现", "故障与取舍",
        "故障与边界", "方案取舍", "结果验证", "技术判断", "证据补强", "迁移验证",
    }
    technical = stage in technical_stages

    personal_terms = (
        "我负责", "我写", "我改", "我实现", "我测试", "我查", "我定位",
        "我修复", "我选择", "我遇到", "我做", "我熟悉", "我学过", "我使用",
    )
    if any(term in text for term in personal_terms):
        score += 10
        signals.append("说明了个人行动")
    else:
        gaps.append("个人贡献不够明确")

    if technical:
        boundary_terms = (
            "空值", "为空", "null", "重复", "重复请求", "超时", "异常", "边界",
            "失败", "不存在", "查不到", "没查到", "返回错误", "越权", "回滚",
        )
        if any(term in lower for term in boundary_terms):
            score += 8
            signals.append("考虑了边界或异常")
        else:
            gaps.append("缺少边界或异常处理")

        verification_terms = (
            "测试", "测了", "跑过", "验证", "验证过", "复现", "回归", "回归过",
            "日志", "断言", "预期", "实际", "用例",
        )
        if any(term in text for term in verification_terms):
            score += 10
            signals.append("有测试或验证过程")
        else:
            gaps.append("没有说明如何验证")

        debugging_terms = ("排查", "定位", "根因", "日志", "报错", "现象", "复现")
        if any(term in text for term in debugging_terms):
            score += 8
            signals.append("描述了排查过程")

        if any(char.isdigit() for char in text) or any(term in text for term in ("截图", "测试记录", "接口返回", "日志")):
            score += 6
            signals.append("给出了可核验证据")

        skill_hits = [skill for skill in job_skills if skill and skill.lower() in lower]
        if skill_hits:
            score += 8
            signals.append("使用了岗位相关技术：" + "、".join(skill_hits[:3]))

        if 40 <= len(text) < 100:
            score += 4
        elif len(text) >= 100:
            score += 6

        buzzwords = [term for term in ("高并发", "分布式", "微服务", "架构设计") if term in text]
        if buzzwords and not any(term in text for term in ("测试", "验证", "复现", "日志", "边界", "指标")):
            score -= 5
            gaps.append("出现架构类词但缺少具体证据")

        if not any(term in text for term in verification_terms):
            score = min(score, 84)
        if not any(term in text for term in personal_terms):
            score = min(score, 78)
    else:
        behavior_hits = [term for term in (
            "沟通", "协调", "冲突", "推进", "反思", "改进", "学习", "复盘", "结果",
            "项目", "团队", "接口", "测试", "负责",
        ) if term in text]
        if behavior_hits:
            score += 10
            signals.append("有具体经历、行动或反思")
        else:
            gaps.append("缺少具体经历或反思")
        if len(text) >= 60:
            score += 6
        if any(term in text for term in ("不会", "没做过", "还没", "正在学", "最不熟悉")):
            score += 5
            signals.append("坦诚说明当前能力边界")

    if any(term in text for term in ("不会", "没做过", "不了解", "不是我负责", "队友负责", "只了解", "没有数据")):
        score += 5
        signals.append("说明了能力或责任边界")

    if len(text) >= 50 and len(relevance_hits) >= 2:
        score = max(score, 65)
    elif len(text) >= 35 and len(relevance_hits) >= 1 and any(term in text for term in personal_terms):
        score = max(score, 62)
    score = max(45, min(94, score))
    feedback = "；".join(signals) if signals else "回答方向可以，但需要补充具体个人行动和验证证据"
    if gaps:
        feedback += "。建议补充：" + "、".join(gaps[:3])
    return {
        "score": score,
        "feedback": feedback,
        "signals": signals,
        "gaps": gaps,
        "evaluationMode": "JUNIOR_TECHNICAL" if technical else "JUNIOR_BEHAVIORAL",
    }
