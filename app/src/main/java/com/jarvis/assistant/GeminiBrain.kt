package com.jarvis.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object GeminiBrain {

    data class BrainResult(
        val reply: String,
        val action: String,
        val params: Map<String, String>
    )

    fun process(
        userText: String,
        apiKey: String,
        model: String,
        language: String
    ): BrainResult {
        if (apiKey.isBlank()) {
            return offlineFallback(userText, language)
        }

        return try {
            val cleanModel = model.ifBlank { "gemini-2.5-flash" }.trim()
            val endpoint =
                "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent?key=${apiKey.trim()}"
            val url = URL(endpoint)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 15000
                readTimeout = 20000
                doOutput = true
            }

            val langInstruction = when (language) {
                "Hindi" -> "Always reply in natural, respectful Hindi (Devanagari script), addressing the user as 'सर'."
                "Hinglish" -> "Always reply in friendly, smart Hinglish (Hindi written in English letters), addressing the user as 'Sir'."
                else -> "Always reply in crisp, intelligent English, addressing the user as 'Sir'."
            }

            val systemPrompt = """
                You are JARVIS, an advanced Iron Man-inspired Android AI assistant.
                $langInstruction
                Keep replies short (1-3 sentences), witty, and helpful.
                You MUST respond ONLY with a valid JSON object using this exact schema:
                {
                  "reply": "your spoken reply to user",
                  "action": "ACTION_NAME",
                  "params": {}
                }
                Allowed ACTION_NAME values:
                - NONE (for normal conversation or questions)
                - FLASHLIGHT_ON
                - FLASHLIGHT_OFF
                - GET_BATTERY_INFO
                - GET_DEVICE_STATS
                - OPEN_APP (params: {"app_name": "WhatsApp"})
                - SET_VOLUME (params: {"mode": "up|down|mute|unmute"})
                - SET_ALARM (params: {"hour": "7", "minute": "30", "label": "Wake up"})
                - DIAL_NUMBER (params: {"number": "1234567890"})
                - LOCK_SCREEN
                - TAKE_SCREENSHOT
                - WIFI_ON
                - WIFI_OFF
                - DATA_ON
                - DATA_OFF
                - BLUETOOTH_ON
                - BLUETOOTH_OFF
                - REBOOT_CONFIRM
            """.trimIndent()

            val payload = JSONObject().apply {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                })
                put("contents", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", userText)))
                }))
                put("generationConfig", JSONObject().apply {
                    put("responseMimeType", "application/json")
                    put("temperature", 0.4)
                })
            }

            OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream.bufferedReader().readText()

            if (code !in 200..299) {
                val fallback = offlineFallback(userText, language)
                return fallback.copy(reply = "${fallback.reply} (API HTTP $code)")
            }

            val root = JSONObject(body)
            val rawText = root.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            val parsed = JSONObject(rawText)
            val reply = parsed.optString("reply", "Done, Sir.")
            val action = parsed.optString("action", "NONE")
            val paramsObj = parsed.optJSONObject("params")
            val paramsMap = mutableMapOf<String, String>()
            if (paramsObj != null) {
                val keys = paramsObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    paramsMap[k] = paramsObj.optString(k, "")
                }
            }
            BrainResult(reply, action, paramsMap)
        } catch (e: Exception) {
            val fallback = offlineFallback(userText, language)
            if (fallback.action != "NONE") fallback
            else BrainResult("Sir, connection issue: ${e.message}", "NONE", emptyMap())
        }
    }

    fun offlineFallback(text: String, language: String): BrainResult {
        val q = text.lowercase(Locale.US).trim()
        return when {
            (q.contains("torch") || q.contains("flash") || q.contains("light")) &&
                (q.contains("on") || q.contains("jala") || q.contains("chalu")) ->
                BrainResult("Flashlight on kar raha hoon, Sir.", "FLASHLIGHT_ON", emptyMap())

            (q.contains("torch") || q.contains("flash") || q.contains("light")) &&
                (q.contains("off") || q.contains("band") || q.contains("bujha")) ->
                BrainResult("Flashlight band kar di, Sir.", "FLASHLIGHT_OFF", emptyMap())

            q.contains("battery") || q.contains("charge") ->
                BrainResult("Battery status check kar raha hoon, Sir.", "GET_BATTERY_INFO", emptyMap())

            q.contains("temp") || q.contains("ram") || q.contains("stats") || q.contains("status") ->
                BrainResult("System diagnostics check kar raha hoon, Sir.", "GET_DEVICE_STATS", emptyMap())

            q.contains("wifi") && (q.contains("on") || q.contains("chalu")) ->
                BrainResult("Wi-Fi chalu kar raha hoon, Sir.", "WIFI_ON", emptyMap())

            q.contains("wifi") && (q.contains("off") || q.contains("band")) ->
                BrainResult("Wi-Fi band kar raha hoon, Sir.", "WIFI_OFF", emptyMap())

            q.contains("data") && (q.contains("on") || q.contains("chalu")) ->
                BrainResult("Mobile data on kar raha hoon, Sir.", "DATA_ON", emptyMap())

            q.contains("data") && (q.contains("off") || q.contains("band")) ->
                BrainResult("Mobile data off kar raha hoon, Sir.", "DATA_OFF", emptyMap())

            q.contains("bluetooth") && (q.contains("on") || q.contains("chalu")) ->
                BrainResult("Bluetooth on kar raha hoon, Sir.", "BLUETOOTH_ON", emptyMap())

            q.contains("bluetooth") && (q.contains("off") || q.contains("band")) ->
                BrainResult("Bluetooth off kar raha hoon, Sir.", "BLUETOOTH_OFF", emptyMap())

            q.contains("lock") && q.contains("screen") ->
                BrainResult("Screen lock kar raha hoon, Sir.", "LOCK_SCREEN", emptyMap())

            q.contains("screenshot") ->
                BrainResult("Screenshot le raha hoon, Sir.", "TAKE_SCREENSHOT", emptyMap())

            q.contains("volume") && (q.contains("up") || q.contains("badha")) ->
                BrainResult("Volume badha raha hoon, Sir.", "SET_VOLUME", mapOf("mode" to "up"))

            q.contains("volume") && (q.contains("down") || q.contains("kam")) ->
                BrainResult("Volume kam kar raha hoon, Sir.", "SET_VOLUME", mapOf("mode" to "down"))

            q.contains("reboot") || q.contains("restart") ->
                BrainResult("Reboot confirmation chahiye, Sir.", "REBOOT_CONFIRM", emptyMap())

            q.startsWith("open ") || q.contains("kholo") -> {
                val app = q.removePrefix("open ").replace("kholo", "").trim()
                BrainResult("$app open kar raha hoon, Sir.", "OPEN_APP", mapOf("app_name" to app))
            }

            else -> {
                val msg = if (language == "Hindi") {
                    "ऑफलाइन मोड में हूँ सर। कृपया Settings में Gemini API Key डालें ताकि मैं हर सवाल का जवाब दे सकूँ।"
                } else {
                    "Offline mode active, Sir. Add your free Gemini API Key in Settings for full AI conversation."
                }
                BrainResult(msg, "NONE", emptyMap())
            }
        }
    }
}
