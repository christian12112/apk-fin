package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.db.ChatDatabase
import com.example.data.db.ChatMessage
import com.example.data.db.ChatSession
import com.example.data.repository.ChatRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val database: ChatDatabase by lazy {
        androidx.room.Room.databaseBuilder(
            application,
            ChatDatabase::class.java,
            "aura_chat_database"
        ).fallbackToDestructiveMigration().build()
    }

    val repository: ChatRepository by lazy {
        ChatRepository(application, database.chatDao())
    }

    // Theme state
    private val _isDarkTheme = MutableStateFlow(repository.isDarkTheme())
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()

    // Key management state
    private val _savedApiKey = MutableStateFlow(repository.getSavedApiKey() ?: "")
    val savedApiKey: StateFlow<String> = _savedApiKey.asStateFlow()

    // Session state
    val sessions: StateFlow<List<ChatSession>> = repository.allSessions
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _selectedSessionId = MutableStateFlow<Int?>(null)
    val selectedSessionId: StateFlow<Int?> = _selectedSessionId.asStateFlow()

    // Message lists
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // Composition state
    private val _currentInputText = MutableStateFlow("")
    val currentInputText: StateFlow<String> = _currentInputText.asStateFlow()

    // Picked Image state (Base64 JPEG)
    private val _selectedImageBase64 = MutableStateFlow<String?>(null)
    val selectedImageBase64: StateFlow<String?> = _selectedImageBase64.asStateFlow()

    // Loading indicator
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Voice dictation state
    private val _isListeningDictation = MutableStateFlow(false)
    val isListeningDictation: StateFlow<Boolean> = _isListeningDictation.asStateFlow()

    private val _isListeningVoiceMessage = MutableStateFlow(false)
    val isListeningVoiceMessage: StateFlow<Boolean> = _isListeningVoiceMessage.asStateFlow()

    // Dialogs/Settings inputs
    private val _showSettingsDialog = MutableStateFlow(false)
    val showSettingsDialog: StateFlow<Boolean> = _showSettingsDialog.asStateFlow()

    private val _showSyncDialog = MutableStateFlow(false)
    val showSyncDialog: StateFlow<Boolean> = _showSyncDialog.asStateFlow()

    private val _showImportDialog = MutableStateFlow(false)
    val showImportDialog: StateFlow<Boolean> = _showImportDialog.asStateFlow()

    private val _importJsonDraft = MutableStateFlow("")
    val importJsonDraft: StateFlow<String> = _importJsonDraft.asStateFlow()

    private val _speechDictationPartial = MutableStateFlow("")
    val speechDictationPartial: StateFlow<String> = _speechDictationPartial.asStateFlow()

    private val _toastMessage = MutableSharedFlow<String>()
    val toastMessage = _toastMessage.asSharedFlow()

    init {
        // Automatically fetch messages whenever selected session changes
        viewModelScope.launch {
            _selectedSessionId.collectLatest { sessionId ->
                if (sessionId != null) {
                    repository.getMessagesForSession(sessionId).collect { list ->
                        _messages.value = list
                    }
                } else {
                    _messages.value = emptyList()
                }
            }
        }

        // Auto-select the first session if empty or when list is populated
        viewModelScope.launch {
            sessions.collect { sessionList ->
                if (_selectedSessionId.value == null && sessionList.isNotEmpty()) {
                    _selectedSessionId.value = sessionList.first().id
                }
            }
        }
    }

    fun selectSession(sessionId: Int) {
        _selectedSessionId.value = sessionId
    }

    fun startNewSession(initialText: String = "Nueva Conversación") {
        viewModelScope.launch {
            val nTitle = if (initialText.length > 25) initialText.substring(0, 22) + "..." else initialText
            val newId = repository.createNewSession(nTitle)
            _selectedSessionId.value = newId
        }
    }

    fun deleteSession(sessionId: Int) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
            if (_selectedSessionId.value == sessionId) {
                // Find next available, or set to null
                val remaining = sessions.value.filter { it.id != sessionId }
                _selectedSessionId.value = if (remaining.isNotEmpty()) remaining.first().id else null
            }
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.clearAllHistory()
            _selectedSessionId.value = null
            _toastMessage.emit("Historial borrado por completo.")
        }
    }

    fun updateCurrentInput(text: String) {
        _currentInputText.value = text
    }

    fun setImageBase64(base64: String?) {
        _selectedImageBase64.value = base64
    }

    fun toggleTheme() {
        val newTheme = !_isDarkTheme.value
        _isDarkTheme.value = newTheme
        repository.setDarkTheme(newTheme)
    }

    fun saveApiKey(key: String) {
        repository.saveApiKey(key)
        _savedApiKey.value = key
        viewModelScope.launch {
            _toastMessage.emit("API guardada correctamente.")
        }
    }

    fun clearApiKey() {
        repository.clearApiKey()
        _savedApiKey.value = ""
        viewModelScope.launch {
            _toastMessage.emit("API eliminada.")
        }
    }

    fun showToast(msg: String) {
        viewModelScope.launch {
            _toastMessage.emit(msg)
        }
    }

    fun setSettingsDialogVisible(visible: Boolean) {
        _showSettingsDialog.value = visible
    }

    fun setSyncDialogVisible(visible: Boolean) {
        _showSyncDialog.value = visible
    }

    fun setImportDialogVisible(visible: Boolean) {
        _showImportDialog.value = visible
        if (!visible) {
            _importJsonDraft.value = ""
        }
    }

    fun updateImportJsonDraft(json: String) {
        _importJsonDraft.value = json
    }

    // Send logic
    fun sendMessage() {
        val text = _currentInputText.value.trim()
        val image = _selectedImageBase64.value

        if (text.isEmpty() && image == null) return

        _currentInputText.value = ""
        _selectedImageBase64.value = null
        _isLoading.value = true

        viewModelScope.launch {
            // Ensure there is an active session
            var sessionId = _selectedSessionId.value
            if (sessionId == null) {
                val title = if (text.isNotEmpty()) text else "Análisis de Imagen"
                val nTitle = if (title.length > 25) title.substring(0, 22) + "..." else title
                sessionId = repository.createNewSession(nTitle)
                _selectedSessionId.value = sessionId
            }

            // Call Repository to save user message, query Gemini, and save AI model response
            val result = repository.sendChatMessageAndGetReply(
                sessionId = sessionId!!,
                userText = text,
                imageBase64 = image,
                userApiKey = _savedApiKey.value.ifEmpty { null }
            )

            result.onFailure { exception ->
                _toastMessage.emit(exception.message ?: "Error al enviar mensaje")
                // Remove prompt if failed to communicate or let user copy it back
                _currentInputText.value = text
                _selectedImageBase64.value = image
            }

            _isLoading.value = false
        }
    }

    // Direct dictation send
    fun sendDirectVoiceTranscribedMessage(text: String) {
        if (text.trim().isEmpty()) return
        _isLoading.value = true
        _currentInputText.value = ""

        viewModelScope.launch {
            var sessionId = _selectedSessionId.value
            if (sessionId == null) {
                val nTitle = if (text.length > 25) text.substring(0, 22) + "..." else text
                sessionId = repository.createNewSession(nTitle)
                _selectedSessionId.value = sessionId
            }

            val result = repository.sendChatMessageAndGetReply(
                sessionId = sessionId!!,
                userText = text,
                imageBase64 = null,
                userApiKey = _savedApiKey.value.ifEmpty { null }
            )

            result.onFailure { exception ->
                _toastMessage.emit(exception.message ?: "Error al enviar mensaje")
                _currentInputText.value = text
            }
            _isLoading.value = false
        }
    }

    // Export & Import synchronization
    fun exportHistory(onCopy: (String) -> Unit) {
        viewModelScope.launch {
            val json = repository.getSyncJsonString()
            onCopy(json)
            _toastMessage.emit("Historial copiado al portapapeles como JSON formateado.")
        }
    }

    fun importHistory() {
        val json = _importJsonDraft.value.trim()
        if (json.isEmpty()) {
            viewModelScope.launch { _toastMessage.emit("Introduce un código de historial válido.") }
            return
        }

        viewModelScope.launch {
            val success = repository.importSyncJsonString(json)
            if (success) {
                _showImportDialog.value = false
                _importJsonDraft.value = ""
                // Reload list by nulling selection and reading fresh
                _selectedSessionId.value = null
                val sessionList = repository.allSessions.first()
                if (sessionList.isNotEmpty()) {
                    _selectedSessionId.value = sessionList.first().id
                }
                _toastMessage.emit("Sincronización finalizada. Historial importado correctamente.")
            } else {
                _toastMessage.emit("Formato de JSON inválido. Revisa tu historial copiado.")
            }
        }
    }

    fun setListeningDictation(active: Boolean) {
        _isListeningDictation.value = active
    }

    fun setListeningVoiceMessage(active: Boolean) {
        _isListeningVoiceMessage.value = active
    }
}
