import hmac
import json
import os

from fastapi import Depends, FastAPI, Header, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from careflow import models, retrieval


def internal(x_internal_token: str = Header(default="")):
    configured = os.environ.get("INTERNAL_TOKEN", "")
    if len(configured) < 32 or not hmac.compare_digest(configured, x_internal_token):
        raise HTTPException(401, "Invalid internal identity")


app = FastAPI(title="CareFlow Internal Worker", dependencies=[Depends(internal)])


class Recall(BaseModel):
    tenant_id: str
    version_ids: list[str] = Field(max_length=10000)
    query: str = Field(min_length=1, max_length=4000)
    mode: str = "hybrid"
    allow_degraded: bool = False


class Rerank(BaseModel):
    query: str = Field(min_length=1, max_length=4000)
    candidates: list[dict] = Field(max_length=40)
    allow_degraded: bool = False


class Generate(BaseModel):
    query: str = Field(min_length=1, max_length=4000)
    evidence: list[dict] = Field(min_length=1, max_length=6)


@app.post("/internal/v1/recall")
def recall(body: Recall):
    try:
        return retrieval.recall(
            body.tenant_id, body.version_ids, body.query, body.mode, body.allow_degraded
        )
    except Exception as exc:
        raise HTTPException(503, "RETRIEVAL_UNAVAILABLE") from exc


@app.post("/internal/v1/rerank")
def rerank(body: Rerank):
    if not body.candidates:
        return {"results": [], "degraded": False}
    try:
        return {
            "results": models.rerank(body.query, body.candidates),
            "degraded": False,
        }
    except Exception as exc:
        if body.allow_degraded:
            return {
                "results": [{"id": c["id"], "score": None} for c in body.candidates],
                "degraded": True,
                "warning": "RERANK_UNAVAILABLE",
            }
        raise HTTPException(503, "RERANK_UNAVAILABLE") from exc


@app.post("/internal/v1/generate/stream")
def stream(body: Generate):
    def generate():
        try:
            yield from models.generate_stream(body.query, body.evidence)
        except Exception:
            yield json.dumps({"error": "GENERATION_UNAVAILABLE"}) + "\n"

    return StreamingResponse(generate(), media_type="application/x-ndjson")


class Tokenize(BaseModel):
    text: str = Field(min_length=1, max_length=10000)


@app.post("/internal/v1/tokenize")
def tokenize(body: Tokenize):
    import tiktoken

    return {"token_count": len(tiktoken.get_encoding("cl100k_base").encode(body.text))}
