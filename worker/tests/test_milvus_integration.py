"""Opt-in real BM25 integration. Does not certify embedding/hybrid/rerank."""

import json
import os
import uuid

import pytest

pytestmark = pytest.mark.skipif(
    os.environ.get("RUN_MILVUS_INTEGRATION") != "1",
    reason="Requires a real running Milvus instance",
)


def test_real_chinese_bm25_with_tenant_and_version_filter():
    from pymilvus import DataType, Function, FunctionType, MilvusClient

    c = MilvusClient(
        uri=os.environ.get("MILVUS_URI", "http://localhost:19530"), timeout=30
    )
    name = "cf_bm25_validation_" + uuid.uuid4().hex
    schema = c.create_schema(auto_id=False, enable_dynamic_field=False)
    schema.add_field("id", DataType.VARCHAR, is_primary=True, max_length=36)
    schema.add_field("tenant_id", DataType.VARCHAR, max_length=36)
    schema.add_field("version_id", DataType.VARCHAR, max_length=36)
    schema.add_field(
        "text",
        DataType.VARCHAR,
        max_length=5000,
        enable_analyzer=True,
        analyzer_params={"type": "chinese"},
    )
    schema.add_field("sparse", DataType.SPARSE_FLOAT_VECTOR)
    schema.add_function(
        Function(
            name="bm25",
            function_type=FunctionType.BM25,
            input_field_names=["text"],
            output_field_names=["sparse"],
        )
    )
    indexes = c.prepare_index_params()
    indexes.add_index(
        field_name="sparse", index_type="SPARSE_INVERTED_INDEX", metric_type="BM25"
    )
    tenant, other, version, old = [str(uuid.uuid4()) for _ in range(4)]
    rows = [
        {
            "id": str(uuid.uuid4()),
            "tenant_id": t,
            "version_id": v,
            "text": "CF-100 故障码 E404 需要检查网络连接",
        }
        for t, v in [(tenant, version), (other, version), (tenant, old)]
    ]
    try:
        c.create_collection(
            collection_name=name,
            schema=schema,
            index_params=indexes,
            consistency_level="Strong",
        )
        c.insert(collection_name=name, data=rows)
        c.flush(collection_name=name)
        c.release_collection(collection_name=name)
        c.load_collection(collection_name=name)
        result = c.search(
            collection_name=name,
            data=["CF-100 E404 连接"],
            anns_field="sparse",
            filter="tenant_id == "
            + json.dumps(tenant)
            + " and version_id in "
            + json.dumps([version]),
            limit=10,
            consistency_level="Strong",
            search_params={"metric_type": "BM25"},
        )
        assert [hit["id"] for hit in result[0]] == [rows[0]["id"]]
    finally:
        if c.has_collection(name):
            c.drop_collection(name)
