package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class AssistantSettings(
    @DocumentId val id: String = "assistant",
    val userId: String = "",
    val assistantEnabled: Boolean = true,
    val autoAnswerMode: String = MODE_ALL,
    val assistantLanguage: String = LANG_BILINGUAL,
    val assistantName: String = "Aria",
    val greetingPrompt: String = DEFAULT_GREETING,
    val importanceRules: String = DEFAULT_IMPORTANCE_RULES,
    val notifyUrgentOnly: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    companion object {
        const val MODE_ALL = "all"
        const val MODE_UNKNOWN_ONLY = "unknown_only"
        const val MODE_SCREENING_ONLY = "screening_only"

        const val LANG_BILINGUAL = "bilingual"
        const val LANG_BENGALI = "bn-BD"
        const val LANG_ENGLISH = "en-US"

        const val DEFAULT_GREETING =
            "হ্যালো! আমি কলগার্ড এআই অ্যাসিস্ট্যান্ট বলছি। উনি এখন ব্যস্ত আছেন। অনুগ্রহ করে বলুন আপনি কে বলছেন এবং আপনার কলটি কী বিষয়ে?"

        const val DEFAULT_IMPORTANCE_RULES =
            "Family emergencies, hospital or doctor updates, bank security/OTP alerts, delivery riders waiting at the gate, urgent office deadlines, or any caller explicitly asking for an immediate callback."
    }
}

@IgnoreExtraProperties
data class VipContact(
    @DocumentId val id: String = "",
    val userId: String = "",
    val name: String = "",
    val phoneNumber: String = "",
    val priorityLevel: String = PRIORITY_CRITICAL,
    val note: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    companion object {
        const val PRIORITY_CRITICAL = "critical"
        const val PRIORITY_HIGH = "high"
        const val PRIORITY_BLOCK = "block"
    }
}

@IgnoreExtraProperties
data class CallLog(
    @DocumentId val id: String = "",
    val userId: String = "",
    val callerName: String = "Unknown Caller",
    val callerNumber: String = "Unknown",
    val status: String = STATUS_ROUTINE,
    val importanceScore: Long = 0L,
    val aiSummary: String = "",
    val transcript: String = "",
    val actionTaken: String = "Screened by AI",
    val durationSeconds: Long = 0L,
    val notifiedUser: Boolean = false,
    val acknowledged: Boolean = false,
    val isSimulated: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    companion object {
        const val STATUS_IMPORTANT = "important"
        const val STATUS_ROUTINE = "routine"
        const val STATUS_SPAM = "spam"
        const val STATUS_MISSED = "missed"
    }
}
