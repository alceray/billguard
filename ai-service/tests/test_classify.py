import httpx
import pytest

from app.main import app


def transaction(transaction_id: str) -> dict[str, object]:
    return {
        "id": transaction_id,
        "merchant_name": "Example Co",
        "name": "Example charge",
        "amount": "12.99",
        "currency": "CAD",
        "date": "2026-08-07",
    }


@pytest.mark.asyncio
async def test_classify_returns_one_bounded_result_per_transaction() -> None:
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://test"
    ) as client:
        response = await client.post(
            "/internal/classify", json={"transactions": [transaction("one"), transaction("two")]}
        )

    assert response.status_code == 200
    body = response.json()
    assert body["model"] == "stub"
    assert [result["transaction_id"] for result in body["results"]] == ["one", "two"]
    assert all(0 <= result["confidence"] <= 1 for result in body["results"])


@pytest.mark.asyncio
async def test_bad_payload_uses_compatible_error_envelope() -> None:
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://test"
    ) as client:
        response = await client.post("/internal/classify", json={"transactions": [{}]})

    assert response.status_code == 422
    assert response.json()["error"] == "Validation error"
    assert response.json()["code"] == "VALIDATION_ERROR"
    assert "detail" not in response.json()

