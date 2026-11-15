package com.example

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.core.configureAppCheck
import com.example.data.local.LocalRuleCache
import com.example.data.model.AssistantSettings
import com.example.data.model.CallLog
import com.example.data.repository.CallAssistantRepository
import com.example.notification.CallNotificationHelper
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.signOut
import com.example.ui.screens.CallsInboxScreen
import com.example.ui.screens.LiveCallScreen
import com.example.ui.screens.RulesAndSettingsScreen
import com.example.ui.theme.CallGuardTheme
import com.example.ui.viewmodel.CallAssistantViewModel
import com.example.ui.viewmodel.LiveCallViewModel
import com.example.ui.viewmodel.UiState
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Configure App Check with launch intent before any UI, ViewModels, or Firebase calls
        configureAppCheck(this, intent)
        CallNotificationHelper.ensureChannels(this)
        enableEdgeToEdge()

        val initialCallId = intent?.getStringExtra(CallNotificationHelper.EXTRA_CALL_LOG_ID)

        setContent {
            CallGuardTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(initialCallId = initialCallId)
                }
            }
        }
    }
}

@Composable
fun AppNavigation(
    auth: FirebaseAuth = Firebase.auth,
    initialCallId: String? = null
) {
    var currentUser by remember { mutableStateOf(auth.currentUser) }

    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            currentUser = firebaseAuth.currentUser
        }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }

    val user = currentUser
    if (user == null) {
        AuthScreen(onAuthSuccess = { currentUser = auth.currentUser })
    } else {
        val uid = user.uid
        key(uid) {
            MainAuthenticatedScaffold(
                currentUserId = uid,
                userEmail = user.email,
                initialCallId = initialCallId
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainAuthenticatedScaffold(
    currentUserId: String,
    userEmail: String?,
    initialCallId: String?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val assistantViewModel: CallAssistantViewModel = viewModel(
        key = "assistant_$currentUserId",
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                CallAssistantViewModel(
                    repository = CallAssistantRepository(db),
                    localCache = LocalRuleCache(app),
                    currentUserId = currentUserId
                )
            }
        }
    )

    val liveCallViewModel: LiveCallViewModel = viewModel(
        key = "live_$currentUserId",
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                LiveCallViewModel(
                    repository = CallAssistantRepository(db),
                    localCache = LocalRuleCache(app),
                    currentUserId = currentUserId
                )
            }
        }
    )

    val settingsState by assistantViewModel.settingsState.collectAsStateWithLifecycle()
    val allLogsState by assistantViewModel.callLogsState.collectAsStateWithLifecycle()
    val filteredLogsState by assistantViewModel.filteredCallsState.collectAsStateWithLifecycle()
    val vipContactsState by assistantViewModel.vipContactsState.collectAsStateWithLifecycle()
    val selectedFilter by assistantViewModel.selectedFilter.collectAsStateWithLifecycle()
    val actionMessage by assistantViewModel.actionMessage.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(actionMessage) {
        val msg = actionMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            assistantViewModel.clearActionMessage()
        }
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedCallDetail by remember { mutableStateOf<CallLog?>(null) }

    LaunchedEffect(initialCallId, allLogsState) {
        if (!initialCallId.isNullOrBlank() && selectedCallDetail == null) {
            val logs = (allLogsState as? UiState.Success)?.data.orEmpty()
            selectedCallDetail = logs.firstOrNull { it.id == initialCallId }
        }
    }

    var hasScreeningRole by remember { mutableStateOf(checkScreeningRole(context)) }
    var hasCallPermissions by remember { mutableStateOf(checkCallPermissions(context)) }

    val roleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        hasScreeningRole = checkScreeningRole(context)
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasCallPermissions = checkCallPermissions(context)
    }

    val requestAllPermissionsAndRole: () -> Unit = {
        val perms = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(Manifest.permission.ANSWER_PHONE_CALLS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
        permissionsLauncher.launch(perms)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager != null &&
                roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
            ) {
                roleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
            }
        }
    }

    val settings = (settingsState as? UiState.Success)?.data ?: AssistantSettings(userId = currentUserId)
    val vipContacts = (vipContactsState as? UiState.Success)?.data.orEmpty()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge
                        )
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            },
            bottomBar = {
                if (!useRail) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.navigationBarsPadding()
                    ) {
                        NavigationBarItem(
                            selected = selectedTab == 0,
                            onClick = {
                                selectedCallDetail = null
                                selectedTab = 0
                            },
                            icon = {
                                Icon(
                                    if (selectedTab == 0) Icons.Rounded.Call else Icons.Outlined.Call,
                                    contentDescription = null
                                )
                            },
                            label = { Text(stringResource(R.string.nav_calls)) },
                            modifier = Modifier.testTag("nav_tab_calls")
                        )
                        NavigationBarItem(
                            selected = selectedTab == 1,
                            onClick = {
                                selectedCallDetail = null
                                selectedTab = 1
                            },
                            icon = {
                                Icon(
                                    if (selectedTab == 1) Icons.Rounded.RecordVoiceOver else Icons.Outlined.RecordVoiceOver,
                                    contentDescription = null
                                )
                            },
                            label = { Text(stringResource(R.string.nav_live_ai)) },
                            modifier = Modifier.testTag("nav_tab_live_ai")
                        )
                        NavigationBarItem(
                            selected = selectedTab == 2,
                            onClick = {
                                selectedCallDetail = null
                                selectedTab = 2
                            },
                            icon = {
                                Icon(
                                    if (selectedTab == 2) Icons.Rounded.Tune else Icons.Outlined.Tune,
                                    contentDescription = null
                                )
                            },
                            label = { Text(stringResource(R.string.nav_rules)) },
                            modifier = Modifier.testTag("nav_tab_rules")
                        )
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                        NavigationRailItem(
                            selected = selectedTab == 0,
                            onClick = {
                                selectedCallDetail = null
                                selectedTab = 0
                            },
                            icon = {
                                Icon(
                                    if (selectedTab == 0) Icons.Rounded.Call else Icons.Outlined.Call,
                                    contentDescription = null
                                )
                            },
                            label = { Text(stringResource(R.string.nav_calls)) }
                        )
                        NavigationRailItem(
                            selected = selectedTab == 1,
                            onClick = {
                                selectedCallDetail = null
                                selectedTab = 1
                            },
                            icon = {
                                Icon(
                                    if (selectedTab == 1) Icons.Rounded.RecordVoiceOver else Icons.Outlined.RecordVoiceOver,
                                    contentDescription = null
                                )
                            },
                            label = { Text(stringResource(R.string.nav_live_ai)) }
                        )
                        NavigationRailItem(
                            selected = selectedTab == 2,
                            onClick = {
                                selectedCallDetail = null
                                selectedTab = 2
                            },
                            icon = {
                                Icon(
                                    if (selectedTab == 2) Icons.Rounded.Tune else Icons.Outlined.Tune,
                                    contentDescription = null
                                )
                            },
                            label = { Text(stringResource(R.string.nav_rules)) }
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f).fillMaxSize()) {
                    when (selectedTab) {
                        0 -> CallsInboxScreen(
                            settingsState = settingsState,
                            allLogsState = allLogsState,
                            filteredLogsState = filteredLogsState,
                            selectedFilter = selectedFilter,
                            selectedCall = selectedCallDetail,
                            hasScreeningRole = hasScreeningRole,
                            hasCallPermissions = hasCallPermissions,
                            onSelectFilter = assistantViewModel::setFilter,
                            onToggleAssistant = assistantViewModel::toggleAssistantEnabled,
                            onOpenCallDetail = { selectedCallDetail = it },
                            onCloseCallDetail = { selectedCallDetail = null },
                            onAcknowledgeCall = assistantViewModel::acknowledgeCall,
                            onDeleteCall = assistantViewModel::deleteCallLog,
                            onStartLiveTest = { selectedTab = 1 },
                            onRequestPermissions = requestAllPermissionsAndRole
                        )

                        1 -> LiveCallScreen(
                            liveViewModel = liveCallViewModel,
                            settings = settings,
                            vipContacts = vipContacts
                        )

                        2 -> RulesAndSettingsScreen(
                            settings = settings,
                            vipContactsState = vipContactsState,
                            hasScreeningRole = hasScreeningRole,
                            hasCallPermissions = hasCallPermissions,
                            userEmail = userEmail,
                            onSaveSettings = assistantViewModel::saveSettings,
                            onAddVip = assistantViewModel::addVipContact,
                            onDeleteVip = assistantViewModel::deleteVipContact,
                            onRequestPermissions = requestAllPermissionsAndRole,
                            onSignOut = {
                                signOut(
                                    credentialManager = credentialManager,
                                    scope = scope
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun checkScreeningRole(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
    val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
    return !roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) ||
        roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
}

private fun checkCallPermissions(context: Context): Boolean {
    val mic = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
    val phoneState = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.READ_PHONE_STATE
    ) == PackageManager.PERMISSION_GRANTED
    val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
    return mic && phoneState && notif
}
