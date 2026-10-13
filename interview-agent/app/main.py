from typing import Any

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from .config import settings
from .graph import InterviewAgent
from .tools import set_context

app = FastAPI(title="CareerNav LangGraph Interview Agent", version="1.0.0")
agent = InterviewAgent()


class StartRequest(BaseModel):
    sessionId: str
    resume: dict[str, Any] = Field(default_factory=dict)
    job: dict[str, Any] = Field(default_factory=dict)
    difficulty: str = "标准"


class AnswerRequest(BaseModel):
    answer: str
    resume: dict[str, Any] | None = None
    job: dict[str, Any] | None = None


@app.get("/health")
def health() -> dict[str, Any]:
    return {"status": "ok", **agent.status()}


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