package com.example.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.repository.CallAssistantRepository
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object CallNotificationHelper {
    const val CHANNEL_IMPORTANT = "callguard_important_calls"
    const val CHANNEL_ROUTINE = "callguard_screened_calls"
    const val EXTRA_CALL_LOG_ID = "extra_call_log_id"
    const val ACTION_ACKNOWLEDGE_CALL = "com.example.ACTION_ACKNOWLEDGE_CALL"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return

            val urgentChannel = NotificationChannel(
                CHANNEL_IMPORTANT,
                "Important Call Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High-priority alerts when CallGuard AI detects an urgent call."
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 150, 300, 150, 400)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    audioAttributes
                )
            }

            val routineChannel = NotificationChannel(
                CHANNEL_ROUTINE,
                "Screened Call Summaries",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Quiet summaries of routine or spam calls handled by CallGuard AI."
            }

            manager.createNotificationChannel(urgentChannel)
            manager.createNotificationChannel(routineChannel)
        }
    }

    fun notifyCallResult(
        context: Context,
        callId: String,
        callerName: String,
        callerNumber: String,
        summary: String,
        importanceScore: Int,
        isImportant: Boolean
    ) {
        ensureChannels(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.w("CallNotify", "POST_NOTIFICATIONS not granted; skipping system notification.")
                return
            }
        }

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CALL_LOG_ID, callId)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            callId.hashCode(),
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dialIntent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:${Uri.encode(callerNumber)}")
        }
        val dialPendingIntent = PendingIntent.getActivity(
            context,
            ("dial_$callId").hashCode(),
            dialIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val ackIntent = Intent(context, CallActionReceiver::class.java).apply {
            action = ACTION_ACKNOWLEDGE_CALL
            putExtra(EXTRA_CALL_LOG_ID, callId)
            putExtra("notification_id", callId.hashCode())
        }
        val ackPendingIntent = PendingIntent.getBroadcast(
            context,
            ("ack_$callId").hashCode(),
            ackIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = if (isImportant) CHANNEL_IMPORTANT else CHANNEL_ROUTINE
        val title = if (isImportant) {
            "গুরুত্বপূর্ণ কল ($importanceScore%): $callerName"
        } else {
            "AI কল সারাংশ: $callerName"
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$callerNumber\n$summary")
            )
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .setPriority(
                if (isImportant) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT
            )
            .setCategory(
                if (isImportant) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_STATUS
            )
            .addAction(android.R.drawable.ic_menu_call, "Call Back", dialPendingIntent)

        if (callId.isNotBlank()) {
            builder.addAction(android.R.drawable.ic_menu_agenda, "Mark Read", ackPendingIntent)
        }

        if (isImportant) {
            val callerPerson = Person.Builder()
                .setName(callerName)
                .setImportant(true)
                .build()
            builder.addPerson(callerPerson)
        }

        NotificationManagerCompat.from(context).notify(callId.hashCode(), builder.build())
    }
}

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CallNotificationHelper.ACTION_ACKNOWLEDGE_CALL) return
        val callId = intent.getStringExtra(CallNotificationHelper.EXTRA_CALL_LOG_ID) ?: return
        val notifId = intent.getIntExtra("notification_id", callId.hashCode())
        NotificationManagerCompat.from(context).cancel(notifId)

        val uid = try {
            Firebase.auth.currentUser?.uid
        } catch (_: Exception) {
            null
        } ?: return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                CallAssistantRepository(context).acknowledgeCallLog(uid, callId)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
