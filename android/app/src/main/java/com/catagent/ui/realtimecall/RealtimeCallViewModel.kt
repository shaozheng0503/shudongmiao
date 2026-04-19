package com.catagent.ui.realtimecall

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.catagent.data.model.AnalyzeResponse
import com.catagent.data.network.CatAgentRepository
import com.catagent.data.network.toUserFacingApiMessage
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class RealtimeCallCatStatus {
    UNKNOWN,
    DETECTED,
    NOT_DETECTED,
}

enum class AssistantTone {
    PROFESSIONAL,
    CARING,
}

enum class RealtimeCallModePreset {
    CUSTOM,
    RESPONSIVE,
    BALANCED,
    STABLE,
}

data class RealtimeDialogueTurn(
    val role: String,
    val text: String,
    val pending: Boolean = false,
    val ts: Long = System.currentTimeMillis(),
)

data class RealtimeCallUiState(
    val running: Boolean = false,
    val loading: Boolean = false,
    val targetIntervalMillis: Long = 1000L,
    val activeIntervalMillis: Long = 1000L,
    val uploadQuality: Int = 85,
    val uploadMaxEdge: Int = 1280,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val avgLatencyMs: Long = 0L,
    val dialogueMemory: List<String> = emptyList(),
    val sessionId: String? = null,
    val latestResponse: AnalyzeResponse? = null,
    val catStatus: RealtimeCallCatStatus = RealtimeCallCatStatus.UNKNOWN,
    val catHint: String = "等待识别猫目标",
    val consecutiveNoCatCount: Int = 0,
    val consecutiveDetectedCount: Int = 0,
    val modelBusyCount: Int = 0,
    val consecutiveBusyCount: Int = 0,
    val dialogueTurns: List<RealtimeDialogueTurn> = emptyList(),
    val dialogueLoading: Boolean = false,
    val queuedDialogueCount: Int = 0,
    val latestAssistantUtterance: String = "",
    val assistantTone: AssistantTone = AssistantTone.CARING,
    val modePreset: RealtimeCallModePreset = RealtimeCallModePreset.BALANCED,
    val interruptPriorityMode: Boolean = true,
    val statusText: String = "等待开始实时通话",
    val error: String? = null,
)

class RealtimeCallViewModel(
    private val repository: CatAgentRepository = CatAgentRepository(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(RealtimeCallUiState())
    val uiState: StateFlow<RealtimeCallUiState> = _uiState.asStateFlow()

    private var loopJob: Job? = null
    private val recentUserIntents = ArrayDeque<String>()
    private val recentModelSummaries = ArrayDeque<String>()
    private val dialogueTurns = ArrayDeque<RealtimeDialogueTurn>()
    private val pendingDialogueQuestions = ArrayDeque<String>()
    private var pendingAssistantTs: Long? = null
    private var dialogueRequestJob: Job? = null
    private var pendingAssistantStreamJob: Job? = null
    private var lastSubmittedDialogue: String? = null
    private var lastSubmittedDialogueAtMs: Long = 0L

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
                statusText = "实时通话中...",
                catStatus = RealtimeCallCatStatus.UNKNOWN,
                catHint = "等待首帧结果",
                consecutiveNoCatCount = 0,
                consecutiveDetectedCount = 0,
                consecutiveBusyCount = 0,
            )
            while (true) {
                val requestStartMs = System.currentTimeMillis()
                runCatching {
                    _uiState.value = _uiState.value.copy(
                        loading = true,
                        statusText = "持续分析中...",
                        catStatus = RealtimeCallCatStatus.UNKNOWN,
                        catHint = "正在识别猫目标...",
                    )
                    val frameUri = captureFrame()
                    val prompt = buildPromptWithMemory(
                        currentInput = inputTextProvider().ifBlank { "实时通话观察当前帧，请返回猫咪状态。" },
                    )
                    repository.analyzeRealtimeFrame(
                        contentResolver = contentResolver,
                        uri = frameUri,
                        inputText = prompt,
                        sceneHint = sceneHint,
                        sessionId = _uiState.value.sessionId,
                        jpegQuality = _uiState.value.uploadQuality,
                        maxEdge = _uiState.value.uploadMaxEdge,
                    )
                }.onSuccess { response ->
                    val modelBusy = response.urgent_flags.contains("model_service_unavailable")
                    val noCat = response.urgent_flags.contains("no_cat_detected") ||
                        response.emotion_assessment.primary == "no_cat"
                    val current = _uiState.value
                    val requestLatency = System.currentTimeMillis() - requestStartMs
                    val boxConfidence = response.cat_target_box?.confidence ?: 0.0
                    val evidenceRich = response.evidence.visual.isNotEmpty()
                    val confidentEmotion = response.emotion_assessment.confidence >= 0.55
                    val detectionStable = !noCat && (boxConfidence >= 0.42 || confidentEmotion || evidenceRich)
                    val nextNoCatCount = if (noCat) current.consecutiveNoCatCount + 1 else 0
                    val nextDetectedCount = if (detectionStable) current.consecutiveDetectedCount + 1 else 0
                    val nextBusyCount = if (modelBusy) current.consecutiveBusyCount + 1 else 0
                    val nextInterval = when {
                        modelBusy -> minOf(
                            maxOf(current.activeIntervalMillis + 400L, current.targetIntervalMillis + 200L),
                            3200L,
                        )
                        noCat -> minOf(
                            maxOf(current.activeIntervalMillis + 250L, current.targetIntervalMillis + 200L),
                            2600L,
                        )
                        !detectionStable -> minOf(
                            maxOf(current.activeIntervalMillis + 150L, current.targetIntervalMillis),
                            2200L,
                        )
                        current.activeIntervalMillis > current.targetIntervalMillis ->
                            maxOf(
                                current.targetIntervalMillis,
                                current.activeIntervalMillis - if (current.consecutiveNoCatCount >= 2) 300L else 200L,
                            )
                        else -> current.activeIntervalMillis
                    }
                    val recoveredQuality = if (modelBusy) {
                        maxOf(55, current.uploadQuality - 10)
                    } else if (noCat) {
                        minOf(90, current.uploadQuality + 2)
                    } else if (!detectionStable) {
                        minOf(92, current.uploadQuality + 3)
                    } else {
                        minOf(85, current.uploadQuality + 2)
                    }
                    val recoveredMaxEdge = if (modelBusy) {
                        maxOf(720, current.uploadMaxEdge - 120)
                    } else if (noCat) {
                        minOf(1440, current.uploadMaxEdge + 80)
                    } else if (!detectionStable) {
                        minOf(1440, current.uploadMaxEdge + 100)
                    } else {
                        minOf(1280, current.uploadMaxEdge + 60)
                    }
                    val nextAvgLatency = if (current.successCount <= 0) {
                        requestLatency
                    } else {
                        ((current.avgLatencyMs * 0.7) + (requestLatency * 0.3)).roundToInt().toLong()
                    }
                    val shouldAdoptResponse = when {
                        modelBusy -> true
                        noCat -> true
                        current.consecutiveNoCatCount >= 2 && nextDetectedCount < 2 -> false
                        !detectionStable && current.latestResponse != null -> false
                        else -> true
                    }
                    val displayResponse = if (shouldAdoptResponse) response else (current.latestResponse ?: response)
                    appendModelSummary(response.summary)
                    _uiState.value = _uiState.value.copy(
                        loading = false,
                        latestResponse = displayResponse,
                        sessionId = response.session_id,
                        activeIntervalMillis = nextInterval,
                        uploadQuality = recoveredQuality,
                        uploadMaxEdge = recoveredMaxEdge,
                        successCount = current.successCount + 1,
                        avgLatencyMs = nextAvgLatency,
                        dialogueMemory = dialogueMemorySnapshot(),
                        modelBusyCount = if (modelBusy) current.modelBusyCount + 1 else current.modelBusyCount,
                        consecutiveBusyCount = nextBusyCount,
                        catStatus = when {
                            noCat -> RealtimeCallCatStatus.NOT_DETECTED
                            nextDetectedCount >= 2 -> RealtimeCallCatStatus.DETECTED
                            else -> RealtimeCallCatStatus.UNKNOWN
                        },
                        consecutiveNoCatCount = nextNoCatCount,
                        consecutiveDetectedCount = nextDetectedCount,
                        catHint = when {
                            noCat && nextNoCatCount >= 3 -> "连续${nextNoCatCount}帧未检测到猫，请靠近并稳定镜头"
                            noCat -> "未检测到猫，请把镜头对准猫咪"
                            !detectionStable && current.consecutiveNoCatCount >= 2 ->
                                "疑似检测到猫，正在二次确认中..."
                            response.cat_target_box != null && boxConfidence < 0.45 ->
                                "已检测到猫，但定位不够稳，请保持光线和距离"
                            nextDetectedCount >= 2 -> "已检测到猫，目标定位稳定"
                            response.cat_target_box != null -> "已检测到猫，正在确认稳定性"
                            else -> "已检测到猫，模型未返回坐标，使用参考框"
                        },
                        statusText = if (modelBusy) {
                            "模型繁忙，已降频到 ${nextInterval / 1000.0}s"
                        } else if (noCat && nextNoCatCount >= 3) {
                            "连续未检测到猫，已自动放慢节奏到 ${nextInterval / 1000.0}s"
                        } else if (!detectionStable && current.consecutiveNoCatCount >= 2) {
                            "检测信号较弱，正在二次确认后更新结果"
                        } else if (!noCat && current.consecutiveNoCatCount >= 2) {
                            "已重新锁定猫咪，恢复实时通话"
                        } else {
                            "最新判断已更新"
                        },
                        error = null,
                    )
                }.onFailure { throwable ->
                    if (throwable is CancellationException) throw throwable
                    val current = _uiState.value
                    val requestLatency = System.currentTimeMillis() - requestStartMs
                    val nextInterval = minOf(current.activeIntervalMillis + 400L, 2800L)
                    _uiState.value = _uiState.value.copy(
                        loading = false,
                        activeIntervalMillis = nextInterval,
                        uploadQuality = maxOf(50, current.uploadQuality - 10),
                        uploadMaxEdge = maxOf(720, current.uploadMaxEdge - 120),
                        failureCount = current.failureCount + 1,
                        avgLatencyMs = if (current.avgLatencyMs == 0L) requestLatency else current.avgLatencyMs,
                        dialogueMemory = dialogueMemorySnapshot(),
                        statusText = "实时通话失败，${nextInterval / 1000.0}s 后重试...",
                        error = throwable.toUserFacingApiMessage("实时通话失败"),
                    )
                }
                delay(_uiState.value.activeIntervalMillis)
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        dialogueRequestJob?.cancel()
        dialogueRequestJob = null
        pendingAssistantStreamJob?.cancel()
        pendingAssistantStreamJob = null
        pendingDialogueQuestions.clear()
        _uiState.value = _uiState.value.copy(
            running = false,
            loading = false,
            dialogueLoading = false,
            queuedDialogueCount = 0,
            statusText = "实时通话已停止",
            catStatus = RealtimeCallCatStatus.UNKNOWN,
            catHint = "实时通话已停止",
            consecutiveNoCatCount = 0,
            consecutiveDetectedCount = 0,
            consecutiveBusyCount = 0,
            dialogueMemory = dialogueMemorySnapshot(),
        )
    }

    fun setIntervalMillis(intervalMillis: Long) {
        _uiState.value = _uiState.value.copy(
            modePreset = RealtimeCallModePreset.CUSTOM,
            targetIntervalMillis = intervalMillis,
            activeIntervalMillis = minOf(_uiState.value.activeIntervalMillis, intervalMillis),
        )
    }

    fun setNetworkProfile(profile: String) {
        val normalized = profile.trim().lowercase()
        _uiState.value = when (normalized) {
            "high" -> _uiState.value.copy(
                modePreset = RealtimeCallModePreset.CUSTOM,
                uploadQuality = 88,
                uploadMaxEdge = 1440,
            )
            "low" -> _uiState.value.copy(
                modePreset = RealtimeCallModePreset.CUSTOM,
                uploadQuality = 60,
                uploadMaxEdge = 900,
            )
            else -> _uiState.value.copy(
                modePreset = RealtimeCallModePreset.CUSTOM,
                uploadQuality = 75,
                uploadMaxEdge = 1080,
            )
        }
    }

    fun applyModePreset(preset: RealtimeCallModePreset) {
        _uiState.value = when (preset) {
            RealtimeCallModePreset.CUSTOM -> _uiState.value.copy(modePreset = preset)
            RealtimeCallModePreset.RESPONSIVE -> _uiState.value.copy(
                modePreset = preset,
                targetIntervalMillis = 800L,
                activeIntervalMillis = minOf(_uiState.value.activeIntervalMillis, 800L),
                uploadQuality = 84,
                uploadMaxEdge = 1280,
                interruptPriorityMode = true,
            )
            RealtimeCallModePreset.BALANCED -> _uiState.value.copy(
                modePreset = preset,
                targetIntervalMillis = 1000L,
                activeIntervalMillis = minOf(_uiState.value.activeIntervalMillis, 1000L),
                uploadQuality = 75,
                uploadMaxEdge = 1080,
                interruptPriorityMode = true,
            )
            RealtimeCallModePreset.STABLE -> _uiState.value.copy(
                modePreset = preset,
                targetIntervalMillis = 1500L,
                activeIntervalMillis = minOf(_uiState.value.activeIntervalMillis, 1500L),
                uploadQuality = 68,
                uploadMaxEdge = 960,
                interruptPriorityMode = false,
            )
        }
    }

    fun setAssistantTone(tone: AssistantTone) {
        _uiState.value = _uiState.value.copy(assistantTone = tone)
    }

    fun setInterruptPriorityMode(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(
            modePreset = RealtimeCallModePreset.CUSTOM,
            interruptPriorityMode = enabled,
        )
    }

    fun pushUserIntent(text: String) {
        val normalized = text.trim()
        if (normalized.isBlank()) return
        if (recentUserIntents.lastOrNull() == normalized) return
        if (recentUserIntents.size >= 4) {
            recentUserIntents.removeFirst()
        }
        recentUserIntents.addLast(normalized.take(80))
        _uiState.value = _uiState.value.copy(dialogueMemory = dialogueMemorySnapshot())
    }

    fun sendDialogueTurn(text: String) {
        val normalized = text.trim()
        if (normalized.isBlank()) return
        val now = System.currentTimeMillis()
        if (
            pendingDialogueQuestions.lastOrNull() == normalized ||
            (lastSubmittedDialogue == normalized && now - lastSubmittedDialogueAtMs < 2500L)
        ) {
            _uiState.value = _uiState.value.copy(
                statusText = "重复问题已忽略，避免挤占实时通话",
                error = null,
            )
            return
        }
        val sessionId = _uiState.value.sessionId ?: run {
            _uiState.value = _uiState.value.copy(error = "请先等待首帧分析完成，再开始对话。")
            return
        }
        lastSubmittedDialogue = normalized
        lastSubmittedDialogueAtMs = now
        appendDialogueTurn("user", normalized)
        if (_uiState.value.interruptPriorityMode && _uiState.value.dialogueLoading) {
            dialogueRequestJob?.cancel()
            dialogueRequestJob = null
            pendingAssistantStreamJob?.cancel()
            pendingAssistantStreamJob = null
            pendingDialogueQuestions.clear()
            completePendingAssistantTurn("收到，我先中断上一条，优先回答你刚刚这句。")
            _uiState.value = _uiState.value.copy(
                dialogueLoading = false,
                queuedDialogueCount = 0,
                statusText = "已中断旧回复，优先处理新问题",
            )
        }
        if (pendingDialogueQuestions.size >= 6) {
            pendingDialogueQuestions.removeFirst()
        }
        pendingDialogueQuestions.addLast(normalized)
        _uiState.value = _uiState.value.copy(
            queuedDialogueCount = pendingDialogueQuestions.size,
            statusText = if (_uiState.value.dialogueLoading) "对话排队中..." else "对话请求中...",
            error = null,
        )
        ensureDialogueQueueWorker(sessionId)
    }

    fun clearConversationMemory() {
        recentUserIntents.clear()
        recentModelSummaries.clear()
        dialogueTurns.clear()
        pendingDialogueQuestions.clear()
        dialogueRequestJob?.cancel()
        dialogueRequestJob = null
        pendingAssistantStreamJob?.cancel()
        pendingAssistantStreamJob = null
        pendingAssistantTs = null
        lastSubmittedDialogue = null
        lastSubmittedDialogueAtMs = 0L
        _uiState.value = _uiState.value.copy(
            dialogueTurns = emptyList(),
            dialogueMemory = emptyList(),
            dialogueLoading = false,
            queuedDialogueCount = 0,
            latestAssistantUtterance = "",
            statusText = if (_uiState.value.running) "已清空通话记忆，继续当前实时通话" else "已清空通话记忆",
            error = null,
        )
    }

    private fun ensureDialogueQueueWorker(sessionId: String) {
        if (dialogueRequestJob?.isActive == true) return
        dialogueRequestJob = viewModelScope.launch {
            while (pendingDialogueQuestions.isNotEmpty()) {
                val question = pendingDialogueQuestions.removeFirst()
                _uiState.value = _uiState.value.copy(
                    dialogueLoading = true,
                    queuedDialogueCount = pendingDialogueQuestions.size,
                    statusText = "对话请求中...",
                    error = null,
                )
                appendPendingAssistantTurn(buildInstantAck(question))
                pendingAssistantStreamJob?.cancel()
                pendingAssistantStreamJob = startPendingAssistantProgress(question)
                runCatching {
                    repository.followup(sessionId = sessionId, question = question)
                }.onSuccess { response ->
                    pendingAssistantStreamJob?.cancel()
                    pendingAssistantStreamJob = null
                    appendModelSummary(response.summary)
                    val assistantUtterance = buildAssistantUtterance(
                        response = response,
                        tone = _uiState.value.assistantTone,
                    )
                    streamPendingAssistantTurn(assistantUtterance)
                    _uiState.value = _uiState.value.copy(
                        latestResponse = response,
                        latestAssistantUtterance = assistantUtterance,
                        dialogueMemory = dialogueMemorySnapshot(),
                        statusText = if (pendingDialogueQuestions.isEmpty()) "模型已回复" else "已回复，继续处理中...",
                        error = null,
                    )
                }.onFailure { throwable ->
                    if (throwable is CancellationException) throw throwable
                    pendingAssistantStreamJob?.cancel()
                    pendingAssistantStreamJob = null
                    completePendingAssistantTurn("我这边网络有点忙，请你再说一次，我马上继续。")
                    _uiState.value = _uiState.value.copy(
                        statusText = "对话失败，请重试",
                        error = throwable.toUserFacingApiMessage("对话失败"),
                    )
                }
            }
            _uiState.value = _uiState.value.copy(
                dialogueLoading = false,
                queuedDialogueCount = 0,
            )
        }
    }

    private fun appendModelSummary(summary: String) {
        val normalized = summary.trim()
        if (normalized.isBlank()) return
        if (recentModelSummaries.lastOrNull() == normalized) return
        if (recentModelSummaries.size >= 4) {
            recentModelSummaries.removeFirst()
        }
        recentModelSummaries.addLast(normalized.take(80))
    }

    private fun appendDialogueTurn(role: String, text: String) {
        if (dialogueTurns.size >= 12) {
            dialogueTurns.removeFirst()
        }
        dialogueTurns.addLast(RealtimeDialogueTurn(role = role, text = text.take(120), pending = false))
        _uiState.value = _uiState.value.copy(dialogueTurns = dialogueTurns.toList())
    }

    private fun appendPendingAssistantTurn(text: String) {
        if (dialogueTurns.size >= 12) {
            dialogueTurns.removeFirst()
        }
        val pendingTurn = RealtimeDialogueTurn(
            role = "assistant",
            text = text.take(120),
            pending = true,
        )
        pendingAssistantTs = pendingTurn.ts
        dialogueTurns.addLast(pendingTurn)
        _uiState.value = _uiState.value.copy(dialogueTurns = dialogueTurns.toList())
    }

    private fun completePendingAssistantTurn(text: String) {
        val targetTs = pendingAssistantTs
        if (targetTs == null) {
            appendDialogueTurn("assistant", text)
            return
        }
        updatePendingAssistantTurn(targetTs, text.take(120), pending = false)
        pendingAssistantTs = null
    }

    private suspend fun streamPendingAssistantTurn(text: String) {
        val targetTs = pendingAssistantTs
        if (targetTs == null) {
            appendDialogueTurn("assistant", text)
            return
        }
        val normalized = text.take(120)
        if (normalized.length <= 12) {
            completePendingAssistantTurn(normalized)
            return
        }
        val chunks = splitForStreaming(normalized)
        val total = chunks.size.coerceAtLeast(1)
        var rendered = ""
        chunks.forEachIndexed { index, chunk ->
            rendered += chunk
            updatePendingAssistantTurn(targetTs, rendered, pending = index < total - 1)
            // 语义分块 + 动态节奏：前段快、结尾慢一点，读起来更像实时生成。
            val baseDelay = when {
                chunk.endsWith("。") || chunk.endsWith("！") || chunk.endsWith("？") -> 120L
                chunk.length >= 8 -> 65L
                else -> 40L
            }
            val tailSlowdown = if (index >= total * 2 / 3) 20L else 0L
            if (index < total - 1) {
                delay(baseDelay + tailSlowdown)
            }
        }
        pendingAssistantTs = null
    }

    private fun updatePendingAssistantTurn(targetTs: Long, text: String, pending: Boolean) {
        val replaced = dialogueTurns.map { turn ->
            if (turn.role == "assistant" && turn.ts == targetTs) {
                turn.copy(text = text.take(120), pending = pending)
            } else {
                turn
            }
        }
        dialogueTurns.clear()
        replaced.forEach { dialogueTurns.addLast(it) }
        _uiState.value = _uiState.value.copy(dialogueTurns = dialogueTurns.toList())
    }

    private fun startPendingAssistantProgress(question: String): Job {
        val short = question.take(16)
        val stages = listOf(
            "收到“$short”，正在理解你的问题...",
            "正在结合实时画面与最近状态...",
            "正在匹配知识库中的相似案例...",
            "正在整理可执行建议...",
        )
        return viewModelScope.launch {
            val targetTs = pendingAssistantTs ?: return@launch
            var index = 0
            while (isActive && pendingAssistantTs == targetTs) {
                updatePendingAssistantTurn(targetTs, stages[index % stages.size], pending = true)
                index += 1
                delay(760L)
            }
        }
    }

    private fun splitForStreaming(text: String): List<String> {
        // 先按中文语义标点切片，再把过长片段拆小，避免一次跳很多字。
        val sentenceChunks = mutableListOf<String>()
        val builder = StringBuilder()
        for (ch in text) {
            builder.append(ch)
            if (ch in setOf('。', '！', '？', '；', '，')) {
                sentenceChunks.add(builder.toString())
                builder.clear()
            }
        }
        if (builder.isNotEmpty()) {
            sentenceChunks.add(builder.toString())
        }
        val refined = mutableListOf<String>()
        sentenceChunks.forEach { part ->
            if (part.length <= 8) {
                refined.add(part)
            } else {
                var start = 0
                while (start < part.length) {
                    val end = (start + 6).coerceAtMost(part.length)
                    refined.add(part.substring(start, end))
                    start = end
                }
            }
        }
        return refined.filter { it.isNotBlank() }
    }

    private fun buildInstantAck(userText: String): String {
        val short = userText.take(24)
        return "收到，你刚刚说“$short”，我正在结合画面快速判断..."
    }

    private fun buildAssistantUtterance(
        response: AnalyzeResponse,
        tone: AssistantTone,
    ): String {
        val summary = response.summary.trim()
        val risk = response.health_risk_assessment.level
        val suggestion = response.care_suggestions.firstOrNull()?.trim().orEmpty()
        val followup = response.followup_questions.firstOrNull()?.trim().orEmpty()
        val prefix = when (tone) {
            AssistantTone.PROFESSIONAL -> when (risk.lowercase()) {
                "urgent" -> "本喵现在有高风险信号，"
                "high" -> "本喵现在偏中高风险，"
                "medium" -> "本喵现在先按中等风险看，"
                else -> "本喵先告诉你当前判断，"
            }
            AssistantTone.CARING -> when (risk.lowercase()) {
                "urgent" -> "本喵现在不太舒服，先别慌，"
                "high" -> "本喵现在状态不太稳，先稳住，"
                "medium" -> "本喵现在先按中等风险看，"
                else -> "本喵先给你一个当前判断，"
            }
        }
        val suggestionSentence = if (suggestion.isNotBlank()) {
            if (tone == AssistantTone.CARING) "你可以先这样帮我：$suggestion。" else "建议先这样帮我：$suggestion。"
        } else {
            ""
        }
        val followupSentence = if (followup.isNotBlank()) "还想请你帮本喵确认：$followup" else ""
        return listOf(
            "$prefix$summary。",
            suggestionSentence,
            followupSentence,
        ).filter { it.isNotBlank() }.joinToString("")
    }

    private fun dialogueMemorySnapshot(): List<String> {
        val memory = mutableListOf<String>()
        recentUserIntents.forEach { memory.add("用户：$it") }
        recentModelSummaries.forEach { memory.add("系统：$it") }
        return memory.takeLast(6)
    }

    private fun buildPromptWithMemory(currentInput: String): String {
        val memory = dialogueMemorySnapshot()
        if (memory.isEmpty()) return currentInput
        return buildString {
            appendLine(currentInput)
            appendLine("最近通话记忆：")
            memory.forEach { appendLine("- $it") }
            append("请结合连续上下文判断本帧变化。")
        }
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
