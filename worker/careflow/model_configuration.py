"""Request-scoped model configuration; never mutate process environment per tenant."""

import hashlib
import json
import os
from contextlib import contextmanager
from contextvars import ContextVar
from typing import Literal
from urllib.parse import urlsplit

from pydantic import BaseModel, ConfigDict, Field, SecretStr, model_validator


class ModelConfiguration(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)
    kind: Literal["EMBEDDING", "RERANK", "GENERATION"]
    base_url: str = Field(min_length=1, max_length=500)
    model: str = Field(min_length=1, max_length=200)
    revision: str = Field(default="", max_length=200)
    dimensions: int | None = Field(default=None, ge=1, le=65536)
    api_key: SecretStr = Field(
        default_factory=lambda: SecretStr(""), max_length=8192, repr=False
    )

    @model_validator(mode="after")
    def valid_embedding(self):
        if not self.model.strip():
            raise ValueError("Model name cannot be blank")
        endpoint = urlsplit(self.base_url)
        if (
            endpoint.scheme not in {"http", "https"}
            or not endpoint.hostname
            or endpoint.username is not None
            or endpoint.password is not None
            or endpoint.query
            or endpoint.fragment
            or endpoint.path not in {"", "/", "/v1", "/v1/"}
        ):
            raise ValueError("Invalid model endpoint")
        if self.kind == "EMBEDDING" and (
            not self.revision.strip() or self.dimensions is None
        ):
            raise ValueError("Embedding requires immutable revision and dimensions")
        if self.kind != "EMBEDDING" and self.dimensions is not None:
            raise ValueError("Dimensions only apply to embedding")
        return self

    def identity(self):
        if self.kind != "EMBEDDING":
            raise ValueError("Only embedding defines vector identity")
        return embedding_identity(
            self.base_url, self.model, self.revision, str(self.dimensions)
        )


def embedding_identity(base_url, model, revision, dimensions):
    # Preserve the existing persisted identity algorithm for old collections.
    snapshot = {
        "EMBEDDING_BASE_URL": base_url,
        "EMBEDDING_MODEL": model,
        "EMBEDDING_REVISION": revision,
        "EMBEDDING_DIMENSIONS": dimensions,
        "metric": "COSINE",
    }
    return hashlib.sha256(json.dumps(snapshot, sort_keys=True).encode()).hexdigest()


_current: ContextVar[ModelConfiguration | None] = ContextVar(
    "careflow_model_configuration", default=None
)


@contextmanager
def use_configuration(configuration: ModelConfiguration | None):
    token = _current.set(configuration)
    try:
        yield
    finally:
        _current.reset(token)


def value(name, default=""):
    configuration = _current.get()
    if configuration is not None and name.startswith(configuration.kind + "_"):
        field = name[len(configuration.kind) + 1 :]
        if field == "API_KEY":
            return configuration.api_key.get_secret_value()
        if field == "BASE_URL":
            return configuration.base_url
        if field == "MODEL":
            return configuration.model
        if field == "REVISION":
            return configuration.revision
        if field == "DIMENSIONS":
            return str(configuration.dimensions)
        raise ValueError("Unknown model configuration field")
    return os.environ.get(name, default)
