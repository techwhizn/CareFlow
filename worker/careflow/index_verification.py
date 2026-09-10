"""Exhaustive scalar verification of a single version/generation, with bounded reports."""

import hashlib
import json
import math
import struct
import uuid

from careflow.model_configuration import value


def content_hash(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def vector_hash(vector):
    # Milvus stores FLOAT_VECTOR as IEEE-754 float32; hash the stored representation.
    return hashlib.sha256(
        b"".join(struct.pack("<f", float(n)) for n in vector)
    ).hexdigest()


def manifest(rows):
    return hashlib.sha256(
        "".join(
            key + ":" + digest + "\n" for key, digest in sorted(rows.items())
        ).encode("ascii")
    ).hexdigest()


def verify(client, name, tenant, version, generation, expected):
    actual, invalid, duplicates = {}, set(), set()
    if client.has_collection(name):
        expression = (
            "tenant_id == "
            + json.dumps(str(uuid.UUID(tenant)))
            + " and version_id == "
            + json.dumps(str(uuid.UUID(version)))
        )
        fields = ["id", "text", "dense"]
        if generation:
            expression += " and generation_id == " + json.dumps(
                str(uuid.UUID(generation))
            )
            fields.extend(["chunk_id", "vector_hash"])
        iterator = client.query_iterator(
            collection_name=name,
            filter=expression,
            output_fields=fields,
            batch_size=200,
            consistency_level="Strong",
            timeout=30,
        )
        try:
            while rows := iterator.next():
                for row in rows:
                    key = row["chunk_id"] if generation else row["id"]
                    key = str(uuid.UUID(key))
                    if key in actual:
                        duplicates.add(key)
                    actual[key] = content_hash(row["text"])
                    vector = row["dense"]
                    if len(vector) != int(value("EMBEDDING_DIMENSIONS", "1024")) or any(
                        not math.isfinite(n) for n in vector
                    ):
                        invalid.add(key)
                    if generation and row["vector_hash"] != vector_hash(vector):
                        invalid.add(key)
                    if generation and row["id"] != str(
                        uuid.uuid5(uuid.UUID(generation), key)
                    ):
                        invalid.add(key)
                if len(actual) > 50000:
                    raise RuntimeError("Index verification exceeds version limit")
        finally:
            iterator.close()
    missing = set(expected) - set(actual)
    extra = set(actual) - set(expected)
    mismatched = (
        {key for key in expected.keys() & actual.keys() if expected[key] != actual[key]}
        | invalid
        | duplicates
    )
    return {
        "consistent": not (missing or extra or mismatched),
        "expected_count": len(expected),
        "actual_count": len(actual),
        "missing_count": len(missing),
        "extra_count": len(extra),
        "mismatched_count": len(mismatched),
        "manifest": manifest(actual),
        "samples": {
            "missing": sorted(missing)[:50],
            "extra": sorted(extra)[:50],
            "mismatched": sorted(mismatched)[:50],
        },
    }
