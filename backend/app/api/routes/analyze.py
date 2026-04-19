from __future__ import annotations

from datetime import datetime

from fastapi import APIRouter, File, Form, UploadFile

from app.domain.schemas import (
    AnalyzeRequestMeta,
    AnalyzeResponse,
    MediaType,
    SceneHint,
    SessionGetResponse,
)
from app.domain.session_store import session_store
from app.services.analyzer import analyzer

router = APIRouter(tags=["analyze"])


@router.post("/analyze", response_model=AnalyzeResponse)
async def analyze_cat_state(
    input_text: str = Form(...),
    media_type: MediaType = Form(...),
    client_ts: datetime | None = Form(None),
    scene_hint: SceneHint = Form(SceneHint.GENERAL),
    session_id: str | None = Form(None),
    image_file: UploadFile | None = File(None),
    video_file: UploadFile | None = File(None),
) -> AnalyzeResponse:
    upload = image_file if media_type is MediaType.IMAGE else video_file
    meta = AnalyzeRequestMeta(
        session_id=session_id,
        input_text=input_text,
        media_type=media_type,
        client_ts=client_ts,
        scene_hint=scene_hint,
    )
    envelope = await analyzer.analyze(meta, upload=upload)
    return envelope.response


@router.get("/session/{session_id}", response_model=SessionGetResponse)
async def get_session(session_id: str) -> SessionGetResponse:
    session = session_store.get(session_id)
    if session is None:
        return SessionGetResponse(exists=False)
    return SessionGetResponse(exists=True, session=session)


@router.post("/realtime/frame", response_model=AnalyzeResponse)
async def analyze_realtime_frame(
    input_text: str = Form("实时观察当前帧，请返回猫咪情绪与风险提示。"),
    scene_hint: SceneHint = Form(SceneHint.GENERAL),
    session_id: str | None = Form(None),
    client_ts: datetime | None = Form(None),
    image_file: UploadFile | None = File(None),
) -> AnalyzeResponse:
    meta = AnalyzeRequestMeta(
        session_id=session_id,
        input_text=input_text,
        media_type=MediaType.IMAGE,
        client_ts=client_ts,
        scene_hint=scene_hint,
    )
    return await analyzer.analyze_realtime_frame(meta, upload=image_file)
