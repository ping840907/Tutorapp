package com.tutorapp.fluenttutor.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.tutorapp.fluenttutor.model.MissionFinalSummary
import com.tutorapp.fluenttutor.model.MissionStatus
import com.tutorapp.fluenttutor.ui.theme.DangerRed
import com.tutorapp.fluenttutor.ui.theme.PrimaryBlue
import com.tutorapp.fluenttutor.ui.theme.SecondaryTeal
import com.tutorapp.fluenttutor.ui.theme.SuccessGreen
import com.tutorapp.fluenttutor.ui.theme.SurfaceDark
import com.tutorapp.fluenttutor.ui.theme.SurfaceVariantDark
import com.tutorapp.fluenttutor.ui.theme.TextMuted
import com.tutorapp.fluenttutor.ui.theme.TextPrimary
import com.tutorapp.fluenttutor.ui.theme.TextSecondary
import com.tutorapp.fluenttutor.ui.theme.WarningOrange

@Composable
fun MissionResultDialog(
    summary: MissionFinalSummary,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onChooseOtherScenario: () -> Unit
) {
    val isPassed = summary.status == MissionStatus.PASSED

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            border = androidx.compose.foundation.BorderStroke(
                1.5.dp,
                if (isPassed) SuccessGreen else DangerRed
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 狀態圖標圓圈
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(
                            if (isPassed) SuccessGreen.copy(alpha = 0.15f) else DangerRed.copy(alpha = 0.15f)
                        )
                        .border(
                            2.dp,
                            if (isPassed) SuccessGreen else DangerRed,
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isPassed) "🏆" else "⚠️",
                        fontSize = 36.sp
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 標題宣告
                Text(
                    text = if (isPassed) "MISSION PASSED!" else "MISSION FAILED",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (isPassed) SuccessGreen else DangerRed,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = if (isPassed) "目標順利達成！恭喜過關" else "完全偏離目標或超過回合上限",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 星星評定 (過關時展示)
                if (isPassed) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (i in 1..3) {
                            val isEarned = i <= summary.stars
                            Icon(
                                imageVector = if (isEarned) Icons.Default.Star else Icons.Default.StarOutline,
                                contentDescription = "Star $i",
                                tint = if (isEarned) Color(0xFFFFD700) else TextMuted,
                                modifier = Modifier
                                    .size(36.dp)
                                    .padding(horizontal = 4.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // 數據指標 (分數、回合、偏離次數)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceVariantDark)
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "整體得分", fontSize = 11.sp, color = TextMuted)
                        Text(
                            text = "${summary.overallScore}",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isPassed) SecondaryTeal else WarningOrange
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "目標完成", fontSize = 11.sp, color = TextMuted)
                        Text(
                            text = "${summary.completedGoalsCount}/${summary.totalGoalsCount}",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "偏離次數", fontSize = 11.sp, color = TextMuted)
                        Text(
                            text = "${summary.offTopicStrikes}/${summary.maxStrikes}",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (summary.offTopicStrikes > 0) DangerRed else SuccessGreen
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 總結回饋反思
                Text(
                    text = summary.summaryFeedback,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    textAlign = TextAlign.Center
                )

                // 改善提示
                if (summary.improvementTips.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Column(modifier = Modifier.fillMaxWidth()) {
                        summary.improvementTips.forEach { tip ->
                            Text(
                                text = tip,
                                fontSize = 12.sp,
                                color = TextSecondary,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 操作按鈕
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onChooseOtherScenario,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(text = "選擇場景", color = TextPrimary)
                    }

                    Button(
                        onClick = onRetry,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPassed) PrimaryBlue else WarningOrange
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Retry", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = if (isPassed) "再玩一次" else "重新挑戰", color = Color.White)
                    }
                }
            }
        }
    }
}
