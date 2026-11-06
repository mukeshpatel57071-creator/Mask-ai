package com.example.maskai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

// --- GOLD & DARK THEME COLORS ---
val BgDark = Color(0xFF080706)
val PanelDark = Color(0xFF17120A)
val PanelBubble = Color(0xFF21190D)
val GoldPrimary = Color(0xFFE7B84B)
val GoldSecondary = Color(0xFFC99732)
val TextLight = Color(0xFFFFF8E8)
val TextMuted = Color(0xFFC9B98F)
val BorderGold = Color(0x40D6AD55)

data class ChatMsg(val role: String, val text: String)
data class TaskItem(val id: Long, val text: String, var done: Boolean)

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Native Text-To-Speech
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("hi", "IN")
            }
        }

        setContent {
            MaterialTheme {
                MaskAiApp(
                    onSpeak = { text, pitch, speed ->
                        tts?.setPitch(pitch)
                        tts?.setSpeechRate(speed)
                        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "MASK_TTS")
                    },
                    onStopSpeak = { tts?.stop() },
                    initSpeech = { onResult, onStatus ->
                        startNativeListening(onResult, onStatus)
                    },
                    stopSpeechRec = {
                        speechRecognizer?.stopListening()
                        speechRecognizer?.destroy()
                        speechRecognizer = null
                    }
                )
            }
        }
    }

    private fun startNativeListening(onResult: (String) -> Unit, onStatus: (String) -> Unit) {
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { onStatus("🎙 सुन रही हूँ...") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { onStatus("Ready • बात शुरू करो") }
            override fun onError(error: Int) { onStatus("Ready • बात शुरू करो") }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    onResult(matches[0])
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechRecognizer?.startListening(intent)
    }

    override fun onDestroy() {
        tts?.shutdown()
        speechRecognizer?.destroy()
        super.onDestroy()
    }
}

@Composable
fun MaskAiApp(
    onSpeak: (String, Float, Float) -> Unit,
    onStopSpeak: () -> Unit,
    initSpeech: ((String) -> Unit, (String) -> Unit) -> Unit,
    stopSpeechRec: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("mask_ultimate_v1", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // --- STATES ---
    var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
    var activeMode by remember { mutableStateOf("COMPANION") }
    var voiceStatus by remember { mutableStateOf("Ready • बात शुरू करो") }
    var clockText by remember { mutableStateOf("") }
    var userInput by remember { mutableStateOf("") }
    var isLive by remember { mutableStateOf(false) }
    var autoSpeak by remember { mutableStateOf(true) }
    var pitch by remember { mutableFloatStateOf(1.15f) }
    var speed by remember { mutableFloatStateOf(1.0f) }

    // Lists
    val messages = remember {
        mutableStateListOf(
            ChatMsg("bot", "नमस्ते! 👑 मैं MASK हूँ (Native Kotlin Edition)। अब मेरे पास memory, notes, tasks, native voice और phone controls हैं। Settings (⚙️) में जाकर अपनी Gemini API Key जोड़ें।")
        )
    }
    val memories = remember {
        val saved = prefs.getStringSet("memories", emptySet())?.toList() ?: emptyList()
        mutableStateListOf(*saved.toTypedArray())
    }
    val notes = remember {
        val saved = prefs.getStringSet("notes", emptySet())?.toList() ?: emptyList()
        mutableStateListOf(*saved.toTypedArray())
    }
    val tasks = remember { mutableStateListOf<TaskItem>() }

    // Dialog Controls
    var activeDialog by remember { mutableStateOf<String?>(null) } // "TOOLS", "MEMORY", "SETTINGS"

    // Live Clock
    LaunchedEffect(Unit) {
        while (true) {
            val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale("hi", "IN"))
            clockText = sdf.format(Date())
            delay(1000)
        }
    }

    // Mic Permission Launcher
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            isLive = true
            initSpeech({ spokenText ->
                isLive = false
                handleSend(
                    spokenText, context, apiKey, memories, tasks, notes,
                    messages, prefs, autoSpeak, pitch, speed,
                    onStatus = { voiceStatus = it },
                    onSpeak = onSpeak,
                    scope = scope
                )
            }, { status -> voiceStatus = status })
        } else {
            Toast.makeText(context, "माइक परमिशन ज़रूरी है!", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF38280D), Color(0xFF120E07), BgDark)))
            .systemBarsPadding()
            .imePadding()
    ) {
        // --- 1. HEADER ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0x14D6AD55))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(GoldPrimary)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "MASK AI ULTIMATE",
                        color = GoldPrimary,
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "Native Kotlin AI • Offline tools ready",
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallHeaderBtn("⚡ Tools") { activeDialog = "TOOLS" }
                SmallHeaderBtn("🧠") { activeDialog = "MEMORY" }
                SmallHeaderBtn("⚙️") { activeDialog = "SETTINGS" }
            }
        }

        // --- 2. DOCK (Modes + Avatar Status) ---
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0x0FD6AD55))
                .padding(10.dp)
        ) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    "COMPANION" to "👑 Companion",
                    "FOCUS" to "🎯 Focus",
                    "PLAYFUL" to "😘 Playful",
                    "CALM" to "✨ Calm"
                ).forEach { (modeKey, label) ->
                    val isSelected = activeMode == modeKey
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(30.dp))
                            .background(if (isSelected) Color(0x40C99732) else Color(0x18D6AD55))
                            .border(1.dp, if (isSelected) GoldPrimary else BorderGold, RoundedCornerShape(30.dp))
                            .clickable { activeMode = modeKey }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(label, color = TextLight, fontSize = 11.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Avatar Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1C160B))
                    .border(1.dp, BorderGold, RoundedCornerShape(16.dp))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar Circle
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2B1E0D))
                        .border(2.dp, GoldPrimary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("😊", fontSize = 26.sp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(voiceStatus, color = GoldPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(clockText, color = TextMuted, fontSize = 10.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SmallHeaderBtn("⏹ Stop voice") {
                            onStopSpeak()
                            stopSpeechRec()
                            isLive = false
                            voiceStatus = "Ready • बात शुरू करो"
                        }
                    }
                }
            }
        }

        // --- 3. QUICK BAR ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                "🕒 Date & Time" to "आज की तारीख और समय बताओ",
                "📋 My tasks" to "मेरे tasks बताओ",
                "✨ Suggest something" to "कुछ नया और रोचक सीखने का सुझाव दो",
                "🔎 Search" to "Google पर खोजो: AI News"
            ).forEach { (label, query) ->
                SmallHeaderBtn(label) {
                    handleSend(
                        query, context, apiKey, memories, tasks, notes,
                        messages, prefs, autoSpeak, pitch, speed,
                        onStatus = { voiceStatus = it },
                        onSpeak = onSpeak,
                        scope = scope
                    )
                }
            }
        }

        // --- 4. CHAT MESSAGES ---
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 10.dp)
        ) {
            items(messages) { msg ->
                val isUser = msg.role == "user"
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
                ) {
                    Box(
                        modifier = Modifier
                            .widthIn(max = 300.dp)
                            .clip(
                                RoundedCornerShape(
                                    topStart = 16.dp, topEnd = 16.dp,
                                    bottomStart = if (isUser) 16.dp else 4.dp,
                                    bottomEnd = if (isUser) 4.dp else 16.dp
                                )
                            )
                            .background(
                                if (isUser) Brush.linearGradient(listOf(Color(0xFFD7A63B), Color(0xFF8B6019)))
                                else Brush.linearGradient(listOf(PanelBubble, PanelBubble))
                            )
                            .border(1.dp, BorderGold, RoundedCornerShape(16.dp))
                            .padding(12.dp)
                    ) {
                        Text(msg.text, color = TextLight, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                    if (!isUser) {
                        Text(
                            text = "🔊 सुनो",
                            color = GoldPrimary,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .clickable { onSpeak(msg.text, pitch, speed) }
                                .padding(4.dp)
                        )
                    }
                }
            }
        }

        // Auto-scroll chat
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }

        // --- 5. COMPOSER ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PanelDark)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // LIVE Mic Button
            Button(
                onClick = {
                    if (isLive) {
                        stopSpeechRec()
                        isLive = false
                        voiceStatus = "Ready • बात शुरू करो"
                    } else {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            isLive = true
                            initSpeech({ spokenText ->
                                isLive = false
                                handleSend(
                                    spokenText, context, apiKey, memories, tasks, notes,
                                    messages, prefs, autoSpeak, pitch, speed,
                                    onStatus = { voiceStatus = it },
                                    onSpeak = onSpeak,
                                    scope = scope
                                )
                            }, { status -> voiceStatus = status })
                        } else {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isLive) GoldPrimary else Color(0x25D6AD55)
                ),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
            ) {
                Text(
                    if (isLive) "🎙 ON" else "🎙 LIVE",
                    color = if (isLive) BgDark else TextLight,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Native TextField
            OutlinedTextField(
                value = userInput,
                onValueChange = { userInput = it },
                placeholder = { Text("MASK से बात करो...", color = TextMuted, fontSize = 13.sp) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextLight,
                    unfocusedTextColor = TextLight,
                    cursorColor = GoldPrimary,
                    focusedBorderColor = GoldPrimary,
                    unfocusedBorderColor = BorderGold,
                    focusedContainerColor = Color(0xFF171108),
                    unfocusedContainerColor = Color(0xFF171108)
                )
            )

            // Send Button
            Button(
                onClick = {
                    val textToSend = userInput.trim()
                    if (textToSend.isNotEmpty()) {
                        userInput = ""
                        handleSend(
                            textToSend, context, apiKey, memories, tasks, notes,
                            messages, prefs, autoSpeak, pitch, speed,
                            onStatus = { voiceStatus = it },
                            onSpeak = onSpeak,
                            scope = scope
                        )
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Text("भेजो ➤", color = BgDark, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }

    // --- DIALOGS (Tools, Memory, Settings) ---
    if (activeDialog == "SETTINGS") {
        AlertDialog(
            onDismissRequest = { activeDialog = null },
            containerColor = PanelDark,
            title = { Text("⚙️ Settings & API Key", color = GoldPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Gemini API Key (gemini-2.5-flash):", color = TextLight, fontSize = 12.sp)
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        visualTransformation = PasswordVisualTransformation(),
                        placeholder = { Text("Paste API Key here", color = TextMuted) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextLight,
                            unfocusedTextColor = TextLight,
                            focusedBorderColor = GoldPrimary,
                            unfocusedBorderColor = BorderGold
                        )
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = autoSpeak, onCheckedChange = { autoSpeak = it })
                        Text("हर जवाब बोलकर सुनाओ (Auto-TTS)", color = TextLight, fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        prefs.edit().putString("api_key", apiKey.trim()).apply()
                        Toast.makeText(context, "API Key सेव हो गई!", Toast.LENGTH_SHORT).show()
                        activeDialog = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
                ) {
                    Text("Save", color = BgDark, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (activeDialog == "MEMORY") {
        var newMem by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { activeDialog = null },
            containerColor = PanelDark,
            title = { Text("🧠 Smart Memory", color = GoldPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newMem,
                        onValueChange = { newMem = it },
                        placeholder = { Text("याद रखना कि...", color = TextMuted) },
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextLight, unfocusedTextColor = TextLight)
                    )
                    Button(
                        onClick = {
                            if (newMem.isNotBlank()) {
                                memories.add(0, newMem.trim())
                                prefs.edit().putStringSet("memories", memories.toSet()).apply()
                                newMem = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
                    ) {
                        Text("Memory सेव करो", color = BgDark)
                    }
                    HorizontalDivider(color = BorderGold)
                    memories.forEach { m ->
                        Text("• $m", color = TextLight, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { activeDialog = null }) { Text("बंद करें", color = GoldPrimary) }
            }
        )
    }

    if (activeDialog == "TOOLS") {
        var newTask by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { activeDialog = null },
            containerColor = PanelDark,
            title = { Text("⚡ Tasks & Phone Launcher", color = GoldPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newTask,
                            onValueChange = { newTask = it },
                            placeholder = { Text("नया Task...", color = TextMuted) },
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextLight, unfocusedTextColor = TextLight)
                        )
                        Button(
                            onClick = {
                                if (newTask.isNotBlank()) {
                                    tasks.add(0, TaskItem(System.currentTimeMillis(), newTask.trim(), false))
                                    newTask = ""
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
                        ) {
                            Text("+", color = BgDark)
                        }
                    }
                    tasks.forEach { t ->
                        Text("• ${t.text}", color = TextLight, fontSize = 13.sp)
                    }
                    HorizontalDivider(color = BorderGold)
                    Text("📱 Quick Phone Apps:", color = GoldPrimary, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SmallHeaderBtn("YouTube") { openExternalUrl(context, "https://m.youtube.com") }
                        SmallHeaderBtn("WhatsApp") { openExternalUrl(context, "https://wa.me/") }
                        SmallHeaderBtn("Instagram") { openExternalUrl(context, "https://instagram.com") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { activeDialog = null }) { Text("बंद करें", color = GoldPrimary) }
            }
        )
    }
}

@Composable
fun SmallHeaderBtn(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x18D6AD55))
            .border(1.dp, BorderGold, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(text, color = TextLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

// --- LOGIC: LOCAL INTENTS + PHONE CONTROLS + GEMINI 2.5 FLASH ---
fun handleSend(
    text: String,
    context: Context,
    apiKey: String,
    memories: MutableList<String>,
    tasks: MutableList<TaskItem>,
    notes: MutableList<String>,
    messages: MutableList<ChatMsg>,
    prefs: android.content.SharedPreferences,
    autoSpeak: Boolean,
    pitch: Float,
    speed: Float,
    onStatus: (String) -> Unit,
    onSpeak: (String, Float, Float) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope
) {
    messages.add(ChatMsg("user", text))
    onStatus("MASK सोच रही है...")

    scope.launch {
        val localReply = checkLocalIntent(text, context, memories, tasks, notes, prefs)
        val finalReply = if (localReply != null) {
            localReply
        } else if (apiKey.isBlank()) {
            "मैंने तुम्हारी बात सुन ली! 💜 AI चैट चालू करने के लिए ऊपर ⚙️ Settings में अपनी Gemini API Key पेस्ट करो।"
        } else {
            callGeminiNative(text, apiKey, memories, messages)
        }

        messages.add(ChatMsg("bot", finalReply))
        onStatus("Ready • बात शुरू करो")
        if (autoSpeak) {
            onSpeak(finalReply, pitch, speed)
        }
    }
}

fun checkLocalIntent(
    input: String,
    context: Context,
    memories: MutableList<String>,
    tasks: MutableList<TaskItem>,
    notes: MutableList<String>,
    prefs: android.content.SharedPreferences
): String? {
    val low = input.lowercase(Locale.ROOT).trim()

    if (low.startsWith("याद रखना") || low.startsWith("remember")) {
        val mem = input.replace(Regex("^(याद रखना|remember that|remember:)", RegexOption.IGNORE_CASE), "").trim()
        if (mem.isNotEmpty()) {
            memories.add(0, mem)
            prefs.edit().putStringSet("memories", memories.toSet()).apply()
            return "ठीक है, मैंने यह बात तुम्हारी MASK मेमोरी में सेव कर ली है।"
        }
    }
    if (low.contains("मेरी यादें") || low.contains("show memories")) {
        return if (memories.isEmpty()) "अभी कोई मेमोरी सेव नहीं है।" else memories.joinToString("\n") { "• $it" }
    }
    if (low.contains("आज की तारीख") || low.contains("समय") || low.contains("date and time")) {
        val sdf = SimpleDateFormat("EEEE, dd MMMM yyyy, hh:mm a", Locale("hi", "IN"))
        return "अभी ${sdf.format(Date())} है।"
    }
    if (low.contains("मेरे tasks") || low.contains("my tasks")) {
        return if (tasks.isEmpty()) "अभी कोई task नहीं है। ⚡ Tools में जाकर नया task जोड़ो।"
        else tasks.joinToString("\n") { "• ${it.text}" }
    }
    if (low.contains("youtube") || low.contains("यूट्यूब") || low.contains("गाना")) {
        val q = input.replace(Regex("youtube|यूट्यूब|पर|पे|खोलो|चलाओ|गाना|play", RegexOption.IGNORE_CASE), "").trim()
        openExternalUrl(context, "https://www.youtube.com/results?search_query=${URLEncoder.encode(q, "UTF-8")}")
        return "मैंने YouTube खोल दिया है।"
    }
    if (low.contains("whatsapp") || low.contains("व्हाट्सएप")) {
        openExternalUrl(context, "https://wa.me/?text=${URLEncoder.encode(input, "UTF-8")}")
        return "WhatsApp खोल दिया है।"
    }
    if (low.contains("google पर खोजो") || low.contains("सर्च करो")) {
        val q = input.replace(Regex("google पर खोजो|सर्च करो|खोजो", RegexOption.IGNORE_CASE), "").trim()
        openExternalUrl(context, "https://www.google.com/search?q=${URLEncoder.encode(q, "UTF-8")}")
        return "मैंने Google Search खोल दिया है: $q"
    }
    return null
}

fun openExternalUrl(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "यह ऐप खोलने में समस्या आई", Toast.LENGTH_SHORT).show()
    }
}

suspend fun callGeminiNative(
    userText: String,
    apiKey: String,
    memories: List<String>,
    history: List<ChatMsg>
): String = withContext(Dispatchers.IO) {
    try {
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 15000
            readTimeout = 15000
        }

        val memBlock = if (memories.isEmpty()) "None" else memories.joinToString("\n") { "- $it" }
        val recentChat = history.takeLast(8).joinToString("\n") { "${if (it.role == "user") "User" else "MASK"}: ${it.text}" }

        val systemPrompt = """
            You are MASK, a warm, emotionally intelligent Indian female personal assistant.
            Reply naturally and concisely in Hindi or Hinglish.
            Saved Memories:
            $memBlock
            Recent Chat:
            $recentChat
            User: $userText
        """.trimIndent()

        val payload = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray().put(
                    JSONObject().put("text", systemPrompt)
                ))
            ))
        }

        OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }

        val code = conn.responseCode
        val responseStream = if (code in 200..299) conn.inputStream else conn.errorStream
        val respText = responseStream.bufferedReader().use { it.readText() }

        if (code !in 200..299) {
            val errObj = JSONObject(respText).optJSONObject("error")
            return@withContext "API Error: ${errObj?.optString("message") ?: "Code $code"}"
        }

        val json = JSONObject(respText)
        val candidates = json.optJSONArray("candidates")
        val firstPart = candidates?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)

        firstPart?.optString("text")?.trim() ?: "मुझे समझ नहीं आया, फिर से बोलो?"
    } catch (e: Exception) {
        "कनेक्शन में दिक्कत आई: ${e.localizedMessage}"
    }
}
