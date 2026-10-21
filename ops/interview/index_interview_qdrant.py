#!/usr/bin/env python3
"""Embed the unified interview question bank and load it into Qdrant.

Pipeline
--------
interview_questions.jsonl
    -> fit (or reuse) a local TF-IDF + SVD 256-dim embedder
    -> create the `careernav_interview_questions` collection
    -> upsert every record with its attribution payload

The embedder is intentionally the same family as the job pipeline
(char n-gram TF-IDF + SVD, 256 dims, cosine) so the project keeps one local
embedding stack and does not depend on an external embedding vendor.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

import joblib
import numpy as np
import requests
from sklearn.decomposition import TruncatedSVD
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.preprocessing import normalize

ROOT = Path(r"D:\何处是家乡\data\github-interview-datasets")
JSONL = ROOT / "interview_questions.jsonl"
MODEL_PATH = Path(r"D:\何处是家乡\data\postgresql\models\tfidf_svd_256_interview.joblib")
REPORT = ROOT / "qdrant_interview_index.report.json"

QDRANT_URL = "http://127.0.0.1:6333"
COLLECTION = "careernav_interview_questions"
DIMENSIONS = 256
UPSERT_BATCH = 128
NAMESPACE = uuid.UUID("6f9619ff-8b86-d011-b42d-00c04fc964ff")


def embed_text(record: dict) -> str:
    question = str(record.get("question", "")).strip()
    answer = str(record.get("answer", "")).strip()
    skills = " ".join(record.get("skills", []) or [])
    # The question carries the retrieval intent, so it is weighted above the body.
    return " ".join(part for part in ((question + " ") * 3 + skills, answer[:500]) if part)


def load_records() -> list:
    rows = []
    with JSONL.open("r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def fit_or_load_model(texts: list, refit: bool) -> dict:
    if MODEL_PATH.exists() and not refit:
        bundle = joblib.load(MODEL_PATH)
        bundle["recordsSeen"] = len(texts)
        print("reusing embedder: " + str(MODEL_PATH), flush=True)
        return bundle
    print("fitting TF-IDF + SVD({0}) on {1} records ...".format(DIMENSIONS, len(texts)), flush=True)
    vectorizer = TfidfVectorizer(analyzer="char", ngram_range=(2, 3), min_df=2, max_features=50000)
    matrix = vectorizer.fit_transform(texts)
    svd = TruncatedSVD(n_components=DIMENSIONS, random_state=42)
    svd.fit(matrix)
    bundle = {
        "vectorizer": vectorizer,
        "svd": svd,
        "dimensions": DIMENSIONS,
        "domain": "interview-questions",
        "recipe": "TfidfVectorizer(analyzer=char, ngram_range=(2,3), min_df=2, max_features=50000) + TruncatedSVD(256) + L2",
        "vocabularySize": len(vectorizer.vocabulary_),
        "fittedAt": datetime.now(timezone.utc).isoformat(),
        "trainedOn": len(texts),
    }
    MODEL_PATH.parent.mkdir(parents=True, exist_ok=True)
    joblib.dump(bundle, MODEL_PATH)
    print("saved embedder: {0} (vocab={1})".format(MODEL_PATH, len(vectorizer.vocabulary_)), flush=True)
    return bundle


def encode(bundle: dict, texts: list) -> np.ndarray:
    matrix = bundle["vectorizer"].transform(texts)
    dense = normalize(bundle["svd"].transform(matrix), axis=1, norm="l2")
    return dense.astype(np.float32)


def qdrant(method: str, path: str, payload=None, timeout: int = 120):
    url = QDRANT_URL + path
    response = requests.request(method, url, json=payload, timeout=timeout)
    if response.status_code >= 400:
        raise RuntimeError("Qdrant {0} {1} -> {2} {3}".format(method, path, response.status_code, response.text[:400]))
    return response.json()


def ensure_collection(recreate: bool) -> None:
    exists = requests.get(QDRANT_URL + "/collections/" + COLLECTION, timeout=30).status_code == 200
    if exists and recreate:
        qdrant("DELETE", "/collections/" + COLLECTION)
        exists = False
        print("dropped existing collection " + COLLECTION, flush=True)
    if not exists:
        qdrant("PUT", "/collections/" + COLLECTION, {
            "vectors": {"size": DIMENSIONS, "distance": "Cosine"},
            "on_disk_payload": True,
        })
        print("created collection " + COLLECTION, flush=True)
    for field, schema in (
        ("source", "keyword"), ("stage", "keyword"), ("role", "keyword"),
        ("kind", "keyword"), ("lang", "keyword"), ("difficulty", "keyword"), ("skills", "keyword"),
    ):
        try:
            qdrant("PUT", "/collections/{0}/index".format(COLLECTION), {"field_name": field, "field_schema": schema})
        except Exception as exc:  # noqa: BLE001 - index already present is fine
            print("  payload index {0}: {1}".format(field, exc), flush=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--refit", action="store_true", help="retrain the embedder instead of reusing the saved one")
    parser.add_argument("--recreate", action="store_true", help="drop and rebuild the Qdrant collection")
    args = parser.parse_args()

    records = load_records()
    if not records:
        print("no records found in " + str(JSONL))
        return 1
    texts = [embed_text(record) for record in records]
    bundle = fit_or_load_model(texts, refit=args.refit)
    vectors = encode(bundle, texts)

    ensure_collection(recreate=args.recreate)

    started = time.time()
    uploaded = 0
    for start in range(0, len(records), UPSERT_BATCH):
        chunk = records[start:start + UPSERT_BATCH]
        points = []
        for offset, record in enumerate(chunk):
            index = start + offset
            payload = dict(record)
            payload.pop("id", None)
            payload["recordId"] = record.get("id")
            points.append({
                "id": str(uuid.uuid5(NAMESPACE, str(record.get("id")))),
                "vector": [float(value) for value in vectors[index]],
                "payload": payload,
            })
        qdrant("PUT", "/collections/{0}/points?wait=true".format(COLLECTION), {"points": points})
        uploaded += len(points)
        print("  upserted {0}/{1}".format(uploaded, len(records)), flush=True)

    info = qdrant("GET", "/collections/" + COLLECTION)["result"]
    elapsed = round(time.time() - started, 1)
    report = {
        "collection": COLLECTION,
        "qdrantUrl": QDRANT_URL,
        "dimensions": DIMENSIONS,
        "distance": "Cosine",
        "embedder": str(MODEL_PATH),
        "embedderRecipe": bundle.get("recipe"),
        "vocabularySize": bundle.get("vocabularySize") or len(bundle["vectorizer"].vocabulary_),
        "indexed": uploaded,
        "pointsCount": info.get("points_count"),
        "status": info.get("status"),
        "elapsedSeconds": elapsed,
        "bySource": {},
        "builtAt": datetime.now(timezone.utc).isoformat(),
    }
    for record in records:
        report["bySource"][record["source"]] = report["bySource"].get(record["source"], 0) + 1
    REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
