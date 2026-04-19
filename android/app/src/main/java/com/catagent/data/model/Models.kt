package com.catagent.data.model

data class AnalyzeResponse(
    val session_id: String,
    val summary: String,
    val emotion_assessment: EmotionAssessment,
    val health_risk_assessment: HealthRiskAssessment,
    val evidence: EvidenceBundle,
    val cat_target_box: CatTargetBox? = null,
    val care_suggestions: List<String>,
    val urgent_flags: List<String>,
    val followup_questions: List<String>,
    val realtime_debug: RealtimeFusionDebug? = null,
    val retrieved_knowledge: List<KnowledgeSnippet> = emptyList(),
    val disclaimer: String,
)

data class KnowledgeSnippet(
    val doc_id: String,
    val title: String,
    val category: String,
    val content: String,
    val score: Double = 0.0,
    val tags: List<String> = emptyList(),
    val source_type: String = "text",
    val section: String? = null,
    val possible_causes: List<String> = emptyList(),
    val care_advice: List<String> = emptyList(),
    val image_refs: List<String> = emptyList(),
    val matched_image_ref: String? = null,
)

data class EmotionAssessment(
    val primary: String,
    val confidence: Double,
    val signals: List<String>,
)

data class HealthRiskAssessment(
    val level: String,
    val score: Double,
    val triggers: List<String>,
    val reason: String,
)

data class EvidenceBundle(
    val visual: List<String>,
    val textual: List<String>,
    val knowledge_refs: List<String>,
)

data class CatTargetBox(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val confidence: Double,
)

data class RealtimeFusionDebug(
    val window_size: Int = 1,
    val fused: Boolean = false,
    val raw_emotions: List<String> = emptyList(),
    val raw_risk_levels: List<String> = emptyList(),
    val note: String = "",
)

data class FollowupRequest(
    val session_id: String,
    val question_text: String,
)

data class SessionEnvelope(
    val exists: Boolean,
    val session: SessionPayload? = null,
)

data class SessionPayload(
    val session_id: String,
    val scene_hint: String,
    val latest_response: AnalyzeResponse? = null,
)

data class HealthResponse(
    val status: String,
    val model_ws_url: String? = null,
    val model_ws_protocol: String? = null,
    val use_mock_model: Boolean? = null,
    val media_payload_format: String? = null,
    val video_transport_mode: String? = null,
    val capabilities: HealthCapabilities? = null,
    val minicpm_temperature: Double? = null,
    val minicpm_send_generation_params: Boolean? = null,
    val minicpm_max_tokens: Int? = null,
)

data class HealthCapabilities(
    val ffmpeg: Boolean = false,
    val ffprobe: Boolean = false,
    val opencv: Boolean = false,
    val numpy: Boolean = false,
    val native_video_ws_probe_verified: Boolean = true,
    val native_image_ws_probe_verified: Boolean = true,
)
