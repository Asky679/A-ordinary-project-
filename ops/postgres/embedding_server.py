#!/usr/bin/env python3
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import joblib
import numpy as np
from sklearn.preprocessing import normalize

MODEL_PATH = Path(r"D:\何处是家乡\data\postgresql\models\tfidf_svd_256.joblib")
bundle = joblib.load(MODEL_PATH)
vectorizer = bundle["vectorizer"]
svd = bundle["svd"]
dimensions = int(bundle["dimensions"])

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path in ("/health", "/v1/models"):
            self.send_json(200, {"status": "ok", "model": "tfidf-svd-256", "dimensions": dimensions})
        else:
            self.send_json(404, {"error": "not found"})

    def read_body(self):
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
        length = int(self.headers.get("Content-Length", "0"))
        return self.rfile.read(length)

    def do_POST(self):
        if self.path != "/v1/embeddings":
            self.send_json(404, {"error": "not found"})
            return
        try:
            payload = json.loads(self.read_body().decode("utf-8"))
            inputs = payload.get("input", "")
            if isinstance(inputs, str):
                inputs = [inputs]
            vectors = []
            for text in inputs:
                X = vectorizer.transform([str(text)])
                Z = normalize(svd.transform(X), axis=1, norm="l2")
                vectors.append([float(x) for x in Z[0]])
            self.send_json(200, {
                "object": "list",
                "model": "tfidf-svd-256",
                "data": [{"object": "embedding", "index": i, "embedding": v} for i, v in enumerate(vectors)]
            })
        except Exception as exc:
            self.send_json(500, {"error": str(exc)})

    def send_json(self, status, value):
        body = json.dumps(value, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        pass

if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", 5001), Handler).serve_forever()
