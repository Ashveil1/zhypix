package com.example.live

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ArrayBlockingQueue

/**
 * Mic capture (16-bit PCM, 16kHz mono) -> Live session, and Live session
 * audio chunks (24kHz PCM) -> speaker. Supports barge-in: when the user
 * starts speaking over the model, [interruptPlayback] drops queued audio.
 *
 * The caller must hold RECORD_AUDIO permission; [hasRecordPermission] and the
 * [start] return value make a missing permission a no-op, never a crash.
 */
class LiveAudioPipeline(
    private val context: Context,
    private val session: LiveSessionManager,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var recorder: AudioRecord? = null
    private var captureJob: Job? = null

    private var player: AudioTrack? = null
    private var playbackJob: Job? = null
    private val playbackQueue = ArrayBlockingQueue<ByteArray>(PLAYBACK_QUEUE_CAPACITY)

    val isCapturing: Boolean get() = captureJob?.isActive == true

    fun hasRecordPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Start mic capture. Returns false when permission is missing. */
    fun start(): Boolean {
        if (isCapturing) return true
        if (!hasRecordPermission()) return false

        val minBuf = AudioRecord.getMinBufferSize(CAPTURE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                CAPTURE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
        } catch (e: Exception) {
            return false
        }

        recorder = rec
        rec.startRecording()
        captureJob = scope.launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ByteArray(CAPTURE_CHUNK_BYTES)
            while (isActive) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    session.sendAudioChunk(if (n == buf.size) buf else buf.copyOf(n))
                }
            }
        }
        startPlaybackLoop()
        return true
    }

    private fun startPlaybackLoop() {
        if (playbackJob?.isActive == true) return
        val minBuf = AudioTrack.getMinBufferSize(PLAYBACK_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(PLAYBACK_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            return
        }

        player = track
        track.play()
        playbackJob = scope.launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (isActive) {
                val chunk = playbackQueue.poll()
                if (chunk != null) {
                    track.write(chunk, 0, chunk.size)
                } else {
                    kotlinx.coroutines.delay(10)
                }
            }
        }
    }

    /** Queue a model audio chunk for playback. */
    fun enqueuePlayback(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        if (!playbackQueue.offer(pcm)) {
            // Queue full: drop the oldest chunk to keep latency bounded.
            playbackQueue.poll()
            playbackQueue.offer(pcm)
        }
    }

    /** Barge-in: drop everything queued and start playback fresh. */
    fun interruptPlayback() {
        playbackQueue.clear()
        try {
            player?.pause()
            player?.flush()
            player?.play()
        } catch (e: Exception) { /* player already released */ }
    }

    fun stop() {
        captureJob?.cancel()
        captureJob = null
        try {
            recorder?.stop()
            recorder?.release()
        } catch (e: Exception) { }
        recorder = null

        playbackJob?.cancel()
        playbackJob = null
        playbackQueue.clear()
        try {
            player?.stop()
            player?.release()
        } catch (e: Exception) { }
        player = null
    }

    companion object {
        const val CAPTURE_RATE = 16000
        const val PLAYBACK_RATE = 24000
        const val CAPTURE_CHUNK_BYTES = 4096
        const val PLAYBACK_QUEUE_CAPACITY = 64
    }
}
