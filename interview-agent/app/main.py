from typing import Any

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from .config import settings
from .diagnosis_graph import DiagnosisAgent
from .graph import InterviewAgent
from .job_graph import JobRecommendationAgent
from .match_graph import MatchAgent
from .tools import set_context

app = FastAPI(title="CareerNav LangGraph Agent Service", version="1.1.0")
agent = InterviewAgent()
job_agent = JobRecommendationAgent()
diagnosis_agent = DiagnosisAgent()
match_agent = MatchAgent()


class StartRequest(BaseModel):
    sessionId: str
    resume: dict[str, Any] = Field(default_factory=dict)
    job: dict[str, Any] = Field(default_factory=dict)
    difficulty: str = "标准"


class AnswerRequest(BaseModel):
    answer: str
    resume: dict[str, Any] | None = None
    job: dict[str, Any] | None = None


class JobAgentRequest(BaseModel):
    resumeId: int
    city: str = ""
    excludedCities: str = ""
    limit: int = 8


class DiagnosisAgentRequest(BaseModel):
    resumeId: int
    jobId: int | None = None


class MatchRequest(BaseModel):
    resume: dict[str, Any] = Field(default_factory=dict)
    jobs: list[dict[str, Any]] = Field(default_factory=list)


@app.get("/health")
def health() -> dict[str, Any]:
    return {"status": "ok", **agent.status(), "jobAgent": job_agent.status(), "diagnosisAgent": diagnosis_agent.status(), "matchAgent": match_agent.status()}


@app.post("/v1/interviews/start")
def start(request: StartRequest) -> dict[str, Any]:
    return agent.start(request.sessionId, request.resume, request.job, request.difficulty)


@app.post("/v1/interviews/{session_id}/answer")
def answer(session_id: str, request: AnswerRequest) -> dict[str, Any]:
    if not request.answer.strip():
        raise HTTPException(status_code=400, detail="answer cannot be empty")
    if request.resume or request.job:
        set_context(session_id, {
            "resume": request.resume or {},
            "job": request.job or {},
        })
    return agent.answer(session_id, request.answer)


@app.get("/v1/interviews/{session_id}")
def snapshot(session_id: str) -> dict[str, Any]:
    return agent.snapshot(session_id)


@app.post("/v1/diagnosis/agent")
def diagnose_jobs(request: JobAgentRequest) -> dict[str, Any]:
    """简历诊断岗位推荐 Agent：读简历 -> 语义召回 -> 算法精排 -> 反思重试 -> 输出报告。

    每个岗位的匹配分都来自 Java 侧的加权算法，模型只负责理解简历和组织结论。
    """
    if request.resumeId is None or request.resumeId <= 0:
        raise HTTPException(status_code=400, detail="resumeId is required")
    return job_agent.run(request.resumeId, request.city, request.excludedCities, request.limit)


@app.post("/v1/diagnosis/analyze")
def analyze_diagnosis(request: DiagnosisAgentRequest) -> dict[str, Any]:
    """简历诊断 Agent：自主决定查哪些证据，返回增强后的诊断建议与工具调用轨迹。"""
    return diagnosis_agent.analyze(request.resumeId, request.jobId)


@app.post("/v1/match/rerank")
def rerank_jobs(request: MatchRequest) -> dict[str, Any]:
    """岗位匹配 Agent：按每个岗位自身的任职要求打分，返回分项与理由。"""
    return match_agent.rerank(request.resume, request.jobs)

