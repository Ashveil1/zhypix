package com.example.live

import android.content.Context
import com.example.service.GestureMapper
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

    /** Live setup `tools` payload: one functionDeclarations group. */
    fun toolsPayload(): JSONArray {
        val decl = JSONObject()
            .put("name", "computer_use_action")
            .put(
                "description",
                "Execute a single action on the phone screen: clicking a button, scrolling, swiping, observing the screen hierarchy, typing, or opening apps."
            )
            .put(
                "parameters",
                JSONObject()
                    .put("type", "OBJECT")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "action_type",
                                JSONObject()
                                    .put("type", "STRING")
                                    .put("description", "One of: CLICK, SWIPE, OBSERVE, TYPE, OPEN_APP, BACK, HOME")
                            )
                            .put(
                                "target",
                                JSONObject()
                                    .put("type", "STRING")
                                    .put(
                                        "description",
                                        "Coordinates 'x,y', a text label, a swipe direction (UP/DOWN/LEFT/RIGHT), a key (BACK/HOME), text to type, or an app name."
                                    )
                            )
                    )
                    .put("required", JSONArray().put("action_type").put("target"))
            )
        return JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().put(decl)))
    }

    /** Execute one Live tool call and build its toolResponse payload. */
    suspend fun execute(call: LiveSessionManager.ToolCallRequest): JSONObject {
        if (call.name != "computer_use_action") {
            return JSONObject()
                .put("status", "error")
                .put("message", "Unknown tool: ${call.name}")
        }

        val actionType = call.args.optString("action_type", "").trim()
        val target = call.args.optString("target", "").trim()
        if (actionType.isEmpty()) {
            return JSONObject()
                .put("status", "error")
                .put("message", "Missing required argument: action_type")
        }

        confirmAction?.let { confirm ->
            if (!confirm(actionType, target)) {
                return JSONObject()
                    .put("status", "blocked")
                    .put("message", "Action '$actionType' on '$target' was not confirmed by the user.")
            }
        }

        val service = serviceProvider()
        if (service == null) {
            return JSONObject()
                .put("status", "error")
                .put("message", "Accessibility Service is not enabled. Ask the user to enable it in Settings.")
        }

        val result = GestureMapper.executeAction(service, actionType, target, contextProvider())
        val hierarchy = try {
            // Live's context is small (~60k): cap the tree per tool response.
            service.awaitTreeSettle().take(MAX_HIERARCHY_CHARS)
        } catch (e: Exception) {
            ""
        }
        return buildResponse(result.success, result.message, hierarchy)
    }

    companion object {
        private const val MAX_HIERARCHY_CHARS = 4000
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
