from __future__ import annotations

import shutil

from fastapi import APIRouter

from app.domain.schemas import FollowupRequest, AnalyzeResponse, HealthCapabilities, HealthResponse
from app.settings import settings
from app.services.analyzer import analyzer
from app.services.media_pipeline import cv2, np

router = APIRouter(tags=["chat"])


@router.post("/chat/followup", response_model=AnalyzeResponse)
async def followup_chat(request: FollowupRequest) -> AnalyzeResponse:
    return await analyzer.followup(request)


@router.get("/health", response_model=HealthResponse)
async def health_check() -> HealthResponse:
    return HealthResponse(
        model_ws_url=settings.minicpm_ws_url,
        model_ws_protocol=settings.minicpm_ws_protocol,
        use_mock_model=settings.use_mock_model,
        video_transport_mode=settings.video_transport_mode,
        capabilities=HealthCapabilities(
            ffmpeg=shutil.which("ffmpeg") is not None,
            ffprobe=shutil.which("ffprobe") is not None,
            opencv=cv2 is not None,
            numpy=np is not None,
        ),
        minicpm_temperature=settings.minicpm_temperature,
        minicpm_send_generation_params=settings.minicpm_send_generation_params,
        minicpm_max_tokens=settings.minicpm_max_tokens,
    )
