package ru.yandex.diploma.aiplatform.infrastructure.llm

import ru.yandex.diploma.aiplatform.domain.model.LlmRequest
import ru.yandex.diploma.aiplatform.domain.model.LlmResponse
import ru.yandex.diploma.aiplatform.domain.provider.LlmProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(value = ["llm.providers.mock.enabled"], havingValue = "true")
class MockLlmProvider : LlmProvider {
    
    init {
        throw RuntimeException("""
            MOCK USAGE FORBIDDEN!           
        """.trimIndent())
    }
    
    override val providerId: String = "mock"
    
    override suspend fun generate(request: LlmRequest): LlmResponse {
        throw RuntimeException("MOCK USAGE FORBIDDEN! MockLlmProvider.generate() called")
    }
    
    override suspend fun isHealthy(): Boolean {
        throw RuntimeException("MOCK USAGE FORBIDDEN! MockLlmProvider.isHealthy() called")
    }
    
    @Deprecated("Mock usage is forbidden", level = DeprecationLevel.ERROR)
    fun addResponse(prompt: String, response: String) {
        throw RuntimeException("MOCK USAGE FORBIDDEN! addResponse() called")
    }
    
    @Deprecated("Mock usage is forbidden", level = DeprecationLevel.ERROR)
    fun clearResponses() {
        throw RuntimeException("MOCK USAGE FORBIDDEN! clearResponses() called")
    }
}