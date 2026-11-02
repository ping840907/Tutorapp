package com.tutorapp.fluenttutor.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tutorapp.fluenttutor.audio.AudioPlayer
import com.tutorapp.fluenttutor.audio.AudioRecorder
import com.tutorapp.fluenttutor.engine.LfmSpeechToSpeechEngine
import com.tutorapp.fluenttutor.engine.MissionStateMachine
import com.tutorapp.fluenttutor.engine.Lfm3Backend
import com.tutorapp.fluenttutor.engine.ModelStore
import com.tutorapp.fluenttutor.model.ChatMessage
import com.tutorapp.fluenttutor.model.MessageSender
import com.tutorapp.fluenttutor.model.MissionFinalSummary
import com.tutorapp.fluenttutor.model.MissionGoal
import com.tutorapp.fluenttutor.model.MissionStatus
import com.tutorapp.fluenttutor.model.NpcEmotion
import com.tutorapp.fluenttutor.model.NpcInteractionState
import com.tutorapp.fluenttutor.model.Scenario
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

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

    private class QueuedAudioSegment(
        val pcmData: ByteArray?,
        val durationMs: Long,
        val manualText: String? = null
    )

    /**
     * 每次 prepareScenario 建立一個新的 Session：自己的佇列、待處理計數與處理協程。
     * 重設場景時舊 Session 整個被取消，舊結果不會污染新對話。
     */
    private class Session {
        val queue = Channel<QueuedAudioSegment>(Channel.UNLIMITED)
        val pending = AtomicInteger(0)
        var job: Job? = null
    }

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

    // 全程 LFM3-Audio-2B GGUF：ASR → 對話/評分 → TTS (移植自 Verbead 已驗證的實作)
    private val s2sEngine = LfmSpeechToSpeechEngine(Lfm3Backend(application))
    private var speakJob: Job? = null
    private var stateMachine = MissionStateMachine(initialScenario)
    private var missionState = stateMachine.initialState()

    private val dialogHistory = mutableListOf<Pair<String, String>>()

    @Volatile
    private var session = Session()

    // 音訊錄音器 (硬體 AEC、自適應 VAD 與全雙工即時打斷 Barge-in)；需顯式宣告型別避免互相遞迴推導
    private val audioRecorder: AudioRecorder = AudioRecorder(
        onSegmentReady = { pcmData, durationMs ->
            enqueueAudioSegment(pcmData, durationMs)
        },
        onAmplitudeChanged = { amp ->
            _uiState.update { it.copy(amplitude = amp) }
        },
        onSpeechStatusChanged = { isSpeaking ->
            _uiState.update { it.copy(isUserSpeaking = isSpeaking) }
        },
        onBargeInTriggered = {
            // 使用者在 AI 發言時主動開口打斷：立即停止播放，讓對話主控權回到使用者
            speakJob?.cancel()
            audioPlayer.stop()
            _uiState.update { it.copy(isAiSpeaking = false, aiAmplitude = 0f) }
        }
    )

    // 音訊播放器 (支援 WAV 標頭解析、即時語音振幅同步與半雙工保護)
    private val audioPlayer: AudioPlayer = AudioPlayer(
        onPlaybackStateChanged = { isPlaying ->
            _uiState.update { it.copy(isAiSpeaking = isPlaying) }
            // 半雙工防回音：當 AI 播放語音時，麥克風自動阻斷自激
            audioRecorder.setHalfDuplexMuted(isPlaying)
        },
        onPlaybackAmplitudeChanged = { aiAmp ->
            _uiState.update { it.copy(aiAmplitude = aiAmp) }
        }
    )

    init {
        // 重設場景 (不在 init 搶播語音)，同時啟動第一個 Session 的處理協程
        prepareScenario(initialScenario)
    }

    /**
     * 只接受「目前沒有處理中/排隊中」的語音，避免使用者連講造成佇列堆積與回合被雜音耗盡
     */
    private fun enqueueAudioSegment(pcmData: ByteArray?, durationMs: Long, manualText: String? = null) {
        if (_uiState.value.missionStatus != MissionStatus.IN_PROGRESS) return
        val s = session
        if (!s.pending.compareAndSet(0, 1)) return
        if (s.queue.trySend(QueuedAudioSegment(pcmData, durationMs, manualText)).isFailure) {
            s.pending.decrementAndGet()
        }
    }

    private fun startSession(): Session {
        val s = Session()
        s.job = viewModelScope.launch {
            for (segment in s.queue) {
                try {
                    processSingleSegment(s, segment)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 單一片段失敗不能讓整個處理協程死亡
                    Log.e("TutorViewModel", "Failed to process segment", e)
                    if (session === s) showSystemError(e.message ?: e.toString())
                } finally {
                    s.pending.decrementAndGet()
                }
            }
        }
        return s
    }

    /**
     * 重設與切換場景 (靜態重設，不搶播語音)；取消進行中的處理並丟棄排隊中的語音
     */
    fun prepareScenario(scenario: Scenario) {
        audioRecorder.stopRecording(flushRemaining = false)
        speakJob?.cancel()
        audioPlayer.stop()

        val old = session
        old.job?.cancel()
        old.queue.close()
        session = startSession()

        dialogHistory.clear()
        stateMachine = MissionStateMachine(scenario)
        missionState = stateMachine.initialState()

        _uiState.update {
            it.copy(
                currentScenario = scenario,
                goals = missionState.goals,
                messages = emptyList(),
                isRecording = false,
                isUserSpeaking = false,
                isAiSpeaking = false,
                isProcessing = false,
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
     * 當使用者進入對話畫面時，才主動觸發 AI 初次招呼語 (防螢幕旋轉重複播放)
     */
    fun playGreetingOnScreenEnter() {
        if (_uiState.value.hasPlayedInitialGreeting) return
        _uiState.update { it.copy(hasPlayedInitialGreeting = true, isProcessing = true) }
        val s = session
        val scenario = _uiState.value.currentScenario
        viewModelScope.launch {
            try {
                val missing = ModelStore.missingFiles(getApplication())
                if (missing.isNotEmpty()) {
                    showSystemError("On-device models are missing. Copy them to:\n" + missing.joinToString("\n"))
                    return@launch
                }
                val greeting = s2sEngine.generateGreeting(scenario)
                if (session !== s) return@launch
                if (greeting.isBlank()) {
                    showSystemError("The model returned an empty greeting.")
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        messages = it.messages + ChatMessage(sender = MessageSender.TUTOR, text = greeting),
                        realRamMb = s2sEngine.getRealMemoryUsageMb()
                    )
                }
                speak(greeting)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("TutorViewModel", "Greeting failed", e)
                if (session === s) showSystemError(e.message ?: e.toString())
            }
        }
    }

    /** LFM3 TTS 合成後以 PCM 播放；失敗只顯示錯誤，不退回其他語音服務 */
    private fun speak(text: String) {
        if (text.isBlank()) return
        speakJob?.cancel()
        audioPlayer.stop()
        val s = session
        val language = _uiState.value.currentScenario.ttsLanguage
        _uiState.update { it.copy(isProcessing = true) } // 合成期間 NPC 顯示思考中
        speakJob = viewModelScope.launch {
            try {
                val pcm = s2sEngine.synthesizeSpeech(text, language)
                if (session !== s) return@launch
                audioPlayer.play(pcm, ModelStore.TTS_SAMPLE_RATE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("TutorViewModel", "TTS failed", e)
                if (session === s) showSystemError("LFM3 TTS failed: ${e.message}")
            } finally {
                if (session === s) _uiState.update { it.copy(isProcessing = false) }
            }
        }
    }

    private fun showSystemError(message: String) {
        _uiState.update {
            it.copy(
                isProcessing = false,
                messages = it.messages + ChatMessage(sender = MessageSender.SYSTEM, text = "⚠️ $message")
            )
        }
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
     * 單一片段處理：模型推論 → 狀態機記錄 → 更新 UI → 播放回覆
     */
    private suspend fun processSingleSegment(s: Session, segment: QueuedAudioSegment) {
        val ui = _uiState.value
        if (ui.missionStatus != MissionStatus.IN_PROGRESS) return

        val scenario = ui.currentScenario
        val state = missionState

        _uiState.update { it.copy(isProcessing = true) }

        // 1. 單一模型推論：回覆語音 + 結構化 verdict (transcript / 離題 / 達成目標 / 評分)
        val result = s2sEngine.process(
            pcmData = segment.pcmData,
            scenario = scenario,
            goals = state.goals,
            history = dialogHistory.toList(),
            manualText = segment.manualText
        )
        if (session !== s) return // 場景已被重設，丟棄舊結果

        val output = result.output

        // 2a. ASR 沒有辨識出任何內容：不編造使用者的話或 NPC 回覆，只提示，不計回合
        if (result.userTranscript.isBlank()) {
            _uiState.update {
                it.copy(
                    isProcessing = false,
                    messages = it.messages + ChatMessage(sender = MessageSender.SYSTEM, text = "🎤 Nothing recognized — please try again."),
                    lastEvaluationFeedback = "Nothing recognized — this turn was not counted.",
                    realRamMb = result.realRamMb,
                    lastLatencyMs = result.latencyMs
                )
            }
            return
        }

        // 2b. LLM 輸出格式錯誤 (沒有 verdict)：顯示使用者的話與回覆，但不計回合、不改狀態
        if (output == null) {
            if (result.replyText.isBlank()) {
                showSystemError("The model returned an empty reply.")
                return
            }
            _uiState.update {
                it.copy(
                    isProcessing = false,
                    messages = it.messages +
                        ChatMessage(sender = MessageSender.USER, text = result.userTranscript, audioDurationMs = segment.durationMs) +
                        ChatMessage(sender = MessageSender.TUTOR, text = result.replyText),
                    lastEvaluationFeedback = "Model gave no score for this turn — not counted.",
                    realRamMb = result.realRamMb,
                    lastLatencyMs = result.latencyMs
                )
            }
            speak(result.replyText)
            return
        }

        // 3. 狀態機驗證並記錄模型的判定
        val newState = stateMachine.applyTurn(state, output)
        missionState = newState
        dialogHistory.add(result.userTranscript to result.replyText)

        val evaluation = newState.evaluations.last()
        val newStatus = newState.status

        // 4. NPC 臉部表情：完全由模型輸出指定的 output.emotion 驅動 (終止狀態作安全防護)
        val updatedEmotion = when {
            newStatus == MissionStatus.PASSED -> NpcEmotion.EXCITED_PROUD
            newStatus == MissionStatus.FAILED -> NpcEmotion.DISAPPOINTED_FAILED
            else -> output.emotion
        }

        // 5. 任務終止：停止收音並結算
        val summary = stateMachine.buildSummary(newState)
        if (newStatus != MissionStatus.IN_PROGRESS) {
            audioRecorder.stopRecording(flushRemaining = false)
        }

        // 6. 原子更新 UI：確保回覆文字、目標狀態/分數、NPC 表情在使用者視角同時生效
        val userMsg = ChatMessage(
            sender = MessageSender.USER,
            text = output.transcript,
            audioDurationMs = segment.durationMs,
            isOffTopic = evaluation.isOffTopic,
            offTopicReason = if (evaluation.isOffTopic) {
                "Off-topic strike (${newState.offTopicStrikes}/${scenario.maxOffTopicStrikes})"
            } else null
        )
        val newMessages = buildList {
            add(userMsg)
            add(ChatMessage(sender = MessageSender.TUTOR, text = result.replyText))
            if (evaluation.isOffTopic) add(ChatMessage(sender = MessageSender.SYSTEM, text = evaluation.feedback))
            if (evaluation.completedGoalIds.isNotEmpty()) add(ChatMessage(sender = MessageSender.SYSTEM, text = "🎯 Goal accomplished!"))
        }

        _uiState.update {
            it.copy(
                goals = newState.goals,
                messages = it.messages + newMessages,
                offTopicStrikes = newState.offTopicStrikes,
                missionStatus = newStatus,
                finalSummary = summary,
                isProcessing = false,
                npcEmotion = updatedEmotion,
                lastEvaluationFeedback = evaluation.feedback,
                realRamMb = result.realRamMb,
                lastLatencyMs = result.latencyMs,
                isRecording = if (newStatus != MissionStatus.IN_PROGRESS) false else it.isRecording
            )
        }

        // 7. 播放回覆
        speak(result.replyText)
    }

    fun sendManualText(text: String) {
        if (text.isBlank()) return
        // 手動輸入時主動打斷當前正在播放的 AI 語音，釋放半雙工鎖
        speakJob?.cancel()
        audioPlayer.stop()
        _uiState.update { it.copy(isAiSpeaking = false, aiAmplitude = 0f) }
        enqueueAudioSegment(null, 0L, manualText = text)
    }

    fun playMessageAudio(message: ChatMessage) {
        speak(message.text)
    }

    fun replayLastAiMessage() {
        val lastTutorMsg = _uiState.value.messages.lastOrNull { it.sender == MessageSender.TUTOR }
        if (lastTutorMsg != null) {
            speak(lastTutorMsg.text)
        }
    }

    fun retryCurrentScenario() {
        prepareScenario(_uiState.value.currentScenario)
        playGreetingOnScreenEnter()
    }

    fun prepareScenarioById(scenarioId: String) {
        val scenario = allScenarios.firstOrNull { it.id == scenarioId } ?: initialScenario
        prepareScenario(scenario)
    }

    fun dismissResultDialog() {
        _uiState.update { it.copy(finalSummary = null) }
    }

    fun stopAllAudio() {
        audioRecorder.stopRecording(flushRemaining = false)
        speakJob?.cancel()
        audioPlayer.stop()
        _uiState.update { it.copy(isRecording = false, isAiSpeaking = false, amplitude = 0f, aiAmplitude = 0f) }
    }

    fun onTrimMemory() {
        Log.i("TutorViewModel", "System memory low, trimming non-essential resources.")
        System.gc()
    }

    override fun onCleared() {
        super.onCleared()
        session.job?.cancel()
        audioRecorder.stopRecording(flushRemaining = false)
        audioPlayer.release()
        s2sEngine.release()
    }
}
