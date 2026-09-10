"""Explicit synthetic probes using the same adapters as ingestion and retrieval."""

import json
import os
import time

import httpx

from careflow import models


def check_models():
    report = {"synthetic_input": True, "checks": [], "complete": False}
    candidates = [
        {
            "id": "synthetic-device",
            "content": "CF-100 设备出错时先检查网络再重新启动。",
        },
        {"id": "synthetic-weather", "content": "今天是晴天。"},
    ]

    def embedding():
        identity = models.identity()
        vectors, usage = models.embed([item["content"] for item in candidates])
        return {
            "identity": identity,
            "dimensions": len(vectors[0]),
            "input_tokens": usage,
        }

    def rerank():
        result = models.rerank("设备故障应该怎么处理？", candidates)
        if not result:
            raise models.ModelUnavailable("No ranking returned")
        return {"ranked_count": len(result)}

    def generation():
        complete, characters = False, 0
        for line in models.generate_stream("CF-100 出错应如何处理？", candidates[:1]):
            event = json.loads(line)
            complete = complete or event.get("done") is True
            characters += len(event.get("text", ""))
        if not complete or characters == 0:
            raise models.ModelUnavailable("No completed answer")
        # Generation accounting is separate; no provider usage is fabricated here.
        return {"stream_done": True, "answer_characters": characters}

    for kind, operation in [
        ("EMBEDDING", embedding),
        ("RERANK", rerank),
        ("GENERATION", generation),
    ]:
        start = time.monotonic()
        result = {"kind": kind, "model": os.environ.get(kind + "_MODEL", "")}
        try:
            result.update(operation())
            result["status"] = "passed"
        except httpx.TimeoutException:
            result.update(status="failed", error="MODEL_TIMEOUT")
        except httpx.HTTPStatusError as error:
            status = error.response.status_code
            result.update(
                status="failed",
                error="MODEL_AUTHENTICATION"
                if status in (401, 403)
                else "MODEL_HTTP_ERROR",
                http_status=status,
            )
        except (models.ModelUnavailable, ValueError, TypeError, KeyError, IndexError):
            result.update(status="failed", error="MODEL_CONFIGURATION_OR_FORMAT")
        except httpx.RequestError:
            result.update(status="failed", error="MODEL_CONNECTION")
        result["elapsed_ms"] = round((time.monotonic() - start) * 1000)
        report["checks"].append(result)
    report["complete"] = all(row["status"] == "passed" for row in report["checks"])
    return report
