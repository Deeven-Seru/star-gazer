package com.stargazed.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class VoiceCommandReceiver(private val mService: StarGazedAccessibilityService) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == "com.stargazed.assistant.ACTION_WAKE_WORD") {
            val spokenIntent = intent.getStringExtra("intent_text") ?: return
            Log.d("VoiceCommandReceiver", "Proxying intent to backend: $spokenIntent")
            mService.sendIntentToBackend(spokenIntent)
        }
    }
}
