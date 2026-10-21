#!/usr/bin/env python3
"""Launch the Spring Boot application with the local infrastructure settings.

The app needs about a dozen environment variables (PostgreSQL, Qdrant, the two
local embedding services, LangGraph, and an LLM key). Setting those by hand in a
shell is error prone, so this launcher:

* reads the DeepSeek/OpenAI settings from `interview-agent/.env`
  (that file is git-ignored, so no key ever lands in the repository),
* fills in the local service addresses,
* starts the packaged jar as a detached background process,
* writes stdout/stderr to `target/app-8080.log` / `target/app-8080.err.log`.

It never prints or writes the API key.
"""
from __future__ import annotations

import os
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAR = ROOT / "target" / "career-navigation-platform-1.0.0.jar"
AGENT_ENV = ROOT / "interview-agent" / ".env"
LOG = ROOT / "target" / "app-8080.log"
ERR_LOG = ROOT / "target" / "app-8080.err.log"

DEFAULTS = {
    "DB_URL": "jdbc:postgresql://127.0.0.1:5432/careernav",
    "DB_USERNAME": "careernav",
    "DB_PASSWORD": "",
    "QDRANT_URL": "http://127.0.0.1:6333",
    "QDRANT_COLLECTION": "careernav_jobs",
    "EMBEDDING_API_KEY": "local",
    "EMBEDDING_BASE_URL": "http://127.0.0.1:5001/v1",
    "EMBEDDING_MODEL": "tfidf-svd-256",
    "EMBEDDING_DIMENSIONS": "256",
    "LANGGRAPH_ENABLED": "true",
    "LANGGRAPH_URL": "http://127.0.0.1:8001",
    "OPENAI_BASE_URL": "https://api.deepseek.com/v1",
    "OPENAI_MODEL": "deepseek-chat",
}


def load_agent_env() -> dict:
    values = {}
    if not AGENT_ENV.exists():
        return values
    for raw in AGENT_ENV.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def main() -> int:
    if not JAR.exists():
        print("missing jar: " + str(JAR))
        print("build it first:  .\\mvnw.cmd -DskipTests package")
        return 1

    env = dict(os.environ)
    env.update(DEFAULTS)
    agent = load_agent_env()
    for key in ("OPENAI_API_KEY", "OPENAI_BASE_URL", "OPENAI_MODEL"):
        if agent.get(key):
            env[key] = agent[key]

    if not env.get("OPENAI_API_KEY"):
        print("note: no OPENAI_API_KEY found in interview-agent/.env -> running without LLM")

    LOG.parent.mkdir(parents=True, exist_ok=True)
    flags = 0
    if os.name == "nt":
        # NOTE: DETACHED_PROCESS must not be used here. The Oracle `java.exe`
        # shim needs a console and exits silently (no output at all) when it is
        # fully detached. CREATE_NO_WINDOW keeps the window hidden but working.
        flags = subprocess.CREATE_NO_WINDOW | subprocess.CREATE_NEW_PROCESS_GROUP
    with LOG.open("ab") as out, ERR_LOG.open("ab") as err:
        process = subprocess.Popen(
            ["java", "-jar", str(JAR)],
            cwd=str(ROOT),
            env=env,
            stdout=out,
            stderr=err,
            stdin=subprocess.DEVNULL,
            creationflags=flags,
        )
    print("started Spring Boot: pid " + str(process.pid))
    print("logs: " + str(LOG) + " | " + str(ERR_LOG))
    deadline = time.time() + 90
    import urllib.error
    import urllib.request
    while time.time() < deadline:
        try:
            with urllib.request.urlopen("http://127.0.0.1:8080/", timeout=3) as response:
                if response.status == 200:
                    print("ready: http://localhost:8080")
                    return 0
        except Exception:  # noqa: BLE001 - still booting
            time.sleep(2)
    print("still starting after 90s, check the log files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
