package com.tutorapp.fluenttutor.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tutorapp.fluenttutor.ui.components.AudioControlPanel
import com.tutorapp.fluenttutor.ui.components.ChatBubble
import com.tutorapp.fluenttutor.ui.components.GoalProgressCard
import com.tutorapp.fluenttutor.ui.components.MissionResultDialog
import com.tutorapp.fluenttutor.ui.components.NpcAvatarCard
import com.tutorapp.fluenttutor.ui.theme.DarkBackground
import com.tutorapp.fluenttutor.ui.theme.PrimaryBlue
import com.tutorapp.fluenttutor.ui.theme.SecondaryTeal
import com.tutorapp.fluenttutor.ui.theme.SurfaceDark
import com.tutorapp.fluenttutor.ui.theme.TextMuted
import com.tutorapp.fluenttutor.ui.theme.TextPrimary
import com.tutorapp.fluenttutor.ui.viewmodel.TutorUiState

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ConversationScreen(
    uiState: TutorUiState,
    onBack: () -> Unit,
    onEnterScreen: () -> Unit,
    onToggleRecording: () -> Unit,
    onToggleAutoVAD: (Boolean) -> Unit,
    onSendManualText: (String) -> Unit,
    onPlayAudio: (com.tutorapp.fluenttutor.model.ChatMessage) -> Unit,
    onReplayLastAiMessage: () -> Unit,
    onRetryScenario: () -> Unit,
    onDismissDialog: () -> Unit
) {
    val listState = rememberLazyListState()

    // 系統實體返回按鍵處理
    BackHandler(onBack = onBack)

    // 僅在使用者真正進入對話畫面時，才主動觸發 AI 的開場問候語 (具備防旋轉重複播放保護)
    LaunchedEffect(Unit) {
        onEnterScreen()
    }

    // 收到新對話訊息時平滑自動滾動
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    // 軟體鍵盤實時升起時自動滾動至最新訊息
    val isImeVisible = WindowInsets.isImeVisible
    LaunchedEffect(isImeVisible) {
        if (isImeVisible && uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = uiState.currentScenario.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "On-device S2S • RAM: ${uiState.realRamMb}MB • ${if (uiState.lastLatencyMs > 0) "${uiState.lastLatencyMs}ms" else "Ready"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = SecondaryTeal
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRetryScenario) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reset Scenario",
                            tint = TextMuted
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        },
        containerColor = DarkBackground,
        bottomBar = {
            AudioControlPanel(
                isRecording = uiState.isRecording,
                isUserSpeaking = uiState.isUserSpeaking,
                isAiSpeaking = uiState.isAiSpeaking,
                amplitude = uiState.activeAmplitude,
                isAutoVAD = uiState.isAutoVAD,
                onToggleRecording = onToggleRecording,
                onToggleAutoVAD = onToggleAutoVAD,
                onSendManualText = onSendManualText,
                onReplayLastAiMessage = onReplayLastAiMessage
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp)
            ) {
                Spacer(modifier = Modifier.height(4.dp))

                // 動態 NPC 講話頭像與表情狀態卡片
                NpcAvatarCard(
                    roleName = uiState.currentScenario.roleName,
                    avatarStyle = uiState.currentScenario.avatarStyle,
                    interactionState = uiState.npcInteractionState,
                    emotion = uiState.npcEmotion,
                    voiceAmplitude = uiState.activeAmplitude,
                    lastEvaluateFeedback = uiState.lastEvaluationFeedback
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 任務目標進度與偏離 Strike 卡片
                GoalProgressCard(
                    goals = uiState.goals,
                    offTopicStrikes = uiState.offTopicStrikes,
                    maxStrikes = uiState.currentScenario.maxOffTopicStrikes
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 對話訊息滾動清單
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(uiState.messages, key = { it.id }) { message ->
                        ChatBubble(
                            message = message,
                            onPlayAudio = onPlayAudio
                        )
                    }
                }
            }

            // SM8850 S2S 推論中指示器 (修正原本變形的圓形進度條尺寸)
            if (uiState.isProcessing) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(SurfaceDark.copy(alpha = 0.95f))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            color = PrimaryBlue,
                            strokeWidth = 2.dp,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(16.dp)
                        )
                        Text(
                            text = "Thinking...",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = PrimaryBlue
                        )
                    }
                }
            }

            // 結算彈窗 (過關 / 失敗)
            uiState.finalSummary?.let { summary ->
                MissionResultDialog(
                    summary = summary,
                    onRetry = onRetryScenario,
                    onDismiss = onDismissDialog,
                    onChooseOtherScenario = {
                        onDismissDialog()
                        onBack()
                    }
                )
            }
        }
    }
}
