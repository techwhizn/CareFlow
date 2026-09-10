"""Maintenance boundaries exercised with an explicit storage double."""

from unittest.mock import Mock
from uuid import uuid4

import pytest

from careflow import index_cleanup as cleanup


def test_no_eligible_compaction_plan_is_not_a_pollable_job():
    client = Mock()
    client.compact.return_value = -1
    assert cleanup.compact(client, "synthetic") is None
    client.flush.assert_called_once()
    client.compact.return_value = 0
    with pytest.raises(RuntimeError, match="Invalid compaction"):
        cleanup.compact(client, "synthetic")


def test_version_deletion_never_touches_another_tenant_or_cache():
    tenant, other, version, generation = map(str, [uuid4() for _ in range(4)])
    current = cleanup.tenant_prefix(tenant, "cf2_") + "a" * 64
    legacy = cleanup.tenant_prefix(tenant, "cf_") + "b" * 16
    client = Mock()
    client.list_collections.return_value = [
        current,
        legacy,
        cleanup.tenant_prefix(other, "cf2_") + "a" * 64,
        cleanup.tenant_prefix(tenant, "cfec2_") + "a" * 64,
    ]
    client.query.return_value = []
    client.compact.return_value = 9
    result = cleanup.purge_version(client, tenant, version, generation)
    assert result == {
        "verified": True,
        "compactions": [{"collection": current, "job_id": 9}],
    }
    client.delete.assert_called_once()
    args = client.delete.call_args.kwargs
    assert args["collection_name"] == current
    assert all(value in args["filter"] for value in [tenant, version, generation])
    client.reset_mock()
    cleanup.purge_version(client, tenant, version)
    assert {
        call.kwargs["collection_name"] for call in client.delete.call_args_list
    } == {current, legacy}


def test_visible_rows_or_incomplete_compaction_cannot_report_completion():
    tenant = str(uuid4())
    name = cleanup.tenant_prefix(tenant, "cfec2_") + "a" * 64
    client = Mock()
    client.has_collection.return_value = True
    client.query.return_value = [{"id": "b" * 64}]
    with pytest.raises(RuntimeError, match="remain visible"):
        cleanup.purge_cache(
            client, tenant, [{"model_identity": "a" * 64, "content_hash": "b" * 64}]
        )
    client.compact.assert_not_called()
    client.get_compaction_state.return_value = "Executing"
    jobs = [{"collection": name, "job_id": 7}]
    assert cleanup.compaction_state(client, tenant, jobs) == {"complete": False}
    client.get_compaction_state.return_value = "Completed"
    assert cleanup.compaction_state(client, tenant, jobs) == {"complete": True}
    with pytest.raises(ValueError, match="scope mismatch"):
        cleanup.compaction_state(client, str(uuid4()), jobs)


def test_cache_eviction_and_legacy_upgrade_are_exactly_scoped():
    tenant = str(uuid4())
    prefix = cleanup.tenant_prefix(tenant, "cfec_")
    client = Mock()
    client.list_collections.return_value = [
        prefix + "a" * 64,
        cleanup.tenant_prefix(tenant, "cfec2_") + "a" * 64,
    ]
    cleanup.purge_legacy_cache(client, tenant)
    client.drop_collection.assert_called_once_with(
        collection_name=prefix + "a" * 64, timeout=30
    )
    client.has_collection.return_value = False
    assert cleanup.purge_cache(
        client, tenant, [{"model_identity": "a" * 64, "content_hash": "b" * 64}]
    )["verified"]
    client.delete.assert_not_called()
    with pytest.raises(ValueError, match="Invalid cache identity"):
        cleanup.purge_cache(
            client, tenant, [{"model_identity": "a", "content_hash": "b" * 64}]
        )
