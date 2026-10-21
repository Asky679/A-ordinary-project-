#!/usr/bin/env python3
"""Local embedding service for the interview question bank (port 5002).

Mirrors the API shape of the job embedding service so the same client code can
talk to both:

    GET  /health
    POST /v1/embeddings   {"input": "..." | ["...", ...]}

The model is the interview-domain TF-IDF + SVD bundle produced by
ops/interview/index_interview_qdrant.py.
"""
from __future__ import annotations

import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import joblib
from sklearn.preprocessing import normalize

MODEL_PATH = Path(os.getenv(
    "INTERVIEW_EMBEDDING_MODEL",
    r"D:\何处是家乡\data\postgresql\models\tfidf_svd_256_interview.joblib",
))
HOST = os.getenv("INTERVIEW_EMBEDDING_HOST", "127.0.0.1")
PORT = int(os.getenv("INTERVIEW_EMBEDDING_PORT", "5002"))

_STATE = {"mtime": None, "bundle": None}


def current_bundle() -> dict:
    """Reload the embedder when the model file changes.

    Re-indexing rewrites the model file; without this check a long-running
    service would keep serving the previous vector space and silently return
    garbage, because query vectors would no longer match the stored vectors.
    """
    mtime = MODEL_PATH.stat().st_mtime
    if _STATE["bundle"] is None or _STATE["mtime"] != mtime:
        _STATE["bundle"] = joblib.load(MODEL_PATH)
        _STATE["mtime"] = mtime
    return _STATE["bundle"]


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        if self.path in ("/health", "/v1/models"):
            self.send_json(200, {
                "status": "ok",
                "model": "tfidf-svd-256-interview",
                "dimensions": int(current_bundle()["dimensions"]),
                "datasetId": current_bundle().get("domain", "interview-questions"),
                "trainedOn": current_bundle().get("trainedOn"),
                "fittedAt": current_bundle().get("fittedAt"),
            })
        else:
            self.send_json(404, {"error": "not found"})

    def read_body(self) -> bytes:
        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            chunks = []
            while True:
                line = self.rfile.readline().strip()
                if not line:
                    continue
                size = int(line.split(b";", 1)[0], 16)
                if size == 0:
                    self.rfile.readline()
                    break
                chunks.append(self.rfile.read(size))
                self.rfile.read(2)
            return b"".join(chunks)
        return self.rfile.read(int(self.headers.get("Content-Length", "0")))

    def do_POST(self):
        if self.path != "/v1/embeddings":
            self.send_json(404, {"error": "not found"})
            return
        try:
            payload = json.loads(self.read_body().decode("utf-8"))
            inputs = payload.get("input", "")
            if isinstance(inputs, str):
                inputs = [inputs]
            bundle = current_bundle()
            vectors = []
            for text in inputs:
                matrix = bundle["vectorizer"].transform([str(text)])
                dense = normalize(bundle["svd"].transform(matrix), axis=1, norm="l2")
                vectors.append([float(value) for value in dense[0]])
            self.send_json(200, {
                "object": "list",
                "model": "tfidf-svd-256-interview",
                "data": [{"object": "embedding", "index": i, "embedding": v} for i, v in enumerate(vectors)],
            })
        except Exception as exc:  # noqa: BLE001
            self.send_json(500, {"error": str(exc)})

    def send_json(self, status: int, value) -> None:
        body = json.dumps(value, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        pass


def main() -> int:
    if not MODEL_PATH.exists():
        print("missing embedder: " + str(MODEL_PATH), file=sys.stderr)
        return 1
    print("interview embedding service on http://{0}:{1}".format(HOST, PORT), flush=True)
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
    return 0


if __name__ == "__main__":
    sys.exit(main())
