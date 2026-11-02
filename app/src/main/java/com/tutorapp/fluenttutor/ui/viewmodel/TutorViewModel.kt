package com.tutorapp.fluenttutor.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tutorapp.fluenttutor.audio.AudioPlayer
import com.tutorapp.fluenttutor.audio.AudioRecorder
import com.tutorapp.fluenttutor.engine.LfmSpeechToSpeechEngine
import com.tutorapp.fluenttutor.engine.MissionJudgeEngine
import com.tutorapp.fluenttutor.model.ChatMessage
import com.tutorapp.fluenttutor.model.MessageSender
import com.tutorapp.fluenttutor.model.MissionFinalSummary
import com.tutorapp.fluenttutor.model.MissionGoal
import com.tutorapp.fluenttutor.model.MissionStatus
import com.tutorapp.fluenttutor.model.NpcEmotion
import com.tutorapp.fluenttutor.model.NpcInteractionState
import com.tutorapp.fluenttutor.model.Scenario
import com.tutorapp.fluenttutor.model.TurnEvaluation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TutorUiState(
    val currentScenario: Scenario,
    val goals: List<MissionGoal>,
    val messages: List<ChatMessage> = emptyList(),
    val isRecording: Boolean = false,
    val isUserSpeaking: Boolean = false,
    val isAiSpeaking: Boolean = false,
    val amplitude: Float = 0f,
    val aiAmplitude: Float = 0f,
    val isAutoVAD: Boolean = true,
    val offTopicStrikes: Int = 0,
    val missionStatus: MissionStatus = MissionStatus.IN_PROGRESS,
    val finalSummary: MissionFinalSummary? = null,
    val isProcessing: Boolean = false,
    val npcEmotion: NpcEmotion = NpcEmotion.WELCOMING,
    val lastEvaluationFeedback: String? = null,
    val hasPlayedInitialGreeting: Boolean = false,
    val realRamMb: Long = 0L,
    val lastLatencyMs: Long = 0L,
    val availableScenarios: List<Scenario> = Scenario.getPredefinedEnglishScenarios()
) {
    val npcInteractionState: NpcInteractionState
        get() = when {
            isAiSpeaking -> NpcInteractionState.SPEAKING
            isProcessing -> NpcInteractionState.THINKING
            isUserSpeaking -> NpcInteractionState.LISTENING_ACTIVE
            isRecording -> NpcInteractionState.LISTENING_IDLE
            else -> NpcInteractionState.IDLE
        }

    val activeAmplitude: Float
        get() = if (isAiSpeaking) aiAmplitude else amplitude
}

class TutorViewModel(application: Application) : AndroidViewModel(application) {

    private data class QueuedAudioSegment(
        val pcmData: ByteArray,
        val durationMs: Long,
        val manualText: String? = null
    )

    private val allScenarios = Scenario.getPredefinedEnglishScenarios()
    private val initialScenario = allScenarios.first()

    private val _uiState = MutableStateFlow(
        TutorUiState(
            currentScenario = initialScenario,
            goals = initialScenario.goals,
            availableScenarios = allScenarios
        )
    )
    val uiState: StateFlow<TutorUiState> = _uiState.asStateFlow()

    // 雙引擎架構：主 S2S 引擎 + 副抗幻覺判定引擎
    private val s2sEngine = LfmSpeechToSpeechEngine(application)
    private val judgeEngine = MissionJudgeEngine()

    private val evaluations = mutableListOf<TurnEvaluation>()
    private val dialogHistory = mutableListOf<Pair<String, String>>()
    private val audioQueueChannel = Channel<QueuedAudioSegment>(Channel.UNLIMITED)

    // 音訊播放器 (支援 WAV 標頭解析、即時語音振幅同步與半雙工保護)
    private val audioPlayer = AudioPlayer(
        context = application,
        onPlaybackStateChanged = { isPlaying ->
            _uiState.update { it.copy(isAiSpeaking = isPlaying) }
            // 半雙工防回音：當 AI 播放語音時，麥克風自動阻斷自激
            audioRecorder.setHalfDuplexMuted(isPlaying)
        },
        onPlaybackAmplitudeChanged = { aiAmp ->
            _uiState.update { it.copy(aiAmplitude = aiAmp) }
        }
    )

    // 音訊錄音器 (SM8850 硬體 AEC 與自適應 VAD)
    private val audioRecorder = AudioRecorder(
        onSegmentReady = { pcmData, durationMs ->
            enqueueAudioSegment(pcmData, durationMs)
        },
        onAmplitudeChanged = { amp ->
            _uiState.update { it.copy(amplitude = amp) }
        },
        onSpeechStatusChanged = { isSpeaking ->
            _uiState.update { it.copy(isUserSpeaking = isSpeaking) }
        }
    )

    init {
        // 啟動單一序列化佇列處理協程，消除任何並行並發狀態競態
        startAudioProcessingQueue()
        // 重設場景 (不在 init 搶播語音)
        prepareScenario(initialScenario)
    }

    /**
     * 啟動單線程序列音訊處理管道
     */
    private fun startAudioProcessingQueue() {
        viewModelScope.launch {
            for (segment in audioQueueChannel) {
                processSingleSegment(segment.pcmData, segment.durationMs, segment.manualText)
            }
        }
    }

    private fun enqueueAudioSegment(pcmData: ByteArray, durationMs: Long, manualText: String? = null) {
        if (_uiState.value.missionStatus != MissionStatus.IN_PROGRESS) return
        audioQueueChannel.trySend(QueuedAudioSegment(pcmData, durationMs, manualText))
    }

    /**
     * 重設與切換場景 (靜態重設，不搶播語音)
     */
    fun prepareScenario(scenario: Scenario) {
        audioRecorder.stopRecording(flushRemaining = false)
        audioPlayer.stop()

        evaluations.clear()
        dialogHistory.clear()
        val resetGoals = scenario.goals.map { it.copy(isCompleted = false) }

        _uiState.update {
            it.copy(
                currentScenario = scenario,
                goals = resetGoals,
                messages = listOf(
                    ChatMessage(
                        sender = MessageSender.TUTOR,
                        text = scenario.initialGreeting
                    )
                ),
                isRecording = false,
                isUserSpeaking = false,
                isAiSpeaking = false,
                amplitude = 0f,
                aiAmplitude = 0f,
                offTopicStrikes = 0,
                missionStatus = MissionStatus.IN_PROGRESS,
                finalSummary = null,
                npcEmotion = NpcEmotion.WELCOMING,
                lastEvaluationFeedback = "Task started: ${scenario.subtitle}",
                hasPlayedInitialGreeting = false,
                realRamMb = s2sEngine.getRealMemoryUsageMb()
            )
        }
    }

    /**
     * 當使用者進入對話畫面時，才主動觸發 AI 初次招呼語 (具備防螢幕旋轉重複播放保護)
     */
    fun playGreetingOnScreenEnter() {
        if (_uiState.value.hasPlayedInitialGreeting) return
        _uiState.update { it.copy(hasPlayedInitialGreeting = true) }
        val greeting = _uiState.value.currentScenario.initialGreeting
        audioPlayer.speakText(greeting)
    }

    fun toggleRecording() {
        if (_uiState.value.isRecording) {
            // 停止錄音時，若緩衝區有尚未送出的語音片段則一併送出 (支援手動關閉 VAD 模式)
            audioRecorder.stopRecording(flushRemaining = true)
            _uiState.update { it.copy(isRecording = false) }
        } else {
            if (_uiState.value.missionStatus != MissionStatus.IN_PROGRESS) {
                prepareScenario(_uiState.value.currentScenario)
            }
            val started = audioRecorder.startRecording(viewModelScope)
            _uiState.update { it.copy(isRecording = started) }
        }
    }

    fun onPermissionDenied() {
        audioRecorder.stopRecording(flushRemaining = false)
        _uiState.update { it.copy(isRecording = false) }
    }

    fun setAutoVAD(enabled: Boolean) {
        audioRecorder.setAutoSegmentEnabled(enabled)
        _uiState.update { it.copy(isAutoVAD = enabled) }
    }

    /**
     * 單一執行緒嚴格序列化處理語音片段
     */
    private suspend fun processSingleSegment(
        pcmData: ByteArray,
        durationMs: Long,
        manualText: String?
    ) {
        if (_uiState.value.missionStatus != MissionStatus.IN_PROGRESS) return

        val scenario = _uiState.value.currentScenario
        val currentGoals = _uiState.value.goals
        val turnIndex = evaluations.size

        _uiState.update { it.copy(isProcessing = true) }

        // 1. 主引擎推論 (LFM3 S2S 音訊特徵與回覆生成)
        val s2sResult = s2sEngine.processSpeechToSpeech(
            pcmData = pcmData,
            durationMs = durationMs,
            scenario = scenario,
            turnIndex = turnIndex,
            manualText = manualText,
            dialogHistory = dialogHistory
        )

        // 2. 副引擎嚴密判定 (防止 2B 模型幻覺)
        val evaluation = judgeEngine.evaluateTurn(
            userInput = s2sResult.recognizedText,
            currentGoals = currentGoals,
            scenario = scenario,
            turnIndex = turnIndex,
            audioDurationMs = durationMs
        )
        evaluations.add(evaluation)
        dialogHistory.add(Pair(s2sResult.recognizedText, s2sResult.replyText))

        // 3. 更新目標完成狀態清單
        val updatedGoals = currentGoals.map { goal ->
            if (evaluation.completedGoalIds.contains(goal.id)) {
                goal.copy(isCompleted = true)
            } else {
                goal
            }
        }

        // 4. 更新偏離 Strike 計數
        val newStrikes = if (evaluation.isOffTopic) {
            _uiState.value.offTopicStrikes + 1
        } else {
            _uiState.value.offTopicStrikes
        }

        // 5. 判定任務整體狀態 (過關 / 失敗 / 進行中)
        val newStatus = judgeEngine.checkMissionStatus(
            goals = updatedGoals,
            offTopicStrikes = newStrikes,
            totalTurns = evaluations.size,
            scenario = scenario
        )

        // 6. 根據判定結果決定 NPC 的臉部表情與情緒狀態
        val updatedEmotion = when {
            newStatus == MissionStatus.PASSED -> NpcEmotion.EXCITED_PROUD
            newStatus == MissionStatus.FAILED -> NpcEmotion.DISAPPOINTED_FAILED
            evaluation.isOffTopic -> NpcEmotion.CONFUSED_WARNING
            evaluation.completedGoalIds.isNotEmpty() -> NpcEmotion.EXCITED_PROUD
            else -> NpcEmotion.HAPPY_ENCOURAGING
        }

        // 7. 若任務終止，結算成績單
        val summary = if (newStatus != MissionStatus.IN_PROGRESS) {
            audioRecorder.stopRecording(flushRemaining = false)
            judgeEngine.generateFinalSummary(
                status = newStatus,
                goals = updatedGoals,
                evaluations = evaluations,
                offTopicStrikes = newStrikes,
                scenario = scenario
            )
        } else null

        // 8. 更新對話 UI
        val userMsg = ChatMessage(
            sender = MessageSender.USER,
            text = s2sResult.recognizedText,
            audioDurationMs = durationMs,
            isOffTopic = evaluation.isOffTopic,
            offTopicReason = if (evaluation.isOffTopic) "Off-topic strike ($newStrikes/${scenario.maxOffTopicStrikes})" else null
        )

        val aiMsg = ChatMessage(
            sender = MessageSender.TUTOR,
            text = s2sResult.replyText
        )

        val newMessages = _uiState.value.messages.toMutableList().apply {
            add(userMsg)
            add(aiMsg)
            if (evaluation.isOffTopic) {
                add(ChatMessage(sender = MessageSender.SYSTEM, text = evaluation.feedback))
            }
            if (evaluation.completedGoalIds.isNotEmpty()) {
                add(ChatMessage(sender = MessageSender.SYSTEM, text = "🎯 Goal accomplished!"))
            }
        }

        _uiState.update {
            it.copy(
                goals = updatedGoals,
                messages = newMessages,
                offTopicStrikes = newStrikes,
                missionStatus = newStatus,
                finalSummary = summary,
                isProcessing = false,
                npcEmotion = updatedEmotion,
                lastEvaluationFeedback = evaluation.feedback,
                realRamMb = s2sResult.realRamMb,
                lastLatencyMs = s2sResult.latencyMs,
                isRecording = if (newStatus != MissionStatus.IN_PROGRESS) false else it.isRecording
            )
        }

        // 9. 播放生成的音訊 (優先播放主引擎生成的波形，若空則走 TTS)
        if (s2sResult.audioBytes != null && s2sResult.audioBytes.isNotEmpty()) {
            audioPlayer.playAudioData(s2sResult.audioBytes)
        } else {
            audioPlayer.speakText(s2sResult.replyText)
        }
    }

    fun sendManualText(text: String) {
        val dummyPcm = ByteArray(16000 * 2)
        enqueueAudioSegment(dummyPcm, 1000L, manualText = text)
    }

    fun playMessageAudio(message: ChatMessage) {
        audioPlayer.speakText(message.text)
    }

    fun replayLastAiMessage() {
        val lastTutorMsg = _uiState.value.messages.lastOrNull { it.sender == MessageSender.TUTOR }
        if (lastTutorMsg != null) {
            audioPlayer.speakText(lastTutorMsg.text)
        }
    }

    fun retryCurrentScenario() {
        prepareScenario(_uiState.value.currentScenario)
        playGreetingOnScreenEnter()
    }

    fun dismissResultDialog() {
        _uiState.update { it.copy(finalSummary = null) }
    }

    fun stopAllAudio() {
        audioRecorder.stopRecording(flushRemaining = false)
        audioPlayer.stop()
        _uiState.update { it.copy(isRecording = false, isAiSpeaking = false, amplitude = 0f, aiAmplitude = 0f) }
    }

    override fun onCleared() {
        super.onCleared()
        audioRecorder.stopRecording(flushRemaining = false)
        audioPlayer.release()
        s2sEngine.release()
    }
}
