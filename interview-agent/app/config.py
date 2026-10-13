import os
from dataclasses import dataclass


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