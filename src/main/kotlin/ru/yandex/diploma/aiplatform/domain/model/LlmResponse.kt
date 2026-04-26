package ru.yandex.diploma.aiplatform.domain.model

data class LlmResponse(
    val content: String,
    val tokensUsed: Int? = null, // кол-во использованных токенов
    val model: String? = null,
    val finishReason: String? = null,
    val metadata: Map<String, Any> = emptyMap()
)