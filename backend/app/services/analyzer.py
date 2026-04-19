from __future__ import annotations

import logging
from collections import Counter, deque
from time import perf_counter

from fastapi import HTTPException, UploadFile, status

from app.domain.schemas import (
    AnalyzeRequestMeta,
    AnalyzeResponse,
    AnalyzeResultEnvelope,
    EmotionAssessment,
    EvidenceBundle,
    FollowupRequest,
    HealthRiskAssessment,
    KnowledgeSnippet,
    RealtimeFusionDebug,
    RiskLevel,
)
from app.domain.session_store import session_store
from app.services.media_pipeline import media_pipeline
from app.services.minicpm_client import minicpm_client
from app.services.prompt_builder import prompt_builder
from app.services.response_parser import response_parser
from app.services.retriever import retriever
from app.services.risk_engine import risk_engine

logger = logging.getLogger(__name__)


class CatStateAnalyzer:
    def __init__(self) -> None:
        self._realtime_window_size = 5
        self._realtime_windows: dict[str, deque[AnalyzeResponse]] = {}
        self._visual_knowledge_cache: dict[str, list[KnowledgeSnippet]] = {}
        self._realtime_visual_counter: Counter[str] = Counter()

    async def analyze(
        self, meta: AnalyzeRequestMeta, upload: UploadFile | None
    ) -> AnalyzeResultEnvelope:
        start = perf_counter()
        if upload is None:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="请上传与 media_type 匹配的图片或视频文件。",
            )
        session = session_store.get_or_create(meta.session_id, meta.scene_hint)
        session_store.append_turn(session.session_id, "user", meta.input_text)

        media = await media_pipeline.prepare_upload(upload, meta.media_type)
        knowledge = retriever.retrieve(
            meta.input_text,
            meta.scene_hint,
            limit=6,
            media=media,
        )
        self._remember_visual_hits(session.session_id, knowledge)
        messages = prompt_builder.build_analysis_messages(
            user_text=meta.input_text,
            scene_hint=meta.scene_hint,
            media=media,
            knowledge=knowledge,
            session=session,
        )
        model_start = perf_counter()
        model_output = await minicpm_client.chat(messages)
        model_ms = (perf_counter() - model_start) * 1000
        parse_start = perf_counter()
        parsed = response_parser.parse(model_output.text, session_id=session.session_id)
        parse_ms = (perf_counter() - parse_start) * 1000
        response = risk_engine.apply(
            parsed,
            user_text=meta.input_text,
            model_tags=[parsed.emotion_assessment.primary, *parsed.health_risk_assessment.triggers],
            knowledge_hits=knowledge,
        )
        response.retrieved_knowledge = [item.model_copy(deep=True) for item in knowledge]
        session_store.save_response(session.session_id, response)
        session_store.append_turn(session.session_id, "assistant", response.summary)
        self._log_latency(
            flow="analyze",
            session_id=session.session_id,
            total_ms=(perf_counter() - start) * 1000,
            model_ms=model_ms,
            parse_ms=parse_ms,
            fallback=False,
        )
        return AnalyzeResultEnvelope(
            response=response,
            retrieved_knowledge=knowledge,
            model_messages=messages,
        )

    async def analyze_realtime_frame(
        self, meta: AnalyzeRequestMeta, upload: UploadFile | None
    ) -> AnalyzeResponse:
        start = perf_counter()
        if upload is None:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="请上传实时观察帧图片。",
            )

        session = session_store.get_or_create(meta.session_id, meta.scene_hint)
        media = await media_pipeline.prepare_upload(upload, meta.media_type)
        self._realtime_visual_counter[session.session_id] += 1
        use_visual_refresh = (
            self._realtime_visual_counter[session.session_id] == 1
            or self._realtime_visual_counter[session.session_id] % 3 == 0
        )
        fresh_hits = retriever.retrieve(
            meta.input_text,
            meta.scene_hint,
            limit=4,
            media=media if use_visual_refresh else None,
        )
        if use_visual_refresh:
            self._remember_visual_hits(session.session_id, fresh_hits)
        knowledge = self._merge_with_visual_cache(session.session_id, fresh_hits, limit=4)
        messages = prompt_builder.build_realtime_messages(
            user_text=meta.input_text,
            scene_hint=meta.scene_hint,
            media=media,
            knowledge=knowledge,
            session=session,
        )
        try:
            model_start = perf_counter()
            model_output = await minicpm_client.chat(messages)
            model_ms = (perf_counter() - model_start) * 1000
        except Exception as exc:
            logger.warning("Realtime model call failed, fallback to local response: %s", exc)
            fallback = self._realtime_model_unavailable_response(
                session_id=session.session_id,
                latest_response=session.latest_response,
            )
            session_store.save_response(session.session_id, fallback)
            self._log_latency(
                flow="realtime",
                session_id=session.session_id,
                total_ms=(perf_counter() - start) * 1000,
                model_ms=None,
                parse_ms=None,
                fallback=True,
            )
            return fallback
        parse_start = perf_counter()
        parsed = response_parser.parse(model_output.text, session_id=session.session_id)
        parse_ms = (perf_counter() - parse_start) * 1000
        response = risk_engine.apply(
            parsed,
            user_text=meta.input_text,
            model_tags=[parsed.emotion_assessment.primary, *parsed.health_risk_assessment.triggers],
            knowledge_hits=knowledge,
        )
        if response.emotion_assessment.primary == "no_cat" or "no_cat_detected" in response.urgent_flags:
            response.retrieved_knowledge = []
        else:
            response.retrieved_knowledge = [item.model_copy(deep=True) for item in knowledge]
        response = self._fuse_realtime_response(session.session_id, response)
        if response.emotion_assessment.primary == "no_cat" or "no_cat_detected" in response.urgent_flags:
            response.retrieved_knowledge = []
        else:
            response.retrieved_knowledge = [item.model_copy(deep=True) for item in knowledge]
        session_store.save_response(session.session_id, response)
        self._log_latency(
            flow="realtime",
            session_id=session.session_id,
            total_ms=(perf_counter() - start) * 1000,
            model_ms=model_ms,
            parse_ms=parse_ms,
            fallback=False,
        )
        return response

    @staticmethod
    def _realtime_model_unavailable_response(
        *,
        session_id: str,
        latest_response: AnalyzeResponse | None,
    ) -> AnalyzeResponse:
        if latest_response is not None:
            fallback = latest_response.model_copy(deep=True)
            fallback.session_id = session_id
            if "model_service_unavailable" not in fallback.urgent_flags:
                fallback.urgent_flags.append("model_service_unavailable")
            if "模型服务繁忙，已暂用上一帧结果。" not in fallback.care_suggestions:
                fallback.care_suggestions.append("本喵这会儿还没来得及更新，你先参考上一帧喵。")
            return fallback

        return AnalyzeResponse(
            session_id=session_id,
            summary="本喵这会儿还没来得及回应，请稳住镜头再试喵。",
            emotion_assessment=EmotionAssessment(
                primary="unknown",
                confidence=0.0,
                signals=["本喵还没回应上"],
            ),
            health_risk_assessment=HealthRiskAssessment(
                level=RiskLevel.LOW,
                score=0.2,
                triggers=["model_service_unavailable"],
                reason="本喵这次还没能及时回应，所以当前这一帧先不给结论喵。",
            ),
            evidence=EvidenceBundle(
                visual=[],
                textual=[],
                knowledge_refs=[],
            ),
            care_suggestions=["请在 2 到 3 秒后再让我看看，或立刻再抓一帧喵。"],
            urgent_flags=["model_service_unavailable"],
            followup_questions=[],
            realtime_debug=RealtimeFusionDebug(
                window_size=1,
                fused=False,
                raw_emotions=["unknown"],
                raw_risk_levels=[RiskLevel.LOW.value],
                note="模型服务不可用，返回保底结果。",
            ),
        )

    async def followup(self, request: FollowupRequest) -> AnalyzeResponse:
        start = perf_counter()
        session = session_store.get(request.session_id)
        if session is None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="会话不存在，请重新发起分析。",
            )

        session_store.append_turn(session.session_id, "user", request.question_text)
        knowledge = retriever.retrieve(
            request.question_text,
            session.scene_hint,
            model_tags=session.latest_response.health_risk_assessment.triggers
            if session.latest_response
            else [],
            limit=5,
        )
        knowledge = self._merge_with_visual_cache(session.session_id, knowledge, limit=5)
        messages = prompt_builder.build_followup_messages(
            session=session,
            question_text=request.question_text,
            knowledge=knowledge,
        )
        model_start = perf_counter()
        model_output = await minicpm_client.chat(messages)
        model_ms = (perf_counter() - model_start) * 1000
        parse_start = perf_counter()
        parsed = response_parser.parse(model_output.text, session_id=session.session_id)
        parse_ms = (perf_counter() - parse_start) * 1000
        response = risk_engine.apply(
            parsed,
            user_text=request.question_text,
            model_tags=[parsed.emotion_assessment.primary, *parsed.health_risk_assessment.triggers],
            knowledge_hits=knowledge,
        )
        response.retrieved_knowledge = [item.model_copy(deep=True) for item in knowledge]
        session_store.save_response(session.session_id, response)
        session_store.append_turn(session.session_id, "assistant", response.summary)
        self._log_latency(
            flow="followup",
            session_id=session.session_id,
            total_ms=(perf_counter() - start) * 1000,
            model_ms=model_ms,
            parse_ms=parse_ms,
            fallback=False,
        )
        return response

    @staticmethod
    def _log_latency(
        *,
        flow: str,
        session_id: str,
        total_ms: float,
        model_ms: float | None,
        parse_ms: float | None,
        fallback: bool,
    ) -> None:
        logger.info(
            "Latency flow=%s session=%s total_ms=%.1f model_ms=%s parse_ms=%s fallback=%s",
            flow,
            session_id,
            total_ms,
            f"{model_ms:.1f}" if model_ms is not None else "n/a",
            f"{parse_ms:.1f}" if parse_ms is not None else "n/a",
            fallback,
        )

    def _fuse_realtime_response(self, session_id: str, response: AnalyzeResponse) -> AnalyzeResponse:
        window = self._realtime_windows.setdefault(
            session_id,
            deque(maxlen=self._realtime_window_size),
        )
        window.append(response.model_copy(deep=True))
        snapshot = list(window)
        if len(snapshot) <= 1:
            response.realtime_debug = RealtimeFusionDebug(
                window_size=1,
                fused=False,
                raw_emotions=[response.emotion_assessment.primary],
                raw_risk_levels=[response.health_risk_assessment.level.value],
                note="首帧结果，尚未触发融合。",
            )
            return response

        fused = response.model_copy(deep=True)
        emotion_candidates = [item.emotion_assessment.primary for item in snapshot]
        risk_candidates = [item.health_risk_assessment.level for item in snapshot]

        fused.emotion_assessment.primary = self._emotion_fusion_from_window(emotion_candidates)
        fused.emotion_assessment.confidence = max(
            0.0,
            min(1.0, sum(item.emotion_assessment.confidence for item in snapshot) / len(snapshot)),
        )
        fused.health_risk_assessment.level = self._max_risk(risk_candidates)
        fused.health_risk_assessment.score = max(
            0.0,
            min(1.0, sum(item.health_risk_assessment.score for item in snapshot) / len(snapshot)),
        )

        latest_box = next((item.cat_target_box for item in reversed(snapshot) if item.cat_target_box is not None), None)
        fused.cat_target_box = latest_box
        fused.urgent_flags = list(dict.fromkeys(flag for item in snapshot for flag in item.urgent_flags))[:6]
        fused.care_suggestions = list(
            dict.fromkeys(suggestion for item in snapshot for suggestion in item.care_suggestions)
        )[:3]
        fused.summary = f"连续{len(snapshot)}帧融合：{response.summary}"
        fused.realtime_debug = RealtimeFusionDebug(
            window_size=len(snapshot),
            fused=True,
            raw_emotions=emotion_candidates,
            raw_risk_levels=[level.value for level in risk_candidates],
            note="已按最近多帧融合：情绪对 no_cat/unknown 去抖，风险取窗口内最高等级。",
        )
        return fused

    @staticmethod
    def _emotion_fusion_from_window(items: list[str]) -> str:
        """多数帧为未检测到猫时，若窗口内存在有效情绪标签则优先采用，减少误判抖动。"""
        if not items:
            return "unknown"
        non_special = [x for x in items if x not in ("no_cat", "unknown")]
        if non_special:
            return Counter(non_special).most_common(1)[0][0]
        return Counter(items).most_common(1)[0][0]

    def _remember_visual_hits(self, session_id: str, knowledge: list[KnowledgeSnippet]) -> None:
        visual_hits = [item.model_copy(deep=True) for item in knowledge if item.source_type in {"visual", "hybrid"}]
        if visual_hits:
            self._visual_knowledge_cache[session_id] = visual_hits[:2]

    def _merge_with_visual_cache(
        self,
        session_id: str,
        knowledge: list[KnowledgeSnippet],
        *,
        limit: int,
    ) -> list[KnowledgeSnippet]:
        cached = [item.model_copy(deep=True) for item in self._visual_knowledge_cache.get(session_id, [])]
        merged: dict[str, KnowledgeSnippet] = {}
        for item in [*knowledge, *cached]:
            existing = merged.get(item.doc_id)
            if existing is None:
                merged[item.doc_id] = item
                continue
            existing.score = max(existing.score, item.score)
            existing.tags = list(dict.fromkeys([*existing.tags, *item.tags]))
            existing.image_refs = list(dict.fromkeys([*existing.image_refs, *item.image_refs]))
            existing.possible_causes = list(dict.fromkeys([*existing.possible_causes, *item.possible_causes]))
            existing.care_advice = list(dict.fromkeys([*existing.care_advice, *item.care_advice]))
            if item.matched_image_ref and not existing.matched_image_ref:
                existing.matched_image_ref = item.matched_image_ref
            if {existing.source_type, item.source_type} == {"text", "visual"}:
                existing.source_type = "hybrid"
        ranked = list(merged.values())
        ranked.sort(key=lambda item: item.score, reverse=True)
        return ranked[:limit]

    @staticmethod
    def _max_risk(items: list[RiskLevel]) -> RiskLevel:
        """安全侧：多帧风险取最高等级，避免多数帧压低单次高危帧。"""
        if not items:
            return RiskLevel.LOW
        order = {
            RiskLevel.LOW: 0,
            RiskLevel.MEDIUM: 1,
            RiskLevel.HIGH: 2,
            RiskLevel.URGENT: 3,
        }
        return max(items, key=lambda level: order[level])


analyzer = CatStateAnalyzer()
