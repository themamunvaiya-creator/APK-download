package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.model.AssistantSettings
import com.example.data.model.CallLog
import com.example.data.model.VipContact
import com.example.ui.viewmodel.CallPhase
import com.example.ui.viewmodel.LiveCallUiState
import com.example.ui.viewmodel.LiveCallViewModel
import com.example.voice.VoiceInteractionManager

private data class CallerScenarioPreset(
    val chipLabel: String,
    val callerName: String,
    val callerNumber: String,
    val sampleLine: String
)

private val SCENARIO_PRESETS = listOf(
    CallerScenarioPreset(
        chipLabel = "Hospital Urgent",
        callerName = "City Hospital Desk",
        callerNumber = "+8801711000999",
        sampleLine = "হ্যালো, সিটি হসপিটাল থেকে বলছি। আপনার বাবার জরুরি রিপোর্ট চলে এসেছে, এখনই কলব্যাক করুন।"
    ),
    CallerScenarioPreset(
        chipLabel = "Delivery Gate",
        callerName = "Parcel Rider",
        callerNumber = "+8801812345678",
        sampleLine = "ভাই আমি গেটের নিচে আপনার পার্সেল নিয়ে দাঁড়িয়ে আছি।"
    ),
    CallerScenarioPreset(
        chipLabel = "Spam Loan Offer",
        callerName = "Unknown Telemarketer",
        callerNumber = "+8801999888777",
        sampleLine = "Congratulations! You are pre-approved for an instant personal loan today, press 1."
    ),
    CallerScenarioPreset(
        chipLabel = "Casual Friend",
        callerName = "Tanvir",
        callerNumber = "+8801611223344",
        sampleLine = "দোস্ত ফ্রি আছিস? বিকেলে চায়ের আড্ডায় আসবি নাকি?"
    )
)

@Composable
fun LiveCallScreen(
    liveViewModel: LiveCallViewModel,
    settings: AssistantSettings,
    vipContacts: List<VipContact>
) {
    val context = LocalContext.current
    val state by liveViewModel.uiState.collectAsStateWithLifecycle()
    val voiceManager = remember(context) { VoiceInteractionManager(context) }

    DisposableEffect(voiceManager) {
        onDispose { voiceManager.release() }
    }

    val isSpeaking by voiceManager.isSpeaking.collectAsStateWithLifecycle()
    val isListening by voiceManager.isListening.collectAsStateWithLifecycle()
    val partialTranscript by voiceManager.partialTranscript.collectAsStateWithLifecycle()

    var callerInputText by remember { mutableStateOf("") }
    var voiceError by remember { mutableStateOf<String?>(null) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val langTag = if (settings.assistantLanguage == AssistantSettings.LANG_ENGLISH) {
                "en-US"
            } else {
                "bn-BD"
            }
            voiceManager.startListening(
                languageTag = langTag,
                onResult = { spoken ->
                    voiceError = null
                    liveViewModel.sendCallerSpeech(
                        utterance = spoken,
                        settings = settings,
                        vipContacts = vipContacts,
                        voiceManager = voiceManager,
                        appContext = context.applicationContext
                    )
                },
                onError = { err -> voiceError = err }
            )
        } else {
            voiceError = "Microphone permission denied. Type caller speech below."
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp)
                .padding(16.dp)
        ) {
            if (state.phase == CallPhase.IDLE) {
                IdleCallSetupView(
                    state = state,
                    onCallerInfoChange = liveViewModel::updateCallerInfo,
                    onPickPreset = { preset ->
                        liveViewModel.updateCallerInfo(preset.callerName, preset.callerNumber)
                        callerInputText = preset.sampleLine
                    },
                    onStartCall = {
                        voiceError = null
                        liveViewModel.startIncomingCallSession(
                            callerName = state.callerName,
                            callerNumber = state.callerNumber,
                            settings = settings,
                            voiceManager = voiceManager
                        )
                    }
                )
            } else {
                ActiveVoiceCallHeader(
                    state = state,
                    isSpeaking = isSpeaking,
                    isListening = isListening,
                    onEndCall = {
                        liveViewModel.endAndSaveCall(
                            appContext = context.applicationContext,
                            settings = settings,
                            voiceManager = voiceManager
                        )
                    },
                    onResetCall = {
                        callerInputText = ""
                        voiceError = null
                        liveViewModel.resetCall()
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))

                AnimatedVisibility(visible = state.notificationSent) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                RoundedCornerShape(14.dp)
                            )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.NotificationsActive,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Important Call Notification Dispatched!",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = state.latestSummary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }

                if (state.errorMessage != null || voiceError != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        Text(
                            text = state.errorMessage ?: voiceError.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(state.messages) { msg ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (msg.isAi) Arrangement.Start else Arrangement.End
                        ) {
                            Surface(
                                color = if (msg.isAi) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                shape = RoundedCornerShape(
                                    topStart = 18.dp,
                                    topEnd = 18.dp,
                                    bottomStart = if (msg.isAi) 4.dp else 18.dp,
                                    bottomEnd = if (msg.isAi) 18.dp else 4.dp
                                ),
                                modifier = Modifier.widthIn(max = 310.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = if (msg.isAi) settings.assistantName else state.callerName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (msg.isAi) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = msg.text,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (msg.isAi) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (isListening && partialTranscript.isNotBlank()) {
                        item {
                            Text(
                                text = "Listening: $partialTranscript...",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    if (state.isAiThinking) {
                        item {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = "${settings.assistantName} is thinking...",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                if (state.phase == CallPhase.ACTIVE_CALL) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SCENARIO_PRESETS.forEach { preset ->
                            AssistChip(
                                onClick = { callerInputText = preset.sampleLine },
                                label = { Text(preset.chipLabel) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledIconButton(
                            onClick = {
                                if (isListening) {
                                    voiceManager.stopListening()
                                } else {
                                    val hasMic = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (hasMic) {
                                        val langTag =
                                            if (settings.assistantLanguage == AssistantSettings.LANG_ENGLISH) {
                                                "en-US"
                                            } else {
                                                "bn-BD"
                                            }
                                        voiceManager.startListening(
                                            languageTag = langTag,
                                            onResult = { spoken ->
                                                voiceError = null
                                                liveViewModel.sendCallerSpeech(
                                                    utterance = spoken,
                                                    settings = settings,
                                                    vipContacts = vipContacts,
                                                    voiceManager = voiceManager,
                                                    appContext = context.applicationContext
                                                )
                                            },
                                            onError = { err -> voiceError = err }
                                        )
                                    } else {
                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                            },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = if (isListening) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer
                                }
                            ),
                            modifier = Modifier
                                .size(50.dp)
                                .testTag("live_call_mic_button")
                        ) {
                            Icon(
                                imageVector = if (isListening) Icons.Rounded.Stop else Icons.Rounded.Mic,
                                contentDescription = stringResource(R.string.cd_mic_speak)
                            )
                        }

                        OutlinedTextField(
                            value = callerInputText,
                            onValueChange = { callerInputText = it },
                            placeholder = { Text("Speak or type as caller...") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("live_call_input")
                        )

                        FilledIconButton(
                            onClick = {
                                val text = callerInputText
                                callerInputText = ""
                                voiceError = null
                                liveViewModel.sendCallerSpeech(
                                    utterance = text,
                                    settings = settings,
                                    vipContacts = vipContacts,
                                    voiceManager = voiceManager,
                                    appContext = context.applicationContext
                                )
                            },
                            enabled = callerInputText.isNotBlank() && !state.isAiThinking,
                            modifier = Modifier
                                .size(50.dp)
                                .testTag("live_call_send_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Send,
                                contentDescription = stringResource(R.string.cd_send_reply)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IdleCallSetupView(
    state: LiveCallUiState,
    onCallerInfoChange: (String, String) -> Unit,
    onPickPreset: (CallerScenarioPreset) -> Unit,
    onStartCall: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Live AI Voice Call Test",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "কল রিসিভ করলে আপনার AI অ্যাসিস্ট্যান্ট কীভাবে বাংলা/ইংরেজিতে কথা বলে এবং জরুরি কল হলে নোটিফিকেশন পাঠায় তা এখানে লাইভ পরীক্ষা করুন।",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = "Quick Caller Presets",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SCENARIO_PRESETS.forEach { preset ->
                AssistChip(
                    onClick = { onPickPreset(preset) },
                    label = { Text(preset.chipLabel) }
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    RoundedCornerShape(20.dp)
                )
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = state.callerName,
                    onValueChange = { onCallerInfoChange(it, state.callerNumber) },
                    label = { Text("Caller Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("test_caller_name_input")
                )
                OutlinedTextField(
                    value = state.callerNumber,
                    onValueChange = { onCallerInfoChange(state.callerName, it) },
                    label = { Text("Caller Number") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("test_caller_number_input")
                )

                Button(
                    onClick = onStartCall,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("start_ai_call_button")
                ) {
                    Icon(Icons.Rounded.PhoneInTalk, contentDescription = null)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Answer Call with AI",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveVoiceCallHeader(
    state: LiveCallUiState,
    isSpeaking: Boolean,
    isListening: Boolean,
    onEndCall: () -> Unit,
    onResetCall: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (isSpeaking || isListening) 1.12f else 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(750),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val scoreColor = when (state.classification) {
        CallLog.STATUS_IMPORTANT -> MaterialTheme.colorScheme.error
        CallLog.STATUS_SPAM -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, scoreColor.copy(alpha = 0.55f), RoundedCornerShape(22.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .border(2.dp, scoreColor, CircleShape)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_assistant_avatar),
                        contentDescription = stringResource(R.string.cd_assistant_avatar),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.callerName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = when {
                            state.phase == CallPhase.ENDED -> "Call Completed • Saved to Inbox"
                            isSpeaking -> "AI Speaking..."
                            isListening -> "Listening to Caller..."
                            else -> state.callerNumber
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = scoreColor
                    )
                }

                if (state.phase == CallPhase.ACTIVE_CALL) {
                    Button(
                        onClick = onEndCall,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("end_live_call_button")
                    ) {
                        Icon(
                            Icons.Rounded.CallEnd,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("End", style = MaterialTheme.typography.labelLarge)
                    }
                } else {
                    Button(
                        onClick = onResetCall,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("new_live_call_button")
                    ) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("New Test", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Urgency Assessment",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${state.importanceScore}% (${state.classification.uppercase()})",
                    style = MaterialTheme.typography.labelLarge,
                    color = scoreColor
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { (state.importanceScore / 100f).coerceIn(0f, 1f) },
                color = scoreColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
        }
    }
}
