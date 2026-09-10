"""Tenant/model-scoped, content-addressed vectors; no authorization decisions here."""

import hashlib
import json
import math
import uuid

from pymilvus import DataType

from careflow import models
from careflow.model_configuration import value


def collection(tenant):
    return "cfec_" + uuid.UUID(tenant).hex + "_" + models.identity()


def ensure(client, tenant):
    name = collection(tenant)
    if client.has_collection(name):
        return name
    schema = client.create_schema(auto_id=False, enable_dynamic_field=False)
    schema.add_field("id", DataType.VARCHAR, max_length=64, is_primary=True)
    schema.add_field("text", DataType.VARCHAR, max_length=65535)
    schema.add_field(
        "dense",
        DataType.FLOAT_VECTOR,
        dim=int(value("EMBEDDING_DIMENSIONS", "1024")),
    )
    indexes = client.prepare_index_params()
    indexes.add_index(field_name="dense", index_type="AUTOINDEX", metric_type="COSINE")
    try:
        client.create_collection(
            collection_name=name,
            schema=schema,
            index_params=indexes,
            consistency_level="Strong",
        )
    except Exception:
        if not client.has_collection(name):
            raise
    return name


def vectors(client, tenant, texts, record_call=None):
    name = ensure(client, tenant)
    keys = [hashlib.sha256(text.encode("utf-8")).hexdigest() for text in texts]
    unique = dict(zip(keys, texts, strict=True))
    found = {}
    dimension = int(value("EMBEDDING_DIMENSIONS", "1024"))
    unique_keys = list(unique)
    for start in range(0, len(unique_keys), 100):
        batch = unique_keys[start : start + 100]
        rows = client.query(
            collection_name=name,
            filter="id in " + json.dumps(batch),
            output_fields=["id", "text", "dense"],
            limit=len(batch),
            consistency_level="Strong",
        )
        for row in rows:
            key, vector = row["id"], row["dense"]
            if (
                key not in batch
                or row["text"] != unique[key]
                or len(vector) != dimension
                or any(not math.isfinite(number) for number in vector)
            ):
                raise RuntimeError("Embedding cache integrity check failed")
            found[key] = vector
    missing = [key for key in unique if key not in found]
    usage = 0
    # Persist each successful batch. A later failure does not discard reusable vectors.
    for start in range(0, len(missing), 32):
        batch = missing[start : start + 32]
        calculated, consumed = models.embed(
            [unique[key] for key in batch],
            record_call=record_call,
        )
        usage = usage + consumed if usage is not None and consumed is not None else None
        records = [
            {"id": key, "text": unique[key], "dense": vector}
            for key, vector in zip(batch, calculated, strict=True)
        ]
        client.upsert(collection_name=name, data=records)
        found.update({row["id"]: row["dense"] for row in records})
    return [found[key] for key in keys], {
        "embedding_tokens": usage,
        "indexed_chunks": len(texts),
        "embedded_texts": len(missing),
        "reused_chunks": len(texts) - len(missing),
    }
