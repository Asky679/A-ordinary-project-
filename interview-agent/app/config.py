import os
from dataclasses import dataclass
from pathlib import Path


def _load_env_file() -> None:
    """Load interview-agent/.env so the service can start without a wrapper script.

    Real process environment variables always win, which keeps container/CI
    overrides working.
    """
    env_path = Path(__file__).resolve().parent.parent / ".env"
    if not env_path.exists():
        return
    try:
        lines = env_path.read_text(encoding="utf-8").splitlines()
    except OSError:
        return
    for raw in lines:
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        if key and key not in os.environ:
            os.environ[key] = value.strip()


_load_env_file()


@dataclass(frozen=True)
class Settings:
    openai_api_key: str
    openai_base_url: str
    openai_model: str
    port: int
    max_rounds: int

    @property
    def llm_enabled(self) -> bool:
        return bool(self.openai_api_key.strip())


def load_settings() -> Settings:
    return Settings(
        openai_api_key=os.getenv("OPENAI_API_KEY", "").strip(),
        openai_base_url=os.getenv("OPENAI_BASE_URL", "https://api.deepseek.com/v1").strip(),
        openai_model=os.getenv("OPENAI_MODEL", "deepseek-chat").strip(),
        port=int(os.getenv("LANGGRAPH_PORT", "8001")),
        max_rounds=int(os.getenv("LANGGRAPH_MAX_ROUNDS", "4")),
    )


settings = load_settings()