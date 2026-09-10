"""Milvus dense + native BM25 retrieval. MySQL remains permission authority."""

import json
import os
import uuid
from collections import defaultdict
from functools import lru_cache

from pymilvus import DataType, Function, FunctionType, MilvusClient

from careflow import embedding_cache, index_verification, models
from careflow.model_configuration import value


def collection(tenant, generations=False):
    return (
        ("cf2_" if generations else "cf_")
        + uuid.UUID(tenant).hex
        + "_"
        + (models.identity() if generations else models.identity()[:20])
    )


@lru_cache(maxsize=1)
def client():
    uri = os.environ.get("MILVUS_URI", "http://localhost:19530")
    return MilvusClient(uri=uri, token=os.environ.get("MILVUS_TOKEN", ""), timeout=30)


def ensure_collection(tenant, generations=False):
    name = collection(tenant, generations)
    c = client()
    if c.has_collection(name):
        return name
    schema = c.create_schema(auto_id=False, enable_dynamic_field=False)
    schema.add_field("id", DataType.VARCHAR, max_length=36, is_primary=True)
    if generations:
        schema.add_field("chunk_id", DataType.VARCHAR, max_length=36)
        schema.add_field("vector_hash", DataType.VARCHAR, max_length=64)
        schema.add_field("generation_id", DataType.VARCHAR, max_length=36)
    schema.add_field("tenant_id", DataType.VARCHAR, max_length=36)
    schema.add_field("version_id", DataType.VARCHAR, max_length=36)
    schema.add_field(
        "text",
        DataType.VARCHAR,
        max_length=65535,
        enable_analyzer=True,
        analyzer_params={"type": "chinese"},
    )
    schema.add_field(
        "dense",
        DataType.FLOAT_VECTOR,
        dim=int(value("EMBEDDING_DIMENSIONS", "1024")),
    )
    schema.add_field("sparse", DataType.SPARSE_FLOAT_VECTOR)
    schema.add_function(
        Function(
            name="bm25",
            input_field_names=["text"],
            output_field_names=["sparse"],
            function_type=FunctionType.BM25,
        )
    )
    indexes = c.prepare_index_params()
    indexes.add_index(field_name="dense", index_type="AUTOINDEX", metric_type="COSINE")
    indexes.add_index(
        field_name="sparse",
        index_type="SPARSE_INVERTED_INDEX",
        metric_type="BM25",
        params={"inverted_index_algo": "DAAT_MAXSCORE"},
    )
    try:
        c.create_collection(
            collection_name=name,
            schema=schema,
            index_params=indexes,
            consistency_level="Strong",
        )
    except Exception:
        if not c.has_collection(name):
            raise
    return name


def index(
    tenant,
    version,
    chunks,
    chunking=None,
    record_call=None,
    generation_id=None,
    before_write=None,
):
    import tiktoken

    if not chunks:
        raise ValueError("Cannot index empty document")
    encoding = tiktoken.get_encoding("cl100k_base")
    if any(
        len(encoding.encode(c["content"], disallowed_special=())) > 600 for c in chunks
    ):
        raise ValueError("Edited chunk exceeds 600-token limit")
    if chunking:
        counts, actual_limit = models.input_tokens(
            [c["content"] for c in chunks],
            chunking.model_tokenizer,
            **({"before_batch": before_write} if before_write else {}),
        )
        if any(count > min(actual_limit, chunking.model_maximum) for count in counts):
            raise ValueError("Edited chunk exceeds model input limit")
        if any(
            len(encoding.encode(c["content"], disallowed_special=())) > chunking.maximum
            for c in chunks
        ):
            raise ValueError("Edited chunk exceeds configured token limit")
    name = ensure_collection(tenant, generation_id is not None)
    vectors, metrics = embedding_cache.vectors(
        client(), tenant, [c["content"] for c in chunks], record_call, before_write
    )
    records = [
        {
            "id": str(uuid.UUID(row["id"])),
            "tenant_id": str(uuid.UUID(tenant)),
            "version_id": str(uuid.UUID(version)),
            "text": row["content"],
            "dense": vector,
        }
        for row, vector in zip(chunks, vectors, strict=True)
    ]
    if generation_id:
        generation_id = str(uuid.UUID(generation_id))
        for record in records:
            record.update(
                chunk_id=record["id"],
                generation_id=generation_id,
                vector_hash=index_verification.vector_hash(record["dense"]),
            )
            record["id"] = str(uuid.uuid5(uuid.UUID(generation_id), record["chunk_id"]))
    c = client()
    for start in range(0, len(records), 100):
        batch = records[start : start + 100]
        if before_write:
            before_write()
        c.upsert(collection_name=name, data=batch)
    verification = index_verification.verify(
        c,
        name,
        tenant,
        version,
        generation_id,
        {
            str(uuid.UUID(row["id"])): index_verification.content_hash(row["content"])
            for row in chunks
        },
    )
    if not verification["consistent"]:
        raise RuntimeError("Indexed records failed full consistency verification")
    return {
        "verified": True,
        "model_identity": models.identity(),
        **metrics,
        "generation_id": generation_id,
        "manifest": verification["manifest"],
    }


def rrf(*lanes, k=60, limit=40):
    scores = defaultdict(float)
    for lane in lanes:
        seen = set()
        for rank, hit in enumerate(lane, 1):
            if hit["id"] not in seen:
                scores[hit["id"]] += 1 / (k + rank)
                seen.add(hit["id"])
    return [
        {"id": key, "score": value}
        for key, value in sorted(scores.items(), key=lambda pair: (-pair[1], pair[0]))[
            :limit
        ]
    ]


def recall(
    tenant, versions, query, mode="hybrid", allow_degraded=False, generation_ids=None
):
    tenant = str(uuid.UUID(tenant))
    versions = [str(uuid.UUID(v)) for v in versions]
    if not versions:
        return {"dense": [], "bm25": [], "fused": [], "degraded": False}
    if mode not in {"hybrid", "semantic", "keyword"}:
        raise ValueError("Invalid retrieval mode")
    name = collection(tenant, generation_ids is not None)
    c = client()
    if not c.has_collection(name):
        # Published data routed to a different model must surface a configuration error.
        raise models.ModelUnavailable("No index for configured embedding identity")
    expression = (
        "tenant_id == "
        + json.dumps(tenant)
        + " and version_id in "
        + json.dumps(versions)
    )
    if generation_ids is not None:
        if not generation_ids:
            raise ValueError("Generation scope must not be empty")
        expression += " and generation_id in " + json.dumps(
            [str(uuid.UUID(g)) for g in generation_ids]
        )
    fields = ["chunk_id"] if generation_ids is not None else ["id"]

    def hit_id(hit):
        return hit["entity"]["chunk_id"] if generation_ids is not None else hit["id"]

    dense, sparse, degraded = [], [], False
    if mode != "keyword":
        try:
            vectors, _ = models.embed([query])
        except (models.ModelUnavailable, __import__("httpx").HTTPError):
            if not allow_degraded:
                raise
            degraded = True
        else:
            dense = [
                {"id": hit_id(h), "score": h["distance"]}
                for h in c.search(
                    collection_name=name,
                    data=vectors,
                    anns_field="dense",
                    output_fields=fields,
                    filter=expression,
                    limit=40,
                    search_params={"metric_type": "COSINE"},
                    consistency_level="Strong",
                )[0]
            ]
    if mode != "semantic" or degraded:
        sparse = [
            {"id": hit_id(h), "score": h["distance"]}
            for h in c.search(
                collection_name=name,
                data=[query],
                anns_field="sparse",
                output_fields=fields,
                filter=expression,
                limit=40,
                search_params={"metric_type": "BM25"},
                consistency_level="Strong",
            )[0]
        ]
    return {
        "dense": dense,
        "bm25": sparse,
        "fused": rrf(dense, sparse),
        "degraded": degraded,
    }
