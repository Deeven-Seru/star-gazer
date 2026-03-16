package com.stargazed.assistant

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.View

data class ChatMessage(val text: String, val isUser: Boolean)

class MainActivity : AppCompatActivity() {

    companion object {
        const val CHANNEL_ID = "star_gazed_channel"
        val messages = mutableListOf<ChatMessage>()
        var messageListener: (() -> Unit)? = null

        fun addMessage(msg: ChatMessage) {
            messages.add(msg)
            messageListener?.invoke()
        }
    }

    private lateinit var messagesLayout: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var inputBox: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D0D0D"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 24)
            setBackgroundColor(Color.parseColor("#1A1A2E"))
        }
        val title = TextView(this).apply {
            text = "★ Star Gazed"
            textSize = 24f
            setTextColor(Color.parseColor("#A78BFA"))
            typeface = Typeface.DEFAULT_BOLD
        }
        val subtitle = TextView(this).apply {
            text = "AI Assistant"
            textSize = 12f
            setTextColor(Color.parseColor("#6B7280"))
        }
        header.addView(title)
        header.addView(subtitle)

        // Status bar
        val statusBar = TextView(this).apply {
            id = R.id.status_text
            text = "⬤  Connecting..."
            textSize = 11f
            setTextColor(Color.parseColor("#F59E0B"))
            setPadding(48, 12, 48, 12)
            setBackgroundColor(Color.parseColor("#111111"))
        }

        // Chat area
        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        messagesLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        scrollView.addView(messagesLayout)

        // Welcome message
        addBubble(ChatMessage("Hi! I'm Star Gazed. Type a command or say 'Star Gaze' to activate me.", false))

        // Bottom input area
        val inputArea = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 16, 24, 32)
            setBackgroundColor(Color.parseColor("#111827"))
            gravity = Gravity.CENTER_VERTICAL
        }

        inputBox = EditText(this).apply {
            hint = "Type a command..."
            setHintTextColor(Color.parseColor("#6B7280"))
            setTextColor(Color.WHITE)
            textSize = 15f
            background = createRoundedBackground("#1F2937", 48f)
            setPadding(32, 24, 32, 24)
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 3
        }

        val sendButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_send)
            background = createRoundedBackground("#7C3AED", 48f)
            setPadding(24, 24, 24, 24)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(16, 0, 0, 0) }
            setColorFilter(Color.WHITE)
        }

        val sendAction = {
            val text = inputBox.text.toString().trim()
            if (text.isNotEmpty()) {
                addBubble(ChatMessage(text, true))
                StarGazedAccessibilityService.instance?.sendIntentToBackend(text)
                inputBox.text.clear()
            }
        }

        sendButton.setOnClickListener { sendAction() }
        inputBox.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendAction(); true } else false
        }

        inputArea.addView(inputBox)
        inputArea.addView(sendButton)

        // Permissions banner
        val permBanner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
            setBackgroundColor(Color.parseColor("#1C1917"))
        }

        val accessibilityBtn = Button(this).apply {
            text = "Enable Accessibility Service"
            setBackgroundColor(Color.parseColor("#7C3AED"))
            setTextColor(Color.WHITE)
            setPadding(24, 16, 24, 16)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 12) }
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val micBtn = Button(this).apply {
            text = "Grant Microphone Permission"
            setBackgroundColor(Color.parseColor("#1D4ED8"))
            setTextColor(Color.WHITE)
            setPadding(24, 16, 24, 16)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                ActivityCompat.requestPermissions(
                    this@MainActivity,
                    arrayOf(Manifest.permission.RECORD_AUDIO), 101
                )
                // Also start the foreground service
                val svcIntent = Intent(this@MainActivity, StarGazedForegroundService::class.java)
                ContextCompat.startForegroundService(this@MainActivity, svcIntent)
            }
        }
        permBanner.addView(accessibilityBtn)
        permBanner.addView(micBtn)

        rootLayout.addView(header)
        rootLayout.addView(statusBar)
        rootLayout.addView(scrollView)
        rootLayout.addView(permBanner)
        rootLayout.addView(inputArea)
        setContentView(rootLayout)

        // Listen for incoming messages from accessibility service
        messageListener = {
            runOnUiThread {
                refreshMessages()
                scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun refreshMessages() {
        messagesLayout.removeAllViews()
        for (msg in messages) {
            addBubble(msg)
        }
    }

    private fun addBubble(msg: ChatMessage) {
        val bubble = TextView(this).apply {
            text = msg.text
            textSize = 14f
            setTextColor(if (msg.isUser) Color.WHITE else Color.parseColor("#E2E8F0"))
            background = createRoundedBackground(
                if (msg.isUser) "#7C3AED" else "#1E293B", 32f
            )
            setPadding(32, 20, 32, 20)
        }
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (msg.isUser) Gravity.END else Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 8) }
        }

        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            weight = 0f
            if (msg.isUser) {
                marginStart = 80
            } else {
                marginEnd = 80
            }
        }
        bubble.layoutParams = params
        wrapper.addView(bubble)
        messagesLayout.addView(wrapper)
    }

    private fun createRoundedBackground(colorHex: String, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(colorHex))
            cornerRadius = radius
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Star Gazed", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Star Gazed AI Assistant" }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        messageListener = null
    }
}
