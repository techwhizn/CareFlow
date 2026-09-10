import hmac
import json
import os

from fastapi import Depends, FastAPI, Header, HTTPException
from fastapi.responses import StreamingResponse

from careflow import models, retrieval
from careflow.protocol_v1 import (
    Generate,
    GenerationEvent,
    Recall,
    RecallResponse,
    Rerank,
    RerankResponse,
    Tokenize,
    TokenizeResponse,
)


def internal(x_internal_token: str = Header(default="")):
    configured = os.environ.get("INTERNAL_TOKEN", "")
    if len(configured) < 32 or not hmac.compare_digest(configured, x_internal_token):
        raise HTTPException(401, "Invalid internal identity")


app = FastAPI(title="CareFlow Internal Worker", dependencies=[Depends(internal)])


@app.post("/internal/v1/recall", response_model=RecallResponse)
def recall(body: Recall):
    try:
        return RecallResponse.model_validate(
            retrieval.recall(
                body.tenant_id,
                body.version_ids,
                body.query,
                body.mode,
                body.allow_degraded,
            )
        )
    except Exception as exc:
        raise HTTPException(503, "RETRIEVAL_UNAVAILABLE") from exc


@app.post("/internal/v1/rerank", response_model=RerankResponse)
def rerank(body: Rerank):
    if not body.candidates:
        return {"results": [], "degraded": False}
    try:
        return RerankResponse.model_validate(
            {
                "results": models.rerank(
                    body.query, [c.model_dump() for c in body.candidates]
                ),
                "degraded": False,
            }
        )
    except Exception as exc:
        if body.allow_degraded:
            return {
                "results": [{"id": c.id, "score": None} for c in body.candidates],
                "degraded": True,
                "warning": "RERANK_UNAVAILABLE",
            }
        raise HTTPException(503, "RERANK_UNAVAILABLE") from exc


@app.post("/internal/v1/generate/stream")
def stream(body: Generate):
    def generate():
        try:
            for line in models.generate_stream(
                body.query, [c.model_dump() for c in body.evidence]
            ):
                event = GenerationEvent.model_validate_json(line)
                yield event.model_dump_json(exclude_none=True) + "\n"
                if event.done:
                    return
        except Exception:
            yield json.dumps({"error": "GENERATION_UNAVAILABLE"}) + "\n"

    return StreamingResponse(generate(), media_type="application/x-ndjson")


@app.post("/internal/v1/tokenize", response_model=TokenizeResponse)
def tokenize(body: Tokenize):
    import tiktoken

    return {"token_count": len(tiktoken.get_encoding("cl100k_base").encode(body.text))}
