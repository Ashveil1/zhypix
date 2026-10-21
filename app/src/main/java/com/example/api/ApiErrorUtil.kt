package com.example.api

/** Helpers for safe API error reporting. */
object ApiErrorUtil {

    private val keyParamPattern = Regex("(?i)([?&]key=)[^&\\s]+")

    /** Remove API keys that providers may echo back inside error messages or URLs. */
    fun redactSecrets(text: String): String {
        return keyParamPattern.replace(text) { m -> m.groupValues[1] + "REDACTED" }
    }
}
