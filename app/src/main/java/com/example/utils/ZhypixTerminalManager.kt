package com.example.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns real PTY-backed interactive terminal sessions (Termux TerminalView integration).
 *
 * This is fully independent from [LinuxTerminalSimulator.executeCommand] — the AI/gesture path
 * keeps spawning its own one-shot processes and is untouched by this manager.
 */
object ZhypixTerminalManager {

    private const val TAG = "ZhypixTerminal"

    class PtyTerminal internal constructor(
        val id: String,
        var name: String,
        val distroAlias: String?,
        internal val manager: ZhypixTerminalManager
    ) : TerminalSessionClient {

        lateinit var session: TerminalSession
            internal set

        fun write(data: ByteArray) {
            if (session.isRunning) session.write(data, 0, data.size)
        }

        fun write(text: String) = write(text.toByteArray(Charsets.UTF_8))

        val isRunning: Boolean get() = session.isRunning

        override fun onTextChanged(changedSession: TerminalSession) { manager.onOutputChanged(id) }
        override fun onTitleChanged(changedSession: TerminalSession) { manager.onTitleChanged(id) }
        override fun onSessionFinished(finishedSession: TerminalSession) { manager.onSessionFinished(id) }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) { manager.onClipboardText(text) }
        override fun onPasteTextFromClipboard(session: TerminalSession) { manager.onPasteRequested() }
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) { manager.onOutputChanged(id) }
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE
        override fun logError(tag: String?, message: String?) { Log.e(TAG, "$tag: $message") }
        override fun logWarn(tag: String?, message: String?) { Log.w(TAG, "$tag: $message") }
        override fun logInfo(tag: String?, message: String?) { Log.i(TAG, "$tag: $message") }
        override fun logDebug(tag: String?, message: String?) { Log.d(TAG, "$tag: $message") }
        override fun logVerbose(tag: String?, message: String?) { Log.v(TAG, "$tag: $message") }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(TAG, "$tag: $message", e) }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.e(TAG, tag, e) }
    }

    interface Listener {
        fun onOutputChanged(sessionId: String)
        fun onTitleOrCwdChanged(sessionId: String)
        fun onSessionFinished(sessionId: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val idCounter = AtomicLong(0)

    private val _terminals = MutableStateFlow<List<PtyTerminal>>(emptyList())
    val terminals: StateFlow<List<PtyTerminal>> = _terminals.asStateFlow()

    private val _activeTerminalId = MutableStateFlow<String?>(null)
    val activeTerminalId: StateFlow<String?> = _activeTerminalId.asStateFlow()

    /** Host-side cwd of the active PTY process (from /proc/<pid>/cwd), refreshed periodically. */
    private val _activeHostCwd = MutableStateFlow<String?>(null)
    val activeHostCwd: StateFlow<String?> = _activeHostCwd.asStateFlow()

    @Volatile
    var listener: Listener? = null

    @Volatile
    var clipboardTextHandler: ((String) -> Unit)? = null

    @Volatile
    var pasteRequestHandler: (() -> Unit)? = null

    fun createSession(context: Context, name: String, distroAlias: String?): PtyTerminal? {
        val spec = LinuxTerminalSimulator.buildPtyLaunchSpec(distroAlias)
        if (spec == null) {
            Log.e(TAG, "Failed to build PTY launch spec (distro=$distroAlias)")
            return null
        }
        val id = "pty-" + idCounter.incrementAndGet()
        val terminal = PtyTerminal(id, name, spec.distroAlias, this)
        terminal.session = TerminalSession(
            spec.executablePath,
            spec.cwd,
            spec.args.toTypedArray(),
            spec.env.toTypedArray(),
            null,
            terminal
        )
        _terminals.value = _terminals.value + terminal
        _activeTerminalId.value = id
        refreshActiveCwd()
        return terminal
    }

    fun onOutputChanged(sessionId: String) { listener?.onOutputChanged(sessionId) }
    fun onTitleChanged(sessionId: String) {
        refreshActiveCwd()
        listener?.onTitleOrCwdChanged(sessionId)
    }
    fun onSessionFinished(sessionId: String) { listener?.onSessionFinished(sessionId) }
    fun onClipboardText(text: String) { clipboardTextHandler?.invoke(text) }
    fun onPasteRequested() { pasteRequestHandler?.invoke() }

    fun switchTo(sessionId: String) {
        if (_terminals.value.any { it.id == sessionId }) {
            _activeTerminalId.value = sessionId
            refreshActiveCwd()
        }
    }

    fun removeSession(context: Context, sessionId: String) {
        val target = _terminals.value.find { it.id == sessionId } ?: return
        try {
            target.session.finishIfRunning()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to finish session", e)
        }
        _terminals.value = _terminals.value.filterNot { it.id == sessionId }
        if (_activeTerminalId.value == sessionId) {
            _activeTerminalId.value = _terminals.value.firstOrNull()?.id
        }
        refreshActiveCwd()
    }

    fun restartSession(context: Context, sessionId: String): PtyTerminal? {
        val old = _terminals.value.find { it.id == sessionId } ?: return null
        val fresh = createSession(context, old.name, old.distroAlias) ?: return null
        removeSession(context, sessionId)
        switchTo(fresh.id)
        return fresh
    }

    fun refreshActiveCwd() {
        val active = _terminals.value.find { it.id == _activeTerminalId.value } ?: run {
            _activeHostCwd.value = null
            return
        }
        _activeHostCwd.value = try {
            active.session.getCwd()
        } catch (e: Exception) {
            null
        }
    }

    @Volatile
    private var cwdPollingStarted = false

    fun startCwdPolling() {
        if (cwdPollingStarted) return
        cwdPollingStarted = true
        scope.launch {
            while (true) {
                delay(4000)
                refreshActiveCwd()
            }
        }
    }
}
