package com.catagent.ui.capture

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.catagent.data.model.AnalyzeResponse
import com.catagent.data.network.CatAgentRepository
import com.catagent.data.network.toUserFacingApiMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CaptureUiState(
    val inputText: String = "",
    val sceneHint: String = "general",
    val selectedUri: Uri? = null,
    val mediaLabel: String = "未选择媒体",
    val backendStatus: String = "后端状态检查中...",
    val mediaType: String = "image",
    val loading: Boolean = false,
    val sessionId: String? = null,
    val result: AnalyzeResponse? = null,
    val error: String? = null,
)

class CaptureViewModel(
    private val repository: CatAgentRepository = CatAgentRepository(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()
    private var submitJob: Job? = null
    private var submitSeq: Long = 0

    fun updateText(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text)
    }

    fun updateSceneHint(sceneHint: String) {
        _uiState.value = _uiState.value.copy(sceneHint = sceneHint)
    }

    fun selectMedia(uri: Uri, mediaType: String, mediaLabel: String) {
        _uiState.value = _uiState.value.copy(
            selectedUri = uri,
            mediaType = mediaType,
            mediaLabel = mediaLabel,
            error = null,
        )
    }

    fun checkBackendStatus() {
        viewModelScope.launch {
            runCatching {
                repository.health()
            }.onSuccess {
                _uiState.value = _uiState.value.copy(backendStatus = "后端已连接")
            }.onFailure {
                _uiState.value = _uiState.value.copy(backendStatus = "后端连接失败，请检查电脑服务和局域网")
            }
        }
    }

    fun submit(contentResolver: ContentResolver) {
        val current = _uiState.value
        val uri = current.selectedUri ?: run {
            _uiState.value = current.copy(error = "请先选择图片或短视频。")
            return
        }
        if (current.inputText.isBlank()) {
            _uiState.value = current.copy(error = "请补充一句文字或语音转写描述。")
            return
        }

        submitJob?.cancel()
        val requestSeq = ++submitSeq
        submitJob = viewModelScope.launch {
            // 新请求开始时先清空旧结果，避免“上一次结论停留过久”。
            _uiState.value = current.copy(
                loading = true,
                error = null,
                result = null,
            )
            runCatching {
                repository.analyze(
                    contentResolver = contentResolver,
                    uri = uri,
                    mediaType = current.mediaType,
                    inputText = current.inputText,
                    sceneHint = current.sceneHint,
                    sessionId = current.sessionId,
                )
            }.onSuccess { response ->
                if (requestSeq != submitSeq) {
                    return@onSuccess
                }
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    result = response,
                    sessionId = response.session_id,
                )
            }.onFailure { throwable ->
                if (throwable is CancellationException || requestSeq != submitSeq) {
                    return@onFailure
                }
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = throwable.toUserFacingApiMessage("提交失败"),
                )
            }
        }
    }
}
