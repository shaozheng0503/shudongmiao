from app.domain.schemas import (
    AnalyzeResponse,
    EmotionAssessment,
    EvidenceBundle,
    HealthRiskAssessment,
    KnowledgeSnippet,
    RiskLevel,
)
from app.services.analyzer import CatStateAnalyzer


def test_analyzer_merges_cached_visual_hits_for_followup() -> None:
    analyzer = CatStateAnalyzer()
    analyzer._remember_visual_hits(
        "session-1",
        [
            KnowledgeSnippet(
                doc_id="visual_case",
                title="应激炸毛",
                category="stress",
                content="观察表现：炸毛。",
                score=1.2,
                source_type="visual",
                matched_image_ref="assets/stress.jpg",
            )
        ],
    )

    merged = analyzer._merge_with_visual_cache(
        "session-1",
        [
            KnowledgeSnippet(
                doc_id="text_case",
                title="排尿困难",
                category="litterbox",
                content="观察表现：频繁蹲盆。",
                score=2.0,
                source_type="text",
            )
        ],
        limit=5,
    )

    assert [item.doc_id for item in merged] == ["text_case", "visual_case"]
    assert merged[1].matched_image_ref == "assets/stress.jpg"


def test_analyzer_marks_hybrid_when_cache_and_text_hit_same_doc() -> None:
    analyzer = CatStateAnalyzer()
    analyzer._remember_visual_hits(
        "session-2",
        [
            KnowledgeSnippet(
                doc_id="shared_case",
                title="呼吸急促",
                category="breathing",
                content="观察表现：张口呼吸。",
                score=1.4,
                source_type="visual",
                matched_image_ref="assets/breath.jpg",
            )
        ],
    )

    merged = analyzer._merge_with_visual_cache(
        "session-2",
        [
            KnowledgeSnippet(
                doc_id="shared_case",
                title="呼吸急促",
                category="breathing",
                content="观察表现：张口呼吸。",
                score=2.6,
                source_type="text",
            )
        ],
        limit=5,
    )

    assert len(merged) == 1
    assert merged[0].source_type == "hybrid"
    assert merged[0].matched_image_ref == "assets/breath.jpg"


def _fake_resp(
    level: RiskLevel,
    *,
    visual: bool,
    box_conf: float = 0.0,
    emo_conf: float = 0.6,
    primary: str = "relaxed",
) -> AnalyzeResponse:
    return AnalyzeResponse(
        summary="test",
        emotion_assessment=EmotionAssessment(primary=primary, confidence=emo_conf, signals=[]),
        health_risk_assessment=HealthRiskAssessment(
            level=level,
            score=0.7 if level in {RiskLevel.HIGH, RiskLevel.URGENT} else 0.3,
            triggers=[],
            reason="test",
        ),
        evidence=EvidenceBundle(visual=["有视觉证据"] if visual else [], textual=[], knowledge_refs=[]),
        cat_target_box={
            "x": 0.1,
            "y": 0.1,
            "width": 0.5,
            "height": 0.5,
            "confidence": box_conf,
        }
        if box_conf > 0
        else None,
        care_suggestions=[],
        urgent_flags=[],
        followup_questions=[],
    )


def _fake_no_cat_resp() -> AnalyzeResponse:
    return AnalyzeResponse(
        summary="未检测到猫",
        emotion_assessment=EmotionAssessment(primary="no_cat", confidence=0.95, signals=["无猫"]),
        health_risk_assessment=HealthRiskAssessment(
            level=RiskLevel.LOW,
            score=0.1,
            triggers=["no_cat_detected"],
            reason="画面中无猫",
        ),
        evidence=EvidenceBundle(visual=["无猫"], textual=[], knowledge_refs=[]),
        cat_target_box=None,
        care_suggestions=[],
        urgent_flags=["no_cat_detected"],
        followup_questions=[],
    )


def test_risk_fusion_should_not_keep_single_frame_high_without_strong_evidence() -> None:
    analyzer = CatStateAnalyzer()
    snapshot = [
        _fake_resp(RiskLevel.LOW, visual=True),
        _fake_resp(RiskLevel.HIGH, visual=False, box_conf=0.0, emo_conf=0.5),
        _fake_resp(RiskLevel.LOW, visual=True),
    ]

    fused = analyzer._risk_fusion_from_window(snapshot, snapshot[1])
    assert fused == RiskLevel.LOW


def test_risk_fusion_should_keep_high_when_two_frames_are_high() -> None:
    analyzer = CatStateAnalyzer()
    snapshot = [
        _fake_resp(RiskLevel.HIGH, visual=True, box_conf=0.6),
        _fake_resp(RiskLevel.HIGH, visual=True, box_conf=0.7),
        _fake_resp(RiskLevel.LOW, visual=True),
    ]

    fused = analyzer._risk_fusion_from_window(snapshot, snapshot[1])
    assert fused == RiskLevel.HIGH


def test_realtime_fusion_should_reset_window_on_no_cat() -> None:
    analyzer = CatStateAnalyzer()
    session_id = "session-reset-no-cat"
    first = _fake_resp(RiskLevel.MEDIUM, visual=True, box_conf=0.72, emo_conf=0.8)
    fused_first = analyzer._fuse_realtime_response(session_id, first)
    assert fused_first.cat_target_box is not None

    no_cat = _fake_no_cat_resp()
    fused_no_cat = analyzer._fuse_realtime_response(session_id, no_cat)
    assert fused_no_cat.emotion_assessment.primary == "no_cat"
    assert fused_no_cat.cat_target_box is None
    assert fused_no_cat.realtime_debug is not None
    assert fused_no_cat.realtime_debug.window_size == 1


def test_analyze_duplicate_guard_should_degrade_when_media_changed_but_result_same() -> None:
    analyzer = CatStateAnalyzer()
    session_id = "session-dup-guard"
    previous = AnalyzeResponse(
        summary="本喵在休息",
        emotion_assessment=EmotionAssessment(primary="relaxed", confidence=0.82, signals=["趴卧"]),
        health_risk_assessment=HealthRiskAssessment(
            level=RiskLevel.LOW,
            score=0.25,
            triggers=["状态平稳"],
            reason="状态稳定",
        ),
        evidence=EvidenceBundle(visual=["趴卧"], textual=[], knowledge_refs=[]),
        cat_target_box={
            "x": 0.2,
            "y": 0.2,
            "width": 0.4,
            "height": 0.5,
            "confidence": 0.77,
        },
        care_suggestions=["继续观察"],
        urgent_flags=[],
        followup_questions=[],
    )
    current = previous.model_copy(deep=True)
    analyzer._last_analyze_media_fp[session_id] = "fp-old"
    guarded = analyzer._guard_duplicate_analyze_result(
        session_id=session_id,
        response=current,
        previous=previous,
        media_fp="fp-new",
    )
    assert guarded.emotion_assessment.primary == "unknown"
    assert guarded.health_risk_assessment.level == RiskLevel.LOW
    assert "stale_repeat_guard" in guarded.urgent_flags
    assert guarded.cat_target_box is None


def test_emotion_fusion_should_keep_latest_nuanced_emotion() -> None:
    analyzer = CatStateAnalyzer()
    snapshot = [
        _fake_resp(RiskLevel.LOW, visual=True, box_conf=0.72, emo_conf=0.78, primary="happy"),
        _fake_resp(RiskLevel.LOW, visual=True, box_conf=0.74, emo_conf=0.75, primary="happy"),
        _fake_resp(RiskLevel.LOW, visual=True, box_conf=0.63, emo_conf=0.62, primary="curious"),
    ]
    fused = analyzer._emotion_fusion_from_window(snapshot, snapshot[-1])
    assert fused == "curious"


def test_attach_knowledge_refs_should_fill_when_missing() -> None:
    analyzer = CatStateAnalyzer()
    response = _fake_resp(RiskLevel.LOW, visual=True, box_conf=0.71, emo_conf=0.8, primary="happy")
    response.evidence.knowledge_refs = []
    knowledge = [
        KnowledgeSnippet(
            doc_id="doc_a",
            title="A",
            category="general",
            content="x",
            score=0.9,
        ),
        KnowledgeSnippet(
            doc_id="doc_b",
            title="B",
            category="general",
            content="y",
            score=0.7,
        ),
    ]
    analyzer._attach_knowledge_refs_if_missing(response, knowledge)
    assert response.evidence.knowledge_refs == ["doc_a", "doc_b"]


def test_attach_knowledge_refs_should_replace_blank_refs() -> None:
    analyzer = CatStateAnalyzer()
    response = _fake_resp(RiskLevel.LOW, visual=True, box_conf=0.71, emo_conf=0.8, primary="happy")
    response.evidence.knowledge_refs = ["", "   "]
    knowledge = [
        KnowledgeSnippet(
            doc_id="doc_x",
            title="X",
            category="general",
            content="x",
            score=0.9,
        )
    ]
    analyzer._attach_knowledge_refs_if_missing(response, knowledge)
    assert response.evidence.knowledge_refs == ["doc_x"]
