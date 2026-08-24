package com.example.model

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class McpServer(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val command: String,
    val args: String,
    val env: String = "",
    val isEnabled: Boolean = true
)
