package com.example.telecom

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.Connection
import android.telecom.InCallService
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.local.LocalRuleCache
import com.example.data.model.AssistantSettings
import com.example.data.model.CallLog
import com.example.data.model.VipContact
import com.example.data.repository.CallAssistantRepository
import com.example.notification.CallNotificationHelper
import com.example.voice.VoiceInteractionManager
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Android CallScreeningService (API 29+) triggered by Telecom on incoming calls.
 * Responds synchronously within the 5-second OS deadline using LocalRuleCache,
 * logs the screened event to Firestore if authenticated, and triggers high-priority
 * notifications for VIP/urgent callers.
 */
class CallGuardScreeningService : CallScreeningService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onScreenCall(callDetails: Call.Details) {
        if (callDetails.callDirection != Call.Details.DIRECTION_INCOMING) {
            respondAllow(callDetails, silence = false)
            return
        }

        val rawNumber = callDetails.handle?.schemeSpecificPart ?: "Unknown"
        val callerDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            callDetails.callerDisplayName?.takeIf { it.isNotBlank() } ?: rawNumber
        } else {
            rawNumber
        }

        val cache = LocalRuleCache(applicationContext)
        val settings = cache.getSettings()
        if (!settings.assistantEnabled) {
            respondAllow(callDetails, silence = false)
            return
        }

        val vipMatch = cache.findMatchingVip(rawNumber)
        val verificationStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            callDetails.callerNumberVerificationStatus
        } else {
            Connection.VERIFICATION_STATUS_NOT_VERIFIED
        }

        when {
            vipMatch?.priorityLevel == VipContact.PRIORITY_BLOCK -> {
                val response = CallResponse.Builder()
                    .setDisallowCall(true)
                    .setRejectCall(true)
                    .setSkipCallLog(false)
                    .setSkipNotification(true)
                    .build()
                respondToCall(callDetails, response)
                recordScreenedCall(
                    callerName = vipMatch.name.ifBlank { callerDisplay },
                    callerNumber = rawNumber,
                    status = CallLog.STATUS_SPAM,
                    score = 5L,
                    summary = "Blocked caller rule matched (${vipMatch.note.ifBlank { "Auto-blocked" }}).",
                    action = "Blocked by CallGuard",
                    shouldNotify = false
                )
            }

            vipMatch?.priorityLevel == VipContact.PRIORITY_CRITICAL -> {
                respondAllow(callDetails, silence = false)
                recordScreenedCall(
                    callerName = vipMatch.name.ifBlank { callerDisplay },
                    callerNumber = rawNumber,
                    status = CallLog.STATUS_IMPORTANT,
                    score = 98L,
                    summary = "Critical VIP caller (${vipMatch.name}): ${vipMatch.note.ifBlank { "Priority call" }}",
                    action = "VIP Alert Sent",
                    shouldNotify = true
                )
            }

            verificationStatus == Connection.VERIFICATION_STATUS_FAILED -> {
                val response = CallResponse.Builder()
                    .setDisallowCall(false)
                    .setRejectCall(false)
                    .setSilenceCall(true)
                    .setSkipNotification(false)
                    .build()
                respondToCall(callDetails, response)
                recordScreenedCall(
                    callerName = callerDisplay,
                    callerNumber = rawNumber,
                    status = CallLog.STATUS_SPAM,
                    score = 18L,
                    summary = "Caller failed carrier STIR/SHAKEN verification; silenced by CallGuard AI.",
                    action = "Silenced Unverified Call",
                    shouldNotify = !settings.notifyUrgentOnly
                )
            }

            else -> {
                val shouldSilenceRing = settings.autoAnswerMode == AssistantSettings.MODE_ALL
                respondAllow(callDetails, silence = shouldSilenceRing)

                // If user granted ANSWER_PHONE_CALLS and mode is auto-answer, attempt to answer & speak
                if (settings.autoAnswerMode == AssistantSettings.MODE_ALL) {
                    tryAutoAnswerAndGreet(applicationContext, callerDisplay, rawNumber, settings)
                }

                val isHighPriority = vipMatch?.priorityLevel == VipContact.PRIORITY_HIGH
                recordScreenedCall(
                    callerName = vipMatch?.name?.ifBlank { callerDisplay } ?: callerDisplay,
                    callerNumber = rawNumber,
                    status = if (isHighPriority) CallLog.STATUS_IMPORTANT else CallLog.STATUS_ROUTINE,
                    score = if (isHighPriority) 82L else 45L,
                    summary = if (isHighPriority) {
                        "High-priority contact $callerDisplay called."
                    } else {
                        "Incoming call from $callerDisplay screened by CallGuard AI."
                    },
                    action = if (isHighPriority) "Priority Alert Sent" else "Screened by AI",
                    shouldNotify = isHighPriority || !settings.notifyUrgentOnly
                )
            }
        }
    }

    private fun respondAllow(callDetails: Call.Details, silence: Boolean) {
        val response = CallResponse.Builder()
            .setDisallowCall(false)
            .setRejectCall(false)
            .setSilenceCall(silence)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()
        respondToCall(callDetails, response)
    }

    private fun tryAutoAnswerAndGreet(
        context: Context,
        callerName: String,
        callerNumber: String,
        settings: AssistantSettings
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ANSWER_PHONE_CALLS
            ) == PackageManager.PERMISSION_GRANTED
            if (hasPermission) {
                try {
                    val telecom = context.getSystemService(TelecomManager::class.java)
                    @Suppress("DEPRECATION")
                    telecom?.acceptRingingCall()
                    val voice = VoiceInteractionManager(context)
                    voice.speak(settings.greetingPrompt, settings.assistantLanguage)
                    Log.d(TAG, "Auto-answered ringing call from $callerNumber ($callerName)")
                } catch (e: Exception) {
                    Log.w(TAG, "Auto-answer unavailable in current call state: ${e.message}")
                }
            }
        }
    }

    private fun recordScreenedCall(
        callerName: String,
        callerNumber: String,
        status: String,
        score: Long,
        summary: String,
        action: String,
        shouldNotify: Boolean
    ) {
        serviceScope.launch {
            val uid = try {
                Firebase.auth.currentUser?.uid
            } catch (_: Exception) {
                null
            }
            var savedId = "screen_${System.currentTimeMillis()}"
            if (uid != null) {
                val repo = CallAssistantRepository(applicationContext)
                val log = CallLog(
                    userId = uid,
                    callerName = callerName,
                    callerNumber = callerNumber,
                    status = status,
                    importanceScore = score,
                    aiSummary = summary,
                    transcript = "System: Call screened ($action)",
                    actionTaken = action,
                    durationSeconds = 0L,
                    notifiedUser = shouldNotify,
                    acknowledged = !shouldNotify,
                    isSimulated = false
                )
                repo.addCallLog(uid, log).onSuccess { createdId ->
                    savedId = createdId
                }
            }

            if (shouldNotify) {
                CallNotificationHelper.notifyCallResult(
                    context = applicationContext,
                    callId = savedId,
                    callerName = callerName,
                    callerNumber = callerNumber,
                    summary = summary,
                    importanceScore = score.toInt(),
                    isImportant = status == CallLog.STATUS_IMPORTANT
                )
            }
        }
    }

    private companion object {
        const val TAG = "CallGuardScreening"
    }
}

/**
 * Optional InCallService invoked when CallGuard AI holds the default Dialer role.
 * Automatically answers incoming calls when AI Auto-Answer is active and speaks the greeting.
 */
class CallGuardInCallService : InCallService() {

    private var voiceManager: VoiceInteractionManager? = null

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val state = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            call.details.state
        } else {
            @Suppress("DEPRECATION")
            call.state
        }

        if (state == Call.STATE_RINGING) {
            val cache = LocalRuleCache(applicationContext)
            val settings = cache.getSettings()
            if (settings.assistantEnabled && settings.autoAnswerMode == AssistantSettings.MODE_ALL) {
                try {
                    call.answer(VideoProfile.STATE_AUDIO_ONLY)
                    voiceManager = VoiceInteractionManager(applicationContext).also { vm ->
                        vm.speak(settings.greetingPrompt, settings.assistantLanguage)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to auto-answer via InCallService: ${e.message}")
                }
            }
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        voiceManager?.release()
        voiceManager = null
    }

    private companion object {
        const val TAG = "CallGuardInCall"
    }
}
