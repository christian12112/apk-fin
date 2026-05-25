package com.example.util

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

class VoiceDictationManager(
    private val context: Context,
    private val onPartialText: (String) -> Unit,
    private val onFinalText: (String) -> Unit,
    private val onErrorMsg: (String) -> Unit,
    private val onListeningState: (Boolean) -> Unit
) : RecognitionListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private var recognitionIntent: Intent? = null
    private var isListening = false

    init {
        try {
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(this@VoiceDictationManager)
                }
                recognitionIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale("es", "ES")) // Spanish priority
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
            }
        } catch (e: Throwable) {
            Log.e("VoiceDictation", "Failed to create SpeechRecognizer: ${e.message}")
        }
    }

    fun startDictation() {
        if (speechRecognizer == null) {
            onErrorMsg("Dictado de voz no disponible o sin soporte en este dispositivo.")
            return
        }
        if (!isListening) {
            try {
                speechRecognizer?.startListening(recognitionIntent)
                isListening = true
                onListeningState(true)
            } catch (e: Exception) {
                onErrorMsg("Error al iniciar el micrófono: ${e.message}")
            }
        }
    }

    fun stopDictation() {
        if (isListening) {
            try {
                speechRecognizer?.stopListening()
                isListening = false
                onListeningState(false)
            } catch (e: Exception) {
                // Ignore silent stop failures
            }
        }
    }

    fun destroy() {
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            // Ignore
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    
    override fun onEndOfSpeech() {
        isListening = false
        onListeningState(false)
    }

    override fun onError(error: Int) {
        isListening = false
        onListeningState(false)
        val message = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Error de audio"
            SpeechRecognizer.ERROR_CLIENT -> "Error de conexión del cliente"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permisos de micrófono requeridos"
            SpeechRecognizer.ERROR_NETWORK -> "Error de red"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Tiempo de red agotado"
            SpeechRecognizer.ERROR_NO_MATCH -> "No se reconoció voz. Intenta de nuevo"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Micrófono ocupado"
            SpeechRecognizer.ERROR_SERVER -> "Error de respuesta del servidor"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Tiempo de habla agotado"
            else -> "Error de dictado de voz"
        }
        onErrorMsg(message)
    }

    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            onFinalText(matches[0])
        }
        isListening = false
        onListeningState(false)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            onPartialText(matches[0])
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}
}
