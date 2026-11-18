from typing import Any

import httpx
import pytest

from app.classification_models import (
    ClassificationResult,
    ClassifyRequest,
    ClassifyResponse,
    ModelOutput,
)
from app.classifier import CONTRACT_VERSION, PROMPT_VERSION, Classifier
from app.config import Settings
from app.main import app


def candidate(merchant_key: str = "netflix") -> dict[str, object]:
    return {
        "merchant_key": merchant_key,
        "merchant_name": "NETFLIX.COM",
        "pfc_primary": "ENTERTAINMENT",
        "pfc_detailed": "ENTERTAINMENT_TV_AND_MOVIES",
    }


def result(merchant_key: str) -> ClassificationResult:
    return ClassificationResult(
        merchant_key=merchant_key,
        display_name=merchant_key.title(),
        category="streaming",
        confidence=0.95,
        recurring_signal=True,
        reasoning="Known streaming service",
    )


@pytest.mark.asyncio
async def test_rules_only_response_returns_no_model_results() -> None:
    classifier = Classifier(Settings(local_llm_mode=False))
    request = ClassifyRequest.model_validate({"candidates": [candidate()]})

    response = await classifier.classify(request)

    assert response.degraded is True
    assert response.prompt_version == PROMPT_VERSION
    assert response.classifier_version == (
        f"{PROMPT_VERSION}/{CONTRACT_VERSION}/{classifier.model}"
    )
    assert response.results == []


class FakeClassifier:
    model = "test-model"
    classifier_version = f"{PROMPT_VERSION}/{CONTRACT_VERSION}/{model}"

    async def classify(self, request: ClassifyRequest) -> ClassifyResponse:
        return ClassifyResponse(
            results=[result(item.merchant_key) for item in request.candidates],
            model=self.model,
            prompt_version=PROMPT_VERSION,
            classifier_version=self.classifier_version,
        )


@pytest.mark.asyncio
async def test_endpoint_accepts_slim_candidate_contract() -> None:
    from app.routers.classify import get_classifier

    app.dependency_overrides[get_classifier] = FakeClassifier
    try:
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://test"
        ) as client:
            response = await client.post(
                "/internal/classify",
                json={"candidates": [candidate(), candidate("gym-membership")]},
            )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    assert [item["merchant_key"] for item in response.json()["results"]] == [
        "netflix",
        "gym-membership",
    ]


@pytest.mark.asyncio
async def test_bad_payload_uses_compatible_error_envelope() -> None:
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://test"
    ) as client:
        response = await client.post("/internal/classify", json={"candidates": []})

    assert response.status_code == 422
    assert response.json()["error"] == "Validation error"
    assert response.json()["code"] == "VALIDATION_ERROR"
    assert "detail" not in response.json()


@pytest.mark.asyncio
async def test_unreachable_ollama_returns_no_results() -> None:
    classifier = Classifier(
        Settings(
            local_llm_mode=True,
            ollama_base_url="http://127.0.0.1:1",
            ollama_timeout_seconds=0.05,
        )
    )
    request = ClassifyRequest.model_validate({"candidates": [candidate()]})

    response = await classifier.classify(request)

    assert response.degraded is True
    assert response.results == []


def test_partial_model_output_does_not_fill_unanswered_keys() -> None:
    classifier = Classifier(Settings())
    request = ClassifyRequest.model_validate(
        {"candidates": [candidate(f"merchant-{index}") for index in range(10)]}
    )
    output = ModelOutput(results=[result(f"merchant-{index}") for index in range(8)])

    response = classifier._normalize(request, output)

    assert response.degraded is True
    assert len(response.results) == 8
    assert {item.merchant_key for item in response.results} == {
        f"merchant-{index}" for index in range(8)
    }


def test_ollama_output_schema_contains_no_cache_provenance() -> None:
    schema = ModelOutput.model_json_schema()

    assert "source" not in str(schema)
    assert "classifier_version" not in str(schema)


@pytest.mark.asyncio
async def test_ollama_prompt_has_one_input_copy_and_explicit_context(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, Any] = {}

    class FakeResponse:
        def raise_for_status(self) -> None:
            return None

        def json(self) -> dict[str, Any]:
            return {
                "message": {"content": ModelOutput(results=[result("netflix")]).model_dump_json()},
                "prompt_eval_count": 100,
                "eval_count": 30,
                "total_duration": 123,
            }

    class FakeAsyncClient:
        def __init__(self, timeout: float) -> None:
            captured["timeout"] = timeout

        async def __aenter__(self) -> "FakeAsyncClient":
            return self

        async def __aexit__(self, *args: object) -> None:
            return None

        async def post(self, endpoint: str, json: dict[str, Any]) -> FakeResponse:
            captured["endpoint"] = endpoint
            captured["payload"] = json
            return FakeResponse()

    monkeypatch.setattr(httpx, "AsyncClient", FakeAsyncClient)
    classifier = Classifier(Settings(ollama_num_ctx=8192))

    await classifier._call_ollama(
        ClassifyRequest.model_validate({"candidates": [candidate()]})
    )

    payload = captured["payload"]
    prompt = payload["messages"][1]["content"]
    assert prompt.count("netflix") == 1
    assert "JSON schema" not in prompt
    assert payload["options"] == {"temperature": 0, "num_ctx": 8192}
    assert payload["format"]["properties"]["results"]["minItems"] == 1
    assert payload["format"]["properties"]["results"]["maxItems"] == 1
    assert payload["format"]["$defs"]["ClassificationResult"]["properties"][
        "merchant_key"
    ]["enum"] == ["netflix"]


def test_ollama_context_setting_has_default_and_override() -> None:
    assert Settings(_env_file=None).ollama_num_ctx == 4096
    assert Settings(_env_file=None, ollama_num_ctx=8192).ollama_num_ctx == 8192


@pytest.mark.asyncio
async def test_metadata_does_not_contact_ollama() -> None:
    from app.routers.classify import get_classifier

    classifier = Classifier(
        Settings(local_llm_mode=True, ollama_base_url="http://127.0.0.1:1")
    )
    app.dependency_overrides[get_classifier] = lambda: classifier
    try:
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://test"
        ) as client:
            response = await client.get("/internal/classify/metadata")
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    assert response.json() == {
        "classifier_version": classifier.classifier_version,
        "model": classifier.model,
        "prompt_version": PROMPT_VERSION,
    }
