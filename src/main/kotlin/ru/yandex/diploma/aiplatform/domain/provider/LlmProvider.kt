package ru.yandex.diploma.aiplatform.domain.provider

import ru.yandex.diploma.aiplatform.domain.model.LlmRequest
import ru.yandex.diploma.aiplatform.domain.model.LlmResponse

interface LlmProvider {
    val providerId: String
    
    suspend fun generate(request: LlmRequest): LlmResponse
    
    suspend fun isHealthy(): Boolean
}

class LlmProviderException(
    message: String,
    cause: Throwable? = null,
    val providerId: String? = null
) : Exception(message, cause)