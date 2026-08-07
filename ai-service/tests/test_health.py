import httpx
import pytest

from app.main import app


@pytest.mark.asyncio
async def test_health_and_readiness() -> None:
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://test"
    ) as client:
        health = await client.get("/health")
        ready = await client.get("/ready")

    assert health.status_code == 200
    assert health.json()["status"] == "ok"
    assert "timestamp" in health.json()
    assert ready.status_code == 200
    assert ready.json() == {"status": "ready"}

