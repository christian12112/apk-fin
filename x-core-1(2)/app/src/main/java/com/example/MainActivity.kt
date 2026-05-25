package com.example

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import coil.compose.rememberAsyncImagePainter
import androidx.lifecycle.lifecycleScope
import com.example.data.db.ChatMessage
import com.example.data.db.ChatSession
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.ChatViewModel
import com.example.util.AudioSpeakerManager
import com.example.util.VoiceDictationManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var audioSpeakerManager: AudioSpeakerManager? = null
    private var voiceDictationManager: VoiceDictationManager? = null
    
    private val viewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize Audio Speakers
        audioSpeakerManager = AudioSpeakerManager(this)

        setContent {
            val isDarkThemeState by viewModel.isDarkTheme.collectAsState()
            
            MyApplicationTheme(darkTheme = isDarkThemeState) {
                // Main UI Content Wrapper
                ChatAppMainScreen(
                    viewModel = viewModel,
                    audioSpeakerManager = audioSpeakerManager,
                    onInitDictation = { onTextPartial, onTextFinal, onErrorState, onListening ->
                        voiceDictationManager = VoiceDictationManager(
                            this@MainActivity,
                            onTextPartial,
                            onTextFinal,
                            onErrorState,
                            onListening
                        )
                    },
                    onTriggerDictationStart = {
                        voiceDictationManager?.startDictation()
                    },
                    onTriggerDictationStop = {
                        voiceDictationManager?.stopDictation()
                    }
                )
            }
        }

        // Collect and display toast events
        lifecycleScope.launch {
            viewModel.toastMessage.collectLatest { msg ->
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        audioSpeakerManager?.destroy()
        voiceDictationManager?.destroy()
    }
}

// Convert chosen URI of image into base64 represented data string
private fun convertUriToBase64(contentResolver: ContentResolver, uri: Uri): String? {
    return try {
        val inputStream = contentResolver.openInputStream(uri)
        val bytes = inputStream?.readBytes()
        inputStream?.close()
        if (bytes != null) {
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } else null
    } catch (e: Exception) {
        null
    }
}




@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatAppMainScreen(
    viewModel: ChatViewModel,
    audioSpeakerManager: AudioSpeakerManager?,
    onInitDictation: ((String) -> Unit, (String) -> Unit, (String) -> Unit, (Boolean) -> Unit) -> Unit,
    onTriggerDictationStart: () -> Unit,
    onTriggerDictationStop: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val clipboardManager = LocalClipboardManager.current

    // Observe State flows from Viewmodel
    val sessions by viewModel.sessions.collectAsState()
    val selectedSessionId by viewModel.selectedSessionId.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val currentInputText by viewModel.currentInputText.collectAsState()
    val selectedImageBase64 by viewModel.selectedImageBase64.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isDarkTheme by viewModel.isDarkTheme.collectAsState()
    val savedApiKey by viewModel.savedApiKey.collectAsState()

    // Dialog state visibility observers
    val showSettingsDialog by viewModel.showSettingsDialog.collectAsState()
    val showSyncDialog by viewModel.showSyncDialog.collectAsState()
    val showImportDialog by viewModel.showImportDialog.collectAsState()
    val importJsonDraft by viewModel.importJsonDraft.collectAsState()

    // Speech states observers
    val isListeningDictation by viewModel.isListeningDictation.collectAsState()
    val isListeningVoiceMessage by viewModel.isListeningVoiceMessage.collectAsState()
    val partialSTTText by viewModel.speechDictationPartial.collectAsState()

    // Setup speech engines on initialization
    LaunchedEffect(Unit) {
        onInitDictation(
            { partial ->
                // Partial Text Handler
                viewModel.updateCurrentInput(currentInputText + " " + partial)
            },
            { final ->
                // Final Text dictates and appends
                viewModel.updateCurrentInput(currentInputText + " " + final)
                viewModel.setListeningDictation(false)
            },
            { error ->
                viewModel.showToast(error)
                viewModel.setListeningDictation(false)
            },
            { active ->
                viewModel.setListeningDictation(active)
            }
        )
    }

    // Permission Launchers
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.showToast("Micrófono activado. Habla ahora...")
            viewModel.setListeningDictation(true)
            onTriggerDictationStart()
        } else {
            viewModel.showToast("Permiso de micrófono requerido para voz.")
            viewModel.setListeningDictation(false)
        }
    }

    fun requestMicrophonePermissionAndStart() {
        val permissionState = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        )
        if (permissionState == PackageManager.PERMISSION_GRANTED) {
            if (isListeningDictation) {
                onTriggerDictationStop()
                viewModel.setListeningDictation(false)
            } else {
                viewModel.setListeningDictation(true)
                onTriggerDictationStart()
            }
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Modern Image Pick Visual Media setup
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val base64 = convertUriToBase64(context.contentResolver, uri)
            viewModel.setImageBase64(base64)
            viewModel.showToast("Imagen cargada correctamente.")
        }
    }

    // Theme values helper
    val appBgBrush = if (isDarkTheme) {
        Brush.verticalGradient(listOf(Color(0xFF0F172A), Color(0xFF020617)))
    } else {
        Brush.verticalGradient(listOf(Color(0xFFF8FAFC), Color(0xFFF1F5F9)))
    }

    val bubbleModelBg = if (isDarkTheme) Color(0xFF1E293B) else Color(0xFFE2E8F0)
    val bubbleUserBg = if (isDarkTheme) Color(0xFF3B82F6) else Color(0xFF2563EB)
    
    val textPrimaryColor = if (isDarkTheme) Color.White else Color(0xFF0F172A)
    val textSecondaryColor = if (isDarkTheme) Color(0xFF94A3B8) else Color(0xFF64748B)

    // Side Drawer holding thread histories and options
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(310.dp),
                drawerTonalElevation = 8.dp,
                drawerContainerColor = if (isDarkTheme) Color(0xFF0B0F19) else Color(0xFFFFFFFF)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    // Drawer Header
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 20.dp, top = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF2563EB)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = "Logo",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "x-core 1",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = textPrimaryColor
                            )
                            Text(
                                text = "por Christian Galíndez",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFEAB308) // golden playboy style!
                            )
                        }
                    }

                    // Options Buttons
                    Button(
                        onClick = {
                            viewModel.startNewSession()
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .testTag("new_chat_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Rounded.Add, contentDescription = "Nuevo Chat")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Nuevo Chat x-core 1", fontWeight = FontWeight.SemiBold)
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = textSecondaryColor.copy(alpha = 0.2f))

                    Text(
                        text = "Historial y Sesiones",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = textSecondaryColor,
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                    )

                    // Lazy List of Previous Conversations
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (sessions.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Crea un chat para comenzar",
                                        color = textSecondaryColor,
                                        fontSize = 13.sp,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        } else {
                            items(sessions, key = { it.id }) { sNode ->
                                val isSelected = selectedSessionId == sNode.id
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(
                                                alpha = 0.6f
                                            ) else Color.Transparent
                                        )
                                        .clickable {
                                            viewModel.selectSession(sNode.id)
                                            scope.launch { drawerState.close() }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ChatBubbleOutline,
                                        contentDescription = null,
                                        tint = if (isSelected) MaterialTheme.colorScheme.primary else textSecondaryColor,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = sNode.title,
                                        fontSize = 14.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = textPrimaryColor,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { viewModel.deleteSession(sNode.id) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteOutline,
                                            contentDescription = "Borrar",
                                            tint = Color.Red.copy(alpha = 0.7f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = textSecondaryColor.copy(alpha = 0.2f))

                    // Bottom Drawer Actions (Settings and Sync)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                viewModel.setSettingsDialogVisible(true)
                                scope.launch { drawerState.close() }
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(imageVector = Icons.Rounded.VpnKey, contentDescription = null, tint = textPrimaryColor)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Insertar API", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = textPrimaryColor)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                viewModel.setSyncDialogVisible(true)
                                scope.launch { drawerState.close() }
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(imageVector = Icons.Rounded.Sync, contentDescription = null, tint = textPrimaryColor)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Sincronizar Dispositivos", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = textPrimaryColor)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                viewModel.clearAllHistory()
                                scope.launch { drawerState.close() }
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(imageVector = Icons.Rounded.LayersClear, contentDescription = null, tint = Color.Red.copy(alpha = 0.8f))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Limpiar Todo", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.Red.copy(alpha = 0.8f))
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "x-core 1",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.SansSerif
                            )
                            if (savedApiKey.isNotEmpty()) {
                                Text(
                                    text = "API de Usuario Activa",
                                    fontSize = 10.sp,
                                    color = Color(0xFF10B981),
                                    fontWeight = FontWeight.Medium
                                )
                            } else {
                                Text(
                                    text = "Usando API predeterminada",
                                    fontSize = 10.sp,
                                    color = textSecondaryColor,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(imageVector = Icons.Rounded.Menu, contentDescription = "Menú")
                        }
                    },
                    actions = {
                        // Light / Dark Theme selector
                        IconButton(
                            onClick = { viewModel.toggleTheme() },
                            modifier = Modifier.testTag("theme_switch_button")
                        ) {
                            Icon(
                                imageVector = if (isDarkTheme) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                                contentDescription = "Cambiar Tema",
                                tint = if (isDarkTheme) Color(0xFFF59E0B) else Color(0xFF475569)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = if (isDarkTheme) Color(0xFF0F172A) else Color(0xFFFFFFFF),
                        titleContentColor = textPrimaryColor,
                        navigationIconContentColor = textPrimaryColor,
                        actionIconContentColor = textPrimaryColor
                    )
                )
            },
            modifier = Modifier.fillMaxSize()
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(appBgBrush)
            ) {
                // Background visual detail: Aura glow in center when empty
                if (messages.isEmpty() && !isLoading) {
                    EmptyChatIntro(
                        isDarkTheme = isDarkTheme,
                        textPrimaryColor = textPrimaryColor,
                        textSecondaryColor = textSecondaryColor,
                        savedApiKey = savedApiKey,
                        onOpenSettings = { viewModel.setSettingsDialogVisible(true) }
                    )
                } else {
                    // Chat Bubble List area
                    val listState = rememberLazyListState()
                    LaunchedEffect(messages.size, isLoading) {
                        if (messages.isNotEmpty()) {
                            listState.animateScrollToItem(messages.size - 1)
                        }
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 90.dp), // space for textbar
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(messages, key = { it.id }) { msg ->
                            BubbleMessageView(
                                message = msg,
                                isDark = isDarkTheme,
                                bubbleModelBg = bubbleModelBg,
                                bubbleUserBg = bubbleUserBg,
                                textPrimaryColor = textPrimaryColor,
                                textSecondary = textSecondaryColor,
                                audioSpeakerManager = audioSpeakerManager
                            )
                        }

                        if (isLoading) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF2563EB).copy(alpha = 0.2f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.AutoAwesome,
                                                contentDescription = null,
                                                tint = Color(0xFF2563EB),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        AssistantLoadingGlow()
                                    }
                                }
                            }
                        }
                    }
                }

                // Composition active sending overlays
                if (isListeningDictation) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 100.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFEF4444).copy(alpha = 0.95f))
                            .border(1.dp, Color.White, RoundedCornerShape(12.dp))
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PulseDot(color = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Dictando... Tu voz se escribirá en tiempo real.",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Composition Bottom Controller Bar
                CompositionFrame(
                    input = currentInputText,
                    onUpdateInput = { viewModel.updateCurrentInput(it) },
                    imageUrlBase64 = selectedImageBase64,
                    onTriggerImagePick = { imagePickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onRemoveImage = { viewModel.setImageBase64(null) },
                    onStartDictationToggle = { requestMicrophonePermissionAndStart() },
                    onSendMessage = { viewModel.sendMessage() },
                    isDark = isDarkTheme,
                    isLoading = isLoading,
                    textPrimaryColor = textPrimaryColor,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }

    // Settings Credentials Modal Dialog
    if (showSettingsDialog) {
        var keyInput by remember { mutableStateOf(savedApiKey) }
        var showPlainKey by remember { mutableStateOf(false) }

        Dialog(onDismissRequest = { viewModel.setSettingsDialogVisible(false) }) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isDarkTheme) Color(0xFF1E293B) else Color.White
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Rounded.VpnKey,
                        contentDescription = null,
                        tint = Color(0xFF2563EB),
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Insertar API",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = textPrimaryColor,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "x-core 1 guarda tu API localmente de forma segura. Si se deja vacía correrá con la de demostración.",
                        fontSize = 12.sp,
                        color = textSecondaryColor,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))

                    TextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        placeholder = { Text("Inserta tu API aquí...") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        visualTransformation = if (showPlainKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPlainKey = !showPlainKey }) {
                                Icon(
                                    imageVector = if (showPlainKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = "Ocultar"
                                )
                            }
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedTextColor = textPrimaryColor,
                            unfocusedTextColor = textPrimaryColor
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, textSecondaryColor.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                viewModel.clearApiKey()
                                keyInput = ""
                                viewModel.setSettingsDialogVisible(false)
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            border = ButtonDefaults.outlinedButtonBorder.copy()
                        ) {
                            Text("Limpiar", color = Color.Red.copy(alpha = 0.8f))
                        }

                        Button(
                            onClick = {
                                if (keyInput.trim().isNotEmpty()) {
                                    viewModel.saveApiKey(keyInput.trim())
                                }
                                viewModel.setSettingsDialogVisible(false)
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Guardar API")
                        }
                    }
                }
            }
        }
    }

    // Sync backup code sharing dialog
    if (showSyncDialog) {
        Dialog(onDismissRequest = { viewModel.setSyncDialogVisible(false) }) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isDarkTheme) Color(0xFF1E293B) else Color.White
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Sync,
                        contentDescription = null,
                        tint = Color(0xFF2563EB),
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Sincronización de Dispositivos",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = textPrimaryColor,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Sincroniza todas tus conversaciones generando una clave/código de respaldo. Cópiala en tu otro dispositivo para restaurarla inmediatamente.",
                        fontSize = 12.sp,
                        color = textSecondaryColor,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = {
                            viewModel.exportHistory { json ->
                                clipboardManager.setText(AnnotatedString(json))
                            }
                            viewModel.setSyncDialogVisible(false)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Rounded.ContentCopy, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Copiar Código de Sincronización", fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = {
                            viewModel.setSyncDialogVisible(false)
                            viewModel.setImportDialogVisible(true)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Rounded.FileUpload, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Importar Respaldo de Historial", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // Import payload setup input
    if (showImportDialog) {
        Dialog(onDismissRequest = { viewModel.setImportDialogVisible(false) }) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isDarkTheme) Color(0xFF1E293B) else Color.White
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Importar Copia de Historial",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = textPrimaryColor
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    TextField(
                        value = importJsonDraft,
                        onValueChange = { viewModel.updateImportJsonDraft(it) },
                        placeholder = { Text("Pega el código JSON de sincronización aquí...", fontSize = 12.sp) },
                        maxLines = 6,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = textSecondaryColor.copy(alpha = 0.1f),
                            unfocusedContainerColor = textSecondaryColor.copy(alpha = 0.05f),
                            focusedTextColor = textPrimaryColor,
                            unfocusedTextColor = textPrimaryColor
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .border(1.dp, textSecondaryColor.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.setImportDialogVisible(false) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Cancelar")
                        }

                        Button(
                            onClick = { viewModel.importHistory() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Importar")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyChatIntro(
    isDarkTheme: Boolean,
    textPrimaryColor: Color,
    textSecondaryColor: Color,
    savedApiKey: String,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Holographic style aura shape
        Box(
            modifier = Modifier
                .size(90.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0xFF3B82F6), Color(0xFF8B5CF6).copy(alpha = 0.1f))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .size(44.dp)
                    .scale(1.1f)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "Hola, soy x-core 1",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = textPrimaryColor,
            fontFamily = FontFamily.SansSerif
        )
        Text(
            text = "Tu asistente inteligente en tiempo real",
            fontSize = 14.sp,
            color = textSecondaryColor,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Styled Tip card
        Box(
            modifier = Modifier
                .widthIn(max = 310.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(textSecondaryColor.copy(alpha = if (isDarkTheme) 0.1f else 0.05f))
                .border(
                    1.dp,
                    textSecondaryColor.copy(alpha = if (isDarkTheme) 0.15f else 0.08f),
                    RoundedCornerShape(16.dp)
                )
                .padding(16.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "x-core 1 posee memoria completa de la conversación, dictado de voz interactivo y lectura de imágenes.",
                    fontSize = 12.sp,
                    color = textPrimaryColor.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )
                if (savedApiKey.isEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(onClick = onOpenSettings) {
                        Text(
                            "Insertar API propia",
                            fontSize = 12.sp,
                            color = Color(0xFF3B82F6),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BubbleMessageView(
    message: ChatMessage,
    isDark: Boolean,
    bubbleModelBg: Color,
    bubbleUserBg: Color,
    textPrimaryColor: Color,
    textSecondary: Color,
    audioSpeakerManager: AudioSpeakerManager?
) {
    val isUser = message.role == "user"
    val alignment = if (isUser) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = alignment
    ) {
        // Optional Avatar / Name
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 4.dp, start = if (isUser) 0.dp else 4.dp, end = if (isUser) 4.dp else 0.dp)
        ) {
            if (!isUser) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF3263EB)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(10.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "x-core 1",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = textSecondary
                )
            } else {
                Text(
                    text = "Tú",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = textSecondary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Rounded.AccountCircle,
                    contentDescription = null,
                    tint = textSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Main Bubble Content
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .clip(
                        RoundedCornerShape(
                            topStart = 18.dp,
                            topEnd = 18.dp,
                            bottomStart = if (isUser) 18.dp else 4.dp,
                            bottomEnd = if (isUser) 4.dp else 18.dp
                        )
                    )
                    .background(if (isUser) bubbleUserBg else bubbleModelBg)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Column {
                    // Image attachment rendering if present safely
                    if (message.imageBase64 != null) {
                        val imageBytes = remember(message.imageBase64) {
                            try {
                                Base64.decode(message.imageBase64, Base64.DEFAULT)
                            } catch (e: Exception) {
                                null
                            }
                        }
                        if (imageBytes != null) {
                            val painter = rememberAsyncImagePainter(model = imageBytes)
                            Image(
                                painter = painter,
                                contentDescription = "Imagen de usuario",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(130.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .padding(bottom = 8.dp)
                            )
                        }
                    }

                    Text(
                        text = message.text,
                        color = if (isUser) Color.White else textPrimaryColor,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Normal
                    )
                    
                    if (!isUser) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            IconButton(
                                onClick = { audioSpeakerManager?.speak(message.text) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.VolumeUp,
                                    contentDescription = "Reproducir voz",
                                    tint = textSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RecordPulseIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(
        modifier = Modifier
            .size(70.dp)
            .clip(CircleShape)
            .background(Color(0xFFEF4444).copy(alpha = alpha)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Mic,
            contentDescription = "Grabando",
            tint = Color.White,
            modifier = Modifier.size(36.dp)
        )
    }
}

@Composable
fun PulseDot(color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "dot")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha))
    )
}

@Composable
fun AssistantLoadingGlow() {
    val infiniteTransition = rememberInfiniteTransition(label = "loading")
    val widthPercent by infiniteTransition.animateFloat(
        initialValue = 40f,
        targetValue = 130f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "width"
    )
    Box(
        modifier = Modifier
            .width(widthPercent.dp)
            .height(18.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(0xFF2563EB).copy(alpha = 0.15f),
                        Color(0xFF8B5CF6).copy(alpha = 0.15f)
                    )
                )
            )
    )
}

@Composable
fun CompositionFrame(
    input: String,
    onUpdateInput: (String) -> Unit,
    imageUrlBase64: String?,
    onTriggerImagePick: () -> Unit,
    onRemoveImage: () -> Unit,
    onStartDictationToggle: () -> Unit,
    onSendMessage: () -> Unit,
    isDark: Boolean,
    isLoading: Boolean,
    textPrimaryColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        tonalElevation = 6.dp,
        shadowElevation = 4.dp,
        color = if (isDark) Color(0xFF0F172A).copy(alpha = 0.96f) else Color(0xFFFFFFFF).copy(alpha = 0.96f),
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            // Picked Image preview block above compose field safely
            if (imageUrlBase64 != null) {
                val previewBytes = remember(imageUrlBase64) {
                    try {
                        Base64.decode(imageUrlBase64, Base64.DEFAULT)
                    } catch (e: Exception) {
                        null
                    }
                }
                if (previewBytes != null) {
                    Box(
                        modifier = Modifier
                            .padding(bottom = 8.dp)
                            .size(68.dp)
                            .clip(RoundedCornerShape(12.dp))
                    ) {
                        Image(
                            painter = rememberAsyncImagePainter(model = previewBytes),
                            contentDescription = "Previsualización",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(2.dp)
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.7f))
                                .clickable { onRemoveImage() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Remover",
                                tint = Color.White,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            }

            // Standard Composition Bar field and controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Add Multimodal Image picker button
                IconButton(
                    onClick = onTriggerImagePick,
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(0xFF2563EB).copy(alpha = 0.1f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Image,
                        contentDescription = "Cargar Imagen",
                        tint = Color(0xFF2563EB),
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Voice Dictation Microphone Button (STT Dictate to Box)
                IconButton(
                    onClick = onStartDictationToggle,
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(0xFF8B5CF6).copy(alpha = 0.1f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Mic,
                        contentDescription = "Dictado por Voz",
                        tint = Color(0xFF8B5CF6),
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Custom Input fields
                TextField(
                    value = input,
                    onValueChange = onUpdateInput,
                    placeholder = { Text("Pregúntame algo...", fontSize = 14.sp) },
                    singleLine = false,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSendMessage() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = textPrimaryColor,
                        unfocusedTextColor = textPrimaryColor
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .border(
                            1.dp,
                            textPrimaryColor.copy(alpha = 0.12f),
                            RoundedCornerShape(24.dp)
                        )
                        .heightIn(min = 40.dp, max = 110.dp)
                        .testTag("chat_input_text")
                )

                // Send Button
                IconButton(
                    onClick = onSendMessage,
                    enabled = !isLoading && (input.trim().isNotEmpty() || imageUrlBase64 != null),
                    modifier = Modifier
                        .size(42.dp)
                        .background(
                            if (input.trim().isEmpty() && imageUrlBase64 == null) Color.Gray.copy(alpha = 0.3f)
                            else Color(0xFF2563EB),
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Send,
                        contentDescription = "Enviar",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
