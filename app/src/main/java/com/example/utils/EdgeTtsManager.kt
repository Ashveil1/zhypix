package com.example.utils

import android.content.Context
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Data class for voice preferences by language category.
 */
data class VoicePreferences(
    val thaiVoice: String = "th-TH-NiwatNeural",
    val englishVoice: String = "en-US-GuyNeural",
    val japaneseVoice: String = "ja-JP-KeitaNeural",
    val chineseVoice: String = "zh-CN-YunxiNeural",
    val preferredEngine: String = "edge" // "edge" or "google"
)

/**
 * EdgeTtsManager provides high-quality speech synthesis using Microsoft Edge TTS WebSocket API
 * with automatic fallback to Android's built-in TextToSpeech engine.
 */
class EdgeTtsManager(private val context: Context) {

    companion object {
        private const val TAG = "EdgeTtsManager"
        private const val TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val EDGE_WS_URL = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=$TRUSTED_TOKEN"
        private const val CHROMIUM_FULL_VERSION = "131.0.2903.86"
        private const val CHROMIUM_MAJOR_VERSION = "131"

        // Voice Defaults
        const val DEFAULT_THAI_VOICE = "th-TH-NiwatNeural"
        const val DEFAULT_ENGLISH_VOICE = "en-US-GuyNeural"
        const val DEFAULT_JAPANESE_VOICE = "ja-JP-KeitaNeural"
        const val DEFAULT_CHINESE_VOICE = "zh-CN-YunxiNeural"

        var activeInstance: EdgeTtsManager? = null

        fun stopAll() {
            activeInstance?.stop()
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private var mediaPlayer: MediaPlayer? = null
    private var googleTts: TextToSpeech? = null
    private var isGoogleTtsReady = false

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isSynthesizing = MutableStateFlow(false)
    val isSynthesizing: StateFlow<Boolean> = _isSynthesizing.asStateFlow()

    private val _currentText = MutableStateFlow<String?>(null)
    val currentText: StateFlow<String?> = _currentText.asStateFlow()

    private var activeWebSocket: WebSocket? = null

    init {
        activeInstance = this
        // Pre-initialize Google TTS as on-device fallback
        googleTts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isGoogleTtsReady = true
                Log.d(TAG, "On-device Google TTS initialized successfully")
            } else {
                Log.e(TAG, "Google TTS initialization failed with status: $status")
            }
        }
    }

    /**
     * Determines the appropriate voice name based on the language of the text.
     */
    fun selectVoiceForText(text: String, prefs: VoicePreferences): String {
        return when {
            // Thai text detection
            text.any { it in '\u0E00'..'\u0E7F' } -> prefs.thaiVoice
            // Japanese text detection (Hiragana/Katakana)
            text.any { it in '\u3040'..'\u30FF' } -> prefs.japaneseVoice
            // Chinese text detection (CJK Unified Ideographs without Japanese kana)
            text.any { it in '\u4E00'..'\u9FFF' } -> prefs.chineseVoice
            // Default to English voice
            else -> prefs.englishVoice
        }
    }

    /**
     * Cleans Markdown formatting symbols (*, #, _, `, links, etc.) so TTS engines
     * won't pronounce symbols like "asterisk" or "hash".
     */
    private fun cleanMarkdownForSpeech(rawText: String): String {
        return rawText
            // 1. Remove HTML/XML tags
            .replace(Regex("<[^>]*>"), "")
            // 2. Remove multi-line code blocks
            .replace(Regex("```[\\s\\S]*?```"), " [contains code block] ")
            // 3. Inline code `code` -> code
            .replace(Regex("`([^`]+)`"), "$1")
            // 4. Markdown images ![alt](url) -> alt
            .replace(Regex("!\\[([^\\]]*)\\]\\([^\\)]*\\)"), "$1")
            // 5. Markdown links [text](url) -> text
            .replace(Regex("\\[([^\\]]+)\\]\\([^\\)]*\\)"), "$1")
            // 6. Headers (#, ##, ###, etc.) at line start
            .replace(Regex("(?m)^#{1,6}\\s*"), "")
            // 7. Bold, Italic, Strikethrough (*, _, ~)
            .replace(Regex("\\*\\*\\*([^*]+)\\*\\*\\*"), "$1")
            .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
            .replace(Regex("\\*([^*]+)\\*"), "$1")
            .replace(Regex("___([^_]+)___"), "$1")
            .replace(Regex("__([^_]+)__"), "$1")
            .replace(Regex("_([^_]+)_"), "$1")
            .replace(Regex("~~([^~]+)~~"), "$1")
            // 8. Horizontal rules (---, ***, ___)
            .replace(Regex("(?m)^[-*_]{3,}\\s*$"), "")
            // 9. Unordered list markers (* , - , + ) at line start
            .replace(Regex("(?m)^[ \\t]*[*\\-+][ \\t]+"), "")
            // 10. Numbered list markers (1. , 2. ) at line start
            .replace(Regex("(?m)^[ \\t]*\\d+\\.[ \\t]+"), "")
            // 11. Blockquotes (> ) at line start
            .replace(Regex("(?m)^[ \\t]*>[ \\t]*"), "")
            // 12. Table separator lines (|---|---|)
            .replace(Regex("(?m)^\\|[-:| ]+\\|\\s*$"), "")
            // 13. Remaining table pipes
            .replace("|", " ")
            // 14. Remaining standalone asterisks, hashes or tildes
            .replace(Regex("[*#~_]"), "")
            // 15. Normalize excessive whitespace
            .replace(Regex("\\n{3,}"), "\n\n")
            .replace(Regex("[ \\t]+"), " ")
            .trim()
            .take(1200)
    }

    /**
     * Generates Sec-MS-GEC token required by Microsoft Edge TTS servers.
     */
    private fun generateSecMsGec(): String {
        return try {
            val trustedToken = TRUSTED_TOKEN
            val unixSeconds = System.currentTimeMillis() / 1000
            val windowsSeconds = unixSeconds + 11644473600L
            val roundedSeconds = windowsSeconds - (windowsSeconds % 300L)
            val filetimeTicks = roundedSeconds * 10_000_000L
            val strToHash = "$filetimeTicks$trustedToken"
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val digest = md.digest(strToHash.toByteArray(Charsets.US_ASCII))
            val token = digest.joinToString("") { "%02X".format(it) }
            Log.d(TAG, "Generated Sec-MS-GEC: $token (ticks: $filetimeTicks)")
            token
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate Sec-MS-GEC token", e)
            ""
        }
    }

    /**
     * Main entry point to speak text using Edge TTS with fallback to Google TTS.
     */
    fun speak(text: String, prefs: VoicePreferences, onComplete: (() -> Unit)? = null) {
        stop()

        if (text.isBlank()) return

        Log.d(TAG, "speak() called with prefs: thaiVoice=${prefs.thaiVoice}, englishVoice=${prefs.englishVoice}, preferredEngine=${prefs.preferredEngine}")

        val cleanText = cleanMarkdownForSpeech(text)
        if (cleanText.isBlank()) return

        _currentText.value = cleanText

        if (prefs.preferredEngine == "google") {
            Log.d(TAG, "Using Google TTS engine")
            speakWithGoogleTts(cleanText, prefs, onComplete)
            return
        }

        val voiceName = selectVoiceForText(cleanText, prefs)
        Log.d(TAG, "Selected voice for text: $voiceName")
        val lang = if (voiceName.length >= 5) voiceName.substring(0, 5) else "en-US"
        _isSynthesizing.value = true

        scope.launch {
            try {
                synthesizeAndPlayEdgeTts(cleanText, voiceName, lang, onComplete)
            } catch (e: Exception) {
                Log.e(TAG, "Edge TTS synthesis failed: ${e.message}. Trying Online Google TTS fallback.", e)
                try {
                    synthesizeAndPlayOnlineGoogleTts(cleanText, onComplete)
                } catch (e2: Exception) {
                    Log.e(TAG, "Online Google TTS failed: ${e2.message}. Falling back to device Google TTS.", e2)
                    withContext(Dispatchers.Main) {
                        speakWithGoogleTts(cleanText, prefs, onComplete)
                    }
                }
            }
        }
    }

    /**
     * Fallback online TTS service using Google Translate TTS API for natural sound.
     */
    private suspend fun synthesizeAndPlayOnlineGoogleTts(
        text: String,
        onComplete: (() -> Unit)?
    ) = withContext(Dispatchers.IO) {
        val lang = when {
            text.any { it in '\u0E00'..'\u0E7F' } -> "th"
            text.any { it in '\u3040'..'\u30FF' } -> "ja"
            text.any { it in '\u4E00'..'\u9FFF' } -> "zh"
            else -> "en"
        }

        val encodedText = java.net.URLEncoder.encode(text, "UTF-8")
        val url = "https://translate.google.com/translate_tts?ie=UTF-8&q=$encodedText&tl=$lang&client=tw-ob"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Google Translate TTS HTTP Error: ${response.code}")
        }

        val audioBytes = response.body?.bytes() ?: throw Exception("Empty response body from Google Translate TTS")
        if (audioBytes.isEmpty()) throw Exception("Zero audio bytes received")

        val audioFile = File(context.cacheDir, "google_online_tts_${System.currentTimeMillis()}.mp3")
        FileOutputStream(audioFile).use { it.write(audioBytes) }

        withContext(Dispatchers.Main) {
            _isSynthesizing.value = false
            playAudioFile(audioFile, onComplete)
        }
    }

    /**
     * Edge TTS synthesis using WebSocket protocol
     */
    private suspend fun synthesizeAndPlayEdgeTts(
        text: String,
        voiceName: String,
        lang: String,
        onComplete: (() -> Unit)?
    ) = withContext(Dispatchers.IO) {
        val audioFile = File(context.cacheDir, "edge_tts_synth_${System.currentTimeMillis()}.mp3")
        val outputStream = FileOutputStream(audioFile)

        val connectionId = UUID.randomUUID().toString().replace("-", "").lowercase()
        val muid = UUID.randomUUID().toString().replace("-", "").uppercase()
        val secMsGec = generateSecMsGec()
        val wsUrl = "$EDGE_WS_URL&Sec-MS-GEC=$secMsGec&Sec-MS-GEC-Version=1-$CHROMIUM_FULL_VERSION&ConnectionId=$connectionId"
        
        Log.d(TAG, "Starting Edge TTS connection ID: $connectionId")
        Log.d(TAG, "WebSocket URL: $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$CHROMIUM_MAJOR_VERSION.0.0.0 Safari/537.36 Edg/$CHROMIUM_MAJOR_VERSION.0.0.0")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("Cookie", "muid=$muid;")
            .header("X-MSEdge-ClientID", muid)
            .header("Sec-MS-GEC", secMsGec)
            .header("Sec-MS-GEC-Version", "1-$CHROMIUM_FULL_VERSION")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
            
        Log.d(TAG, "Request headers: ${request.headers}")
            
        val requestId = UUID.randomUUID().toString().replace("-", "").uppercase()

        val latch = java.util.concurrent.CountDownLatch(1)
        var synthesisError: Throwable? = null

        val wsListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket Opened: ${response.code} ${response.message}")
                val timestamp = getTimestamp()
                // 1. Send configuration message
                val configMsg = "X-Timestamp:$timestamp\r\n" +
                        "Content-Type:application/json; charset=utf-8\r\n" +
                        "Path:speech.config\r\n\r\n" +
                        "{\"context\":{\"synthesis\":{\"audio\":{\"metadataversion\":\"2020-02-07\",\"dataformat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"
                Log.d(TAG, "Sending speech.config")
                webSocket.send(configMsg)

                // 2. Send SSML request message
                val ssmlMsg = "X-RequestId:$requestId\r\n" +
                        "Content-Type:application/ssml+xml; charset=utf-8\r\n" +
                        "X-Timestamp:$timestamp\r\n" +
                        "Path:ssml\r\n\r\n" +
                        "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$lang'>" +
                        "<voice name='$voiceName'>" +
                        "<prosody pitch='+0Hz' rate='+0%' volume='+0%'>" +
                        escapeXml(text) +
                        "</prosody>" +
                        "</voice></speak>"
                Log.d(TAG, "Sending SSML (Lang: $lang, Voice: $voiceName)")
                webSocket.send(ssmlMsg)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.v(TAG, "Received message: ${text.take(100)}...")
                if (text.contains("Path:turn.end")) {
                    Log.d(TAG, "Synthesis turn ended")
                    latch.countDown()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                if (data.size >= 2) {
                    val buffer = ByteBuffer.wrap(data)
                    val headerLen = buffer.short.toInt() and 0xFFFF
                    if (data.size >= headerLen + 2) {
                        val headerStr = String(data, 2, headerLen, Charsets.UTF_8)
                        if (headerStr.contains("Path:audio")) {
                            val audioDataOffset = 2 + headerLen
                            val audioDataLen = data.size - audioDataOffset
                            if (audioDataLen > 0) {
                                try {
                                    outputStream.write(data, audioDataOffset, audioDataLen)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Write error: ${e.message}")
                                }
                            }
                        } else if (headerStr.contains("Path:audio.metadata")) {
                            Log.v(TAG, "Received audio metadata")
                        }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}. Response: ${response?.code} ${response?.message}", t)
                synthesisError = t
                latch.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket Closed: $code / $reason")
                latch.countDown()
            }
        }

        activeWebSocket = client.newWebSocket(request, wsListener)

        val completedInTime = latch.await(20, TimeUnit.SECONDS)
        try { outputStream.close() } catch (_: Exception) {}

        if (!completedInTime || synthesisError != null || audioFile.length() == 0L) {
            val fileLen = audioFile.length()
            audioFile.delete()
            val msg = when {
                !completedInTime -> "Edge TTS timed out"
                synthesisError != null -> "Edge TTS error: ${synthesisError?.message}"
                fileLen == 0L -> "Edge TTS returned empty audio"
                else -> "Unknown error"
            }
            throw Exception(msg)
        }

        withContext(Dispatchers.Main) {
            _isSynthesizing.value = false
            playAudioFile(audioFile, onComplete)
        }
    }

    private fun playAudioFile(file: File, onComplete: (() -> Unit)?) {
        try {
            stopAudioOnly()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    _isPlaying.value = false
                    _currentText.value = null
                    file.delete()
                    onComplete?.invoke()
                }
                setOnErrorListener { _, _, _ ->
                    _isPlaying.value = false
                    _currentText.value = null
                    file.delete()
                    true
                }
                start()
            }
            _isPlaying.value = true
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio file: ${e.message}", e)
            _isPlaying.value = false
            _currentText.value = null
            file.delete()
        }
    }

    private fun speakWithGoogleTts(text: String, prefs: VoicePreferences, onComplete: (() -> Unit)?) {
        _isSynthesizing.value = false
        if (!isGoogleTtsReady || googleTts == null) {
            Log.e(TAG, "Google TTS is not ready.")
            onComplete?.invoke()
            return
        }

        val locale = when {
            text.any { it in '\u0E00'..'\u0E7F' } -> Locale("th", "TH")
            text.any { it in '\u3040'..'\u30FF' } -> Locale.JAPAN
            text.any { it in '\u4E00'..'\u9FFF' } -> Locale.CHINA
            else -> Locale.US
        }

        googleTts?.language = locale
        _isPlaying.value = true

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "zhypix_tts_${System.currentTimeMillis()}")
        }

        googleTts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _isPlaying.value = true
            }

            override fun onDone(utteranceId: String?) {
                _isPlaying.value = false
                _currentText.value = null
                onComplete?.invoke()
            }

            override fun onError(utteranceId: String?) {
                _isPlaying.value = false
                _currentText.value = null
            }
        })

        googleTts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "zhypix_tts_${System.currentTimeMillis()}")
    }

    fun stop() {
        try {
            activeWebSocket?.cancel()
            activeWebSocket = null
        } catch (_: Exception) {}

        stopAudioOnly()

        try {
            if (googleTts?.isSpeaking == true) {
                googleTts?.stop()
            }
        } catch (_: Exception) {}

        _isPlaying.value = false
        _isSynthesizing.value = false
        _currentText.value = null
    }

    private fun stopAudioOnly() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    fun destroy() {
        stop()
        try {
            googleTts?.shutdown()
        } catch (_: Exception) {}
        googleTts = null
    }

    private fun escapeXml(input: String): String {
        return input.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun getTimestamp(): String {
        val sdf = java.text.SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss", Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date()) + " GMT+0000 (Coordinated Universal Time)"
    }
}
