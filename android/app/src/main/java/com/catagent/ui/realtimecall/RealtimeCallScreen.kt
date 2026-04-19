package com.catagent.ui.realtimecall

import android.Manifest
import android.content.Intent
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.catagent.data.model.CatTargetBox
import com.catagent.data.model.KnowledgeSnippet
import com.catagent.ui.realtime.RealtimeResultCard
import com.catagent.ui.realtime.captureFrameUri
import java.lang.IllegalStateException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Composable
fun RealtimeCallScreen(
    contentResolver: ContentResolver,
    sceneHint: String,
    inputText: String,
    onBack: () -> Unit,
    viewModel: RealtimeCallViewModel = viewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var promptText by remember { mutableStateOf(inputText) }
    var useFrontCamera by remember { mutableStateOf(false) }
    var autoStartDone by rememberSaveable { mutableStateOf(false) }
    var isListening by remember { mutableStateOf(false) }
    var speechStatus by remember { mutableStateOf("点击麦克风开始说话") }
    var handsFreeMode by remember { mutableStateOf(true) }
    var autoSpeak by remember { mutableStateOf(true) }
    var ttsReady by remember { mutableStateOf(false) }
    var lastSpokenSummary by remember { mutableStateOf<String?>(null) }
    var lastVoiceResultAtMs by remember { mutableStateOf(0L) }
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val speechRecognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else {
            null
        }
    }
    val textToSpeech = remember {
        TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraGranted = granted
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        speechStatus = if (granted) "录音权限已授予" else "未授予录音权限"
    }
    val startSpeechListening = {
        if (speechRecognizer == null) {
            speechStatus = "当前设备不支持语音识别"
        } else if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            speechStatus = "正在请求录音权限..."
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            runCatching {
                isListening = true
                speechStatus = if (handsFreeMode) "连续听说中..." else "正在启动语音识别..."
                speechRecognizer.startListening(intent)
            }.onFailure {
                isListening = false
                speechStatus = "语音识别启动失败，请重试"
            }
        }
    }

    DisposableEffect(cameraGranted, useFrontCamera, lifecycleOwner, previewViewRef) {
        if (!cameraGranted || previewViewRef == null) {
            onDispose { }
        } else {
            val previewView = previewViewRef ?: run {
                onDispose { }
                return@DisposableEffect onDispose { }
            }
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            val executor = ContextCompat.getMainExecutor(context)
            cameraProviderFuture.addListener({
                runCatching {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val capture = ImageCapture.Builder().build()
                    val selector = if (useFrontCamera) {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    } else {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        selector,
                        preview,
                        capture,
                    )
                    imageCapture = capture
                }
            }, executor)
            onDispose {
                runCatching {
                    val cameraProvider = cameraProviderFuture.get()
                    cameraProvider.unbindAll()
                }
                imageCapture = null
            }
        }
    }

    DisposableEffect(speechRecognizer, textToSpeech) {
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                speechStatus = "正在听你说..."
            }

            override fun onBeginningOfSpeech() {
                // 连续语音输入时，用户说话应能打断播报，避免抢话。
                textToSpeech.stop()
                speechStatus = "已开始识别"
            }

            override fun onRmsChanged(rmsdB: Float) = Unit

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                isListening = false
                speechStatus = "语音结束，等待结果"
            }

            override fun onError(error: Int) {
                isListening = false
                speechStatus = "语音识别失败，可再试一次"
                if (handsFreeMode && uiState.running) {
                    startSpeechListening()
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    promptText = text
                    lastVoiceResultAtMs = System.currentTimeMillis()
                    viewModel.pushUserIntent(text)
                    viewModel.sendDialogueTurn(text)
                    speechStatus = "已更新语音描述"
                } else {
                    speechStatus = "未识别到清晰语音"
                }
                if (handsFreeMode && uiState.running) {
                    startSpeechListening()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    promptText = text
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }
        speechRecognizer?.setRecognitionListener(listener)
        textToSpeech.language = Locale.SIMPLIFIED_CHINESE
        onDispose {
            speechRecognizer?.destroy()
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            viewModel.stop()
        }
    }

    LaunchedEffect(Unit) {
        if (!cameraGranted) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(
        uiState.latestResponse?.summary,
        uiState.latestAssistantUtterance,
        autoSpeak,
        ttsReady,
        isListening,
        lastVoiceResultAtMs,
    ) {
        val summary = uiState.latestResponse?.summary ?: return@LaunchedEffect
        if (!autoSpeak || !ttsReady || summary == lastSpokenSummary) return@LaunchedEffect
        val current = uiState.latestResponse ?: return@LaunchedEffect
        val noCat = current.emotion_assessment.primary == "no_cat" || current.urgent_flags.contains("no_cat_detected")
        if (noCat && uiState.consecutiveNoCatCount >= 2) return@LaunchedEffect
        if (isListening) return@LaunchedEffect
        val cooldownMs = 1800L - (System.currentTimeMillis() - lastVoiceResultAtMs)
        if (cooldownMs > 0L) {
            delay(cooldownMs)
            if (isListening || summary == lastSpokenSummary) return@LaunchedEffect
        }
        val speechText = buildRealtimeCallSpeechText(current, uiState.latestAssistantUtterance)
        textToSpeech.speak(
            speechText,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "realtime-call-latest",
        )
        lastSpokenSummary = summary
    }

    LaunchedEffect(handsFreeMode, uiState.running) {
        if (!handsFreeMode) {
            if (isListening) {
                runCatching { speechRecognizer?.stopListening() }
                isListening = false
                speechStatus = "语音输入已关闭"
            }
            return@LaunchedEffect
        }
        if (uiState.running && !isListening) {
            startSpeechListening()
        }
    }

    LaunchedEffect(cameraGranted, imageCapture, uiState.running) {
        if (!cameraGranted || imageCapture == null || uiState.running || autoStartDone) return@LaunchedEffect
        autoStartDone = true
        viewModel.pushUserIntent(promptText)
        viewModel.start(
            contentResolver = contentResolver,
            sceneHint = sceneHint,
            inputTextProvider = { promptText },
            captureFrame = {
                val latestCapture = imageCapture ?: throw IllegalStateException("相机尚未就绪")
                captureFrameUri(context, latestCapture)
            },
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF111111))) {
        if (cameraGranted) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val previewView = PreviewView(ctx).apply {
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                        }
                        previewViewRef = previewView
                        previewView
                    },
                    update = { view ->
                        previewViewRef = view
                    },
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.34f),
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.7f),
                                ),
                            ),
                        ),
                )

                val targetBox = uiState.latestResponse?.cat_target_box
                val fallbackBox = CatTargetBox(
                    x = 0.2,
                    y = 0.15,
                    width = 0.6,
                    height = 0.7,
                    confidence = 0.35,
                )
                val overlayBox = when {
                    uiState.catStatus != RealtimeCallCatStatus.DETECTED -> null
                    targetBox != null -> targetBox
                    else -> fallbackBox
                }
                if (overlayBox != null) {
                    RealtimeCallTargetOverlay(
                        box = overlayBox,
                        isFallback = targetBox == null,
                        frameWidth = maxWidth,
                        frameHeight = maxHeight,
                        modifier = Modifier.align(Alignment.TopStart),
                    )
                }
            }
        } else {
            Card(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White.copy(alpha = 0.12f),
                    contentColor = Color.White,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("实时通话需要相机权限", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("授权相机")
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Button(onClick = onBack) {
                        Text("返回")
                    }
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color.White.copy(alpha = 0.1f),
                            contentColor = Color.White,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text("通话模式", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
                            Text(
                                when (uiState.modePreset) {
                                    RealtimeCallModePreset.RESPONSIVE -> "高响应"
                                    RealtimeCallModePreset.BALANCED -> "均衡"
                                    RealtimeCallModePreset.STABLE -> "稳妥"
                                    RealtimeCallModePreset.CUSTOM -> "自定义"
                                },
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(uiState.catHint, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .padding(top = 72.dp)
                        .width(136.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CallDebugStatCard(
                        title = "AI Confidence",
                        value = "${((uiState.latestResponse?.emotion_assessment?.confidence ?: 0.0) * 100).toInt()}%",
                    )
                    CallDebugStatCard(
                        title = "Stress Level",
                        value = uiState.latestResponse?.health_risk_assessment?.level?.uppercase() ?: "WAIT",
                    )
                    CallDebugStatCard(
                        title = "Latency",
                        value = "${uiState.avgLatencyMs} ms",
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (uiState.dialogueTurns.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.Transparent,
                            contentColor = Color.White,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            uiState.dialogueTurns.takeLast(4).forEach { turn ->
                                RealtimeDialogueBubble(turn)
                            }
                            if (uiState.dialogueLoading) {
                                Text(
                                    "模型思考中...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.72f),
                                    modifier = Modifier.padding(start = 40.dp),
                                )
                            }
                        }
                    }
                }

                uiState.latestResponse?.takeIf {
                    val level = it.health_risk_assessment.level.lowercase()
                    level == "high" || level == "urgent" || it.urgent_flags.isNotEmpty()
                }?.let { response ->
                    RealtimeCallRiskBanner(
                        title = if (response.health_risk_assessment.level.lowercase() == "urgent") "紧急提醒" else "风险提醒",
                        reason = response.health_risk_assessment.reason,
                        suggestion = response.care_suggestions.firstOrNull().orEmpty(),
                    )
                }

                uiState.latestResponse?.takeIf {
                    it.emotion_assessment.primary != "no_cat" && !it.urgent_flags.contains("no_cat_detected")
                }?.retrieved_knowledge?.takeIf { it.isNotEmpty() }?.let { knowledge ->
                    val rankedKnowledge = rankKnowledgeByRisk(knowledge, uiState.latestResponse)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.Black.copy(alpha = 0.42f),
                            contentColor = Color.White,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("当前知识依据", style = MaterialTheme.typography.titleSmall, color = Color(0xFFFFB067))
                            rankedKnowledge.take(2).forEach { item ->
                                RealtimeCallKnowledgeItem(
                                    title = item.title,
                                    sourceType = item.source_type,
                                    summary = item.content,
                                    score = item.score,
                                    riskRelation = knowledgeRiskRelation(item, uiState.latestResponse),
                                    possibleCauses = item.possible_causes,
                                    careAdvice = item.care_advice,
                                )
                            }
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Black.copy(alpha = 0.48f),
                        contentColor = Color.White,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedTextField(
                            value = promptText,
                            onValueChange = { promptText = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("实时通话描述") },
                            minLines = 2,
                        )
                        Text(speechStatus, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
                        if (uiState.error != null) {
                            Text(uiState.error ?: "", style = MaterialTheme.typography.bodySmall, color = Color(0xFFFCA5A5))
                        }
                        val quickFollowups = uiState.latestResponse?.followup_questions.orEmpty().take(2)
                        if (quickFollowups.isNotEmpty()) {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                quickFollowups.forEach { question ->
                                    AssistChip(
                                        onClick = {
                                            promptText = question
                                            viewModel.pushUserIntent(question)
                                            viewModel.sendDialogueTurn(question)
                                        },
                                        label = { Text(question) },
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = {
                                    val text = promptText.trim()
                                    if (text.isNotBlank()) {
                                        viewModel.pushUserIntent(text)
                                        viewModel.sendDialogueTurn(text)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(if (uiState.dialogueLoading) "排队发送(${uiState.queuedDialogueCount})" else "发送")
                            }
                            Button(
                                onClick = { viewModel.clearConversationMemory() },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("清空记忆")
                            }
                        }
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AssistChip(onClick = { viewModel.applyModePreset(RealtimeCallModePreset.RESPONSIVE) }, label = { Text(if (uiState.modePreset == RealtimeCallModePreset.RESPONSIVE) "高响应✓" else "高响应") })
                            AssistChip(onClick = { viewModel.applyModePreset(RealtimeCallModePreset.BALANCED) }, label = { Text(if (uiState.modePreset == RealtimeCallModePreset.BALANCED) "均衡✓" else "均衡") })
                            AssistChip(onClick = { viewModel.applyModePreset(RealtimeCallModePreset.STABLE) }, label = { Text(if (uiState.modePreset == RealtimeCallModePreset.STABLE) "稳妥✓" else "稳妥") })
                            AssistChip(onClick = { autoSpeak = !autoSpeak }, label = { Text(if (autoSpeak) "播报开" else "播报关") })
                            AssistChip(
                                onClick = {
                                    val speechText = uiState.latestAssistantUtterance.ifBlank {
                                        uiState.latestResponse?.let {
                                            buildRealtimeCallSpeechText(it, "")
                                        } ?: ""
                                    }
                                    if (ttsReady && speechText.isNotBlank()) {
                                        textToSpeech.speak(
                                            speechText,
                                            TextToSpeech.QUEUE_FLUSH,
                                            null,
                                            "realtime-call-manual",
                                        )
                                        lastSpokenSummary = uiState.latestResponse?.summary
                                    }
                                },
                                label = { Text("朗读") },
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
                            if (isListening) {
                                runCatching { speechRecognizer?.stopListening() }
                                isListening = false
                                speechStatus = "语音输入已暂停"
                            } else {
                                startSpeechListening()
                            }
                        },
                        modifier = Modifier.size(56.dp),
                    ) {
                        Text(if (isListening) "停" else "麦")
                    }
                    Button(
                        onClick = {
                            if (uiState.running) {
                                viewModel.stop()
                                onBack()
                            } else {
                                viewModel.pushUserIntent(promptText)
                                viewModel.start(
                                    contentResolver = contentResolver,
                                    sceneHint = sceneHint,
                                    inputTextProvider = { promptText },
                                    captureFrame = {
                                        val latestCapture = imageCapture ?: throw IllegalStateException("相机尚未就绪")
                                        captureFrameUri(context, latestCapture)
                                    },
                                )
                            }
                        },
                        modifier = Modifier.size(74.dp),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                    ) {
                        Text(if (uiState.running) "挂断" else "开始")
                    }
                    Button(
                        onClick = { useFrontCamera = !useFrontCamera },
                        modifier = Modifier.size(56.dp),
                    ) {
                        Text(if (useFrontCamera) "前" else "后")
                    }
                }
            }
        }
    }
}

@Composable
private fun CallDebugStatCard(
    title: String,
    value: String,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color.Black.copy(alpha = 0.42f),
            contentColor = Color.White,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.65f))
            Text(value, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun RealtimeCallKnowledgeItem(
    title: String,
    sourceType: String,
    summary: String,
    score: Double,
    riskRelation: String,
    possibleCauses: List<String>,
    careAdvice: List<String>,
) {
    var expanded by remember(title, summary) { mutableStateOf(false) }
    val sourceLabel = when (sourceType.lowercase()) {
        "visual" -> "相似示例"
        "hybrid" -> "图文混合"
        else -> "规则知识"
    }
    Column(
        modifier = Modifier.clickable { expanded = !expanded },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.14f),
            ) {
                Text(
                    sourceLabel,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                )
            }
            Text(title, style = MaterialTheme.typography.bodyMedium, color = Color.White)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                color = Color(0xFF2563EB).copy(alpha = 0.36f),
            ) {
                Text(
                    "命中 ${(score * 100).toInt()}%",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                )
            }
            Surface(
                shape = CircleShape,
                color = Color(0xFFB45309).copy(alpha = 0.36f),
            ) {
                Text(
                    riskRelation,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                )
            }
        }
        Text(summary, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.76f))
        Text(
            if (expanded) "收起详情" else "展开详情",
            color = Color(0xFFFFD6A5),
            style = MaterialTheme.typography.bodySmall,
        )
        if (expanded) {
            possibleCauses.takeIf { it.isNotEmpty() }?.let {
                Text(
                    "可能原因：${it.take(3).joinToString("、")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
            careAdvice.takeIf { it.isNotEmpty() }?.let {
                Text(
                    "照护建议：${it.take(2).joinToString("；")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
        }
    }
}

private fun knowledgeRiskRelation(
    item: KnowledgeSnippet,
    response: com.catagent.data.model.AnalyzeResponse?,
): String {
    if (response == null) return "风险关联:未知"
    val level = response.health_risk_assessment.level.lowercase()
    val responseCorpus = buildString {
        append(response.health_risk_assessment.reason)
        append(" ")
        append(response.health_risk_assessment.triggers.joinToString(" "))
    }
    val itemCorpus = "${item.title} ${item.content} ${item.possible_causes.joinToString(" ")}"
    val overlap = response.health_risk_assessment.triggers.count { trigger ->
        trigger.isNotBlank() && itemCorpus.contains(trigger, ignoreCase = true)
    }
    return when {
        level == "urgent" || overlap >= 2 -> "风险关联:高"
        (responseCorpus.isNotBlank() && itemCorpus.contains(responseCorpus.take(12), ignoreCase = true)) || overlap == 1 -> "风险关联:中"
        else -> "风险关联:弱"
    }
}

private fun riskRank(label: String): Int = when (label) {
    "风险关联:高" -> 0
    "风险关联:中" -> 1
    else -> 2
}

private fun rankKnowledgeByRisk(
    items: List<KnowledgeSnippet>,
    response: com.catagent.data.model.AnalyzeResponse?,
): List<KnowledgeSnippet> {
    return items.sortedWith(
        compareBy<KnowledgeSnippet> { riskRank(knowledgeRiskRelation(it, response)) }
            .thenByDescending { it.score }
    )
}

private fun buildRealtimeCallSpeechText(
    response: com.catagent.data.model.AnalyzeResponse,
    assistantUtterance: String,
): String {
    val level = response.health_risk_assessment.level.lowercase()
    if (level == "urgent" || level == "high") {
        val suggestion = response.care_suggestions.firstOrNull().orEmpty()
        val knowledge = response.retrieved_knowledge.firstOrNull()?.title.orEmpty()
        val prefix = if (level == "urgent") "先重点关注本喵，现在是紧急风险" else "先重点关注本喵，现在风险偏高"
        return listOf(
            prefix,
            response.health_risk_assessment.reason,
            suggestion.takeIf { it.isNotBlank() }?.let { "请优先这样帮我，$it" } ?: "",
            knowledge.takeIf { it.isNotBlank() }?.let { "这次我参考了$it" } ?: "",
        ).filter { it.isNotBlank() }.joinToString("，")
    }
    val guidance = listOf(
        response.summary,
        response.health_risk_assessment.reason.takeIf { it.isNotBlank() },
        response.retrieved_knowledge.firstOrNull()?.let { "这次我参考了${it.title}" },
        response.care_suggestions.firstOrNull()?.let { "你可以先这样帮我，$it" },
    ).filterNotNull().joinToString("，")
    return assistantUtterance.takeIf { it.isNotBlank() }?.let {
        "$it。本喵再补充一下，$guidance"
    } ?: "本喵现在的判断是，$guidance"
}

@Composable
private fun RealtimeCallRiskBanner(
    title: String,
    reason: String,
    suggestion: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF7F1D1D).copy(alpha = 0.82f),
            contentColor = Color.White,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Color(0xFFFECACA))
            Text(reason, style = MaterialTheme.typography.bodySmall)
            if (suggestion.isNotBlank()) {
                Text("建议：$suggestion", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.84f))
            }
        }
    }
}

@Composable
private fun RealtimeDialogueBubble(
    turn: RealtimeDialogueTurn,
) {
    val isUser = turn.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) Color.White.copy(alpha = 0.18f) else Color(0xFFF59E0B).copy(alpha = 0.9f),
                contentColor = Color.White,
            ),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (!isUser) {
                    Text("喵语翻译", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.72f))
                }
                Text(
                    if (turn.pending) "${turn.text}（草稿中）" else turn.text,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun RealtimeCallPipelineStatus(
    listening: Boolean,
    frameAnalyzing: Boolean,
    dialogueRequesting: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PipelineStatusDot(
                label = "语音监听",
                active = listening,
            )
            PipelineStatusDot(
                label = "图像分析",
                active = frameAnalyzing,
            )
            PipelineStatusDot(
                label = "对话请求",
                active = dialogueRequesting,
            )
        }
    }
}

@Composable
private fun PipelineStatusDot(
    label: String,
    active: Boolean,
) {
    val color = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color = color, shape = CircleShape),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RealtimeCallStatusBanner(
    catStatus: RealtimeCallCatStatus,
    catHint: String,
    status: String,
) {
    val (title, colors) = when (catStatus) {
        RealtimeCallCatStatus.DETECTED -> {
            "已检测到猫" to CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        RealtimeCallCatStatus.NOT_DETECTED -> {
            "未检测到猫" to CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        RealtimeCallCatStatus.UNKNOWN -> {
            "识别中" to CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = colors,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(catHint, style = MaterialTheme.typography.bodySmall)
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RealtimeCallTargetOverlay(
    box: CatTargetBox,
    isFallback: Boolean,
    frameWidth: androidx.compose.ui.unit.Dp,
    frameHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val left = frameWidth * box.x.toFloat().coerceIn(0f, 1f)
    val top = frameHeight * box.y.toFloat().coerceIn(0f, 1f)
    val width = (frameWidth * box.width.toFloat().coerceIn(0.05f, 1f)).coerceAtLeast(48.dp)
    val height = (frameHeight * box.height.toFloat().coerceIn(0.05f, 1f)).coerceAtLeast(48.dp)
    Box(
        modifier = modifier
            .offset(x = left, y = top)
            .width(width)
            .height(height)
            .border(
                width = if (isFallback) 1.dp else 2.dp,
                color = if (isFallback) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
            )
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
    ) {
        Text(
            text = if (isFallback) "猫（参考框）" else "猫 ${(box.confidence * 100).toInt()}%",
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .align(Alignment.TopStart)
                .background(if (isFallback) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
