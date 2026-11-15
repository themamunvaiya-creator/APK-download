package com.example.ai

import android.util.Log
import com.example.data.model.AssistantSettings
import com.example.data.model.CallLog
import com.example.data.model.VipContact
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

data class ConversationMessage(
    val isAi: Boolean,
    val text: String
)

data class AiCallTurnResponse(
    val replyText: String,
    val importanceScore: Int,
    val classification: String,
    val shouldNotifyUser: Boolean,
    val shouldEndCall: Boolean,
    val aiSummary: String,
    val suggestedAction: String
)

class GeminiCallAssistant {

    private val responseSchema = Schema.obj(
        properties = mapOf(
            "replyText" to Schema.string(
                description = "Natural, polite spoken response to the caller (1-2 short sentences)."
            ),
            "importanceScore" to Schema.integer(
                description = "Urgency and importance score from 0 to 100."
            ),
            "classification" to Schema.enumeration(
                values = listOf(
                    CallLog.STATUS_IMPORTANT,
                    CallLog.STATUS_ROUTINE,
                    CallLog.STATUS_SPAM
                ),
                description = "Category of the call based on caller intent and user rules."
            ),
            "shouldNotifyUser" to Schema.boolean(
                description = "True if the user should receive an immediate high-priority alert."
            ),
            "shouldEndCall" to Schema.boolean(
                description = "True if the assistant has enough information and politely concluded."
            ),
            "aiSummary" to Schema.string(
                description = "One concise sentence summarizing who called, why, and urgency."
            ),
            "suggestedAction" to Schema.string(
                description = "Short action label like 'Urgent Notification Sent' or 'Screened & Logged'."
            )
        )
    )

    private val model by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                temperature = 0.35f
                responseMimeType = "application/json"
                responseSchema = this@GeminiCallAssistant.responseSchema
            }
        )
    }

    suspend fun processCallerTurn(
        callerName: String,
        callerNumber: String,
        vipContact: VipContact?,
        settings: AssistantSettings,
        history: List<ConversationMessage>,
        latestCallerUtterance: String
    ): Result<AiCallTurnResponse> {
        val languageInstruction = when (settings.assistantLanguage) {
            AssistantSettings.LANG_BENGALI ->
                "Always reply in natural, polite Bengali (বাংলা)."
            AssistantSettings.LANG_ENGLISH ->
                "Always reply in natural, polite English."
            else ->
                "Reply in the same language the caller is speaking (Bengali or English). If unsure, use warm Bengali."
        }

        val vipContext = if (vipContact != null) {
            "VIP MATCH: This caller is saved as '${vipContact.name}' with priority '${vipContact.priorityLevel}' and note '${vipContact.note}'."
        } else {
            "Caller is not in the VIP list."
        }

        val transcriptSoFar = if (history.isEmpty()) {
            "(Call just connected)"
        } else {
            history.joinToString("\n") { msg ->
                if (msg.isAi) "Assistant (${settings.assistantName}): ${msg.text}"
                else "Caller ($callerName): ${msg.text}"
            }
        }

        val prompt = """
            You are ${settings.assistantName}, a smart, calm, and polite AI Call Assistant answering a phone call on behalf of the phone's owner.
            
            Caller Name: $callerName
            Caller Number: $callerNumber
            $vipContext
            
            Owner's Importance Rules:
            ${settings.importanceRules}
            
            Language Rule:
            $languageInstruction
            
            Conversation so far:
            $transcriptSoFar
            
            Latest thing the caller just said:
            "$latestCallerUtterance"
            
            Instructions:
            1. Talk naturally like a real human-like phone receptionist (keep replyText concise, 1-2 short sentences suitable for Text-to-Speech).
            2. Ask clarifying questions ONLY if you don't yet know why they are calling or how urgent it is.
            3. Evaluate importanceScore (0-100):
               - 75-100 ('important'): Medical/family emergency, VIP critical contact, urgent work/delivery waiting right now, bank fraud alert, or caller explicitly needing immediate callback. Set shouldNotifyUser = true.
               - 30-74 ('routine'): General inquiries, casual check-ins, non-urgent business.
               - 0-29 ('spam'): Telemarketing, robocalls, prize scams, unsolicited sales.
            4. If you already clearly understand who is calling, why, and the urgency (or if it's clear spam), politely thank them / wrap up and set shouldEndCall = true.
        """.trimIndent()

        return try {
            val response = withTimeout(60_000L) {
                model.generateContent(prompt)
            }
            val rawJson = response.text
                ?: return Result.failure(IllegalStateException("Gemini returned an empty response."))

            val json = JSONObject(rawJson)
            val parsed = AiCallTurnResponse(
                replyText = json.optString("replyText").ifBlank {
                    "ধন্যবাদ, আমি আপনার বার্তাটি নোট করেছি এবং এখনই জানিয়ে দিচ্ছি।"
                },
                importanceScore = json.optInt("importanceScore", 50).coerceIn(0, 100),
                classification = json.optString("classification", CallLog.STATUS_ROUTINE).let {
                    if (it in listOf(
                            CallLog.STATUS_IMPORTANT,
                            CallLog.STATUS_ROUTINE,
                            CallLog.STATUS_SPAM
                        )
                    ) it else CallLog.STATUS_ROUTINE
                },
                shouldNotifyUser = json.optBoolean("shouldNotifyUser", false),
                shouldEndCall = json.optBoolean("shouldEndCall", false),
                aiSummary = json.optString("aiSummary").ifBlank {
                    "Call from $callerName: $latestCallerUtterance"
                }.take(500),
                suggestedAction = json.optString("suggestedAction", "Screened by AI").take(120)
            )
            Result.success(parsed)
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini call turn timed out", e)
            Result.failure(Exception("AI response timed out. Please try again.", e))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini call turn failed", e)
            Result.failure(e)
        }
    }

    private companion object {
        const val TAG = "GeminiCallAssistant"
    }
}
