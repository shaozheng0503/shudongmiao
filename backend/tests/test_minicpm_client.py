from app.domain.schemas import ChatMessage, ContentPart, ContentType
from app.settings import settings
from app.services.minicpm_client import MiniCPMClient


def test_build_payload_merges_generation_params_when_enabled() -> None:
    client = MiniCPMClient()
    messages = [
        ChatMessage(
            role="user",
            content=[ContentPart(type=ContentType.TEXT, text="ping")],
        )
    ]
    payload = client._build_payload(messages, protocol="flat")
    if settings.minicpm_send_generation_params:
        assert payload["temperature"] == settings.minicpm_temperature
        if settings.minicpm_max_tokens is not None:
            assert payload["max_tokens"] == settings.minicpm_max_tokens


def test_extract_text_supports_real_text_delta_event() -> None:
    message = {"type": "chunk", "text_delta": '{"pong":true}'}
    assert MiniCPMClient._extract_text(message) == '{"pong":true}'


def test_terminal_message_supports_real_done_event_type() -> None:
    assert MiniCPMClient._is_terminal_message({"type": "done"}) is True
    assert MiniCPMClient._is_terminal_message({"type": "error"}) is True


def test_normalize_binary_payload_strips_data_url_prefix() -> None:
    payload = "data:image/jpeg;base64,abc123=="
    assert MiniCPMClient._normalize_binary_payload(payload) == "abc123=="


def test_finalize_text_prefers_done_text_over_accumulated_chunks() -> None:
    chunks = ['{"ok":', 'true}', '{"ok":true}']
    payloads = [
        {"type": "chunk", "text_delta": '{"ok":'},
        {"type": "chunk", "text_delta": "true}"},
        {"type": "done", "text": '{"ok":true}'},
    ]
    assert MiniCPMClient._finalize_text(chunks, payloads) == '{"ok":true}'
