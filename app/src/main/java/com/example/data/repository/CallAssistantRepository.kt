package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.core.OperationType
import com.example.core.handleFirestoreError
import com.example.data.model.AssistantSettings
import com.example.data.model.CallLog
import com.example.data.model.VipContact
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class CallAssistantRepository(private val db: FirebaseFirestore) {

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun settingsDocRef(userId: String) =
        db.collection("users").document(userId).collection("settings").document("assistant")

    private fun vipCollection(userId: String) =
        db.collection("users").document(userId).collection("vip_contacts")

    private fun callLogsCollection(userId: String) =
        db.collection("users").document(userId).collection("call_logs")

    fun observeSettings(userId: String): Flow<AssistantSettings?> = flow {
        val docRef = settingsDocRef(userId)
        emitAll(
            docRef.snapshots()
                .map { snapshot ->
                    if (!snapshot.exists()) {
                        null
                    } else {
                        snapshot.toObject(
                            AssistantSettings::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    }
                }
                .catch { error ->
                    val firestoreError = unwrapFirestoreException(error)
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && isStillSignedInAs(userId)) {
                        handleFirestoreError(toLog, OperationType.GET, docRef.path)
                    }
                    throw error
                }
        )
    }

    suspend fun ensureDefaultSettings(userId: String): Result<Unit> {
        val docRef = settingsDocRef(userId)
        return try {
            val snap = docRef.get().await()
            if (!snap.exists()) {
                val payload = mapOf(
                    "userId" to userId,
                    "assistantEnabled" to true,
                    "autoAnswerMode" to AssistantSettings.MODE_ALL,
                    "assistantLanguage" to AssistantSettings.LANG_BILINGUAL,
                    "assistantName" to "Aria",
                    "greetingPrompt" to AssistantSettings.DEFAULT_GREETING,
                    "importanceRules" to AssistantSettings.DEFAULT_IMPORTANCE_RULES,
                    "notifyUrgentOnly" to true,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun saveSettings(userId: String, settings: AssistantSettings): Result<Unit> {
        val docRef = settingsDocRef(userId)
        return try {
            val existingSnap = docRef.get().await()
            if (!existingSnap.exists()) {
                val payload = mapOf(
                    "userId" to userId,
                    "assistantEnabled" to settings.assistantEnabled,
                    "autoAnswerMode" to settings.autoAnswerMode,
                    "assistantLanguage" to settings.assistantLanguage,
                    "assistantName" to settings.assistantName.trim().take(40).ifBlank { "Aria" },
                    "greetingPrompt" to settings.greetingPrompt.trim().take(500)
                        .ifBlank { AssistantSettings.DEFAULT_GREETING },
                    "importanceRules" to settings.importanceRules.trim().take(1000)
                        .ifBlank { AssistantSettings.DEFAULT_IMPORTANCE_RULES },
                    "notifyUrgentOnly" to settings.notifyUrgentOnly,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).await()
            } else {
                val updates = mapOf(
                    "assistantEnabled" to settings.assistantEnabled,
                    "autoAnswerMode" to settings.autoAnswerMode,
                    "assistantLanguage" to settings.assistantLanguage,
                    "assistantName" to settings.assistantName.trim().take(40).ifBlank { "Aria" },
                    "greetingPrompt" to settings.greetingPrompt.trim().take(500)
                        .ifBlank { AssistantSettings.DEFAULT_GREETING },
                    "importanceRules" to settings.importanceRules.trim().take(1000)
                        .ifBlank { AssistantSettings.DEFAULT_IMPORTANCE_RULES },
                    "notifyUrgentOnly" to settings.notifyUrgentOnly,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(updates).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    fun observeVipContacts(userId: String): Flow<List<VipContact>> = flow {
        val col = vipCollection(userId)
        emitAll(
            col.orderBy("createdAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        VipContact::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = unwrapFirestoreException(error)
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && isStillSignedInAs(userId)) {
                        handleFirestoreError(toLog, OperationType.LIST, col.path)
                    }
                    throw error
                }
        )
    }

    suspend fun addVipContact(
        userId: String,
        name: String,
        phoneNumber: String,
        priorityLevel: String,
        note: String
    ): Result<String> {
        val docRef = vipCollection(userId).document()
        val payload = mapOf(
            "userId" to userId,
            "name" to name.trim().take(60),
            "phoneNumber" to phoneNumber.trim().take(30),
            "priorityLevel" to priorityLevel,
            "note" to note.trim().take(200),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        return try {
            docRef.set(payload).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateVipContact(userId: String, contact: VipContact): Result<Unit> {
        require(contact.id.isNotBlank()) { "VIP contact ID must not be blank" }
        val docRef = vipCollection(userId).document(contact.id)
        val updates = mapOf(
            "name" to contact.name.trim().take(60),
            "phoneNumber" to contact.phoneNumber.trim().take(30),
            "priorityLevel" to contact.priorityLevel,
            "note" to contact.note.trim().take(200),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        return try {
            docRef.update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteVipContact(userId: String, contactId: String): Result<Unit> {
        val docRef = vipCollection(userId).document(contactId)
        return try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    fun observeCallLogs(userId: String): Flow<List<CallLog>> = flow {
        val col = callLogsCollection(userId)
        emitAll(
            col.orderBy("createdAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        CallLog::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    val firestoreError = unwrapFirestoreException(error)
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && isStillSignedInAs(userId)) {
                        handleFirestoreError(toLog, OperationType.LIST, col.path)
                    }
                    throw error
                }
        )
    }

    suspend fun addCallLog(userId: String, log: CallLog): Result<String> {
        val docRef = callLogsCollection(userId).document()
        val payload = mapOf(
            "userId" to userId,
            "callerName" to log.callerName.trim().take(80).ifBlank { "Unknown Caller" },
            "callerNumber" to log.callerNumber.trim().take(30).ifBlank { "Unknown" },
            "status" to log.status,
            "importanceScore" to log.importanceScore.coerceIn(0L, 100L),
            "aiSummary" to log.aiSummary.trim().take(500).ifBlank { "Call screened by AI" },
            "transcript" to log.transcript.trim().take(4000),
            "actionTaken" to log.actionTaken.trim().take(120).ifBlank { "Screened" },
            "durationSeconds" to log.durationSeconds.coerceIn(0L, 36000L),
            "notifiedUser" to log.notifiedUser,
            "acknowledged" to log.acknowledged,
            "isSimulated" to log.isSimulated,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        return try {
            docRef.set(payload).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun getCallLog(userId: String, callId: String): Result<CallLog?> {
        val docRef = callLogsCollection(userId).document(callId)
        return try {
            val snap = docRef.get().await()
            val item = if (snap.exists()) {
                snap.toObject(
                    CallLog::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            } else {
                null
            }
            Result.success(item)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun acknowledgeCallLog(userId: String, callId: String): Result<Unit> {
        val docRef = callLogsCollection(userId).document(callId)
        val updates = mapOf(
            "acknowledged" to true,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        return try {
            docRef.update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteCallLog(userId: String, callId: String): Result<Unit> {
        val docRef = callLogsCollection(userId).document(callId)
        return try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    private fun unwrapFirestoreException(error: Throwable): FirebaseFirestoreException? =
        generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()

    private fun isStillSignedInAs(uid: String): Boolean {
        return try {
            Firebase.auth.currentUser?.uid == uid
        } catch (_: Exception) {
            false
        }
    }
}
