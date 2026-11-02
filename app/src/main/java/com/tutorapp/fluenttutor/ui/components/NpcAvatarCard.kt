package com.tutorapp.fluenttutor.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tutorapp.fluenttutor.model.NpcAvatarStyle
import com.tutorapp.fluenttutor.model.NpcEmotion
import com.tutorapp.fluenttutor.model.NpcInteractionState
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
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 動態 NPC 角色頭像與狀態卡片
 * 結合：
 * 1. 即時嘴型同步動畫 (Lip-Sync driven by speech amplitude & syllable cadence)
 * 2. 5 種情緒臉部表情 (Welcoming, Encouraging, Impressed/Passed, Confused/Off-Topic, Disappointed/Failed)
 * 3. 自然眨眼、呼吸起伏、傾聽點頭、思考眼神偏移與歪頭動作
 * 4. 場景專屬造型 (咖啡師 Sarah、飯店禮賓 David、地勤 Elena)
 */
@Composable
fun NpcAvatarCard(
    roleName: String,
    avatarStyle: NpcAvatarStyle,
    interactionState: NpcInteractionState,
    emotion: NpcEmotion,
    voiceAmplitude: Float,
    lastEvaluateFeedback: String?,
    modifier: Modifier = Modifier
) {
    val accentColor = Color(avatarStyle.primaryColorHex)
    val secondaryColor = Color(avatarStyle.secondaryColorHex)

    val statusColor by animateColorAsState(
        targetValue = when (interactionState) {
            NpcInteractionState.SPEAKING -> PrimaryBlue
            NpcInteractionState.LISTENING_ACTIVE -> SecondaryTeal
            NpcInteractionState.THINKING -> Color(0xFFA855F7)
            NpcInteractionState.LISTENING_IDLE -> SuccessGreen
            NpcInteractionState.IDLE -> TextMuted
        },
        animationSpec = tween(300),
        label = "status_color"
    )

    val emotionColor by animateColorAsState(
        targetValue = when (emotion) {
            NpcEmotion.EXCITED_PROUD -> SuccessGreen
            NpcEmotion.CONFUSED_WARNING -> WarningOrange
            NpcEmotion.DISAPPOINTED_FAILED -> DangerRed
            NpcEmotion.HAPPY_ENCOURAGING -> SecondaryTeal
            NpcEmotion.WELCOMING -> PrimaryBlue
        },
        animationSpec = tween(350),
        label = "emotion_color"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "$roleName, status: ${interactionState.statusLabel}, emotion: ${emotion.label}"
            }
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        SurfaceDark,
                        SurfaceDark.copy(alpha = 0.92f),
                        accentColor.copy(alpha = 0.12f)
                    )
                )
            )
            .border(
                width = 1.5.dp,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        emotionColor.copy(alpha = 0.65f),
                        SurfaceVariantDark
                    )
                ),
                shape = RoundedCornerShape(20.dp)
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左側：動態 Canvas 繪製的 NPC 角色頭像
        Box(
            modifier = Modifier.size(104.dp),
            contentAlignment = Alignment.Center
        ) {
            AnimatedNpcAvatarCanvas(
                avatarStyle = avatarStyle,
                interactionState = interactionState,
                emotion = emotion,
                voiceAmplitude = voiceAmplitude,
                modifier = Modifier.fillMaxSize()
            )

            // 右下角情緒小徽章
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(SurfaceDark)
                    .border(1.5.dp, emotionColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(text = emotion.emoji, fontSize = 14.sp)
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        // 右側：NPC 角色資訊、即時狀態與情緒反饋
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            // 角色名稱與職稱標籤
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = roleName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 職業徽章與情緒膠囊
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(accentColor.copy(alpha = 0.16f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = avatarStyle.roleBadge,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = accentColor
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(emotionColor.copy(alpha = 0.16f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "${emotion.emoji} ${emotion.label}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = emotionColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 即時互動狀態指示燈 (Speaking / Listening / Thinking)
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = interactionState.statusLabel,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = statusColor
                )
            }

            // 最新判定回饋提示
            if (!lastEvaluateFeedback.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = lastEvaluateFeedback,
                    fontSize = 11.sp,
                    color = TextSecondary,
                    maxLines = 2,
                    lineHeight = 14.sp,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 純 Compose Canvas 向量繪製的動態 NPC 頭像
 */
@Composable
fun AnimatedNpcAvatarCanvas(
    avatarStyle: NpcAvatarStyle,
    interactionState: NpcInteractionState,
    emotion: NpcEmotion,
    voiceAmplitude: Float,
    modifier: Modifier = Modifier
) {
    // 1. 連續動畫時間軸 (呼吸、說話音節擺動、思考光環)
    val infiniteTransition = rememberInfiniteTransition(label = "npc_avatar_infinite")

    val breathPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "breath_phase"
    )

    val speechPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 480, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "speech_phase"
    )

    val auraPulse by infiniteTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "aura_pulse"
    )

    // 2. 自然隨機眨眼計時器
    var isBlinking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2900L)
            isBlinking = true
            delay(130L)
            isBlinking = false
            delay(1800L)
            // 偶爾雙眨眼增加真實生命感
            isBlinking = true
            delay(110L)
            isBlinking = false
            delay(140L)
            isBlinking = true
            delay(110L)
            isBlinking = false
        }
    }

    // 3. 情緒與互動狀態平滑補間動畫參數
    val targetHeadTilt = when {
        emotion == NpcEmotion.CONFUSED_WARNING -> 9f
        interactionState == NpcInteractionState.LISTENING_ACTIVE -> -4f
        interactionState == NpcInteractionState.THINKING -> 5f
        emotion == NpcEmotion.DISAPPOINTED_FAILED -> -3f
        else -> 0f
    }
    val headTiltDegrees by animateFloatAsState(
        targetValue = targetHeadTilt,
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "head_tilt"
    )

    // 眼神偏移 (思考時往右上看，失望時往下看，說話與傾聽時專注正前方)
    val targetPupilOffsetX = when (interactionState) {
        NpcInteractionState.THINKING -> 3.5f
        else -> 0f
    }
    val targetPupilOffsetY = when {
        interactionState == NpcInteractionState.THINKING -> -3.5f
        emotion == NpcEmotion.DISAPPOINTED_FAILED -> 2.5f
        else -> 0f
    }
    val pupilOffsetX by animateFloatAsState(targetValue = targetPupilOffsetX, animationSpec = tween(300), label = "pupil_x")
    val pupilOffsetY by animateFloatAsState(targetValue = targetPupilOffsetY, animationSpec = tween(300), label = "pupil_y")

    // 眉毛角度 (左眉與右眉獨立控制，支援困惑高低眉與難過八字眉)
    val targetLeftBrowAngle = when (emotion) {
        NpcEmotion.CONFUSED_WARNING -> 14f     // 左眉壓低皺眉
        NpcEmotion.DISAPPOINTED_FAILED -> -14f // 八字眉下垂
        NpcEmotion.EXCITED_PROUD -> -6f
        else -> -3f
    }
    val targetRightBrowAngle = when (emotion) {
        NpcEmotion.CONFUSED_WARNING -> -16f    // 右眉高高挑起 (高低眉)
        NpcEmotion.DISAPPOINTED_FAILED -> 14f  // 八字眉下垂
        NpcEmotion.EXCITED_PROUD -> 6f
        else -> 3f
    }
    val leftBrowAngle by animateFloatAsState(targetValue = targetLeftBrowAngle, animationSpec = tween(350), label = "left_brow")
    val rightBrowAngle by animateFloatAsState(targetValue = targetRightBrowAngle, animationSpec = tween(350), label = "right_brow")

    val targetBrowLift = when {
        emotion == NpcEmotion.EXCITED_PROUD -> -4f
        interactionState == NpcInteractionState.LISTENING_ACTIVE -> -2.5f
        emotion == NpcEmotion.DISAPPOINTED_FAILED -> 2f
        else -> 0f
    }
    val browLift by animateFloatAsState(targetValue = targetBrowLift, animationSpec = tween(300), label = "brow_lift")

    val primaryColor = Color(avatarStyle.primaryColorHex)
    val secondaryColor = Color(avatarStyle.secondaryColorHex)
    val hairColor = Color(avatarStyle.hairColorHex)
    val skinColor = Color(avatarStyle.skinColorHex)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val center = Offset(w / 2f, h / 2f)
        val radius = minOf(w, h) / 2f

        // --- A. 繪製背景氛圍光環與互動波紋 ---
        drawStatusAura(
            center = center,
            baseRadius = radius * 0.88f,
            interactionState = interactionState,
            emotion = emotion,
            primaryColor = primaryColor,
            voiceAmplitude = voiceAmplitude,
            auraPulse = auraPulse,
            speechPhase = speechPhase
        )

        // 裁切圓形頭像基底背景
        drawCircle(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF1E293B),
                    primaryColor.copy(alpha = 0.28f)
                )
            ),
            radius = radius * 0.84f,
            center = center
        )

        // 計算呼吸與說話/傾聽點頭的垂直位移
        val breathBob = sin(breathPhase) * 2.2f
        val nodOffset = when (interactionState) {
            NpcInteractionState.LISTENING_ACTIVE -> sin(speechPhase) * 2.5f
            NpcInteractionState.SPEAKING -> sin(speechPhase * 0.5f) * 1.8f
            else -> 0f
        }

        // --- B. 繪製肩膀與職業服裝 ---
        translate(left = 0f, top = breathBob * 0.6f) {
            drawNpcOutfit(
                w = w,
                h = h,
                avatarStyle = avatarStyle,
                primaryColor = primaryColor,
                secondaryColor = secondaryColor,
                skinColor = skinColor
            )
        }

        // --- C. 繪製頭部、髮型與五官表情 (跟隨歪頭與點頭動畫) ---
        translate(left = 0f, top = breathBob + nodOffset) {
            rotate(degrees = headTiltDegrees, pivot = Offset(w * 0.5f, h * 0.52f)) {
                // 後層頭髮
                drawBackHair(w, h, avatarStyle, hairColor)

                // 耳朵
                val earY = h * 0.49f
                drawCircle(color = skinColor, radius = w * 0.055f, center = Offset(w * 0.27f, earY))
                drawCircle(color = skinColor, radius = w * 0.055f, center = Offset(w * 0.73f, earY))

                // 臉部輪廓 (圓潤親和的現代角色臉型)
                val faceWidth = w * 0.44f
                val faceHeight = h * 0.48f
                val faceLeft = (w - faceWidth) / 2f
                val faceTop = h * 0.24f
                drawRoundRect(
                    color = skinColor,
                    topLeft = Offset(faceLeft, faceTop),
                    size = Size(faceWidth, faceHeight),
                    cornerRadius = CornerRadius(faceWidth * 0.46f, faceHeight * 0.46f)
                )

                // 雙頰紅暈 (開心或激動時更明顯)
                val blushAlpha = if (emotion == NpcEmotion.EXCITED_PROUD || emotion == NpcEmotion.HAPPY_ENCOURAGING) 0.35f else 0.18f
                drawOval(
                    color = Color(0xFFF43F5E).copy(alpha = blushAlpha),
                    topLeft = Offset(w * 0.31f, h * 0.50f),
                    size = Size(w * 0.08f, h * 0.045f)
                )
                drawOval(
                    color = Color(0xFFF43F5E).copy(alpha = blushAlpha),
                    topLeft = Offset(w * 0.61f, h * 0.50f),
                    size = Size(w * 0.08f, h * 0.045f)
                )

                // 眼睛與瞳孔
                drawNpcEyes(
                    w = w,
                    h = h,
                    isBlinking = isBlinking,
                    emotion = emotion,
                    pupilOffsetX = pupilOffsetX,
                    pupilOffsetY = pupilOffsetY
                )

                // 眉毛
                drawNpcEyebrows(
                    w = w,
                    h = h,
                    hairColor = hairColor,
                    leftBrowAngle = leftBrowAngle,
                    rightBrowAngle = rightBrowAngle,
                    browLift = browLift
                )

                // 鼻子微陰影
                drawRoundRect(
                    color = Color(0xFFD9A07E),
                    topLeft = Offset(w * 0.488f, h * 0.47f),
                    size = Size(w * 0.024f, h * 0.045f),
                    cornerRadius = CornerRadius(4f, 4f)
                )

                // 嘴巴 (動態語音 Lip-Sync 與情緒嘴型)
                drawNpcMouth(
                    w = w,
                    h = h,
                    interactionState = interactionState,
                    emotion = emotion,
                    voiceAmplitude = voiceAmplitude,
                    speechPhase = speechPhase
                )

                // 前層瀏海與頭飾
                drawFrontHairAndAccessory(
                    w = w,
                    h = h,
                    avatarStyle = avatarStyle,
                    hairColor = hairColor,
                    primaryColor = primaryColor,
                    secondaryColor = secondaryColor
                )
            }
        }

        // --- D. 繪製外層特效 (思考粒子 / 讚賞閃星 / 困惑問號) ---
        drawEmotionOverlays(
            w = w,
            h = h,
            interactionState = interactionState,
            emotion = emotion,
            breathPhase = breathPhase,
            speechPhase = speechPhase
        )
    }
}

private fun DrawScope.drawStatusAura(
    center: Offset,
    baseRadius: Float,
    interactionState: NpcInteractionState,
    emotion: NpcEmotion,
    primaryColor: Color,
    voiceAmplitude: Float,
    auraPulse: Float,
    speechPhase: Float
) {
    val ringColor = when {
        emotion == NpcEmotion.CONFUSED_WARNING -> WarningOrange
        emotion == NpcEmotion.DISAPPOINTED_FAILED -> DangerRed
        interactionState == NpcInteractionState.SPEAKING -> PrimaryBlue
        interactionState == NpcInteractionState.LISTENING_ACTIVE -> SecondaryTeal
        interactionState == NpcInteractionState.THINKING -> Color(0xFFA855F7)
        else -> primaryColor
    }

    // 說話或傾聽時產生聲波擴散外環
    if (interactionState == NpcInteractionState.SPEAKING || interactionState == NpcInteractionState.LISTENING_ACTIVE) {
        val dynamicBoost = (voiceAmplitude * 0.16f).coerceIn(0.02f, 0.18f)
        val outerR = baseRadius * (0.98f + dynamicBoost + 0.04f * sin(speechPhase))
        drawCircle(
            color = ringColor.copy(alpha = 0.25f),
            radius = outerR,
            center = center
        )
        drawCircle(
            color = ringColor.copy(alpha = 0.65f),
            radius = baseRadius * 0.90f,
            center = center,
            style = Stroke(width = 3.dp.toPx())
        )
    } else {
        drawCircle(
            color = ringColor.copy(alpha = 0.35f),
            radius = baseRadius * 0.88f * auraPulse,
            center = center,
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

private fun DrawScope.drawNpcOutfit(
    w: Float,
    h: Float,
    avatarStyle: NpcAvatarStyle,
    primaryColor: Color,
    secondaryColor: Color,
    skinColor: Color
) {
    // 脖子
    drawRoundRect(
        color = skinColor.copy( red = skinColor.red * 0.93f, green = skinColor.green * 0.91f, blue = skinColor.blue * 0.90f),
        topLeft = Offset(w * 0.43f, h * 0.66f),
        size = Size(w * 0.14f, h * 0.14f),
        cornerRadius = CornerRadius(8f, 8f)
    )

    // 肩膀上衣基底
    val torsoPath = Path().apply {
        moveTo(w * 0.20f, h * 0.92f)
        quadraticTo(w * 0.22f, h * 0.73f, w * 0.43f, h * 0.73f)
        lineTo(w * 0.57f, h * 0.73f)
        quadraticTo(w * 0.78f, h * 0.73f, w * 0.80f, h * 0.92f)
        close()
    }

    when (avatarStyle) {
        NpcAvatarStyle.BARISTA_SARAH -> {
            // 溫暖內搭衫 + 青綠咖啡師圍裙
            drawPath(path = torsoPath, color = Color(0xFF334155))
            val apronPath = Path().apply {
                moveTo(w * 0.33f, h * 0.92f)
                lineTo(w * 0.36f, h * 0.74f)
                lineTo(w * 0.64f, h * 0.74f)
                lineTo(w * 0.67f, h * 0.92f)
                close()
            }
            drawPath(path = apronPath, color = primaryColor)
            // 咖啡師金色徽章
            drawCircle(color = secondaryColor, radius = w * 0.025f, center = Offset(w * 0.58f, h * 0.81f))
        }
        NpcAvatarStyle.CONCIERGE_DAVID -> {
            // 皇家藍禮賓西裝 + 白襯衫與金色領帶
            drawPath(path = torsoPath, color = Color(0xFF1E3A8A))
            val shirtPath = Path().apply {
                moveTo(w * 0.44f, h * 0.73f)
                lineTo(w * 0.56f, h * 0.73f)
                lineTo(w * 0.50f, h * 0.88f)
                close()
            }
            drawPath(path = shirtPath, color = Color.White)
            // 領帶
            drawLine(
                color = secondaryColor,
                start = Offset(w * 0.50f, h * 0.75f),
                end = Offset(w * 0.50f, h * 0.88f),
                strokeWidth = w * 0.035f,
                cap = StrokeCap.Round
            )
        }
        NpcAvatarStyle.AGENT_ELENA -> {
            // 航空深藍西裝 + 亮粉紅空服領巾
            drawPath(path = torsoPath, color = Color(0xFF312E81))
            val scarfPath = Path().apply {
                moveTo(w * 0.41f, h * 0.73f)
                quadraticTo(w * 0.50f, h * 0.81f, w * 0.59f, h * 0.73f)
                lineTo(w * 0.63f, h * 0.82f)
                lineTo(w * 0.54f, h * 0.78f)
                close()
            }
            drawPath(path = scarfPath, color = secondaryColor)
        }
    }
}

private fun DrawScope.drawBackHair(
    w: Float,
    h: Float,
    avatarStyle: NpcAvatarStyle,
    hairColor: Color
) {
    when (avatarStyle) {
        NpcAvatarStyle.BARISTA_SARAH -> {
            // 咖啡師丸子頭 + 後髮
            drawCircle(color = hairColor, radius = w * 0.13f, center = Offset(w * 0.50f, h * 0.17f))
            drawOval(
                color = hairColor,
                topLeft = Offset(w * 0.24f, h * 0.21f),
                size = Size(w * 0.52f, h * 0.46f)
            )
        }
        NpcAvatarStyle.CONCIERGE_DAVID -> {
            // 男士俐落短髮基底
            drawOval(
                color = hairColor,
                topLeft = Offset(w * 0.25f, h * 0.20f),
                size = Size(w * 0.50f, h * 0.36f)
            )
        }
        NpcAvatarStyle.AGENT_ELENA -> {
            // 優雅及肩鮑伯頭 (Bob Hair)
            drawRoundRect(
                color = hairColor,
                topLeft = Offset(w * 0.23f, h * 0.21f),
                size = Size(w * 0.54f, h * 0.50f),
                cornerRadius = CornerRadius(w * 0.22f, w * 0.22f)
            )
        }
    }
}

private fun DrawScope.drawFrontHairAndAccessory(
    w: Float,
    h: Float,
    avatarStyle: NpcAvatarStyle,
    hairColor: Color,
    primaryColor: Color,
    secondaryColor: Color
) {
    // 旁分柔和瀏海
    val bangsPath = Path().apply {
        moveTo(w * 0.27f, h * 0.38f)
        quadraticTo(w * 0.32f, h * 0.21f, w * 0.52f, h * 0.22f)
        quadraticTo(w * 0.70f, h * 0.22f, w * 0.73f, h * 0.37f)
        quadraticTo(w * 0.62f, h * 0.28f, w * 0.48f, h * 0.32f)
        quadraticTo(w * 0.35f, h * 0.30f, w * 0.27f, h * 0.38f)
        close()
    }
    drawPath(path = bangsPath, color = hairColor)

    if (avatarStyle == NpcAvatarStyle.BARISTA_SARAH) {
        // 髮帶裝飾
        drawArc(
            color = primaryColor,
            startAngle = 200f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(w * 0.28f, h * 0.21f),
            size = Size(w * 0.44f, h * 0.24f),
            style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
        )
    } else if (avatarStyle == NpcAvatarStyle.AGENT_ELENA) {
        // 地勤耳麥麥克風小細節
        drawArc(
            color = Color(0xFF94A3B8),
            startAngle = 15f,
            sweepAngle = 55f,
            useCenter = false,
            topLeft = Offset(w * 0.34f, h * 0.42f),
            size = Size(w * 0.38f, h * 0.22f),
            style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
        )
        drawCircle(
            color = secondaryColor,
            radius = w * 0.018f,
            center = Offset(w * 0.58f, h * 0.61f)
        )
    }
}

private fun DrawScope.drawNpcEyes(
    w: Float,
    h: Float,
    isBlinking: Boolean,
    emotion: NpcEmotion,
    pupilOffsetX: Float,
    pupilOffsetY: Float
) {
    val leftEyeCenter = Offset(w * 0.39f, h * 0.45f)
    val rightEyeCenter = Offset(w * 0.61f, h * 0.45f)
    val eyeRadius = w * 0.042f

    if (isBlinking) {
        // 閉眼微笑弧線
        drawArc(
            color = Color(0xFF1E293B),
            startAngle = 15f,
            sweepAngle = 150f,
            useCenter = false,
            topLeft = Offset(leftEyeCenter.x - eyeRadius, leftEyeCenter.y - eyeRadius * 0.5f),
            size = Size(eyeRadius * 2, eyeRadius),
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        )
        drawArc(
            color = Color(0xFF1E293B),
            startAngle = 15f,
            sweepAngle = 150f,
            useCenter = false,
            topLeft = Offset(rightEyeCenter.x - eyeRadius, rightEyeCenter.y - eyeRadius * 0.5f),
            size = Size(eyeRadius * 2, eyeRadius),
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        )
        return
    }

    if (emotion == NpcEmotion.EXCITED_PROUD) {
        // 達成目標時：彎彎笑眼 (^ ^)
        drawArc(
            color = Color(0xFF1E293B),
            startAngle = 195f,
            sweepAngle = 150f,
            useCenter = false,
            topLeft = Offset(leftEyeCenter.x - eyeRadius, leftEyeCenter.y - eyeRadius * 0.6f),
            size = Size(eyeRadius * 2, eyeRadius * 1.3f),
            style = Stroke(width = 2.8.dp.toPx(), cap = StrokeCap.Round)
        )
        drawArc(
            color = Color(0xFF1E293B),
            startAngle = 195f,
            sweepAngle = 150f,
            useCenter = false,
            topLeft = Offset(rightEyeCenter.x - eyeRadius, rightEyeCenter.y - eyeRadius * 0.6f),
            size = Size(eyeRadius * 2, eyeRadius * 1.3f),
            style = Stroke(width = 2.8.dp.toPx(), cap = StrokeCap.Round)
        )
        return
    }

    // 正常張眼：眼白 + 瞳孔 + 眼神光
    listOf(leftEyeCenter, rightEyeCenter).forEach { eyeCenter ->
        drawCircle(
            color = Color.White,
            radius = eyeRadius * 1.15f,
            center = eyeCenter
        )
        val pupilCenter = Offset(
            eyeCenter.x + pupilOffsetX,
            eyeCenter.y + pupilOffsetY
        )
        drawCircle(
            color = Color(0xFF0F172A),
            radius = eyeRadius * 0.82f,
            center = pupilCenter
        )
        // 高光點 (Catchlight)
        drawCircle(
            color = Color.White,
            radius = eyeRadius * 0.30f,
            center = Offset(pupilCenter.x - eyeRadius * 0.25f, pupilCenter.y - eyeRadius * 0.25f)
        )
    }
}

private fun DrawScope.drawNpcEyebrows(
    w: Float,
    h: Float,
    hairColor: Color,
    leftBrowAngle: Float,
    rightBrowAngle: Float,
    browLift: Float
) {
    val browY = h * 0.375f + browLift
    val browHalfLen = w * 0.048f

    // 左眉
    val leftCenter = Offset(w * 0.39f, browY)
    rotate(degrees = leftBrowAngle, pivot = leftCenter) {
        drawLine(
            color = hairColor,
            start = Offset(leftCenter.x - browHalfLen, leftCenter.y),
            end = Offset(leftCenter.x + browHalfLen, leftCenter.y),
            strokeWidth = 2.8.dp.toPx(),
            cap = StrokeCap.Round
        )
    }

    // 右眉
    val rightCenter = Offset(w * 0.61f, browY)
    rotate(degrees = rightBrowAngle, pivot = rightCenter) {
        drawLine(
            color = hairColor,
            start = Offset(rightCenter.x - browHalfLen, rightCenter.y),
            end = Offset(rightCenter.x + browHalfLen, rightCenter.y),
            strokeWidth = 2.8.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

private fun DrawScope.drawNpcMouth(
    w: Float,
    h: Float,
    interactionState: NpcInteractionState,
    emotion: NpcEmotion,
    voiceAmplitude: Float,
    speechPhase: Float
) {
    val mouthCenterX = w * 0.50f
    val mouthCenterY = h * 0.58f

    if (interactionState == NpcInteractionState.SPEAKING) {
        // 講話中：結合真實音訊振幅與多頻率音節開合模擬自然說話嘴型
        val syllableWave = abs(sin(speechPhase * 2.0f)) * 0.65f + abs(cos(speechPhase * 1.3f)) * 0.35f
        val effectiveOpen = ((voiceAmplitude * 0.85f) + (syllableWave * 0.55f)).coerceIn(0.18f, 1.0f)

        val mouthW = w * (0.11f + 0.04f * cos(speechPhase))
        val mouthH = h * (0.025f + 0.075f * effectiveOpen)

        val mouthRect = Rect(
            left = mouthCenterX - mouthW / 2f,
            top = mouthCenterY - mouthH / 2f,
            right = mouthCenterX + mouthW / 2f,
            bottom = mouthCenterY + mouthH / 2f
        )

        // 口腔深色背景
        drawRoundRect(
            color = Color(0xFF881337),
            topLeft = mouthRect.topLeft,
            size = mouthRect.size,
            cornerRadius = CornerRadius(mouthW * 0.45f, mouthH * 0.45f)
        )

        // 舌頭暖紅點綴
        if (effectiveOpen > 0.35f) {
            drawOval(
                color = Color(0xFFF43F5E),
                topLeft = Offset(mouthCenterX - mouthW * 0.32f, mouthCenterY + mouthH * 0.05f),
                size = Size(mouthW * 0.64f, mouthH * 0.42f)
            )
        }
        return
    }

    // 非講話狀態：依據情緒繪製靜態表情嘴型
    when (emotion) {
        NpcEmotion.EXCITED_PROUD -> {
            // 開朗大笑嘴型 (D 型張嘴笑)
            val mouthW = w * 0.14f
            val mouthH = h * 0.07f
            val path = Path().apply {
                moveTo(mouthCenterX - mouthW / 2f, mouthCenterY - mouthH * 0.2f)
                lineTo(mouthCenterX + mouthW / 2f, mouthCenterY - mouthH * 0.2f)
                quadraticTo(
                    mouthCenterX,
                    mouthCenterY + mouthH * 1.1f,
                    mouthCenterX - mouthW / 2f,
                    mouthCenterY - mouthH * 0.2f
                )
                close()
            }
            drawPath(path = path, color = Color(0xFF9F1239))
        }
        NpcEmotion.CONFUSED_WARNING -> {
            // 困惑微嘟嘴或歪嘴曲線
            val path = Path().apply {
                moveTo(mouthCenterX - w * 0.05f, mouthCenterY + h * 0.01f)
                quadraticTo(
                    mouthCenterX - w * 0.01f,
                    mouthCenterY - h * 0.02f,
                    mouthCenterX + w * 0.05f,
                    mouthCenterY + h * 0.005f
                )
            }
            drawPath(
                path = path,
                color = Color(0xFF7C2D12),
                style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round)
            )
        }
        NpcEmotion.DISAPPOINTED_FAILED -> {
            // 遺憾下彎嘴角
            drawArc(
                color = Color(0xFF7C2D12),
                startAngle = 200f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(mouthCenterX - w * 0.055f, mouthCenterY - h * 0.005f),
                size = Size(w * 0.11f, h * 0.045f),
                style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round)
            )
        }
        else -> {
            // 溫柔微笑弧線 (WELCOMING / HAPPY_ENCOURAGING)
            drawArc(
                color = Color(0xFF881337),
                startAngle = 20f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(mouthCenterX - w * 0.06f, mouthCenterY - h * 0.03f),
                size = Size(w * 0.12f, h * 0.055f),
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}

private fun DrawScope.drawEmotionOverlays(
    w: Float,
    h: Float,
    interactionState: NpcInteractionState,
    emotion: NpcEmotion,
    breathPhase: Float,
    speechPhase: Float
) {
    // 1. 思考中：頭頂斜上方漂浮的三個思考小氣泡
    if (interactionState == NpcInteractionState.THINKING) {
        val alpha1 = ((sin(speechPhase) + 1f) / 2f).coerceIn(0.3f, 1f)
        val alpha2 = ((cos(speechPhase) + 1f) / 2f).coerceIn(0.3f, 1f)
        drawCircle(
            color = Color(0xFFC084FC).copy(alpha = alpha1),
            radius = w * 0.022f,
            center = Offset(w * 0.76f, h * 0.26f)
        )
        drawCircle(
            color = Color(0xFFC084FC).copy(alpha = alpha2),
            radius = w * 0.032f,
            center = Offset(w * 0.83f, h * 0.19f)
        )
        drawCircle(
            color = Color(0xFFE879F9).copy(alpha = alpha1),
            radius = w * 0.042f,
            center = Offset(w * 0.90f, h * 0.11f)
        )
    }

    // 2. 達成目標 (EXCITED_PROUD)：閃亮十字星芒
    if (emotion == NpcEmotion.EXCITED_PROUD) {
        val starScale = 0.7f + 0.3f * abs(sin(breathPhase * 2f))
        drawSparkleStar(Offset(w * 0.16f, h * 0.22f), w * 0.045f * starScale, Color(0xFFFACC15))
        drawSparkleStar(Offset(w * 0.85f, h * 0.20f), w * 0.052f * starScale, Color(0xFF4ADE80))
    }

    // 3. 困惑偏離警告 (CONFUSED_WARNING)：橘色閃爍驚嘆/疑惑標記
    if (emotion == NpcEmotion.CONFUSED_WARNING) {
        val bobY = sin(speechPhase) * 3f
        val markCenter = Offset(w * 0.84f, h * 0.20f + bobY)
        drawCircle(
            color = WarningOrange,
            radius = w * 0.065f,
            center = markCenter
        )
        drawLine(
            color = Color.White,
            start = Offset(markCenter.x, markCenter.y - w * 0.03f),
            end = Offset(markCenter.x, markCenter.y + w * 0.005f),
            strokeWidth = 2.5.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawCircle(
            color = Color.White,
            radius = 1.6.dp.toPx(),
            center = Offset(markCenter.x, markCenter.y + w * 0.028f)
        )
    }
}

private fun DrawScope.drawSparkleStar(center: Offset, radius: Float, color: Color) {
    drawLine(
        color = color,
        start = Offset(center.x - radius, center.y),
        end = Offset(center.x + radius, center.y),
        strokeWidth = 2.2.dp.toPx(),
        cap = StrokeCap.Round
    )
    drawLine(
        color = color,
        start = Offset(center.x, center.y - radius),
        end = Offset(center.x, center.y + radius),
        strokeWidth = 2.2.dp.toPx(),
        cap = StrokeCap.Round
    )
}
