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
            )
            while (true) {
                runCatching {
                    _uiState.value = _uiState.value.copy(
                        loading = true,
                        statusText = "抓帧分析中...",
                        catTargetStatus = CatTargetStatus.UNKNOWN,
                        catTargetHint = "正在识别猫目标...",
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
                    statusText = "手动抓帧分析中...",
                    catTargetStatus = CatTargetStatus.UNKNOWN,
                    catTargetHint = "正在识别猫目标...",
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
                    statusText = "手动抓帧失败",
                    catTargetHint = "识别失败，请重试",
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
        )
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }

    private fun applyResponse(response: AnalyzeResponse) {
        val isNoCat = response.urgent_flags.contains("no_cat_detected") || response.emotion_assessment.primary == "no_cat"
        val signature = buildString {
            append(response.emotion_assessment.primary)
            append("|")
            append(response.health_risk_assessment.level)
            append("|")
            append(response.summary)
        }

        if (isNoCat) {
            pendingSignature = null
            pendingCount = 0
            val nextNoCatCount = _uiState.value.consecutiveNoCatCount + 1
            _uiState.value = _uiState.value.copy(
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
                intervalMillis = if (nextNoCatCount >= 3) 4200L else _uiState.value.intervalMillis,
                consecutiveNoCatCount = nextNoCatCount,
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

        if (pendingCount >= 2 || _uiState.value.latestResponse == null) {
            val hasBox = response.cat_target_box != null
            _uiState.value = _uiState.value.copy(
                loading = false,
                latestResponse = response,
                sessionId = response.session_id,
                statusText = "最近一次分析已更新",
                catTargetStatus = CatTargetStatus.DETECTED,
                catTargetHint = if (hasBox) "已检测到猫，目标框已更新" else "已检测到猫，已显示参考框",
                intervalMillis = if (_uiState.value.consecutiveNoCatCount >= 3) 2500L else _uiState.value.intervalMillis,
                consecutiveNoCatCount = 0,
                error = null,
            )
        } else {
            _uiState.value = _uiState.value.copy(
                loading = false,
                sessionId = response.session_id,
                statusText = "结果确认中...",
                catTargetStatus = CatTargetStatus.DETECTED,
                catTargetHint = "已检测到猫，正在确认结果稳定性",
                intervalMillis = if (_uiState.value.consecutiveNoCatCount >= 3) 2500L else _uiState.value.intervalMillis,
                consecutiveNoCatCount = 0,
                error = null,
            )
        }
    }
}
