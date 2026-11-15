package com.example.data.local

import android.content.Context
import com.example.data.model.AssistantSettings
import com.example.data.model.VipContact
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fast local mirror of user's active screening settings and VIP rules so
 * Android's CallScreeningService can respond well within the 5-second Telecom deadline.
 */
class LocalRuleCache(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun saveSnapshot(settings: AssistantSettings, vips: List<VipContact>) {
        val vipArray = JSONArray()
        vips.forEach { vip ->
            vipArray.put(
                JSONObject().apply {
                    put("name", vip.name)
                    put("phoneNumber", vip.phoneNumber)
                    put("priorityLevel", vip.priorityLevel)
                    put("note", vip.note)
                }
            )
        }
        prefs.edit()
            .putBoolean(KEY_ENABLED, settings.assistantEnabled)
            .putString(KEY_MODE, settings.autoAnswerMode)
            .putString(KEY_LANG, settings.assistantLanguage)
            .putString(KEY_NAME, settings.assistantName)
            .putString(KEY_GREETING, settings.greetingPrompt)
            .putString(KEY_IMPORTANCE, settings.importanceRules)
            .putBoolean(KEY_NOTIFY_URGENT_ONLY, settings.notifyUrgentOnly)
            .putString(KEY_VIPS, vipArray.toString())
            .apply()
    }

    fun getSettings(): AssistantSettings {
        return AssistantSettings(
            assistantEnabled = prefs.getBoolean(KEY_ENABLED, true),
            autoAnswerMode = prefs.getString(KEY_MODE, AssistantSettings.MODE_ALL)
                ?: AssistantSettings.MODE_ALL,
            assistantLanguage = prefs.getString(KEY_LANG, AssistantSettings.LANG_BILINGUAL)
                ?: AssistantSettings.LANG_BILINGUAL,
            assistantName = prefs.getString(KEY_NAME, "Aria") ?: "Aria",
            greetingPrompt = prefs.getString(KEY_GREETING, AssistantSettings.DEFAULT_GREETING)
                ?: AssistantSettings.DEFAULT_GREETING,
            importanceRules = prefs.getString(
                KEY_IMPORTANCE,
                AssistantSettings.DEFAULT_IMPORTANCE_RULES
            ) ?: AssistantSettings.DEFAULT_IMPORTANCE_RULES,
            notifyUrgentOnly = prefs.getBoolean(KEY_NOTIFY_URGENT_ONLY, true)
        )
    }

    fun getVipContacts(): List<VipContact> {
        val raw = prefs.getString(KEY_VIPS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { idx ->
                val obj = arr.getJSONObject(idx)
                VipContact(
                    name = obj.optString("name", ""),
                    phoneNumber = obj.optString("phoneNumber", ""),
                    priorityLevel = obj.optString("priorityLevel", VipContact.PRIORITY_HIGH),
                    note = obj.optString("note", "")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun findMatchingVip(incomingNumber: String): VipContact? {
        val normalizedIncoming = normalizeDigits(incomingNumber)
        if (normalizedIncoming.isBlank()) return null
        return getVipContacts().firstOrNull { vip ->
            val vipDigits = normalizeDigits(vip.phoneNumber)
            vipDigits.isNotBlank() && (
                normalizedIncoming.endsWith(vipDigits.takeLast(8)) ||
                    vipDigits.endsWith(normalizedIncoming.takeLast(8))
                )
        }
    }

    private fun normalizeDigits(input: String): String =
        input.filter { it.isDigit() }

    private companion object {
        const val PREFS_NAME = "callguard_rule_cache"
        const val KEY_ENABLED = "assistant_enabled"
        const val KEY_MODE = "auto_answer_mode"
        const val KEY_LANG = "assistant_language"
        const val KEY_NAME = "assistant_name"
        const val KEY_GREETING = "greeting_prompt"
        const val KEY_IMPORTANCE = "importance_rules"
        const val KEY_NOTIFY_URGENT_ONLY = "notify_urgent_only"
        const val KEY_VIPS = "vip_contacts_json"
    }
}
