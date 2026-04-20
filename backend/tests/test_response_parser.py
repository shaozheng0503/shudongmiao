from app.services.response_parser import response_parser


def test_response_parser_strips_markdown_json_fence() -> None:
    raw = """```json
    {
      "summary": "围栏内",
      "emotion_assessment": {"primary": "relaxed", "confidence": 0.9, "signals": []},
      "health_risk_assessment": {"level": "low", "score": 0.2, "triggers": [], "reason": "x"},
      "evidence": {"visual": ["静卧"], "textual": [], "knowledge_refs": []},
      "care_suggestions": [],
      "urgent_flags": [],
      "followup_questions": [],
      "disclaimer": "本结果仅用于风险提示与照护建议，不构成医疗诊断。"
    }
    ```
    """
    parsed = response_parser.parse(raw, session_id="fence-1")
    assert parsed.summary == "围栏内"


def test_response_parser_extracts_json_block() -> None:
    raw = """
    分析如下：
    {
      "summary": "猫咪有一定警觉和不适信号。",
      "emotion_assessment": {
        "primary": "stress_alert",
        "confidence": 0.7,
        "signals": ["耳朵后压"]
      },
      "health_risk_assessment": {
        "level": "medium",
        "score": 0.58,
        "triggers": ["需要继续观察"],
        "reason": "暂未见明确高危信号。"
      },
      "evidence": {
        "visual": ["身体紧绷"],
        "textual": ["用户说精神差"],
        "knowledge_refs": ["emotion_stress_signals"]
      },
      "care_suggestions": ["减少刺激"],
      "urgent_flags": [],
      "followup_questions": ["最近排尿是否正常？"],
      "disclaimer": "本结果仅用于风险提示与照护建议，不构成医疗诊断。"
    }
    """
    parsed = response_parser.parse(raw, session_id="session-1")

    assert parsed.session_id == "session-1"
    assert parsed.emotion_assessment.primary == "stress_alert"
    assert parsed.health_risk_assessment.level == "medium"


def test_response_parser_extracts_first_json_when_output_has_duplicate_suffix() -> None:
    raw = """
    {
      "summary": "猫咪有一定警觉和不适信号。",
      "emotion_assessment": {
        "primary": "stress_alert",
        "confidence": 0.7,
        "signals": ["耳朵后压"]
      },
      "health_risk_assessment": {
        "level": "medium",
        "score": 0.58,
        "triggers": ["需要继续观察"],
        "reason": "暂未见明确高危信号。"
      },
      "evidence": {
        "visual": ["身体紧绷"],
        "textual": ["用户说精神差"],
        "knowledge_refs": ["emotion_stress_signals"]
      },
      "care_suggestions": ["减少刺激"],
      "urgent_flags": [],
      "followup_questions": ["最近排尿是否正常？"],
      "disclaimer": "本结果仅用于风险提示与照护建议，不构成医疗诊断。"
    }{
      "summary": "重复片段"
    }
    """
    parsed = response_parser.parse(raw, session_id="session-2")

    assert parsed.session_id == "session-2"
    assert parsed.summary == "猫咪有一定警觉和不适信号。"


def test_response_parser_calibrates_confidence_when_no_visual_evidence() -> None:
    raw = """
    {
      "summary": "测试",
      "emotion_assessment": {"primary": "stress_alert", "confidence": 0.95, "signals": ["紧张"]},
      "health_risk_assessment": {"level": "medium", "score": 0.5, "triggers": [], "reason": "x"},
      "evidence": {"visual": [], "textual": ["用户说躲起来"], "knowledge_refs": []},
      "care_suggestions": [],
      "urgent_flags": [],
      "followup_questions": [],
      "disclaimer": "本结果仅用于风险提示与照护建议，不构成医疗诊断。"
    }
    """
    parsed = response_parser.parse(raw, session_id="session-cal")
    assert parsed.emotion_assessment.confidence <= 0.52


def test_response_parser_normalizes_risk_level_casing() -> None:
    raw = """
    {
      "summary": "测试",
      "emotion_assessment": {"primary": "relaxed", "confidence": 0.9, "signals": []},
      "health_risk_assessment": {"level": "MEDIUM", "score": 0.5, "triggers": [], "reason": "x"},
      "evidence": {"visual": [], "textual": [], "knowledge_refs": []},
      "care_suggestions": [],
      "urgent_flags": [],
      "followup_questions": [],
      "disclaimer": "本结果仅用于风险提示与照护建议，不构成医疗诊断。"
    }
    """
    parsed = response_parser.parse(raw, session_id="session-norm")
    assert parsed.health_risk_assessment.level == "medium"


def test_response_parser_salvages_partial_truncated_json() -> None:
    raw = """
    {
      "summary": "猫咪表现出紧张状态",
      "emotion_assessment": {
        "primary": "stress_alert",
        "confidence": 0.85,
        "signals": ["眼神警惕", "姿态紧绷"]
      },
      "health_risk_assessment": {
        "level": "medium",
        "score": 0.6,
        "triggers": ["紧张行为"],
        "reason": "存在应激信号"
      },
      "evidence": {
        "visual": ["瞳孔放大"],
        "textual": ["用户说紧张"],
        "knowledge_refs": ["emotion_stress_signals"]
      },
      "cat_target_box": {"x": 0.1, "y": 0.2, "width": 0.5, "height": 0.6, "confidence": 0.9},
      "care_suggestions": [
    """
    parsed = response_parser.parse(raw, session_id="session-3")

    assert parsed.session_id == "session-3"
    assert parsed.summary == "猫咪表现出紧张状态"
    assert parsed.emotion_assessment.primary == "stress_alert"
    assert parsed.health_risk_assessment.level == "medium"
    assert parsed.cat_target_box is not None
    assert parsed.cat_target_box.width == 0.5


def test_response_parser_forces_no_cat_when_non_cat_target_detected() -> None:
    raw = """
    {
      "summary": "画面里是一只小狗，精神正常",
      "emotion_assessment": {"primary": "relaxed", "confidence": 0.88, "signals": ["狗耳朵竖起"]},
      "health_risk_assessment": {"level": "medium", "score": 0.56, "triggers": ["犬类表现"], "reason": "疑似犬类"},
      "evidence": {"visual": ["小狗在画面中央"], "textual": [], "knowledge_refs": []},
      "cat_target_box": {"x": 0.2, "y": 0.2, "width": 0.5, "height": 0.5, "confidence": 0.9},
      "care_suggestions": ["继续观察"],
      "urgent_flags": [],
      "followup_questions": [],
      "disclaimer": "本结果仅用于风险提示与照护建议，不构成医疗诊断。"
    }
    """
    parsed = response_parser.parse(raw, session_id="session-dog")

    assert parsed.emotion_assessment.primary == "no_cat"
    assert "no_cat_detected" in parsed.urgent_flags
    assert "non_cat_target_detected" in parsed.urgent_flags
    assert parsed.cat_target_box is None


def test_response_parser_enforces_no_cat_field_consistency() -> None:
    raw = """
    {
      "summary": "未检测到猫",
      "emotion_assessment": {"primary": "no_cat", "confidence": 0.95, "signals": ["画面中无猫"]},
      "health_risk_assessment": {"level": "high", "score": 0.92, "triggers": ["异常"], "reason": "旧状态残留"},
      "evidence": {"visual": [], "textual": [], "knowledge_refs": []},
      "cat_target_box": {"x": 0.1, "y": 0.2, "width": 0.4, "height": 0.4, "confidence": 0.8},
      "care_suggestions": [],
      "urgent_flags": [],
      "followup_questions": ["它吃饭了吗？"],
      "disclaimer": "本结果仅用于风险提示与照护建议，不构成医疗诊断。"
    }
    """
    parsed = response_parser.parse(raw, session_id="session-no-cat-consistency")

    assert parsed.health_risk_assessment.level == "low"
    assert "no_cat_detected" in parsed.urgent_flags
    assert parsed.cat_target_box is None
    assert parsed.followup_questions == []


def test_response_parser_downgrades_high_risk_without_visual_evidence() -> None:
    raw = """
    {
      "summary": "状态风险偏高",
      "emotion_assessment": {"primary": "stress_alert", "confidence": 0.86, "signals": ["紧张"]},
      "health_risk_assessment": {"level": "urgent", "score": 0.9, "triggers": ["呼吸异常"], "reason": "模型判断高危"},
      "evidence": {"visual": [], "textual": ["用户称呼吸急促"], "knowledge_refs": []},
      "care_suggestions": ["尽快就医"],
      "urgent_flags": ["respiratory_distress"],
      "followup_questions": [],
      "disclaimer": "本结果仅用于风险提示与照护建议，不构成医疗诊断。"
    }
    """
    parsed = response_parser.parse(raw, session_id="session-risk-gate")

    assert parsed.health_risk_assessment.level == "medium"
    assert "visual_evidence_weak" in parsed.health_risk_assessment.triggers
    assert "visual_evidence_weak" in parsed.urgent_flags


def test_response_parser_downgrades_high_risk_when_dual_evidence_missing() -> None:
    raw = """
    {
      "summary": "检测到疑似疼痛姿态",
      "emotion_assessment": {"primary": "pain_sign", "confidence": 0.88, "signals": ["弓背"]},
      "health_risk_assessment": {"level": "high", "score": 0.82, "triggers": ["疼痛体态"], "reason": "模型判断高风险"},
      "evidence": {"visual": ["弓背姿态"], "textual": [], "knowledge_refs": []},
      "care_suggestions": ["尽快处理"],
      "urgent_flags": ["gait_issue"],
      "followup_questions": [],
      "disclaimer": "本结果仅用于风险提示与照护建议，不构成医疗诊断。"
    }
    """
    parsed = response_parser.parse(raw, session_id="session-dual-gate")
    assert parsed.health_risk_assessment.level == "medium"
    assert "risk_dual_evidence_missing" in parsed.health_risk_assessment.triggers


def test_response_parser_marks_low_quality_frame_as_unknown() -> None:
    raw = """
    {
      "summary": "画面模糊且反光，难判断",
      "emotion_assessment": {"primary": "pain_sign", "confidence": 0.8, "signals": ["疑似弓背"]},
      "health_risk_assessment": {"level": "high", "score": 0.85, "triggers": ["异常姿态"], "reason": "疑似疼痛"},
      "evidence": {"visual": ["画面过暗且模糊"], "textual": ["用户称状态一般"], "knowledge_refs": []},
      "cat_target_box": {"x": 0.1, "y": 0.1, "width": 0.3, "height": 0.4, "confidence": 0.3},
      "care_suggestions": [],
      "urgent_flags": [],
      "followup_questions": [],
      "disclaimer": "本结果仅用于风险提示与照护建议，不构成医疗诊断。"
    }
    """
    parsed = response_parser.parse(raw, session_id="session-low-quality")
    assert parsed.emotion_assessment.primary == "unknown"
    assert "low_quality_frame" in parsed.urgent_flags
    assert parsed.health_risk_assessment.level == "medium"


def test_response_parser_normalizes_chinese_positive_emotions() -> None:
    raw = """
    {
      "summary": "本喵今天很开心",
      "emotion_assessment": {"primary": "开心", "confidence": 0.82, "signals": ["主动靠近", "呼噜"]},
      "health_risk_assessment": {"level": "low", "score": 0.2, "triggers": [], "reason": "状态稳定"},
      "evidence": {"visual": ["主动靠近主人"], "textual": [], "knowledge_refs": []},
      "care_suggestions": [],
      "urgent_flags": [],
      "followup_questions": [],
      "disclaimer": "本结果仅用于风险提示与照护建议，不构成医疗诊断。"
    }
    """
    parsed = response_parser.parse(raw, session_id="session-happy")
    assert parsed.emotion_assessment.primary == "happy"


def test_response_parser_normalizes_curious_alias() -> None:
    raw = """
    {
      "summary": "本喵在探索周围",
      "emotion_assessment": {"primary": "探索", "confidence": 0.66, "signals": ["四处嗅闻"]},
      "health_risk_assessment": {"level": "low", "score": 0.2, "triggers": [], "reason": "状态稳定"},
      "evidence": {"visual": ["四处嗅闻"], "textual": [], "knowledge_refs": []},
      "care_suggestions": [],
      "urgent_flags": [],
      "followup_questions": [],
      "disclaimer": "本结果仅用于风险提示与照护建议，不构成医疗诊断。"
    }
    """
    parsed = response_parser.parse(raw, session_id="session-curious")
    assert parsed.emotion_assessment.primary == "curious"


def test_response_parser_normalizes_primary_in_partial_extract() -> None:
    raw = """
    {"summary":"本喵很放松","emotion_assessment":{"primary":"放松","confidence":0.7,"signals":["安静趴卧"]},"health_risk_assessment":{"level":"low","score":0.2,"triggers":[],"reason":"状态稳定"},"evidence":{"visual":["安静趴卧"],"textual":[],"knowledge_refs":[]},"care_suggestions":[],"urgent_flags":[],"followup_questions":[],"disclaimer":"本结果仅用于风险提示与照护建议，不构成医疗诊断。"} trailing-noise
    """
    parsed = response_parser.parse(raw, session_id="session-partial-relaxed")
    assert parsed.emotion_assessment.primary == "relaxed"


def test_response_parser_forces_no_cat_when_cat_evidence_missing() -> None:
    raw = """
    {
      "summary": "画面里有个玩具在桌面上",
      "emotion_assessment": {"primary": "stress_alert", "confidence": 0.64, "signals": ["画面主体静止"]},
      "health_risk_assessment": {"level": "medium", "score": 0.45, "triggers": [], "reason": "疑似不适"},
      "evidence": {"visual": ["主体轮廓不清"], "textual": [], "knowledge_refs": []},
      "care_suggestions": [],
      "urgent_flags": [],
      "followup_questions": [],
      "disclaimer": "本结果仅用于风险提示与照护建议，不构成医疗诊断。"
    }
    """
    parsed = response_parser.parse(raw, session_id="session-missing-cat-evidence")
    assert parsed.emotion_assessment.primary == "no_cat"
    assert "no_cat_detected" in parsed.urgent_flags


def test_response_parser_normalizes_excited_alias() -> None:
    raw = """
    {
      "summary": "本喵好兴奋",
      "emotion_assessment": {"primary": "兴奋", "confidence": 0.8, "signals": ["快速走动"]},
      "health_risk_assessment": {"level": "low", "score": 0.2, "triggers": [], "reason": "状态稳定"},
      "evidence": {"visual": ["快速走动"], "textual": [], "knowledge_refs": []},
      "care_suggestions": [],
      "urgent_flags": [],
      "followup_questions": [],
      "disclaimer": "本结果仅用于风险提示与照护建议，不构成医疗诊断。"
    }
    """
    parsed = response_parser.parse(raw, session_id="session-excited")
    assert parsed.emotion_assessment.primary == "excited"


def test_response_parser_normalizes_confused_alias() -> None:
    raw = """
    {
      "summary": "本喵有点疑惑",
      "emotion_assessment": {"primary": "疑惑", "confidence": 0.71, "signals": ["反复张望"]},
      "health_risk_assessment": {"level": "low", "score": 0.2, "triggers": [], "reason": "状态稳定"},
      "evidence": {"visual": ["反复张望"], "textual": [], "knowledge_refs": []},
      "care_suggestions": [],
      "urgent_flags": [],
      "followup_questions": [],
      "disclaimer": "本结果仅用于风险提示与照护建议，不构成医疗诊断。"
    }
    """
    parsed = response_parser.parse(raw, session_id="session-confused")
    assert parsed.emotion_assessment.primary == "confused"


def test_response_parser_refines_emotion_by_visual_cues() -> None:
    raw = """
    {
      "summary": "本喵在窗边反复张望和嗅闻",
      "emotion_assessment": {"primary": "relaxed", "confidence": 0.55, "signals": ["反复张望", "嗅闻"]},
      "health_risk_assessment": {"level": "low", "score": 0.2, "triggers": [], "reason": "状态稳定"},
      "evidence": {"visual": ["反复张望", "嗅闻"], "textual": [], "knowledge_refs": []},
      "care_suggestions": [],
      "urgent_flags": [],
      "followup_questions": [],
      "disclaimer": "本结果仅用于风险提示与照护建议，不构成医疗诊断。"
    }
    """
    parsed = response_parser.parse(raw, session_id="session-emotion-refine")
    assert parsed.emotion_assessment.primary in {"curious", "confused"}


def test_response_parser_should_not_map_negative_happy_to_happy() -> None:
    raw = """
    {
      "summary": "本喵看起来不开心",
      "emotion_assessment": {"primary": "不开心", "confidence": 0.72, "signals": ["低头", "无互动"]},
      "health_risk_assessment": {"level": "low", "score": 0.3, "triggers": [], "reason": "状态偏低"},
      "evidence": {"visual": ["低头", "无互动"], "textual": [], "knowledge_refs": []},
      "care_suggestions": [],
      "urgent_flags": [],
      "followup_questions": [],
      "disclaimer": "本结果仅用于风险提示与照护建议，不构成医疗诊断。"
    }
    """
    parsed = response_parser.parse(raw, session_id="session-not-happy")
    assert parsed.emotion_assessment.primary != "happy"
