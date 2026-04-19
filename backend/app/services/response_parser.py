from __future__ import annotations

import json
import re
from pydantic import ValidationError

from app.domain.schemas import (
    AnalyzeResponse,
    CatTargetBox,
    EmotionAssessment,
    EvidenceBundle,
    HealthRiskAssessment,
    RiskLevel,
)

_UNCERTAIN_SUMMARY_HINTS = ("模糊", "不清", "过暗", "过小", "遮挡", "看不清", "无法确认", "难判断")
_NON_CAT_TOKENS = (
    "dog",
    "puppy",
    "canine",
    "犬",
    "狗",
    "小狗",
)
_CAT_TOKENS = ("cat", "猫", "猫咪")
_EMOTION_PRIMARY_ALIASES: dict[str, str] = {
    "开心": "happy",
    "高兴": "happy",
    "愉快": "happy",
    "happy": "happy",
    "放松": "relaxed",
    "松弛": "relaxed",
    "安逸": "relaxed",
    "轻松": "relaxed",
    "relaxed": "relaxed",
    "好奇": "curious",
    "探索": "curious",
    "探究": "curious",
    "curious": "curious",
    "紧张": "stress_alert",
    "应激": "stress_alert",
    "stress": "stress_alert",
    "stress_alert": "stress_alert",
    "害怕": "fearful",
    "fearful": "fearful",
    "玩耍": "playful",
    "playful": "playful",
    "低落": "low_energy",
    "没精神": "low_energy",
    "low_energy": "low_energy",
    "疼痛": "pain_sign",
    "pain_sign": "pain_sign",
    "排尿不适": "litterbox_discomfort",
    "litterbox_discomfort": "litterbox_discomfort",
}


class ResponseParser:
    @staticmethod
    def _strip_markdown_code_fence(text: str) -> str:
        """去掉 ``` 或 ```json 包裹，减少模型套 markdown 导致的解析失败。"""
        stripped = text.strip()
        if not stripped.startswith("```"):
            return text
        lines = stripped.splitlines()
        if not lines:
            return text
        # 去掉首行 ``` 或 ```json
        lines = lines[1:]
        while lines and not lines[-1].strip():
            lines.pop()
        if lines and lines[-1].strip() == "```":
            lines = lines[:-1]
        return "\n".join(lines).strip()
    def parse(self, raw_text: str, *, session_id: str) -> AnalyzeResponse:
        raw_text = self._strip_markdown_code_fence(raw_text.strip())
        no_cat_response = self._no_cat_response(raw_text, session_id)
        if no_cat_response is not None:
            no_cat_response.raw_model_output = raw_text
            return no_cat_response
        try:
            payload = self._extract_json(raw_text)
            self._normalize_payload_in_place(payload)
            response = AnalyzeResponse.model_validate(payload)
        except (ValidationError, json.JSONDecodeError, TypeError, ValueError):
            response = self._partial_structured_response(raw_text, session_id) or self._fallback_response(raw_text, session_id)
        response.session_id = session_id
        response.raw_model_output = raw_text
        self.calibrate_response(response)
        return response

    def calibrate_response(self, response: AnalyzeResponse) -> None:
        """后置校准：抑制「无视觉依据却过高 confidence」等常见过拟合。"""
        response.emotion_assessment.primary = self._normalize_emotion_primary(response.emotion_assessment.primary)
        primary = (response.emotion_assessment.primary or "").strip().lower()
        conf = float(response.emotion_assessment.confidence)

        if self._looks_like_non_cat_target(response):
            self._force_non_cat_response(response)
            return

        self._enforce_consistency_rules(response)
        primary = (response.emotion_assessment.primary or "").strip().lower()

        if primary not in ("no_cat", "unknown", ""):
            if not response.evidence.visual:
                conf = min(conf, 0.52)
        elif primary == "unknown":
            conf = min(conf, 0.5)

        if any(hint in response.summary for hint in _UNCERTAIN_SUMMARY_HINTS):
            conf = min(conf, 0.48)

        response.emotion_assessment.confidence = max(0.0, min(1.0, conf))

    @staticmethod
    def _looks_like_non_cat_target(response: AnalyzeResponse) -> bool:
        visual_corpus = " ".join(
            [
                response.summary or "",
                " ".join(response.emotion_assessment.signals),
                " ".join(response.evidence.visual),
                " ".join(response.health_risk_assessment.triggers),
                response.health_risk_assessment.reason or "",
            ]
        ).lower()
        if not any(token in visual_corpus for token in _NON_CAT_TOKENS):
            return False
        if any(token in visual_corpus for token in _CAT_TOKENS):
            return False
        return True

    @staticmethod
    def _force_non_cat_response(response: AnalyzeResponse) -> None:
        response.summary = "这画面里不像本喵，请把镜头对准我喵。"
        response.emotion_assessment.primary = "no_cat"
        response.emotion_assessment.confidence = 0.9
        response.emotion_assessment.signals = ["不像本喵"]
        response.health_risk_assessment.level = RiskLevel.LOW
        response.health_risk_assessment.score = min(response.health_risk_assessment.score, 0.2)
        response.health_risk_assessment.triggers = ["non_cat_target_detected", "no_cat_detected"]
        response.health_risk_assessment.reason = "这次画面里更像别的小动物，不是本喵，所以没法判断我的状态喵。"
        response.evidence.visual = ["画面主体不像本喵"]
        response.evidence.knowledge_refs = []
        response.cat_target_box = None
        response.care_suggestions = ["请把镜头稳稳对准本喵再试喵。"]
        response.urgent_flags = ["non_cat_target_detected", "no_cat_detected"]
        response.followup_questions = []

    @staticmethod
    def _enforce_consistency_rules(response: AnalyzeResponse) -> None:
        primary = (response.emotion_assessment.primary or "").strip().lower()
        has_visual_evidence = bool(response.evidence.visual)

        if primary == "no_cat":
            response.health_risk_assessment.level = RiskLevel.LOW
            response.health_risk_assessment.score = min(response.health_risk_assessment.score, 0.25)
            if "no_cat_detected" not in response.urgent_flags:
                response.urgent_flags.append("no_cat_detected")
            response.health_risk_assessment.triggers = list(
                dict.fromkeys([*response.health_risk_assessment.triggers, "no_cat_detected"])
            )
            response.cat_target_box = None
            if not response.care_suggestions:
                response.care_suggestions = ["请把镜头稳稳对准本喵再试喵。"]
            response.followup_questions = []
            return

        # 高危输出必须存在清晰视觉证据，否则强制降为中风险，减少幻觉性误报。
        if response.health_risk_assessment.level in {RiskLevel.HIGH, RiskLevel.URGENT} and not has_visual_evidence:
            response.health_risk_assessment.level = RiskLevel.MEDIUM
            response.health_risk_assessment.score = min(response.health_risk_assessment.score, 0.6)
            if "visual_evidence_weak" not in response.health_risk_assessment.triggers:
                response.health_risk_assessment.triggers.append("visual_evidence_weak")
            if "visual_evidence_weak" not in response.urgent_flags:
                response.urgent_flags.append("visual_evidence_weak")

    @staticmethod
    def _normalize_payload_in_place(payload: dict) -> None:
        """兼容模型大小写/别名，减少校验失败导致的整段降级。"""
        h = payload.get("health_risk_assessment")
        if isinstance(h, dict) and "level" in h and isinstance(h["level"], str):
            h["level"] = ResponseParser._normalize_risk_level_string(h["level"])
        e = payload.get("emotion_assessment")
        if isinstance(e, dict) and "primary" in e and isinstance(e["primary"], str):
            e["primary"] = ResponseParser._normalize_emotion_primary(e["primary"])

    def _extract_json(self, raw_text: str) -> dict:
        try:
            return json.loads(raw_text)
        except json.JSONDecodeError:
            start = raw_text.find("{")
            if start == -1:
                raise

            decoder = json.JSONDecoder()
            payload, _ = decoder.raw_decode(raw_text[start:])
            return payload

    def _partial_structured_response(self, raw_text: str, session_id: str) -> AnalyzeResponse | None:
        summary = self._extract_string(raw_text, "summary")
        primary = self._extract_string(raw_text, "primary")
        level = self._extract_string(raw_text, "level")
        if not summary or not primary or not level:
            return None

        confidence = self._extract_number(raw_text, "confidence", default=0.5)
        score = self._extract_number(raw_text, "score", default=0.5)
        signals = self._extract_array(raw_text, "signals")
        triggers = self._extract_array(raw_text, "triggers")
        visual = self._extract_array(raw_text, "visual")
        textual = self._extract_array(raw_text, "textual")
        knowledge_refs = self._extract_array(raw_text, "knowledge_refs")
        care_suggestions = self._extract_array(raw_text, "care_suggestions")
        followup_questions = self._extract_array(raw_text, "followup_questions")
        urgent_flags = self._extract_array(raw_text, "urgent_flags")
        reason = self._extract_string(raw_text, "reason") or "已根据模型部分输出提取风险原因。"
        disclaimer = self._extract_string(raw_text, "disclaimer") or "本结果仅用于风险提示与照护建议，不构成医疗诊断。"

        try:
            risk_level = RiskLevel(self._normalize_risk_level_string(level))
        except ValueError:
            risk_level = RiskLevel.MEDIUM

        cat_box = self._extract_cat_target_box(raw_text)

        partial = AnalyzeResponse(
            session_id=session_id,
            summary=summary,
            emotion_assessment=EmotionAssessment(
                primary=self._normalize_emotion_primary(primary),
                confidence=confidence,
                signals=signals,
            ),
            health_risk_assessment=HealthRiskAssessment(
                level=risk_level,
                score=score,
                triggers=triggers,
                reason=reason,
            ),
            evidence=EvidenceBundle(
                visual=visual,
                textual=textual,
                knowledge_refs=knowledge_refs,
            ),
            cat_target_box=cat_box,
            care_suggestions=care_suggestions or ["继续观察猫咪状态变化。"],
            urgent_flags=urgent_flags,
            followup_questions=followup_questions or ["它最近 12 小时的进食和饮水情况如何？"],
            disclaimer=disclaimer,
        )
        return partial

    @staticmethod
    def _normalize_risk_level_string(level: str) -> str:
        key = level.strip().lower()
        aliases = {
            "med": "medium",
            "mid": "medium",
            "moderate": "medium",
            "critical": "urgent",
            "severe": "high",
            "hi": "high",
        }
        key = aliases.get(key, key)
        if key in ("low", "medium", "high", "urgent"):
            return key
        return "medium"

    @staticmethod
    def _normalize_emotion_primary(primary: str) -> str:
        key = primary.strip().lower()
        if not key:
            return "unknown"
        if key in _EMOTION_PRIMARY_ALIASES:
            return _EMOTION_PRIMARY_ALIASES[key]
        for alias, normalized in _EMOTION_PRIMARY_ALIASES.items():
            if alias in key:
                return normalized
        return key

    def _extract_cat_target_box(self, raw_text: str) -> CatTargetBox | None:
        obj = self._extract_json_object(raw_text, "cat_target_box")
        if obj is None:
            return None
        try:
            return CatTargetBox.model_validate(obj)
        except ValidationError:
            return None

    @staticmethod
    def _extract_json_object(raw_text: str, key: str) -> dict | None:
        pattern = rf'"{re.escape(key)}"\s*:\s*(\{{)'
        match = re.search(pattern, raw_text)
        if not match:
            return None
        start = match.start(1)
        decoder = json.JSONDecoder()
        try:
            obj, _ = decoder.raw_decode(raw_text[start:])
        except json.JSONDecodeError:
            return None
        return obj if isinstance(obj, dict) else None

    @staticmethod
    def _extract_string(raw_text: str, key: str) -> str | None:
        pattern = rf'"{re.escape(key)}"\s*:\s*"([^"]*)"'
        match = re.search(pattern, raw_text)
        return match.group(1) if match else None

    @staticmethod
    def _extract_number(raw_text: str, key: str, default: float) -> float:
        pattern = rf'"{re.escape(key)}"\s*:\s*([0-9]+(?:\.[0-9]+)?)'
        match = re.search(pattern, raw_text)
        return float(match.group(1)) if match else default

    @staticmethod
    def _extract_array(raw_text: str, key: str) -> list[str]:
        pattern = rf'"{re.escape(key)}"\s*:\s*(\[[\s\S]*?\])'
        match = re.search(pattern, raw_text)
        if not match:
            return []
        try:
            parsed = json.loads(match.group(1))
            return [str(item) for item in parsed]
        except json.JSONDecodeError:
            return []

    def _fallback_response(self, raw_text: str, session_id: str) -> AnalyzeResponse:
        lowered = raw_text.lower()
        risk = RiskLevel.MEDIUM
        score = 0.5
        triggers: list[str] = []
        if any(token in lowered for token in ("排尿", "无尿", "极少尿", "呕吐", "步态")):
            risk = RiskLevel.HIGH
            score = 0.8
            triggers.append("文本提及高风险信号")
        return AnalyzeResponse(
            session_id=session_id,
            summary="本喵这次状态还说不太准，先给你保底判断喵。",
            emotion_assessment={"primary": "unknown", "confidence": 0.3, "signals": []},
            health_risk_assessment=HealthRiskAssessment(
                level=risk,
                score=score,
                triggers=triggers,
                reason="本喵这次表达得不够完整，所以先给你一个保底风险提示喵。",
            ),
            evidence={"visual": [], "textual": [raw_text[:300]], "knowledge_refs": []},
            care_suggestions=[
                "再给本喵拍清楚一点的照片或 5 到 15 秒视频喵。",
                "帮我继续看看进食、饮水、排尿和精神状态喵。",
            ],
            urgent_flags=["format_fallback"] if risk in {RiskLevel.HIGH, RiskLevel.URGENT} else [],
            followup_questions=[
                "本喵最近 12 小时吃喝怎么样喵？",
                "我有没有反复蹲猫砂盆或明显不想动喵？",
            ],
        )

    def _no_cat_response(self, raw_text: str, session_id: str) -> AnalyzeResponse | None:
        indicators = (
            "未检测到猫",
            "没有猫",
            "画面中没有猫",
            "未看到猫",
            "未发现猫",
            "未识别到猫",
            "检测不到猫",
            "no_cat",
            "no cat",
        )
        lowered = raw_text.lower()
        if not any(
            (indicator in lowered if indicator.isascii() else indicator in raw_text) for indicator in indicators
        ):
            return None

        return AnalyzeResponse(
            session_id=session_id,
            summary="本喵还没出现在画面里，请把镜头对准我喵。",
            emotion_assessment=EmotionAssessment(
                primary="no_cat",
                confidence=0.95,
                signals=["本喵没入镜"],
            ),
            health_risk_assessment=HealthRiskAssessment(
                level=RiskLevel.LOW,
                score=0.1,
                triggers=["no_cat_detected"],
                reason="本喵还没出现在画面里，现在没法判断状态喵。",
            ),
            evidence=EvidenceBundle(
                visual=["本喵没出现在画面里"],
                textual=[],
                knowledge_refs=[],
            ),
            care_suggestions=["请把镜头稳稳对准本喵再试喵。"],
            urgent_flags=["no_cat_detected"],
            followup_questions=[],
        )


response_parser = ResponseParser()
