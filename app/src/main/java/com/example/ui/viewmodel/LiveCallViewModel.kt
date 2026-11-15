package com.example.ui.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.AiCallTurnResponse
import com.example.ai.ConversationMessage
import com.example.ai.GeminiCallAssistant
import com.example.data.local.LocalRuleCache
import com.example.data.model.AssistantSettings
import com.example.data.model.CallLog
import com.example.data.model.VipContact
import com.example.data.repository.CallAssistantRepository
import com.example.notification.CallNotificationHelper
import com.example.voice.VoiceInteractionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class CallPhase {
    IDLE,
    ACTIVE_CALL,
    ENDED
}

data class LiveCallUiState(
    val phase: CallPhase = CallPhase.IDLE,
    val callerName: String = "City Hospital Desk",
    val callerNumber: String = "+8801711000999",
    val messages: List<ConversationMessage> = emptyList(),
    val isAiThinking: Boolean = false,
    val importanceScore: Int = 0,
    val classification: String = CallLog.STATUS_ROUTINE,
    val latestSummary: String = "",
    val suggestedAction: String = "",
    val notificationSent: Boolean = false,
    val savedCallId: String? = null,
    val errorMessage: String? = null,
    val elapsedSeconds: Int = 0
)

class LiveCallViewModel(
    private val repository: CallAssistantRepository,
    private val localCache: LocalRuleCache,
    private val currentUserId: String,
    private val aiAssistant: GeminiCallAssistant = GeminiCallAssistant()
) : ViewModel() {

    private val _uiState = MutableStateFlow(LiveCallUiState())
    val uiState: StateFlow<LiveCallUiState> = _uiState.asStateFlow()

    private var callStartTimeMs: Long = 0L

    fun updateCallerInfo(name: String, number: String) {
        _uiState.update { it.copy(callerName = name, callerNumber = number) }
    }

    fun startIncomingCallSession(
        callerName: String,
        callerNumber: String,
        settings: AssistantSettings,
        voiceManager: VoiceInteractionManager?
    ) {
        val greeting = settings.greetingPrompt.ifBlank { AssistantSettings.DEFAULT_GREETING }
        callStartTimeMs = System.currentTimeMillis()
        _uiState.value = LiveCallUiState(
            phase = CallPhase.ACTIVE_CALL,
            callerName = callerName.ifBlank { "Unknown Caller" },
            callerNumber = callerNumber.ifBlank { "Unknown" },
            messages = listOf(ConversationMessage(isAi = true, text = greeting)),
            importanceScore = 15,
            classification = CallLog.STATUS_ROUTINE,
            latestSummary = "AI answered call and greeted caller."
        )
        voiceManager?.speak(greeting, settings.assistantLanguage)
    }

    fun sendCallerSpeech(
        utterance: String,
        settings: AssistantSettings,
        vipContacts: List<VipContact>,
        voiceManager: VoiceInteractionManager?,
        appContext: Context
    ) {
        val trimmed = utterance.trim()
        if (trimmed.isBlank()) return
        val currentState = _uiState.value
        if (currentState.phase != CallPhase.ACTIVE_CALL || currentState.isAiThinking) return

        val updatedHistory = currentState.messages + ConversationMessage(isAi = false, text = trimmed)
        _uiState.update {
            it.copy(
                messages = updatedHistory,
                isAiThinking = true,
                errorMessage = null
            )
        }

        val matchingVip = findVip(currentState.callerNumber, vipContacts)

        viewModelScope.launch {
            val result = aiAssistant.processCallerTurn(
                callerName = currentState.callerName,
                callerNumber = currentState.callerNumber,
                vipContact = matchingVip,
                settings = settings,
                history = updatedHistory.dropLast(1),
                latestCallerUtterance = trimmed
            )

            result.onSuccess { aiTurn ->
                handleAiTurnSuccess(
                    aiTurn = aiTurn,
                    settings = settings,
                    voiceManager = voiceManager,
                    appContext = appContext
                )
            }.onFailure { err ->
                Log.e(TAG, "AI turn failed", err)
                _uiState.update {
                    it.copy(
                        isAiThinking = false,
                        errorMessage = err.localizedMessage
                            ?: "AI call assistant could not respond."
                    )
                }
            }
        }
    }

    private suspend fun handleAiTurnSuccess(
        aiTurn: AiCallTurnResponse,
        settings: AssistantSettings,
        voiceManager: VoiceInteractionManager?,
        appContext: Context
    ) {
        val stateBefore = _uiState.value
        val newHistory = stateBefore.messages + ConversationMessage(isAi = true, text = aiTurn.replyText)
        val shouldAlert = aiTurn.shouldNotifyUser ||
            aiTurn.classification == CallLog.STATUS_IMPORTANT ||
            aiTurn.importanceScore >= 75

        voiceManager?.speak(aiTurn.replyText, settings.assistantLanguage)

        val elapsed = ((System.currentTimeMillis() - callStartTimeMs) / 1000L).toInt().coerceAtLeast(1)

        var notificationTriggered = stateBefore.notificationSent
        var savedId = stateBefore.savedCallId

        if (shouldAlert && !notificationTriggered) {
            val callId = persistCallLog(
                callerName = stateBefore.callerName,
                callerNumber = stateBefore.callerNumber,
                status = aiTurn.classification,
                score = aiTurn.importanceScore.toLong(),
                summary = aiTurn.aiSummary,
                messages = newHistory,
                actionTaken = aiTurn.suggestedAction,
                durationSeconds = elapsed.toLong(),
                notifiedUser = true
            )
            savedId = callId
            CallNotificationHelper.notifyCallResult(
                context = appContext,
                callId = callId ?: "urgent_${System.currentTimeMillis()}",
                callerName = stateBefore.callerName,
                callerNumber = stateBefore.callerNumber,
                summary = aiTurn.aiSummary,
                importanceScore = aiTurn.importanceScore,
                isImportant = true
            )
            notificationTriggered = true
        }

        _uiState.update {
            it.copy(
                messages = newHistory,
                isAiThinking = false,
                importanceScore = aiTurn.importanceScore,
                classification = aiTurn.classification,
                latestSummary = aiTurn.aiSummary,
                suggestedAction = aiTurn.suggestedAction,
                notificationSent = notificationTriggered,
                savedCallId = savedId,
                elapsedSeconds = elapsed,
                phase = if (aiTurn.shouldEndCall) CallPhase.ENDED else CallPhase.ACTIVE_CALL
            )
        }

        if (aiTurn.shouldEndCall && savedId == null) {
            finalizeAndSaveIfNeeded(appContext, settings)
        }
    }

    fun endAndSaveCall(appContext: Context, settings: AssistantSettings, voiceManager: VoiceInteractionManager?) {
        voiceManager?.stopSpeaking()
        voiceManager?.stopListening()
        viewModelScope.launch {
            finalizeAndSaveIfNeeded(appContext, settings)
            _uiState.update { it.copy(phase = CallPhase.ENDED) }
        }
    }

    fun resetCall() {
        _uiState.value = LiveCallUiState()
    }

    private suspend fun finalizeAndSaveIfNeeded(appContext: Context, settings: AssistantSettings) {
        val state = _uiState.value
        if (state.savedCallId != null || state.messages.size <= 1) return

        val elapsed = ((System.currentTimeMillis() - callStartTimeMs) / 1000L).coerceAtLeast(1L)
        val isImportant = state.classification == CallLog.STATUS_IMPORTANT || state.importanceScore >= 75
        val shouldNotify = isImportant || !settings.notifyUrgentOnly

        val savedId = persistCallLog(
            callerName = state.callerName,
            callerNumber = state.callerNumber,
            status = state.classification,
            score = state.importanceScore.toLong(),
            summary = state.latestSummary.ifBlank { "AI screened call from ${state.callerName}" },
            messages = state.messages,
            actionTaken = state.suggestedAction.ifBlank { "Screened & Logged" },
            durationSeconds = elapsed,
            notifiedUser = shouldNotify
        )

        if (shouldNotify && !state.notificationSent) {
            CallNotificationHelper.notifyCallResult(
                context = appContext,
                callId = savedId ?: "call_${System.currentTimeMillis()}",
                callerName = state.callerName,
                callerNumber = state.callerNumber,
                summary = state.latestSummary.ifBlank { "Screened call summary ready." },
                importanceScore = state.importanceScore,
                isImportant = isImportant
            )
        }

        _uiState.update {
            it.copy(
                savedCallId = savedId,
                notificationSent = it.notificationSent || shouldNotify,
                elapsedSeconds = elapsed.toInt()
            )
        }
    }

    private suspend fun persistCallLog(
        callerName: String,
        callerNumber: String,
        status: String,
        score: Long,
        summary: String,
        messages: List<ConversationMessage>,
        actionTaken: String,
        durationSeconds: Long,
        notifiedUser: Boolean
    ): String? {
        val transcript = messages.joinToString("\n") { msg ->
            if (msg.isAi) "AI: ${msg.text}" else "Caller: ${msg.text}"
        }.take(4000)

        val log = CallLog(
            userId = currentUserId,
            callerName = callerName,
            callerNumber = callerNumber,
            status = status,
            importanceScore = score.coerceIn(0L, 100L),
            aiSummary = summary.take(500),
            transcript = transcript,
            actionTaken = actionTaken.take(120),
            durationSeconds = durationSeconds,
            notifiedUser = notifiedUser,
            acknowledged = false,
            isSimulated = true
        )
        return repository.addCallLog(currentUserId, log).getOrNull()
    }

    private fun findVip(number: String, vips: List<VipContact>): VipContact? {
        val digits = number.filter { it.isDigit() }
        if (digits.isBlank()) return localCache.findMatchingVip(number)
        return vips.firstOrNull { vip ->
            val vipDigits = vip.phoneNumber.filter { it.isDigit() }
            vipDigits.isNotBlank() && (
                digits.endsWith(vipDigits.takeLast(8)) ||
                    vipDigits.endsWith(digits.takeLast(8))
                )
        }
    }

    private companion object {
        const val TAG = "LiveCallVM"
    }
}
