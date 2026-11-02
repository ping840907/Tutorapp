package com.tutorapp.fluenttutor.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tutorapp.fluenttutor.model.MissionGoal
import com.tutorapp.fluenttutor.ui.theme.DangerRed
import com.tutorapp.fluenttutor.ui.theme.PrimaryBlue
import com.tutorapp.fluenttutor.ui.theme.SuccessGreen
import com.tutorapp.fluenttutor.ui.theme.SurfaceDark
import com.tutorapp.fluenttutor.ui.theme.SurfaceVariantDark
import com.tutorapp.fluenttutor.ui.theme.TextMuted
import com.tutorapp.fluenttutor.ui.theme.TextPrimary
import com.tutorapp.fluenttutor.ui.theme.TextSecondary
import com.tutorapp.fluenttutor.ui.theme.WarningOrange

@Composable
fun GoalProgressCard(
    goals: List<MissionGoal>,
    offTopicStrikes: Int,
    maxStrikes: Int,
    modifier: Modifier = Modifier
) {
    val completedCount = goals.count { it.isCompleted }
    val progress = if (goals.isNotEmpty()) completedCount.toFloat() / goals.size else 0f

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
            .border(1.dp, SurfaceVariantDark, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        // 標題列與進度比
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "🎯 Mission Goals",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "$completedCount/${goals.size}",
                    fontSize = 13.sp,
                    color = PrimaryBlue,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // 偏離警告 Strike 計數
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (offTopicStrikes > 0) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Warning Strikes",
                        tint = if (offTopicStrikes >= maxStrikes - 1) DangerRed else WarningOrange,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Strikes: $offTopicStrikes/$maxStrikes",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (offTopicStrikes >= maxStrikes - 1) DangerRed else WarningOrange
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 進度條
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = if (progress >= 1f) SuccessGreen else PrimaryBlue,
            trackColor = SurfaceVariantDark
        )

        Spacer(modifier = Modifier.height(10.dp))

        // 子目標清單
        goals.forEach { goal ->
            val iconBg by animateColorAsState(
                targetValue = if (goal.isCompleted) SuccessGreen else SurfaceVariantDark,
                animationSpec = tween(400),
                label = "goal_bg"
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(iconBg),
                    contentAlignment = Alignment.Center
                ) {
                    if (goal.isCompleted) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Completed",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(TextMuted)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    Text(
                        text = goal.title,
                        fontSize = 13.sp,
                        fontWeight = if (goal.isCompleted) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (goal.isCompleted) TextPrimary else TextSecondary
                    )
                }
            }
        }
    }
}
