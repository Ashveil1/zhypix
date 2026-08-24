package com.example.utils

import android.content.Context
import android.os.Environment
import android.util.Base64
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileUtils {

    fun resolveFile(context: Context, filePath: String, distro: String? = null): File {
        val trimmedPath = filePath.trim()

        // 1. Check if path is explicit Android External Storage or App Storage
        if (trimmedPath.startsWith("/sdcard") ||
            trimmedPath.startsWith("/storage/emulated/0") ||
            trimmedPath.startsWith(context.filesDir.absolutePath) ||
            trimmedPath.startsWith(context.cacheDir.absolutePath)
        ) {
            return File(trimmedPath)
        }

        // 2. Determine target distro (explicit parameter or active distro or first installed in LinuxTerminalSimulator)
        val activeDistro = distro?.takeIf { it.isNotBlank() }
            ?: LinuxTerminalSimulator.activeDistro.value?.takeIf { it.isNotBlank() }
            ?: LinuxTerminalSimulator.installedDistros.value.firstOrNull()

        if (activeDistro != null) {
            val prootDistroDir = File(File(context.filesDir, "proot-distros"), activeDistro)
            if (prootDistroDir.exists()) {
                val relativePath = if (trimmedPath.startsWith("./")) trimmedPath.substring(2) else trimmedPath
                val cleanGuestPath = when {
                    relativePath.startsWith("~/") -> "root/" + relativePath.substring(2)
                    relativePath == "~" -> "root"
                    relativePath.startsWith("/") -> relativePath.substring(1)
                    relativePath.startsWith("root/") || relativePath.startsWith("home/") ||
                    relativePath.startsWith("etc/") || relativePath.startsWith("usr/") ||
                    relativePath.startsWith("var/") || relativePath.startsWith("tmp/") ||
                    relativePath.startsWith("opt/") || relativePath.startsWith("bin/") -> relativePath
                    else -> "root/" + relativePath
                }
                return File(prootDistroDir, cleanGuestPath)
            }
        }

        // 3. Fallback to direct path on Android host
        val file = File(trimmedPath)
        if (file.isAbsolute) {
            return file
        }

        // 4. Default relative path to Android filesDir or external storage
        return File(context.filesDir, trimmedPath)
    }

    private fun needsStoragePermission(file: File): Boolean {
        val path = file.absolutePath
        return path.startsWith("/sdcard") || path.startsWith("/storage/emulated/0") || path.startsWith(Environment.getExternalStorageDirectory().absolutePath)
    }

    fun readFile(
        context: Context,
        filePath: String,
        isBinaryBase64: Boolean? = null,
        distro: String? = null,
        maxBytes: Int = 2000000
    ): Map<String, Any> {
        val targetFile = resolveFile(context, filePath, distro)

        if (needsStoragePermission(targetFile) && !PermissionUtils.isStorageGranted(context)) {
            return mapOf(
                "status" to "error",
                "message" to "Storage permission not granted. Please grant storage permission on device to access /sdcard or external storage."
            )
        }

        if (!targetFile.exists()) {
            return mapOf(
                "status" to "error",
                "message" to "File does not exist: ${targetFile.absolutePath} (input path: '$filePath')"
            )
        }

        if (targetFile.isDirectory) {
            return mapOf(
                "status" to "error",
                "message" to "Target path is a directory, not a file: ${targetFile.absolutePath}. Use list_directory tool instead."
            )
        }

        return try {
            val fileSize = targetFile.length()
            val bytesToRead = Math.min(fileSize, maxBytes.toLong()).toInt()

            val bytes = targetFile.inputStream().use { input ->
                val buffer = ByteArray(bytesToRead)
                var readTotal = 0
                while (readTotal < bytesToRead) {
                    val count = input.read(buffer, readTotal, bytesToRead - readTotal)
                    if (count == -1) break
                    readTotal += count
                }
                if (readTotal < bytesToRead) buffer.copyOf(readTotal) else buffer
            }

            // Detect binary if not explicitly specified
            val forceBinary = isBinaryBase64 ?: isLikelyBinary(bytes)

            if (forceBinary) {
                val base64String = Base64.encodeToString(bytes, Base64.NO_WRAP)
                mapOf(
                    "status" to "success",
                    "file_path" to targetFile.absolutePath,
                    "encoding" to "base64",
                    "size_bytes" to fileSize,
                    "bytes_read" to bytes.size,
                    "content" to base64String,
                    "truncated" to (fileSize > maxBytes)
                )
            } else {
                val textContent = String(bytes, Charsets.UTF_8)
                mapOf(
                    "status" to "success",
                    "file_path" to targetFile.absolutePath,
                    "encoding" to "utf-8",
                    "size_bytes" to fileSize,
                    "bytes_read" to bytes.size,
                    "content" to textContent,
                    "truncated" to (fileSize > maxBytes)
                )
            }
        } catch (e: Exception) {
            mapOf(
                "status" to "error",
                "message" to "Failed to read file '${targetFile.absolutePath}': ${e.message}"
            )
        }
    }

    fun writeFile(
        context: Context,
        filePath: String,
        content: String,
        isBinaryBase64: Boolean? = null,
        append: Boolean? = null,
        distro: String? = null
    ): Map<String, Any> {
        val targetFile = resolveFile(context, filePath, distro)

        if (needsStoragePermission(targetFile) && !PermissionUtils.isStorageGranted(context)) {
            return mapOf(
                "status" to "error",
                "message" to "Storage permission not granted. Please grant storage permission on device to access /sdcard or external storage."
            )
        }

        return try {
            targetFile.parentFile?.mkdirs()

            val shouldAppend = append == true
            val isBinary = isBinaryBase64 == true

            if (isBinary) {
                val bytes = Base64.decode(content, Base64.DEFAULT)
                if (shouldAppend) {
                    targetFile.appendBytes(bytes)
                } else {
                    targetFile.writeBytes(bytes)
                }
                mapOf(
                    "status" to "success",
                    "file_path" to targetFile.absolutePath,
                    "bytes_written" to bytes.size,
                    "mode" to (if (shouldAppend) "appended" else "overwritten"),
                    "encoding" to "binary"
                )
            } else {
                if (shouldAppend) {
                    targetFile.appendText(content, Charsets.UTF_8)
                } else {
                    targetFile.writeText(content, Charsets.UTF_8)
                }
                mapOf(
                    "status" to "success",
                    "file_path" to targetFile.absolutePath,
                    "bytes_written" to content.toByteArray(Charsets.UTF_8).size,
                    "mode" to (if (shouldAppend) "appended" else "overwritten"),
                    "encoding" to "utf-8"
                )
            }
        } catch (e: Exception) {
            mapOf(
                "status" to "error",
                "message" to "Failed to write file '${targetFile.absolutePath}': ${e.message}"
            )
        }
    }

    fun listDirectory(
        context: Context,
        dirPath: String,
        distro: String? = null
    ): Map<String, Any> {
        val targetDir = resolveFile(context, dirPath, distro)

        if (needsStoragePermission(targetDir) && !PermissionUtils.isStorageGranted(context)) {
            return mapOf(
                "status" to "error",
                "message" to "Storage permission not granted. Please grant storage permission on device to access /sdcard or external storage."
            )
        }

        if (!targetDir.exists()) {
            return mapOf(
                "status" to "error",
                "message" to "Directory does not exist: ${targetDir.absolutePath} (input path: '$dirPath')"
            )
        }

        if (!targetDir.isDirectory) {
            return mapOf(
                "status" to "error",
                "message" to "Target path is a file, not a directory: ${targetDir.absolutePath}. Use read_file tool instead."
            )
        }

        return try {
            val files = targetDir.listFiles() ?: emptyArray()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

            val items = files.map { file ->
                mapOf(
                    "name" to file.name,
                    "path" to file.absolutePath,
                    "is_directory" to file.isDirectory,
                    "size_bytes" to if (file.isFile) file.length() else 0L,
                    "last_modified" to dateFormat.format(Date(file.lastModified())),
                    "can_read" to file.canRead(),
                    "can_write" to file.canWrite()
                )
            }

            mapOf(
                "status" to "success",
                "directory_path" to targetDir.absolutePath,
                "total_items" to items.size,
                "items" to items
            )
        } catch (e: Exception) {
            mapOf(
                "status" to "error",
                "message" to "Failed to list directory '${targetDir.absolutePath}': ${e.message}"
            )
        }
    }

    private fun isLikelyBinary(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        var nullCount = 0
        val checkLen = Math.min(bytes.size, 1024)
        for (i in 0 until checkLen) {
            if (bytes[i] == 0.toByte()) {
                nullCount++
            }
        }
        return nullCount > 0
    }
}
