package com.example.model

import java.util.UUID

data class ProviderProfile(
    val id: String,
    val name: String,
    val apiKey: String = "",
    val baseUrl: String = "",
    val modelName: String = ""
)

enum class AgentState {
    IDLE,
    LISTENING,
    THINKING,
    ACTING
}

enum class SettingsScreenType {
    MAIN, PROFILE, PERSONA, MEMORY, CAPABILITIES, CONNECTORS, PERMISSIONS, COLOR_MODE, FONT_STYLE, VOICE, PRIVACY, SHARED_LINKS
}

data class AgentAction(
    val actionType: String,
    val target: String
)

data class ReasoningStep(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String,
    val timestamp: String,
    val status: String = "SUCCESS", // "PENDING", "RUNNING", "SUCCESS", "FAILED"
    val type: String = "INFO" // "USER", "AI", "TOOL", "SYSTEM"
)

sealed class ChatMessage(open val id: String = UUID.randomUUID().toString()) {
    data class User(val text: String, override val id: String = UUID.randomUUID().toString()) : ChatMessage(id)
    data class Agent(val text: String, override val id: String = UUID.randomUUID().toString()) : ChatMessage(id)
    data class System(val text: String, override val id: String = UUID.randomUUID().toString()) : ChatMessage(id)
    data class TaskExecution(val action: AgentAction, val status: String, val resultSnippet: String? = null, override val id: String = UUID.randomUUID().toString()) : ChatMessage(id)
    data class ProviderConfigRequired(val text: String = "AI Provider and Model not configured", override val id: String = UUID.randomUUID().toString()) : ChatMessage(id)
}
