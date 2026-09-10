import asyncio
import importlib.util
from pathlib import Path

import httpx
import pytest

SPEC = importlib.util.spec_from_file_location(
    "performance_benchmark",
    Path(__file__).resolve().parents[2] / "scripts/performance-benchmark.py",
)
benchmark = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(benchmark)


def test_summary_preserves_failures_and_missing_timing_instead_of_zero():
    result = benchmark.summary(
        [
            {"outcome": "OK", "elapsed_ms": 300},
            {"outcome": "HTTP_503", "elapsed_ms": 10},
            {"outcome": "LOAD_GENERATOR_CAPACITY"},
        ],
        2,
    )
    assert result["failure_fraction"] == pytest.approx(2 / 3)
    assert result["elapsed_ms"]["p95"] == 300
    assert result["pure_retrieval_ms"]["samples"] == 0
    assert result["pure_retrieval_ms"]["p95"] is None
    assert result["successful_qps"] == 0.5


def test_answer_probe_requires_nonempty_answer_delta_not_status_or_empty_text():
    async def run(body):
        async with httpx.AsyncClient(
            base_url="https://synthetic.invalid",
            transport=httpx.MockTransport(
                lambda request: httpx.Response(200, text=body)
            ),
        ) as client:
            return await benchmark.request(
                client, {"token": "synthetic", "kb_id": "synthetic"}, 0, 0, "answer"
            )

    empty = (
        'event: status\ndata: {"text":"working"}\n\nevent: delta\ndata: {"text":""}\n\n'
    )
    assert asyncio.run(run(empty))["outcome"] == "NO_ANSWER_TOKEN"
    complete = asyncio.run(run(empty + 'event: delta\ndata: {"text":"answer"}\n\n'))
    assert complete["outcome"] == "OK"
    assert "first_token_ms" in complete
    assert "answer" not in str(complete)


def test_saturated_generator_records_dropped_arrivals(monkeypatch):
    async def delayed(*args):
        await asyncio.sleep(0.06)
        return {"sequence": args[3], "outcome": "OK", "elapsed_ms": 60}

    monkeypatch.setattr(benchmark, "request", delayed)
    result = asyncio.run(
        benchmark.run_stage(None, [{"token": "synthetic"}], "api", 4, 100, 1)
    )
    assert result["summary"]["offered"] == 4
    assert result["summary"]["outcomes"]["LOAD_GENERATOR_CAPACITY"] == 3
