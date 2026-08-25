import json
from pathlib import Path

import httpx
import pytest

from app.classification_models import ClassifyRequest, ClassifyResponse
from app.classifier import PROMPT_VERSION, Classifier
from app.config import Settings
from app.main import app


def stream(merchant_key: str = "netflix") -> dict[str, object]:
    return {
        "merchant_key": merchant_key,
        "merchant_name": "NETFLIX.COM",
        "transaction_count": 3,
        "amounts": ["15.49", "15.49", "15.49"],
        "dates": ["2026-06-01", "2026-07-01", "2026-08-01"],
        "currency": "CAD",
        "pfc_primary": "ENTERTAINMENT",
        "pfc_detailed": "ENTERTAINMENT_TV_AND_MOVIES",
    }


@pytest.mark.asyncio
async def test_rules_only_response_has_stream_contract() -> None:
    classifier = Classifier(Settings(local_llm_mode=False))
    request = ClassifyRequest.model_validate({"streams": [stream()], "singletons": []})

    response = await classifier.classify(request)

    assert response.degraded is True
    assert response.prompt_version == PROMPT_VERSION
    assert response.results[0].merchant_key == "netflix"
    assert response.results[0].category == "streaming"
    assert response.results[0].recurring_signal is True
    assert 0 <= response.results[0].confidence <= 1


class FakeClassifier:
    async def classify(self, request: ClassifyRequest) -> ClassifyResponse:
        classifier = Classifier(Settings(local_llm_mode=False))
        return await classifier.classify(request)


@pytest.mark.asyncio
async def test_endpoint_accepts_streams_and_singletons() -> None:
    from app.routers.classify import get_classifier

    app.dependency_overrides[get_classifier] = FakeClassifier
    try:
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://test"
        ) as client:
            response = await client.post(
                "/internal/classify",
                json={
                    "streams": [stream()],
                    "singletons": [
                        {
                            **stream("gym-membership"),
                            "merchant_name": "CITY FITNESS MEMBERSHIP",
                            "transaction_count": 1,
                            "amounts": ["29.00"],
                            "dates": ["2026-08-01"],
                        }
                    ],
                },
            )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    body = response.json()
    assert [result["merchant_key"] for result in body["results"]] == [
        "netflix",
        "gym-membership",
    ]


@pytest.mark.asyncio
async def test_bad_payload_uses_compatible_error_envelope() -> None:
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://test"
    ) as client:
        response = await client.post("/internal/classify", json={"streams": [], "singletons": []})

    assert response.status_code == 422
    assert response.json()["error"] == "Validation error"
    assert response.json()["code"] == "VALIDATION_ERROR"
    assert "detail" not in response.json()


@pytest.mark.asyncio
async def test_unreachable_ollama_degrades_to_rules() -> None:
    classifier = Classifier(
        Settings(
            local_llm_mode=True,
            ollama_base_url="http://127.0.0.1:1",
            ollama_timeout_seconds=0.05,
        )
    )
    request = ClassifyRequest.model_validate({"streams": [stream()], "singletons": []})

    response = await classifier.classify(request)

    assert response.degraded is True
    assert response.results[0].category == "streaming"


@pytest.mark.asyncio
async def test_labelled_evaluation_fixture_matches_public_rules_contract() -> None:
    cases = json.loads(
        (Path(__file__).parents[1] / "evals" / "classification_cases.json").read_text(
            encoding="utf-8"
        )
    )
    request = ClassifyRequest.model_validate(
        {
            "singletons": [
                {
                    **stream(f"case-{index}"),
                    "merchant_name": case["merchant"],
                    "transaction_count": 1,
                    "amounts": ["9.99"],
                    "dates": ["2026-08-01"],
                    "pfc_detailed": case["pfc"],
                }
                for index, case in enumerate(cases)
            ]
        }
    )

    response = await Classifier(Settings(local_llm_mode=False)).classify(request)

    assert [result.category for result in response.results] == [case["category"] for case in cases]
