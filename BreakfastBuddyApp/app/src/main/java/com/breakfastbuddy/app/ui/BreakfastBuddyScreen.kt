package com.breakfastbuddy.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.breakfastbuddy.app.detector.ChewingEngine
import com.breakfastbuddy.app.detector.MonitoringConfig
import com.breakfastbuddy.app.detector.MonitoringPhase
import com.breakfastbuddy.app.detector.MonitoringUiState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BreakfastBuddyScreen() {
    val context = LocalContext.current
    val engine = remember { ChewingEngine() }

    var config by remember { mutableStateOf(MonitoringConfig()) }
    var uiState by remember { mutableStateOf(engine.getCurrentState()) }
    var urlInput by remember { mutableStateOf("https://www.youtube.com/watch?v=e_04ZrNroTo") }
    var activeVideoId by remember { mutableStateOf(extractYouTubeVideoId(urlInput)) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    // 初始化繁體中文語音提醒 (TTS)
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(context) {
        var instance: TextToSpeech? = null
        instance = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                instance?.language = Locale.TRADITIONAL_CHINESE
            }
        }
        tts = instance
        onDispose {
            instance.stop()
            instance.shutdown()
        }
    }

    // 觸發語音提醒
    LaunchedEffect(uiState.speechEventId) {
        val msg = uiState.speechMessage
        if (uiState.speechEventId > 0L && !msg.isNullOrBlank() && config.voiceReminderEnabled) {
            tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "reminder_${uiState.speechEventId}")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "早餐咀嚼小幫手",
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                actions = {
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "家長設定")
                    }
                }
            )
        }
    ) { innerPadding ->
        // 響應式佈局：平板/橫向 (width >= 720dp) 使用雙欄排版；手機/直向使用上下排版
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(12.dp)
        ) {
            val isTabletOrLandscape = maxWidth >= 720.dp

            if (isTabletOrLandscape) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 左側主區域 (70%)：YouTube 播放器 + 暫停提醒遮罩 + 網址列
                    Column(
                        modifier = Modifier
                            .weight(0.68f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        VideoUrlBar(
                            urlInput = urlInput,
                            onUrlChange = { urlInput = it },
                            onLoadClick = { activeVideoId = extractYouTubeVideoId(urlInput) }
                        )
                        VideoStageWithOverlay(
                            videoId = activeVideoId,
                            uiState = uiState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                    }

                    // 右側監測控制欄 (32%)：前置鏡頭預覽 + 進食統計 + 控制按鈕
                    Column(
                        modifier = Modifier
                            .weight(0.32f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CameraPanel(
                            hasPermission = hasCameraPermission,
                            onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            uiState = uiState,
                            onObservation = { obs ->
                                uiState = engine.processFrame(obs, config)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                        )
                        StatusDashboardCard(uiState = uiState)
                        ControlButtonsPanel(
                            uiState = uiState,
                            onToggleMonitor = {
                                uiState = if (uiState.isMonitoring) {
                                    engine.stopMonitoring()
                                } else {
                                    engine.startMonitoring(System.currentTimeMillis(), config)
                                }
                            },
                            onManualBite = {
                                uiState = engine.markManualBite(System.currentTimeMillis())
                            },
                            onResetPenalty = {
                                uiState = engine.resetPenaltyCounter()
                            }
                        )
                    }
                }
            } else {
                // 手機直向佈局
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    VideoUrlBar(
                        urlInput = urlInput,
                        onUrlChange = { urlInput = it },
                        onLoadClick = { activeVideoId = extractYouTubeVideoId(urlInput) }
                    )
                    VideoStageWithOverlay(
                        videoId = activeVideoId,
                        uiState = uiState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CameraPanel(
                            hasPermission = hasCameraPermission,
                            onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            uiState = uiState,
                            onObservation = { obs ->
                                uiState = engine.processFrame(obs, config)
                            },
                            modifier = Modifier
                                .weight(0.45f)
                                .height(165.dp)
                        )
                        Box(modifier = Modifier.weight(0.55f)) {
                            StatusDashboardCard(uiState = uiState)
                        }
                    }
                    ControlButtonsPanel(
                        uiState = uiState,
                        onToggleMonitor = {
                            uiState = if (uiState.isMonitoring) {
                                engine.stopMonitoring()
                            } else {
                                engine.startMonitoring(System.currentTimeMillis(), config)
                            }
                        },
                        onManualBite = {
                            uiState = engine.markManualBite(System.currentTimeMillis())
                        },
                        onResetPenalty = {
                            uiState = engine.resetPenaltyCounter()
                        }
                    )
                }
            }
        }
    }

    if (showSettingsDialog) {
        ParentSettingsDialog(
            config = config,
            onDismiss = { showSettingsDialog = false },
            onApply = { newConfig ->
                config = newConfig
                showSettingsDialog = false
            }
        )
    }
}

@Composable
private fun VideoUrlBar(
    urlInput: String,
    onUrlChange: (String) -> Unit,
    onLoadClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = urlInput,
            onValueChange = onUrlChange,
            label = { Text("貼上 YouTube 影片網址或 ID") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        Button(
            onClick = onLoadClick,
            modifier = Modifier.height(54.dp)
        ) {
            Text("載入")
        }
    }
}

@Composable
private fun VideoStageWithOverlay(
    videoId: String,
    uiState: MonitoringUiState,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            YouTubePlayerView(
                videoId = videoId,
                shouldPlay = uiState.shouldPlayVideo,
                modifier = Modifier.fillMaxSize()
            )

            // 暫停時的醒目溫馨提醒覆蓋層
            AnimatedVisibility(
                visible = uiState.isMonitoring && !uiState.shouldPlayVideo,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                val overlayColor = when (uiState.phase) {
                    MonitoringPhase.PAUSED_FAKE_CHEWING -> Color(0xE6B71C1C)
                    MonitoringPhase.PAUSED_STOPPED_CHEWING -> Color(0xE6E65100)
                    else -> Color(0xDD1565C0)
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(overlayColor)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = uiState.statusTitle,
                            color = Color.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.ExtraBold,
                            textAlign = TextAlign.Center
                        )

                        if (uiState.penaltyRemainingSec > 0 && uiState.currentPenaltyTotalSec > 0) {
                            Box(contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(
                                    progress = {
                                        (uiState.penaltyRemainingSec.toFloat() / uiState.currentPenaltyTotalSec.toFloat())
                                            .coerceIn(0f, 1f)
                                    },
                                    modifier = Modifier.size(92.dp),
                                    color = Color(0xFFFFEE58),
                                    trackColor = Color.White.copy(alpha = 0.25f),
                                    strokeWidth = 8.dp
                                )
                                Text(
                                    text = "${uiState.penaltyRemainingSec}s",
                                    color = Color.White,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Text(
                            text = uiState.reminderMessage ?: uiState.statusDetail,
                            color = Color.White,
                            fontSize = 18.sp,
                            textAlign = TextAlign.Center
                        )

                        if (uiState.consecutiveForgetCount > 0) {
                            Text(
                                text = "已忘記咀嚼 ${uiState.consecutiveForgetCount} 次（本次強制暫停 ${uiState.currentPenaltyTotalSec} 秒）",
                                color = Color(0xFFFFF59D),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraPanel(
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    uiState: MonitoringUiState,
    onObservation: (com.breakfastbuddy.app.detector.FrameObservation) -> Unit,
    modifier: Modifier = Modifier
) {
    if (hasPermission) {
        CameraPreviewCard(
            faceDetected = uiState.faceDetected,
            isChewingNow = uiState.isChewingNow,
            handNearMouth = uiState.handNearMouth,
            onObservation = onObservation,
            modifier = modifier
        )
    } else {
        Card(
            modifier = modifier,
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("需要前置鏡頭權限以偵測咀嚼", textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(10.dp))
                Button(onClick = onRequestPermission) {
                    Text("開啟攝影機權限")
                }
            }
        }
    }
}

@Composable
private fun StatusDashboardCard(uiState: MonitoringUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = uiState.statusTitle,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Text(
                text = uiState.statusDetail,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("已吃口數：${uiState.verifiedBiteCount} 口", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text("忘記次數：${uiState.consecutiveForgetCount} 次", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            Text(
                text = "本口已咀嚼：${uiState.chewsSinceLastBite} 下",
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun ControlButtonsPanel(
    uiState: MonitoringUiState,
    onToggleMonitor: () -> Unit,
    onManualBite: () -> Unit,
    onResetPenalty: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onToggleMonitor,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (uiState.isMonitoring) Color(0xFFD32F2F) else Color(0xFF2E7D32)
            )
        ) {
            Icon(
                imageVector = if (uiState.isMonitoring) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (uiState.isMonitoring) "停止監測" else "開始早餐監測",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = onManualBite,
                enabled = uiState.isMonitoring,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Icon(Icons.Default.Restaurant, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("手動記一口", fontSize = 13.sp)
            }

            FilledTonalButton(
                onClick = onResetPenalty,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("重置延長時間", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ParentSettingsDialog(
    config: MonitoringConfig,
    onDismiss: () -> Unit,
    onApply: (MonitoringConfig) -> Unit
) {
    var stopTimeout by remember { mutableStateOf(config.stopChewTimeoutSec.toFloat()) }
    var basePenalty by remember { mutableStateOf(config.basePenaltySec.toFloat()) }
    var biteValidity by remember { mutableStateOf(config.biteValiditySec.toFloat()) }
    var antiFake by remember { mutableStateOf(config.requireNewBiteToPreventFake) }
    var voiceEnabled by remember { mutableStateOf(config.voiceReminderEnabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("家長監測設定", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("停止咀嚼幾秒後暫停影片：${stopTimeout.toInt()} 秒")
                Slider(
                    value = stopTimeout,
                    onValueChange = { stopTimeout = it },
                    valueRange = 3f..15f
                )

                Text("第一次忘記咀嚼的基礎暫停秒數：${basePenalty.toInt()} 秒（後續會倍數延長）")
                Slider(
                    value = basePenalty,
                    onValueChange = { basePenalty = it },
                    valueRange = 3f..15f
                )

                Text("每口食物最長有效咀嚼時間：${biteValidity.toInt()} 秒（逾時需吃新一口）")
                Slider(
                    value = biteValidity,
                    onValueChange = { biteValidity = it },
                    valueRange = 15f..60f
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("防假裝空咬（需偵測新食物入口）", modifier = Modifier.weight(1f))
                    Switch(checked = antiFake, onCheckedChange = { antiFake = it })
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("開啟中文語音提醒", modifier = Modifier.weight(1f))
                    Switch(checked = voiceEnabled, onCheckedChange = { voiceEnabled = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onApply(
                        config.copy(
                            stopChewTimeoutSec = stopTimeout.toInt(),
                            basePenaltySec = basePenalty.toInt(),
                            biteValiditySec = biteValidity.toInt(),
                            requireNewBiteToPreventFake = antiFake,
                            voiceReminderEnabled = voiceEnabled
                        )
                    )
                }
            ) {
                Text("套用")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
