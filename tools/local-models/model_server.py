"""Optional CPU model service. Runs from pinned local snapshots with no runtime downloads."""

import math
import os
import secrets
import threading
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel, ConfigDict, Field, field_validator

from model_registry import MODELS, model_path, verify_snapshot


class EmbeddingRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    model: str
    input: list[str] = Field(min_length=1, max_length=32)

    @field_validator("input")
    @classmethod
    def texts(cls, values):
        if any(not value.strip() or len(value) > 10000 for value in values):
            raise ValueError("Expected nonempty bounded text")
        return values


class RerankRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    model: str
    query: str = Field(min_length=1, max_length=4000)
    documents: list[str] = Field(min_length=1, max_length=40)
    top_n: int = Field(ge=1, le=40)

    @field_validator("documents")
    @classmethod
    def texts(cls, values):
        return EmbeddingRequest.texts(values)

    @field_validator("query")
    @classmethod
    def question(cls, value):
        if not value.strip():
            raise ValueError("Nonempty query required")
        return value


class Models:
    def __init__(self):
        import torch
        from transformers import (
            AutoModel,
            AutoModelForSequenceClassification,
            AutoTokenizer,
        )

        torch.set_num_threads(2)
        self.torch = torch
        self.tokenizers = {}
        self.models = {}
        for kind in MODELS:
            verify_snapshot(kind)
            path = model_path(kind)
            self.tokenizers[kind] = AutoTokenizer.from_pretrained(
                path, local_files_only=True, trust_remote_code=False
            )
            factory = (
                AutoModel if kind == "embedding" else AutoModelForSequenceClassification
            )
            self.models[kind] = factory.from_pretrained(
                path,
                local_files_only=True,
                trust_remote_code=False,
                use_safetensors=True,
            ).eval()
        self.dimension = int(self.models["embedding"].config.hidden_size)

    def encode(self, kind, texts, pairs=None):
        batch = self.tokenizers[kind](
            texts, text_pair=pairs, padding=True, truncation=False, return_tensors="pt"
        )
        # The BGE input window is 512 model tokens. Never silently truncate source evidence.
        if batch["input_ids"].shape[1] > 512:
            raise HTTPException(400, "MODEL_INPUT_TOO_LONG")
        return batch

    def embed(self, texts):
        batch = self.encode("embedding", texts)
        with self.torch.inference_mode():
            vectors = self.models["embedding"](**batch).last_hidden_state[:, 0]
            vectors = self.torch.nn.functional.normalize(vectors, p=2, dim=1)
        return vectors.tolist(), int(batch["attention_mask"].sum().item())

    def token_counts(self, texts):
        batch = self.tokenizers["embedding"](texts, padding=False, truncation=False)
        return [len(ids) for ids in batch["input_ids"]]

    def rerank(self, query, documents):
        scores, tokens = [], 0
        for start in range(0, len(documents), 4):
            group = documents[start : start + 4]
            batch = self.encode("rerank", [query] * len(group), group)
            with self.torch.inference_mode():
                logits = self.models["rerank"](**batch).logits.view(-1).float()
                scores.extend(self.torch.sigmoid(logits).tolist())
            tokens += int(batch["attention_mask"].sum().item())
        return scores, tokens


def create_app(engine=None, token=None):
    key = token if token is not None else os.environ.get("LOCAL_MODELS_TOKEN", "")
    if len(key) < 32:
        raise RuntimeError("LOCAL_MODELS_TOKEN must contain at least 32 characters")
    gate = threading.BoundedSemaphore(1)

    @asynccontextmanager
    async def lifespan(app):
        app.state.engine = engine if engine is not None else Models()
        yield

    def authorize(authorization: str = Header(default="")):
        if not secrets.compare_digest(authorization, "Bearer " + key):
            raise HTTPException(401, "INVALID_MODEL_CREDENTIAL")

    app = FastAPI(lifespan=lifespan, dependencies=[Depends(authorize)])

    def run(kind, requested, operation):
        if requested != MODELS[kind][0]:
            raise HTTPException(400, "UNKNOWN_MODEL")
        if not gate.acquire(blocking=False):
            raise HTTPException(429, "MODEL_BUSY")
        try:
            return operation(app.state.engine)
        except HTTPException:
            raise
        except Exception:
            raise HTTPException(503, "MODEL_EXECUTION_FAILED") from None
        finally:
            gate.release()

    @app.get("/health")
    def health():
        return {
            "models": MODELS,
            "embedding_dimensions": app.state.engine.dimension,
            "max_input_tokens": 512,
        }

    @app.post("/v1/embeddings")
    def embeddings(request: EmbeddingRequest):
        vectors, tokens = run(
            "embedding", request.model, lambda model: model.embed(request.input)
        )
        if len(vectors) != len(request.input) or any(
            len(vector) != app.state.engine.dimension
            or any(not math.isfinite(number) for number in vector)
            for vector in vectors
        ):
            raise HTTPException(503, "INVALID_MODEL_OUTPUT")
        return {
            "model": request.model,
            "data": [
                {"index": i, "embedding": vector} for i, vector in enumerate(vectors)
            ],
            "usage": {"total_tokens": tokens},
        }

    @app.post("/v1/tokenize")
    def tokenize(request: EmbeddingRequest):
        counts = run(
            "embedding", request.model, lambda model: model.token_counts(request.input)
        )
        return {
            "model": request.model,
            "revision": MODELS["embedding"][1],
            "counts": counts,
            "max_input_tokens": 512,
        }

    @app.post("/v1/rerank")
    def rerank(request: RerankRequest):
        scores, tokens = run(
            "rerank",
            request.model,
            lambda model: model.rerank(request.query, request.documents),
        )
        if len(scores) != len(request.documents) or any(
            not math.isfinite(score) for score in scores
        ):
            raise HTTPException(503, "INVALID_MODEL_OUTPUT")
        results = sorted(
            [{"index": i, "relevance_score": score} for i, score in enumerate(scores)],
            key=lambda row: row["relevance_score"],
            reverse=True,
        )
        return {"results": results[: request.top_n], "usage": {"total_tokens": tokens}}

    return app
