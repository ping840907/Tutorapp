package com.tutorapp.fluenttutor.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tutorapp.fluenttutor.model.ChatMessage
import com.tutorapp.fluenttutor.model.MessageSender
import com.tutorapp.fluenttutor.ui.theme.BubbleAi
import com.tutorapp.fluenttutor.ui.theme.BubbleUser
import com.tutorapp.fluenttutor.ui.theme.DangerRed
import com.tutorapp.fluenttutor.ui.theme.PrimaryBlue
import com.tutorapp.fluenttutor.ui.theme.SurfaceVariantDark
import com.tutorapp.fluenttutor.ui.theme.TextMuted
import com.tutorapp.fluenttutor.ui.theme.TextPrimary
import com.tutorapp.fluenttutor.ui.theme.TextSecondary
import com.tutorapp.fluenttutor.ui.theme.WarningOrange

@Composable
fun ChatBubble(
    message: ChatMessage,
    onPlayAudio: (ChatMessage) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isUser = message.sender == MessageSender.USER
    val isSystem = message.sender == MessageSender.SYSTEM

    if (isSystem) {
        // 系統通知 (例如提示、過關宣告)
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceVariantDark)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
        return
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!isUser) {
            // AI 頭像
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(PrimaryBlue),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "AI", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            Box(
                modifier = Modifier
                    .clip(
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isUser) 16.dp else 4.dp,
                            bottomEnd = if (isUser) 4.dp else 16.dp
                        )
                    )
                    .background(
                        when {
                            message.isOffTopic -> DangerRed.copy(alpha = 0.2f)
                            isUser -> BubbleUser
                            else -> BubbleAi
                        }
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Column {
                    // 若有偏離主題警告
                    if (message.isOffTopic) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Off Topic Warning",
                                tint = WarningOrange,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = message.offTopicReason ?: "Off-topic detected",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = WarningOrange
                            )
                        }
                    }

                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary
                    )

                    if (!isUser) {
                        // 播放按鈕
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { onPlayAudio(message) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Play voice",
                                    tint = PrimaryBlue,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 附屬資訊 (說話時長)
            if (message.audioDurationMs > 0) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${message.audioDurationMs}ms audio",
                    fontSize = 10.sp,
                    color = TextMuted
                )
            }
        }

        if (isUser) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(SurfaceVariantDark),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "ME", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 11.sp)
            }
        }
    }
}
