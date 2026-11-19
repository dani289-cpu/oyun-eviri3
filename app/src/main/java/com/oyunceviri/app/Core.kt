package com.oyunceviri.app

import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class SubtitleLine(
    val rect: Rect,
    val rawText: String,
    val translatedText: String
)

object SubtitleProcessor {
    private val ignoredExact = setOf(
        "mkiceandfire",
        "afk modu",
        "döndürme",
        "yardım kilidi",
        "subtitles",
        "skip"
    )

    private val ignoredSubstrings = listOf(
        "mkiceandfire",
        "iceandfire",
        "afk",
        "fps"
    )

    private var lastSignature: String = ""
    private var lastTranslatedLines: List<SubtitleLine> = emptyList()
    private var lastRequestTimeMs: Long = 0L

    fun isNoiseLine(text: String): Boolean {
        val cleaned = text.trim().lowercase()
        if (cleaned.length < 3) return true
        if (cleaned in ignoredExact) return true
        if (ignoredSubstrings.any { cleaned.contains(it) }) return true
        if (cleaned.matches(Regex("^[0-9\\W_]+$"))) return true
        return false
    }

    fun buildSignature(lines: List<Pair<Rect, String>>): String {
        return lines.joinToString("||") { (_, text) ->
            text.trim().lowercase().replace(Regex("\\s+"), " ")
        }
    }

    fun shouldSkipRequest(signature: String, minCooldownMs: Long = 2500L): Boolean {
        val now = System.currentTimeMillis()
        if (signature == lastSignature && lastTranslatedLines.isNotEmpty()) {
            return true
        }
        if (now - lastRequestTimeMs < minCooldownMs && lastTranslatedLines.isNotEmpty()) {
            return true
        }
        return false
    }

    fun getCachedLines(): List<SubtitleLine> = lastTranslatedLines

    fun resetCache() {
        lastSignature = ""
        lastTranslatedLines = emptyList()
    }

    fun translateLines(
        apiKey: String,
        model: String,
        items: List<Pair<Rect, String>>,
        onStatus: (String) -> Unit
    ): List<SubtitleLine> {
        if (items.isEmpty() || apiKey.isBlank()) return emptyList()

        val signature = buildSignature(items)
        if (shouldSkipRequest(signature)) {
            return lastTranslatedLines
        }

        return try {
            lastRequestTimeMs = System.currentTimeMillis()
            val numberedInput = items.mapIndexed { idx, (_, text) -> "[$idx] ${text.trim()}" }.joinToString("\n")
            val prompt = """
                Sen oyun altyazıları için uzman bir Türkçeye çevirmenisin.
                Aşağıdaki diyalog ve oyun altyazı satırlarını doğal, akıcı, konuşma diline ve oyun bağlamına uygun Türkçeye çevir.
                Kelime kelime kaba çevirilerden kaçın; filigran, kanal adı veya anlamsız parça ekleme.
                Sadece her satırı "[numara] Çeviri" formatında döndür:
                $numberedInput
            """.trimIndent()

            val cleanModel = model.removePrefix("models/").trim()
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", apiKey.trim())
                connectTimeout = 9000
                readTimeout = 9000
                doOutput = true
            }

            val payload = JSONObject().put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", prompt))
                    )
                )
            )

            conn.outputStream.use { out ->
                out.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val errBody = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                val errMsg = try {
                    JSONObject(errBody).optJSONObject("error")?.optString("message") ?: "HTTP $code"
                } catch (_: Exception) {
                    "HTTP $code"
                }
                onStatus("API ($code): ${errMsg.take(36)}")
                return lastTranslatedLines
            }

            val responseText = conn.inputStream.bufferedReader().readText()
            val replyText = JSONObject(responseText)
                .optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
                .orEmpty()

            val parsedMap = mutableMapOf<Int, String>()
            replyText.lines().forEach { line ->
                val match = Regex("^\\[(\\d+)]\\s*(.+)$").find(line.trim())
                if (match != null) {
                    val idx = match.groupValues[1].toIntOrNull()
                    val translated = match.groupValues[2].trim()
                    if (idx != null && translated.isNotEmpty() && !isNoiseLine(translated)) {
                        parsedMap[idx] = translated
                    }
                }
            }

            val result = items.mapIndexed { index, (rect, raw) ->
                val fallbackLines = replyText.lines()
                    .map { it.trim().replace(Regex("^\\[\\d+]\\s*"), "") }
                    .filter { it.isNotBlank() && !isNoiseLine(it) }
                val finalText = parsedMap[index] ?: fallbackLines.getOrNull(index) ?: raw.trim()
                SubtitleLine(rect = rect, rawText = raw.trim(), translatedText = finalText)
            }

            lastSignature = signature
            lastTranslatedLines = result
            result
        } catch (e: Exception) {
            onStatus("Bağlantı: ${e.message?.take(32) ?: "Hata"}")
            lastTranslatedLines
        }
    }
}
