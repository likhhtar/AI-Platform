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
            🚨 MOCK USAGE FORBIDDEN! 🚨
            
            MockLlmProvider instantiation is STRICTLY PROHIBITED.
            This system MUST run with REAL infrastructure only.
            
            REQUIRED ACTIONS:
            1. Set llm.providers.mock.enabled=false in configuration
            2. Use ONLY OpenRouterLlmProvider for real LLM calls
            3. Remove all mock dependencies from tests
            
            REAL PROVIDERS AVAILABLE:
            - OpenRouterLlmProvider (openrouter)
            
            Stack trace will show where mock was attempted to be used.
        """.trimIndent())
    }
    
    override val providerId: String = "mock"
    
    override suspend fun generate(request: LlmRequest): LlmResponse {
        throw RuntimeException("🚨 MOCK USAGE FORBIDDEN! MockLlmProvider.generate() called - system must use REAL LLM providers only!")
    }
    
    override suspend fun isHealthy(): Boolean {
        throw RuntimeException("🚨 MOCK USAGE FORBIDDEN! MockLlmProvider.isHealthy() called - system must use REAL LLM providers only!")
    }
    
    @Deprecated("Mock usage is forbidden", level = DeprecationLevel.ERROR)
    fun addResponse(prompt: String, response: String) {
        throw RuntimeException("🚨 MOCK USAGE FORBIDDEN! addResponse() called - system must use REAL LLM providers only!")
    }
    
    @Deprecated("Mock usage is forbidden", level = DeprecationLevel.ERROR)
    fun clearResponses() {
        throw RuntimeException("🚨 MOCK USAGE FORBIDDEN! clearResponses() called - system must use REAL LLM providers only!")
    }
}