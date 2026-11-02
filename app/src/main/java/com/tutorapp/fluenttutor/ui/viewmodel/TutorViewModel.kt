package com.tutorapp.fluenttutor.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tutorapp.fluenttutor.audio.AudioPlayer
import com.tutorapp.fluenttutor.audio.AudioRecorder
import com.tutorapp.fluenttutor.engine.LfmAudioClient
import com.tutorapp.fluenttutor.engine.MissionJudgeEngine
import com.tutorapp.fluenttutor.model.ChatMessage
import com.tutorapp.fluenttutor.model.Language
import com.tutorapp.fluenttutor.model.MessageSender
import com.tutorapp.fluenttutor.model.MissionFinalSummary
import com.tutorapp.fluenttutor.model.MissionGoal
import com.tutorapp.fluenttutor.model.MissionStatus
import com.tutorapp.fluenttutor.model.Scenario
import com.tutorapp.fluenttutor.model.TurnEvaluation
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
    val isAutoVAD: Boolean = true,
    val offTopicStrikes: Int = 0,
    val missionStatus: MissionStatus = MissionStatus.IN_PROGRESS,
    val finalSummary: MissionFinalSummary? = null,
    val isConnectingLfm: Boolean = false,
    val isMockMode: Boolean = true,
    val lfmServerUrl: String = "",
    val availableScenarios: List<Scenario> = Scenario.getPredefinedScenarios(),
    val selectedLanguage: Language = Language.ENGLISH
)

class TutorViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "TutorViewModel"
    }

    private val allScenarios = Scenario.getPredefinedScenarios()
    private val initialScenario = allScenarios.first()

    private val _uiState = MutableStateFlow(
        TutorUiState(
            currentScenario = initialScenario,
            goals = initialScenario.goals,
            availableScenarios = allScenarios
        )
    )
    val uiState: StateFlow<TutorUiState> = _uiState.asStateFlow()

    private val lfmClient = LfmAudioClient()
    private val judgeEngine = MissionJudgeEngine()
    private val evaluations = mutableListOf<TurnEvaluation>()

    private val audioPlayer = AudioPlayer(
        context = application,
        onPlaybackStateChanged = { isPlaying ->
            _uiState.update { it.copy(isAiSpeaking = isPlaying) }
        }
    )

    private val audioRecorder = AudioRecorder(
        onSegmentReady = { pcmData, durationMs ->
            handleAudioSegment(pcmData, durationMs)
        },
        onAmplitudeChanged = { amp ->
            _uiState.update { it.copy(amplitude = amp) }
        },
        onSpeechStatusChanged = { isSpeaking ->
            _uiState.update { it.copy(isUserSpeaking = isSpeaking) }
        }
    )

    init {
        initScenario(initialScenario)
    }

    /**
     * 初始化或重設場景
     */
    fun initScenario(scenario: Scenario) {
        audioRecorder.stopRecording()
        audioPlayer.stop()
        audioPlayer.setLanguage(scenario.language)

        evaluations.clear()
        val resetGoals = scenario.goals.map { it.copy(isCompleted = false) }

        _uiState.update {
            it.copy(
                currentScenario = scenario,
                goals = resetGoals,
                messages = listOf(
                    ChatMessage(
                        sender = MessageSender.TUTOR,
                        text = scenario.aiInitialGreeting
                    )
                ),
                isRecording = false,
                isUserSpeaking = false,
                isAiSpeaking = false,
                offTopicStrikes = 0,
                missionStatus = MissionStatus.IN_PROGRESS,
                finalSummary = null
            )
        }

        // 開啟場景時播放 AI 初次招呼語
        viewModelScope.launch {
            kotlinx.coroutines.delay(400)
            audioPlayer.speakText(scenario.aiInitialGreeting)
        }
    }

    fun selectLanguage(language: Language) {
        val filtered = allScenarios.filter { it.language == language }
        val newScenario = filtered.firstOrNull() ?: allScenarios.first()
        _uiState.update { it.copy(selectedLanguage = language) }
        initScenario(newScenario)
    }

    fun toggleRecording() {
        if (_uiState.value.isRecording) {
            audioRecorder.stopRecording()
            _uiState.update { it.copy(isRecording = false) }
        } else {
            if (_uiState.value.missionStatus != MissionStatus.IN_PROGRESS) {
                // 如果已經結束，先重設
                initScenario(_uiState.value.currentScenario)
            }
            audioRecorder.startRecording(viewModelScope)
            _uiState.update { it.copy(isRecording = true) }
        }
    }

    fun setAutoVAD(enabled: Boolean) {
        audioRecorder.setAutoSegmentEnabled(enabled)
        _uiState.update { it.copy(isAutoVAD = enabled) }
    }

    /**
     * 處理分段語音或文字
     */
    private fun handleAudioSegment(pcmData: ByteArray, durationMs: Long, manualText: String? = null) {
        if (_uiState.value.missionStatus != MissionStatus.IN_PROGRESS) return

        val currentScenario = _uiState.value.currentScenario
        val currentGoals = _uiState.value.goals
        val turnIndex = evaluations.size

        viewModelScope.launch {
            _uiState.update { it.copy(isConnectingLfm = true) }

            // 1. 調用 LFM3-Audio 模型推論 (或端側 Smart Simulator)
            val response = lfmClient.sendAudioSegment(
                pcmData = pcmData,
                durationMs = durationMs,
                scenario = currentScenario,
                turnIndex = turnIndex,
                simulatedTextInput = manualText
            )

            // 2. 評判小模型評估此輪對話
            val evaluation = judgeEngine.evaluateTurn(
                userInput = response.recognizedUserText,
                currentGoals = currentGoals,
                scenario = currentScenario,
                turnIndex = turnIndex,
                audioDurationMs = durationMs
            )
            evaluations.add(evaluation)

            // 3. 更新目標清單
            val updatedGoals = currentGoals.map { goal ->
                if (evaluation.completedGoalIds.contains(goal.id)) {
                    goal.copy(isCompleted = true)
                } else {
                    goal
                }
            }

            // 4. 更新偏離 Strike
            val newStrikes = if (evaluation.isOffTopic) {
                _uiState.value.offTopicStrikes + 1
            } else {
                _uiState.value.offTopicStrikes
            }

            // 5. 判定任務狀態 (PASSED / FAILED / IN_PROGRESS)
            val newStatus = judgeEngine.checkMissionStatus(
                goals = updatedGoals,
                offTopicStrikes = newStrikes,
                totalTurns = evaluations.size,
                scenario = currentScenario
            )

            // 6. 若任務完成或失敗，產生最終結算報告
            val summary = if (newStatus != MissionStatus.IN_PROGRESS) {
                audioRecorder.stopRecording()
                judgeEngine.generateFinalSummary(
                    status = newStatus,
                    goals = updatedGoals,
                    evaluations = evaluations,
                    offTopicStrikes = newStrikes,
                    scenario = currentScenario
                )
            } else null

            // 7. 更新 UI 訊息佇列
            val userMsg = ChatMessage(
                sender = MessageSender.USER,
                text = response.recognizedUserText,
                audioDurationMs = durationMs,
                isOffTopic = evaluation.isOffTopic,
                offTopicReason = if (evaluation.isOffTopic) "Off-topic strike ($newStrikes/${currentScenario.maxOffTopicStrikes})" else null
            )

            val aiMsg = ChatMessage(
                sender = MessageSender.TUTOR,
                text = response.replyText
            )

            val updatedMessages = _uiState.value.messages.toMutableList().apply {
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
                    messages = updatedMessages,
                    offTopicStrikes = newStrikes,
                    missionStatus = newStatus,
                    finalSummary = summary,
                    isConnectingLfm = false,
                    isRecording = if (newStatus != MissionStatus.IN_PROGRESS) false else it.isRecording
                )
            }

            // 8. 播放 AI 回應語音
            if (response.audioBytes != null && response.audioBytes.isNotEmpty()) {
                audioPlayer.playPcm(response.audioBytes)
            } else {
                audioPlayer.speakText(response.replyText)
            }
        }
    }

    /**
     * 手動發送文字 (模擬發音)
     */
    fun sendManualText(text: String) {
        val dummyPcm = ByteArray(16000 * 2) // 模擬 1 秒長度的音訊
        handleAudioSegment(dummyPcm, 1000L, manualText = text)
    }

    fun playMessageAudio(message: ChatMessage) {
        audioPlayer.speakText(message.text)
    }

    fun retryCurrentScenario() {
        initScenario(_uiState.value.currentScenario)
    }

    fun dismissResultDialog() {
        _uiState.update { it.copy(finalSummary = null) }
    }

    fun updateServerConfig(url: String, apiKey: String, mockMode: Boolean) {
        lfmClient.serverUrl = url
        lfmClient.apiKey = apiKey
        lfmClient.isMockMode = mockMode
        _uiState.update {
            it.copy(
                lfmServerUrl = url,
                isMockMode = mockMode
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioRecorder.stopRecording()
        audioPlayer.release()
    }
}
