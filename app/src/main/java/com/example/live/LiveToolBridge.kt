package com.example.live

import android.content.Context
import com.example.service.ZhypixAccessibilityService
import org.json.JSONArray
import org.json.JSONObject

/**
 * Maps Gemini Live tool calls onto the existing screen-control execution
 * layer (GestureMapper -> ZhypixAccessibilityService). The Live API requires
 * the client to answer tool calls manually, so every call gets a
 * toolResponse payload built by [buildResponse].
 */
class LiveToolBridge(
    private val serviceProvider: () -> ZhypixAccessibilityService?,
    private val contextProvider: () -> Context?,
) {

    /**
     * Optional confirmation hook for sensitive actions (mirrors the REST
     * loop's confirm gate). Return true to allow, false to block. Default
     * allows everything; wiring to the UI gate lands with the Live UI step.
     */
    var confirmAction: (suspend (actionType: String, target: String) -> Boolean)? = null

    /** No legacy screen tools are advertised during the rebuild. */
    fun toolsPayload(): JSONArray = JSONArray()

    suspend fun execute(call: LiveSessionManager.ToolCallRequest): JSONObject =
        JSONObject().put("status", "error")
            .put("message", "Screen control is being rebuilt and is currently unavailable.")

    internal fun buildResponse(success: Boolean, message: String, hierarchy: String): JSONObject {
        val resp = JSONObject()
            .put("status", if (success) "success" else "error")
            .put("message", message)
        if (hierarchy.isNotBlank()) {
            resp.put("current_screen_hierarchy", hierarchy)
        }
        return resp
    }
}
