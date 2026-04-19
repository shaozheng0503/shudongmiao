package com.catagent.ui.realtime

import android.Manifest
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.catagent.data.model.AnalyzeResponse
import com.catagent.data.model.CatTargetBox
import com.catagent.data.model.KnowledgeSnippet
import com.catagent.ui.capture.MediaCaptureFileStore
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.IllegalStateException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Composable
fun RealtimeScreen(
    contentResolver: ContentResolver,
    sceneHint: String,
    inputText: String,
    onBack: () -> Unit,
    realtimeViewModel: RealtimeViewModel = viewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by realtimeViewModel.uiState.collectAsState()
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var useFrontCamera by remember { mutableStateOf(false) }
    var promptText by remember { mutableStateOf(inputText) }
    var isListening by remember { mutableStateOf(false) }
    var speechStatus by remember { mutableStateOf("按住说话可补充实时描述") }
    var autoSpeak by remember { mutableStateOf(true) }
    var ttsReady by remember { mutableStateOf(false) }
    var lastSpokenSummary by remember { mutableStateOf<String?>(null) }
    var autoStartDone by rememberSaveable { mutableStateOf(false) }
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        speechStatus = if (granted) "录音权限已授予，请重新按住说话" else "未授予录音权限"
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraGranted = granted
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

    DisposableEffect(speechRecognizer, textToSpeech) {
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                speechStatus = "正在听你说..."
            }

            override fun onBeginningOfSpeech() {
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
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    promptText = text
                    speechStatus = "已更新语音描述"
                } else {
                    speechStatus = "未识别到清晰语音"
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
            realtimeViewModel.stop()
            speechRecognizer?.destroy()
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
    }

    LaunchedEffect(uiState.latestResponse?.summary, autoSpeak, ttsReady, uiState.consecutiveNoCatCount) {
        val response = uiState.latestResponse ?: return@LaunchedEffect
        val summary = response.summary
        if (!autoSpeak || !ttsReady || summary == lastSpokenSummary) return@LaunchedEffect
        if (response.emotion_assessment.primary == "no_cat" || response.urgent_flags.contains("no_cat_detected")) {
            if (uiState.consecutiveNoCatCount >= 2) return@LaunchedEffect
        }
        val speechText = buildRealtimeSpeechText(response)
        textToSpeech.speak(
            speechText,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "latest-judgment",
        )
        lastSpokenSummary = summary
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
                }.onFailure {
                    // 部分机型前摄绑定可能失败，自动回退后摄避免黑屏卡死。
                    if (useFrontCamera) {
                        runCatching {
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }
                            val capture = ImageCapture.Builder().build()
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                capture,
                            )
                            imageCapture = capture
                            useFrontCamera = false
                        }
                    }
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

    LaunchedEffect(Unit) {
        if (!cameraGranted) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(cameraGranted, imageCapture, uiState.running) {
        if (imageCapture == null) return@LaunchedEffect
        if (!cameraGranted || uiState.running || autoStartDone) return@LaunchedEffect
        autoStartDone = true
        realtimeViewModel.start(
            contentResolver = contentResolver,
            sceneHint = sceneHint,
            inputTextProvider = { promptText },
            captureFrame = {
                val latestCapture = imageCapture
                    ?: throw IllegalStateException("相机尚未就绪，请稍后重试")
                captureFrameUri(context, latestCapture)
            },
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
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
                    update = { previewView ->
                        previewViewRef = previewView
                    },
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.38f),
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.62f),
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
                    uiState.catTargetStatus != CatTargetStatus.DETECTED -> null
                    targetBox != null -> targetBox
                    else -> fallbackBox
                }
                if (overlayBox != null) {
                    CatTargetOverlay(
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
                    Text("实时预览需要相机权限", style = MaterialTheme.typography.titleMedium)
                    Text("请先允许访问相机后再开始实时观察。", style = MaterialTheme.typography.bodySmall)
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
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = onBack) {
                        Text("返回")
                    }
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color.Black.copy(alpha = 0.36f),
                            contentColor = Color.White,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(Color.Red, CircleShape),
                            )
                            Text(
                                "LIVE · ${(uiState.intervalMillis / 1000.0)}s",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Button(onClick = { useFrontCamera = !useFrontCamera }) {
                        Text(if (useFrontCamera) "前摄" else "后摄")
                    }
                }

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LiveTag("目标", if (uiState.catTargetStatus == CatTargetStatus.DETECTED) "已锁定猫咪" else "等待识别")
                    LiveTag("状态", uiState.latestResponse?.emotion_assessment?.primary ?: "分析中")
                    LiveTag(
                        "置信度",
                        "${((uiState.latestResponse?.emotion_assessment?.confidence ?: 0.0) * 100).toInt()}%",
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.White.copy(alpha = 0.14f),
                        contentColor = Color.White,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("AI 行为实时解析", color = Color(0xFFFFB067), style = MaterialTheme.typography.titleSmall)
                            Text(speechStatus, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
                        }
                        Text(
                            uiState.latestResponse?.summary
                                ?: if (uiState.loading) "正在持续分析当前画面..." else "实时分析尚未开始，系统会在相机就绪后自动启动。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                        )
                        uiState.latestResponse?.evidence?.visual?.firstOrNull()?.let { evidence ->
                            Text("视觉线索：$evidence", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.82f))
                        }
                        Text(uiState.statusText, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.82f))
                        uiState.error?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFCA5A5))
                        }
                    }
                }

                uiState.latestResponse?.takeIf {
                    val level = it.health_risk_assessment.level.lowercase()
                    level == "high" || level == "urgent" || it.urgent_flags.isNotEmpty()
                }?.let { response ->
                    RealtimeRiskBanner(
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
                            containerColor = Color.White.copy(alpha = 0.12f),
                            contentColor = Color.White,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("当前知识依据", style = MaterialTheme.typography.titleSmall, color = Color(0xFFFFB067))
                            rankedKnowledge.take(2).forEach { item ->
                                RealtimeKnowledgeItem(
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
                        containerColor = Color.White.copy(alpha = 0.12f),
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
                            label = { Text("实时描述（可手输或按住说话）") },
                            minLines = 2,
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AssistChip(onClick = { realtimeViewModel.setIntervalMillis(1500L) }, label = { Text("1.5秒") })
                            AssistChip(onClick = { realtimeViewModel.setIntervalMillis(2500L) }, label = { Text("2.5秒") })
                            AssistChip(onClick = { realtimeViewModel.setIntervalMillis(4000L) }, label = { Text("4秒") })
                            AssistChip(onClick = { autoSpeak = !autoSpeak }, label = { Text(if (autoSpeak) "播报开" else "播报关") })
                        }
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onPress = {
                                            if (speechRecognizer == null) {
                                                speechStatus = "当前设备不支持语音识别"
                                                return@detectTapGestures
                                            }
                                            if (ContextCompat.checkSelfPermission(
                                                    context,
                                                    android.Manifest.permission.RECORD_AUDIO,
                                                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                                            ) {
                                                speechStatus = "正在请求录音权限..."
                                                audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                                return@detectTapGestures
                                            }
                                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                                                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                                            }
                                            isListening = true
                                            speechStatus = "正在启动语音识别..."
                                            speechRecognizer.startListening(intent)
                                            tryAwaitRelease()
                                            if (isListening) {
                                                speechRecognizer.stopListening()
                                                isListening = false
                                            }
                                        },
                                    )
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = Color.Black.copy(alpha = 0.24f),
                                contentColor = Color.White,
                            ),
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(if (isListening) "松开结束说话" else "按住说话")
                                Text("转写结果会自动作为实时观察描述使用。", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Button(
                                onClick = {
                                    realtimeViewModel.captureOnce(
                                        contentResolver = contentResolver,
                                        sceneHint = sceneHint,
                                        inputTextProvider = { promptText },
                                        captureFrame = {
                                            val latestCapture = imageCapture
                                                ?: throw IllegalStateException("相机尚未就绪，请稍后重试")
                                            captureFrameUri(context, latestCapture)
                                        },
                                    )
                                },
                                enabled = imageCapture != null && !uiState.loading,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("抓拍")
                            }
                            Button(
                                onClick = {
                                    if (!uiState.running) {
                                        realtimeViewModel.start(
                                            contentResolver = contentResolver,
                                            sceneHint = sceneHint,
                                            inputTextProvider = { promptText },
                                            captureFrame = {
                                                val latestCapture = imageCapture
                                                    ?: throw IllegalStateException("相机尚未就绪，请稍后重试")
                                                captureFrameUri(context, latestCapture)
                                            },
                                        )
                                    } else {
                                        realtimeViewModel.stop()
                                    }
                                },
                                enabled = imageCapture != null,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(if (uiState.running) "停止观察" else "开始观察")
                            }
                            Button(
                                onClick = {
                                    val response = uiState.latestResponse ?: return@Button
                                    if (ttsReady) {
                                        val speechText = buildRealtimeSpeechText(response)
                                        textToSpeech.speak(
                                            speechText,
                                            TextToSpeech.QUEUE_FLUSH,
                                            null,
                                            "manual-judgment",
                                        )
                                        lastSpokenSummary = response.summary
                                    }
                                },
                                enabled = ttsReady && uiState.latestResponse != null,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("播报")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveTag(
    title: String,
    value: String,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.16f),
            contentColor = Color.White,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
            Text(value, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RealtimeKnowledgeItem(
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
        Surface(
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.16f),
        ) {
            Text(
                sourceLabel,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                color = Color(0xFF2563EB).copy(alpha = 0.38f),
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
                color = Color(0xFFB45309).copy(alpha = 0.38f),
            ) {
                Text(
                    riskRelation,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                )
            }
        }
        Text(title, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        Text(summary, color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.bodySmall)
        Text(
            if (expanded) "收起详情" else "展开详情",
            color = Color(0xFFFFD6A5),
            style = MaterialTheme.typography.bodySmall,
        )
        if (expanded) {
            possibleCauses.takeIf { it.isNotEmpty() }?.let {
                Text(
                    "可能原因：${it.take(3).joinToString("、")}",
                    color = Color.White.copy(alpha = 0.82f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            careAdvice.takeIf { it.isNotEmpty() }?.let {
                Text(
                    "照护建议：${it.take(2).joinToString("；")}",
                    color = Color.White.copy(alpha = 0.82f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun knowledgeRiskRelation(
    item: KnowledgeSnippet,
    response: AnalyzeResponse?,
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
    response: AnalyzeResponse?,
): List<KnowledgeSnippet> {
    return items.sortedWith(
        compareBy<KnowledgeSnippet> { riskRank(knowledgeRiskRelation(it, response)) }
            .thenByDescending { it.score }
    )
}

private fun buildRealtimeSpeechText(response: AnalyzeResponse): String {
    val knowledge = response.retrieved_knowledge.firstOrNull()
    val knowledgePart = knowledge?.let {
        "这次我参考了${if (it.source_type == "visual") "相似示例" else if (it.source_type == "hybrid") "图文混合知识" else "规则知识"}《${it.title}》"
    }.orEmpty()
    val suggestionPart = response.care_suggestions.firstOrNull()?.let { "你可以先这样帮我，$it" }.orEmpty()
    val level = response.health_risk_assessment.level.lowercase()
    if (level == "urgent" || level == "high") {
        return listOf(
            if (level == "urgent") "先重点关注我，现在是紧急风险" else "先重点关注我，现在风险偏高",
            response.health_risk_assessment.reason,
            suggestionPart.removePrefix("你可以先这样帮我，").takeIf { it.isNotBlank() }?.let { "请优先这样做，$it" } ?: "",
            knowledgePart,
        ).filter { it.isNotBlank() }.joinToString("，")
    }
    return listOf(response.summary, knowledgePart, suggestionPart)
        .filter { it.isNotBlank() }
        .joinToString("，")
}

@Composable
private fun RealtimeRiskBanner(
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
            Text(title, color = Color(0xFFFECACA), style = MaterialTheme.typography.titleSmall)
            Text(reason, style = MaterialTheme.typography.bodySmall)
            if (suggestion.isNotBlank()) {
                Text("建议：$suggestion", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.84f))
            }
        }
    }
}

@Composable
private fun CatTargetOverlay(
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

@Composable
fun RealtimeResultCard(response: AnalyzeResponse) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("最新判断：${response.summary}", style = MaterialTheme.typography.titleMedium)
            Text("情绪：${response.emotion_assessment.primary}")
            Text("风险：${response.health_risk_assessment.level}")
            if (response.urgent_flags.contains("no_cat_detected")) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "未检测到猫：请将镜头稳定对准猫咪。",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (response.evidence.visual.isNotEmpty()) {
                Text("视觉依据：${response.evidence.visual.joinToString("；")}")
            }
            if (response.care_suggestions.isNotEmpty()) {
                Text("建议：${response.care_suggestions.joinToString("；")}")
            }
        }
    }
}

@Composable
private fun CatTargetStatusBanner(
    status: CatTargetStatus,
    hint: String,
) {
    val (title, colors) = when (status) {
        CatTargetStatus.DETECTED -> {
            "已检测到猫" to CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        CatTargetStatus.NOT_DETECTED -> {
            "未检测到猫" to CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        CatTargetStatus.UNKNOWN -> {
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
            Text(hint, style = MaterialTheme.typography.bodySmall)
        }
    }
}

suspend fun captureFrameUri(
    context: android.content.Context,
    imageCapture: ImageCapture,
): Uri = suspendCancellableCoroutine { continuation ->
    val outputFile = MediaCaptureFileStore.createImageFile(context)
    val outputUri = Uri.fromFile(outputFile)
    val options = ImageCapture.OutputFileOptions.Builder(outputFile).build()

    imageCapture.takePicture(
        options,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                continuation.resume(outputUri)
            }

            override fun onError(exception: ImageCaptureException) {
                continuation.resumeWithException(exception)
            }
        },
    )
}
