package com.catagent.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.catagent.data.model.AnalyzeResponse
import com.catagent.data.model.CatTargetBox
import com.catagent.data.model.EmotionAssessment
import com.catagent.data.model.EvidenceBundle
import com.catagent.data.model.HealthRiskAssessment
import com.catagent.ui.capture.CaptureViewModel
import com.catagent.ui.capture.MediaCaptureFileStore
import com.catagent.ui.chat.ChatViewModel
import com.catagent.ui.realtime.RealtimeScreen
import com.catagent.ui.realtimecall.RealtimeCallScreen

private val AppWarmBackground = Color(0xFFFFFDF0)
private val AppOrange = Color(0xFFFF9F43)
private val AppOrangeDeep = Color(0xFFFF6B6B)
private val AppBlue = Color(0xFF60A5FA)
private val AppLime = Color(0xFF84CC16)
private val AppSurface = Color.White
private val AppMuted = Color(0xFF6B7280)
private val AppGreen = Color(0xFF22C55E)
private val AppRed = Color(0xFFEF4444)
private val AppCardBorder = Color(0xFFFDE68A)
private val AppTitleColor = Color(0xFF7C2D12)
private val AppBodyColor = Color(0xFF92400E)
private val PageHorizontalPadding = 20.dp
private val PageTopPadding = 20.dp
private val PageBottomPadding = 110.dp
private val SectionSpacing = 14.dp

private data class DemoMeta(
    val petName: String,
    val dateLabel: String,
    val timeLabel: String,
    val cameraLabel: String,
)

private data class DemoTimelineEntry(
    val timeLabel: String,
    val phaseLabel: String,
    val summary: String,
    val level: String,
    val ownerAdvice: String,
)

private enum class DemoScenario {
    STABLE,
    RISK,
}

private data class EvidenceMarkerUi(
    val label: String,
    val detail: String,
    val topRatio: Float,
    val leftRatio: Float,
)

private enum class MainTab {
    HOME,
    MONITOR,
    HISTORY,
    RESULT,
    HEALTH,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatAgentApp(
    captureViewModel: CaptureViewModel = viewModel(),
    chatViewModel: ChatViewModel = viewModel(),
) {
    val captureState by captureViewModel.uiState.collectAsState()
    val chatState by chatViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val contentResolver = context.contentResolver
    var currentTab by remember { mutableStateOf(MainTab.HOME) }
    var showRealtimeScreen by remember { mutableStateOf(false) }
    var showRealtimeCallScreen by remember { mutableStateOf(false) }
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }
    var pendingPermissionAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingNavigateToResult by remember { mutableStateOf(false) }
    var showQuickDetectDialog by remember { mutableStateOf(false) }
    var lastPrimaryResultKey by remember { mutableStateOf<String?>(null) }
    var homeFeedbackMessage by remember { mutableStateOf<String?>(null) }
    var demoScenario by rememberSaveable { mutableStateOf(DemoScenario.STABLE.name) }
    val activeDemoScenario = runCatching { DemoScenario.valueOf(demoScenario) }.getOrDefault(DemoScenario.STABLE)
    val demoTimeline = remember(activeDemoScenario) { demoDayTimeline(activeDemoScenario) }
    val trackedResults = remember { mutableStateListOf<AnalyzeResponse>() }
    val archivedResults = remember { mutableStateListOf<AnalyzeResponse>() }

    if (showRealtimeScreen) {
        RealtimeScreen(
            contentResolver = contentResolver,
            sceneHint = captureState.sceneHint,
            inputText = captureState.inputText,
            onBack = { showRealtimeScreen = false },
        )
        return
    }
    if (showRealtimeCallScreen) {
        RealtimeCallScreen(
            contentResolver = contentResolver,
            sceneHint = captureState.sceneHint,
            inputText = captureState.inputText,
            onBack = { showRealtimeCallScreen = false },
        )
        return
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val granted = results.values.all { it }
        if (granted) {
            pendingPermissionAction?.invoke()
        }
        pendingPermissionAction = null
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let {
            captureViewModel.selectMedia(it, mediaType = "image", mediaLabel = "已选择图片")
        }
    }
    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let {
            captureViewModel.selectMedia(it, mediaType = "video", mediaLabel = "已选择视频")
        }
    }
    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        if (success) {
            pendingCaptureUri?.let {
                captureViewModel.selectMedia(it, mediaType = "image", mediaLabel = "刚刚拍摄的照片")
            }
        }
    }
    val captureVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CaptureVideo(),
    ) { success ->
        if (success) {
            pendingCaptureUri?.let {
                captureViewModel.selectMedia(it, mediaType = "video", mediaLabel = "刚刚拍摄的短视频")
            }
        }
    }

    val openPickImage = { imagePicker.launch("image/*") }
    val openPickVideo = { videoPicker.launch("video/*") }
    val openTakePhoto = {
        requestPermissionsIfNeeded(
            requiredPermissions = listOf(Manifest.permission.CAMERA),
            hasPermission = { permission ->
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            },
            onRequest = { permissions ->
                pendingPermissionAction = {
                    val uri = MediaCaptureFileStore.createImageUri(context)
                    pendingCaptureUri = uri
                    takePictureLauncher.launch(uri)
                }
                permissionLauncher.launch(permissions.toTypedArray())
            },
            onGranted = {
                val uri = MediaCaptureFileStore.createImageUri(context)
                pendingCaptureUri = uri
                takePictureLauncher.launch(uri)
            },
        )
    }
    val openRecordVideo = {
        requestPermissionsIfNeeded(
            requiredPermissions = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
            hasPermission = { permission ->
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            },
            onRequest = { permissions ->
                pendingPermissionAction = {
                    val uri = MediaCaptureFileStore.createVideoUri(context)
                    pendingCaptureUri = uri
                    captureVideoLauncher.launch(uri)
                }
                permissionLauncher.launch(permissions.toTypedArray())
            },
            onGranted = {
                val uri = MediaCaptureFileStore.createVideoUri(context)
                pendingCaptureUri = uri
                captureVideoLauncher.launch(uri)
            },
        )
    }
    val submitCurrentMedia = {
        pendingNavigateToResult = true
        currentTab = MainTab.RESULT
        captureViewModel.submit(contentResolver)
    }
    val triggerQuickDetect = {
        if (captureState.selectedUri == null) {
            showQuickDetectDialog = true
            currentTab = MainTab.HOME
            homeFeedbackMessage = "先选择图片或视频，再开始识喵。"
        } else {
            submitCurrentMedia()
        }
    }

    LaunchedEffect(captureState.sessionId, captureState.result) {
        chatViewModel.bindSession(captureState.sessionId, captureState.result)
    }

    LaunchedEffect(Unit) {
        captureViewModel.checkBackendStatus()
    }

    // 识别主链路只使用真实分析结果，避免默认演示数据误导真实测试。
    val primaryResponse = chatState.latestResponse ?: captureState.result
    val historyResponses = if (trackedResults.isNotEmpty()) {
        trackedResults.reversed()
    } else {
        emptyList()
    }
    val healthResponses = if (archivedResults.isNotEmpty()) {
        archivedResults.reversed()
    } else {
        historyResponses
    }

    LaunchedEffect(primaryResponse?.session_id, primaryResponse?.summary) {
        val key = primaryResponse?.let(::responseKey)
        if (key != null && key != lastPrimaryResultKey) {
            lastPrimaryResultKey = key
            if (trackedResults.none { responseKey(it) == key }) {
                trackedResults.add(primaryResponse)
            }
            if (pendingNavigateToResult) {
                currentTab = MainTab.RESULT
                pendingNavigateToResult = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppWarmBackground),
    ) {
        when (currentTab) {
            MainTab.HOME -> HomeTabScreen(
                backendStatus = captureState.backendStatus,
                inputText = captureState.inputText,
                onInputChange = captureViewModel::updateText,
                sceneHint = captureState.sceneHint,
                onSceneHintChange = captureViewModel::updateSceneHint,
                mediaLabel = captureState.mediaLabel,
                isSubmitting = captureState.loading,
                captureError = captureState.error,
                homeFeedbackMessage = homeFeedbackMessage,
                onDismissHomeFeedback = { homeFeedbackMessage = null },
                latestResponse = primaryResponse,
                historyResponses = historyResponses,
                onRefreshBackend = captureViewModel::checkBackendStatus,
                onPickImage = openPickImage,
                onPickVideo = openPickVideo,
                onTakePhoto = openTakePhoto,
                onRecordVideo = openRecordVideo,
                onSubmitAnalysis = triggerQuickDetect,
                onSelectFollowup = chatViewModel::updateQuestion,
                onOpenRealtime = { showRealtimeScreen = true },
                onOpenRealtimeCall = { showRealtimeCallScreen = true },
                onOpenHealth = { currentTab = MainTab.HEALTH },
                onOpenResult = { currentTab = MainTab.RESULT },
                demoScenario = activeDemoScenario,
                onToggleDemoScenario = {
                    demoScenario = when (activeDemoScenario) {
                        DemoScenario.STABLE -> DemoScenario.RISK.name
                        DemoScenario.RISK -> DemoScenario.STABLE.name
                    }
                    homeFeedbackMessage = if (activeDemoScenario == DemoScenario.STABLE) {
                        "已切换到风险日演示数据。"
                    } else {
                        "已切换到稳定日演示数据。"
                    }
                },
            )
            MainTab.RESULT -> ResultFlowScreen(
                response = primaryResponse,
                mediaUri = captureState.selectedUri,
                mediaType = captureState.mediaType,
                isAnalyzing = captureState.loading || pendingNavigateToResult,
                chatQuestion = chatState.question,
                onChatQuestionChange = chatViewModel::updateQuestion,
                isChatLoading = chatState.loading,
                chatError = chatState.error,
                onSendFollowup = chatViewModel::sendFollowup,
                onSelectFollowup = chatViewModel::updateQuestion,
                onSaveToArchive = { response ->
                    val key = responseKey(response)
                    if (archivedResults.none { responseKey(it) == key }) {
                        archivedResults.add(response)
                        homeFeedbackMessage = "已保存到健康档案，可在健康页查看长期趋势。"
                    } else {
                        homeFeedbackMessage = "这条分析已经在健康档案里了。"
                    }
                },
                isArchived = primaryResponse?.let { response ->
                    archivedResults.any { responseKey(it) == responseKey(response) }
                } ?: false,
                onBackHome = {
                    currentTab = MainTab.HOME
                    if (primaryResponse != null) {
                        homeFeedbackMessage = "最近一次分析已更新，可继续查看详情或追问 AI。"
                    }
                },
                onOpenHealth = { currentTab = MainTab.HEALTH },
            )
            MainTab.MONITOR -> MonitorTabScreen(
                response = primaryResponse,
                demoTimeline = demoTimeline,
                onBackHome = { currentTab = MainTab.HOME },
                onOpenRealtimeCall = { showRealtimeCallScreen = true },
                onOpenRealtimeObserve = { showRealtimeScreen = true },
            )
            MainTab.HISTORY -> HistoryTabScreen(
                responses = historyResponses,
                demoTimeline = demoTimeline,
                onBackHome = { currentTab = MainTab.HOME },
                onOpenResult = { currentTab = MainTab.RESULT },
            )
            MainTab.HEALTH -> HealthTabScreen(
                response = primaryResponse,
                historyResponses = healthResponses,
                onBackHome = { currentTab = MainTab.HOME },
            )
        }

        if (showQuickDetectDialog) {
            AlertDialog(
                onDismissRequest = { showQuickDetectDialog = false },
                title = { Text("即刻识喵") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("先选择一种输入方式：")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                showQuickDetectDialog = false
                                openTakePhoto()
                            }) { Text("拍照") }
                            OutlinedButton(onClick = {
                                showQuickDetectDialog = false
                                openRecordVideo()
                            }) { Text("录视频") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                showQuickDetectDialog = false
                                openPickImage()
                            }) { Text("上传图片") }
                            OutlinedButton(onClick = {
                                showQuickDetectDialog = false
                                openPickVideo()
                            }) { Text("上传视频") }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showQuickDetectDialog = false }) {
                        Text("我知道了")
                    }
                },
            )
        }

        BottomAppBarDesign(
            currentTab = currentTab,
            onSelectHome = { currentTab = MainTab.HOME },
            onSelectMonitor = { currentTab = MainTab.MONITOR },
            onSelectHistory = { currentTab = MainTab.HISTORY },
            onSelectHealth = { currentTab = MainTab.HEALTH },
            onCenterAction = triggerQuickDetect,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

private fun demoShowcaseResponses(scenario: DemoScenario): List<AnalyzeResponse> = when (scenario) {
    DemoScenario.STABLE -> listOf(
        AnalyzeResponse(
            session_id = "demo-001",
            summary = "本喵趴卧放松，呼吸平稳，整体状态不错喵。",
            emotion_assessment = EmotionAssessment(
                primary = "relaxed",
                confidence = 0.89,
                signals = listOf("趴卧稳定", "尾巴自然"),
            ),
            health_risk_assessment = HealthRiskAssessment(
                level = "low",
                score = 0.22,
                triggers = listOf("状态平稳"),
                reason = "当前未见明显异常，建议保持日常观察。",
            ),
            evidence = EvidenceBundle(
                visual = listOf("身体放松", "呼吸节奏均匀"),
                textual = listOf("用户描述: 午后休息"),
                knowledge_refs = listOf("docx_overall_mental_04"),
            ),
            cat_target_box = CatTargetBox(x = 0.18, y = 0.14, width = 0.62, height = 0.72, confidence = 0.84),
            care_suggestions = listOf("继续保持安静环境，补充清洁饮水。"),
            urgent_flags = emptyList(),
            followup_questions = listOf("今天进食和饮水是否正常喵？"),
            disclaimer = "本结果仅用于风险提示与照护建议，不构成医疗诊断。",
        ),
        AnalyzeResponse(
            session_id = "demo-002",
            summary = "本喵状态轻松，互动意愿正常，建议维持日常节奏喵。",
            emotion_assessment = EmotionAssessment(primary = "happy", confidence = 0.83, signals = listOf("主动靠近", "轻声呼噜")),
            health_risk_assessment = HealthRiskAssessment(level = "low", score = 0.3, triggers = listOf("活跃正常"), reason = "今日波动较小，整体表现稳定。"),
            evidence = EvidenceBundle(visual = listOf("主动贴近镜头"), textual = listOf("用户描述: 下午玩耍"), knowledge_refs = listOf("emotion_stress_signals")),
            cat_target_box = CatTargetBox(x = 0.2, y = 0.18, width = 0.58, height = 0.67, confidence = 0.79),
            care_suggestions = listOf("继续保持固定作息和互动节奏。"),
            urgent_flags = emptyList(),
            followup_questions = listOf("今晚准备了新玩具吗喵？"),
            disclaimer = "本结果仅用于风险提示与照护建议，不构成医疗诊断。",
        ),
        AnalyzeResponse(
            session_id = "demo-003",
            summary = "本喵偶尔警觉但可快速放松，整体仍在稳定区间。",
            emotion_assessment = EmotionAssessment(primary = "curious", confidence = 0.74, signals = listOf("短暂停顿观察", "耳位回正")),
            health_risk_assessment = HealthRiskAssessment(level = "medium", score = 0.48, triggers = listOf("轻度波动"), reason = "存在轻度短时波动，继续观察即可。"),
            evidence = EvidenceBundle(visual = listOf("短暂耳位后压"), textual = listOf("用户描述: 门外有声响"), knowledge_refs = listOf("docx_abnormal_posture_03")),
            cat_target_box = CatTargetBox(x = 0.23, y = 0.19, width = 0.56, height = 0.65, confidence = 0.76),
            care_suggestions = listOf("保持环境安静，减少突发刺激。"),
            urgent_flags = listOf("observe_behavior_change"),
            followup_questions = listOf("夜间是否还会频繁警觉喵？"),
            disclaimer = "本结果仅用于风险提示与照护建议，不构成医疗诊断。",
        ),
    )
    DemoScenario.RISK -> listOf(
        AnalyzeResponse(
            session_id = "demo-001",
            summary = "本喵活动意愿下降，姿态偏紧，建议重点观察步态。",
            emotion_assessment = EmotionAssessment(primary = "stress_alert", confidence = 0.82, signals = listOf("躯干紧绷", "活动减少")),
            health_risk_assessment = HealthRiskAssessment(level = "medium", score = 0.62, triggers = listOf("持续应激"), reason = "上午出现连续紧张体态，需提高观察频次。"),
            evidence = EvidenceBundle(visual = listOf("动作保守"), textual = listOf("用户描述: 上午不太愿动"), knowledge_refs = listOf("docx_overall_mental_03")),
            cat_target_box = CatTargetBox(x = 0.18, y = 0.14, width = 0.62, height = 0.72, confidence = 0.84),
            care_suggestions = listOf("减少跳高活动并记录步态变化。"),
            urgent_flags = listOf("observe_gait_change"),
            followup_questions = listOf("今天是否出现明显拒跳喵？"),
            disclaimer = "本结果仅用于风险提示与照护建议，不构成医疗诊断。",
        ),
        AnalyzeResponse(
            session_id = "demo-002",
            summary = "本喵晚间应激加重，建议先降低环境刺激并短时隔离休息。",
            emotion_assessment = EmotionAssessment(primary = "stress_alert", confidence = 0.86, signals = listOf("耳位后压", "持续警觉")),
            health_risk_assessment = HealthRiskAssessment(level = "high", score = 0.78, triggers = listOf("应激升级"), reason = "晚间应激信号连续出现，建议及时干预。"),
            evidence = EvidenceBundle(visual = listOf("耳位持续后压"), textual = listOf("用户描述: 夜间噪声"), knowledge_refs = listOf("emotion_stress_signals")),
            cat_target_box = CatTargetBox(x = 0.2, y = 0.18, width = 0.58, height = 0.67, confidence = 0.79),
            care_suggestions = listOf("先转移到安静空间，避免陌生接触。"),
            urgent_flags = listOf("stress_escalation"),
            followup_questions = listOf("是否伴随食欲下降或躲藏增多喵？"),
            disclaimer = "本结果仅用于风险提示与照护建议，不构成医疗诊断。",
        ),
        AnalyzeResponse(
            session_id = "demo-003",
            summary = "本喵步态偏慢且起身迟疑，建议尽快线下评估。",
            emotion_assessment = EmotionAssessment(primary = "pain_sign", confidence = 0.79, signals = listOf("起身迟疑", "步幅变短")),
            health_risk_assessment = HealthRiskAssessment(level = "high", score = 0.81, triggers = listOf("步态异常线索"), reason = "连续出现运动不适表现，需尽快排查。"),
            evidence = EvidenceBundle(visual = listOf("后肢发力保守"), textual = listOf("用户描述: 晚饭后走路慢"), knowledge_refs = listOf("docx_abnormal_posture_03")),
            cat_target_box = CatTargetBox(x = 0.23, y = 0.19, width = 0.56, height = 0.65, confidence = 0.76),
            care_suggestions = listOf("暂停高强度活动，优先安排线下评估。"),
            urgent_flags = listOf("gait_issue"),
            followup_questions = listOf("是否已连续两天出现步态变慢喵？"),
            disclaimer = "本结果仅用于风险提示与照护建议，不构成医疗诊断。",
        ),
    )
}

private fun demoMetaOf(response: AnalyzeResponse?): DemoMeta? = when (response?.session_id) {
    "demo-001" -> DemoMeta(
        petName = "糯米",
        dateLabel = "2026年04月19日",
        timeLabel = "10:30 AM",
        cameraLabel = "客厅树洞摄像头",
    )
    "demo-002" -> DemoMeta(
        petName = "二蛋",
        dateLabel = "2026年04月18日",
        timeLabel = "09:12 PM",
        cameraLabel = "玄关树洞摄像头",
    )
    "demo-003" -> DemoMeta(
        petName = "糯米",
        dateLabel = "2026年04月18日",
        timeLabel = "07:46 PM",
        cameraLabel = "阳台树洞摄像头",
    )
    else -> null
}

private fun demoTimelineTime(response: AnalyzeResponse, index: Int): String =
    demoMetaOf(response)?.timeLabel ?: "记录 ${index + 1}"

private fun demoDayTimeline(scenario: DemoScenario): List<DemoTimelineEntry> = when (scenario) {
    DemoScenario.STABLE -> listOf(
        DemoTimelineEntry("07:30", "清晨巡屋", "本喵精神不错，主动巡逻和伸展。", "low", "早晨可用逗猫棒做 5 分钟唤醒活动。"),
        DemoTimelineEntry("09:10", "早餐后", "进食顺利，饮水正常，情绪稳定。", "low", "保持湿粮+清水搭配，记录今日进食量。"),
        DemoTimelineEntry("11:40", "窗边观察", "对窗外声音较敏感，出现短暂警觉。", "medium", "减少突发噪音，拉上部分窗帘降低刺激。"),
        DemoTimelineEntry("13:20", "午后小憩", "趴卧放松，呼吸均匀，无明显异常。", "low", "保持安静休息区，避免频繁打扰。"),
        DemoTimelineEntry("16:00", "活动时段", "起身动作略慢，后肢发力偏保守。", "medium", "今天减少跳高动作，优先平地互动。"),
        DemoTimelineEntry("18:45", "晚餐前后", "情绪好转，互动意愿提升。", "low", "晚饭后可安排轻运动，观察步态连续性。"),
        DemoTimelineEntry("21:15", "夜间巡游", "出现轻度应激反应，耳位有后压。", "medium", "关灯后减少陌生刺激，留熟悉气味物品。"),
        DemoTimelineEntry("23:00", "睡前状态", "整体趋稳，可继续居家观察。", "low", "睡前补充清水，明早复查精神和步态。"),
    )
    DemoScenario.RISK -> listOf(
        DemoTimelineEntry("07:20", "清晨观察", "活动意愿偏低，起身略慢。", "medium", "早晨减少跳高，先做平地观察。"),
        DemoTimelineEntry("09:00", "早餐后", "进食较慢，警觉度偏高。", "medium", "分次少量喂食，记录进食速度。"),
        DemoTimelineEntry("11:30", "窗边反应", "对外界声音持续紧张，耳位后压。", "high", "立即降低噪音刺激，避免访客接触。"),
        DemoTimelineEntry("13:40", "午后休息", "短暂放松后再次紧绷。", "medium", "提供可躲藏空间，减少触碰。"),
        DemoTimelineEntry("16:10", "活动时段", "步态保守，后肢发力不足。", "high", "停止高跳，保留平地缓行。"),
        DemoTimelineEntry("18:30", "晚餐前后", "进食意愿下降，互动减少。", "medium", "观察饮水与排泄，必要时拍视频留档。"),
        DemoTimelineEntry("21:05", "夜间巡游", "应激再次上升，持续警觉。", "high", "先隔离到安静房间并保持弱光。"),
        DemoTimelineEntry("22:50", "睡前复查", "状态未完全回稳，建议线下评估。", "high", "若明早仍异常，尽快到院检查。"),
    )
}

@Composable
private fun HomeTabScreen(
    backendStatus: String,
    inputText: String,
    onInputChange: (String) -> Unit,
    sceneHint: String,
    onSceneHintChange: (String) -> Unit,
    mediaLabel: String,
    isSubmitting: Boolean,
    captureError: String?,
    homeFeedbackMessage: String?,
    onDismissHomeFeedback: () -> Unit,
    latestResponse: AnalyzeResponse?,
    historyResponses: List<AnalyzeResponse>,
    onRefreshBackend: () -> Unit,
    onPickImage: () -> Unit,
    onPickVideo: () -> Unit,
    onTakePhoto: () -> Unit,
    onRecordVideo: () -> Unit,
    onSubmitAnalysis: () -> Unit,
    onSelectFollowup: (String) -> Unit,
    onOpenRealtime: () -> Unit,
    onOpenRealtimeCall: () -> Unit,
    onOpenHealth: () -> Unit,
    onOpenResult: () -> Unit,
    demoScenario: DemoScenario,
    onToggleDemoScenario: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = PageHorizontalPadding, top = PageTopPadding, end = PageHorizontalPadding, bottom = PageBottomPadding),
        verticalArrangement = Arrangement.spacedBy(SectionSpacing),
    ) {
        HomeHeader(
            backendStatus = backendStatus,
            onRefreshBackend = onRefreshBackend,
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7ED)),
            border = BorderStroke(1.dp, Color(0xFFFED7AA)),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "演示模式：${if (demoScenario == DemoScenario.STABLE) "稳定日" else "风险日"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF9A3412),
                    fontWeight = FontWeight.Medium,
                )
                OutlinedButton(onClick = onToggleDemoScenario) {
                    Text(if (demoScenario == DemoScenario.STABLE) "切到风险日" else "切到稳定日")
                }
            }
        }
        Text("喵~ 铲屎的，", style = MaterialTheme.typography.bodyLarge, color = Color(0xFF92400E))
        Text("本喵现在心情美滋滋！", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color(0xFF7C2D12))
        homeFeedbackMessage?.let { message ->
            FeedbackBanner(
                message = message,
                onDismiss = onDismissHomeFeedback,
            )
        }
        MainHeroCard(
            mediaLabel = mediaLabel,
            onPickImage = onPickImage,
            onPickVideo = onPickVideo,
            onTakePhoto = onTakePhoto,
            onRecordVideo = onRecordVideo,
            onSubmitAnalysis = onSubmitAnalysis,
        )
        MonitorPreviewCard(
            summary = latestResponse?.summary ?: "本喵正在阳台晒太阳，看起来很安逸喵~",
            onOpenRealtimeCall = onOpenRealtimeCall,
        )
        HomeFeatureGrid(
            latestResponse = latestResponse,
            onOpenHealth = onOpenHealth,
            onOpenResult = onOpenResult,
        )
        QuickEntryRow(
            onOpenRealtime = onOpenRealtime,
            onOpenRealtimeCall = onOpenRealtimeCall,
        )
        HealthDigestCard(
            response = latestResponse,
            onOpenHealth = onOpenHealth,
        )
        if (latestResponse != null) {
            LatestResultEntryCard(
                response = latestResponse,
                onOpenResult = onOpenResult,
            )
        }
        MediaComposeCard(
            sceneHint = sceneHint,
            onSceneHintChange = onSceneHintChange,
            inputText = inputText,
            onInputChange = onInputChange,
            mediaLabel = mediaLabel,
            isSubmitting = isSubmitting,
            onPickImage = onPickImage,
            onPickVideo = onPickVideo,
            onSubmitAnalysis = onSubmitAnalysis,
        )
        if (isSubmitting) {
            ResultLoadingCard(
                title = "AI 正在分析中",
                subtitle = "已收到媒体和描述，正在整理情绪、风险与照护建议。",
            )
        }
        captureError?.let {
            Text(it, color = AppRed, style = MaterialTheme.typography.bodySmall)
        }
        HistorySection(
            responses = historyResponses,
            onSelectFollowup = onSelectFollowup,
        )
    }
}

@Composable
private fun ResultFlowScreen(
    response: AnalyzeResponse?,
    mediaUri: Uri?,
    mediaType: String,
    isAnalyzing: Boolean,
    chatQuestion: String,
    onChatQuestionChange: (String) -> Unit,
    isChatLoading: Boolean,
    chatError: String?,
    onSendFollowup: () -> Unit,
    onSelectFollowup: (String) -> Unit,
    onSaveToArchive: (AnalyzeResponse) -> Unit,
    isArchived: Boolean,
    onBackHome: () -> Unit,
    onOpenHealth: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = PageHorizontalPadding, top = 24.dp, end = PageHorizontalPadding, bottom = PageBottomPadding),
        verticalArrangement = Arrangement.spacedBy(SectionSpacing),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onBackHome) {
                Text("返回首页")
            }
            if (response != null) {
                OutlinedButton(onClick = onOpenHealth) {
                    Text("进入健康页")
                }
            }
        }
        if (response == null && isAnalyzing) {
            ResultLoadingCard(
                title = "AI 正在生成完整结果",
                subtitle = "正在整理证据、情绪摘要、健康提醒与建议，请稍候。",
            )
            return
        }
        if (response == null) {
            EmptyStateCard(
                title = "等待分析结果",
                description = "先在首页选择图片或视频并提交分析，结果会在这里展开。",
            )
            return
        }
        ResultDetailDesignCard(
            title = "AI 智能识别结果",
            response = response,
            mediaUri = mediaUri,
            mediaType = mediaType,
            onSelectFollowup = onSelectFollowup,
            onSaveToArchive = onSaveToArchive,
            isArchived = isArchived,
        )
        FollowupComposerCard(
            chatQuestion = chatQuestion,
            onChatQuestionChange = onChatQuestionChange,
            isChatLoading = isChatLoading,
            onSendFollowup = onSendFollowup,
        )
        chatError?.let {
            Text(it, color = AppRed, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun HealthTabScreen(
    response: AnalyzeResponse?,
    historyResponses: List<AnalyzeResponse>,
    onBackHome: () -> Unit,
) {
    val moodScore = ((response?.emotion_assessment?.confidence ?: 0.74) * 100).toInt().coerceIn(0, 100)
    val sleepScore = (78 + historyResponses.size * 2).coerceAtMost(96)
    val appetiteScore = when (response?.health_risk_assessment?.level?.lowercase()) {
        "urgent" -> 35
        "high" -> 52
        "medium" -> 71
        else -> 88
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = PageHorizontalPadding, top = 24.dp, end = PageHorizontalPadding, bottom = PageBottomPadding),
        verticalArrangement = Arrangement.spacedBy(SectionSpacing),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("本喵的健康报告", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("今日身体与情绪综合评分", style = MaterialTheme.typography.bodySmall, color = AppMuted)
            }
            OutlinedButton(onClick = onBackHome) {
                Text("返回首页")
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { moodScore / 100f },
                        modifier = Modifier.size(120.dp),
                        strokeWidth = 10.dp,
                        color = Color(0xFF84CC16),
                        trackColor = Color(0xFFFDE68A),
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$moodScore", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = Color(0xFF7C2D12))
                        Text("身体分数", style = MaterialTheme.typography.bodySmall, color = AppMuted)
                    }
                }
                Text("本喵整体状态还算不错喵！", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF92400E), fontWeight = FontWeight.Bold)
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        ) {
            Box(
                modifier = Modifier
                    .background(
                        Brush.linearGradient(listOf(Color(0xFFFF9B9B), Color(0xFFFF6B6B))),
                        RoundedCornerShape(26.dp),
                    )
                    .padding(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("异常行为提醒", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        response?.health_risk_assessment?.reason ?: "本喵今天状态有点不一样，建议你多关注我一下喵。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.9f),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onBackHome, colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.24f))) {
                            Text("咨询宠医", color = Color.White)
                        }
                        Button(onClick = onBackHome, colors = ButtonDefaults.buttonColors(containerColor = Color.White)) {
                            Text("查看对策", color = Color(0xFFDC2626))
                        }
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("指标分析（今日）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                ScoreLine("饮水频次", (appetiteScore - 28).coerceIn(15, 92), Color(0xFF60A5FA))
                ScoreLine("运动消耗", (moodScore - 8).coerceIn(12, 96), Color(0xFF84CC16))
                ScoreLine("睡眠质量", sleepScore, Color(0xFFF59E0B))
            }
        }

        if (response == null && historyResponses.isEmpty()) {
            EmptyStateCard(
                title = "健康档案等待建立",
                description = "先完成一次分析，健康页就会开始积累情绪、食欲和风险趋势。",
            )
        }

        if (historyResponses.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("最近观察趋势", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFF7C2D12))
                    historyResponses.take(3).forEachIndexed { index, item ->
                        TrendRow(
                            title = "第 ${index + 1} 次分析",
                            summary = item.summary,
                            level = item.health_risk_assessment.level,
                        )
                    }
                }
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF7FEE7)),
            border = BorderStroke(1.dp, Color(0xFFD9F99D)),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("本喵的小建议", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color(0xFF3F6212))
                Text(
                    response?.care_suggestions?.firstOrNull()
                        ?: "主人，最近多给本喵准备点干净流动水，记得陪我活动一下喵~",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF4D7C0F),
                )
            }
        }
    }
}

@Composable
private fun MonitorTabScreen(
    response: AnalyzeResponse?,
    demoTimeline: List<DemoTimelineEntry>,
    onBackHome: () -> Unit,
    onOpenRealtimeCall: () -> Unit,
    onOpenRealtimeObserve: () -> Unit,
) {
    val activeMeta = demoMetaOf(response)
    val activePetName = activeMeta?.petName ?: "糯米"
    val activeCameraLabel = activeMeta?.cameraLabel ?: "客厅树洞摄像头"
    val activeTimeLabel = activeMeta?.timeLabel ?: "14:23"
    val monitorEvents = demoTimeline.takeLast(4).reversed()
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(380.dp)
                .background(Brush.verticalGradient(listOf(Color(0xFF111827), Color(0xFF1F2937)))),
        ) {
            Text(
                "LIVE",
                modifier = Modifier
                    .padding(start = 20.dp, top = 28.dp)
                    .background(Color(0xFFEF4444), RoundedCornerShape(10.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
            )
            OutlinedButton(
                onClick = onBackHome,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 22.dp, end = 20.dp),
            ) { Text("返回") }
            Text(
                activeTimeLabel,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 30.dp)
                    .background(Color.Black.copy(alpha = 0.32f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                response?.summary ?: "客厅树洞摄像头：本喵正在打盹喵。",
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(18.dp),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.2f)) {
                    Text("麦", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = Color.White)
                }
                Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.2f)) {
                    Text("镜", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = Color.White)
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .offset(y = (-24).dp)
                .verticalScroll(rememberScrollState())
                .background(Color.Transparent)
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card(
                shape = RoundedCornerShape(30.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(activeCameraLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFF7C2D12))
                    Text("当前状态：${response?.summary ?: "本喵正在打盹喵"}", style = MaterialTheme.typography.bodySmall, color = AppMuted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = Color(0xFFD9F99D)) {
                            Text(
                                "$activePetName（本喵）",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF3F6212),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Surface(shape = CircleShape, color = Color(0xFFE5E7EB)) {
                            Text(
                                "二蛋",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF4B5563),
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onOpenRealtimeCall,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = AppOrange),
                        ) { Text("开始实时通话") }
                        OutlinedButton(
                            onClick = onOpenRealtimeObserve,
                            modifier = Modifier.weight(1f),
                        ) { Text("开始实时观察") }
                    }
                }
            }
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7ED)),
                border = BorderStroke(1.dp, Color(0xFFFED7AA)),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("AI 喵语分析", fontWeight = FontWeight.Bold, color = Color(0xFF7C2D12))
                    Text(
                        response?.health_risk_assessment?.reason ?: "主人主人，本喵刚刚去蹭了猫碗，不一定是饿了，可能是想你陪我玩喵~",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF92400E),
                    )
                    response?.care_suggestions?.firstOrNull()?.let {
                        Text("建议：$it", style = MaterialTheme.typography.bodySmall, color = AppMuted)
                    }
                }
            }
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE5E7EB)),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("今日监控动态", fontWeight = FontWeight.Bold, color = Color(0xFF7C2D12))
                    monitorEvents.forEach { event ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("${event.timeLabel} · ${event.phaseLabel}", style = MaterialTheme.typography.bodySmall, color = AppMuted)
                                Text(event.summary, style = MaterialTheme.typography.bodySmall, color = Color(0xFF374151), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = when (event.level.lowercase()) {
                                    "low" -> Color(0xFFD9F99D)
                                    "medium" -> Color(0xFFFDE68A)
                                    else -> Color(0xFFFECACA)
                                },
                            ) {
                                Text(
                                    event.level.uppercase(),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF7C2D12),
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryTabScreen(
    responses: List<AnalyzeResponse>,
    demoTimeline: List<DemoTimelineEntry>,
    onBackHome: () -> Unit,
    onOpenResult: () -> Unit,
) {
    val latestMeta = demoMetaOf(responses.firstOrNull())
    val latestDateLabel = latestMeta?.dateLabel ?: "今日"
    val happiest = responses.maxByOrNull { it.emotion_assessment.confidence }
    val happiestTimeLabel = happiest?.let { demoMetaOf(it)?.timeLabel } ?: "10:30 AM"
    val stressPercent = (
        responses.take(8).count { it.health_risk_assessment.level.lowercase() in setOf("medium", "high", "urgent") } * 100
            / responses.take(8).size.coerceAtLeast(1)
        ).coerceIn(8, 42)
    val timelineEntries = if (responses.size >= 7) {
        responses.take(8).mapIndexed { index, item ->
            DemoTimelineEntry(
                timeLabel = demoTimelineTime(item, index),
                phaseLabel = if (index == 0) "最新记录" else "历史记录 ${index + 1}",
                summary = item.summary,
                level = item.health_risk_assessment.level,
                ownerAdvice = item.care_suggestions.firstOrNull() ?: "继续观察本喵状态变化。",
            )
        }
    } else demoTimeline
    val highRiskCount = timelineEntries.count { it.level.lowercase() in setOf("high", "urgent") }
    val mediumRiskCount = timelineEntries.count { it.level.lowercase() == "medium" }
    val isRiskDay = highRiskCount >= 1 || mediumRiskCount >= 3 || stressPercent >= 34
    val daySummaryText = if (isRiskDay) {
        "本喵今天出现多次中风险波动，晚间还有轻度应激，建议明天继续重点观察步态和食欲变化。"
    } else {
        "本喵今天整体以低风险为主，情绪和活动节奏稳定，属于舒适放松的一天。"
    }
    val ownerAdviceText = if (isRiskDay) {
        "给主人的建议：1) 今晚减少高跳和噪音刺激；2) 明早复查起身动作与进食饮水；3) 若连续两天中高风险，尽快线下评估。"
    } else {
        "给主人的建议：1) 保持规律陪玩和饮水补给；2) 继续记录早晚两次状态；3) 维持熟悉环境，避免突发应激。"
    }
    val summaryBg = if (isRiskDay) Color(0xFFFFF1F2) else Color(0xFFF0FDF4)
    val summaryBorder = if (isRiskDay) Color(0xFFFECDD3) else Color(0xFFBBF7D0)
    val summaryTitleColor = if (isRiskDay) Color(0xFFBE123C) else Color(0xFF166534)
    val summaryBodyColor = if (isRiskDay) Color(0xFF9F1239) else Color(0xFF14532D)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = PageHorizontalPadding, top = 24.dp, end = PageHorizontalPadding, bottom = PageBottomPadding),
        verticalArrangement = Arrangement.spacedBy(SectionSpacing),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("本喵的情绪档案", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("时间足迹与情绪变化", style = MaterialTheme.typography.bodySmall, color = AppMuted)
            }
            OutlinedButton(onClick = onBackHome) { Text("返回首页") }
        }
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Color(0xFFFDE68A)),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("当前查看", style = MaterialTheme.typography.bodySmall, color = AppMuted)
                    Text(latestDateLabel, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color(0xFF7C2D12))
                }
                Text("日历", color = Color(0xFF65A30D), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            }
        }
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("今日心情指数", fontWeight = FontWeight.Bold, color = Color(0xFF7C2D12))
                ScoreLine("兴奋活跃度", 74, AppOrange)
                ScoreLine("放松稳定度", 81, AppGreen)
                ScoreLine("压力指数", 26, Color(0xFF60A5FA))
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = AppLime),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("最快乐时刻", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.86f))
                    Text(happiestTimeLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("罐头时间喵！", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.92f))
                }
            }
            Card(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("平均压力", style = MaterialTheme.typography.bodySmall, color = Color(0xFF92400E))
                    Text("${stressPercent}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFF7C2D12))
                    Text("本喵很放松~", style = MaterialTheme.typography.bodySmall, color = Color(0xFF65A30D))
                }
            }
        }
        if (timelineEntries.isEmpty()) {
            EmptyStateCard(
                title = "还没有喵语档案",
                description = "先完成一次识别，历史页会开始记录本喵的状态变化。",
            )
        } else {
            Text("时间足迹", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFF7C2D12))
            timelineEntries.forEach { item ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .size(12.dp)
                            .background(
                                when (item.level.lowercase()) {
                                    "low" -> Color(0xFF84CC16)
                                    "medium" -> Color(0xFFF59E0B)
                                    "high", "urgent" -> Color(0xFFEF4444)
                                    else -> Color(0xFF9CA3AF)
                                },
                                CircleShape,
                            ),
                    )
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, Color(0xFFFFEDD5)),
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    item.phaseLabel,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF7C2D12),
                                )
                                Text(
                                    "${item.timeLabel} · ${item.level.uppercase()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppMuted,
                                )
                            }
                            Text(
                                item.summary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF374151),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            AssistChip(onClick = onOpenResult, label = { Text("给主人建议：${item.ownerAdvice}") })
                        }
                    }
                }
            }
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = summaryBg),
                border = BorderStroke(1.dp, summaryBorder),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (isRiskDay) "今日总结（风险日）" else "今日总结（稳定日）",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = summaryTitleColor,
                    )
                    Text(
                        daySummaryText,
                        style = MaterialTheme.typography.bodySmall,
                        color = summaryBodyColor,
                    )
                    Text(
                        ownerAdviceText,
                        style = MaterialTheme.typography.bodySmall,
                        color = summaryBodyColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(
    backendStatus: String,
    onRefreshBackend: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(AppLime, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("喵", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
            }
            Column {
                Text("树洞喵", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFF7C2D12))
                Text("读懂情绪变化，守护健康日常", style = MaterialTheme.typography.bodySmall, color = AppMuted)
            }
        }
        OutlinedButton(
            onClick = onRefreshBackend,
            shape = CircleShape,
            border = BorderStroke(1.dp, Color(0xFFFCD34D)),
        ) {
            Text("刷新")
        }
    }
    Text(backendStatus, style = MaterialTheme.typography.bodySmall, color = Color(0xFF78716C))
}

@Composable
private fun MainHeroCard(
    mediaLabel: String,
    onPickImage: () -> Unit,
    onPickVideo: () -> Unit,
    onTakePhoto: () -> Unit,
    onRecordVideo: () -> Unit,
    onSubmitAnalysis: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(36.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFFE8D5B5), Color(0xFFFFFDF0)),
                    ),
                    RoundedCornerShape(36.dp),
                )
                .border(1.dp, AppCardBorder, RoundedCornerShape(36.dp))
                .padding(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "别让本喵等太久喵！",
                    color = AppBodyColor,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                )
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Button(
                        onClick = onSubmitAnalysis,
                        modifier = Modifier.size(168.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF84CC16)),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("即刻识喵", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("AI读脸中", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onTakePhoto,
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFEF3C7), contentColor = AppBodyColor),
                    ) {
                        Text("拍照")
                    }
                    OutlinedButton(
                        onClick = onRecordVideo,
                        border = BorderStroke(1.dp, Color(0xFFFCD34D)),
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppBodyColor),
                    ) {
                        Text("录视频")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onPickImage,
                        border = BorderStroke(1.dp, Color(0xFFFCD34D)),
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppBodyColor),
                    ) {
                        Text("上传图片")
                    }
                    OutlinedButton(
                        onClick = onPickVideo,
                        border = BorderStroke(1.dp, Color(0xFFFCD34D)),
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF92400E)),
                    ) {
                        Text("上传视频")
                    }
                }
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xFFFEF3C7),
                ) {
                    Text(
                        text = mediaLabel,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        color = AppBodyColor,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun MonitorPreviewCard(
    summary: String,
    onOpenRealtimeCall: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, AppCardBorder),
        onClick = onOpenRealtimeCall,
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("偷看本喵（实时监控）", fontWeight = FontWeight.Bold, color = Color(0xFF7C2D12))
                Text("查看全部 >", color = Color(0xFF65A30D), style = MaterialTheme.typography.bodySmall)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .background(Brush.verticalGradient(listOf(Color(0xFF374151), Color(0xFF111827))), RoundedCornerShape(18.dp)),
            ) {
                Text(
                    "REC 14:23",
                    modifier = Modifier
                        .padding(10.dp)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(summary, style = MaterialTheme.typography.bodySmall, color = Color(0xFF92400E))
        }
    }
}

@Composable
private fun HomeFeatureGrid(
    latestResponse: AnalyzeResponse?,
    onOpenHealth: () -> Unit,
    onOpenResult: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
            border = BorderStroke(1.dp, Color(0xFFFECACA)),
            onClick = onOpenHealth,
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("健康预警", fontWeight = FontWeight.Bold, color = Color(0xFFB91C1C))
                Text(
                    latestResponse?.health_risk_assessment?.reason ?: "本喵最近有点小异常，快帮我看看喵。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF991B1B),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Card(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
            border = BorderStroke(1.dp, Color(0xFFBFDBFE)),
            onClick = onOpenResult,
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("往日快乐", fontWeight = FontWeight.Bold, color = Color(0xFF1D4ED8))
                Text(
                    latestResponse?.summary ?: "记录本喵的开心、困倦和小情绪波动。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF1E40AF),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun QuickEntryRow(
    onOpenRealtime: () -> Unit,
    onOpenRealtimeCall: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        QuickEntryCard(
            modifier = Modifier.weight(1f),
            title = "实时观察",
            subtitle = "相机持续分析",
            background = Color(0xFFFFEDD5),
            accent = AppOrange,
            onClick = onOpenRealtime,
        )
        QuickEntryCard(
            modifier = Modifier.weight(1f),
            title = "实时通话",
            subtitle = "边看边说边播报",
            background = Color(0xFFE0F2FE),
            accent = AppBlue,
            onClick = onOpenRealtimeCall,
        )
    }
}

@Composable
private fun QuickEntryCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    background: Color,
    accent: Color,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = background),
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(accent, CircleShape),
            )
            Text(title, fontWeight = FontWeight.Bold, color = accent)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppMuted)
        }
    }
}

@Composable
private fun HealthDigestCard(
    response: AnalyzeResponse?,
    onOpenHealth: () -> Unit,
) {
    val activeMeta = demoMetaOf(response)
    val activePetName = activeMeta?.petName ?: "布丁"
    val activeTimeLabel = activeMeta?.timeLabel ?: "10:24 AM"
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = AppSurface),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("健康监测报告", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = onOpenHealth) {
                    Text("查看全部")
                }
            }
            val emotionScore = ((response?.emotion_assessment?.confidence ?: 0.89) * 100).toInt()
            val summary = response?.summary ?: "情绪稳定，等待你的首次分析结果。"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("$activePetName（美短）", fontWeight = FontWeight.Bold)
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = AppMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("上次检测", style = MaterialTheme.typography.bodySmall, color = AppMuted)
                    Text(activeTimeLabel, fontWeight = FontWeight.Medium)
                }
            }
            ScoreLine("当前稳定度", emotionScore.coerceIn(0, 100), AppGreen)
        }
    }
}

@Composable
private fun FeedbackBanner(
    message: String,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1E6)),
        border = BorderStroke(1.dp, Color(0xFFFFD2B5)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF9A3412),
            )
            OutlinedButton(onClick = onDismiss) {
                Text("知道了")
            }
        }
    }
}

@Composable
private fun ResultLoadingCard(
    title: String,
    subtitle: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = AppSurface),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = AppMuted)
            repeat(3) { index ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth(if (index == 2) 0.62f else 1f)
                        .height(if (index == 0) 18.dp else 12.dp)
                        .background(Color(0xFFF3F4F6), RoundedCornerShape(10.dp)),
                )
            }
        }
    }
}

@Composable
private fun EmptyStateCard(
    title: String,
    description: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = AppSurface),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = AppMuted)
        }
    }
}

@Composable
private fun LatestResultEntryCard(
    response: AnalyzeResponse,
    onOpenResult: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = AppSurface),
        onClick = onOpenResult,
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("最新分析结果", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("点击进入完整结果页", style = MaterialTheme.typography.bodySmall, color = AppMuted)
                }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = riskBackground(response),
                ) {
                    Text(
                        riskBadgeText(response),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        color = riskTextColor(response),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Text(
                response.summary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = Color(0xFF4B5563),
            )
        }
    }
}

@Composable
private fun MediaComposeCard(
    sceneHint: String,
    onSceneHintChange: (String) -> Unit,
    inputText: String,
    onInputChange: (String) -> Unit,
    mediaLabel: String,
    isSubmitting: Boolean,
    onPickImage: () -> Unit,
    onPickVideo: () -> Unit,
    onSubmitAnalysis: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = AppSurface),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("开始一次新的 AI 检测", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallActionButton("选图", onPickImage)
                SmallActionButton("选视频", onPickVideo)
            }
            Text("当前媒体：$mediaLabel", style = MaterialTheme.typography.bodySmall, color = AppMuted)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    "stress" to "应激",
                    "appetite" to "食欲差",
                    "litterbox" to "猫砂盆",
                    "general" to "综合",
                ).forEach { (value, label) ->
                    AssistChip(
                        onClick = { onSceneHintChange(value) },
                        label = { Text(if (sceneHint == value) "$label ✓" else label) },
                    )
                }
            }
            OutlinedTextField(
                value = inputText,
                onValueChange = onInputChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("补充一句描述或语音转写") },
                minLines = 3,
            )
            Button(
                onClick = onSubmitAnalysis,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                enabled = !isSubmitting,
                colors = ButtonDefaults.buttonColors(containerColor = AppOrange),
            ) {
                Text(if (isSubmitting) "分析中..." else "提交分析")
            }
        }
    }
}

@Composable
private fun SmallActionButton(
    text: String,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, shape = RoundedCornerShape(18.dp)) {
        Text(text)
    }
}

@Composable
private fun ResultDetailDesignCard(
    title: String,
    response: AnalyzeResponse,
    mediaUri: Uri?,
    mediaType: String,
    onSelectFollowup: (String) -> Unit,
    onSaveToArchive: (AnalyzeResponse) -> Unit,
    isArchived: Boolean,
) {
    var selectedEvidenceIndex by remember(response.session_id) { mutableStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFAF9F6)),
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                EvidenceHero(
                    response = response,
                    mediaUri = mediaUri,
                    mediaType = mediaType,
                    selectedIndex = selectedEvidenceIndex,
                    onSelectIndex = { selectedEvidenceIndex = it },
                )
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = AppSurface),
                    border = BorderStroke(1.dp, Color(0xFFFFEDD5)),
                ) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("当前情绪翻译", color = AppOrange, style = MaterialTheme.typography.labelMedium)
                        Text(response.summary, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "情绪：${response.emotion_assessment.primary} · 置信度 ${(response.emotion_assessment.confidence * 100).toInt()}%",
                            color = AppMuted,
                        )
                        if (response.evidence.visual.isNotEmpty()) {
                            Text(
                                "基于 ${response.evidence.visual.take(2).joinToString("、")} 的综合判断。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppMuted,
                            )
                        }
                    }
                }
                SelectedEvidenceCard(
                    response = response,
                    selectedIndex = selectedEvidenceIndex,
                )

                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = riskBackground(response)),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("健康小贴士", fontWeight = FontWeight.Bold, color = riskTextColor(response))
                        Text(response.health_risk_assessment.reason, style = MaterialTheme.typography.bodySmall)
                        if (response.urgent_flags.isNotEmpty()) {
                            Text(
                                "重点提醒：${response.urgent_flags.joinToString("；")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = riskTextColor(response),
                            )
                        }
                    }
                }

                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = AppSurface),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("AI 科学养宠建议", fontWeight = FontWeight.Bold)
                        response.care_suggestions.take(2).forEach { suggestion ->
                            SuggestionItem(suggestion)
                        }
                    }
                }

                if (response.retrieved_knowledge.isNotEmpty()) {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = AppSurface),
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("本次知识依据", fontWeight = FontWeight.Bold)
                            response.retrieved_knowledge.take(3).forEach { item ->
                                KnowledgeReferenceItem(
                                    title = item.title,
                                    sourceType = item.source_type,
                                    summary = item.content,
                                    tag = item.section ?: item.category,
                                )
                            }
                        }
                    }
                }

                if (response.followup_questions.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        response.followup_questions.take(2).forEach { question ->
                            AssistChip(
                                onClick = { onSelectFollowup(question) },
                                label = { Text(question) },
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { onSaveToArchive(response) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (isArchived) "已保存到档案" else "保存到档案")
                    }
                    Button(
                        onClick = {
                            response.followup_questions.firstOrNull()?.let(onSelectFollowup)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = AppOrange),
                    ) {
                        Text("继续追问 AI")
                    }
                }
                Text(response.disclaimer, style = MaterialTheme.typography.bodySmall, color = AppMuted)
            }
        }
    }
}

@Composable
private fun EvidenceHero(
    response: AnalyzeResponse,
    mediaUri: Uri?,
    mediaType: String,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
) {
    val imageBitmap = rememberMediaImageBitmap(mediaUri = mediaUri, mediaType = mediaType)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFE7D6)),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
        ) {
            if (imageBitmap != null) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = "分析原图",
                    modifier = Modifier.fillMaxSize(),
                )
                if (mediaType == "video") {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = Color.Black.copy(alpha = 0.58f),
                    ) {
                        Text(
                            text = "视频首帧预览 · 0.0s",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color(0xFFFFCFA3), Color(0xFFFFF1E6)),
                            ),
                        ),
                )
            }
            response.cat_target_box?.let { box ->
                val left = maxWidth * box.x.toFloat().coerceIn(0f, 1f)
                val top = maxHeight * box.y.toFloat().coerceIn(0f, 1f)
                val width = (maxWidth * box.width.toFloat().coerceIn(0.05f, 1f)).coerceAtLeast(44.dp)
                val height = (maxHeight * box.height.toFloat().coerceIn(0.05f, 1f)).coerceAtLeast(44.dp)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = left, top = top)
                        .width(width)
                        .height(height)
                        .border(2.dp, Color(0xFFFF6B00), RoundedCornerShape(10.dp))
                        .background(Color(0x33FF9F43), RoundedCornerShape(10.dp)),
                ) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = Color.Black.copy(alpha = 0.55f),
                    ) {
                        Text(
                            text = "识别 ${(box.confidence * 100).toInt()}%",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            val markers = buildList {
                response.evidence.visual.take(3).forEachIndexed { index, item ->
                    add(
                        EvidenceMarkerUi(
                            label = item,
                            detail = buildString {
                                append(item)
                                append("；")
                                append(
                                    response.evidence.textual.getOrNull(index)
                                        ?: response.health_risk_assessment.reason,
                                )
                            },
                            topRatio = listOf(0.24f, 0.52f, 0.74f).getOrElse(index) { 0.24f },
                            leftRatio = listOf(0.34f, 0.7f, 0.5f).getOrElse(index) { 0.5f },
                        ),
                    )
                }
                if (isEmpty()) {
                    add(EvidenceMarkerUi("耳位分析", "耳位线索提示当前情绪与警觉度。", 0.24f, 0.34f))
                    add(EvidenceMarkerUi("尾巴姿态", "尾巴姿态能帮助判断放松、警惕或烦躁。", 0.52f, 0.7f))
                    add(EvidenceMarkerUi("瞳孔状态", "瞳孔变化可辅助判断紧张、光照或不适。", 0.74f, 0.5f))
                }
            }
            markers.forEachIndexed { index, marker ->
                EvidenceMarker(
                    label = marker.label,
                    selected = index == selectedIndex,
                    onClick = { onSelectIndex(index) },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(
                            start = maxWidth * marker.leftRatio - 10.dp,
                            top = maxHeight * marker.topRatio - 10.dp,
                        ),
                )
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(14.dp),
                shape = RoundedCornerShape(18.dp),
                color = Color.Black.copy(alpha = 0.58f),
            ) {
                Text(
                    text = if (imageBitmap != null) {
                        if (mediaType == "video") "已展示视频首帧与识别框，点击橙色标记看证据" else "原图与识别框已展示，点击橙色标记看证据"
                    } else {
                        "点击橙色标记查看 AI 分析证据"
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun rememberMediaImageBitmap(
    mediaUri: Uri?,
    mediaType: String,
): ImageBitmap? {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, mediaUri, mediaType) {
        value = null
        if (mediaUri == null) return@produceState
        value = runCatching {
            if (mediaType == "image") {
                context.contentResolver.openInputStream(mediaUri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            } else if (mediaType == "video") {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, mediaUri)
                    val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    frame?.asImageBitmap()
                } finally {
                    retriever.release()
                }
            } else {
                null
            }
        }.getOrNull()
    }
    return bitmap
}

@Composable
private fun EvidenceMarker(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .background(AppOrange, CircleShape)
                .border(2.dp, Color.White, CircleShape),
        )
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (selected) Color.White else Color.White.copy(alpha = 0.82f),
            border = if (selected) BorderStroke(1.dp, AppOrange.copy(alpha = 0.5f)) else null,
        ) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF7C2D12),
            )
        }
    }
}

@Composable
private fun SelectedEvidenceCard(
    response: AnalyzeResponse,
    selectedIndex: Int,
) {
    val visual = response.evidence.visual.take(3)
    val label = visual.getOrNull(selectedIndex) ?: "综合证据"
    val detail = response.evidence.textual.getOrNull(selectedIndex)
        ?: response.health_risk_assessment.reason
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFFFEDD5)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("证据详情", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(label, color = AppOrange, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = AppMuted)
        }
    }
}

@Composable
private fun SuggestionItem(suggestion: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(8.dp)
                .background(AppOrange, CircleShape),
        )
        Text(suggestion, style = MaterialTheme.typography.bodyMedium, color = AppMuted)
    }
}

@Composable
private fun KnowledgeReferenceItem(
    title: String,
    sourceType: String,
    summary: String,
    tag: String,
) {
    val sourceLabel = when (sourceType.lowercase()) {
        "visual" -> "相似示例"
        "hybrid" -> "图文混合"
        else -> "规则知识"
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFFFFF1E6),
            ) {
                Text(
                    sourceLabel,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppOrange,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(tag, style = MaterialTheme.typography.bodySmall, color = AppMuted)
        }
        Text(title, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
        Text(summary, style = MaterialTheme.typography.bodySmall, color = AppMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FollowupComposerCard(
    chatQuestion: String,
    onChatQuestionChange: (String) -> Unit,
    isChatLoading: Boolean,
    onSendFollowup: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = AppSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("继续追问 AI", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = chatQuestion,
                onValueChange = onChatQuestionChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("例如：它 12 小时没怎么吃，需要马上去医院吗？") },
                minLines = 2,
            )
            Button(
                onClick = onSendFollowup,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                enabled = !isChatLoading,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF7A59)),
            ) {
                Text(if (isChatLoading) "发送中..." else "继续追问 AI")
            }
        }
    }
}

@Composable
private fun HistorySection(
    responses: List<AnalyzeResponse>,
    onSelectFollowup: (String) -> Unit,
) {
    if (responses.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("历史分析", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        responses.take(3).forEachIndexed { index, response ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = AppSurface),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(Color(0xFFFFE7D6), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("布丁", color = AppOrange, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = riskBackground(response),
                                ) {
                                    Text(
                                        riskBadgeText(response),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        color = riskTextColor(response),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                Text(
                                    if (index == 0) "昨天 18:30" else "04-1${6 - index} 14:20",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppMuted,
                                )
                            }
                            Text(
                                response.summary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF4B5563),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    response.followup_questions.firstOrNull()?.let { question ->
                        AssistChip(
                            onClick = { onSelectFollowup(question) },
                            label = { Text(question) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomAppBarDesign(
    currentTab: MainTab,
    onSelectHome: () -> Unit,
    onSelectMonitor: () -> Unit,
    onSelectHistory: () -> Unit,
    onSelectHealth: () -> Unit,
    onCenterAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.White,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        border = BorderStroke(1.dp, AppCardBorder),
        shadowElevation = 10.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BottomNavItem("首页", selected = currentTab == MainTab.HOME, onClick = onSelectHome)
            BottomNavItem("监控", selected = currentTab == MainTab.MONITOR, onClick = onSelectMonitor)
            Button(
                onClick = onCenterAction,
                modifier = Modifier
                    .size(58.dp)
                    .offset(y = (-20).dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF84CC16)),
                border = BorderStroke(3.dp, Color.White),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                Text("识喵", color = Color.White, fontWeight = FontWeight.Bold)
            }
            BottomNavItem("历史", selected = currentTab == MainTab.HISTORY, onClick = onSelectHistory)
            BottomNavItem("健康", selected = currentTab == MainTab.HEALTH, onClick = onSelectHealth)
        }
    }
}

@Composable
private fun BottomNavItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.width(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(if (selected) 8.dp else 6.dp)
                .background(if (selected) AppOrange else Color(0xFFD1D5DB), CircleShape),
        )
        Text(
            text = label,
            color = if (selected) AppOrange else Color(0xFF9CA3AF),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.clickable(onClick = onClick),
        )
    }
}

@Composable
private fun ScoreLine(
    title: String,
    value: Int,
    color: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, style = MaterialTheme.typography.bodySmall, color = AppMuted)
            Text("$value 分", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = color)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(Color(0xFFF3F4F6), RoundedCornerShape(8.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(value / 100f)
                    .height(8.dp)
                    .background(color, RoundedCornerShape(8.dp)),
            )
        }
    }
}

@Composable
private fun RadarLikeMetrics(
    metrics: List<Pair<String, Int>>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        metrics.forEach { (name, value) ->
            ScoreLine(name, value, AppOrange)
        }
    }
}

@Composable
private fun RankingRow(
    rank: String,
    name: String,
    score: Int,
    highlight: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (highlight) Color(0xFFFFF3E8) else Color.Transparent,
                RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(rank, modifier = Modifier.width(28.dp), color = if (highlight) AppOrange else AppMuted, fontWeight = FontWeight.Bold)
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(if (highlight) AppOrange.copy(alpha = 0.18f) else Color(0xFFF3F4F6), CircleShape),
        )
        Text(name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("$score 分", color = if (highlight) AppOrange else AppGreen, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TrendRow(
    title: String,
    summary: String,
    level: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(10.dp)
                .background(
                    when (level.lowercase()) {
                        "urgent", "high" -> AppRed
                        "medium" -> Color(0xFFD97706)
                        else -> AppGreen
                    },
                    CircleShape,
                ),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = AppMuted)
        }
    }
}

private fun riskBackground(response: AnalyzeResponse): Color = when (response.health_risk_assessment.level.lowercase()) {
    "urgent", "high" -> Color(0xFFFEF2F2)
    "medium" -> Color(0xFFFFF7ED)
    else -> Color(0xFFF0FDF4)
}

private fun riskTextColor(response: AnalyzeResponse): Color = when (response.health_risk_assessment.level.lowercase()) {
    "urgent", "high" -> AppRed
    "medium" -> Color(0xFFD97706)
    else -> AppGreen
}

private fun riskBadgeText(response: AnalyzeResponse): String = when (response.health_risk_assessment.level.lowercase()) {
    "urgent" -> "预警：紧急"
    "high" -> "预警：高风险"
    "medium" -> "提示：中风险"
    else -> "情绪：稳定"
}

private fun responseKey(response: AnalyzeResponse): String {
    return buildString {
        append(response.session_id)
        append(':')
        append(response.summary)
        append(':')
        append(response.health_risk_assessment.level)
    }
}

private fun requestPermissionsIfNeeded(
    requiredPermissions: List<String>,
    hasPermission: (String) -> Boolean,
    onRequest: (List<String>) -> Unit,
    onGranted: () -> Unit,
) {
    val missing = requiredPermissions.filterNot(hasPermission)
    if (missing.isEmpty()) {
        onGranted()
    } else {
        onRequest(missing)
    }
}
