import httpx
from langchain_openai import ChatOpenAI

from .config import settings


def create_chat_model(*, temperature: float = 0.2) -> ChatOpenAI:
    """Create the OpenAI-compatible model without inheriting broken system proxies.

    The execution environment may set HTTP_PROXY/HTTPS_PROXY to a local placeholder.
    DeepSeek is reachable directly, so the model client must not use that proxy.
    """
    timeout = httpx.Timeout(connect=15.0, read=90.0, write=30.0, pool=15.0)
    http_client = httpx.Client(trust_env=False, timeout=timeout)
    return ChatOpenAI(
        model=settings.openai_model,
        api_key=settings.openai_api_key,
        base_url=settings.openai_base_url,
        temperature=temperature,
        timeout=90,
        max_retries=1,
        http_client=http_client,
    )
