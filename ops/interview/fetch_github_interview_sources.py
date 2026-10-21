#!/usr/bin/env python3
"""Mirror the text files of whitelisted interview repositories.

Why not `git clone`?
--------------------
On this network `git clone` / codeload tarballs are reset mid-transfer, while
`cdn.jsdelivr.net` (a GitHub mirror) is reachable. Interview corpora are
markdown/JSON only, so we enumerate files through the jsDelivr package API and
download just the text files we need. Images and other binaries are skipped,
which keeps the local mirror at a few MB instead of hundreds of MB.

Licensing
---------
Only repositories with an explicit redistributable license are mirrored, and
the mirror is used locally (Qdrant payloads keep source URL + license) rather
than redistributed. GitHub metadata is best effort: if the unauthenticated API
quota is exhausted the script still succeeds.
"""
from __future__ import annotations

import json
import sys
import time
import urllib.parse
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from pathlib import Path

import requests

API = "https://api.github.com"
JSDELIVR_API = "https://data.jsdelivr.com/v1/packages/gh"
JSDELIVR_CDN = "https://cdn.jsdelivr.net/gh"
RAW = "https://raw.githubusercontent.com"
ROOT = Path(r"D:\何处是家乡\data\github-interview-datasets")
MIRROR = ROOT / "sources"
TEXT_SUFFIXES = (".md", ".json", ".jsonl", ".csv", ".txt")
TEXT_NAMES = ("LICENSE", "LICENSE.md", "LICENSE.txt", "NOTICE")

SOURCES = [
    {
        "name": "tech-interview-handbook",
        "repo": "yangshun/tech-interview-handbook",
        "branch": "main",
        "license": "MIT",
        "include": ("apps/website/contents/",),
        "extra": ("README.md", "LICENSE"),
    },
    {
        "name": "JavaGuide",
        "repo": "Snailclimb/JavaGuide",
        "branch": "main",
        "license": "Apache-2.0",
        "include": ("docs/",),
        "extra": ("README.md", "LICENSE"),
    },
    {
        "name": "advanced-java",
        "repo": "doocs/advanced-java",
        "branch": "main",
        "license": "CC-BY-SA-4.0",
        "include": ("docs/",),
        "extra": ("README.md", "LICENSE"),
    },
    {
        "name": "codeprep-questions",
        "repo": "codeprepdev/codeprep-questions",
        "branch": "main",
        "license": "CC-BY-4.0",
        "include": ("data/",),
        "extra": ("schema.json", "README.md", "LICENSE"),
    },
]

HEADERS = {"User-Agent": "CareerNavDataBot/1.0"}


def session() -> requests.Session:
    s = requests.Session()
    s.headers.update(HEADERS)
    return s


def repo_meta(sess: requests.Session, repo: str) -> dict:
    """Best-effort GitHub metadata; never fatal."""
    try:
        r = sess.get(API + "/repos/" + repo, timeout=30,
                     headers={"Accept": "application/vnd.github+json"})
        if r.status_code != 200:
            return {"repoMetaError": "HTTP " + str(r.status_code)}
        data = r.json()
        lic = data.get("license") or {}
        return {
            "stars": data.get("stargazers_count"),
            "declaredLicense": lic.get("spdx_id"),
            "pushedAt": data.get("pushed_at"),
            "htmlUrl": data.get("html_url"),
        }
    except Exception as exc:  # noqa: BLE001
        return {"repoMetaError": type(exc).__name__ + ": " + str(exc)}


def list_text_files(sess: requests.Session, src: dict) -> list:
    url = JSDELIVR_API + "/" + src["repo"] + "@" + src["branch"] + "?structure=flat"
    r = sess.get(url, timeout=60)
    r.raise_for_status()
    entries = r.json().get("files", [])
    wanted = []
    for entry in entries:
        path = entry.get("name", "").lstrip("/")
        name = path.rsplit("/", 1)[-1]
        if not (path.endswith(TEXT_SUFFIXES) or name in TEXT_NAMES):
            continue
        if not (path.startswith(src["include"]) or path in src["extra"]):
            continue
        wanted.append({"path": path, "size": entry.get("size", 0)})
    wanted.sort(key=lambda item: item["path"])
    return wanted


def download_one(sess: requests.Session, src: dict, entry: dict, dest_root: Path, attempts: int = 3) -> dict:
    path = entry["path"]
    target = dest_root / path
    if target.exists() and target.stat().st_size > 0:
        return {"path": path, "status": "cached", "bytes": target.stat().st_size}
    target.parent.mkdir(parents=True, exist_ok=True)
    quoted = urllib.parse.quote(path)
    urls = [
        "/".join([JSDELIVR_CDN, src["repo"], src["branch"], quoted]),
        "/".join([RAW, src["repo"], src["branch"], quoted]),
    ]
    last_error = None
    for attempt in range(1, attempts + 1):
        for url in urls:
            try:
                r = sess.get(url, timeout=60)
                if r.status_code == 200 and r.content:
                    target.write_bytes(r.content)
                    return {"path": path, "status": "ok", "bytes": len(r.content)}
                last_error = "HTTP " + str(r.status_code)
            except Exception as exc:  # noqa: BLE001
                last_error = type(exc).__name__ + ": " + str(exc)
        time.sleep(1.0 * attempt)
    return {"path": path, "status": "failed", "error": last_error}


def fetch_source(sess: requests.Session, src: dict) -> dict:
    dest_root = MIRROR / src["name"]
    files = list_text_files(sess, src)
    meta = repo_meta(sess, src["repo"])
    results = []
    with ThreadPoolExecutor(max_workers=6) as pool:
        futures = [pool.submit(download_one, sess, src, entry, dest_root) for entry in files]
        for future in as_completed(futures):
            results.append(future.result())
    ok = [r for r in results if r["status"] in ("ok", "cached")]
    failed = [r for r in results if r["status"] == "failed"]
    total_bytes = sum(r.get("bytes", 0) for r in ok)
    print("  {0}: {1} text files | {2} ok | {3} failed | {4:.2f} MB".format(
        src["name"], len(files), len(ok), len(failed), total_bytes / 1048576.0), flush=True)
    for item in failed[:5]:
        print("    failed: " + item["path"] + " -> " + str(item.get("error")), flush=True)
    record = {
        "name": src["name"],
        "source": src["repo"],
        "branch": src["branch"],
        "license": src["license"],
        "localPath": str(dest_root),
        "textFiles": len(files),
        "downloaded": len(ok),
        "failed": len(failed),
        "bytes": total_bytes,
        "fetchedAt": datetime.now(timezone.utc).isoformat(),
        "notes": "Text-only mirror for local retrieval. Images skipped. Source URL + license kept in every indexed record.",
    }
    record.update(meta)
    return record


def main() -> int:
    ROOT.mkdir(parents=True, exist_ok=True)
    MIRROR.mkdir(parents=True, exist_ok=True)
    sess = session()
    report = []
    print("Mirroring interview sources (text only):", flush=True)
    for src in SOURCES:
        print("- " + src["repo"], flush=True)
        report.append(fetch_source(sess, src))
    out = ROOT / "sources.json"
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print("wrote " + str(out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
