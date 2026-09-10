import hmac
import json
import os
import uuid

from fastapi import Depends, FastAPI, Header, HTTPException
from fastapi.responses import StreamingResponse

from careflow import index_cleanup, index_verification, models, retrieval
from careflow.model_configuration import use_configuration
from careflow.protocol_v1 import (
    CachePurge,
    CompactionRequest,
    CompactionResponse,
    ContextTokens,
    ContextTokensResponse,
    Generate,
    GenerationEvent,
    IndexPurge,
    IndexVerification,
    IndexVerificationResponse,
    LegacyCachePurge,
    ManualFaq,
    ParseCompletion,
    PurgeResponse,
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


@app.post("/internal/v1/context/tokens", response_model=ContextTokensResponse)
def context_tokens(body: ContextTokens):
    from careflow.chunking import tokens

    return ContextTokensResponse.model_validate(
        {
            "counts": [
                {"id": item.id, "token_count": tokens(item.content)}
                for item in body.candidates
            ],
            "tokenizer": "cl100k_base",
        }
    )


@app.post("/internal/v1/faq/chunk", response_model=ParseCompletion)
def faq_chunk(body: ManualFaq):
    from careflow.context_chunking import manual_faq
    from careflow.parsing_types import InvalidFile

    try:
        if (
            body.model_configuration is not None
            and body.model_configuration.kind != "EMBEDDING"
        ):
            raise ValueError("Embedding configuration required")
        with use_configuration(body.model_configuration):
            return manual_faq(
                body.question,
                body.alternatives,
                body.answer,
                body.chunking.model_dump(),
            )
    except InvalidFile as exc:
        raise HTTPException(422, "FAQ_EXCEEDS_CONTEXT_BUDGET") from exc
    except Exception as exc:
        raise HTTPException(503, "FAQ_PROCESSING_UNAVAILABLE") from exc


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
                    body.generation_ids,
                )
            )
    except Exception as exc:
        raise HTTPException(503, "RETRIEVAL_UNAVAILABLE") from exc


@app.post("/internal/v1/rerank", response_model=RerankResponse)
def rerank(body: Rerank):
    if not body.candidates:
        return {"results": [], "degraded": False, "usage": {"state": "NOT_CALLED"}}
    if (
        body.model_configuration is not None
        and body.model_configuration.kind != "RERANK"
    ):
        raise HTTPException(422, "INVALID_MODEL_KIND")
    usage = {"state": "UNKNOWN", "total_tokens": None}

    def record_usage(tokens):
        usage.update(
            state="REPORTED" if tokens is not None else "NOT_REPORTED",
            total_tokens=tokens,
        )

    try:
        with use_configuration(body.model_configuration):
            return RerankResponse.model_validate(
                {
                    "results": models.rerank(
                        body.query,
                        [c.model_dump() for c in body.candidates],
                        record_usage,
                    ),
                    "degraded": False,
                    "usage": usage,
                }
            )
    except Exception as exc:
        if body.allow_degraded:
            return {
                "results": [{"id": c.id, "score": None} for c in body.candidates],
                "degraded": True,
                "warning": "RERANK_UNAVAILABLE",
                "usage": usage,
            }
        raise HTTPException(503, "RERANK_UNAVAILABLE") from exc


@app.post("/internal/v1/generate/stream")
def stream(body: Generate):
    from careflow.chunking import tokens

    if sum(tokens(item.content) for item in body.evidence) > 6000:
        raise HTTPException(422, "EVIDENCE_TOKEN_LIMIT")

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


@app.post("/internal/v1/index/verify", response_model=IndexVerificationResponse)
def verify_index(body: IndexVerification):
    try:
        if (
            body.model_configuration is not None
            and body.model_configuration.kind != "EMBEDDING"
        ):
            raise ValueError("Embedding configuration required")
        with use_configuration(body.model_configuration):
            if body.expected_model_identity != models.identity():
                raise models.ModelUnavailable("Index model identity mismatch")
            expected = {str(uuid.UUID(row.id)): row.content_hash for row in body.chunks}
            if len(expected) != len(body.chunks):
                raise ValueError("Duplicate expected chunk")
            return index_verification.verify(
                retrieval.client(),
                retrieval.collection(body.tenant_id, body.generation_id is not None),
                body.tenant_id,
                body.version_id,
                body.generation_id,
                expected,
            )
    except Exception as exc:
        raise HTTPException(503, "INDEX_VERIFICATION_UNAVAILABLE") from exc


@app.post("/internal/v1/index/purge-version", response_model=PurgeResponse)
def purge_version(body: IndexPurge):
    try:
        return PurgeResponse.model_validate(
            index_cleanup.purge_version(
                retrieval.client(), body.tenant_id, body.version_id, body.generation_id
            )
        )
    except Exception as exc:
        raise HTTPException(503, "INDEX_PURGE_UNAVAILABLE") from exc


@app.post("/internal/v1/index/purge-cache", response_model=PurgeResponse)
def purge_cache(body: CachePurge):
    try:
        return PurgeResponse.model_validate(
            index_cleanup.purge_cache(
                retrieval.client(),
                body.tenant_id,
                [entry.model_dump() for entry in body.entries],
            )
        )
    except Exception as exc:
        raise HTTPException(503, "CACHE_PURGE_UNAVAILABLE") from exc


@app.post("/internal/v1/index/purge-legacy-cache", response_model=PurgeResponse)
def purge_legacy_cache(body: LegacyCachePurge):
    try:
        return PurgeResponse.model_validate(
            index_cleanup.purge_legacy_cache(retrieval.client(), body.tenant_id)
        )
    except Exception as exc:
        raise HTTPException(503, "CACHE_PURGE_UNAVAILABLE") from exc


@app.post("/internal/v1/index/compactions", response_model=CompactionResponse)
def compactions(body: CompactionRequest):
    try:
        return CompactionResponse.model_validate(
            index_cleanup.compaction_state(
                retrieval.client(),
                body.tenant_id,
                [job.model_dump() for job in body.compactions],
            )
        )
    except Exception as exc:
        raise HTTPException(503, "COMPACTION_UNAVAILABLE") from exc
