package com.tutorapp.fluenttutor.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tutorapp.fluenttutor.ui.theme.BubbleUser
import com.tutorapp.fluenttutor.ui.theme.DangerRed
import com.tutorapp.fluenttutor.ui.theme.PrimaryBlue
import com.tutorapp.fluenttutor.ui.theme.SecondaryTeal
import com.tutorapp.fluenttutor.ui.theme.SurfaceDark
import com.tutorapp.fluenttutor.ui.theme.SurfaceVariantDark
import com.tutorapp.fluenttutor.ui.theme.TextMuted
import com.tutorapp.fluenttutor.ui.theme.TextPrimary
import com.tutorapp.fluenttutor.ui.theme.TextSecondary

@Composable
fun AudioControlPanel(
    isRecording: Boolean,
    isUserSpeaking: Boolean,
    isAiSpeaking: Boolean,
    amplitude: Float,
    isAutoVAD: Boolean,
    onToggleRecording: () -> Unit,
    onToggleAutoVAD: (Boolean) -> Unit,
    onSendManualText: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var textInput by remember { mutableStateOf("") }
    var isTextInputExpanded by remember { mutableStateOf(false) }

    val micButtonColor by animateColorAsState(
        targetValue = when {
            isRecording && isUserSpeaking -> SecondaryTeal
            isRecording -> DangerRed
            else -> PrimaryBlue
        },
        label = "mic_btn_color"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(SurfaceDark)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 波形視覺化條
        WaveformBar(
            amplitude = amplitude,
            isSpeaking = isUserSpeaking || isAiSpeaking,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // 狀態描述指示標籤
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isAiSpeaking -> PrimaryBlue
                                isUserSpeaking -> SecondaryTeal
                                isRecording -> DangerRed
                                else -> TextMuted
                            }
                        )
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = when {
                        isAiSpeaking -> "AI 正在回話..."
                        isUserSpeaking -> "偵測到語音輸入 (VAD 活躍)"
                        isRecording -> "聆聽中 (自動斷句分段發送)"
                        else -> "點擊麥克風開始即時對話"
                    },
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }

            // VAD 自動切片模式切換
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "即時分段VAD", fontSize = 11.sp, color = TextMuted)
                Spacer(modifier = Modifier.width(4.dp))
                Switch(
                    checked = isAutoVAD,
                    onCheckedChange = onToggleAutoVAD,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = PrimaryBlue,
                        checkedTrackColor = PrimaryBlue.copy(alpha = 0.4f),
                        uncheckedThumbColor = TextMuted,
                        uncheckedTrackColor = SurfaceVariantDark
                    ),
                    modifier = Modifier.height(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 麥克風大按鈕與模式切換
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 文字鍵盤輸入展開按鈕
            IconButton(
                onClick = { isTextInputExpanded = !isTextInputExpanded },
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(SurfaceVariantDark)
            ) {
                Text(text = "⌨️", fontSize = 18.sp)
            }

            // 核心麥克風按鈕
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .shadow(12.dp, CircleShape)
                    .clip(CircleShape)
                    .background(micButtonColor)
                    .clickable { onToggleRecording() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Default.Mic else Icons.Default.MicOff,
                    contentDescription = "Microphone",
                    tint = Color.White,
                    modifier = Modifier.size(34.dp)
                )
            }

            // 喇叭測試按鈕
            IconButton(
                onClick = { /* 提示 */ },
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(SurfaceVariantDark)
            ) {
                Icon(
                    imageVector = Icons.Default.VolumeUp,
                    contentDescription = "Audio feedback",
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // 展開的文字輸入區 (備用輸入)
        if (isTextInputExpanded) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    placeholder = { Text("模擬或輸入發音內容...", fontSize = 13.sp, color = TextMuted) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryBlue,
                        unfocusedBorderColor = SurfaceVariantDark,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (textInput.isNotBlank()) {
                            onSendManualText(textInput)
                            textInput = ""
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(BubbleUser)
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "Send",
                        tint = Color.White
                    )
                }
            }
        }
    }
}
