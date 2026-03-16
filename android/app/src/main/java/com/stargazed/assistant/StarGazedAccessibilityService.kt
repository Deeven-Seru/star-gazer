package com.stargazed.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class StarGazedAccessibilityService : AccessibilityService() {

    companion object {
        var instance: StarGazedAccessibilityService? = null
        private const val TAG = "StarGazedService"
        private const val WS_URL = "wss://star-gazed-backend-774960018863.us-central1.run.app"
    }

    private var webSocket: WebSocket? = null
    private var voiceReceiver: VoiceCommandReceiver? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility Service Connected")
        connectWebSocket()

        voiceReceiver = VoiceCommandReceiver(this)
        val filter = android.content.IntentFilter("com.stargazed.assistant.ACTION_WAKE_WORD")
        registerReceiver(voiceReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    private fun connectWebSocket() {
        val request = Request.Builder().url(WS_URL).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket Connected")
                MainActivity.addMessage(ChatMessage("⬤ Connected to Star Gazed AI", false))
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.d(TAG, "Received: $text")
                handleBackendCommand(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket Error: ${t.message}")
                MainActivity.addMessage(ChatMessage("⚠ Connection lost. Reconnecting...", false))
                // Reconnect after 5s
                android.os.Handler(mainLooper).postDelayed({ connectWebSocket() }, 5000)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket Closed")
            }
        })
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.d(TAG, "Service Interrupted")
    }

    fun sendIntentToBackend(intentText: String) {
        val rootNode = rootInActiveWindow
        val nodesArray = JSONArray()
        if (rootNode != null) {
            traverseNodeTree(rootNode, nodesArray)
        }

        val payload = JSONObject().apply {
            put("type", "request")
            put("intent", intentText)
            put("screenNodes", nodesArray)
        }

        Log.d(TAG, "Sending intent to backend: $intentText (${nodesArray.length()} nodes)")
        webSocket?.send(payload.toString())
    }

    private fun traverseNodeTree(node: AccessibilityNodeInfo, array: JSONArray) {
        if (!node.isVisibleToUser) return
        val rect = Rect()
        node.getBoundsInScreen(rect)

        val nodeObj = JSONObject().apply {
            put("class", node.className?.toString() ?: "")
            put("text", node.text?.toString() ?: "")
            put("contentDescription", node.contentDescription?.toString() ?: "")
            put("clickable", node.isClickable)
            put("editable", node.isEditable)
            put("bounds", JSONObject().apply {
                put("left", rect.left); put("top", rect.top)
                put("right", rect.right); put("bottom", rect.bottom)
                put("centerX", rect.centerX()); put("centerY", rect.centerY())
            })
        }

        val text = nodeObj.getString("text")
        val desc = nodeObj.getString("contentDescription")
        if (node.isClickable || node.isEditable || text.isNotEmpty() || desc.isNotEmpty()) {
            array.put(nodeObj)
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { traverseNodeTree(it, array) }
        }
    }

    private fun handleBackendCommand(jsonText: String) {
        try {
            val data = JSONObject(jsonText)
            if (data.getString("type") != "action") return

            val action = data.getString("action")
            val args = if (data.has("args")) data.getJSONObject("args") else JSONObject()

            Log.d(TAG, "Executing action: $action")

            when (action) {
                "tap_node" -> {
                    val x = args.getDouble("x").toFloat()
                    val y = args.getDouble("y").toFloat()
                    performTap(x, y)
                    MainActivity.addMessage(ChatMessage("⬤ Tapping at (${x.toInt()}, ${y.toInt()})", false))
                }
                "speak" -> {
                    val text = args.getString("text")
                    Log.d(TAG, "SPEAKING: $text")
                    MainActivity.addMessage(ChatMessage(text, false))
                    val speakIntent = Intent(this, VoiceService::class.java)
                    speakIntent.action = "com.stargazed.assistant.ACTION_SPEAK"
                    speakIntent.putExtra("speak_text", text)
                    startService(speakIntent)
                }
                "swipe", "scroll" -> {
                    val dir = if (args.has("direction")) args.getString("direction") else "up"
                    val distance = if (args.has("distance")) args.getInt("distance") else 600
                    performSwipe(dir, distance)
                    MainActivity.addMessage(ChatMessage("⬤ Swiping $dir", false))
                }
                "press_button" -> {
                    val button = args.getString("button").uppercase()
                    when (button) {
                        "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
                        "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
                        "RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                        "NOTIFICATIONS" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                        "ENTER" -> {
                            val focused = findFocusedNode()
                            focused?.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION)
                            sendKeys("\n")
                        }
                        "VOLUME_UP" -> sendKeyEvent(android.view.KeyEvent.KEYCODE_VOLUME_UP)
                        "VOLUME_DOWN" -> sendKeyEvent(android.view.KeyEvent.KEYCODE_VOLUME_DOWN)
                    }
                    MainActivity.addMessage(ChatMessage("⬤ Pressed $button", false))
                }
                "type_text" -> {
                    val text = args.getString("text")
                    val submit = args.optBoolean("submit", false)
                    
                    val focused = findFocusedNode() ?: findFirstEditText()
                    var success = false
                    if (focused != null) {
                        val bundle = android.os.Bundle()
                        bundle.putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                        success = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                    }
                    
                    if (!success) {
                        // Fallback: use shell injection if we have root, but for standard we just send intents
                        Log.d(TAG, "Accessibility set_text failed, falling back to copy/paste")
                        sendKeys(text)
                    }
                    
                    if (submit) {
                        // Usually "ENTER" is keycode 66
                        Log.d(TAG, "Submitting text...")
                        sendKeyEvent(66) // KEYCODE_ENTER
                    }
                    MainActivity.addMessage(ChatMessage("⬤ Typed: \"$text\"", false))
                }
                "long_press" -> {
                    val x = args.getDouble("x").toFloat()
                    val y = args.getDouble("y").toFloat()
                    val duration = args.optLong("duration", 800)
                    performLongPress(x, y, duration)
                    MainActivity.addMessage(ChatMessage("⬤ Long pressed at (${x.toInt()}, ${y.toInt()})", false))
                }
                "double_tap" -> {
                    val x = args.getDouble("x").toFloat()
                    val y = args.getDouble("y").toFloat()
                    performTap(x, y)
                    android.os.Handler(mainLooper).postDelayed({ performTap(x, y) }, 100)
                    MainActivity.addMessage(ChatMessage("⬤ Double tapped at (${x.toInt()}, ${y.toInt()})", false))
                }
                "launch_app" -> {
                    val pkg = args.getString("packageName")
                    val intent = packageManager.getLaunchIntentForPackage(pkg)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                        MainActivity.addMessage(ChatMessage("⬤ Launched $pkg", false))
                    } else {
                        MainActivity.addMessage(ChatMessage("⚠ App $pkg not found", false))
                    }
                }
                "open_url" -> {
                    val url = args.getString("url")
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    MainActivity.addMessage(ChatMessage("⬤ Opening $url", false))
                }
                "list_apps" -> {
                    val apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                    val appList = JSONArray()
                    for (app in apps) {
                        val label = packageManager.getApplicationLabel(app).toString()
                        val pkg = app.packageName
                        appList.put(JSONObject().apply {
                            put("label", label); put("packageName", pkg)
                        })
                    }
                    val response = JSONObject().apply {
                        put("type", "request")
                        put("intent", "Here are the installed apps: $appList. What would you like to know?")
                        put("screenNodes", JSONArray())
                    }
                    webSocket?.send(response.toString())
                    MainActivity.addMessage(ChatMessage("⬤ Listing ${appList.length()} apps...", false))
                }
                "set_orientation" -> {
                    val orientation = args.getString("orientation")
                    // orientation changes are system-level, we notify the user
                    MainActivity.addMessage(ChatMessage("⬤ Orientation: $orientation (requires system permission)", false))
                }
                "take_screenshot" -> {
                    // Screenshot via AccessibilityService API requires runtime type resolution
                    // We notify Gemini the screen data is already sent via screenNodes
                    MainActivity.addMessage(ChatMessage("⬤ Screenshot: use screen nodes for element detection", false))
                }
                "error" -> {
                    val msg = data.optString("message", "Unknown error")
                    MainActivity.addMessage(ChatMessage("⚠ Backend error: $msg", false))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed parsing command", e)
        }
    }

    private fun performTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun performLongPress(x: Float, y: Float, duration: Long) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun performSwipe(direction: String, distance: Int) {
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val cx = screenWidth / 2f
        val cy = screenHeight / 2f

        val startX: Float; val startY: Float; val endX: Float; val endY: Float
        when (direction.lowercase()) {
            "up"    -> { startX = cx; startY = cy; endX = cx; endY = cy - distance }
            "down"  -> { startX = cx; startY = cy; endX = cx; endY = cy + distance }
            "left"  -> { startX = cx; startY = cy; endX = cx - distance; endY = cy }
            "right" -> { startX = cx; startY = cy; endX = cx + distance; endY = cy }
            else    -> { startX = cx; startY = cy; endX = cx; endY = cy - distance }
        }

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun sendKeys(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("text", text))
        val focused = findFocusedNode() ?: findFirstEditText()
        focused?.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun sendKeyEvent(keyCode: Int) {
        val instrumentation = android.app.Instrumentation()
        Thread {
            instrumentation.sendKeyDownUpSync(keyCode)
        }.start()
    }

    private fun findFocusedNode(): AccessibilityNodeInfo? {
        return rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    }

    private fun findFirstEditText(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findEditText(root)
    }

    private fun findEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val result = node.getChild(i)?.let { findEditText(it) }
            if (result != null) return result
        }
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        webSocket?.close(1000, "Service destroyed")
        voiceReceiver?.let { unregisterReceiver(it) }
    }
}
