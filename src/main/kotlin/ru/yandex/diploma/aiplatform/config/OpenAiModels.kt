package ru.yandex.diploma.aiplatform.config

import com.fasterxml.jackson.annotation.JsonProperty

data class OpenAiRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val temperature: Double? = null,
    @JsonProperty("max_tokens")
    val maxTokens: Int? = null,
    @JsonProperty("top_p")
    val topP: Double? = null,
    @JsonProperty("frequency_penalty")
    val frequencyPenalty: Double? = null,
    @JsonProperty("presence_penalty")
    val presencePenalty: Double? = null,
    val stream: Boolean = false
)

data class OpenAiMessage(
    val role: String, // "system", "user", "assistant"
    val content: String
)

data class OpenAiResponse(
    val id: String? = null,
    val `object`: String? = null,
    val created: Long? = null,
    val model: String? = null,
    val choices: List<OpenAiChoice> = emptyList(),
    val usage: OpenAiUsage? = null,
)

data class OpenAiChoice(
    val index: Int,
    val message: OpenAiMessage,
    @JsonProperty("finish_reason")
    val finishReason: String? = null,
)

data class OpenAiUsage(
    @JsonProperty("prompt_tokens")
    val promptTokens: Int = 0,
    @JsonProperty("completion_tokens")
    val completionTokens: Int = 0,
    @JsonProperty("total_tokens")
    val totalTokens: Int = 0,
)

data class OpenAiError(
    val error: OpenAiErrorDetails
)

data class OpenAiErrorDetails(
    val message: String,
    val type: String,
    val param: String? = null,
    val code: String? = null
)

object OpenAiModels {
    const val GPT_4 = "gpt-4"
    const val GPT_4_TURBO = "gpt-4-turbo"
    const val GPT_4O = "gpt-4o"
    const val GPT_3_5_TURBO = "gpt-3.5-turbo"
    
    fun getAllModels(): Set<String> = setOf(
        GPT_4,
        GPT_4_TURBO,
        GPT_4O,
        GPT_3_5_TURBO
    )
    
    fun isValidModel(model: String): Boolean = getAllModels().contains(model)
    
    fun getDefaultModel(): String = GPT_4O
}