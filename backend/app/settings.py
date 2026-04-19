from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


def _optional_int_env(name: str) -> int | None:
    raw = os.getenv(name)
    if raw is None or not str(raw).strip():
        return None
    return int(str(raw).strip())


@dataclass(slots=True)
class Settings:
    app_name: str = "Cat Health Emotion Agent"
    api_prefix: str = "/api/v1"
    minicpm_ws_url: str = os.getenv(
        "MINICPM_WS_URL",
        "wss://deployment-452-qdufnumb-8006.550w.link/ws/chat",
    )
    minicpm_request_timeout_seconds: float = float(
        os.getenv("MINICPM_REQUEST_TIMEOUT_SECONDS", "45")
    )
    minicpm_ws_protocol: str = os.getenv("MINICPM_WS_PROTOCOL", "flat")
    minicpm_ws_subprotocol: str | None = os.getenv("MINICPM_WS_SUBPROTOCOL")
    minicpm_ws_origin: str | None = os.getenv("MINICPM_WS_ORIGIN")
    video_transport_mode: str = os.getenv("VIDEO_TRANSPORT_MODE", "frames")
    knowledge_dir: Path = Path(
        os.getenv("KNOWLEDGE_DIR", Path(__file__).resolve().parents[1] / "knowledge")
    )
    temp_dir: Path = Path(os.getenv("CAT_AGENT_TEMP_DIR", "/tmp/cat-agent"))
    max_image_bytes: int = int(os.getenv("MAX_IMAGE_BYTES", str(6 * 1024 * 1024)))
    max_video_bytes: int = int(os.getenv("MAX_VIDEO_BYTES", str(40 * 1024 * 1024)))
    max_video_seconds: float = float(os.getenv("MAX_VIDEO_SECONDS", "15"))
    max_frame_count: int = int(os.getenv("MAX_FRAME_COUNT", "8"))
    use_mock_model: bool = os.getenv("MINICPM_USE_MOCK", "0") == "1"
    # 结构化输出：略低温度可减少胡编与格式漂移；上游若忽略这些字段也无害。
    minicpm_send_generation_params: bool = os.getenv("MINICPM_SEND_PARAMS", "1") == "1"
    minicpm_temperature: float = float(os.getenv("MINICPM_TEMPERATURE", "0.35"))
    minicpm_max_tokens: int | None = _optional_int_env("MINICPM_MAX_TOKENS")


settings = Settings()
