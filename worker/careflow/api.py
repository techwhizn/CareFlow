import hmac
import json
import os

from fastapi import Depends, FastAPI, Header, HTTPException
from fastapi.responses import StreamingResponse

from careflow import models, retrieval
from careflow.model_configuration import use_configuration
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
        if (
            body.model_configuration is not None
            and body.model_configuration.kind != "EMBEDDING"
        ):
            raise ValueError("Recall requires embedding configuration")
        with use_configuration(body.model_configuration):
            if (
                body.expected_model_identity is not None
                and body.expected_model_identity != models.identity()
            ):
                raise models.ModelUnavailable("Published index model identity mismatch")
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
    if (
        body.model_configuration is not None
        and body.model_configuration.kind != "RERANK"
    ):
        raise HTTPException(422, "INVALID_MODEL_KIND")
    try:
        with use_configuration(body.model_configuration):
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
        upstream = None
        try:
            if (
                body.model_configuration is not None
                and body.model_configuration.kind != "GENERATION"
            ):
                raise ValueError("Generation requires generation configuration")
            upstream = iter(
                models.generate_stream(
                    body.query, [c.model_dump() for c in body.evidence]
                )
            )
            while True:
                # StreamingResponse may resume each next() in a different copied thread context.
                # Never retain a ContextVar token across a yield to that thread pool.
                with use_configuration(body.model_configuration):
                    line = next(upstream, None)
                if line is None:
                    return
                event = GenerationEvent.model_validate_json(line)
                yield event.model_dump_json(exclude_none=True) + "\n"
                if event.done:
                    return
        except Exception:
            yield json.dumps({"error": "GENERATION_UNAVAILABLE"}) + "\n"
        finally:
            if upstream is not None and hasattr(upstream, "close"):
                with use_configuration(body.model_configuration):
                    upstream.close()

    return StreamingResponse(generate(), media_type="application/x-ndjson")


@app.post("/internal/v1/tokenize", response_model=TokenizeResponse)
def tokenize(body: Tokenize):
    try:
        if (
            body.model_configuration is not None
            and body.model_configuration.kind != "EMBEDDING"
        ):
            raise ValueError("Embedding tokenizer required")
        with use_configuration(body.model_configuration):
            counts, limit = models.input_tokens([body.text], body.model_tokenizer)
        logical, _ = models.input_tokens([body.text])
        return {
            "token_count": logical[0],
            "model_token_count": counts[0],
            "model_limit": min(limit, body.model_maximum),
        }
    except Exception as exc:
        raise HTTPException(503, "TOKENIZER_UNAVAILABLE") from exc
