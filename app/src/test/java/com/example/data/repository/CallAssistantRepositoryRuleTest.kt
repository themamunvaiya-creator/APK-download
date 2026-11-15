package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.AssistantSettings
import com.example.data.model.CallLog
import com.example.data.model.VipContact
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CallAssistantRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun ensureDefaultSettings_authenticatedOwner_succeedsAndObserves() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repository = CallAssistantRepository(firestore)

        val result = withTimeout(DEFAULT_TIMEOUT_MS) { repository.ensureDefaultSettings(uid) }
        assertTrue(result.isSuccess)

        val observed = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeSettings(uid).first { it != null }
        }
        assertNotNull(observed)
        assertEquals(true, observed?.assistantEnabled)
        assertEquals("Aria", observed?.assistantName)
    }

    @Test
    fun addAndObserveCallLog_authenticatedOwner_succeeds() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repository = CallAssistantRepository(firestore)

        val log = CallLog(
            userId = uid,
            callerName = "Dr. Sen",
            callerNumber = "+8801711000222",
            status = CallLog.STATUS_IMPORTANT,
            importanceScore = 92L,
            aiSummary = "Urgent medical test results ready.",
            transcript = "AI: Hello\nCaller: Doctor calling about urgent report.",
            actionTaken = "Urgent Notification Sent",
            durationSeconds = 28L,
            notifiedUser = true,
            acknowledged = false,
            isSimulated = true
        )

        val createResult = withTimeout(DEFAULT_TIMEOUT_MS) { repository.addCallLog(uid, log) }
        assertTrue(createResult.isSuccess)
        val callId = createResult.getOrThrow()

        val logs = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeCallLogs(uid).first { list -> list.any { it.id == callId } }
        }
        assertTrue(logs.any { it.id == callId && it.status == CallLog.STATUS_IMPORTANT })

        val ackResult = withTimeout(DEFAULT_TIMEOUT_MS) { repository.acknowledgeCallLog(uid, callId) }
        assertTrue(ackResult.isSuccess)
    }

    @Test
    fun addVipContact_andCrossUserRead_deniedWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = CallAssistantRepository(firestore)

        val createVip = withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.addVipContact(
                userId = aliceUid,
                name = "Mom",
                phoneNumber = "+8801811000001",
                priorityLevel = VipContact.PRIORITY_CRITICAL,
                note = "Always urgent"
            )
        }
        assertTrue(createVip.isSuccess)

        val logId = withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.addCallLog(
                aliceUid,
                CallLog(
                    userId = aliceUid,
                    callerName = "Unknown",
                    callerNumber = "+8801900000000",
                    status = CallLog.STATUS_ROUTINE,
                    importanceScore = 25L,
                    aiSummary = "Routine delivery inquiry.",
                    transcript = "AI: Hello\nCaller: Delivery info.",
                    actionTaken = "Screened & Logged",
                    durationSeconds = 15L,
                    notifiedUser = false,
                    acknowledged = false,
                    isSimulated = true
                )
            ).getOrThrow()
        }

        // Switch to Bob and verify cross-user access is denied
        signInTestUser(BOB_EMAIL)
        val bobRepo = CallAssistantRepository(firestore)
        val bobRead = withTimeout(DEFAULT_TIMEOUT_MS) { bobRepo.getCallLog(aliceUid, logId) }
        assertTrue(bobRead.isFailure)
        val firestoreErr = bobRead.exceptionOrNull()?.unwrapFirestoreException()
        assertNotNull(firestoreErr)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreErr?.code)
    }

    @Test
    fun observeCallLogs_unauthenticatedUser_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repository = CallAssistantRepository(firestore)

        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repository.observeCallLogs("alice_unauth_target").first()
            }
            fail("Expected PERMISSION_DENIED for unauthenticated user")
        } catch (e: Exception) {
            val firestoreErr = e.unwrapFirestoreException()
            assertNotNull("Expected nested FirebaseFirestoreException, got $e", firestoreErr)
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreErr?.code)
        }
    }

    private companion object {
        const val ALICE_EMAIL = "alice_callguard@test.com"
        const val BOB_EMAIL = "bob_callguard@test.com"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 3000L
    }
}
