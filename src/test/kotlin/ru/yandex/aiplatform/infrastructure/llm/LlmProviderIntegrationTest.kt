package ru.yandex.diploma.aiplatform.infrastructure.llm

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import ru.yandex.diploma.aiplatform.domain.model.LlmRequest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LlmProviderIntegrationTest {
    
    @Test
    fun `LlmRequest should handle all parameters correctly`() = runTest {
        val provider = DeterministicLlmProvider()
        
        val request = LlmRequest(
            prompt = "test prompt",
            systemPrompt = "You are a helpful assistant",
            temperature = 0.8,
            maxTokens = 100,
            topP = 0.9,
            frequencyPenalty = 0.1,
            presencePenalty = 0.2,
            additionalParameters = mapOf("custom_param" to "custom_value")
        )
        
        val response = provider.generate(request)
        
        assertNotNull(response.content)
        assertEquals("deterministic-model-v1", response.model)
        assertEquals(0.8, response.metadata["temperature"])
    }
    
    @Test
    fun `LlmResponse should contain all required fields`() = runTest {
        val provider = DeterministicLlmProvider()
        
        val request = LlmRequest(prompt = "hello")
        val response = provider.generate(request)
        
        assertNotNull(response.content)
        assertTrue(response.content.isNotEmpty())
        assertTrue(response.tokensUsed != null && response.tokensUsed > 0)
        assertNotNull(response.model)
        assertNotNull(response.finishReason)
        assertNotNull(response.metadata)
        
        assertTrue(response.metadata.containsKey("deterministic"))
        assertTrue(response.metadata.containsKey("temperature"))
    }
}