from app.domain.schemas import (
    AnalyzeResponse,
    EmotionAssessment,
    EvidenceBundle,
    HealthRiskAssessment,
    KnowledgeSnippet,
    RiskLevel,
)
from app.services.risk_engine import risk_engine


def test_risk_engine_upgrades_urination_issue() -> None:
    response = AnalyzeResponse(
        summary="猫咪频繁蹲猫砂盆，状态有些差。",
        emotion_assessment=EmotionAssessment(primary="stress_alert", confidence=0.6, signals=["紧张"]),
        health_risk_assessment=HealthRiskAssessment(
            level=RiskLevel.MEDIUM,
            score=0.45,
            triggers=["频繁蹲猫砂盆"],
            reason="需要进一步确认。",
        ),
        evidence=EvidenceBundle(visual=["多次蹲姿"], textual=["几乎没有尿"], knowledge_refs=[]),
        care_suggestions=["继续观察。"],
        urgent_flags=[],
        followup_questions=[],
    )

    upgraded = risk_engine.apply(
        response,
        user_text="今天频繁蹲猫砂盆，几乎没有尿。",
        model_tags=["频繁蹲猫砂盆"],
    )

    assert upgraded.health_risk_assessment.level == RiskLevel.URGENT
    assert "urination_issue_possible" in upgraded.urgent_flags


def test_risk_engine_uses_retrieved_knowledge_terms() -> None:
    response = AnalyzeResponse(
        summary="状态需要继续观察。",
        emotion_assessment=EmotionAssessment(primary="unknown", confidence=0.4, signals=[]),
        health_risk_assessment=HealthRiskAssessment(
            level=RiskLevel.LOW,
            score=0.2,
            triggers=[],
            reason="暂未识别高危。",
        ),
        evidence=EvidenceBundle(visual=[], textual=[], knowledge_refs=[]),
        care_suggestions=["继续观察。"],
        urgent_flags=[],
        followup_questions=[],
    )

    upgraded = risk_engine.apply(
        response,
        user_text="它看起来怪怪的。",
        model_tags=[],
        knowledge_hits=[
            KnowledgeSnippet(
                doc_id="docx_breathing",
                title="呼吸急促",
                category="breathing",
                content="观察表现：腹部剧烈起伏、张口呼吸。",
                possible_causes=["中暑", "缺氧"],
                care_advice=["尽快就医"],
            )
        ],
    )

    assert upgraded.health_risk_assessment.level == RiskLevel.HIGH
    assert "respiratory_distress" in upgraded.urgent_flags


def test_risk_engine_no_cat_should_not_escalate() -> None:
    response = AnalyzeResponse(
        summary="未检测到猫",
        emotion_assessment=EmotionAssessment(primary="no_cat", confidence=0.1, signals=[]),
        health_risk_assessment=HealthRiskAssessment(
            level=RiskLevel.HIGH,
            score=0.8,
            triggers=["呼吸急促"],
            reason="旧状态残留",
        ),
        evidence=EvidenceBundle(visual=["未检测到猫"], textual=[], knowledge_refs=[]),
        care_suggestions=["旧建议"],
        urgent_flags=["no_cat_detected", "respiratory_distress"],
        followup_questions=[],
    )

    upgraded = risk_engine.apply(
        response,
        user_text="黑屏",
        model_tags=["呼吸急促"],
        knowledge_hits=[
            KnowledgeSnippet(
                doc_id="docx_resp",
                title="呼吸急促",
                category="breathing",
                content="张口呼吸属于急症",
            )
        ],
    )

    assert upgraded.health_risk_assessment.level == RiskLevel.LOW
    assert upgraded.health_risk_assessment.reason == "本喵还没出现在画面里，现在没法判断状态喵。"
    assert upgraded.urgent_flags == ["no_cat_detected"]
    assert upgraded.retrieved_knowledge == []


def test_risk_engine_non_cat_target_should_keep_species_reason() -> None:
    response = AnalyzeResponse(
        summary="这画面里不像本喵，请把镜头对准我喵。",
        emotion_assessment=EmotionAssessment(primary="no_cat", confidence=0.9, signals=["不像本喵"]),
        health_risk_assessment=HealthRiskAssessment(
            level=RiskLevel.MEDIUM,
            score=0.6,
            triggers=["non_cat_target_detected"],
            reason="疑似狗",
        ),
        evidence=EvidenceBundle(visual=["画面主体不像本喵"], textual=[], knowledge_refs=[]),
        care_suggestions=["旧建议"],
        urgent_flags=["non_cat_target_detected", "no_cat_detected"],
        followup_questions=[],
    )

    upgraded = risk_engine.apply(
        response,
        user_text="看起来像只狗",
        model_tags=[],
        knowledge_hits=[],
    )

    assert upgraded.health_risk_assessment.level == RiskLevel.LOW
    assert upgraded.health_risk_assessment.reason == "这次画面里更像别的小动物，不是本喵，所以没法判断我的状态喵。"
    assert upgraded.urgent_flags == ["non_cat_target_detected", "no_cat_detected"]
