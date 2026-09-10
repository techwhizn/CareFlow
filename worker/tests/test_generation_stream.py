import asyncio
import json

import httpx
import pytest

from careflow import models


def test_generation_usage_and_missing_usage_are_distinct_and_not_text():
    decoder = models.GenerationDecoder()
    events = decoder.events(
        'data: {"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":4,"total_tokens":16}}'
    )
    assert events == [
        {"usage": {"input_tokens": 12, "output_tokens": 4, "total_tokens": 16}}
    ]
    assert decoder.events("data: [DONE]") == [{"done": True}]
    assert models.GenerationDecoder().events("data: [DONE]") == [
        {"usage": {}},
        {"done": True},
    ]
    assert models.GenerationDecoder().events(
        'data: {"usage":{"total_tokens":true}}'
    ) == [{"usage": {}}]


def test_async_network_read_cancellation_closes_upstream(monkeypatch):
    async def scenario():
        closed, waiting = asyncio.Event(), asyncio.Event()

        class Delayed(httpx.AsyncByteStream):
            async def __aiter__(self):
                yield b'data: {"choices":[{"delta":{"content":"first"}}]}\n\n'
                waiting.set()
                await asyncio.Event().wait()

            async def aclose(self):
                closed.set()

        real_client = httpx.AsyncClient
        monkeypatch.setenv("GENERATION_BASE_URL", "https://fixture.invalid/v1")
        monkeypatch.setenv("GENERATION_MODEL", "synthetic")
        monkeypatch.setattr(
            models.httpx,
            "AsyncClient",
            lambda **kwargs: real_client(
                transport=httpx.MockTransport(
                    lambda request: httpx.Response(200, stream=Delayed())
                ),
                **kwargs,
            ),
        )
        received = []

        async def consume():
            async for line in models.generate_stream_async(
                "fixture", [{"id": "a", "content": "source"}]
            ):
                received.append(json.loads(line))

        task = asyncio.create_task(consume())
        await asyncio.wait_for(waiting.wait(), 2)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        await asyncio.wait_for(closed.wait(), 2)
        assert received == [{"text": "first"}]

    asyncio.run(scenario())
