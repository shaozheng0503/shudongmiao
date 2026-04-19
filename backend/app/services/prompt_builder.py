from __future__ import annotations

import json

from app.domain.schemas import (
    ChatMessage,
    ContentPart,
    ContentType,
    KnowledgeSnippet,
    PreparedMedia,
    SceneHint,
    SessionState,
)


SYSTEM_INSTRUCTION = """
你是“猫咪健康 + 情绪识别 Agent”。
- 基于图片、视频关键帧、用户描述与知识片段综合判断；优先依据画面中可观察行为与体态，再结合文本。
- 默认用猫的第一人称说话，summary / reason / care_suggestions / followup_questions 可适度加入“本喵 / 本咪 / 喵”等亲切表达，但每句最多一次；若风险较高，减少卖萌词，优先清楚表达。
- 若用户文字与画面明显矛盾，以画面为准；在 evidence.textual 中用一句话说明“用户描述与画面不一致/需核实”，不要强行迎合文字。
- 若画面主体是狗/犬或其他非猫动物，必须按 no_cat 返回，不得把狗当猫分析。
- 先做可分析性判定，再做情绪与风险判定：看不清、遮挡严重、目标过小、拖影明显时，优先 no_cat 或 unknown。
- 同一画面有多个动物且无法确认哪只是猫时，返回 unknown，不要猜测主体。
- 仅凭局部（如尾巴/耳朵/背部一角）不得给高置信结论，confidence 应显著下调。
- 禁止编造画面中未出现的行为与体征；高风险判断必须附带清晰视觉证据。
- 知识片段只用于佐证，不得覆盖画面事实：未确认猫目标时不得输出猫病症结论。
- 当 primary=no_cat 时，必须满足：health_risk_assessment.level=low，urgent_flags 含 no_cat_detected，cat_target_box=null。
- 只做风险提示和照护建议，不做疾病诊断和确定性医疗结论。
- 观察时系统检查：耳朵朝向与是否后压、瞳孔大小、尾巴高度与摆动、身体是否蜷缩或弓背、是否躲藏/僵住、呼吸是否急促、步态是否正常、毛发是否凌乱。
- 当画面出现积极状态时，优先区分并使用更细粒度情绪：happy（开心）、relaxed（放松）、curious（好奇）；避免全部笼统写成 unknown 或 playful。
- 若画面模糊、过暗、猫过小或被遮挡，应降低 confidence，并在 evidence.visual 中说明局限；不要编造画面中不存在的细节。
- 若线索涉及排尿困难、持续不进食、明显精神萎靡、反复呕吐、异常步态、张口呼吸或抽搐样动作，请提高风险感知。
- 输出必须是严格 JSON，不要 markdown，不要解释，不要重复；不要用 ``` 或 ```json 代码块包裹。
- 输出尽量短：summary <= 30字；signals/visual/textual/triggers/care_suggestions/followup_questions 各最多 2 项；每项 <= 20字。
""".strip()


FEW_SHOT_JSON_LINE = (
    '{"summary":"示例：本喵耳朵后压，像是有点紧张喵","emotion_assessment":{"primary":"stress_alert","confidence":0.62,'
    '"signals":["耳后压"]},"health_risk_assessment":{"level":"medium","score":0.55,"triggers":["紧张体态"],'
    '"reason":"本喵现在能看到明显应激体态"},"evidence":{"visual":["耳后压"],"textual":["用户称环境嘈杂"],"knowledge_refs":[]},'
    '"cat_target_box":null,"care_suggestions":["先帮本喵减少刺激喵"],"urgent_flags":[],"followup_questions":["本喵最近进食怎么样喵？"],'
    '"disclaimer":"本结果仅用于风险提示与照护建议，不构成医疗诊断。"}'
)


SCENE_FOCUS: dict[SceneHint, str] = {
    SceneHint.STRESS: "侧重：应激与恐惧（躲藏、哈气、炸毛、僵住）、环境压力源。",
    SceneHint.APPETITE: "侧重：进食意愿、体重与体型线索、挑食与厌食相关行为。",
    SceneHint.LITTERBOX: "侧重：排尿姿势、蹲砂盆频率线索（画面能见的部分）、腹部不适姿势。",
    SceneHint.GENERAL: "侧重：整体精神、活动度与明显疼痛/不适姿势。",
}


OUTPUT_SCHEMA_HINT = {
    "summary": "一句猫视角的简短中文总结",
    "emotion_assessment": {
        "primary": "happy / relaxed / curious / stress_alert / playful / fearful / low_energy / pain_sign / litterbox_discomfort / no_cat / unknown",
        "confidence": 0.7,
        "signals": ["可见信号1", "可见信号2"],
    },
    "health_risk_assessment": {
        "level": "low | medium | high | urgent",
        "score": 0.6,
        "triggers": ["触发词1", "触发词2"],
        "reason": "简短原因",
    },
    "evidence": {
        "visual": ["视觉线索1", "视觉线索2"],
        "textual": ["文本线索1", "文本线索2"],
        "knowledge_refs": ["知识id1", "知识id2"],
    },
    "cat_target_box": {
        "x": 0.12,
        "y": 0.2,
        "width": 0.55,
        "height": 0.68,
        "confidence": 0.83,
    },
    "care_suggestions": ["猫视角建议1", "猫视角建议2"],
    "urgent_flags": ["若无则返回空数组"],
    "followup_questions": ["猫视角追问1", "猫视角追问2"],
    "disclaimer": "本结果仅用于风险提示与照护建议，不构成医疗诊断。",
}


class PromptBuilder:
    def build_analysis_messages(
        self,
        *,
        user_text: str,
        scene_hint: SceneHint,
        media: PreparedMedia,
        knowledge: list[KnowledgeSnippet],
        session: SessionState,
    ) -> list[ChatMessage]:
        user_parts = [
            ContentPart(
                type=ContentType.TEXT,
                text=self._compose_analysis_text(user_text, scene_hint, knowledge, session, media),
            )
        ]
        user_parts.extend(self._media_parts(media))
        return [
            ChatMessage(
                role="system",
                content=[ContentPart(type=ContentType.TEXT, text=SYSTEM_INSTRUCTION)],
            ),
            ChatMessage(role="user", content=user_parts),
        ]

    def build_followup_messages(
        self,
        *,
        session: SessionState,
        question_text: str,
        knowledge: list[KnowledgeSnippet],
    ) -> list[ChatMessage]:
        history_lines = [f"{turn.role}: {turn.text}" for turn in session.history[-8:]]
        if session.latest_response:
            history_lines.append(
                "assistant_structured_summary: "
                + session.latest_response.summary
            )
        user_text = "\n".join(
            [
                "继续追问，保持同一 JSON 结构。",
                "若无新图像，主要依据历史与知识作答；避免断言当前画面中无法验证的细节，必要时降低 confidence。",
                "历史：",
                *history_lines,
                "知识：",
                *self._knowledge_lines(knowledge),
                f"当前追问: {question_text}",
                "返回紧凑 JSON。",
                "schema: " + json.dumps(OUTPUT_SCHEMA_HINT, ensure_ascii=False, separators=(",", ":")),
            ]
        )
        return [
            ChatMessage(
                role="system",
                content=[ContentPart(type=ContentType.TEXT, text=SYSTEM_INSTRUCTION)],
            ),
            ChatMessage(
                role="user",
                content=[ContentPart(type=ContentType.TEXT, text=user_text)],
            ),
        ]

    def build_realtime_messages(
        self,
        *,
        user_text: str,
        scene_hint: SceneHint,
        media: PreparedMedia,
        knowledge: list[KnowledgeSnippet],
        session: SessionState,
    ) -> list[ChatMessage]:
        realtime_instruction = "\n".join(
            [
                self._compose_analysis_text(user_text, scene_hint, knowledge, session, media),
                "这是实时观察单帧。",
                "单帧判断不确定时，宁可略低 confidence，并在 evidence.visual 写明“单帧局限”。",
                SCENE_FOCUS.get(scene_hint, SCENE_FOCUS[SceneHint.GENERAL]),
                "如果检测到猫，必须返回 cat_target_box（归一化坐标，x/y 为左上角，width/height 为宽高，均在 0~1；坐标应对准猫全身可见区域）。",
                "如果画面里没有猫，必须返回猫视角 no_cat，例如 summary=本喵还没出现在画面里，请把镜头对准我喵，emotion_assessment.primary=no_cat，health_risk_assessment.level=low，urgent_flags包含no_cat_detected，care_suggestions提示把镜头对准猫。",
                "如果画面主体是狗/犬或其他非猫动物，也必须按未检测到猫处理（no_cat）。",
                "未检测到猫时，cat_target_box 必须为 null。",
                "如果猫只占画面很小一部分或被遮挡严重，也按未检测到猫处理。",
                "若只有局部身体或单帧证据不足，优先返回 unknown 或 no_cat，不要给高风险结论。",
                "高风险（high/urgent）必须有清晰视觉证据；若证据弱，最多给 medium。",
                "实时场景以稳定为先：避免单帧噪声导致过激结论。",
                "返回更短更稳的 JSON，不要重复。",
            ]
        )
        user_parts = [ContentPart(type=ContentType.TEXT, text=realtime_instruction)]
        user_parts.extend(self._media_parts(media))
        return [
            ChatMessage(
                role="system",
                content=[ContentPart(type=ContentType.TEXT, text=SYSTEM_INSTRUCTION)],
            ),
            ChatMessage(role="user", content=user_parts),
        ]

    def _compose_analysis_text(
        self,
        user_text: str,
        scene_hint: SceneHint,
        knowledge: list[KnowledgeSnippet],
        session: SessionState,
        media: PreparedMedia,
    ) -> str:
        history_lines = [f"{turn.role}: {turn.text}" for turn in session.history[-4:]]
        sections = [
            f"场景: {scene_hint.value}",
            SCENE_FOCUS.get(scene_hint, SCENE_FOCUS[SceneHint.GENERAL]),
            f"描述: {user_text}",
            "知识:",
            *self._knowledge_lines(knowledge),
        ]
        if history_lines:
            sections.extend(["历史:", *history_lines])
        media_note = self._media_guidance(media)
        sections.extend(
            [
                media_note,
                "合格 JSON 参考（字段须齐全，内容替换为你的判断，勿照抄）：" + FEW_SHOT_JSON_LINE,
                "只返回紧凑 JSON。",
                "schema: " + json.dumps(OUTPUT_SCHEMA_HINT, ensure_ascii=False, separators=(",", ":")),
            ]
        )
        return "\n".join(sections)

    @staticmethod
    def _media_guidance(media: PreparedMedia) -> str:
        if media.frames and len(media.frames) > 1:
            return (
                f"媒体: 视频关键帧共 {len(media.frames)} 张，按时间顺序排列；请综合趋势与最清晰的一帧，"
                "在 evidence.visual 分别简要说明（若帧间矛盾，写明以哪类线索为主）。"
            )
        if media.data_url and media.media_type.value == "image":
            return "媒体: 单张静态图；结论仅反映拍摄瞬间。"
        return "媒体: 请结合给定画面判断。"

    @staticmethod
    def _knowledge_lines(knowledge: list[KnowledgeSnippet]) -> list[str]:
        if not knowledge:
            return ["- 无"]
        lines: list[str] = []
        for index, item in enumerate(knowledge):
            limit = 170 if index < 2 else 110
            prefix = "相似示例" if item.source_type in {"visual", "hybrid"} else "规则"
            content = item.content.replace("\n", " ")[:limit]
            parts = [f"- [{item.doc_id}] {prefix}:{item.title}"]
            if content:
                parts.append(content)
            if item.possible_causes:
                parts.append("可能原因:" + "、".join(item.possible_causes[:2]))
            if item.care_advice:
                parts.append("建议:" + "、".join(item.care_advice[:1]))
            lines.append("；".join(parts))
        return lines

    @staticmethod
    def _media_parts(media: PreparedMedia) -> list[ContentPart]:
        if media.data_url:
            return [
                ContentPart(
                    type=ContentType.IMAGE if media.media_type.value == "image" else ContentType.VIDEO,
                    data=media.data_url,
                    mime_type=media.mime_type,
                )
            ]

        parts: list[ContentPart] = []
        for frame in media.frames:
            parts.append(
                ContentPart(
                    type=ContentType.IMAGE,
                    data=frame.data_url,
                    mime_type="image/jpeg",
                    metadata={"timestamp_ms": frame.timestamp_ms},
                )
            )
        return parts


prompt_builder = PromptBuilder()
