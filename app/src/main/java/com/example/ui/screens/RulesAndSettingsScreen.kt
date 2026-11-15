package com.example.ui.screens

import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.AssistantSettings
import com.example.data.model.VipContact
import com.example.ui.viewmodel.UiState

@Composable
fun RulesAndSettingsScreen(
    settings: AssistantSettings,
    vipContactsState: UiState<List<VipContact>>,
    hasScreeningRole: Boolean,
    hasCallPermissions: Boolean,
    userEmail: String?,
    onSaveSettings: (AssistantSettings) -> Unit,
    onAddVip: (name: String, phone: String, priority: String, note: String) -> Unit,
    onDeleteVip: (String) -> Unit,
    onRequestPermissions: () -> Unit,
    onSignOut: () -> Unit
) {
    var assistantName by remember(settings.assistantName) { mutableStateOf(settings.assistantName) }
    var autoAnswerMode by remember(settings.autoAnswerMode) { mutableStateOf(settings.autoAnswerMode) }
    var language by remember(settings.assistantLanguage) { mutableStateOf(settings.assistantLanguage) }
    var greetingPrompt by remember(settings.greetingPrompt) { mutableStateOf(settings.greetingPrompt) }
    var importanceRules by remember(settings.importanceRules) { mutableStateOf(settings.importanceRules) }
    var notifyUrgentOnly by remember(settings.notifyUrgentOnly) { mutableStateOf(settings.notifyUrgentOnly) }

    LaunchedEffect(settings) {
        assistantName = settings.assistantName
        autoAnswerMode = settings.autoAnswerMode
        language = settings.assistantLanguage
        greetingPrompt = settings.greetingPrompt
        importanceRules = settings.importanceRules
        notifyUrgentOnly = settings.notifyUrgentOnly
    }

    var showAddVipDialog by remember { mutableStateOf(false) }

    if (showAddVipDialog) {
        AddVipDialog(
            onDismiss = { showAddVipDialog = false },
            onSave = { name, phone, priority, note ->
                onAddVip(name, phone, priority, note)
                showAddVipDialog = false
            }
        )
    }

    val vipList = (vipContactsState as? UiState.Success)?.data.orEmpty()

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = "AI Rules & Settings",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            item {
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
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Assistant Persona & Script",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )

                        OutlinedTextField(
                            value = assistantName,
                            onValueChange = { assistantName = it },
                            label = { Text("Assistant Name") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text(
                            text = "Voice Language",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                AssistantSettings.LANG_BILINGUAL to "বাংলা + EN",
                                AssistantSettings.LANG_BENGALI to "বাংলা",
                                AssistantSettings.LANG_ENGLISH to "English"
                            ).forEach { (key, label) ->
                                FilterChip(
                                    selected = language == key,
                                    onClick = { language = key },
                                    label = { Text(label) }
                                )
                            }
                        }

                        Text(
                            text = "Call Answering Mode",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                AssistantSettings.MODE_ALL to "All Calls",
                                AssistantSettings.MODE_UNKNOWN_ONLY to "Unknown",
                                AssistantSettings.MODE_SCREENING_ONLY to "Screen Only"
                            ).forEach { (key, label) ->
                                FilterChip(
                                    selected = autoAnswerMode == key,
                                    onClick = { autoAnswerMode = key },
                                    label = { Text(label) }
                                )
                            }
                        }

                        OutlinedTextField(
                            value = greetingPrompt,
                            onValueChange = { greetingPrompt = it },
                            label = { Text("Opening Greeting (অভ্যর্থনা বার্তা)") },
                            minLines = 2,
                            maxLines = 4,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = importanceRules,
                            onValueChange = { importanceRules = it },
                            label = { Text("What Counts as Important? (জরুরি কলের নিয়ম)") },
                            minLines = 2,
                            maxLines = 4,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Notify Only Urgent Calls",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "শুধুমাত্র গুরুত্বপূর্ণ কল হলেই সাউন্ড অ্যালার্ট দিবে",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = notifyUrgentOnly,
                                onCheckedChange = { notifyUrgentOnly = it }
                            )
                        }

                        Button(
                            onClick = {
                                onSaveSettings(
                                    settings.copy(
                                        assistantName = assistantName,
                                        autoAnswerMode = autoAnswerMode,
                                        assistantLanguage = language,
                                        greetingPrompt = greetingPrompt,
                                        importanceRules = importanceRules,
                                        notifyUrgentOnly = notifyUrgentOnly
                                    )
                                )
                            },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("save_settings_button")
                        ) {
                            Icon(Icons.Rounded.Save, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Save Assistant Rules")
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "VIP & Block Rules",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "নির্দিষ্ট নম্বরের জন্য প্রায়োরিটি বা ব্লক সেট করুন",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = { showAddVipDialog = true },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("add_vip_button")
                    ) {
                        Icon(
                            Icons.Rounded.Add,
                            contentDescription = stringResource(R.string.cd_add_vip)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add")
                    }
                }
            }

            if (vipList.isEmpty()) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                RoundedCornerShape(16.dp)
                            )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.PersonAdd,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "No VIP or blocked numbers yet. Tap Add to prioritize family, doctors, or block spam.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(vipList, key = { it.id }) { vip ->
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                RoundedCornerShape(16.dp)
                            )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${vip.name} (${vip.priorityLevel.uppercase()})",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (vip.priorityLevel == VipContact.PRIORITY_BLOCK) {
                                        MaterialTheme.colorScheme.tertiary
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    }
                                )
                                Text(
                                    text = vip.phoneNumber,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (vip.note.isNotBlank()) {
                                    Text(
                                        text = vip.note,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            IconButton(
                                onClick = { onDeleteVip(vip.id) },
                                modifier = Modifier.testTag("delete_vip_${vip.id}")
                            ) {
                                Icon(
                                    Icons.Rounded.DeleteOutline,
                                    contentDescription = "Delete VIP rule",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            item {
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
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Device Integration & Account",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (hasScreeningRole && hasCallPermissions) {
                                    Icons.Rounded.CheckCircle
                                } else {
                                    Icons.Rounded.Security
                                },
                                contentDescription = null,
                                tint = if (hasScreeningRole && hasCallPermissions) {
                                    MaterialTheme.colorScheme.secondary
                                } else {
                                    MaterialTheme.colorScheme.tertiary
                                },
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (hasScreeningRole && hasCallPermissions) {
                                    "Call Screening Role & Permissions Active"
                                } else {
                                    "Call Role or Permissions Pending"
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        if (!hasScreeningRole || !hasCallPermissions) {
                            OutlinedButton(
                                onClick = onRequestPermissions,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Grant Call Screening & Audio Permissions")
                            }
                        }

                        if (!userEmail.isNullOrBlank()) {
                            Text(
                                text = "Signed in as $userEmail",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        OutlinedButton(
                            onClick = onSignOut,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("sign_out_button")
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Sign Out")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddVipDialog(
    onDismiss: () -> Unit,
    onSave: (String, String, String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(VipContact.PRIORITY_CRITICAL) }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add VIP / Block Rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.testTag("vip_name_input")
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Phone Number") },
                    singleLine = true,
                    modifier = Modifier.testTag("vip_phone_input")
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        VipContact.PRIORITY_CRITICAL to "Critical",
                        VipContact.PRIORITY_HIGH to "High",
                        VipContact.PRIORITY_BLOCK to "Block"
                    ).forEach { (key, label) ->
                        FilterChip(
                            selected = priority == key,
                            onClick = { priority = key },
                            label = { Text(label) }
                        )
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name, phone, priority, note) },
                enabled = name.isNotBlank() && phone.isNotBlank(),
                modifier = Modifier.testTag("vip_save_button")
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
