package ru.yandex.aiplatform.infrastructure.llm

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ru.yandex.diploma.aiplatform.domain.model.AgentConfig
import ru.yandex.diploma.aiplatform.domain.model.LlmRequest
import ru.yandex.diploma.aiplatform.domain.model.LlmResponse
import ru.yandex.diploma.aiplatform.domain.provider.LlmProvider
import ru.yandex.diploma.aiplatform.domain.provider.ProviderNotFoundException
import ru.yandex.diploma.aiplatform.domain.provider.ProviderRegistrationException
import ru.yandex.diploma.aiplatform.infrastructure.llm.DefaultProviderRegistry
import java.math.BigDecimal

class MultiProviderSystemTest {
    
    private lateinit var providerRegistry: DefaultProviderRegistry
    private lateinit var validOpenAiProvider: ValidLlmProvider
    private lateinit var validAnthropicProvider: ValidLlmProvider
    private lateinit var validGeminiProvider: ValidLlmProvider
    
    @BeforeEach
    fun setUp() {
        validOpenAiProvider = ValidLlmProvider("openai")
        validAnthropicProvider = ValidLlmProvider("anthropic")
        validGeminiProvider = ValidLlmProvider("gemini")
        
        providerRegistry = DefaultProviderRegistry(
            listOf(validOpenAiProvider, validAnthropicProvider, validGeminiProvider)
        )
    }
    
    @Test
    fun `should register all providers on initialization`() {
        val availableProviders = providerRegistry.getAvailableProviders()
        
        assertEquals(3, availableProviders.size)
        assertTrue(availableProviders.contains("openai"))
        assertTrue(availableProviders.contains("anthropic"))
        assertTrue(availableProviders.contains("gemini"))
    }
    
    @Test
    fun `should retrieve providers by ID`() {
        val openAiProvider = providerRegistry.getProvider("openai")
        val anthropicProvider = providerRegistry.getProvider("anthropic")
        val geminiProvider = providerRegistry.getProvider("gemini")
        
        assertEquals("openai", openAiProvider.providerId)
        assertEquals("anthropic", anthropicProvider.providerId)
        assertEquals("gemini", geminiProvider.providerId)
    }
    
    @Test
    fun `should throw exception for unknown provider`() {
        val exception = assertThrows<ProviderNotFoundException> {
            providerRegistry.getProvider("unknown")
        }
        
        assertEquals("unknown", exception.providerId)
        assertTrue(exception.message!!.contains("Provider 'unknown' not found"))
        assertTrue(exception.message!!.contains("Available providers:"))
    }
    
    @Test
    fun `should prevent duplicate provider registration`() {
        val duplicateProvider = ValidLlmProvider("openai")
        
        val exception = assertThrows<ProviderRegistrationException> {
            providerRegistry.register(duplicateProvider)
        }
        
        assertTrue(exception.message!!.contains("Provider with ID 'openai' is already registered"))
    }
    
    @Test
    fun `should support provider unregistration`() {
        assertTrue(providerRegistry.isProviderRegistered("openai"))
        
        val removed = providerRegistry.unregister("openai")
        assertTrue(removed)
        assertFalse(providerRegistry.isProviderRegistered("openai"))
        
        val removedAgain = providerRegistry.unregister("openai")
        assertFalse(removedAgain)
    }
    
    @Test
    fun `should create agents with backward compatibility`() {
        val agentWithoutProvider = AgentConfig(
            name = "test-agent",
            systemPrompt = "You are a test assistant"
        )
        assertEquals("openai", agentWithoutProvider.provider)
        
        val agentWithProvider = AgentConfig(
            name = "test-agent",
            provider = "anthropic",
            systemPrompt = "You are a test assistant"
        )
        assertEquals("anthropic", agentWithProvider.provider)
        
        val agentFromCompanion = AgentConfig.create(
            name = "test-agent",
            systemPrompt = "You are a test assistant",
            provider = "gemini"
        )
        assertEquals("gemini", agentFromCompanion.provider)
    }
    
    
    @Test
    fun `should generate responses from different providers`() = runBlocking {
        val request = LlmRequest(
            prompt = "Hello, world!",
            systemPrompt = "You are a helpful assistant",
            model = "test-model"
        )
        
        val openAiResponse = validOpenAiProvider.generate(request)
        assertTrue(openAiResponse.content.contains("Valid response for: Hello, world!"))
        assertEquals("test-model", openAiResponse.model)
        
        val anthropicResponse = validAnthropicProvider.generate(request)
        assertTrue(anthropicResponse.content.contains("Valid response for: Hello, world!"))
        assertEquals("test-model", anthropicResponse.model)
        
        val geminiResponse = validGeminiProvider.generate(request)
        assertTrue(geminiResponse.content.contains("Valid response for: Hello, world!"))
        assertEquals("test-model", geminiResponse.model)
    }
    
    @Test
    fun `should report provider health status`() = runBlocking {
        assertTrue(validOpenAiProvider.isHealthy())
        assertTrue(validAnthropicProvider.isHealthy())
        assertTrue(validGeminiProvider.isHealthy())
    }
    
    @Test
    fun `should provide provider statistics`() {
        val stats = providerRegistry.getProviderStats()
        
        assertEquals(3, stats.size)
        assertTrue(stats.containsKey("openai"))
        assertTrue(stats.containsKey("anthropic"))
        assertTrue(stats.containsKey("gemini"))
        
        stats.values.forEach { stat ->
            assertTrue(stat.isHealthy)
            assertNotNull(stat.providerId)
        }
    }
    
    private class ValidLlmProvider(
        override val providerId: String
    ) : LlmProvider {
        
        override suspend fun generate(request: LlmRequest): LlmResponse {
            return LlmResponse(
                content = "Valid response for: ${request.prompt}",
                tokensUsed = 50,
                model = request.model ?: "default-model",
                finishReason = "stop",
                metadata = mapOf(
                    "provider" to providerId,
                    "valid" to true
                )
            )
        }
        
        override suspend fun isHealthy(): Boolean = true
    }
}