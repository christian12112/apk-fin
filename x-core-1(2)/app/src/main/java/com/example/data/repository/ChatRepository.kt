package com.example.data.repository

import android.content.Context
import android.util.Base64
import com.example.BuildConfig
import com.example.data.api.*
import com.example.data.db.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class ChatRepository(private val context: Context, private val chatDao: ChatDao) {

    private val sharedPrefs = context.getSharedPreferences("aura_chat_prefs", Context.MODE_PRIVATE)
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    // Key management
    fun getSavedApiKey(): String? {
        return sharedPrefs.getString("api_key", null)
    }

    fun saveApiKey(key: String) {
        sharedPrefs.edit().putString("api_key", key).apply()
    }

    fun clearApiKey() {
        sharedPrefs.edit().remove("api_key").apply()
    }

    // Theme Management
    fun isDarkTheme(): Boolean {
        // Default to true (modern Dark mode first per guidelines!)
        return sharedPrefs.getBoolean("dark_theme", true)
    }

    fun setDarkTheme(isDark: Boolean) {
        sharedPrefs.edit().putBoolean("dark_theme", isDark).apply()
    }

    // Sessions and messages room access
    val allSessions: Flow<List<ChatSession>> = chatDao.getAllSessionsFlow()

    fun getMessagesForSession(sessionId: Int): Flow<List<ChatMessage>> {
        return chatDao.getMessagesForSessionFlow(sessionId)
    }

    suspend fun createNewSession(title: String): Int = withContext(Dispatchers.IO) {
        val session = ChatSession(title = title)
        chatDao.insertSession(session).toInt()
    }

    suspend fun deleteSession(sessionId: Int) = withContext(Dispatchers.IO) {
        chatDao.deleteSession(sessionId)
    }

    suspend fun clearAllHistory() = withContext(Dispatchers.IO) {
        chatDao.deleteAllSessions()
    }

    suspend fun addMessage(message: ChatMessage) = withContext(Dispatchers.IO) {
        chatDao.insertMessage(message)
    }

    // Calling Gemini with memory/history!
    suspend fun sendChatMessageAndGetReply(
        sessionId: Int,
        userText: String,
        imageBase64: String? = null,
        userApiKey: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val keyToUse = userApiKey ?: getSavedApiKey() ?: BuildConfig.GEMINI_API_KEY
            if (keyToUse.isEmpty() || keyToUse == "MY_GEMINI_API_KEY") {
                return@withContext Result.failure(Exception("Inserta una API válida en la configuración para poder comenzar a chatear."))
            }

            // 1. Save user message to database
            val userMsg = ChatMessage(
                sessionId = sessionId,
                role = "user",
                text = userText,
                imageBase64 = imageBase64
            )
            chatDao.insertMessage(userMsg)

            // 2. Fetch history from DB for context (excluding the just inserted one to append or include it)
            // Wait, we need to send the whole alternating history to the model
            val dbMessages = chatDao.getMessagesForSession(sessionId)

            // Convert to Gemini API Contents
            val contentsList = dbMessages.map { dbMsg ->
                val parts = mutableListOf<Part>()
                parts.add(Part(text = dbMsg.text))
                if (dbMsg.role == "user" && dbMsg.imageBase64 != null) {
                    parts.add(Part(inlineData = InlineData(mimeType = "image/jpeg", data = dbMsg.imageBase64)))
                }
                Content(
                    role = if (dbMsg.role == "user") "user" else "model",
                    parts = parts
                )
            }

            // System instructions per user's requests
            val systemInstruction = Content(
                parts = listOf(
                    Part(
                        text = "Te llamas x-core 1. Si te preguntan cómo te llamas, quién te creó, quién es tu creador o quién hizo esta aplicación, debes responder SIEMPRE, de manera simpática, divertida y cómplice diciéndoles que te llamas x-core 1 y que tu creador es Christian Galíndez, un genio playboy filántropo. Mantén una conversación fluida, comprensiva, extremadamente moderna y cercana en español, adaptada al tono de un asistente inteligente de última generación."
                    )
                )
            )

            val request = GenerateContentRequest(
                contents = contentsList,
                systemInstruction = systemInstruction
            )

            // Call API
            val response = RetrofitClient.service.generateContent(apiKey = keyToUse, request = request)
            
            val error = response.error
            if (error != null) {
                return@withContext Result.failure(Exception("Error API (${error.code}): ${error.message}"))
            }

            val replyText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                ?: "No he recibido contenido en la respuesta."

            // 3. Save assistant response to DB
            val helperMsg = ChatMessage(
                sessionId = sessionId,
                role = "model",
                text = replyText
            )
            chatDao.insertMessage(helperMsg)

            Result.success(replyText)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Sincronización local / copia multi-dispositivo por JSON String
    data class SyncData(
        val sessions: List<ChatSession>,
        val messages: List<ChatMessage>
    )

    suspend fun getSyncJsonString(): String = withContext(Dispatchers.IO) {
        val sessions = chatDao.getAllSessions()
        // Wait, sessions list could be mapped. Let's fetch messages for all sessions
        val messages = mutableListOf<ChatMessage>()
        sessions.forEach { sess ->
            messages.addAll(chatDao.getMessagesForSession(sess.id))
        }

        val adapter = moshi.adapter(SyncData::class.java)
        adapter.toJson(SyncData(sessions, messages))
    }

    suspend fun importSyncJsonString(json: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val adapter = moshi.adapter(SyncData::class.java)
            val syncData = adapter.fromJson(json) ?: return@withContext false

            // Clear old local db and insert all
            chatDao.deleteAllMessages()
            chatDao.deleteAllSessions()

            chatDao.insertSessions(syncData.sessions)
            chatDao.insertMessages(syncData.messages)
            true
        } catch (e: Exception) {
            false
        }
    }
}
