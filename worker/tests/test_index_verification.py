import uuid

from careflow import index_verification as verification


class Iterator:
    def __init__(self, batches):
        self.batches = iter(batches)
        self.closed = False

    def next(self):
        return next(self.batches, [])

    def close(self):
        self.closed = True


class Store:
    def __init__(self, batches):
        self.iterator = Iterator(batches)
        self.request = None

    def has_collection(self, name):
        return True

    def query_iterator(self, **request):
        self.request = request
        return self.iterator


def test_full_iterator_detects_missing_extra_changed_and_duplicate_rows(monkeypatch):
    monkeypatch.setenv("EMBEDDING_DIMENSIONS", "2")
    tenant, version, generation, first, second, third, extra = [
        str(uuid.uuid4()) for _ in range(7)
    ]

    def row(key, text):
        return {
            "id": str(uuid.uuid5(uuid.UUID(generation), key)),
            "chunk_id": key,
            "text": text,
            "dense": [1, 2],
            "vector_hash": verification.vector_hash([1, 2]),
        }

    store = Store(
        [
            [row(first, "unchanged")],
            [row(second, "corrupt"), row(extra, "orphan")],
            [row(first, "unchanged")],
        ]
    )
    result = verification.verify(
        store,
        "synthetic",
        tenant,
        version,
        generation,
        {
            first: verification.content_hash("unchanged"),
            second: verification.content_hash("expected"),
            third: verification.content_hash("missing"),
        },
    )
    assert not result["consistent"]
    assert result["missing_count"] == result["extra_count"] == 1
    assert result["mismatched_count"] == 2
    assert store.iterator.closed
    assert tenant in store.request["filter"] and generation in store.request["filter"]


def test_clean_generation_manifest_matches_exact_content_and_rejects_wrong_physical_id(
    monkeypatch,
):
    monkeypatch.setenv("EMBEDDING_DIMENSIONS", "2")
    tenant, version, generation, chunk = [str(uuid.uuid4()) for _ in range(4)]
    expected = {chunk: verification.content_hash("中文😀\n")}
    row = {
        "id": str(uuid.uuid5(uuid.UUID(generation), chunk)),
        "chunk_id": chunk,
        "text": "中文😀\n",
        "dense": [1, 2],
        "vector_hash": verification.vector_hash([1, 2]),
    }
    result = verification.verify(
        Store([[row]]), "synthetic", tenant, version, generation, expected
    )
    assert result["consistent"] and result["manifest"] == verification.manifest(
        expected
    )
    row["dense"] = [2, 1]
    result = verification.verify(
        Store([[row]]), "synthetic", tenant, version, generation, expected
    )
    assert not result["consistent"] and result["mismatched_count"] == 1
    row["dense"] = [1, 2]
    row["id"] = str(uuid.uuid4())
    result = verification.verify(
        Store([[row]]), "synthetic", tenant, version, generation, expected
    )
    assert not result["consistent"] and result["mismatched_count"] == 1


def test_legacy_index_is_verified_without_generation_fields(monkeypatch):
    monkeypatch.setenv("EMBEDDING_DIMENSIONS", "2")
    tenant, version, chunk = [str(uuid.uuid4()) for _ in range(3)]
    store = Store([[{"id": chunk, "text": "legacy", "dense": [1, 2]}]])
    result = verification.verify(
        store,
        "synthetic",
        tenant,
        version,
        None,
        {chunk: verification.content_hash("legacy")},
    )
    assert result["consistent"] and "chunk_id" not in store.request["output_fields"]
