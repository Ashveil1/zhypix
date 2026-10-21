package com.example

import com.example.api.ApiErrorUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ApiErrorUtilTest {

    @Test
    fun redactSecrets_masksGeminiKeyQueryParam() {
        val raw = "HTTP 400 Bad Request\nResponse details: {\"error\":\"bad\"} at https://generativelanguage.googleapis.com/v1beta/models/x:generateContent?key=AIzaSySecretKey123"
        val out = ApiErrorUtil.redactSecrets(raw)
        assertFalse(out.contains("AIzaSySecretKey123"))
        assert(out.contains("key=REDACTED")) { "expected redacted marker, got: $out" }
    }

    @Test
    fun redactSecrets_masksAmpersandFormAndIsCaseInsensitive() {
        val out = ApiErrorUtil.redactSecrets("failed: https://api.example.com/v1?foo=1&KEY=abc123xyz&bar=2")
        assertFalse(out.contains("abc123xyz"))
        assert(out.contains("&bar=2")) { "other params must survive: $out" }
    }

    @Test
    fun redactSecrets_leavesPlainTextUntouched() {
        val raw = "HTTP 500 Internal Server Error"
        assertEquals(raw, ApiErrorUtil.redactSecrets(raw))
    }
}
