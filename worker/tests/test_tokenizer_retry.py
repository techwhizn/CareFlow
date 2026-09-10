import httpx
import pytest

from careflow import models


@pytest.mark.parametrize(
    "status,retryable", [(429, True), (503, True), (401, False), (400, False)]
)
def test_tokenizer_transient_failure_remains_retryable(monkeypatch, status, retryable):
    monkeypatch.setattr(
        models, "endpoint", lambda *_: ("http://model.test/tokenize", "synthetic", {})
    )
    client = httpx.Client(
        transport=httpx.MockTransport(lambda request: httpx.Response(status, json={}))
    )
    monkeypatch.setattr(models.httpx, "Client", lambda **_: client)
    with pytest.raises(models.ModelUnavailable) as failure:
        models.input_tokens(["Synthetic"], tokenizer="provider")
    assert failure.value.retryable is retryable


def test_tokenizer_transport_failure_is_retryable(monkeypatch):
    monkeypatch.setattr(
        models, "endpoint", lambda *_: ("http://model.test/tokenize", "synthetic", {})
    )

    def fail(request):
        raise httpx.ConnectError("synthetic connection failure", request=request)

    client = httpx.Client(transport=httpx.MockTransport(fail))
    monkeypatch.setattr(models.httpx, "Client", lambda **_: client)
    with pytest.raises(models.ModelUnavailable) as failure:
        models.input_tokens(["Synthetic"], tokenizer="provider")
    assert failure.value.retryable
