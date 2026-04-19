package com.catagent.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.catagent.data.model.AnalyzeResponse
import com.catagent.data.network.CatAgentRepository
import com.catagent.data.network.toUserFacingApiMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChatUiState(
    val sessionId: String? = null,
    val question: String = "",
    val latestResponse: AnalyzeResponse? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

class ChatViewModel(
    private val repository: CatAgentRepository = CatAgentRepository(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    fun bindSession(sessionId: String?, response: AnalyzeResponse?) {
        _uiState.value = _uiState.value.copy(sessionId = sessionId, latestResponse = response)
    }

    fun updateQuestion(question: String) {
        _uiState.value = _uiState.value.copy(question = question)
    }

    fun sendFollowup() {
        val current = _uiState.value
        val sessionId = current.sessionId ?: run {
            _uiState.value = current.copy(error = "请先完成首次分析。")
            return
        }
        if (current.question.isBlank()) {
            _uiState.value = current.copy(error = "请输入追问内容。")
            return
        }

        viewModelScope.launch {
            _uiState.value = current.copy(loading = true, error = null)
            runCatching {
                repository.followup(sessionId = sessionId, question = current.question)
            }.onSuccess { response ->
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    latestResponse = response,
                    question = "",
                )
            }.onFailure { throwable ->
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = throwable.toUserFacingApiMessage("追问失败"),
                )
            }
        }
    }
}
