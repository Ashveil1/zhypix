package com.example.live

import android.content.Context
import com.example.service.ZhypixAccessibilityService
import org.json.JSONArray
import org.json.JSONObject

/** Live adapter for the rebuilt action parser and device executor. */
class LiveToolBridge(
    private val serviceProvider: () -> ZhypixAccessibilityService?,
    private val contextProvider: () -> Context?,
) {

    /**
     * Optional confirmation hook for sensitive actions (mirrors the REST
     * loop's confirm gate). Return true to allow, false to block. Missing hooks block execution. The UI owns the approval dialog.
     */
    var confirmAction: (suspend (actionType: String, target: String) -> Boolean)? = null

    fun toolsPayload(): JSONArray = JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().put(
        JSONObject().put("name", "screen_action").put("description", "Execute exactly one grounded Android action using the documented name(JSON object) language. Every physical action needs user confirmation.")
            .put("parameters", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject().put("action", JSONObject().put("type", "STRING")))
                .put("required", JSONArray().put("action")))
    )))

    suspend fun execute(call: LiveSessionManager.ToolCallRequest): JSONObject {
        if (call.name != "screen_action") return buildResponse(false, "Unknown tool: ${call.name}", "")
        val raw = call.args.optString("action")
        val action = (com.example.agent.ActionParser.parse(raw) as? com.example.agent.ActionParseResult.Ok)?.action
            ?: return buildResponse(false, "Invalid action. Return exactly one name(JSON object) call.", "")
        if (action is com.example.agent.ScreenAction.Terminal) {
            return JSONObject().put("status", "needs_user").put("message", action.message)
        }
        val service = serviceProvider() ?: return buildResponse(false, "Enable the accessibility service first.", "")
        if (!com.example.agent.ScreenSessionGate.mutex.tryLock()) return buildResponse(false, "Another screen task is running.", "")
        try {
            // Approval is never inferred from a model message or screen content.
            val approved = confirmAction?.invoke("screen action", raw) == true
            if (!approved) return JSONObject().put("status", "blocked").put("message", "User confirmation required; action not dispatched.")
            val result = service.device.execute(action)
            val state = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) { service.observation.phoneState().take(4000) }
            return buildResponse(result.success, result.message, state)
        } finally { com.example.agent.ScreenSessionGate.mutex.unlock() }
    }

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
