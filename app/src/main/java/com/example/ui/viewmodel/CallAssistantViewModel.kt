package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.LocalRuleCache
import com.example.data.model.AssistantSettings
import com.example.data.model.CallLog
import com.example.data.model.VipContact
import com.example.data.repository.CallAssistantRepository
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

class CallAssistantViewModel(
    private val repository: CallAssistantRepository,
    private val localCache: LocalRuleCache,
    private val currentUserId: String
) : ViewModel() {

    private val _selectedFilter = MutableStateFlow("all")
    val selectedFilter: StateFlow<String> = _selectedFilter.asStateFlow()

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    val settingsState: StateFlow<UiState<AssistantSettings>> =
        repository.observeSettings(currentUserId)
            .map<AssistantSettings?, UiState<AssistantSettings>> { settings ->
                val resolved = settings ?: AssistantSettings(userId = currentUserId)
                localCache.saveSnapshot(resolved, latestVips)
                UiState.Success(resolved)
            }
            .catch { error ->
                Log.w(TAG, "Error observing assistant settings, using local fallback", error)
                val fallback = localCache.getSettings().copy(userId = currentUserId)
                emit(UiState.Success(fallback))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = UiState.Loading
            )

    private var latestVips: List<VipContact> = emptyList()

    val vipContactsState: StateFlow<UiState<List<VipContact>>> =
        repository.observeVipContacts(currentUserId)
            .map<List<VipContact>, UiState<List<VipContact>>> { list ->
                latestVips = list
                val currentSettings = (settingsState.value as? UiState.Success)?.data
                    ?: localCache.getSettings()
                localCache.saveSnapshot(currentSettings, list)
                UiState.Success(list)
            }
            .catch { error ->
                Log.w(TAG, "Error observing VIP contacts, using local list", error)
                emit(UiState.Success(emptyList()))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = UiState.Loading
            )

    val callLogsState: StateFlow<UiState<List<CallLog>>> =
        repository.observeCallLogs(currentUserId)
            .map<List<CallLog>, UiState<List<CallLog>>> { UiState.Success(it) }
            .catch { error ->
                Log.w(TAG, "Error observing call logs, using local list", error)
                emit(UiState.Success(emptyList()))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = UiState.Loading
            )

    val filteredCallsState: StateFlow<UiState<List<CallLog>>> =
        combine(callLogsState, selectedFilter) { state, filter ->
            when (state) {
                is UiState.Loading -> UiState.Loading
                is UiState.Error -> state
                is UiState.Success -> {
                    val items = when (filter) {
                        "all" -> state.data
                        else -> state.data.filter { it.status == filter }
                    }
                    UiState.Success(items)
                }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UiState.Loading
        )

    init {
        viewModelScope.launch {
            repository.ensureDefaultSettings(currentUserId)
        }
    }

    fun setFilter(filter: String) {
        _selectedFilter.value = filter
    }

    fun clearActionMessage() {
        _actionMessage.value = null
    }

    fun toggleAssistantEnabled(enabled: Boolean) {
        val current = (settingsState.value as? UiState.Success)?.data
            ?: AssistantSettings(userId = currentUserId)
        saveSettings(current.copy(assistantEnabled = enabled))
    }

    fun saveSettings(updated: AssistantSettings) {
        viewModelScope.launch {
            localCache.saveSnapshot(updated, latestVips)
            val result = repository.saveSettings(currentUserId, updated)
            result.onSuccess {
                _actionMessage.value = "Assistant settings saved"
            }.onFailure { _ ->
                _actionMessage.value = "Settings saved locally"
            }
        }
    }

    fun addVipContact(name: String, phone: String, priorityLevel: String, note: String) {
        if (name.isBlank() || phone.isBlank()) {
            _actionMessage.value = "Name and phone number are required"
            return
        }
        viewModelScope.launch {
            repository.addVipContact(currentUserId, name, phone, priorityLevel, note)
                .onSuccess { _actionMessage.value = "Saved VIP rule for $name" }
                .onFailure { _actionMessage.value = it.localizedMessage ?: "Failed to add VIP rule" }
        }
    }

    fun deleteVipContact(contactId: String) {
        viewModelScope.launch {
            repository.deleteVipContact(currentUserId, contactId)
                .onFailure { _actionMessage.value = it.localizedMessage ?: "Failed to delete rule" }
        }
    }

    fun acknowledgeCall(callId: String) {
        if (callId.isBlank()) return
        viewModelScope.launch {
            repository.acknowledgeCallLog(currentUserId, callId)
                .onFailure { _actionMessage.value = it.localizedMessage ?: "Failed to update call" }
        }
    }

    fun deleteCallLog(callId: String) {
        if (callId.isBlank()) return
        viewModelScope.launch {
            repository.deleteCallLog(currentUserId, callId)
                .onSuccess { _actionMessage.value = "Call record deleted" }
                .onFailure { _actionMessage.value = it.localizedMessage ?: "Failed to delete record" }
        }
    }

    private fun unwrapFirestoreException(error: Throwable): FirebaseFirestoreException? =
        generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()

    private companion object {
        const val TAG = "CallAssistantVM"
        const val STOP_TIMEOUT_MILLIS = 5000L
    }
}
