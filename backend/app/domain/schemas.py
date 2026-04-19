from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Any
from uuid import uuid4

from pydantic import BaseModel, Field


class MediaType(str, Enum):
    IMAGE = "image"
    VIDEO = "video"


class SceneHint(str, Enum):
    STRESS = "stress"
    APPETITE = "appetite"
    LITTERBOX = "litterbox"
    GENERAL = "general"


class RiskLevel(str, Enum):
    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"
    URGENT = "urgent"


class ContentType(str, Enum):
    TEXT = "text"
    IMAGE = "image"
    AUDIO = "audio"
    VIDEO = "video"


class ContentPart(BaseModel):
    type: ContentType
    text: str | None = None
    data: str | None = None
    mime_type: str | None = None
    metadata: dict[str, Any] = Field(default_factory=dict)


class ChatMessage(BaseModel):
    role: str
    content: list[ContentPart]


class KnowledgeSnippet(BaseModel):
    doc_id: str
    title: str
    category: str
    content: str
    score: float = 0.0
    tags: list[str] = Field(default_factory=list)
    source_type: str = "text"
    section: str | None = None
    possible_causes: list[str] = Field(default_factory=list)
    care_advice: list[str] = Field(default_factory=list)
    image_refs: list[str] = Field(default_factory=list)
    matched_image_ref: str | None = None


class MediaFrame(BaseModel):
    timestamp_ms: int
    data_url: str
    width: int | None = None
    height: int | None = None
    score: float | None = None


class PreparedMedia(BaseModel):
    media_type: MediaType
    mime_type: str
    data_url: str | None = None
    frames: list[MediaFrame] = Field(default_factory=list)
    duration_seconds: float | None = None
    width: int | None = None
    height: int | None = None
    metadata: dict[str, Any] = Field(default_factory=dict)


class EmotionAssessment(BaseModel):
    primary: str = "unknown"
    confidence: float = 0.0
    signals: list[str] = Field(default_factory=list)


class HealthRiskAssessment(BaseModel):
    level: RiskLevel = RiskLevel.LOW
    score: float = 0.0
    triggers: list[str] = Field(default_factory=list)
    reason: str = ""


class EvidenceBundle(BaseModel):
    visual: list[str] = Field(default_factory=list)
    textual: list[str] = Field(default_factory=list)
    knowledge_refs: list[str] = Field(default_factory=list)


class CatTargetBox(BaseModel):
    x: float = Field(ge=0.0, le=1.0)
    y: float = Field(ge=0.0, le=1.0)
    width: float = Field(ge=0.01, le=1.0)
    height: float = Field(ge=0.01, le=1.0)
    confidence: float = Field(default=0.0, ge=0.0, le=1.0)


class RealtimeFusionDebug(BaseModel):
    window_size: int = 1
    fused: bool = False
    raw_emotions: list[str] = Field(default_factory=list)
    raw_risk_levels: list[str] = Field(default_factory=list)
    note: str = ""


class AnalyzeResponse(BaseModel):
    session_id: str = Field(default_factory=lambda: str(uuid4()))
    summary: str
    emotion_assessment: EmotionAssessment
    health_risk_assessment: HealthRiskAssessment
    evidence: EvidenceBundle
    cat_target_box: CatTargetBox | None = None
    care_suggestions: list[str] = Field(default_factory=list)
    urgent_flags: list[str] = Field(default_factory=list)
    followup_questions: list[str] = Field(default_factory=list)
    realtime_debug: RealtimeFusionDebug | None = None
    retrieved_knowledge: list[KnowledgeSnippet] = Field(default_factory=list)
    disclaimer: str = "本结果仅用于风险提示与照护建议，不构成医疗诊断。"
    raw_model_output: str | None = None


class AnalyzeResultEnvelope(BaseModel):
    response: AnalyzeResponse
    retrieved_knowledge: list[KnowledgeSnippet] = Field(default_factory=list)
    model_messages: list[ChatMessage] = Field(default_factory=list)


class AnalyzeRequestMeta(BaseModel):
    session_id: str | None = None
    input_text: str = Field(min_length=1)
    media_type: MediaType
    client_ts: datetime | None = None
    scene_hint: SceneHint = SceneHint.GENERAL


class FollowupRequest(BaseModel):
    session_id: str
    question_text: str = Field(min_length=1)


class SessionTurn(BaseModel):
    role: str
    text: str
    created_at: datetime = Field(default_factory=datetime.utcnow)


class SessionState(BaseModel):
    session_id: str
    created_at: datetime = Field(default_factory=datetime.utcnow)
    updated_at: datetime = Field(default_factory=datetime.utcnow)
    scene_hint: SceneHint = SceneHint.GENERAL
    latest_response: AnalyzeResponse | None = None
    history: list[SessionTurn] = Field(default_factory=list)


class ModelOutput(BaseModel):
    text: str
    usage: dict[str, Any] = Field(default_factory=dict)
    raw_payload: dict[str, Any] = Field(default_factory=dict)
    protocol: str = "flat"


class HealthCapabilities(BaseModel):
    ffmpeg: bool
    ffprobe: bool
    opencv: bool
    numpy: bool
    native_video_ws_probe_verified: bool = True
    native_image_ws_probe_verified: bool = True


class HealthResponse(BaseModel):
    """GET /health 统一响应，便于客户端探测能力与配置。"""

    status: str = "ok"
    model_ws_url: str
    model_ws_protocol: str
    use_mock_model: bool
    media_payload_format: str = "base64_without_data_url_prefix"
    video_transport_mode: str
    capabilities: HealthCapabilities
    minicpm_temperature: float | None = None
    minicpm_send_generation_params: bool = True
    minicpm_max_tokens: int | None = None


class SessionGetResponse(BaseModel):
    """GET /session/{id}：会话是否存在及完整状态。"""

    exists: bool
    session: SessionState | None = None
