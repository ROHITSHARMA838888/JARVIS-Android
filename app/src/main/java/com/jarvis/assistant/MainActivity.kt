package com.jarvis.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var arcReactor: ArcReactorView
    private lateinit var tvRootBadge: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvChatLog: TextView
    private lateinit var scrollChat: ScrollView
    private lateinit var etCommand: EditText
    private lateinit var btnMic: Button
    private lateinit var btnSend: Button
    private lateinit var btnMute: Button
    private lateinit var btnSettings: Button

    private lateinit var deviceController: DeviceController
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var isMuted = false
    private var isListening = false

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val prefs by lazy { getSharedPreferences("jarvis_prefs", MODE_PRIVATE) }

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening()
            else Toast.makeText(this, "Microphone permission required for voice.", Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        arcReactor = findViewById(R.id.arcReactor)
        tvRootBadge = findViewById(R.id.tvRootBadge)
        tvStatus = findViewById(R.id.tvStatus)
        tvChatLog = findViewById(R.id.tvChatLog)
        scrollChat = findViewById(R.id.scrollChat)
        etCommand = findViewById(R.id.etCommand)
        btnMic = findViewById(R.id.btnMic)
        btnSend = findViewById(R.id.btnSend)
        btnMute = findViewById(R.id.btnMute)
        btnSettings = findViewById(R.id.btnSettings)

        deviceController = DeviceController(this)
        tts = TextToSpeech(this, this)

        appendLog("SYSTEM", "JARVIS OS v1.0 initialized. At your service, Sir.")
        checkRootBadge()

        arcReactor.setOnClickListener { toggleVoiceInput() }
        btnMic.setOnClickListener { toggleVoiceInput() }

        btnSend.setOnClickListener { submitTypedCommand() }
        etCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitTypedCommand()
                true
            } else false
        }

        btnMute.setOnClickListener {
            isMuted = !isMuted
            btnMute.text = if (isMuted) "🔇" else "🔊"
            if (isMuted) tts?.stop()
        }

        btnSettings.setOnClickListener { showSettingsDialog() }
    }

    private fun checkRootBadge() {
        scope.launch {
            val hasRoot = withContext(Dispatchers.IO) { RootExecutor.isRootAvailable() }
            val rootEnabled = prefs.getBoolean("root_enabled", true)
            tvRootBadge.text = when {
                hasRoot && rootEnabled -> "ROOT: ACTIVE (FULL ACCESS)"
                hasRoot && !rootEnabled -> "ROOT: AVAILABLE (PAUSED)"
                else -> "MODE: NON-ROOT + ACCESSIBILITY"
            }
        }
    }

    private fun toggleVoiceInput() {
        if (isListening) {
            speechRecognizer?.stopListening()
            isListening = false
            arcReactor.setState(ArcReactorView.State.IDLE)
            tvStatus.text = "STANDBY"
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startListening()
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition not available on device.", Toast.LENGTH_SHORT).show()
            return
        }
        tts?.stop()
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                    arcReactor.setState(ArcReactorView.State.LISTENING)
                    tvStatus.text = "LISTENING..."
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    isListening = false
                    tvStatus.text = "PROCESSING..."
                }

                override fun onError(error: Int) {
                    isListening = false
                    arcReactor.setState(ArcReactorView.State.IDLE)
                    tvStatus.text = "STANDBY (TAP TO SPEAK)"
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!spoken.isNullOrBlank()) {
                        handleCommand(spoken)
                    } else {
                        arcReactor.setState(ArcReactorView.State.IDLE)
                        tvStatus.text = "STANDBY"
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val langPref = prefs.getString("language", "Hinglish") ?: "Hinglish"
        val recLocale = if (langPref == "Hindi") "hi-IN" else "en-IN"
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, recLocale)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun submitTypedCommand() {
        val text = etCommand.text.toString().trim()
        if (text.isNotEmpty()) {
            etCommand.setText("")
            handleCommand(text)
        }
    }

    private fun handleCommand(command: String) {
        appendLog("SIR", command)
        tvStatus.text = "THINKING..."
        val apiKey = prefs.getString("api_key", "") ?: ""
        val model = prefs.getString("model", "gemini-2.5-flash") ?: "gemini-2.5-flash"
        val language = prefs.getString("language", "Hinglish") ?: "Hinglish"
        val rootAllowed = prefs.getBoolean("root_enabled", true)

        scope.launch {
            val brain = withContext(Dispatchers.IO) {
                GeminiBrain.process(command, apiKey, model, language)
            }
            val outcome = withContext(Dispatchers.IO) {
                deviceController.execute(brain.action, brain.params, rootAllowed)
            }

            appendLog("JARVIS", brain.reply)
            if (outcome.message.isNotBlank()) {
                appendLog("EXEC", outcome.message)
            }

            speak(brain.reply)

            if (outcome.requiresRebootConfirm) {
                showRebootConfirmDialog()
            }
        }
    }

    private fun showRebootConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("Confirm System Reboot")
            .setMessage("Sir, are you sure you want to reboot the phone via Root?")
            .setPositiveButton("Reboot Now") { _, _ ->
                scope.launch(Dispatchers.IO) { RootExecutor.run("svc power reboot") }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSettingsDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val etApiKey = view.findViewById<EditText>(R.id.etApiKey)
        val etModel = view.findViewById<EditText>(R.id.etModel)
        val spLanguage = view.findViewById<Spinner>(R.id.spLanguage)
        val cbRoot = view.findViewById<CheckBox>(R.id.cbRootEnabled)
        val btnAcc = view.findViewById<Button>(R.id.btnOpenAccessibility)
        val btnAssist = view.findViewById<Button>(R.id.btnOpenAssistantSettings)

        val languages = listOf("Hinglish", "English", "Hindi")
        spLanguage.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languages)

        etApiKey.setText(prefs.getString("api_key", ""))
        etModel.setText(prefs.getString("model", "gemini-2.5-flash"))
        val savedLang = prefs.getString("language", "Hinglish")
        spLanguage.setSelection(languages.indexOf(savedLang).coerceAtLeast(0))
        cbRoot.isChecked = prefs.getBoolean("root_enabled", true)

        btnAcc.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btnAssist.setOnClickListener {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        }

        AlertDialog.Builder(this)
            .setTitle("JARVIS Configuration")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putString("api_key", etApiKey.text.toString().trim())
                    .putString("model", etModel.text.toString().trim().ifBlank { "gemini-2.5-flash" })
                    .putString("language", languages[spLanguage.selectedItemPosition])
                    .putBoolean("root_enabled", cbRoot.isChecked)
                    .apply()
                updateTtsLanguage()
                checkRootBadge()
                Toast.makeText(this, "Settings saved, Sir.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            updateTtsLanguage()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    runOnUiThread {
                        arcReactor.setState(ArcReactorView.State.SPEAKING)
                        tvStatus.text = "SPEAKING..."
                    }
                }

                override fun onDone(utteranceId: String?) {
                    runOnUiThread {
                        arcReactor.setState(ArcReactorView.State.IDLE)
                        tvStatus.text = "STANDBY"
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    runOnUiThread {
                        arcReactor.setState(ArcReactorView.State.IDLE)
                        tvStatus.text = "STANDBY"
                    }
                }
            })
        }
    }

    private fun updateTtsLanguage() {
        val lang = prefs.getString("language", "Hinglish") ?: "Hinglish"
        val locale = if (lang == "Hindi") Locale("hi", "IN") else Locale("en", "IN")
        tts?.language = locale
    }

    private fun speak(text: String) {
        if (isMuted || !isTtsReady || text.isBlank()) {
            arcReactor.setState(ArcReactorView.State.IDLE)
            tvStatus.text = "STANDBY"
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_${System.currentTimeMillis()}")
    }

    private fun appendLog(sender: String, message: String) {
        val line = "[$sender] $message\n\n"
        tvChatLog.append(line)
        scrollChat.post { scrollChat.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        scope.cancel()
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
