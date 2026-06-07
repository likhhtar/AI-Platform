package ru.yandex.diploma.aiplatform.infrastructure.llm

import ru.yandex.diploma.aiplatform.config.OpenAiResponse

data class ElizaChatCompletionEnvelope(
    val response: OpenAiResponse,
)
