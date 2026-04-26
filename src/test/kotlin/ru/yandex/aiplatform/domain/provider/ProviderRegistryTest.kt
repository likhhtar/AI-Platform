package ru.yandex.aiplatform.domain.provider

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ru.yandex.diploma.aiplatform.domain.model.LlmRequest
import ru.yandex.diploma.aiplatform.domain.model.LlmResponse
import ru.yandex.diploma.aiplatform.domain.provider.*
import ru.yandex.diploma.aiplatform.infrastructure.llm.DefaultProviderRegistry
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderRegistryTest {

    private class ValidLlmProvider(
        override val providerId: String,
        private val shouldBeHealthy: Boolean = true
    ) : LlmProvider {
        
        override suspend fun generate(request: LlmRequest): LlmResponse {
            return LlmResponse(
                content = "Valid response for: ${request.prompt}",
                tokensUsed = 10,
                model = "valid-model",
                finishReason = "stop"
            )
        }
        
        override suspend fun isHealthy(): Boolean = shouldBeHealthy
    }

    @Test
    fun `должен успешно регистрировать и получать провайдер`() {
        val registry = DefaultProviderRegistry()
        val validProvider = ValidLlmProvider("valid-provider")
        
        registry.register(validProvider)
        val retrievedProvider = registry.getProvider("valid-provider")
        
        assertEquals(validProvider, retrievedProvider)
        assertEquals("valid-provider", retrievedProvider.providerId)
    }

    @Test
    fun `должен выбрасывать исключение при получении несуществующего провайдера`() {
        val registry = DefaultProviderRegistry()
        
        val exception = assertThrows<ProviderNotFoundException> {
            registry.getProvider("nonexistent-provider")
        }
        
        assertEquals("nonexistent-provider", exception.providerId)
        assertTrue(exception.message!!.contains("Provider 'nonexistent-provider' not found"))
    }

    @Test
    fun `должен корректно проверять наличие зарегистрированных провайдеров`() {
        val registry = DefaultProviderRegistry()
        val validProvider = ValidLlmProvider("registered-provider")
        
        assertFalse(registry.isProviderRegistered("registered-provider"))
        
        registry.register(validProvider)
        
        assertTrue(registry.isProviderRegistered("registered-provider"))
        assertFalse(registry.isProviderRegistered("unregistered-provider"))
    }

    @Test
    fun `должен возвращать список всех доступных провайдеров`() {
        val registry = DefaultProviderRegistry()
        val provider1 = ValidLlmProvider("provider-1")
        val provider2 = ValidLlmProvider("provider-2")
        
        registry.register(provider1)
        registry.register(provider2)
        val availableProviders = registry.getAvailableProviders()
        
        assertEquals(2, availableProviders.size)
        assertTrue(availableProviders.contains("provider-1"))
        assertTrue(availableProviders.contains("provider-2"))
    }

    @Test
    fun `должен успешно удалять зарегистрированный провайдер`() {
        val registry = DefaultProviderRegistry()
        val validProvider = ValidLlmProvider("removable-provider")
        
        registry.register(validProvider)
        assertTrue(registry.isProviderRegistered("removable-provider"))
        
        val removed = registry.unregister("removable-provider")
        
        assertTrue(removed)
        assertFalse(registry.isProviderRegistered("removable-provider"))
    }

    @Test
    fun `должен возвращать false при попытке удалить несуществующий провайдер`() {
        val registry = DefaultProviderRegistry()
        
        val removed = registry.unregister("nonexistent-provider")
        
        assertFalse(removed)
    }

    @Test
    fun `зарегистрированный провайдер должен корректно генерировать ответы`() = runBlocking {
        val registry = DefaultProviderRegistry()
        val validProvider = ValidLlmProvider("working-provider")
        registry.register(validProvider)
        
        val provider = registry.getProvider("working-provider")
        val request = LlmRequest(
            prompt = "Тестовый промпт",
            temperature = 0.5
        )
        val response = provider.generate(request)
        
        assertTrue(response.content.contains("Valid response for: Тестовый промпт"))
        assertEquals(10, response.tokensUsed)
        assertEquals("valid-model", response.model)
        assertEquals("stop", response.finishReason)
    }

    @Test
    fun `должен корректно проверять здоровье провайдера`() = runBlocking {
        val registry = DefaultProviderRegistry()
        val healthyProvider = ValidLlmProvider("healthy-provider", shouldBeHealthy = true)
        val unhealthyProvider = ValidLlmProvider("unhealthy-provider", shouldBeHealthy = false)
        
        registry.register(healthyProvider)
        registry.register(unhealthyProvider)
        
        val healthy = registry.getProvider("healthy-provider")
        val unhealthy = registry.getProvider("unhealthy-provider")
        
        assertTrue(healthy.isHealthy())
        assertFalse(unhealthy.isHealthy())
    }
}