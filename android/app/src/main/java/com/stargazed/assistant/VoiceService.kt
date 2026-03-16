package com.stargazed.assistant

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.*

class VoiceService : Service(), RecognitionListener, TextToSpeech.OnInitListener {

    private val TAG = "VoiceService"
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isListening = false

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(this)
        
        Log.d(TAG, "Voice Service Started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "com.stargazed.assistant.ACTION_SPEAK") {
            val text = intent.getStringExtra("speak_text")
            if (!text.isNullOrEmpty()) speak(text)
        } else if (!isListening) {
            startListening()
        }
        return START_STICKY
    }

    private fun startListening() {
        Log.d(TAG, "Starting to listen...")
        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        speechRecognizer?.startListening(recognizerIntent)
        isListening = true
    }

    // --- RecognitionListener Methods ---
    override fun onReadyForSpeech(params: Bundle?) { Log.d(TAG, "Ready for speech") }
    override fun onBeginningOfSpeech() { Log.d(TAG, "Speech beginning") }
    override fun onRmsChanged(rmsdB: Float) { }
    override fun onBufferReceived(buffer: ByteArray?) { }
    override fun onEndOfSpeech() { 
        Log.d(TAG, "Speech ended") 
        isListening = false
    }
    
    override fun onError(error: Int) {
        Log.e(TAG, "Speech Error: $error")
        isListening = false
        // Restart listening after a short delay in a real persistent app
        Thread.sleep(1000)
        startListening()
    }

    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            val spokenText = matches[0].lowercase(Locale.getDefault())
            Log.d(TAG, "Heard final: $spokenText")

            // Check if the user said the wake word
            if (spokenText.contains("star gaze") || spokenText.contains("assistant")) {
                Log.i(TAG, "WAKE COMMAND DETECTED: $spokenText")
                // Tell the Accessibility Service to scrape the screen and send to backend
                val intent = Intent("com.stargazed.assistant.ACTION_WAKE_WORD")
                intent.putExtra("intent_text", spokenText)
                sendBroadcast(intent)
            }
        }
        // Auto-restart listening
        startListening()
    }

    override fun onPartialResults(partialResults: Bundle?) { }
    override fun onEvent(eventType: Int, params: Bundle?) { }

    // --- TTS Methods ---
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
        }
    }

    fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "stargazed_utterance")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        tts?.shutdown()
    }
}
