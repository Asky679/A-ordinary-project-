#!/usr/bin/env python3
import base64
import json
import pathlib
import subprocess
from datetime import datetime, timezone

import requests

REPO = "Asky679/A-ordinary-project-"
SOURCE = pathlib.Path(r"D:\何处是家乡\new pj")
BRANCH = "main"
AUTHOR = {"name": "Asky679", "email": "msh060719@qq.com"}
EXCLUDED_PARTS = {".git", "target", ".ppt-build", "output", "data", "__pycache__", ".venv", ".idea", ".vscode"}
EXCLUDED_SUFFIXES = {".exe", ".zip", ".log", ".tmp", ".pyc"}


def token_from_git_credential():
    result = subprocess.run(
        ["git", "credential", "fill"],
        input="protocol=https\nhost=github.com\n\n",
        text=True,
        capture_output=True,
        check=True,
    )
    values = dict(line.split("=", 1) for line in result.stdout.splitlines() if "=" in line)
    token = values.get("password")
    if not token:
        raise RuntimeError("GitHub token not found in Git credential manager")
    return token


def api(session, method, path, payload=None):
    response = session.request(method, "https://api.github.com" + path, json=payload, timeout=60)
    if response.status_code >= 300:
        raise RuntimeError(f"GitHub API {response.status_code}: {response.text[:500]}")
    return response.json()


def main():
    token = token_from_git_credential()
    session = requests.Session()
    session.trust_env = False
    session.headers.update({
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "CareerNav-Uploader",
    })

    tracked = []
    for path in SOURCE.rglob("*"):
        if not path.is_file():
            continue
        relative = path.relative_to(SOURCE)
        if any(part in EXCLUDED_PARTS for part in relative.parts):
            continue
        if path.suffix.lower() in EXCLUDED_SUFFIXES:
            continue
        if path.name == ".env" or (path.name.startswith(".env.") and path.name != ".env.example"):
            continue
        if relative.as_posix().startswith("tools/qdrant/"):
            continue
        tracked.append(relative.as_posix())
    tracked.sort()
    if not tracked:
        raise RuntimeError("No source files found")

    ref = api(session, "GET", f"/repos/{REPO}/git/ref/heads/{BRANCH}")
    parent_sha = ref["object"]["sha"]
    parent = api(session, "GET", f"/repos/{REPO}/git/commits/{parent_sha}")

    entries = []
    for index, relative in enumerate(tracked, start=1):
        file_path = SOURCE / relative
        blob = api(session, "POST", f"/repos/{REPO}/git/blobs", {
            "content": base64.b64encode(file_path.read_bytes()).decode("ascii"),
            "encoding": "base64",
        })
        entries.append({"path": relative.replace("\\", "/"), "mode": "100644", "type": "blob", "sha": blob["sha"]})
        if index % 20 == 0:
            print(f"uploaded_blobs={index}")

    tree = api(session, "POST", f"/repos/{REPO}/git/trees", {
        "base_tree": parent["tree"]["sha"],
        "tree": entries,
    })
    commit = api(session, "POST", f"/repos/{REPO}/git/commits", {
        "message": "Add agent-driven job matching and complete CareerNav features",
        "tree": tree["sha"],
        "parents": [parent_sha],
        "author": AUTHOR,
        "committer": AUTHOR,
    })
    api(session, "PATCH", f"/repos/{REPO}/git/refs/heads/{BRANCH}", {
        "sha": commit["sha"],
        "force": False,
    })

    receipt = {
        "repo": f"https://github.com/{REPO}",
        "branch": BRANCH,
        "commit": commit["sha"],
        "files": len(entries),
        "uploaded_at": datetime.now(timezone.utc).isoformat(),
    }
    output = pathlib.Path("output/github_push_receipt.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(receipt, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(receipt, ensure_ascii=False))


if __name__ == "__main__":
    main()