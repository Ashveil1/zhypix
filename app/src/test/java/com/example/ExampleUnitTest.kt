package com.example

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  @org.junit.Ignore("External Microsoft service requires live session tokens")
  fun testEdgeTtsConnection() {
    val TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    val EDGE_WS_URL = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=$TRUSTED_TOKEN"
    val CHROMIUM_FULL_VERSION = "130.0.2849.68"
    val CHROMIUM_MAJOR_VERSION = "130"

    val unixSeconds = System.currentTimeMillis() / 1000
    val windowsSeconds = unixSeconds + 11644473600L
    val roundedSeconds = windowsSeconds - (windowsSeconds % 300L)
    val baseTicks = roundedSeconds * 10_000_000L

    val buckets = listOf(
        baseTicks,                     // Current bucket
        baseTicks - 3000000000L,       // Previous 5-minute bucket (in case clock is fast)
        baseTicks + 3000000000L,       // Next 5-minute bucket (in case clock is slow)
        -1L,                           // SPECIAL: with GEC but without Cookie and X-MSEdge-ClientID
        0L                             // SPECIAL: with NO Sec-MS-GEC headers at all!
    )

    var success = false
    var errorMsg = ""

    for ((index, ticks) in buckets.withIndex()) {
        val connectionId = UUID.randomUUID().toString().replace("-", "").lowercase()
        val muid = UUID.randomUUID().toString().replace("-", "").uppercase()
        val wsUrl = "$EDGE_WS_URL&ConnectionId=$connectionId"

        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        val requestBuilder = Request.Builder()
            .url(wsUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$CHROMIUM_MAJOR_VERSION.0.0.0 Safari/537.36 Edg/$CHROMIUM_MAJOR_VERSION.0.0.0")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")

        if (ticks > 0L) {
            val strToHash = "$ticks$TRUSTED_TOKEN"
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val digest = md.digest(strToHash.toByteArray(Charsets.US_ASCII))
            val secMsGec = digest.joinToString("") { "%02x".format(it) }

            println("Attempt ${index + 1} with ticks: $ticks, GEC: $secMsGec")
            requestBuilder.header("Cookie", "muid=$muid;")
            requestBuilder.header("X-MSEdge-ClientID", muid)
            requestBuilder.header("Sec-MS-GEC", secMsGec)
            requestBuilder.header("Sec-MS-GEC-Version", "1-$CHROMIUM_FULL_VERSION")
            requestBuilder.header("Accept-Language", "en-US,en;q=0.9")
        } else if (ticks == -1L) {
            val strToHash = "$baseTicks$TRUSTED_TOKEN"
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val digest = md.digest(strToHash.toByteArray(Charsets.US_ASCII))
            val secMsGec = digest.joinToString("") { "%02x".format(it) }

            println("Attempt ${index + 1} with GEC: $secMsGec, NO Cookie/ClientID headers")
            requestBuilder.header("Sec-MS-GEC", secMsGec)
            requestBuilder.header("Sec-MS-GEC-Version", "1-$CHROMIUM_FULL_VERSION")
        } else {
            println("Attempt ${index + 1} with NO Sec-MS-GEC headers")
        }

        val request = requestBuilder.build()
        val latch = CountDownLatch(1)
        var attemptSuccess = false
        var attemptError = ""

        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                println("WebSocket opened successfully for attempt ${index + 1}! Code: ${response.code}")
                attemptSuccess = true
                webSocket.close(1000, "Done")
                latch.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (response != null) {
                    attemptError = "HTTP ${response.code}: ${response.message}"
                } else {
                    attemptError = t.message ?: "Unknown error"
                }
                latch.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                latch.countDown()
            }
        })

        latch.await(6, TimeUnit.SECONDS)
        if (attemptSuccess) {
            success = true
            break
        } else {
            println("Attempt ${index + 1} failed with error: $attemptError")
            errorMsg = attemptError
        }
    }

    assertTrue("WebSocket connection failed on all configurations: $errorMsg", success)
  }
}

