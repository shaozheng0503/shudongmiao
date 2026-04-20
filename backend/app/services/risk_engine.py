from __future__ import annotations

from collections.abc import Iterable

from app.domain.schemas import AnalyzeResponse, KnowledgeSnippet, RiskLevel

_NEGATION_TERMS = ("无", "没有", "并无", "未", "未见", "不是", "并非", "否认")


class RiskEngine:
    HARD_RULES: tuple[tuple[tuple[str, ...], RiskLevel, str, str], ...] = (
        (("排尿困难", "无尿", "尿不出来", "极少尿", "几乎没有尿", "几乎无尿"), RiskLevel.URGENT, "urination_issue_possible", "出现排尿困难或几乎无尿信号"),
        (("频繁蹲猫砂盆", "反复蹲猫砂盆"), RiskLevel.HIGH, "litterbox_warning", "频繁蹲猫砂盆提示较高泌尿风险"),
        (("持续不进食", "不吃", "食欲差"), RiskLevel.HIGH, "appetite_drop", "持续不进食或食欲明显下降"),
        (("精神萎靡", "没精神", "精神差"), RiskLevel.HIGH, "low_energy", "明显精神状态下降"),
        (("反复呕吐", "一直吐", "呕吐"), RiskLevel.HIGH, "vomit_warning", "反复呕吐属于高风险信号"),
        (("异常步态", "走路不稳", "跛行"), RiskLevel.HIGH, "gait_issue", "异常步态提示需尽快评估"),
        (("张口呼吸", "呼吸急促", "腹式呼吸"), RiskLevel.HIGH, "respiratory_distress", "呼吸模式异常需尽快评估"),
        (("抽搐", "癫痫", "发抖不止"), RiskLevel.URGENT, "neuro_or_severe", "抽搐或疑似神经系统急症"),
        (("黄疸", "皮肤发黄", "眼白发黄"), RiskLevel.HIGH, "icterus_possible", "黄疸样表现需尽快就医排查"),
        (("流口水", "流涎", "口吐白沫"), RiskLevel.MEDIUM, "oral_signs", "口腔或神经相关异常需结合线下检查"),
        (("高烧", "发热", "体温高"), RiskLevel.HIGH, "fever_possible", "发热样描述需尽快排查感染等问题"),
    )

    ORDER = {
        RiskLevel.LOW: 0,
        RiskLevel.MEDIUM: 1,
        RiskLevel.HIGH: 2,
        RiskLevel.URGENT: 3,
    }

    def apply(
        self,
        response: AnalyzeResponse,
        *,
        user_text: str,
        model_tags: Iterable[str],
        knowledge_hits: Iterable[KnowledgeSnippet] = (),
    ) -> AnalyzeResponse:
        if response.emotion_assessment.primary == "no_cat" or "no_cat_detected" in response.urgent_flags:
            is_non_cat_target = "non_cat_target_detected" in response.urgent_flags
            response.health_risk_assessment.level = RiskLevel.LOW
            response.health_risk_assessment.score = min(response.health_risk_assessment.score, 0.25)
            response.health_risk_assessment.triggers = (
                ["non_cat_target_detected", "no_cat_detected"]
                if is_non_cat_target
                else ["no_cat_detected"]
            )
            response.health_risk_assessment.reason = (
                "这次画面里更像别的小动物，不是本喵，所以没法判断我的状态喵。"
                if is_non_cat_target
                else "本喵还没出现在画面里，现在没法判断状态喵。"
            )
            response.urgent_flags = (
                ["non_cat_target_detected", "no_cat_detected"] if is_non_cat_target else ["no_cat_detected"]
            )
            response.care_suggestions = ["请把镜头稳稳对准本喵再试喵。"]
            response.retrieved_knowledge = []
            return response

        risk_texts = [
            user_text,
            response.summary,
            " ".join(response.emotion_assessment.signals),
            " ".join(response.health_risk_assessment.triggers),
            " ".join(model_tags),
            " ".join(response.evidence.visual),
            " ".join(response.evidence.textual),
            response.health_risk_assessment.reason,
        ]
        # 知识库命中用于解释与建议，不直接作为“风险触发证据”，避免把知识条目词面当成当前事实。
        has_visual_or_textual_evidence = bool(response.evidence.visual or response.evidence.textual)

        upgraded_level = response.health_risk_assessment.level
        urgent_flags = set(response.urgent_flags)
        triggers = list(response.health_risk_assessment.triggers)
        reason_parts = [response.health_risk_assessment.reason] if response.health_risk_assessment.reason else []

        for keywords, target_level, flag, reason in self.HARD_RULES:
            matched = any(self._contains_non_negated_keyword(risk_texts, keyword) for keyword in keywords)
            if matched:
                # high/urgent 需要至少有当前帧视觉或文本证据，避免仅靠模糊总结词误报。
                if target_level in {RiskLevel.HIGH, RiskLevel.URGENT} and not has_visual_or_textual_evidence:
                    continue
                if self.ORDER[target_level] > self.ORDER[upgraded_level]:
                    upgraded_level = target_level
                urgent_flags.add(flag)
                if not any(keyword in trigger for trigger in triggers for keyword in keywords):
                    triggers.append(reason)
                reason_parts.append(reason)

        response.health_risk_assessment.level = upgraded_level
        response.health_risk_assessment.score = max(
            response.health_risk_assessment.score,
            self._level_score(upgraded_level),
        )
        response.health_risk_assessment.triggers = triggers
        response.health_risk_assessment.reason = "；".join(dict.fromkeys(reason_parts)) or "未触发额外风险规则。"
        response.urgent_flags = sorted(urgent_flags)

        has_visual_evidence = bool(response.evidence.visual)
        has_textual_evidence = bool(response.evidence.textual)
        if response.health_risk_assessment.level in {RiskLevel.HIGH, RiskLevel.URGENT}:
            if not (has_visual_evidence and has_textual_evidence):
                response.health_risk_assessment.level = RiskLevel.MEDIUM
                response.health_risk_assessment.score = min(response.health_risk_assessment.score, 0.62)
                if "risk_dual_evidence_missing" not in response.health_risk_assessment.triggers:
                    response.health_risk_assessment.triggers.append("risk_dual_evidence_missing")
                if "risk_dual_evidence_missing" not in response.urgent_flags:
                    response.urgent_flags.append("risk_dual_evidence_missing")

        if response.health_risk_assessment.level in {RiskLevel.HIGH, RiskLevel.URGENT}:
            if "如果本喵一直这样或更难受了，请尽快带我线下就医喵。" not in response.care_suggestions:
                response.care_suggestions.append("如果本喵一直这样或更难受了，请尽快带我线下就医喵。")

        return response

    @staticmethod
    def _contains_non_negated_keyword(texts: Iterable[str], keyword: str) -> bool:
        key = (keyword or "").strip().lower()
        if not key:
            return False
        for text in texts:
            content = (text or "").lower()
            if not content:
                continue
            start = content.find(key)
            while start != -1:
                left = content[max(0, start - 4) : start]
                if not any(neg in left for neg in _NEGATION_TERMS):
                    return True
                start = content.find(key, start + len(key))
        return False

    @staticmethod
    def _level_score(level: RiskLevel) -> float:
        mapping = {
            RiskLevel.LOW: 0.25,
            RiskLevel.MEDIUM: 0.5,
            RiskLevel.HIGH: 0.8,
            RiskLevel.URGENT: 0.95,
        }
        return mapping[level]


risk_engine = RiskEngine()
