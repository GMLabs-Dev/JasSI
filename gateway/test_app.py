from fastapi.testclient import TestClient

from app import app

client = TestClient(app)


def test_health() -> None:
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_privacy_mode_blocks_cloud_requests() -> None:
    response = client.post(
        "/v1/complete",
        json={"prompt": "hello", "privacy_mode": True},
    )
    assert response.status_code == 403


def test_missing_provider_key_is_reported(monkeypatch) -> None:
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    monkeypatch.setenv("JARVIS_GATEWAY_TOKEN", "test")
    response = client.post(
        "/v1/complete",
        json={"prompt": "hello", "privacy_mode": False, "provider": "openai"},
    )
    assert response.status_code == 503
