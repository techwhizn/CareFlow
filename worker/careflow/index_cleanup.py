"""Idempotent physical-store deletion. Java supplies only fenced maintenance scope."""

import json
import re
import uuid
from collections import defaultdict


def tenant_prefix(tenant, kind):
    return kind + uuid.UUID(tenant).hex + "_"


def compact(client, name):
    client.flush(collection_name=name, timeout=30)
    job_id = client.compact(collection_name=name, timeout=30)
    # Milvus 2.6 returns -1 when ManualCompaction has zero eligible plans.
    # It is not a job ID and must never be polled as one.
    if job_id == -1:
        return None
    if type(job_id) is not int or job_id < 1:
        raise RuntimeError("Invalid compaction identifier")
    return {"collection": name, "job_id": job_id}


def purge_version(client, tenant, version, generation=None):
    tenant, version = str(uuid.UUID(tenant)), str(uuid.UUID(version))
    prefixes = [tenant_prefix(tenant, "cf2_")]
    if generation is None:
        prefixes.append(tenant_prefix(tenant, "cf_"))
    expression = (
        "tenant_id == "
        + json.dumps(tenant)
        + " and version_id == "
        + json.dumps(version)
    )
    if generation is not None:
        expression += " and generation_id == " + json.dumps(str(uuid.UUID(generation)))
    jobs = []
    for name in client.list_collections():
        if not any(name.startswith(prefix) for prefix in prefixes):
            continue
        client.delete(collection_name=name, filter=expression, timeout=30)
        remaining = client.query(
            collection_name=name,
            filter=expression,
            output_fields=["id"],
            limit=1,
            consistency_level="Strong",
        )
        if remaining:
            raise RuntimeError("Deleted index rows remain visible")
        if plan := compact(client, name):
            jobs.append(plan)
    return {"verified": True, "compactions": jobs}


def purge_cache(client, tenant, entries):
    grouped = defaultdict(list)
    for entry in entries:
        model, digest = entry["model_identity"], entry["content_hash"]
        if not re.fullmatch(r"[a-f0-9]{64}", model) or not re.fullmatch(
            r"[a-f0-9]{64}", digest
        ):
            raise ValueError("Invalid cache identity")
        grouped[model].append(digest)
    jobs = []
    for model, hashes in grouped.items():
        name = tenant_prefix(tenant, "cfec2_") + model
        if not client.has_collection(name):
            continue
        expression = "id in " + json.dumps(hashes)
        client.delete(collection_name=name, filter=expression, timeout=30)
        if client.query(
            collection_name=name,
            filter=expression,
            output_fields=["id"],
            limit=1,
            consistency_level="Strong",
        ):
            raise RuntimeError("Deleted cache rows remain visible")
        if plan := compact(client, name):
            jobs.append(plan)
    return {"verified": True, "compactions": jobs}


def purge_legacy_cache(client, tenant):
    # V21/V22 caches have no durable version ownership. They contain derived data only;
    # new consumers exclusively use cfec2_, whose inputs are registered by Java.
    prefix = tenant_prefix(tenant, "cfec_")
    for name in client.list_collections():
        if name.startswith(prefix):
            client.drop_collection(collection_name=name, timeout=30)
    return {"verified": True, "compactions": []}


def compaction_state(client, tenant, jobs):
    prefixes = [tenant_prefix(tenant, kind) for kind in ("cf_", "cf2_", "cfec2_")]
    for job in jobs:
        if not any(job["collection"].startswith(prefix) for prefix in prefixes):
            raise ValueError("Compaction scope mismatch")
        if not client.has_collection(job["collection"]):
            continue
        if client.get_compaction_state(job_id=job["job_id"], timeout=30) != "Completed":
            return {"complete": False}
    return {"complete": True}
