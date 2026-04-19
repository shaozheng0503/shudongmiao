import io

from fastapi.testclient import TestClient
from PIL import Image

from app.main import app
from app.services import analyzer as analyzer_service
from app.settings import settings


def _make_jpeg_bytes() -> bytes:
    image = Image.new("RGB", (64, 64), color=(180, 180, 180))
    buffer = io.BytesIO()
    image.save(buffer, format="JPEG")
    return buffer.getvalue()


def test_analyze_image_endpoint_returns_structured_response() -> None:
    settings.use_mock_model = True
    client = TestClient(app)

    response = client.post(
        "/api/v1/analyze",
        data={
            "input_text": "它今天躲起来了，而且看起来有点没精神。",
            "media_type": "image",
            "scene_hint": "stress",
        },
        files={"image_file": ("cat.jpg", _make_jpeg_bytes(), "image/jpeg")},
    )

    assert response.status_code == 200
    payload = response.json()
    assert payload["summary"]
    assert payload["disclaimer"]


def test_realtime_frame_fallback_when_model_temporarily_unavailable() -> None:
    settings.use_mock_model = True
    client = TestClient(app)

    async def _raise_model_error(messages):  # noqa: ANN001
        raise RuntimeError("upstream 503")

    original_chat = analyzer_service.minicpm_client.chat
    analyzer_service.minicpm_client.chat = _raise_model_error
    try:
        response = client.post(
            "/api/v1/realtime/frame",
            data={
                "input_text": "实时观察当前帧",
                "scene_hint": "general",
            },
            files={"image_file": ("cat.jpg", _make_jpeg_bytes(), "image/jpeg")},
        )
    finally:
        analyzer_service.minicpm_client.chat = original_chat

    assert response.status_code == 200
    payload = response.json()
    assert payload["summary"]
    assert "model_service_unavailable" in payload["urgent_flags"]


def test_realtime_frame_uses_previous_result_when_model_unavailable() -> None:
    settings.use_mock_model = True
    client = TestClient(app)

    first = client.post(
        "/api/v1/realtime/frame",
        data={
            "input_text": "实时观察当前帧",
            "scene_hint": "general",
            "session_id": "rt-fallback-session",
        },
        files={"image_file": ("cat.jpg", _make_jpeg_bytes(), "image/jpeg")},
    )
    assert first.status_code == 200
    first_summary = first.json()["summary"]

    async def _raise_model_error(messages):  # noqa: ANN001
        raise RuntimeError("upstream 503")

    original_chat = analyzer_service.minicpm_client.chat
    analyzer_service.minicpm_client.chat = _raise_model_error
    try:
        second = client.post(
            "/api/v1/realtime/frame",
            data={
                "input_text": "实时观察当前帧",
                "scene_hint": "general",
                "session_id": "rt-fallback-session",
            },
            files={"image_file": ("cat.jpg", _make_jpeg_bytes(), "image/jpeg")},
        )
    finally:
        analyzer_service.minicpm_client.chat = original_chat

    assert second.status_code == 200
    payload = second.json()
    assert payload["summary"] == first_summary
    assert "model_service_unavailable" in payload["urgent_flags"]
