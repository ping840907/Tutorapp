package com.tutorapp.fluenttutor.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tutorapp.fluenttutor.ui.theme.PrimaryBlue
import com.tutorapp.fluenttutor.ui.theme.SecondaryTeal
import kotlin.math.sin

/**
 * 動態即時音訊波形視覺化組件 (Dynamic Audio Waveform)
 */
@Composable
fun WaveformBar(
    amplitude: Float,
    isSpeaking: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 24,
    color: Color = PrimaryBlue
) {
    val infiniteTransition = rememberInfiniteTransition(label = "wave_anim")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(48.dp)) {
            val totalWidth = size.width
            val totalHeight = size.height
            val spacing = 6.dp.toPx()
            val barWidth = (totalWidth - (barCount - 1) * spacing) / barCount
            val midY = totalHeight / 2f

            for (i in 0 until barCount) {
                val x = i * (barWidth + spacing)
                val normalizedIndex = i.toFloat() / barCount
                // 波動計算
                val waveFactor = if (isSpeaking) {
                    val sine = ((sin((phase + normalizedIndex * 4f).toDouble()) + 1.0) / 2.0).toFloat()
                    (sine * 0.7f + amplitude * 1.5f).coerceIn(0.15f, 1.0f)
                } else {
                    0.08f + (amplitude * 0.5f)
                }

                val barHeight = (totalHeight * waveFactor).coerceIn(4.dp.toPx(), totalHeight)
                val topY = midY - barHeight / 2f

                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(PrimaryBlue, SecondaryTeal)
                    ),
                    topLeft = Offset(x, topY),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )
            }
        }
    }
}
