#!/usr/bin/env python3
"""Ad-hoc retrieval check for the interview question collection."""
from __future__ import annotations

import argparse
import json
import sys

import requests

EMBEDDING_URL = "http://127.0.0.1:5002/v1/embeddings"
QDRANT_URL = "http://127.0.0.1:6333"
COLLECTION = "careernav_interview_questions"


def embed(text: str):
    response = requests.post(EMBEDDING_URL, json={"input": text, "model": "tfidf-svd-256-interview"}, timeout=30)
    response.raise_for_status()
    return response.json()["data"][0]["embedding"]


def search(query: str, limit: int = 5, stage: str = "", source: str = "", role: str = ""):
    must = []
    if stage:
        must.append({"key": "stage", "match": {"value": stage}})
    if source:
        must.append({"key": "source", "match": {"value": source}})
    if role:
        must.append({"key": "role", "match": {"value": role}})
    body = {"vector": embed(query), "limit": limit, "with_payload": True}
    if must:
        body["filter"] = {"must": must}
    response = requests.post(QDRANT_URL + "/collections/" + COLLECTION + "/points/search", json=body, timeout=30)
    response.raise_for_status()
    return response.json()["result"]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("query", nargs="?", default="")
    parser.add_argument("--limit", type=int, default=5)
    parser.add_argument("--stage", default="")
    parser.add_argument("--source", default="")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    queries = [args.query] if args.query else [
        "Redis 缓存穿透、雪崩怎么解决",
        "Java 线程池的核心参数怎么设置",
        "MySQL 索引失效的场景有哪些",
        "说说你在项目里做过的一次性能优化",
        "如何设计一个限流系统",
    ]
    for query in queries:
        hits = search(query, limit=args.limit, stage=args.stage, source=args.source)
        if args.json:
            print(json.dumps({"query": query, "hits": hits}, ensure_ascii=False, indent=2))
            continue
        print("=" * 78)
        print("query: " + query)
        for rank, hit in enumerate(hits, 1):
            payload = hit["payload"]
            print("  {0}. score={1:.3f} [{2}/{3}/{4}] {5}".format(
                rank, hit["score"], payload.get("source"), payload.get("stage"),
                ",".join(payload.get("skills", [])[:3]) or "-", payload.get("question", "")[:90]))
            print("     " + payload.get("answer", "")[:150].replace("\n", " "))
    return 0


if __name__ == "__main__":
    sys.exit(main())
