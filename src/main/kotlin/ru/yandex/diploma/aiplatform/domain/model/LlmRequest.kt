package ru.yandex.diploma.aiplatform.domain.model

data class LlmRequest(
    val prompt: String,
    val systemPrompt: String? = null,
    val model: String? = null, // specific model to use, overrides provider default
    val temperature: Double = 0.7,
    val maxTokens: Int? = null,
    val topP: Double? = null,
    val frequencyPenalty: Double? = null,
    val presencePenalty: Double? = null,
    val additionalParameters: Map<String, Any> = emptyMap()
)