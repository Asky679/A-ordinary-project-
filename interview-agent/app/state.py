from typing import Annotated, Any, TypedDict

from langgraph.graph.message import add_messages


class InterviewState(TypedDict, total=False):
    session_id: str
    phase: str
    round: int
    max_rounds: int
    difficulty: str
    resume: dict[str, Any]
    job: dict[str, Any]
    answer: str
    messages: Annotated[list, add_messages]
    tool_calls: list[str]
    memory: list[str]
    history: list[dict[str, Any]]
    evaluation: dict[str, Any]
    next_question: str
    stage: str
    done: bool