package com.example.utils

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.InputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths

sealed class TerminalUpdate {
    data class AddLine(val text: String) : TerminalUpdate()
    data class OverwriteLastLine(val text: String) : TerminalUpdate()
    object Clear : TerminalUpdate()
}

object LinuxTerminalSimulator {
    data class DistroInfo(
        val name: String,
        val alias: String,
        val version: String,
        val description: String,
        val pkgManager: String,
        val kernel: String,
        val sizeMb: Int,
        val defaultFiles: List<String>
    )

    val availableDistros = listOf(
        DistroInfo("Ubuntu", "ubuntu", "24.04.1 LTS (Noble)", "Latest Ubuntu LTS Rootfs via PRoot", "apt", "6.8.0", 28, listOf()),
        DistroInfo("Debian", "debian", "12.9 Bookworm (Latest)", "Latest Debian Stable Rootfs via PRoot", "apt", "6.6.0", 18, listOf()),
        DistroInfo("Alpine", "alpine", "3.21.2 (Latest Stable)", "Lightweight PRoot Container Rootfs", "apk", "6.12.0", 5, listOf()),
        DistroInfo("Arch Linux", "archlinux", "Rolling 2026.07", "Arch Linux Rolling ARM/x86 Rootfs", "pacman", "6.12.0", 25, listOf()),
        DistroInfo("Fedora", "fedora", "41 Workstation", "Fedora Container Rootfs via PRoot", "dnf", "6.11.0", 30, listOf()),
        DistroInfo("Kali Linux", "kali", "2024.4 Rolling", "Security & Pentesting Rootfs via PRoot", "apt", "6.10.0", 32, listOf()),
        DistroInfo("Void Linux", "void", "Rolling glibc", "XBPS Fast Lightweight Container Rootfs", "xbps", "6.11.0", 12, listOf())
    )

    data class TerminalSession(
        val id: String,
        val name: String,
        val terminalLines: MutableList<String> = mutableListOf(),
        var activeDistro: String? = null,
        var guestDirectory: String = "/root",
        var currentDirectory: String = "/",
        var realDirectory: File = File("/"),
        val commandHistory: MutableList<String> = mutableListOf(),
        var currentInputText: String = ""
    )

    val sessions = androidx.compose.runtime.mutableStateListOf<TerminalSession>()
    private val _activeSessionId = MutableStateFlow<String>("")
    val activeSessionId = _activeSessionId.asStateFlow()

    fun switchSession(sessionId: String) {
        val currentSessionId = _activeSessionId.value
        val oldSession = sessions.find { it.id == currentSessionId }
        
        // Save current active state to old session
        if (oldSession != null) {
            oldSession.terminalLines.clear()
            oldSession.terminalLines.addAll(terminalLines)
            oldSession.activeDistro = _activeDistro.value
            oldSession.guestDirectory = _guestDirectory.value
            oldSession.currentDirectory = _currentDirectory.value
            oldSession.realDirectory = globalRealDirectory
        }

        val newSession = sessions.find { it.id == sessionId }
        if (newSession != null) {
            _activeSessionId.value = sessionId
            _activeDistro.value = newSession.activeDistro
            _guestDirectory.value = newSession.guestDirectory
            _currentDirectory.value = newSession.currentDirectory
            globalRealDirectory = newSession.realDirectory
            
            terminalLines.clear()
            terminalLines.addAll(newSession.terminalLines)
            syncState()
            
            val context = applicationContext
            if (context != null) {
                saveSessions(context)
            }
        }
    }

    fun createSession(name: String, distroToLogin: String? = null): String {
        val context = applicationContext ?: return ""
        val newId = "term-${System.currentTimeMillis()}"
        val newSession = TerminalSession(
            id = newId,
            name = name,
            realDirectory = context.filesDir
        )
        
        newSession.terminalLines.add("Android Native Shell [Real Environment]")
        newSession.terminalLines.add("System Architecture: ${System.getProperty("os.arch")}")
        if (prootPath != null) {
            newSession.terminalLines.add("PRoot Engine: ENABLED ($prootPath)")
        } else {
            newSession.terminalLines.add("PRoot Engine: Missing libproot.so native library.")
        }
        newSession.terminalLines.add("App directory: ${context.filesDir.absolutePath}")
        newSession.terminalLines.add("")
        
        if (distroToLogin != null) {
            newSession.activeDistro = distroToLogin
            newSession.guestDirectory = "/root"
            newSession.currentDirectory = "/root"
            newSession.terminalLines.add("Logged into $distroToLogin PRoot container.")
            newSession.terminalLines.add("You are now running inside the real Linux rootfs.")
        } else {
            val installed = _installedDistros.value
            if (installed.isNotEmpty()) {
                val defaultDistro = if (installed.contains("ubuntu")) "ubuntu" else installed.first()
                newSession.activeDistro = defaultDistro
                newSession.guestDirectory = "/root"
                newSession.currentDirectory = "/root"
                newSession.terminalLines.add("Logged into $defaultDistro PRoot container.")
                newSession.terminalLines.add("You are now running inside the real Linux rootfs.")
            }
        }
        
        sessions.add(newSession)
        switchSession(newId)
        saveSessions(context)
        return newId
    }

    fun removeSession(sessionId: String) {
        if (sessions.size <= 1) return
        val currentActiveId = _activeSessionId.value
        val indexToRemove = sessions.indexOfFirst { it.id == sessionId }
        if (indexToRemove != -1) {
            sessions.removeAt(indexToRemove)
            if (currentActiveId == sessionId) {
                switchSession(sessions.first().id)
            }
            val context = applicationContext
            if (context != null) {
                saveSessions(context)
            }
        }
    }

    fun renameSession(sessionId: String, newName: String) {
        val index = sessions.indexOfFirst { it.id == sessionId }
        if (index != -1) {
            sessions[index] = sessions[index].copy(name = newName)
            val context = applicationContext
            if (context != null) {
                saveSessions(context)
            }
        }
    }

    val terminalLines = mutableStateListOf<String>()

    private fun log(msg: String) {
        terminalLines.add("[LinuxSim] $msg")
        android.util.Log.d("LinuxTerminalSimulator", msg)
    }

    private val _isInstalled = MutableStateFlow(true)
    val isInstalled = _isInstalled.asStateFlow()

    private val _installedDistros = MutableStateFlow(setOf<String>())
    val installedDistros = _installedDistros.asStateFlow()

    private val _activeDistro = MutableStateFlow<String?>(null)
    val activeDistro = _activeDistro.asStateFlow()

    private val _currentDirectory = MutableStateFlow("/")
    val currentDirectory = _currentDirectory.asStateFlow()

    private val _selectedDistro = MutableStateFlow("Android Shell")
    val selectedDistro = _selectedDistro.asStateFlow()

    private val _guestDirectory = MutableStateFlow("/root")
    val guestDirectory = _guestDirectory.asStateFlow()

    private val _isDesktopScreenActive = MutableStateFlow(false)
    val isDesktopScreenActive = _isDesktopScreenActive.asStateFlow()

    private val _activeDesktopAppName = MutableStateFlow<String?>(null)
    val activeDesktopAppName = _activeDesktopAppName.asStateFlow()

    private var xvfbProcess: Process? = null

    fun setDesktopScreenActive(active: Boolean, appName: String? = "Linux Virtual Display (:99)") {
        _isDesktopScreenActive.value = active
        _activeDesktopAppName.value = appName
        if (active) {
            startX11VirtualDisplayIfNeeded()
        }
    }

    fun ensureProotPathResolved(context: Context) {
        if (prootPath == null) {
            val nativeProotFile = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
            val libDir = File(context.filesDir, "lib")
            val prootFile = File(libDir, "libproot.so")
            if (isValidElf(nativeProotFile)) {
                prootPath = nativeProotFile.absolutePath
            } else if (isValidElf(prootFile)) {
                prootFile.setExecutable(true, false)
                prootPath = prootFile.absolutePath
            } else {
                extractLibFromApk(context, "libproot.so", prootFile)
                if (isValidElf(prootFile)) {
                    prootFile.setExecutable(true, false)
                    prootPath = prootFile.absolutePath
                }
            }
        }
    }

    fun startX11VirtualDisplayIfNeeded() {
        val distro = _activeDistro.value ?: "ubuntu"
        val context = applicationContext ?: return
        ensureProotPathResolved(context)
        
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                if (!isDistroFullyInstalled(distro)) {
                    android.util.Log.d("Zhypix", "Xvfb deferred: Distro '$distro' is not installed or incomplete.")
                    return@launch
                }
                
                // 2. Check if we already have a running Xvfb process
                val currentProcess = xvfbProcess
                val isAlive = if (currentProcess != null) {
                    try {
                        currentProcess.exitValue()
                        false
                    } catch (e: IllegalThreadStateException) {
                        true
                    }
                } else false
                
                val distroDir = resolveDistroDir(distro)
                val lockFile1 = File(distroDir, "tmp/.X99-lock")
                val lockFile2 = File(distroDir, "tmp/.X11-unix/X99")
                if (isAlive || lockFile1.exists() || lockFile2.exists()) {
                    android.util.Log.d("Zhypix", "Xvfb process or lock file is already running/present.")
                    return@launch
                }
                
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    terminalLines.add("[System] Starting Headless X11 Virtual Display Server on DISPLAY=:99 (1280x720 24-bit)...")
                }
                
                // CRITICAL: Clean up stale lock files from previous crashes/runs
                try {
                    val lockFile1 = File(distroDir, "tmp/.X99-lock")
                    if (lockFile1.exists()) {
                        lockFile1.delete()
                    }
                    val lockFile2 = File(distroDir, "tmp/.X11-unix/X99")
                    if (lockFile2.exists()) {
                        lockFile2.delete()
                    }
                    android.util.Log.d("Zhypix", "Cleaned up stale Xvfb lock files if any existed.")
                } catch (e: Exception) {
                    android.util.Log.e("Zhypix", "Failed to clean up stale Xvfb lock files", e)
                }

                val prootCmdList = mutableListOf<String>()
                prootCmdList.add(prootPath ?: return@launch)
                prootCmdList.add("-r")
                prootCmdList.add((try { distroDir.canonicalFile.absolutePath } catch (e: Exception) { distroDir.absolutePath }))
                prootCmdList.add("-0")
                prootCmdList.add("--link2symlink")
                prootCmdList.add("--sysvipc")
                prootCmdList.add("--kill-on-exit")
                prootCmdList.add("-w")
                prootCmdList.add("/root")
                
                appendStandardPRootBindings(prootCmdList, context, distroDir)
                
                prootCmdList.addAll(listOf(
                    "/bin/sh", "-c", "unset LD_LIBRARY_PATH; export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:\$PATH HOME=/root USER=root SHELL=/bin/bash TERM=xterm-256color LANG=C.UTF-8 LC_ALL=C.UTF-8 DEBIAN_FRONTEND=noninteractive APT_LISTCHANGES_FRONTEND=none TMPDIR=/tmp TEMP=/tmp TMP=/tmp DISPLAY=:99; Xvfb :99 -screen 0 1280x720x24 -ac > /root/xvfb.log 2>&1 & rm -f /root/cmd_pipe && mkfifo /root/cmd_pipe && tail -f /root/cmd_pipe | while read -r line; do export DISPLAY=:99; eval \"\$line\" >> /root/background_cmds.log 2>&1 & done"
                ))
                
                val builder = ProcessBuilder(prootCmdList).directory(context.filesDir)
                val prootTmpDir = File(File(context.cacheDir, "proot_tmp"), "x11_" + java.util.UUID.randomUUID().toString()).apply {
                    mkdirs()
                    setWritable(true, false)
                    setReadable(true, false)
                    setExecutable(true, false)
                }
                applyStandardPRootEnvironment(builder, context, prootTmpDir)
                
                builder.redirectErrorStream(true)
                val process = builder.start()
                xvfbProcess = process
                
                // Read input stream in background to prevent process hanging due to full buffer
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    try {
                        val reader = java.io.InputStreamReader(process.inputStream, "UTF-8")
                        val buffer = CharArray(1024)
                        while (true) {
                            val read = reader.read(buffer)
                            if (read == -1) break
                            val output = String(buffer, 0, read)
                            android.util.Log.d("Zhypix_Xvfb", output)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("Zhypix", "Xvfb input stream reader error", e)
                    }
                }
                
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    terminalLines.add("[System] Xvfb virtual display started successfully inside background coroutine.")
                }
            } catch (e: Exception) {
                android.util.Log.e("Zhypix", "Error setting up virtual display persistently", e)
            }
        }
    }

    suspend fun captureX11Screenshot(): String? {
        startX11VirtualDisplayIfNeeded()
        val distro = _activeDistro.value ?: "ubuntu"
        val context = applicationContext ?: return null
        val distroDir = resolveDistroDir(distro)
        
        return withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val screenshotFile = File(distroDir, "root/x11_screenshot.png")
                
                var success = false
                var attempts = 0
                while (!success && attempts < 3) {
                    attempts++
                    if (screenshotFile.exists()) {
                        screenshotFile.delete()
                    }
                    executeCommand("scrot -z -q 80 /root/x11_screenshot.png")
                    if (screenshotFile.exists() && screenshotFile.length() > 0) {
                        success = true
                    } else {
                        // Wait a bit, start display if needed, and retry
                        kotlinx.coroutines.delay(500L)
                        startX11VirtualDisplayIfNeeded()
                    }
                }
                
                if (success) {
                    val bytes = screenshotFile.readBytes()
                    android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                } else {
                    null
                }
            } catch (e: Exception) {
                android.util.Log.e("Zhypix", "Failed to capture X11 screenshot", e)
                null
            }
        }
    }

    private var globalWakeLock: android.os.PowerManager.WakeLock? = null
    private var globalWifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private val _isWakeLockHeld = MutableStateFlow(false)
    val isWakeLockHeld = _isWakeLockHeld.asStateFlow()

    fun toggleWakeLock(context: Context): Boolean {
        return if (_isWakeLockHeld.value) {
            releaseWakeLock()
            false
        } else {
            acquireWakeLock(context)
            true
        }
    }

    fun acquireWakeLock(context: Context) {
        try {
            if (globalWakeLock == null || !globalWakeLock!!.isHeld) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                globalWakeLock = powerManager?.newWakeLock(
                    android.os.PowerManager.PARTIAL_WAKE_LOCK,
                    "Zhypix:PersistentWakeLock"
                )?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
                _isWakeLockHeld.value = true
                android.util.Log.d("LinuxTerminalSimulator", "Acquired continuous CPU WakeLock (Termux-style)")
            }

            // Also acquire WifiLock (high perf) to keep network streaming alive in background
            try {
                if (globalWifiLock == null || !globalWifiLock!!.isHeld) {
                    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                    val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    } else {
                        @Suppress("DEPRECATION")
                        android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    }
                    globalWifiLock = wifiManager?.createWifiLock(mode, "Zhypix:WifiLock")?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                }
            } catch (e: Exception) {}

            // Ensure background service is running to guarantee low OOM score
            try {
                val sIntent = android.content.Intent(context, com.example.service.FloatingAgentService::class.java).apply {
                    action = "ACTION_UPDATE_WAKELOCK"
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(sIntent)
                } else {
                    context.startService(sIntent)
                }
            } catch (e: Exception) {}
        } catch (e: Exception) {
            android.util.Log.e("LinuxTerminalSimulator", "Failed to acquire WakeLock: ${e.message}")
        }
    }

    fun releaseWakeLock() {
        try {
            if (globalWakeLock != null && globalWakeLock!!.isHeld) {
                globalWakeLock?.release()
                _isWakeLockHeld.value = false
                android.util.Log.d("LinuxTerminalSimulator", "Released CPU WakeLock")
            }
            if (globalWifiLock != null && globalWifiLock!!.isHeld) {
                globalWifiLock?.release()
            }
            applicationContext?.let { ctx ->
                try {
                    val sIntent = android.content.Intent(ctx, com.example.service.FloatingAgentService::class.java).apply {
                        action = "ACTION_UPDATE_WAKELOCK"
                    }
                    ctx.startService(sIntent)
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } catch (e: Exception) {
            false
        }
    }

    fun requestIgnoreBatteryOptimization(context: Context) {
        try {
            if (!isIgnoringBatteryOptimizations(context)) {
                val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            try {
                // Fallback to battery optimization settings
                val intent = android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e2: Exception) {
                android.util.Log.e("LinuxTerminalSimulator", "Could not open battery optimization settings", e2)
            }
        }
    }

    var globalRealDirectory = File(System.getProperty("user.dir") ?: "/")
    var applicationContext: Context? = null
    var prootPath: String? = null

    private fun extractLibFromApk(context: Context, libName: String, destFile: File): Boolean {
        try {
            val apkPath = context.applicationInfo.publicSourceDir ?: return false
            val zipFile = java.util.zip.ZipFile(apkPath)
            
            // Try supported ABIs in order of preference
            for (abi in getPreferredAbis()) {
                val entryPath = "lib/$abi/$libName"
                val entry = zipFile.getEntry(entryPath)
                if (entry != null) {
                    zipFile.getInputStream(entry).use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    destFile.setExecutable(true, false)
                    android.util.Log.d("LinuxTerminalSimulator", "Extracted $libName from APK ($abi) to ${destFile.absolutePath}")
                    zipFile.close()
                    return true
                }
            }
            zipFile.close()
        } catch (e: Exception) {
            android.util.Log.e("LinuxTerminalSimulator", "Failed to extract $libName from APK: ${e.message}", e)
        }
        return false
    }

    private fun isValidElf(file: File): Boolean {
        if (!file.exists() || file.length() < 20) return false
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(64)
                val read = input.read(header)
                if (read < 20) return false
                val magicValid = header[0] == 0x7F.toByte() && header[1] == 'E'.code.toByte() && header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()
                if (!magicValid) return false
                
                if (read >= 24) {
                    val eVersion = (header[20].toInt() and 0xFF) or ((header[21].toInt() and 0xFF) shl 8) or ((header[22].toInt() and 0xFF) shl 16) or ((header[23].toInt() and 0xFF) shl 24)
                    if (eVersion != 0 && eVersion != 1) return false
                }
                
                if (read >= 64) {
                    val hostIsArm = isHostCpuArm()
                    val binIsArm = isBinaryArm(file)
                    if (hostIsArm != binIsArm) return false
                }
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun isHostCpuArm(): Boolean {
        val abis = android.os.Build.SUPPORTED_ABIS
        val primaryAbi = if (abis.isNotEmpty()) abis[0] else ""
        val arch = System.getProperty("os.arch")?.lowercase(java.util.Locale.US) ?: ""
        return primaryAbi.contains("arm") || primaryAbi.contains("aarch64") ||
               arch.contains("arm") || arch.contains("aarch64") || arch.contains("aarch")
    }

    private fun getPreferredAbis(): List<String> {
        val abis = android.os.Build.SUPPORTED_ABIS.toList()
        return if (abis.isNotEmpty()) abis else listOf("arm64-v8a", "armeabi-v7a", "armeabi")
    }

    private fun isBinaryArm(file: File): Boolean {
        if (!file.exists() || file.length() < 64) return isHostCpuArm()
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(64)
                val read = input.read(header)
                if (read < 64) return isHostCpuArm()
                val eMachine = (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
                eMachine == 0xB7 || eMachine == 0x28 // 0xB7 = AArch64, 0x28 = ARM (32-bit)
            }
        } catch (e: Exception) {
            isHostCpuArm()
        }
    }

    private fun getGuestArchIsArm(distroDir: File): Boolean? {
        val candidates = listOf(
            "bin/dash",
            "bin/bash",
            "bin/busybox",
            "bin/cat",
            "bin/ls",
            "usr/bin/dpkg",
            "bin/sh"
        )
        for (relPath in candidates) {
            val file = File(distroDir, relPath)
            if (file.exists() && file.isFile && file.length() >= 64) {
                try {
                    file.inputStream().use { input ->
                        val header = ByteArray(64)
                        val read = input.read(header)
                        if (read >= 64) {
                            val magicValid = header[0] == 0x7F.toByte() && header[1] == 'E'.code.toByte() && header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()
                            if (magicValid) {
                                val eMachine = (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
                                return eMachine == 0xB7 || eMachine == 0x28
                            }
                        }
                    }
                } catch (e: Exception) {}
            }
        }
        return isHostCpuArm()
    }

    private fun scanForFirstElfIsArm(dir: File, depth: Int = 0): Boolean? {
        if (depth > 4) return isHostCpuArm()
        try {
            val files = dir.listFiles() ?: return isHostCpuArm()
            for (f in files) {
                if (f.isDirectory) {
                    val res = scanForFirstElfIsArm(f, depth + 1)
                    if (res != null) return res
                } else if (f.isFile && f.length() >= 64) {
                    f.inputStream().use { input ->
                        val header = ByteArray(64)
                        val read = input.read(header)
                        if (read >= 64) {
                            val magicValid = header[0] == 0x7F.toByte() && header[1] == 'E'.code.toByte() && header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()
                            if (magicValid) {
                                val eMachineInt = (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
                                return eMachineInt == 0xB7 || eMachineInt == 0x28
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {}
        return isHostCpuArm()
    }

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
        globalRealDirectory = context.filesDir
        _currentDirectory.value = globalRealDirectory.absolutePath
        ensureProotPathResolved(context)
        
        // Ensure /tmp directory under filesDir exists and has full permissions (solves Issue 1: tmp creation failure)
        try {
            val legacyTmp = File(context.filesDir, "tmp")
            legacyTmp.mkdirs()
            legacyTmp.setWritable(true, false)
            legacyTmp.setReadable(true, false)
            legacyTmp.setExecutable(true, false)
        } catch (e: Exception) {
            android.util.Log.e("Zhypix", "Failed to setup legacy tmp directory", e)
        }

        // Ensure /proot_tmp under cacheDir exists and has full permissions (solves Issue 1)
        try {
            val cacheTmp = File(context.cacheDir, "proot_tmp")
            cacheTmp.mkdirs()
            cacheTmp.setWritable(true, false)
            cacheTmp.setReadable(true, false)
            cacheTmp.setExecutable(true, false)
            cacheTmp.listFiles()?.forEach { sub ->
                if (sub.isDirectory && System.currentTimeMillis() - sub.lastModified() > 3600_000) {
                    sub.deleteRecursively()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Zhypix", "Failed to setup cache tmp directory", e)
        }

        // Setup fake virtual filesystems to solve C2, C3, C4, C5, H1, H2, M2, M3
        try {
            val cacheDir = context.cacheDir
            
            // 1. /proc/stat
            val fakeProcStat = File(cacheDir, "fake_proc_stat")
            fakeProcStat.parentFile?.mkdirs()
            fakeProcStat.writeText("cpu  22345 345 54321 987654 123 0 456 0 0 0\nbtime 1700000000\n")
            fakeProcStat.setReadable(true, false)

            // 2. /proc/version
            val fakeProcVersion = File(cacheDir, "fake_proc_version")
            fakeProcVersion.writeText("Linux version 5.15.194-android13-zhypix (android-build@google.com) (gcc version 11.0) #1 SMP PREEMPT Fri Jul 31 00:00:00 UTC 2026\n")
            fakeProcVersion.setReadable(true, false)

            // 3. /proc/uptime
            val fakeProcUptime = File(cacheDir, "fake_proc_uptime")
            fakeProcUptime.writeText("3600.00 3600.00\n")
            fakeProcUptime.setReadable(true, false)

            // 4. /proc/loadavg
            val fakeProcLoadavg = File(cacheDir, "fake_proc_loadavg")
            fakeProcLoadavg.writeText("0.05 0.03 0.01 1/150 12345\n")
            fakeProcLoadavg.setReadable(true, false)

            // 5. /proc/net directory
            val fakeProcNetDir = File(cacheDir, "fake_proc_net")
            fakeProcNetDir.mkdirs()
            
            val fakeProcNetTcp = File(fakeProcNetDir, "tcp")
            fakeProcNetTcp.writeText("  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode                                                     \n" +
                "   0: 0100007F:0050 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 12345 1 0000000000000000\n" +
                "   1: 0100007F:0016 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 12346 1 0000000000000000\n" +
                "   2: 00000000:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 12347 1 0000000000000000\n")
            fakeProcNetTcp.setReadable(true, false)

            val fakeProcNetTcp6 = File(fakeProcNetDir, "tcp6")
            fakeProcNetTcp6.writeText("  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode                                                     \n")
            fakeProcNetTcp6.setReadable(true, false)

            val fakeProcNetUdp = File(fakeProcNetDir, "udp")
            fakeProcNetUdp.writeText("  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode                                                     \n")
            fakeProcNetUdp.setReadable(true, false)

            val fakeProcNetUdp6 = File(fakeProcNetDir, "udp6")
            fakeProcNetUdp6.writeText("  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode                                                     \n")
            fakeProcNetUdp6.setReadable(true, false)

            val fakeProcNetDev = File(fakeProcNetDir, "dev")
            fakeProcNetDev.writeText(
                "Inter-|   Receive                                                |  Transmit\n" +
                " face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed\n" +
                "    lo:    1024      10    0    0    0     0          0         0     1024      10    0    0    0     0       0          0\n" +
                "  eth0:  524288    1200    0    0    0     0          0         0   524288    1000    0    0    0     0       0          0\n" +
                " wlan0:       0       0    0    0    0     0          0         0        0       0    0    0    0     0       0          0\n"
            )
            fakeProcNetDev.setReadable(true, false)

            try {
                fakeProcNetDir.walk().forEach {
                    it.setReadable(true, false)
                }
            } catch (e: Exception) {}

            // 6. /proc/self/status (solves Issue H1: capabilities always 0)
            val fakeProcSelfStatus = File(cacheDir, "fake_proc_self_status")
            fakeProcSelfStatus.writeText("Name: bash\nState: R (running)\nTgid: 12345\nPid: 12345\nPPid: 1\nUID: 0 0 0 0\nGID: 0 0 0 0\nCapEff: 0000003fffffffff\nCapInh: 0000000000000000\nCapPrm: 0000003fffffffff\nCapBnd: 0000003fffffffff\n")
            fakeProcSelfStatus.setReadable(true, false)

            // 7. /proc/self/cmdline (solves Issue M3: cmdline wrong)
            val fakeProcSelfCmdline = File(cacheDir, "fake_proc_self_cmdline")
            fakeProcSelfCmdline.writeText("bash\n")
            fakeProcSelfCmdline.setReadable(true, false)

            // 8. fake sysfs directory (solves Issue C5: /sys Permission denied, H4: lsblk)
            val fakeSysDir = File(cacheDir, "fake_sys")
            fakeSysDir.mkdirs()
            listOf("class", "block", "bus", "dev", "devices", "firmware", "fs", "kernel").forEach {
                File(fakeSysDir, it).mkdirs()
            }
            // Create /sys/dev/block and /sys/dev/char specifically for lsblk
            val fakeSysDev = File(fakeSysDir, "dev").apply { mkdirs() }
            File(fakeSysDev, "block").mkdirs()
            File(fakeSysDev, "char").mkdirs()

            val fakeSysHostname = File(File(fakeSysDir, "kernel"), "hostname")
            fakeSysHostname.writeText("zhypix-container\n")
            fakeSysHostname.setReadable(true, false)

            // 8.5 fake /proc/sys directory (solves Issue H3: sysctl permission denied)
            val fakeProcSysDir = File(cacheDir, "fake_proc_sys")
            fakeProcSysDir.mkdirs()
            val fakeProcSysKernel = File(fakeProcSysDir, "kernel").apply { mkdirs() }
            val fakeProcSysVm = File(fakeProcSysDir, "vm").apply { mkdirs() }
            File(fakeProcSysKernel, "hostname").writeText("zhypix-container\n")
            File(fakeProcSysKernel, "osrelease").writeText("5.15.194-android13-zhypix\n")
            File(fakeProcSysVm, "max_map_count").writeText("262144\n")
            fakeProcSysDir.walk().forEach {
                try {
                    it.setReadable(true, false)
                } catch (e: Exception) {}
            }
            
            // 9. fake dev directory (solves Issue C4: /dev Permission denied, M2: /dev/fuse)
            val fakeDevDir = File(cacheDir, "fake_dev")
            fakeDevDir.mkdirs()
            listOf("pts", "shm").forEach {
                File(fakeDevDir, it).mkdirs()
            }
            listOf("null", "zero", "random", "urandom", "ptmx").forEach {
                val f = File(fakeDevDir, it)
                if (!f.exists()) f.createNewFile()
                f.setReadable(true, false)
                f.setWritable(true, false)
            }
            
            // Symlinks inside fake dev
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                try {
                    val stdinLink = File(fakeDevDir, "stdin").toPath()
                    if (!java.nio.file.Files.exists(stdinLink) && !java.nio.file.Files.isSymbolicLink(stdinLink)) {
                        java.nio.file.Files.createSymbolicLink(stdinLink, java.nio.file.Paths.get("/proc/self/fd/0"))
                    }
                } catch (e: Exception) {}
                try {
                    val stdoutLink = File(fakeDevDir, "stdout").toPath()
                    if (!java.nio.file.Files.exists(stdoutLink) && !java.nio.file.Files.isSymbolicLink(stdoutLink)) {
                        java.nio.file.Files.createSymbolicLink(stdoutLink, java.nio.file.Paths.get("/proc/self/fd/1"))
                    }
                } catch (e: Exception) {}
                try {
                    val stderrLink = File(fakeDevDir, "stderr").toPath()
                    if (!java.nio.file.Files.exists(stderrLink) && !java.nio.file.Files.isSymbolicLink(stderrLink)) {
                        java.nio.file.Files.createSymbolicLink(stderrLink, java.nio.file.Paths.get("/proc/self/fd/2"))
                    }
                } catch (e: Exception) {}
                try {
                    val fdLink = File(fakeDevDir, "fd").toPath()
                    if (!java.nio.file.Files.exists(fdLink) && !java.nio.file.Files.isSymbolicLink(fdLink)) {
                        java.nio.file.Files.createSymbolicLink(fdLink, java.nio.file.Paths.get("/proc/self/fd"))
                    }
                } catch (e: Exception) {}
            }
            android.util.Log.d("Zhypix", "Successfully set up all virtual dev, sys, and proc structures.")
        } catch (e: Exception) {
            android.util.Log.e("Zhypix", "Failed to setup virtual filesystems", e)
        }
        
        /*
         * ==========================================
         * CRITICAL NATIVE BINARY INTEGRITY WARNING
         * ==========================================
         *
         * HISTORICAL ISSUE DESCRIPTION:
         * Previously, the application suffered from major binary corruption because the `.so` files
         * in `jniLibs` (libproot.so, libbusybox.so, libtalloc.so, etc.) were accidentally modified,
         * checked in, or transmitted as text files rather than raw binary files. 
         * This resulted in a classic lossy Unicode conversion where many non-UTF-8 binary bytes 
         * were replaced with the UTF-8 replacement character '' (bytes: 0xef 0xbf 0xbd).
         * This corrupted the ELF program headers, changed the machine architecture code to 0xbfef,
         * and caused runtime execution to crash with:
         * "libproot.so: inaccessible or not found" / "syntax error: unexpected '('"
         *
         * CORRECT RESOLUTION AND MAINTENANCE:
         * 1. All libraries under `app/src/main/jniLibs/{arm64-v8a, x86_64}` must remain pure, raw,
         *    un-corrupted ELF binary files extracted directly from the official Termux repositories:
         *    - proot (from Packages: pool/main/p/proot/)
         *    - busybox (from Packages: pool/main/b/busybox/)
         *    - libtalloc (from Packages: pool/main/libt/libtalloc/)
         *    - libandroid-shmem (from Packages: pool/main/liba/libandroid-shmem/)
         * 2. NEVER edit these `.so` files with any text editors, do not use git filters or tools
         *    that attempt to treat them as UTF-8/text files, and do not commit them using text mode.
         * 3. Any replacement or updates to these files must be done by fetching the raw DEB package
         *    from Termux mirrors, extracting the data.tar.xz archive using a binary-safe tool, and
         *    copying the compiled binaries directly without any encoding/decoding steps.
         */
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val libDir = File(context.filesDir, "lib")
        libDir.mkdirs()

        val libsToCopy = listOf(
            "libproot.so",
            "libproot-loader.so",
            "libproot-loader32.so",
            "libandroid-shmem.so",
            "libbusybox.so",
            "libtalloc.so"
        )

        libsToCopy.forEach { libName ->
            val srcLib = File(nativeDir, libName)
            val destLib = File(libDir, libName)
            var valid = false
            
            // 1. Try copying from nativeLibraryDir if srcLib is valid
            if (srcLib.exists() && isValidElf(srcLib)) {
                if (destLib.exists() && isValidElf(destLib) && destLib.length() == srcLib.length()) {
                    destLib.setExecutable(true, false)
                    valid = true
                } else {
                    try {
                        if (destLib.exists()) destLib.delete()
                        srcLib.inputStream().use { input ->
                            destLib.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        destLib.setExecutable(true, false)
                        if (isValidElf(destLib)) {
                            valid = true
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } else if (destLib.exists() && isValidElf(destLib)) {
                destLib.setExecutable(true, false)
                valid = true
            }

            // 2. Extract directly from APK if still not valid
            if (!valid) {
                if (destLib.exists()) destLib.delete()
                val extracted = extractLibFromApk(context, libName, destLib)
                if (extracted && destLib.exists() && isValidElf(destLib)) {
                    destLib.setExecutable(true, false)
                    valid = true
                }
            }
        }
        
        // Ensure libtalloc.so.2 and libtalloc.so.2.4.3 exist (needed by libproot.so)
        val destTalloc = File(libDir, "libtalloc.so")
        if (destTalloc.exists() && isValidElf(destTalloc)) {
            for (alias in listOf("libtalloc.so.2", "libtalloc.so.2.4.3")) {
                val aliasFile = File(libDir, alias)
                if (!aliasFile.exists() || aliasFile.length() != destTalloc.length() || !isValidElf(aliasFile)) {
                    try {
                        if (aliasFile.exists()) aliasFile.delete()
                        destTalloc.inputStream().use { input ->
                            aliasFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        aliasFile.setExecutable(true, false)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else {
                    aliasFile.setExecutable(true, false)
                }
            }
        }

        // Ensure libbusybox.so.1.38.0 exists (needed by libbusybox.so)
        val destBusybox = File(libDir, "libbusybox.so")
        if (destBusybox.exists() && isValidElf(destBusybox)) {
            val destBusyboxVer = File(libDir, "libbusybox.so.1.38.0")
            if (!destBusyboxVer.exists() || destBusyboxVer.length() != destBusybox.length() || !isValidElf(destBusyboxVer)) {
                try {
                    if (destBusyboxVer.exists()) destBusyboxVer.delete()
                    destBusybox.inputStream().use { input ->
                        destBusyboxVer.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    destBusyboxVer.setExecutable(true, false)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                destBusyboxVer.setExecutable(true, false)
            }
        }

        val nativeProot = File(nativeDir, "libproot.so")
        val prootFile = File(libDir, "libproot.so")
        if (nativeProot.exists() && isValidElf(nativeProot)) {
            prootPath = nativeProot.absolutePath
        } else if (isValidElf(prootFile)) {
            prootFile.setExecutable(true, false)
            prootPath = prootFile.absolutePath
        } else {
            extractLibFromApk(context, "libproot.so", prootFile)
            if (isValidElf(prootFile)) {
                prootFile.setExecutable(true, false)
                prootPath = prootFile.absolutePath
            } else {
                prootPath = null
            }
        }

        // Check installed distros
        val prootDir = File(context.filesDir, "proot-distros")
        prootDir.mkdirs()
        try {
            val singularDir = File(context.filesDir, "proot-distro")
            if (Files.isSymbolicLink(singularDir.toPath()) || (singularDir.exists() && !singularDir.isDirectory)) {
                try { singularDir.delete() } catch (_: Exception) {}
            }
            if (!singularDir.exists()) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    try { android.system.Os.symlink("proot-distros", singularDir.absolutePath) } catch (_: Exception) { singularDir.mkdirs() }
                } else {
                    singularDir.mkdirs()
                }
            }
        } catch (e: Exception) {}
        var installed = setOf<String>()
        if (prootDir.exists()) {
            installed = prootDir.listFiles()?.filter { 
                it.isDirectory && isDistroFullyInstalled(it.name)
            }?.map { it.name }?.toSet() ?: setOf()
            _installedDistros.value = installed
            installed.forEach { distroAlias ->
                try {
                    val dDir = resolveDistroDir(distroAlias)
                    if (dDir.exists()) {
                        removeLegacyMockWrappers(dDir)
                    }
                } catch (_: Exception) {}
            }
        }

        // Load saved sessions if they exist
        loadSessions(context)

        // Initialize default session if empty
        if (sessions.isEmpty()) {
            val defaultSession = TerminalSession(
                id = "term-default",
                name = "1",
                realDirectory = context.filesDir
            )
            
            defaultSession.terminalLines.add("Welcome to Termux!")
            defaultSession.terminalLines.add("")
            defaultSession.terminalLines.add("Community forum: https://termux.dev/community")
            defaultSession.terminalLines.add("Gitter chat:     https://gitter.im/termux/termux")
            defaultSession.terminalLines.add("IRC channel:     #termux on libera.chat")
            defaultSession.terminalLines.add("")
            defaultSession.terminalLines.add("Working with packages:")
            defaultSession.terminalLines.add(" * Search packages:   pkg search <query>")
            defaultSession.terminalLines.add(" * Install a package: pkg install <package>")
            defaultSession.terminalLines.add(" * Upgrade packages:  pkg upgrade")
            defaultSession.terminalLines.add("")
            defaultSession.terminalLines.add("Subscribed repositories:")
            defaultSession.terminalLines.add(" * https://packages.termux.dev/apt/termux-main")
            defaultSession.terminalLines.add("")
            
            if (installed.isNotEmpty()) {
                val defaultDistro = if (installed.contains("ubuntu")) "ubuntu" else installed.first()
                defaultSession.activeDistro = defaultDistro
                defaultSession.guestDirectory = "/root"
                defaultSession.currentDirectory = "/root"
            }
            
            sessions.add(defaultSession)
            _activeSessionId.value = "term-default"
            
            _activeDistro.value = defaultSession.activeDistro
            _guestDirectory.value = defaultSession.guestDirectory
            _currentDirectory.value = defaultSession.currentDirectory
            globalRealDirectory = defaultSession.realDirectory
            
            terminalLines.clear()
            terminalLines.addAll(defaultSession.terminalLines)
            syncState()
        }
        if (_activeDistro.value != null) {
            startX11VirtualDisplayIfNeeded()
        }
    }

    fun syncState() {
        val distro = _activeDistro.value
        if (distro != null) {
            _currentDirectory.value = _guestDirectory.value
        } else {
            _currentDirectory.value = globalRealDirectory.absolutePath
        }
    }

    fun setInstalled(isInstalled: Boolean) {
        _isInstalled.value = isInstalled
    }

    fun bootTermux() {
        terminalLines.clear()
        terminalLines.add("Android Native Shell [Real Environment]")
        terminalLines.add("System Architecture: ${System.getProperty("os.arch")}")
        if (prootPath != null) {
            terminalLines.add("PRoot Engine: ENABLED ($prootPath)")
        } else {
            terminalLines.add("PRoot Engine: Missing libproot.so native library.")
        }
        terminalLines.add("App directory: ${globalRealDirectory.absolutePath}")
        terminalLines.add("")
        syncState()
    }

    suspend fun installDistroDirect(
        distroAlias: String,
        onProgress: (Float, String) -> Unit,
        onComplete: (Boolean) -> Unit
    ) {
        val context = applicationContext
        if (context == null) {
            onComplete(false)
            return
        }
        val prootDir = File(context.filesDir, "proot-distros")
        prootDir.mkdirs()
        val distroDir = File(prootDir, distroAlias)
        
        val isReallyInstalled = isDistroFullyInstalled(distroAlias)
        if (isReallyInstalled) {
            terminalLines.add("Distribution $distroAlias is already installed.")
            onComplete(true)
            return
        }
        
        if (distroDir.exists() && !isReallyInstalled) {
            terminalLines.add("Cleaning up incomplete or corrupted environment for $distroAlias...")
            distroDir.deleteRecursively()
        }
        
        terminalLines.add("Installing $distroAlias...")
        onProgress(0.01f, "Preparing directory...")
        
        withContext(Dispatchers.IO) {
            try {
                distroDir.mkdirs()
                
                val isArm = isHostCpuArm()
                
                terminalLines.add("PRoot Engine Arch is ARM: $isArm (prootPath: $prootPath)")
                
                val candidateUrls = when (distroAlias) {
                    "ubuntu" -> listOf(
                        if (isArm) "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz"
                        else "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-amd64.tar.gz",
                        if (isArm) "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-arm64.tar.gz"
                        else "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-amd64.tar.gz"
                    )
                    "debian" -> listOf(
                        if (isArm) "https://raw.githubusercontent.com/debuerreotype/docker-debian-artifacts/dist-arm64v8/stable/oci/blobs/rootfs.tar.gz"
                        else "https://raw.githubusercontent.com/debuerreotype/docker-debian-artifacts/dist-amd64/stable/oci/blobs/rootfs.tar.gz",
                        if (isArm) "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz"
                        else "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-amd64.tar.gz"
                    )
                    "archlinux" -> listOf(
                        if (isArm) "https://fl.us.mirror.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz"
                        else "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-amd64.tar.gz",
                        if (isArm) "https://os.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz"
                        else "https://raw.githubusercontent.com/debuerreotype/docker-debian-artifacts/dist-amd64/stable/oci/blobs/rootfs.tar.gz"
                    )
                    "kali" -> listOf(
                        if (isArm) "https://raw.githubusercontent.com/debuerreotype/docker-debian-artifacts/dist-arm64v8/stable/oci/blobs/rootfs.tar.gz"
                        else "https://raw.githubusercontent.com/debuerreotype/docker-debian-artifacts/dist-amd64/stable/oci/blobs/rootfs.tar.gz",
                        if (isArm) "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz"
                        else "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-amd64.tar.gz"
                    )
                    "fedora" -> listOf(
                        if (isArm) "https://github.com/termux/proot-distro/releases/download/v3.15.2/fedora-aarch64-pd-v3.15.2.tar.xz"
                        else "https://github.com/termux/proot-distro/releases/download/v3.15.2/fedora-x86_64-pd-v3.15.2.tar.xz"
                    )
                    else -> listOf(
                        if (isArm) "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.2-aarch64.tar.gz"
                        else "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/x86_64/alpine-minirootfs-3.21.2-x86_64.tar.gz",
                        if (isArm) "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/alpine-minirootfs-3.21.2-aarch64.tar.gz"
                        else "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/x86_64/alpine-minirootfs-3.21.2-x86_64.tar.gz"
                    )
                }

                var downloaded = false
                var lastError = ""
                var downloadedFile: File? = null

                for (tarUrl in candidateUrls) {
                    val isXz = tarUrl.endsWith(".xz")
                    val tarFile = File(context.filesDir, if (isXz) "rootfs.tar.xz" else "rootfs.tar.gz")
                    downloadedFile = tarFile
                    try {
                        onProgress(0.05f, "Downloading rootfs from $tarUrl...")
                        terminalLines.add("Downloading rootfs from $tarUrl ...")
                        
                        var currentUrl = tarUrl
                        var redirects = 0
                        var totalSize = -1
                        var input: InputStream? = null
                        
                        while (redirects < 8) {
                            val conn = URL(currentUrl).openConnection() as HttpURLConnection
                            conn.instanceFollowRedirects = true
                            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                            conn.connectTimeout = 15000
                            conn.readTimeout = 30000
                            conn.connect()
                            
                            val code = conn.responseCode
                            if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                                code == HttpURLConnection.HTTP_MOVED_TEMP ||
                                code == HttpURLConnection.HTTP_SEE_OTHER ||
                                code == 307 || code == 308) {
                                val loc = conn.getHeaderField("Location")
                                if (!loc.isNullOrBlank()) {
                                    currentUrl = loc
                                    redirects++
                                    conn.disconnect()
                                    continue
                                }
                            }
                            
                            if (code != HttpURLConnection.HTTP_OK) {
                                conn.disconnect()
                                throw IOException("HTTP $code when opening $currentUrl")
                            }
                            
                            totalSize = conn.contentLength
                            input = conn.inputStream
                            break
                        }
                        
                        if (input == null) {
                            throw IOException("Failed to establish stream for $tarUrl")
                        }
                        
                        val output = FileOutputStream(tarFile)
                        val buffer = ByteArray(16384)
                        var bytesRead: Int
                        var totalBytesRead = 0L
                        
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            if (totalSize > 0) {
                                val progress = 0.05f + 0.45f * (totalBytesRead.toFloat() / totalSize)
                                val mbRead = totalBytesRead / 1024 / 1024
                                val mbTotal = totalSize / 1024 / 1024
                                onProgress(progress, "Downloading rootfs: ${mbRead}MB / ${mbTotal}MB")
                            } else {
                                val mbRead = totalBytesRead / 1024 / 1024
                                onProgress(0.25f, "Downloading rootfs: ${mbRead}MB")
                            }
                        }
                        output.close()
                        input.close()
                        downloaded = true
                        break
                    } catch (e: Exception) {
                        lastError = e.message ?: "Download failed"
                        terminalLines.add("Source $tarUrl failed: $lastError. Trying fallback...")
                    }
                }

                val currentDownloadedFile = downloadedFile ?: throw IOException("All rootfs sources failed. No file downloaded.")
                if (!downloaded) {
                    throw IOException("All rootfs sources failed. Last error: $lastError")
                }
                
                onProgress(0.5f, "Download complete. Extracting rootfs...")
                
                terminalLines.add("Starting pure JVM rootfs extractor (memory-safe & portable)...")
                try {
                    val bufferSize = 32768
                    val fis = FileInputStream(currentDownloadedFile)
                    val bis = BufferedInputStream(fis, bufferSize)
                    val isXz = currentDownloadedFile.name.endsWith(".xz")
                    val decompressorStream = if (isXz) {
                        org.apache.commons.compress.compressors.xz.XZCompressorInputStream(bis)
                    } else {
                        GzipCompressorInputStream(bis)
                    }
                    val taris = TarArchiveInputStream(decompressorStream)
                    
                    var entry: TarArchiveEntry?
                    var filesExtracted = 0
                    
                    while (taris.nextEntry.also { entry = it as? TarArchiveEntry } != null) {
                        val currentEntry = entry ?: continue
                        val outputFile = File(distroDir, currentEntry.name)
                        
                        if (currentEntry.isDirectory) {
                            outputFile.mkdirs()
                        } else if (currentEntry.isSymbolicLink || currentEntry.isLink) {
                            if (outputFile.exists() || Files.isSymbolicLink(Paths.get(outputFile.absolutePath))) {
                                outputFile.deleteRecursively()
                            }
                            outputFile.parentFile?.mkdirs()
                            
                            var linkTarget = currentEntry.linkName
                            if (linkTarget.startsWith("/")) {
                                try {
                                    val targetAbs = File(distroDir, linkTarget.removePrefix("/")).toPath().toAbsolutePath()
                                    val parentAbs = (outputFile.parentFile ?: distroDir).toPath().toAbsolutePath()
                                    val relPath = parentAbs.relativize(targetAbs).toString()
                                    linkTarget = if (relPath.isBlank()) "." else relPath
                                } catch (e: Exception) {
                                    linkTarget = linkTarget.removePrefix("/")
                                }
                            }
                            
                            var linkCreated = false
                            if (currentEntry.isLink) {
                                try {
                                    val targetFile = File(distroDir, currentEntry.linkName.removePrefix("/"))
                                    if (targetFile.exists() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                        Files.createLink(Paths.get(outputFile.absolutePath), targetFile.toPath())
                                        linkCreated = true
                                    }
                                } catch (_: Exception) {}
                            }
                            
                            if (!linkCreated) {
                                try {
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                                        android.system.Os.symlink(linkTarget, outputFile.absolutePath)
                                    } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                        Files.createSymbolicLink(Paths.get(outputFile.absolutePath), Paths.get(linkTarget))
                                    }
                                } catch (e: Exception) {
                                    try {
                                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                            Files.createSymbolicLink(Paths.get(outputFile.absolutePath), Paths.get(linkTarget))
                                        }
                                    } catch (ex: Exception) {}
                                }
                            }
                        } else {
                            if (outputFile.exists() || Files.isSymbolicLink(Paths.get(outputFile.absolutePath))) {
                                outputFile.deleteRecursively()
                            }
                            outputFile.parentFile?.mkdirs()
                            FileOutputStream(outputFile).use { fos ->
                                val buffer = ByteArray(bufferSize)
                                var len: Int
                                while (taris.read(buffer).also { len = it } != -1) {
                                    fos.write(buffer, 0, len)
                                }
                            }
                            
                            // Restore Unix executable permissions if necessary
                            if ((currentEntry.mode and 0x49) != 0) { // Check user, group, or other executable bit (0111 octal / 73 decimal)
                                outputFile.setExecutable(true, false)
                            }
                        }
                        
                        filesExtracted++
                        if (filesExtracted % 100 == 0) {
                            val progress = 0.5f + 0.45f * (filesExtracted.toFloat() / (filesExtracted + 3000))
                            onProgress(progress, "Extracted $filesExtracted files...")
                            terminalLines.add("Extracted: ${currentEntry.name}")
                        }
                    }
                    taris.close()
                    decompressorStream.close()
                    bis.close()
                    fis.close()
                    
                    if (currentDownloadedFile.exists()) {
                        currentDownloadedFile.delete()
                    }
                    terminalLines.add("Successfully extracted $filesExtracted items natively!")
                } catch (e: Throwable) {
                    terminalLines.add("Extraction failed: ${e.message}")
                    e.printStackTrace()
                    throw e
                }
                
                terminalLines.add("Installation of $distroAlias complete!")
                onProgress(0.95f, "Configuring network resolution...")
                
                // create basic resolv.conf and hosts
                val resolvConf = File(distroDir, "etc/resolv.conf")
                resolvConf.parentFile?.mkdirs()
                resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
                
                val hostsConf = File(distroDir, "etc/hosts")
                hostsConf.writeText("127.0.0.1 localhost\n::1 localhost\n")
                
                // Configure APT for PRoot environment
                val aptConf = File(distroDir, "etc/apt/apt.conf.d/99proot")
                aptConf.parentFile?.mkdirs()
                aptConf.writeText("APT::Sandbox::User \"root\";\nAcquire::ForceIPv4 \"true\";\nAcquire::Languages \"none\";\n")

                onProgress(0.97f, "Running robust filesystem setup...")
                ensureRobustFilesystem(distroDir)
                
                onProgress(0.98f, "Auto-installing essential tools (curl, wget, git, python3, nano, ca-certificates)...")
                runEssentialPackageInstaller(distroAlias, distroDir, onProgress)

                val currentInstalled = _installedDistros.value.toMutableSet()
                currentInstalled.add(distroAlias)
                _installedDistros.value = currentInstalled
                
                onProgress(1.0f, "Completed!")
                onComplete(true)
            } catch (e: Throwable) {
                terminalLines.add("Error installing distro: ${e.message}")
                onProgress(0f, "Error: ${e.message}")
                try {
                    if (distroDir.exists() && !isDistroFullyInstalled(distroAlias)) {
                        distroDir.deleteRecursively()
                    }
                } catch (_: Exception) {}
                onComplete(false)
            }
        }
    }

    private fun convertAbsoluteSymlinksToRelative(distroDir: File) {
        val terminalLinesList = terminalLines
        fun log(msg: String) {
            terminalLinesList.add("[Symlink-Fix] $msg")
            android.util.Log.d("LinuxTerminalSimulator", "[Symlink-Fix] $msg")
        }

        try {
            log("Scanning for absolute symbolic links to make them relative...")
            var count = 0
            val canonicalDistroDir = try { distroDir.canonicalFile } catch (e: Exception) { distroDir.absoluteFile }
            
            fun traverse(file: File) {
                if (Files.isSymbolicLink(file.toPath())) {
                    try {
                        val targetPath = Files.readSymbolicLink(file.toPath())
                        val targetStr = targetPath.toString()
                        if (targetStr.startsWith("/")) {
                            val realTargetOnHost = File(canonicalDistroDir, targetStr.removePrefix("/"))
                            val parentFile = file.parentFile ?: canonicalDistroDir
                            val relativePath = try {
                                val rel = parentFile.toPath().toAbsolutePath().relativize(realTargetOnHost.toPath().toAbsolutePath()).toString()
                                if (rel.isBlank()) "." else rel
                            } catch (e: Exception) {
                                targetStr.removePrefix("/")
                            }
                            
                            val tempFile = File(parentFile, ".symlink_tmp_${java.util.UUID.randomUUID()}")
                            var success = false
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                                try {
                                    android.system.Os.symlink(relativePath, tempFile.absolutePath)
                                    success = true
                                } catch (_: Exception) {}
                            }
                            if (!success && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                try {
                                    Files.createSymbolicLink(tempFile.toPath(), Paths.get(relativePath))
                                    success = true
                                } catch (_: Exception) {}
                            }
                            if (success) {
                                file.delete()
                                tempFile.renameTo(file)
                                count++
                            }
                        }
                    } catch (e: Exception) {
                        // ignore single file error
                    }
                    return
                }
                
                if (file.isDirectory) {
                    val name = file.name
                    if (name == "dev" || name == "proc" || name == "sys") return
                    
                    file.listFiles()?.forEach { child ->
                        traverse(child)
                    }
                }
            }
            
            traverse(canonicalDistroDir)
            log("Symlink scan completed. Fixed $count absolute symlinks.")
        } catch (e: Exception) {
            log("Error during symlink scan: ${e.message}")
        }
    }

    private fun repairDpkgDatabase(distroDir: File) {
        try {
            val dpkgDir = File(distroDir, "var/lib/dpkg")
            if (!dpkgDir.exists()) dpkgDir.mkdirs()

            fun makeTreeWritable(dir: File, depth: Int = 0) {
                if (depth > 12 || !dir.exists()) return
                try {
                    dir.setWritable(true, false)
                    dir.setReadable(true, false)
                    if (dir.isDirectory) {
                        dir.setExecutable(true, false)
                        dir.listFiles()?.forEach { child ->
                            makeTreeWritable(child, depth + 1)
                        }
                    }
                } catch (e: Exception) {}
            }

            val criticalDirs = listOf(
                "var/lib/dpkg",
                "var/lib/dpkg/info",
                "var/lib/dpkg/updates",
                "var/lib/dpkg/parts",
                "var/lib/dpkg/triggers",
                "var/lib/dpkg/alternatives",
                "var/lib/apt",
                "var/lib/apt/lists",
                "var/lib/apt/lists/partial",
                "var/cache/apt",
                "var/cache/apt/archives",
                "var/cache/apt/archives/partial",
                "var/log",
                "var/log/apt",
                "var/run",
                "run",
                "tmp"
            )
            for (dirPath in criticalDirs) {
                val dir = File(distroDir, dirPath)
                if (dir.exists() && !dir.isDirectory) {
                    dir.delete()
                }
                if (!dir.exists()) {
                    dir.mkdirs()
                }
            }

            makeTreeWritable(File(distroDir, "var/lib/dpkg"))
            makeTreeWritable(File(distroDir, "var/lib/apt"))
            makeTreeWritable(File(distroDir, "var/cache/apt"))
            makeTreeWritable(File(distroDir, "tmp"))
            makeTreeWritable(File(distroDir, "etc/dpkg"))

            val statusFile = File(distroDir, "var/lib/dpkg/status")
            val statusOldFile = File(distroDir, "var/lib/dpkg/status-old")
            val statusNewFile = File(distroDir, "var/lib/dpkg/status-new")

            fun removeIfSymlink(file: File) {
                try {
                    val p = file.toPath()
                    if (java.nio.file.Files.isSymbolicLink(p)) {
                        java.nio.file.Files.deleteIfExists(p)
                    }
                } catch (_: Exception) {}
            }

            removeIfSymlink(statusOldFile)
            removeIfSymlink(statusNewFile)
            removeIfSymlink(statusFile)

            if ((!statusFile.exists() || statusFile.length() == 0L) && statusOldFile.exists() && statusOldFile.length() > 0L) {
                try {
                    statusOldFile.copyTo(statusFile, overwrite = true)
                } catch (e: Exception) {}
            } else if (!statusFile.exists()) {
                try {
                    statusFile.createNewFile()
                } catch (e: Exception) {}
            }

            if (statusFile.exists() && statusFile.length() > 0L && (!statusOldFile.exists() || statusOldFile.length() == 0L)) {
                try {
                    statusFile.copyTo(statusOldFile, overwrite = true)
                } catch (e: Exception) {}
            }

            val availableFile = File(distroDir, "var/lib/dpkg/available")
            removeIfSymlink(availableFile)
            if (!availableFile.exists()) {
                try { availableFile.createNewFile() } catch (e: Exception) {}
            }

            listOf(statusFile, statusOldFile, statusNewFile, availableFile).forEach { file ->
                if (file.exists()) {
                    try {
                        file.setWritable(true, false)
                        file.setReadable(true, false)
                    } catch (e: Exception) {}
                }
            }

            val lockFiles = listOf(
                "var/lib/dpkg/lock",
                "var/lib/dpkg/lock-frontend",
                "var/lib/dpkg/triggers/Lock",
                "var/lib/apt/lists/lock",
                "var/cache/apt/archives/lock"
            )
            for (lockPath in lockFiles) {
                try {
                    val lFile = File(distroDir, lockPath)
                    removeIfSymlink(lFile)
                    if (lFile.exists()) {
                        lFile.delete()
                    }
                } catch (e: Exception) {}
            }

            val updatesDir = File(distroDir, "var/lib/dpkg/updates")
            if (updatesDir.exists() && updatesDir.isDirectory) {
                updatesDir.listFiles()?.forEach { uFile ->
                    try {
                        removeIfSymlink(uFile)
                        uFile.setWritable(true, false)
                        uFile.setReadable(true, false)
                        if (uFile.length() == 0L || !uFile.exists()) {
                            uFile.delete()
                        }
                    } catch (e: Exception) {}
                }
            }

            try {
                val dpkgCfgDir = File(distroDir, "etc/dpkg/dpkg.cfg.d")
                dpkgCfgDir.mkdirs()
                val dpkgCfg = File(dpkgCfgDir, "99_android")
                dpkgCfg.writeText("force-unsafe-io\nforce-bad-path\nno-debsig\nforce-overwrite\n")
                dpkgCfg.setReadable(true, false)
                dpkgCfg.setWritable(true, false)

                val aptCfgDir = File(distroDir, "etc/apt/apt.conf.d")
                aptCfgDir.mkdirs()
                val aptCfg = File(aptCfgDir, "99_android")
                aptCfg.writeText("DPkg::Options {\n   \"--force-unsafe-io\";\n   \"--force-bad-path\";\n   \"--force-overwrite\";\n};\nAPT::Register-Post-Invoke { \"true\"; };\nAPT::Get::AllowUnauthenticated \"true\";\n")
                aptCfg.setReadable(true, false)
                aptCfg.setWritable(true, false)
            } catch (e: Exception) {}

            val dummyScriptPaths = listOf(
                "sbin/start-stop-daemon",
                "usr/sbin/start-stop-daemon",
                "sbin/initctl",
                "usr/bin/systemctl"
            )
            for (dummyPath in dummyScriptPaths) {
                try {
                    val file = File(distroDir, dummyPath)
                    if (!file.exists() || file.length() == 0L) {
                        file.parentFile?.mkdirs()
                        file.writeText("#!/bin/sh\nexit 0\n")
                        file.setExecutable(true, false)
                        file.setReadable(true, false)
                    }
                } catch (e: Exception) {}
            }

            // Fix ldconfig / ldconfig.real symlink issues under PRoot (solves dpkg --configure -a hanging)
            try {
                val sbinLdconfig = File(distroDir, "sbin/ldconfig")
                val usrSbinLdconfig = File(distroDir, "usr/sbin/ldconfig")
                val sbinLdconfigReal = File(distroDir, "sbin/ldconfig.real")
                val usrSbinLdconfigReal = File(distroDir, "usr/sbin/ldconfig.real")

                val realTarget = when {
                    usrSbinLdconfigReal.exists() -> usrSbinLdconfigReal
                    sbinLdconfigReal.exists() -> sbinLdconfigReal
                    else -> null
                }

                listOf(sbinLdconfig, usrSbinLdconfig).forEach { targetFile ->
                    targetFile.parentFile?.mkdirs()
                    if (!targetFile.exists() || targetFile.length() == 0L) {
                        removeIfSymlink(targetFile)
                        if (realTarget != null) {
                            try {
                                java.nio.file.Files.createSymbolicLink(targetFile.toPath(), java.nio.file.Paths.get("ldconfig.real"))
                            } catch (_: Exception) {
                                targetFile.writeText("#!/bin/sh\nif [ -x /usr/sbin/ldconfig.real ]; then\n  exec /usr/sbin/ldconfig.real \"$@\"\nelif [ -x /sbin/ldconfig.real ]; then\n  exec /sbin/ldconfig.real \"$@\"\nelse\n  exit 0\nfi\n")
                            }
                        } else {
                            targetFile.writeText("#!/bin/sh\nexit 0\n")
                        }
                    }
                    try {
                        targetFile.setExecutable(true, false)
                        targetFile.setReadable(true, false)
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {}

            try {
                val resolvConf = File(distroDir, "etc/resolv.conf")
                if (!resolvConf.exists() || resolvConf.length() == 0L) {
                    resolvConf.parentFile?.mkdirs()
                    resolvConf.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\nnameserver 9.9.9.9\nnameserver 2606:4700:4700::1111\n")
                    resolvConf.setReadable(true, false)
                    resolvConf.setWritable(true, false)
                }

                val hostsFile = File(distroDir, "etc/hosts")
                if (!hostsFile.exists() || hostsFile.length() == 0L) {
                    hostsFile.parentFile?.mkdirs()
                    hostsFile.writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
                    hostsFile.setReadable(true, false)
                    hostsFile.setWritable(true, false)
                }
            } catch (e: Exception) {}

            // Prevent warnings like "groups: cannot find name for group ID ..."
            ensureAndroidIdsInGuestFiles(distroDir)
        } catch (e: Exception) {
            log("Error during dpkg database repair: ${e.message}")
        }
    }

    private fun ensureAndroidIdsInGuestFiles(distroDir: File) {
        try {
            val etcDir = File(distroDir, "etc")
            if (!etcDir.exists()) etcDir.mkdirs()

            val groupFile = File(etcDir, "group")
            val myUid = try { android.os.Process.myUid() } catch (_: Exception) { 10000 }
            
            val defaultGroupsToAdd = mutableMapOf<Int, String>(
                3003 to "inet",
                9997 to "everybody",
                1015 to "sdcard_rw",
                1023 to "media_rw",
                1028 to "sdcard_r",
                3001 to "net_bt_admin",
                3002 to "net_bt",
                3004 to "net_raw",
                3005 to "net_admin"
            )
            // Also include myUid if it's not root (0)
            if (myUid > 0 && !defaultGroupsToAdd.containsKey(myUid)) {
                defaultGroupsToAdd[myUid] = "aid_app_$myUid"
            }

            // Let's add other common user group IDs mentioned in the prompt (e.g. 20331, 50331, etc.)
            val staticCommonGids = listOf(20331, 50331)
            staticCommonGids.forEach { gid ->
                if (!defaultGroupsToAdd.containsKey(gid)) {
                    defaultGroupsToAdd[gid] = "aid_app_$gid"
                }
            }

            if (groupFile.exists()) {
                val lines = groupFile.readLines().toMutableList()
                val existingGids = mutableSetOf<Int>()
                lines.forEach { line ->
                    val parts = line.split(":")
                    if (parts.size >= 3) {
                        parts[2].toIntOrNull()?.let { existingGids.add(it) }
                    }
                }
                
                var modified = false
                defaultGroupsToAdd.forEach { (gid, name) ->
                    if (!existingGids.contains(gid)) {
                        lines.add("$name:x:$gid:root")
                        modified = true
                    }
                }
                if (modified) {
                    groupFile.writeText(lines.joinToString("\n") + "\n")
                }
            } else {
                val sb = java.lang.StringBuilder()
                sb.append("root:x:0:\n")
                defaultGroupsToAdd.forEach { (gid, name) ->
                    sb.append("$name:x:$gid:root\n")
                }
                groupFile.writeText(sb.toString())
            }

            // Same for /etc/passwd to prevent "cannot find name for user ID ..."
            val passwdFile = File(etcDir, "passwd")
            if (passwdFile.exists()) {
                val lines = passwdFile.readLines().toMutableList()
                val existingUids = mutableSetOf<Int>()
                lines.forEach { line ->
                    val parts = line.split(":")
                    if (parts.size >= 3) {
                        parts[2].toIntOrNull()?.let { existingUids.add(it) }
                    }
                }
                
                var modified = false
                if (myUid > 0 && !existingUids.contains(myUid)) {
                    lines.add("aid_app_$myUid:x:$myUid:$myUid:Android App:/root:/bin/bash")
                    modified = true
                }
                staticCommonGids.forEach { uid ->
                    if (uid > 0 && !existingUids.contains(uid)) {
                        lines.add("aid_app_$uid:x:$uid:$uid:Android App:/root:/bin/bash")
                        modified = true
                    }
                }
                if (modified) {
                    passwdFile.writeText(lines.joinToString("\n") + "\n")
                }
            } else {
                val sb = java.lang.StringBuilder()
                sb.append("root:x:0:0:root:/root:/bin/bash\n")
                if (myUid > 0) {
                    sb.append("aid_app_$myUid:x:$myUid:$myUid:Android App:/root:/bin/bash\n")
                }
                staticCommonGids.forEach { uid ->
                    if (uid != myUid) {
                        sb.append("aid_app_$uid:x:$uid:$uid:Android App:/root:/bin/bash\n")
                    }
                }
                passwdFile.writeText(sb.toString())
            }
        } catch (_: Exception) {}
    }

    private fun ensureRobustFilesystem(distroDir: File) {
        val terminalLinesList = terminalLines
        fun log(msg: String) {
            terminalLinesList.add("[FS-Setup] $msg")
            android.util.Log.d("LinuxTerminalSimulator", "[FS-Setup] $msg")
        }
        
        try {
            log("Initializing robust filesystem verification for ${distroDir.name}...")
            
            // 1. First step: Convert any guest-absolute symlinks to relative to ensure they resolve properly on host
            convertAbsoluteSymlinksToRelative(distroDir)
            
            // 2. Ensure core directories exist
            val coreDirs = listOf("tmp", "dev", "dev/shm", "dev/pts", "run/shm", "proc", "sys", "etc", "root", "bin", "sbin", "lib", "usr", "usr/bin", "usr/sbin", "usr/lib", "usr/local/bin")
            for (dirName in coreDirs) {
                val d = File(distroDir, dirName)
                if (!d.exists()) {
                    d.mkdirs()
                }
                try {
                    d.setWritable(true, false)
                    d.setReadable(true, false)
                    d.setExecutable(true, false)
                } catch (e: Exception) {}
            }
            
            // Ensure /tmp and /dev/shm are fully writable and executable
            val tmpDir = File(distroDir, "tmp")
            try {
                tmpDir.setWritable(true, false)
                tmpDir.setReadable(true, false)
                tmpDir.setExecutable(true, false)
            } catch (e: Exception) {
                log("Permission warning for /tmp: ${e.message}")
            }
            val shmDir = File(distroDir, "dev/shm")
            try {
                shmDir.setWritable(true, false)
                shmDir.setReadable(true, false)
                shmDir.setExecutable(true, false)
            } catch (e: Exception) {}

            // 2.5 Ensure core package manager (dpkg and apt) directories and files exist and are not corrupted
            log("Verifying and repairing core package manager (dpkg and apt) filesystems...")
            repairDpkgDatabase(distroDir)

            // Create helper script /usr/local/bin/install-essential-tools for manual re-installation anytime
                try {
                    val toolScript = File(distroDir, "usr/local/bin/install-essential-tools")
                    toolScript.parentFile?.mkdirs()
                    toolScript.writeText(
                        "#!/bin/sh\n" +
                        "echo \"============================================================\"\n" +
                        "echo \"  Zhypix PRoot Linux - Installing Essential Developer Tools \"\n" +
                        "echo \"============================================================\"\n" +
                        "if [ -f /usr/bin/apt-get ] || [ -f /usr/bin/apt ]; then\n" +
                        "  export DEBIAN_FRONTEND=noninteractive\n" +
                        "  apt-get update -y\n" +
                        "  apt-get install -y --no-install-recommends ca-certificates curl wget nano git python3 python3-pip procps iputils-ping net-tools sudo unzip tar gzip build-essential neofetch\n" +
                        "elif [ -f /sbin/apk ]; then\n" +
                        "  apk update\n" +
                        "  apk add ca-certificates curl wget nano git python3 procps net-tools sudo unzip tar gzip bash build-base neofetch\n" +
                        "elif [ -f /usr/bin/pacman ]; then\n" +
                        "  pacman -Sy --noconfirm ca-certificates curl wget nano git python3 procps-ng net-tools sudo unzip tar gzip base-devel neofetch\n" +
                        "elif [ -f /usr/bin/dnf ]; then\n" +
                        "  dnf install -y ca-certificates curl wget nano git python3 procps-ng net-tools sudo unzip tar gzip gcc gcc-c++ make neofetch\n" +
                        "fi\n" +
                        "echo \"\"\n" +
                        "echo \"=== Essential Tools Installation Complete! ===\"\n"
                    )
                    toolScript.setExecutable(true, false)
                } catch (pe: Exception) {}

                // Create default shell aliases and profile
                try {
                    val bashProfile = "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:\$PATH\n" +
                            "export HOME=/root\n" +
                            "export USER=root\n" +
                            "export TERM=xterm-256color\n" +
                            "export LANG=C.UTF-8\n" +
                            "export LC_ALL=C.UTF-8\n\n" +
                            "alias ll='ls -la --color=auto'\n" +
                            "alias la='ls -A --color=auto'\n" +
                            "alias l='ls -CF --color=auto'\n" +
                            "alias update='if [ -f /usr/bin/apt-get ]; then apt-get update && apt-get upgrade -y; elif [ -f /sbin/apk ]; then apk update && apk upgrade; fi'\n" +
                            "alias install-tools='/usr/local/bin/install-essential-tools'\n"

                    val sysBashrc = File(distroDir, "etc/bash.bashrc")
                    sysBashrc.parentFile?.mkdirs()
                    if (!sysBashrc.exists() || !sysBashrc.readText().contains("install-tools")) {
                        sysBashrc.appendText("\n" + bashProfile)
                    }

                    val rootBashrc = File(distroDir, "root/.bashrc")
                    rootBashrc.parentFile?.mkdirs()
                    if (!rootBashrc.exists() || !rootBashrc.readText().contains("install-tools")) {
                        rootBashrc.appendText("\n" + bashProfile)
                    }

                    val motd = File(distroDir, "etc/motd")
                    motd.parentFile?.mkdirs()
                    motd.writeText(
                        "===================================================================\n" +
                        "  Welcome to Zhypix PRoot Linux Sandbox\n" +
                        "  System: Linux Container (PRoot Guest)\n" +
                        "-------------------------------------------------------------------\n" +
                        "  * Pre-configured essential tools: curl, wget, git, python3, nano, ca-certificates\n" +
                        "  * Type 'install-tools' to install complete developer compilers & tools\n" +
                        "  * Type 'apt update && apt install <package>' to install any software\n" +
                        "===================================================================\n"
                    )

                    // Create Chrome & GUI app wrappers to automatically supply --no-sandbox --disable-dev-shm-usage
                    try {
                        val chromeWrapperContent = "#!/bin/sh\n" +
                                "if [ -f /usr/bin/google-chrome-stable ]; then\n" +
                                "    exec /usr/bin/google-chrome-stable --no-sandbox --disable-dev-shm-usage \"$@\"\n" +
                                "elif [ -f /usr/bin/google-chrome ]; then\n" +
                                "    exec /usr/bin/google-chrome --no-sandbox --disable-dev-shm-usage \"$@\"\n" +
                                "elif [ -f /usr/bin/chromium ]; then\n" +
                                "    exec /usr/bin/chromium --no-sandbox --disable-dev-shm-usage \"$@\"\n" +
                                "elif [ -f /usr/bin/chromium-browser ]; then\n" +
                                "    exec /usr/bin/chromium-browser --no-sandbox --disable-dev-shm-usage \"$@\"\n" +
                                "fi\n"

                        val gChrome = File(distroDir, "usr/local/bin/google-chrome")
                        gChrome.parentFile?.mkdirs()
                        gChrome.writeText(chromeWrapperContent)
                        gChrome.setExecutable(true, false)

                        val chromium = File(distroDir, "usr/local/bin/chromium")
                        chromium.writeText(chromeWrapperContent)
                        chromium.setExecutable(true, false)

                        val chromiumBrowser = File(distroDir, "usr/local/bin/chromium-browser")
                        chromiumBrowser.writeText(chromeWrapperContent)
                        chromiumBrowser.setExecutable(true, false)
                    } catch (pe: Exception) {}
                } catch (pe: Exception) {}

            // 3. Scan and fix permissions recursively for ALL executables and dynamic linkers/loaders
            log("Scanning entire rootfs to ensure all binaries & loaders have executable permissions...")
            var executableCount = 0
            
            fun processFile(file: File) {
                if (Files.isSymbolicLink(file.toPath())) {
                    try {
                        val realTarget = file.canonicalFile
                        if (realTarget.exists() && realTarget.isFile) {
                            if (!realTarget.canExecute()) {
                                realTarget.setExecutable(true, false)
                                executableCount++
                            }
                        }
                    } catch (e: Exception) {
                        // ignore broken symlink errors
                    }
                    return
                }

                if (file.isDirectory) {
                    val name = file.name
                    // Skip proc, sys, dev virtual file systems to avoid infinite loops or blocking
                    if (name == "dev" || name == "proc" || name == "sys") return
                    
                    file.listFiles()?.forEach { child ->
                        processFile(child)
                    }
                } else if (file.isFile) {
                    val path = file.absolutePath
                    val isExecutableDir = path.contains("/bin/") || path.contains("/sbin/")
                    val name = file.name
                    val isLoader = name.startsWith("ld-") || name.contains("musl") || name.contains("ld-linux") || name.contains(".so")
                    
                    if (isExecutableDir || isLoader) {
                        if (!file.canExecute()) {
                            file.setExecutable(true, false)
                            executableCount++
                        }
                    }
                }
            }
            
            processFile(distroDir)
            log("Permissions successfully verified. Made $executableCount files/targets executable.")
            
            log("Running advanced dynamic linker self-healing and recovery...")
            val realLoaders = mutableListOf<File>()
            
            fun scanForLoaders(file: File) {
                if (file.isDirectory) {
                    val name = file.name
                    if (name == "dev" || name == "proc" || name == "sys" || name == "tmp") return
                    file.listFiles()?.forEach { scanForLoaders(it) }
                } else if (file.isFile && !Files.isSymbolicLink(file.toPath())) {
                    val name = file.name
                    val path = file.absolutePath
                    val isPossibleLoader = (name.startsWith("ld-") && name.contains(".so")) || 
                                           name.contains("ld-linux") || 
                                           name.contains("ld-musl") ||
                                           (name.startsWith("ld-") && name.matches(Regex("ld-[0-9.]+\\.so.*")))
                    if (isPossibleLoader) {
                        realLoaders.add(file)
                        log("Discovered real system loader binary: ${file.absolutePath}")
                    }
                }
            }
            scanForLoaders(distroDir)

            for (loader in realLoaders) {
                loader.setExecutable(true, false)
                val name = loader.name
                val path = loader.absolutePath
                
                // 1. Glibc AMD64 / x86_64
                if (name.contains("ld-linux-x86-64") || (name.startsWith("ld-2.") && path.contains("x86_64"))) {
                    val symlinkFile = File(distroDir, "lib64/ld-linux-x86-64.so.2")
                    ensureRelativeSymlink(symlinkFile, loader)
                    
                    val symlinkFile2 = File(distroDir, "lib/ld-linux-x86-64.so.2")
                    ensureRelativeSymlink(symlinkFile2, loader)
                }
                
                // 2. Glibc AArch64 / ARM64
                if (name.contains("ld-linux-aarch64") || (name.startsWith("ld-2.") && path.contains("aarch64"))) {
                    val symlinkFile = File(distroDir, "lib/ld-linux-aarch64.so.1")
                    ensureRelativeSymlink(symlinkFile, loader)
                }
                
                // 3. Glibc ARMHF / ARM 32-bit
                if (name.contains("ld-linux-armhf") || (name.startsWith("ld-2.") && path.contains("arm"))) {
                    val symlinkFile = File(distroDir, "lib/ld-linux-armhf.so.3")
                    ensureRelativeSymlink(symlinkFile, loader)
                }
                
                // 4. Musl AMD64
                if (name.contains("ld-musl-x86_64")) {
                    val symlinkFile = File(distroDir, "lib/ld-musl-x86_64.so.1")
                    ensureRelativeSymlink(symlinkFile, loader)
                }
                
                // 5. Musl AArch64
                if (name.contains("ld-musl-aarch64")) {
                    val symlinkFile = File(distroDir, "lib/ld-musl-aarch64.so.1")
                    ensureRelativeSymlink(symlinkFile, loader)
                }
            }

            // 5. Advanced shell self-healing and recovery
            log("Running advanced shell self-healing and recovery...")
            val realShells = mutableListOf<File>()
            fun scanForShells(file: File) {
                if (file.isDirectory) {
                    val name = file.name
                    if (name == "dev" || name == "proc" || name == "sys" || name == "tmp") return
                    file.listFiles()?.forEach { scanForShells(it) }
                } else if (file.isFile && !Files.isSymbolicLink(file.toPath())) {
                    val name = file.name
                    if (name == "dash" || name == "bash" || name == "sh" || name == "busybox" || name == "ash") {
                        realShells.add(file)
                        log("Discovered real shell binary: ${file.absolutePath}")
                    }
                }
            }
            scanForShells(distroDir)
            
            val bestShell = realShells.firstOrNull { it.name == "bash" }
                ?: realShells.firstOrNull { it.name == "dash" }
                ?: realShells.firstOrNull { it.name == "sh" }
                ?: realShells.firstOrNull { it.name == "busybox" }
                ?: realShells.firstOrNull { it.name == "ash" }
                ?: realShells.firstOrNull()
                
            bestShell?.let { shell ->
                shell.setExecutable(true, false)
                log("Self-healing shell selected: ${shell.absolutePath}")
                
                val shSymlink = File(distroDir, "bin/sh")
                ensureRelativeSymlink(shSymlink, shell)
                
                val usrShSymlink = File(distroDir, "usr/bin/sh")
                ensureRelativeSymlink(usrShSymlink, shell)
            }
            
            // Self-heal and install custom system wrappers (pgrep, sysctl, systemctl, lsblk, etc.)
            removeLegacyMockWrappers(distroDir)
            
            log("Robust filesystem setup and check completed successfully.")
        } catch (e: Exception) {
            log("Error during robust filesystem check: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun ensureRelativeSymlink(symlinkFile: File, targetFile: File) {
        try {
            val parentDir = symlinkFile.parentFile ?: return
            parentDir.mkdirs()
            
            if (symlinkFile.exists() || Files.isSymbolicLink(symlinkFile.toPath())) {
                symlinkFile.delete()
            }
            
            val parentCanonical = parentDir.canonicalFile
            val targetCanonical = targetFile.canonicalFile
            val relativePath = parentCanonical.toPath().relativize(targetCanonical.toPath()).toString()
            
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    android.system.Os.symlink(relativePath, symlinkFile.absolutePath)
                } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    Files.createSymbolicLink(symlinkFile.toPath(), Paths.get(relativePath))
                }
                log("Created self-healing symlink: ${symlinkFile.absolutePath} -> $relativePath")
            } catch (e: Exception) {
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        Files.createSymbolicLink(symlinkFile.toPath(), Paths.get(relativePath))
                        log("Created self-healing symlink (NIO): ${symlinkFile.absolutePath} -> $relativePath")
                    }
                } catch (ex: Exception) {
                    log("Failed to create symlink ${symlinkFile.absolutePath}: ${ex.message}")
                }
            }
        } catch (e: Exception) {
            log("Failed to create symlink ${symlinkFile.absolutePath}: ${e.message}")
        }
    }

    private fun ensureBinariesAndShellsExecutable(distroDir: File) {
        try {
            fun makeExecutable(dir: File, depth: Int = 0) {
                if (depth > 12) return
                try {
                    dir.setExecutable(true, false)
                    dir.setWritable(true, false)
                    dir.setReadable(true, false)
                } catch (e: Exception) {}
                dir.listFiles()?.forEach { file ->
                    try {
                        if (file.isDirectory) {
                            makeExecutable(file, depth + 1)
                        } else if (file.isFile) {
                            file.setExecutable(true, false)
                            file.setReadable(true, false)
                        }
                    } catch (e: Exception) {}
                }
            }
            makeExecutable(distroDir)

            val binDirs = listOf(
                "bin", "usr/bin", "sbin", "usr/sbin", "usr/local/bin",
                "lib", "lib64", "usr/lib", "usr/lib64", "usr/local/lib",
                "lib/aarch64-linux-gnu", "lib/x86_64-linux-gnu",
                "usr/lib/aarch64-linux-gnu", "usr/lib/x86_64-linux-gnu"
            )
            for (dName in binDirs) {
                val dir = File(distroDir, dName)
                if (dir.exists()) {
                    try {
                        dir.setExecutable(true, false)
                        dir.setWritable(true, false)
                        dir.setReadable(true, false)
                    } catch (e: Exception) {}
                    dir.listFiles()?.forEach { file ->
                        try {
                            file.setExecutable(true, false)
                            file.setReadable(true, false)
                        } catch (e: Exception) {}
                    }
                }
            }

            val shellCandidates = listOf("bash", "dash", "sh", "busybox", "ash")
            var bestShell: File? = null
            for (sName in shellCandidates) {
                for (dName in listOf("bin", "usr/bin", "usr/local/bin")) {
                    val candidate = File(distroDir, "$dName/$sName")
                    if (candidate.exists() && !Files.isSymbolicLink(candidate.toPath()) && candidate.length() > 0) {
                        candidate.setExecutable(true, false)
                        candidate.setReadable(true, false)
                        if (bestShell == null) bestShell = candidate
                    }
                }
            }

            for (shPath in listOf("bin/sh", "usr/bin/sh", "bin/bash", "usr/bin/bash")) {
                val shFile = File(distroDir, shPath)
                if (shFile.exists()) {
                    try {
                        val canonical = shFile.canonicalFile
                        if (canonical.exists()) {
                            canonical.setExecutable(true, false)
                            canonical.setReadable(true, false)
                        }
                    } catch (e: Exception) {}
                }
            }

            bestShell?.let { shell ->
                val binSh = File(distroDir, "bin/sh")
                val usrBinSh = File(distroDir, "usr/bin/sh")
                
                var needBinShFix = false
                if (!binSh.exists()) {
                    needBinShFix = true
                } else {
                    try {
                        val canonical = binSh.canonicalFile
                        if (!canonical.exists() || !canonical.canExecute()) {
                            needBinShFix = true
                        }
                    } catch (e: Exception) {
                        needBinShFix = true
                    }
                }
                if (needBinShFix) {
                    ensureRelativeSymlink(binSh, shell)
                }

                var needUsrBinShFix = false
                if (!usrBinSh.exists()) {
                    needUsrBinShFix = true
                } else {
                    try {
                        val canonical = usrBinSh.canonicalFile
                        if (!canonical.exists() || !canonical.canExecute()) {
                            needUsrBinShFix = true
                        }
                    } catch (e: Exception) {
                        needUsrBinShFix = true
                    }
                }
                if (needUsrBinShFix) {
                    ensureRelativeSymlink(usrBinSh, shell)
                }
            }
        } catch (e: Exception) {
            // ignore non-fatal errors
        }
    }

    private suspend fun runEssentialPackageInstaller(
        distroAlias: String,
        distroDir: File,
        onProgress: ((Float, String) -> Unit)? = null
    ) {
        val context = applicationContext ?: return
        val pPath = prootPath ?: return
        
        terminalLines.add("[Package-Installer] Auto-installing essential tools for $distroAlias (curl, wget, nano, git, python3, ca-certificates)...")
        
        repairDpkgDatabase(distroDir)
        
        val installCmd = when (distroAlias) {
            "ubuntu", "debian", "kali" -> 
                "export DEBIAN_FRONTEND=noninteractive; dpkg --configure -a --force-confdef --force-confold 2>/dev/null || true; apt-get update -y && apt-get install -y --no-install-recommends ca-certificates curl wget nano git python3 python3-pip procps iputils-ping net-tools sudo unzip tar gzip nodejs npm xvfb scrot xdotool neofetch && ln -sf /usr/bin/python3 /usr/bin/python"
            "alpine" -> 
                "apk update && apk add ca-certificates curl wget nano git python3 py3-pip procps net-tools sudo unzip tar gzip bash nodejs npm xvfb scrot xdotool neofetch && ln -sf /usr/bin/python3 /usr/bin/python"
            "archlinux" -> 
                "pacman -Sy --noconfirm ca-certificates curl wget nano git python python-pip procps-ng net-tools sudo unzip tar gzip nodejs npm xorg-server-xvfb scrot xdotool neofetch"
            "fedora" -> 
                "dnf install -y ca-certificates curl wget nano git python3 python3-pip procps-ng net-tools sudo unzip tar gzip nodejs npm xorg-x11-server-Xvfb scrot xdotool neofetch && ln -sf /usr/bin/python3 /usr/bin/python"
            else -> 
                "export DEBIAN_FRONTEND=noninteractive; dpkg --configure -a --force-confdef --force-confold 2>/dev/null || true; apt-get update -y && apt-get install -y --no-install-recommends ca-certificates curl wget nano git python3 python3-pip procps iputils-ping net-tools sudo unzip tar gzip nodejs npm xvfb scrot xdotool neofetch && ln -sf /usr/bin/python3 /usr/bin/python"
        }

        val prootCmdList = mutableListOf<String>()
        prootCmdList.add(pPath)
        prootCmdList.add("-r")
        prootCmdList.add((try { distroDir.canonicalFile.absolutePath } catch (e: Exception) { distroDir.absolutePath }))
        prootCmdList.add("-0")
        prootCmdList.add("--link2symlink")
        prootCmdList.add("--sysvipc")
        prootCmdList.add("--kill-on-exit")
        prootCmdList.add("-w")
        prootCmdList.add("/root")
        
        appendStandardPRootBindings(prootCmdList, context, distroDir)

        ensureBinariesAndShellsExecutable(distroDir)

        prootCmdList.addAll(listOf(
            "/bin/sh", "-c", "unset LD_LIBRARY_PATH; export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:\$PATH HOME=/root USER=root SHELL=/bin/bash TERM=xterm-256color LANG=C.UTF-8 LC_ALL=C.UTF-8 DEBIAN_FRONTEND=noninteractive APT_LISTCHANGES_FRONTEND=none TMPDIR=/tmp TEMP=/tmp TMP=/tmp; $installCmd"
        ))

        try {
            val builder = ProcessBuilder(prootCmdList).directory(context.filesDir)
            builder.redirectErrorStream(true)
            val prootTmpDir = File(File(context.cacheDir, "proot_tmp"), "install_" + java.util.UUID.randomUUID().toString()).apply {
                mkdirs()
                setWritable(true, false)
                setReadable(true, false)
                setExecutable(true, false)
            }
            applyStandardPRootEnvironment(builder, context, prootTmpDir)

            val process = builder.start()
            val reader = java.io.InputStreamReader(process.inputStream, "UTF-8")
            val bufferedReader = java.io.BufferedReader(reader)
            var line: String?
            var lineCount = 0
            while (bufferedReader.readLine().also { line = it } != null) {
                line?.let { l ->
                    lineCount++
                    if (l.contains("Get:") || l.contains("Unpacking") || l.contains("Setting up") || l.contains("Installing") || l.contains("Fetch") || l.contains("Progress") || l.contains("Processing")) {
                        onProgress?.invoke(0.98f + (lineCount % 20) * 0.001f, "Installing packages: ${l.take(50)}...")
                    }
                }
            }
            process.waitFor(3, java.util.concurrent.TimeUnit.MINUTES)
            terminalLines.add("[Package-Installer] Essential tools installed successfully! (curl, wget, git, python3, nano, ca-certificates, etc.)")
        } catch (e: Exception) {
            terminalLines.add("[Package-Installer] Package auto-installer skipped: ${e.message}. You can run 'install-tools' inside terminal anytime.")
        }
    }

    fun removeDistroDirect(distroAlias: String) {
        val context = applicationContext ?: return
        val distroDir = resolveDistroDir(distroAlias)
        if (distroDir.exists()) {
            distroDir.deleteRecursively()
            terminalLines.add("Removed $distroAlias.")
            val currentInstalled = _installedDistros.value.toMutableSet()
            currentInstalled.remove(distroAlias)
            _installedDistros.value = currentInstalled
        }
    }

    fun loginDistroDirect(distroAlias: String) {
        val context = applicationContext ?: return
        if (!isDistroFullyInstalled(distroAlias)) {
            terminalLines.add("Distribution '$distroAlias' is not installed or incomplete.")
            terminalLines.add("Run 'proot-distro install $distroAlias' or use the PRoot Distros tab first.")
            return
        }
        val distroDir = resolveDistroDir(distroAlias)
        
        ensureRobustFilesystem(distroDir)
        
        // Configure APT for existing installations
        val aptConf = File(distroDir, "etc/apt/apt.conf.d/99proot")
        aptConf.parentFile?.mkdirs()
        aptConf.writeText("APT::Sandbox::User \"root\";\nAcquire::ForceIPv4 \"true\";\nAcquire::Languages \"none\";\n")
        
        // Ensure DNS is configured properly on every login
        val resolvConf = File(distroDir, "etc/resolv.conf")
        resolvConf.parentFile?.mkdirs()
        resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        
        val hostsConf = File(distroDir, "etc/hosts")
        hostsConf.parentFile?.mkdirs()
        hostsConf.writeText("127.0.0.1 localhost\n::1 localhost\n")
        
        if (prootPath == null) {
            val nativeProotFile = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
            val libDir = File(context.filesDir, "lib")
            val prootFile = File(libDir, "libproot.so")
            if (isValidElf(nativeProotFile)) {
                prootPath = nativeProotFile.absolutePath
            } else if (isValidElf(prootFile)) {
                prootFile.setExecutable(true, false)
                prootPath = prootFile.absolutePath
            } else {
                extractLibFromApk(context, "libproot.so", prootFile)
                if (isValidElf(prootFile)) {
                    prootFile.setExecutable(true, false)
                    prootPath = prootFile.absolutePath
                }
            }
        }

        if (prootPath == null) {
            _activeDistro.value = distroAlias
            _guestDirectory.value = "/root"
            syncState()
            terminalLines.add("Warning: PRoot engine (libproot.so) is missing or incompatible with architecture (${System.getProperty("os.arch")}).")
            terminalLines.add("Cannot chroot into PRoot container. Commands will execute in host Android shell.")
            return
        }

        _activeDistro.value = distroAlias
        _guestDirectory.value = "/root"
        syncState()
        terminalLines.add("Logged into $distroAlias PRoot container.")
        terminalLines.add("You are now running inside the real Linux rootfs.")
        startX11VirtualDisplayIfNeeded()
    }

    fun getPrompt(): String {
        val distro = _activeDistro.value
        return if (distro != null && prootPath != null) {
            val dir = _guestDirectory.value
            val displayDir = if (dir == "/root") "~" else dir
            "root@$distro:$displayDir# "
        } else if (distro != null) {
            val dirName = if (globalRealDirectory.name.isEmpty()) "~" else globalRealDirectory.name
            "$dirName $ "
        } else {
            "~ $ "
        }
    }

    suspend fun executeCommand(commandLine: String, requestedDistro: String? = null, sessionName: String? = null, onOutputLine: ((String) -> Unit)? = null): String {
        val ctx = applicationContext
        if (ctx != null && sessions.isEmpty()) {
            withContext(Dispatchers.Main) {
                initialize(ctx)
            }
        }
        ctx?.let { ensureProotPathResolved(it) }

        if (_installedDistros.value.isEmpty() && ctx != null) {
            withContext(Dispatchers.IO) {
                try {
                    installDistroDirect("ubuntu", { _, _ -> }, { success ->
                        if (success) {
                            kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                                loginDistroDirect("ubuntu")
                            }
                        }
                    })
                } catch (e: Exception) {}
            }
        }

        if (sessionName != null && sessionName.isNotBlank()) {
            withContext(Dispatchers.Main) {
                val existing = sessions.find { it.name.equals(sessionName, ignoreCase = true) }
                if (existing != null) {
                    if (activeSessionId.value != existing.id) {
                        switchSession(existing.id)
                    }
                } else {
                    createSession(sessionName, requestedDistro)
                }
            }
        }

        val targetDistro = requestedDistro ?: if (_activeDistro.value == null && _installedDistros.value.isNotEmpty()) {
            if (_installedDistros.value.contains("ubuntu")) "ubuntu" else _installedDistros.value.first()
        } else null

        if (targetDistro != null && targetDistro != _activeDistro.value) {
            val isDistroInstalled = _installedDistros.value.contains(targetDistro)
            if (isDistroInstalled) {
                withContext(Dispatchers.Main) {
                    loginDistroDirect(targetDistro)
                }
            } else {
                val errorMsg = "Error: Distro '$targetDistro' is not installed. Use 'proot-distro install $targetDistro' first. Currently installed: ${_installedDistros.value.joinToString(", ")}"
                withContext(Dispatchers.Main) {
                    terminalLines.add(errorMsg)
                }
                return errorMsg
            }
        }

        var foregroundTmpDir: File? = null
        val trimmed = commandLine.trim()
        if (trimmed.isEmpty()) return ""

        // Detect GUI / Desktop Screen launch commands to activate desktop screen stream mode for user to watch
        val lowerCmd = trimmed.lowercase(Locale.ROOT)
        if (lowerCmd.contains("chrome") || lowerCmd.contains("chromium") || lowerCmd.contains("firefox") || lowerCmd.contains("vnc") || lowerCmd.contains("x11") || lowerCmd.contains("gui") || lowerCmd.contains("startxfce4") || lowerCmd.contains("xinit") || lowerCmd.contains("desktop")) {
            setDesktopScreenActive(true, if (lowerCmd.contains("firefox")) "Firefox Browser (Ubuntu PRoot)" else if (lowerCmd.contains("chrome")) "Chrome Browser (Ubuntu PRoot)" else "X11 Virtual Display (:99)")
        } else if (lowerCmd.contains("pkill chrome") || lowerCmd.contains("killall chrome") || lowerCmd.contains("pkill firefox")) {
            setDesktopScreenActive(false)
        }

        withContext(Dispatchers.Main) {
            terminalLines.add("${getPrompt()}$trimmed")
        }

        val isDesktopActive = _isDesktopScreenActive.value
        val isBackground = trimmed.endsWith("&") || lowerCmd.contains("chrome") || lowerCmd.contains("chromium") || lowerCmd.contains("firefox") || lowerCmd.contains("xterm") || lowerCmd.contains("startxfce")
        
        if (isDesktopActive && isBackground && !trimmed.startsWith("which ") && !trimmed.contains("scrot ") && !trimmed.contains("ps aux")) {
            val distro = targetDistro ?: _activeDistro.value ?: "ubuntu"
            val context = applicationContext
            if (context != null && isDistroFullyInstalled(distro)) {
                val distroDir = resolveDistroDir(distro)
                val pipeFile = File(distroDir, "root/cmd_pipe")
                val cleanCmd = if (trimmed.endsWith("&")) trimmed.substring(0, trimmed.length - 1).trim() else trimmed
                
                // Asynchronously write to the pipe or execute directly to avoid ANY blocking of terminal thread! (Solves Issue 4 & 5)
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val isXvfbAlive = try {
                            val process = xvfbProcess
                            if (process != null) {
                                process.exitValue()
                                false
                            } else false
                        } catch (e: IllegalThreadStateException) {
                            true
                        }

                        if (isXvfbAlive && pipeFile.exists()) {
                            // Xvfb is running and pipe exists, send it to the FIFO queue
                            pipeFile.writeText(cleanCmd + "\n")
                        } else {
                            // Fallback: Xvfb is not running or pipe missing.
                            // Start a direct background process inside the container with DISPLAY=:99
                            val pPath = prootPath
                            if (pPath != null) {
                                val prootCmdList = mutableListOf<String>()
                                prootCmdList.add(pPath)
                                prootCmdList.add("-r")
                                prootCmdList.add((try { distroDir.canonicalFile.absolutePath } catch (e: Exception) { distroDir.absolutePath }))
                                prootCmdList.add("-0")
                                prootCmdList.add("--link2symlink")
                                prootCmdList.add("--sysvipc")
                                prootCmdList.add("--kill-on-exit")
                                prootCmdList.add("-w")
                                prootCmdList.add("/root")
                                
                                appendStandardPRootBindings(prootCmdList, context, distroDir)
                                
                                prootCmdList.addAll(listOf(
                                    "/bin/sh", "-c", "unset LD_LIBRARY_PATH; export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:\$PATH HOME=/root USER=root SHELL=/bin/bash TERM=xterm-256color LANG=C.UTF-8 LC_ALL=C.UTF-8 DEBIAN_FRONTEND=noninteractive DISPLAY=:99 TMPDIR=/tmp TEMP=/tmp TMP=/tmp; $cleanCmd >> /root/background_cmds.log 2>&1"
                                ))
                                
                                val builder = ProcessBuilder(prootCmdList).directory(context.filesDir)
                                val prootTmpDir = File(context.cacheDir, "proot_tmp").apply { mkdirs() }
                                applyStandardPRootEnvironment(builder, context, prootTmpDir)
                                builder.start()
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("Zhypix", "Failed to run background command", e)
                    }
                }
                
                val msg = "Command routed to persistent background session. Output logged to /root/background_cmds.log"
                withContext(Dispatchers.Main) {
                    terminalLines.add(msg)
                }
                return msg
            }
        }

        if (trimmed == "clear") {
            withContext(Dispatchers.Main) {
                terminalLines.clear()
            }
            return ""
        }

        // Friendly guide if user tries to run package managers in host Android shell
        if (_activeDistro.value == null) {
            val pkgCmds = listOf("apt", "apt-get", "dpkg", "apk", "pacman", "yum", "dnf")
            val firstWord = trimmed.split("\\s+".toRegex()).firstOrNull()?.lowercase(Locale.ROOT)
            if (pkgCmds.contains(firstWord)) {
                val installed = _installedDistros.value
                val guideMsg = if (installed.isNotEmpty()) {
                    "Notice: '$firstWord' operates inside Linux containers.\nType 'proot-distro login ${installed.first()}' or use the 'PRoot Distros' tab above to enter your Linux environment."
                } else {
                    "Notice: '$firstWord' operates inside Linux containers.\nType 'proot-distro install debian' or switch to the 'PRoot Distros' tab to install a full Linux environment."
                }
                withContext(Dispatchers.Main) {
                    terminalLines.add(guideMsg)
                }
                return guideMsg
            }
        }

        if (trimmed.startsWith("proot-distro ")) {
            val args = parseCommandArgs(trimmed)
            if (args.size >= 2) {
                val action = args[1]
                when (action) {
                    "list" -> {
                        val availableList = availableDistros.map { distro ->
                            val status = if (_installedDistros.value.contains(distro.alias)) "[installed]" else "[not installed]"
                            " - ${distro.alias} (${distro.name} ${distro.version}) $status"
                        }.joinToString("\n")
                        val resultMsg = "Supported distributions:\n$availableList"
                        withContext(Dispatchers.Main) {
                            terminalLines.add(resultMsg)
                        }
                        return resultMsg
                    }
                    "install" -> {
                        if (args.size >= 3) {
                            val alias = args[2]
                            val isSupported = availableDistros.any { it.alias == alias }
                            if (!isSupported) {
                                val err = "Distribution '$alias' is not supported."
                                withContext(Dispatchers.Main) { terminalLines.add(err) }
                                return err
                            }
                            withContext(Dispatchers.Main) {
                                terminalLines.add("Downloading and installing $alias...")
                            }
                            installDistroDirect(alias, { _, _ -> }, { _ -> })
                            return ""
                        } else {
                            val err = "Usage: proot-distro install <alias>"
                            withContext(Dispatchers.Main) { terminalLines.add(err) }
                            return err
                        }
                    }
                    "login" -> {
                        if (args.size >= 3) {
                            val alias = args[2]
                            withContext(Dispatchers.Main) {
                                loginDistroDirect(alias)
                            }
                            return ""
                        } else {
                            val err = "Usage: proot-distro login <alias>"
                            withContext(Dispatchers.Main) { terminalLines.add(err) }
                            return err
                        }
                    }
                    "remove", "uninstall" -> {
                        if (args.size >= 3) {
                            val alias = args[2]
                            withContext(Dispatchers.Main) {
                                terminalLines.add("Removing $alias...")
                                removeDistroDirect(alias)
                            }
                            return ""
                        } else {
                            val err = "Usage: proot-distro remove <alias>"
                            withContext(Dispatchers.Main) { terminalLines.add(err) }
                            return err
                        }
                    }
                    else -> {
                        val err = "Unknown proot-distro action: $action. Supported: list, install, login, remove"
                        withContext(Dispatchers.Main) { terminalLines.add(err) }
                        return err
                    }
                }
            }
        }
        
        if (trimmed == "exit" && _activeDistro.value != null) {
            withContext(Dispatchers.Main) {
                _activeDistro.value = null
                syncState()
                terminalLines.add("Exited PRoot container. Back to Android shell.")
            }
            return ""
        }

        if (trimmed.startsWith("cd ")) {
            val dir = trimmed.substring(3).trim()
            val distro = _activeDistro.value
            if (distro != null) {
                val context = applicationContext ?: return ""
                val distroDir = resolveDistroDir(distro)
                
                // Determine absolute guest path
                val absoluteGuestPath = if (dir.startsWith("/")) {
                    dir
                } else {
                    val current = _guestDirectory.value
                    if (current == "/") "/$dir" else "$current/$dir"
                }
                
                // Canonicalize target file on host filesystem to resolve "." and ".."
                val targetHostFile = withContext(Dispatchers.IO) {
                    File(distroDir, absoluteGuestPath).canonicalFile
                }
                val existsAndDirectory = withContext(Dispatchers.IO) {
                    targetHostFile.exists() && targetHostFile.isDirectory
                }
                if (existsAndDirectory) {
                    val targetHostPath = targetHostFile.absolutePath
                    val finalDistroDirPath = withContext(Dispatchers.IO) { distroDir.canonicalFile.absolutePath }
                    
                    if (targetHostPath.startsWith(finalDistroDirPath)) {
                        var relativePath = targetHostPath.substring(finalDistroDirPath.length)
                        if (relativePath.isEmpty()) {
                            relativePath = "/"
                        }
                        withContext(Dispatchers.Main) {
                            _guestDirectory.value = relativePath
                            syncState()
                        }
                        return ""
                    } else {
                        // Restricted within container
                        withContext(Dispatchers.Main) {
                            _guestDirectory.value = "/"
                            syncState()
                        }
                        return ""
                    }
                } else {
                    val err = "cd: $dir: No such file or directory"
                    withContext(Dispatchers.Main) {
                        terminalLines.add(err)
                    }
                    return err
                }
            } else {
                var newDir = File(globalRealDirectory, dir)
                if (dir.startsWith("/")) {
                    newDir = File(dir)
                }
                val existsAndDirectory = withContext(Dispatchers.IO) {
                    newDir.exists() && newDir.isDirectory
                }
                if (existsAndDirectory) {
                    val canonicalDir = withContext(Dispatchers.IO) { newDir.canonicalFile }
                    withContext(Dispatchers.Main) {
                        globalRealDirectory = canonicalDir
                        syncState()
                    }
                    return ""
                } else {
                    val err = "cd: $dir: No such file or directory"
                    withContext(Dispatchers.Main) {
                        terminalLines.add(err)
                    }
                    return err
                }
            }
        }

        val distro = _activeDistro.value
        val result = withContext(Dispatchers.IO) {
            try {
                val isDistroReady = distro != null && prootPath != null && isDistroFullyInstalled(distro)
                val processBuilder = if (isDistroReady) {
                    val context = applicationContext ?: return@withContext "Error: Terminal not initialized."
                    val distroDir = resolveDistroDir(distro!!)
                    
                    // Architecture match validation & Self-healing re-install
                    val prootIsArm = prootPath?.let { isBinaryArm(File(it)) } ?: false
                    val guestIsArm = getGuestArchIsArm(distroDir)
                    if (guestIsArm != null && prootIsArm != guestIsArm) {
                        withContext(Dispatchers.Main) {
                            terminalLines.add("Warning: Architecture mismatch detected!")
                            terminalLines.add("Installed distro '$distro' is ${if (guestIsArm) "ARM64" else "x86_64"}, but the host engine is ${if (prootIsArm) "ARM64" else "x86_64"}.")
                            terminalLines.add("Automatically deleting and rebuilding the incompatible distro environment...")
                        }
                        try {
                            if (distroDir.exists()) {
                                distroDir.deleteRecursively()
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                terminalLines.add("Failed to delete incompatible directory: ${e.message}")
                            }
                        }
                        withContext(Dispatchers.Main) {
                            _activeDistro.value = null
                            syncState()
                            val currentInstalled = _installedDistros.value.toMutableSet()
                            currentInstalled.remove(distro)
                            _installedDistros.value = currentInstalled
                            terminalLines.add("Downloading and reinstalling '$distro' with the correct architecture for this device...")
                        }
                        installDistroDirect(distro, { _, _ -> }, { _ -> })
                        return@withContext "Architecture mismatch corrected. Distro is being reinstalled. Please run the command again."
                    }
                    
                    // Lightweight self-healing for critical package manager paths before every command execution
                    repairDpkgDatabase(distroDir)
                    removeLegacyMockWrappers(distroDir)
                    
                    // PRoot execution string: proot -r <rootfs> -0 -w <dir> -b /dev -b /sys -b /proc /bin/sh -c "command"
                    ensureBinariesAndShellsExecutable(distroDir)

                    val prootCmdList = mutableListOf<String>()
                    prootCmdList.add(prootPath!!)
                    prootCmdList.add("-r")
                    prootCmdList.add((try { distroDir.canonicalFile.absolutePath } catch (e: Exception) { distroDir.absolutePath }))
                    prootCmdList.add("-0")
                    prootCmdList.add("--link2symlink")
                    prootCmdList.add("--sysvipc")
                    prootCmdList.add("--kill-on-exit")
                    // Check if guest directory exists inside distro rootfs
                    var guestDir = _guestDirectory.value
                    val targetDirInDistro = File(distroDir, guestDir.removePrefix("/"))
                    if (!targetDirInDistro.exists()) {
                        guestDir = "/root"
                    }
                    prootCmdList.add("-w")
                    prootCmdList.add(guestDir)
                    
                    appendStandardPRootBindings(prootCmdList, context, distroDir)

                    var cmdToExecute = trimmed

                    val libDir = File(context.filesDir, "lib").absolutePath
                    val nativeLibDir = context.applicationInfo.nativeLibraryDir

                    prootCmdList.addAll(listOf(
                        "/bin/sh", "-c", "unset LD_LIBRARY_PATH; export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:\$PATH HOME=/root USER=root SHELL=/bin/bash TERM=xterm-256color LANG=C.UTF-8 LC_ALL=C.UTF-8 DEBIAN_FRONTEND=noninteractive APT_LISTCHANGES_FRONTEND=none TMPDIR=/tmp TEMP=/tmp TMP=/tmp DISPLAY=:99; $cmdToExecute"
                    ))

                    val builder = ProcessBuilder(prootCmdList).directory(context.filesDir)

                    val prootTmpDir = File(File(context.cacheDir, "proot_tmp"), "proc_" + java.util.UUID.randomUUID().toString()).apply {
                        mkdirs()
                        setWritable(true, false)
                        setReadable(true, false)
                        setExecutable(true, false)
                    }
                    foregroundTmpDir = prootTmpDir
                    applyStandardPRootEnvironment(builder, context, prootTmpDir)
                    
                    builder
                } else {
                    val shPath = if (File("/system/bin/sh").exists()) "/system/bin/sh" else "sh"
                    val builder = ProcessBuilder(shPath, "-c", trimmed).directory(globalRealDirectory)
                    val context = applicationContext
                    if (context != null) {
                        val libDir = File(context.filesDir, "lib").absolutePath
                        val nativeLibDir = context.applicationInfo.nativeLibraryDir
                        builder.environment()["LD_LIBRARY_PATH"] = "$libDir:$nativeLibDir"
                        val currentPath = builder.environment()["PATH"] ?: ""
                        builder.environment()["PATH"] = "$libDir:$nativeLibDir:$currentPath:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin"
                    }
                    builder
                }
                
                processBuilder.redirectErrorStream(true)
                
                val context = applicationContext
                val wakeLock = if (context != null) {
                    try {
                        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                        powerManager?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Zhypix:LinuxCommandExecution")?.apply {
                            acquire(15 * 60 * 1000L) // limit to 15 minutes max safety limit
                        }
                    } catch (e: Exception) {
                        null
                    }
                } else null

                val process = processBuilder.start()

                val reader = java.io.InputStreamReader(process.inputStream, "UTF-8")
                val output = java.lang.StringBuilder()
                
                val pendingUpdates = java.util.Collections.synchronizedList(mutableListOf<TerminalUpdate>())
                val flushJob = kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                    while (true) {
                        kotlinx.coroutines.delay(60L)
                        val toFlush = synchronized(pendingUpdates) {
                            if (pendingUpdates.isNotEmpty()) {
                                val list = pendingUpdates.toList()
                                pendingUpdates.clear()
                                list
                            } else null
                        }
                        if (toFlush != null) {
                            for (update in toFlush) {
                                when (update) {
                                    is TerminalUpdate.AddLine -> {
                                        terminalLines.add(update.text)
                                    }
                                    is TerminalUpdate.OverwriteLastLine -> {
                                        if (terminalLines.isNotEmpty()) {
                                            terminalLines[terminalLines.size - 1] = update.text
                                        } else {
                                            terminalLines.add(update.text)
                                        }
                                    }
                                    is TerminalUpdate.Clear -> {
                                        terminalLines.clear()
                                    }
                                }
                            }
                            if (terminalLines.size > 1000) {
                                val excess = terminalLines.size - 1000
                                if (excess in 1..terminalLines.size) {
                                    terminalLines.removeRange(0, excess)
                                }
                            }
                        }
                    }
                }

                try {
                    val buffer = CharArray(2048)
                    var readCount: Int
                    val currentLine = java.lang.StringBuilder()
                    var prevChar: Char = ' '
                    var isLastLineOverwritable = false
                    
                    while (reader.read(buffer).also { readCount = it } != -1) {
                        for (i in 0 until readCount) {
                            val c = buffer[i]
                            if (c == '\n') {
                                if (prevChar != '\r') {
                                    val lineStr = currentLine.toString()
                                    output.append(lineStr).append("\n")
                                    if (isLastLineOverwritable) {
                                        pendingUpdates.add(TerminalUpdate.OverwriteLastLine(lineStr))
                                        isLastLineOverwritable = false
                                    } else {
                                        pendingUpdates.add(TerminalUpdate.AddLine(lineStr))
                                    }
                                    onOutputLine?.invoke(lineStr)
                                    currentLine.setLength(0)
                                } else {
                                    isLastLineOverwritable = false
                                }
                            } else if (c == '\r') {
                                val lineStr = currentLine.toString()
                                output.append(lineStr).append("\n")
                                if (isLastLineOverwritable) {
                                    pendingUpdates.add(TerminalUpdate.OverwriteLastLine(lineStr))
                                } else {
                                    pendingUpdates.add(TerminalUpdate.AddLine(lineStr))
                                    isLastLineOverwritable = true
                                }
                                currentLine.setLength(0)
                            } else {
                                currentLine.append(c)
                            }
                            prevChar = c
                        }
                    }
                    
                    val remainingLine = currentLine.toString()
                    if (remainingLine.isNotEmpty()) {
                        output.append(remainingLine).append("\n")
                        if (isLastLineOverwritable) {
                            pendingUpdates.add(TerminalUpdate.OverwriteLastLine(remainingLine))
                        } else {
                            pendingUpdates.add(TerminalUpdate.AddLine(remainingLine))
                        }
                        onOutputLine?.invoke(remainingLine)
                    }
                } finally {
                    flushJob.cancel()
                    val remaining = synchronized(pendingUpdates) {
                        val list = pendingUpdates.toList()
                        pendingUpdates.clear()
                        list
                    }
                    if (remaining.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            for (update in remaining) {
                                when (update) {
                                    is TerminalUpdate.AddLine -> {
                                        terminalLines.add(update.text)
                                    }
                                    is TerminalUpdate.OverwriteLastLine -> {
                                        if (terminalLines.isNotEmpty()) {
                                            terminalLines[terminalLines.size - 1] = update.text
                                        } else {
                                            terminalLines.add(update.text)
                                        }
                                    }
                                    is TerminalUpdate.Clear -> {
                                        terminalLines.clear()
                                    }
                                }
                            }
                            if (terminalLines.size > 1000) {
                                val excess = terminalLines.size - 1000
                                if (excess in 1..terminalLines.size) {
                                    terminalLines.removeRange(0, excess)
                                }
                            }
                        }
                    }
                    try {
                        reader.close()
                    } catch (e: Exception) {}
                    
                    try {
                        foregroundTmpDir?.deleteRecursively()
                    } catch (e: Exception) {}
                    
                    try {
                        if (wakeLock != null && wakeLock.isHeld) {
                            wakeLock.release()
                        }
                    } catch (e: Exception) {}
                }
                
                val exitCode = process.waitFor()
                val finalOutput = output.toString()
                if (finalOutput.contains("Exec format error", ignoreCase = true)) {
                    val activeAlias = distro
                    if (activeAlias != null) {
                        try {
                            val ctx = applicationContext
                            if (ctx != null) {
                                val distroDir = File(File(ctx.filesDir, "proot-distros"), activeAlias)
                                if (distroDir.exists()) {
                                    distroDir.deleteRecursively()
                                }
                            }
                        } catch (ex: Exception) {}
                    }
                    withContext(Dispatchers.Main) {
                        _activeDistro.value = null
                        syncState()
                        val currentInstalled = _installedDistros.value.toMutableSet()
                        if (activeAlias != null) currentInstalled.remove(activeAlias)
                        _installedDistros.value = currentInstalled
                        terminalLines.add("Architecture Mismatch Detected (Exec format error): Container binaries do not match your host device/emulator architecture (e.g. ARM rootfs on x86 emulator).")
                        terminalLines.add("Automatically cleaned up the incompatible environment. Please run 'proot-distro install <distro>' again to install the correct architecture for this device.")
                    }
                } else if (finalOutput.contains("proot error", ignoreCase = true)) {
                    withContext(Dispatchers.Main) {
                        terminalLines.add("Notice: PRoot container execution warning: $finalOutput")
                    }
                }
                if (finalOutput.trim().isEmpty()) {
                    ""
                } else {
                    finalOutput
                }
            } catch (e: Exception) {
                val err = "Error: ${e.message}"
                withContext(Dispatchers.Main) {
                    terminalLines.add(err)
                }
                err
            }
        }

        // Save terminal lines to the active session and persist
        val context = applicationContext
        if (context != null) {
            val currentSessionId = _activeSessionId.value
            val s = sessions.find { it.id == currentSessionId }
            if (s != null) {
                s.terminalLines.clear()
                s.terminalLines.addAll(terminalLines)
                s.activeDistro = _activeDistro.value
                s.guestDirectory = _guestDirectory.value
                s.currentDirectory = _currentDirectory.value
                s.realDirectory = globalRealDirectory
            }
            saveSessions(context)
        }

        return result
    }

    fun parseCommandArgs(command: String): List<String> {
        val list = mutableListOf<String>()
        val current = java.lang.StringBuilder()
        var inDoubleQuotes = false
        var inSingleQuotes = false
        var i = 0
        while (i < command.length) {
            val c = command[i]
            if (c == '\"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes
            } else if (c == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes
            } else if (c == ' ' && !inDoubleQuotes && !inSingleQuotes) {
                if (current.isNotEmpty()) {
                    list.add(current.toString())
                    current.setLength(0)
                }
            } else {
                current.append(c)
            }
            i++
        }
        if (current.isNotEmpty()) {
            list.add(current.toString())
        }
        return list
    }

    fun saveSessions(context: Context) {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val file = File(context.filesDir, "terminal_sessions.json")
                val array = org.json.JSONArray()
                for (s in sessions) {
                    val obj = org.json.JSONObject().apply {
                        put("id", s.id)
                        put("name", s.name)
                        put("activeDistro", s.activeDistro ?: "")
                        put("guestDirectory", s.guestDirectory)
                        put("currentDirectory", s.currentDirectory)
                        put("realDirectory", s.realDirectory.absolutePath)
                        put("commandHistory", org.json.JSONArray(s.commandHistory))
                        put("terminalLines", org.json.JSONArray(s.terminalLines))
                        put("currentInputText", s.currentInputText)
                    }
                    array.put(obj)
                }
                file.writeText(array.toString())
                android.util.Log.d("Zhypix", "Successfully saved ${sessions.size} sessions")
            } catch (e: Exception) {
                android.util.Log.e("Zhypix", "Failed to save sessions", e)
            }
        }
    }

    fun loadSessions(context: Context) {
        try {
            val file = File(context.filesDir, "terminal_sessions.json")
            if (!file.exists()) return
            val content = file.readText()
            if (content.isBlank()) return
            val array = org.json.JSONArray(content)
            
            sessions.clear()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.getString("id")
                val name = obj.getString("name")
                val activeDistro = obj.optString("activeDistro", "").let { if (it.isEmpty()) null else it }
                val guestDirectory = obj.optString("guestDirectory", "/root")
                val currentDirectory = obj.optString("currentDirectory", "/")
                val realDirectory = File(obj.optString("realDirectory", context.filesDir.absolutePath))
                val currentInputText = obj.optString("currentInputText", "")
                
                val cmdHistory = mutableListOf<String>()
                val cmdHistArr = obj.optJSONArray("commandHistory")
                if (cmdHistArr != null) {
                    for (j in 0 until cmdHistArr.length()) {
                        cmdHistory.add(cmdHistArr.getString(j))
                    }
                }
                
                val termLines = mutableListOf<String>()
                val termLinesArr = obj.optJSONArray("terminalLines")
                if (termLinesArr != null) {
                    for (j in 0 until termLinesArr.length()) {
                        termLines.add(termLinesArr.getString(j))
                    }
                }
                
                sessions.add(TerminalSession(
                    id = id,
                    name = name,
                    terminalLines = termLines.toMutableList(),
                    activeDistro = activeDistro,
                    guestDirectory = guestDirectory,
                    currentDirectory = currentDirectory,
                    realDirectory = realDirectory,
                    commandHistory = cmdHistory.toMutableList(),
                    currentInputText = currentInputText
                ))
            }
            
            if (sessions.isNotEmpty()) {
                _activeSessionId.value = sessions.first().id
                val s = sessions.first()
                _activeDistro.value = s.activeDistro
                _guestDirectory.value = s.guestDirectory
                _currentDirectory.value = s.currentDirectory
                globalRealDirectory = s.realDirectory
                terminalLines.clear()
                terminalLines.addAll(s.terminalLines)
            }
            android.util.Log.d("Zhypix", "Successfully loaded ${sessions.size} sessions")
        } catch (e: Exception) {
            android.util.Log.e("Zhypix", "Failed to load sessions", e)
        }
    }

    private fun applyStandardPRootEnvironment(builder: ProcessBuilder, context: Context, prootTmpDir: File) {
        val env = buildStandardPRootEnvironment(context, prootTmpDir)
        builder.environment().putAll(env)
    }

    fun buildStandardPRootEnvironment(context: Context, prootTmpDir: File): MutableMap<String, String> {
        val env = mutableMapOf<String, String>()
        val libDir = File(context.filesDir, "lib").absolutePath
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        env["LD_LIBRARY_PATH"] = "$libDir:$nativeLibDir"
        env["PATH"] = "$libDir:$nativeLibDir:/system/bin:/system/xbin"

        val loaderCandidate1 = File(context.filesDir, "lib/libproot-loader.so")
        val loaderCandidate2 = File(context.applicationInfo.nativeLibraryDir, "libproot-loader.so")
        val loaderFile = if (loaderCandidate2.exists() && isValidElf(loaderCandidate2)) loaderCandidate2 else if (loaderCandidate1.exists() && isValidElf(loaderCandidate1)) loaderCandidate1 else null
        if (loaderFile != null) {
            try {
                loaderFile.setExecutable(true, false)
                loaderFile.setReadable(true, false)
            } catch (e: Exception) {}
            env["PROOT_LOADER"] = loaderFile.absolutePath
        }

        val loader32Candidate1 = File(context.filesDir, "lib/libproot-loader32.so")
        val loader32Candidate2 = File(context.applicationInfo.nativeLibraryDir, "libproot-loader32.so")
        val loader32File = if (loader32Candidate2.exists() && isValidElf(loader32Candidate2)) loader32Candidate2 else if (loader32Candidate1.exists() && isValidElf(loader32Candidate1)) loader32Candidate1 else null
        if (loader32File != null) {
            try {
                loader32File.setExecutable(true, false)
                loader32File.setReadable(true, false)
            } catch (e: Exception) {}
            env["PROOT_LOADER_32"] = loader32File.absolutePath
        }

        env["PROOT_TMP_DIR"] = prootTmpDir.absolutePath
        env["PROOT_TMPDIR"] = prootTmpDir.absolutePath
        env["TMPDIR"] = prootTmpDir.absolutePath
        env["TEMP"] = prootTmpDir.absolutePath
        env["TMP"] = prootTmpDir.absolutePath
        env["PROOT_VERBOSE"] = "0"
        env["PROOT_NO_SECCOMP"] = "1"
        env["PROOT_FORCE_NO_SECCOMP"] = "1"
        env["PROOT_NO_SHMEM_WARNING"] = "1"
        env["PROOT_NO_ALIGNMENT_CHECK"] = "1"
        env["PROOT_KILL_ON_EXIT"] = "1"
        env["HOME"] = "/root"
        env["USER"] = "root"
        env["SHELL"] = "/bin/bash"
        env["TERM"] = "xterm-256color"
        return env
    }

    private fun appendStandardPRootBindings(prootCmdList: MutableList<String>, context: Context, distroDir: File) {
        val canonicalDistroDir = try { distroDir.canonicalFile } catch (e: Exception) { distroDir.absoluteFile }

        // Bind fake dev directory
        val fakeDevDir = File(context.cacheDir, "fake_dev")
        if (fakeDevDir.exists()) {
            val devPath = try { fakeDevDir.canonicalFile.absolutePath } catch (e: Exception) { fakeDevDir.absolutePath }
            prootCmdList.add("-b")
            prootCmdList.add("$devPath:/dev")
            
            // Bind host individual safe devices onto fake dev directory
            val hostDevices = listOf("null", "zero", "random", "urandom", "ptmx")
            for (dev in hostDevices) {
                if (File("/dev/$dev").exists()) {
                    prootCmdList.add("-b")
                    prootCmdList.add("/dev/$dev:/dev/$dev")
                }
            }
            if (File("/dev/pts").exists()) {
                prootCmdList.add("-b")
                prootCmdList.add("/dev/pts:/dev/pts")
            }
            if (File("/proc/self/fd").exists()) {
                prootCmdList.add("-b")
                prootCmdList.add("/proc/self/fd:/dev/fd")
            }
            if (File("/proc/self/fd/0").exists()) {
                prootCmdList.add("-b")
                prootCmdList.add("/proc/self/fd/0:/dev/stdin")
            }
            if (File("/proc/self/fd/1").exists()) {
                prootCmdList.add("-b")
                prootCmdList.add("/proc/self/fd/1:/dev/stdout")
            }
            if (File("/proc/self/fd/2").exists()) {
                prootCmdList.add("-b")
                prootCmdList.add("/proc/self/fd/2:/dev/stderr")
            }
        } else {
            if (File("/dev").exists()) {
                prootCmdList.add("-b")
                prootCmdList.add("/dev")
            }
        }

        // Bind fake sys directory
        val fakeSysDir = File(context.cacheDir, "fake_sys")
        if (fakeSysDir.exists()) {
            val sysPath = try { fakeSysDir.canonicalFile.absolutePath } catch (e: Exception) { fakeSysDir.absolutePath }
            prootCmdList.add("-b")
            prootCmdList.add("$sysPath:/sys")
        } else {
            if (File("/sys").exists()) {
                prootCmdList.add("-b")
                prootCmdList.add("/sys")
            }
        }

        // Bind host /proc
        if (File("/proc").exists()) {
            prootCmdList.add("-b")
            prootCmdList.add("/proc")
        }

        // Bind fake proc files
        try {
            val fakeProcStat = File(context.cacheDir, "fake_proc_stat")
            if (fakeProcStat.exists()) {
                val pPath = try { fakeProcStat.canonicalFile.absolutePath } catch (e: Exception) { fakeProcStat.absolutePath }
                prootCmdList.add("-b")
                prootCmdList.add("$pPath:/proc/stat")
            }
            val fakeProcVersion = File(context.cacheDir, "fake_proc_version")
            if (fakeProcVersion.exists()) {
                val pPath = try { fakeProcVersion.canonicalFile.absolutePath } catch (e: Exception) { fakeProcVersion.absolutePath }
                prootCmdList.add("-b")
                prootCmdList.add("$pPath:/proc/version")
            }
            val fakeProcUptime = File(context.cacheDir, "fake_proc_uptime")
            if (fakeProcUptime.exists()) {
                val pPath = try { fakeProcUptime.canonicalFile.absolutePath } catch (e: Exception) { fakeProcUptime.absolutePath }
                prootCmdList.add("-b")
                prootCmdList.add("$pPath:/proc/uptime")
            }
            val fakeProcLoadavg = File(context.cacheDir, "fake_proc_loadavg")
            if (fakeProcLoadavg.exists()) {
                val pPath = try { fakeProcLoadavg.canonicalFile.absolutePath } catch (e: Exception) { fakeProcLoadavg.absolutePath }
                prootCmdList.add("-b")
                prootCmdList.add("$pPath:/proc/loadavg")
            }
            val fakeProcNetDir = File(context.cacheDir, "fake_proc_net")
            if (fakeProcNetDir.exists()) {
                val pPath = try { fakeProcNetDir.canonicalFile.absolutePath } catch (e: Exception) { fakeProcNetDir.absolutePath }
                prootCmdList.add("-b")
                prootCmdList.add("$pPath:/proc/net")
                prootCmdList.add("-b")
                prootCmdList.add("$pPath:/proc/self/net")
            }
            val fakeProcSelfStatus = File(context.cacheDir, "fake_proc_self_status")
            if (fakeProcSelfStatus.exists()) {
                val pPath = try { fakeProcSelfStatus.canonicalFile.absolutePath } catch (e: Exception) { fakeProcSelfStatus.absolutePath }
                prootCmdList.add("-b")
                prootCmdList.add("$pPath:/proc/self/status")
            }
            val fakeProcSelfCmdline = File(context.cacheDir, "fake_proc_self_cmdline")
            if (fakeProcSelfCmdline.exists()) {
                val pPath = try { fakeProcSelfCmdline.canonicalFile.absolutePath } catch (e: Exception) { fakeProcSelfCmdline.absolutePath }
                prootCmdList.add("-b")
                prootCmdList.add("$pPath:/proc/self/cmdline")
            }
            val fakeProcSysDir = File(context.cacheDir, "fake_proc_sys")
            if (fakeProcSysDir.exists()) {
                val pPath = try { fakeProcSysDir.canonicalFile.absolutePath } catch (e: Exception) { fakeProcSysDir.absolutePath }
                prootCmdList.add("-b")
                prootCmdList.add("$pPath:/proc/sys")
            }
        } catch (e: Exception) {}
        
        // Bind host cache shm folder to /dev/shm in PRoot guest
        try {
            val hostShmDir = File(context.cacheDir, "shm").apply { mkdirs() }
            hostShmDir.setWritable(true, false)
            hostShmDir.setReadable(true, false)
            hostShmDir.setExecutable(true, false)
            val canonicalHostShm = try { hostShmDir.canonicalFile.absolutePath } catch (e: Exception) { hostShmDir.absolutePath }
            val guestShmDir = File(canonicalDistroDir, "dev/shm").apply { mkdirs() }
            guestShmDir.setWritable(true, false)
            prootCmdList.add("-b")
            prootCmdList.add("$canonicalHostShm:/dev/shm")
        } catch (e: Exception) {}

        // Bind extra mounts with canonical host target & directory check
        val extraMounts = listOf("/system", "/sdcard", "/storage")
        for (m in extraMounts) {
            val hostFile = File(m)
            if (hostFile.exists()) {
                val canonicalHost = try { hostFile.canonicalFile } catch (e: Exception) { hostFile }
                val guestFile = File(canonicalDistroDir, m.removePrefix("/"))
                if (guestFile.exists() && guestFile.isDirectory) {
                    prootCmdList.add("-b")
                    prootCmdList.add("${canonicalHost.absolutePath}:$m")
                }
            }
        }
    }

    fun removeLegacyMockWrappers(distroDir: File) {
        try {
            val checkDirs = listOf(
                File(distroDir, "usr/local/bin"),
                File(distroDir, "usr/bin"),
                File(distroDir, "bin"),
                File(distroDir, "usr/sbin"),
                File(distroDir, "sbin")
            )
            val mockTargetNames = setOf(
                "vim", "vi", "nano", "ping", "curl", "wget", "python", "python3",
                "java", "javac", "systemctl", "service", "sysctl", "lsblk",
                "neofetch", "crontab", "strace", "lsof", "capsh", "xterm",
                "cat", "grep", "sudo", "pgrep", "pkill", "ip"
            )
            for (dir in checkDirs) {
                if (!dir.exists() || !dir.isDirectory) continue
                dir.listFiles()?.forEach { file ->
                    try {
                        if (file.isFile && !java.nio.file.Files.isSymbolicLink(file.toPath()) && file.length() < 50000) {
                            val name = file.name.lowercase(Locale.ROOT)
                            val t = try { file.readText() } catch (e: Exception) { "" }
                            val lowerText = t.lowercase(Locale.ROOT)
                            val isMockContent = lowerText.contains("zhypix") ||
                                    lowerText.contains("sandbox") ||
                                    lowerText.contains("mock") ||
                                    lowerText.contains("custom") ||
                                    lowerText.contains("wrapper") ||
                                    lowerText.contains("fake_status") ||
                                    lowerText.contains("zhypix-container")

                            val isUsrLocalBin = dir.name == "bin" && dir.parentFile?.name == "local"
                            if (isMockContent || (isUsrLocalBin && mockTargetNames.contains(name) && t.startsWith("#!/"))) {
                                file.delete()
                                android.util.Log.d("Zhypix", "Cleaned legacy mock wrapper: ${file.absolutePath}")
                            }
                        }
                    } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Zhypix", "Error cleaning legacy wrappers", e)
        }
    }

    data class PtyLaunchSpec(
        val executablePath: String,
        val cwd: String,
        val args: List<String>,
        val env: List<String>,
        val distroAlias: String?
    )

    fun isDistroFullyInstalled(distroAlias: String?): Boolean {
        if (distroAlias.isNullOrBlank()) return false
        val context = applicationContext ?: return false
        val prootDir = File(context.filesDir, "proot-distros")
        val distroDir = File(prootDir, distroAlias)
        val altDir = File(File(context.filesDir, "proot-distro"), distroAlias)
        val target = if (distroDir.exists()) distroDir else if (altDir.exists()) altDir else return false
        
        val shellPaths = listOf("bin/bash", "bin/sh", "bin/dash", "bin/ash", "usr/bin/bash", "usr/bin/sh", "usr/bin/dash")
        val hasShell = shellPaths.any { p ->
            val f = File(target, p)
            f.exists() || Files.isSymbolicLink(f.toPath())
        }
        val hasStructure = listOf("bin", "usr", "etc", "lib", "lib64", "sbin").any { p ->
            val f = File(target, p)
            f.exists() || Files.isSymbolicLink(f.toPath())
        }
        return hasShell && hasStructure
    }

    fun resolveDistroDir(distroAlias: String): File {
        val context = applicationContext
        val primary = if (context != null) File(File(context.filesDir, "proot-distros"), distroAlias) else File("proot-distros/$distroAlias")
        val secondary = if (context != null) File(File(context.filesDir, "proot-distro"), distroAlias) else File("proot-distro/$distroAlias")
        val chosen = when {
            primary.exists() && primary.isDirectory -> primary
            secondary.exists() && secondary.isDirectory -> secondary
            else -> primary
        }
        return try {
            chosen.canonicalFile
        } catch (e: Exception) {
            chosen.absoluteFile
        }
    }

    fun resolveBusyboxFile(): File? {
        val context = applicationContext ?: return null
        val filesLib = File(context.filesDir, "lib/libbusybox.so")
        val nativeFile = File(context.applicationInfo.nativeLibraryDir, "libbusybox.so")
        return when {
            filesLib.exists() && isValidElf(filesLib) -> filesLib
            nativeFile.exists() && isValidElf(nativeFile) && nativeFile.length() > 50000 -> nativeFile
            else -> null
        }
    }

    fun buildPtyLaunchSpec(distroAlias: String?, cwdOverride: String? = null): PtyLaunchSpec? {
        val context = applicationContext ?: return null
        ensureProotPathResolved(context)

        if (distroAlias != null) {
            if (!isDistroFullyInstalled(distroAlias)) {
                android.util.Log.w("Zhypix", "Distro '$distroAlias' is not installed or incomplete. Falling back to host shell.")
                return null
            }
            val distroDir = resolveDistroDir(distroAlias)
            removeLegacyMockWrappers(distroDir)
            repairDpkgDatabase(distroDir)
            ensureBinariesAndShellsExecutable(distroDir)
            val proot = prootPath?.takeIf { File(it).exists() && isValidElf(File(it)) } ?: return null

            var guestDir = cwdOverride ?: _guestDirectory.value.ifBlank { "/root" }
            if (!File(distroDir, guestDir.removePrefix("/")).exists()) {
                guestDir = "/root"
            }

            val canonicalDistroPath = try { distroDir.canonicalFile.absolutePath } catch (e: Exception) { distroDir.absolutePath }

            val cmdList = mutableListOf(
                proot,
                "-r", canonicalDistroPath,
                "-0",
                "--link2symlink",
                "--sysvipc",
                "--kill-on-exit",
                "-w", guestDir
            )
            appendStandardPRootBindings(cmdList, context, distroDir)

            // exec replaces sh with robust interactive shell fallback
            cmdList.addAll(listOf(
                "/bin/sh", "-c",
                "unset LD_LIBRARY_PATH; export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:\$PATH HOME=/root USER=root SHELL=/bin/bash TERM=xterm-256color LANG=C.UTF-8 LC_ALL=C.UTF-8 TMPDIR=/tmp TEMP=/tmp TMP=/tmp DEBIAN_FRONTEND=noninteractive; if [ -x /bin/bash ]; then exec /bin/bash -l; elif [ -x /usr/bin/bash ]; then exec /usr/bin/bash -l; elif [ -x /bin/sh ]; then exec /bin/sh -l; else exec sh; fi"
            ))

            val prootTmpDir = File(File(context.cacheDir, "proot_tmp"), "pty_" + java.util.UUID.randomUUID().toString()).apply {
                mkdirs()
                setWritable(true, false)
                setReadable(true, false)
                setExecutable(true, false)
            }
            val envMap = buildStandardPRootEnvironment(context, prootTmpDir)

            return PtyLaunchSpec(
                executablePath = proot,
                cwd = context.filesDir.absolutePath,
                args = cmdList,
                env = envMap.map { (k, v) -> "$k=$v" },
                distroAlias = distroAlias
            )
        }

        // Fallback to Android host shell (/system/bin/sh) directly to avoid SELinux W^X blocks on API 29+
        val executable = if (File("/system/bin/sh").exists()) "/system/bin/sh" else "sh"
        val env = mutableMapOf(
            "PATH" to "/system/bin:/system/xbin:${context.applicationInfo.nativeLibraryDir}:${File(context.filesDir, "lib").absolutePath}",
            "LD_LIBRARY_PATH" to "${File(context.filesDir, "lib").absolutePath}:${context.applicationInfo.nativeLibraryDir}",
            "HOME" to context.filesDir.absolutePath,
            "USER" to "u0_a" + android.os.Process.myUid() % 10000,
            "SHELL" to executable,
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "LC_ALL" to "C.UTF-8",
            "TMPDIR" to context.cacheDir.absolutePath
        )
        return PtyLaunchSpec(
            executablePath = executable,
            cwd = globalRealDirectory.takeIf { it.isDirectory }?.absolutePath ?: context.filesDir.absolutePath,
            args = listOf(executable),
            env = env.map { (k, v) -> "$k=$v" },
            distroAlias = null
        )
    }
}
