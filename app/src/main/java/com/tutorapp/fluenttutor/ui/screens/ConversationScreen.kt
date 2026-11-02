package com.tutorapp.fluenttutor.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tutorapp.fluenttutor.ui.components.AudioControlPanel
import com.tutorapp.fluenttutor.ui.components.ChatBubble
import com.tutorapp.fluenttutor.ui.components.GoalProgressCard
import com.tutorapp.fluenttutor.ui.components.MissionResultDialog
import com.tutorapp.fluenttutor.ui.theme.DarkBackground
import com.tutorapp.fluenttutor.ui.theme.PrimaryBlue
import com.tutorapp.fluenttutor.ui.theme.SurfaceDark
import com.tutorapp.fluenttutor.ui.theme.TextMuted
import com.tutorapp.fluenttutor.ui.theme.TextPrimary
import com.tutorapp.fluenttutor.ui.viewmodel.TutorUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    uiState: TutorUiState,
    onBack: () -> Unit,
    onToggleRecording: () -> Unit,
    onToggleAutoVAD: (Boolean) -> Unit,
    onSendManualText: (String) -> Unit,
    onPlayAudio: (com.tutorapp.fluenttutor.model.ChatMessage) -> Unit,
    onRetryScenario: () -> Unit,
    onDismissDialog: () -> Unit
) {
    val listState = rememberLazyListState()

    // 新訊息自動滾動到底部
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
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
                            text = if (uiState.isMockMode) "⚡ Smart Simulator (Edge Mode)" else "🌐 LFM3-Audio Connected",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (uiState.isMockMode) PrimaryBlue else Color(0xFF22C55E)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
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
                amplitude = uiState.amplitude,
                isAutoVAD = uiState.isAutoVAD,
                onToggleRecording = onToggleRecording,
                onToggleAutoVAD = onToggleAutoVAD,
                onSendManualText = onSendManualText
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
                Spacer(modifier = Modifier.height(6.dp))

                // 任務目標與偏離警報卡片
                GoalProgressCard(
                    goals = uiState.goals,
                    offTopicStrikes = uiState.offTopicStrikes,
                    maxStrikes = uiState.currentScenario.maxOffTopicStrikes
                )

                Spacer(modifier = Modifier.height(10.dp))

                // 訊息列表
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

            // 推論中 Loading 指示器
            if (uiState.isConnectingLfm) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(SurfaceDark.copy(alpha = 0.9f))
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            color = PrimaryBlue,
                            strokeWidth = 2.dp,
                            modifier = Modifier.padding(end = 8.dp).height(16.dp).fillMaxWidth(0.1f)
                        )
                        Text(
                            text = "LFM3 Audio Processing...",
                            fontSize = 11.sp,
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
