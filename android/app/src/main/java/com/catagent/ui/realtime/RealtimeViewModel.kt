package com.catagent.ui.realtime

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.catagent.data.model.AnalyzeResponse
import com.catagent.data.network.CatAgentRepository
import com.catagent.data.network.toUserFacingApiMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class CatTargetStatus {
    UNKNOWN,
    DETECTED,
    NOT_DETECTED,
}

data class RealtimeUiState(
    val running: Boolean = false,
    val loading: Boolean = false,
    val intervalMillis: Long = 2500L,
    val sessionId: String? = null,
    val latestResponse: AnalyzeResponse? = null,
    val catTargetStatus: CatTargetStatus = CatTargetStatus.UNKNOWN,
    val catTargetHint: String = "等待识别猫目标",
    val statusText: String = "等待开始实时观察",
    val consecutiveNoCatCount: Int = 0,
    val switchingTarget: Boolean = false,
    val error: String? = null,
)

class RealtimeViewModel(
    private val repository: CatAgentRepository = CatAgentRepository(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(RealtimeUiState())
    val uiState: StateFlow<RealtimeUiState> = _uiState.asStateFlow()

    private var loopJob: Job? = null
    private var pendingSignature: String? = null
    private var pendingCount: Int = 0

    fun start(
        contentResolver: ContentResolver,
        sceneHint: String,
        inputTextProvider: () -> String,
        captureFrame: suspend () -> Uri,
    ) {
        if (loopJob != null) return

        loopJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                running = true,
                error = null,
                statusText = "实时观察中...",
                catTargetStatus = CatTargetStatus.UNKNOWN,
                catTargetHint = "实时观察中，等待首帧结果",
                switchingTarget = false,
            )
            while (true) {
                runCatching {
                    _uiState.value = _uiState.value.copy(
                        loading = true,
                        statusText = "正在识别中...",
                        catTargetStatus = CatTargetStatus.UNKNOWN,
                        catTargetHint = "正在识别猫目标...",
                        switchingTarget = false,
                    )
                    val frameUri = captureFrame()
                    repository.analyzeRealtimeFrame(
                        contentResolver = contentResolver,
                        uri = frameUri,
                        inputText = inputTextProvider().ifBlank { "实时观察当前帧，请返回猫咪情绪与风险提示。" },
                        sceneHint = sceneHint,
                        sessionId = _uiState.value.sessionId,
                    )
                }.onSuccess { response ->
                    applyResponse(response)
                }.onFailure { throwable ->
                    _uiState.value = _uiState.value.copy(
                        loading = false,
                        statusText = "实时观察失败",
                        catTargetHint = "识别失败，请重试",
                        switchingTarget = false,
                        error = throwable.toUserFacingApiMessage("实时观察失败"),
                    )
                }
                delay(_uiState.value.intervalMillis)
            }
        }
    }

    fun captureOnce(
        contentResolver: ContentResolver,
        sceneHint: String,
        inputTextProvider: () -> String,
        captureFrame: suspend () -> Uri,
    ) {
        viewModelScope.launch {
            runCatching {
                _uiState.value = _uiState.value.copy(
                    loading = true,
                    statusText = "正在识别中...",
                    catTargetStatus = CatTargetStatus.UNKNOWN,
                    catTargetHint = "正在识别猫目标...",
                    switchingTarget = false,
                )
                val frameUri = captureFrame()
                repository.analyzeRealtimeFrame(
                    contentResolver = contentResolver,
                    uri = frameUri,
                    inputText = inputTextProvider().ifBlank { "实时观察当前帧，请返回猫咪情绪与风险提示。" },
                    sceneHint = sceneHint,
                    sessionId = _uiState.value.sessionId,
                )
            }.onSuccess { response ->
                applyResponse(response)
            }.onFailure { throwable ->
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    statusText = "识别失败，请重试",
                    catTargetHint = "识别失败，请重试",
                    switchingTarget = false,
                    error = throwable.toUserFacingApiMessage("手动抓帧失败"),
                )
            }
        }
    }

    fun setIntervalMillis(intervalMillis: Long) {
        _uiState.value = _uiState.value.copy(intervalMillis = intervalMillis)
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        _uiState.value = _uiState.value.copy(
            running = false,
            loading = false,
            statusText = "实时观察已停止",
            catTargetStatus = CatTargetStatus.UNKNOWN,
            catTargetHint = "实时观察已停止",
            switchingTarget = false,
        )
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }

    private fun applyResponse(response: AnalyzeResponse) {
        val current = _uiState.value
        val modelBusy = response.urgent_flags.contains("model_service_unavailable")
        val isNoCat = response.urgent_flags.contains("no_cat_detected") || response.emotion_assessment.primary == "no_cat"
        val signature = buildString {
            append(response.emotion_assessment.primary)
            append("|")
            append(response.health_risk_assessment.level)
            append("|")
            append(response.summary)
        }

        if (modelBusy) {
            _uiState.value = current.copy(
                loading = false,
                sessionId = response.session_id,
                statusText = "当前网络较忙，暂未拿到新一帧结果",
                catTargetStatus = CatTargetStatus.UNKNOWN,
                catTargetHint = "结果保持上一帧，请稍候重试",
                switchingTarget = false,
                error = null,
            )
            return
        }

        if (isNoCat) {
            pendingSignature = null
            pendingCount = 0
            val nextNoCatCount = current.consecutiveNoCatCount + 1
            _uiState.value = current.copy(
                loading = false,
                latestResponse = response,
                sessionId = response.session_id,
                statusText = if (nextNoCatCount >= 3) {
                    "连续${nextNoCatCount}帧未检测到猫，已放慢刷新节奏"
                } else {
                    "未检测到猫，请将镜头对准猫咪"
                },
                catTargetStatus = CatTargetStatus.NOT_DETECTED,
                catTargetHint = "未检测到猫，请对准猫咪后重试",
                intervalMillis = if (nextNoCatCount >= 3) 4200L else current.intervalMillis,
                consecutiveNoCatCount = nextNoCatCount,
                switchingTarget = false,
                error = null,
            )
            return
        }

        if (signature == pendingSignature) {
            pendingCount += 1
        } else {
            pendingSignature = signature
            pendingCount = 1
        }

        val latest = current.latestResponse
        val hasStrongEvidence = response.evidence.visual.isNotEmpty() &&
            ((response.cat_target_box?.confidence ?: 0.0) >= 0.55 || response.emotion_assessment.confidence >= 0.68)
        val targetChanged = latest?.let {
            it.emotion_assessment.primary != response.emotion_assessment.primary ||
                it.health_risk_assessment.level != response.health_risk_assessment.level
        } ?: false
        val shouldFastSwitch = latest != null && targetChanged && hasStrongEvidence

        if (pendingCount >= 2 || latest == null || shouldFastSwitch) {
            val hasBox = response.cat_target_box != null
            _uiState.value = current.copy(
                loading = false,
                latestResponse = response,
                sessionId = response.session_id,
                statusText = if (shouldFastSwitch) "已快速切换到新目标结果" else "最近一次分析已更新",
                catTargetStatus = CatTargetStatus.DETECTED,
                catTargetHint = if (hasBox) "已检测到猫，目标框已更新" else "已检测到猫，已显示参考框",
                intervalMillis = if (current.consecutiveNoCatCount >= 3) 2500L else current.intervalMillis,
                consecutiveNoCatCount = 0,
                switchingTarget = false,
                error = null,
            )
        } else {
            val switching = targetChanged
            _uiState.value = current.copy(
                loading = false,
                sessionId = response.session_id,
                statusText = if (switching) "检测到新目标，正在切换结果..." else "结果确认中...",
                catTargetStatus = if (switching) CatTargetStatus.UNKNOWN else CatTargetStatus.DETECTED,
                catTargetHint = if (switching) {
                    "新目标信号已出现，正在等待稳定后替换"
                } else {
                    "已检测到猫，正在确认结果稳定性"
                },
                intervalMillis = if (current.consecutiveNoCatCount >= 3) 2500L else current.intervalMillis,
                consecutiveNoCatCount = 0,
                switchingTarget = switching,
                error = null,
            )
        }
    }
}
