package ru.yandex.diploma.aiplatform.infrastructure.llm

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import ru.yandex.diploma.aiplatform.domain.provider.LlmProvider
import ru.yandex.diploma.aiplatform.domain.provider.ProviderNotFoundException
import ru.yandex.diploma.aiplatform.domain.provider.ProviderRegistrationException
import ru.yandex.diploma.aiplatform.domain.provider.ProviderRegistry
import java.util.concurrent.ConcurrentHashMap

@Component
class DefaultProviderRegistry(
    private val availableProviders: List<LlmProvider> = emptyList()
) : ProviderRegistry {
    
    private val logger = LoggerFactory.getLogger(DefaultProviderRegistry::class.java)
    private val providers = ConcurrentHashMap<String, LlmProvider>()
    
    init {
        availableProviders.forEach { provider ->
            register(provider)
            logger.info("Auto-registered LLM provider: ${provider.providerId}")
        }
        
        validateOnlyRealProvidersRegistered()
        
        logger.info("ProviderRegistry initialized with ${providers.size} REAL providers: ${getAvailableProviders()}")
    }
    
    override fun register(provider: LlmProvider) {
        val providerId = provider.providerId
        
        if (providerId.isBlank()) {
            throw ProviderRegistrationException("Provider ID cannot be blank")
        }
        
        if (providers.containsKey(providerId)) {
            throw ProviderRegistrationException("Provider with ID '$providerId' is already registered")
        }
        
        validateProviderIsReal(provider)
        
        providers[providerId] = provider
        logger.debug("Registered REAL LLM provider: $providerId")
    }
    
    override fun getProvider(providerId: String): LlmProvider {
        if (providerId.isBlank()) {
            throw ProviderNotFoundException(providerId, "Provider ID cannot be blank")
        }
        
        val provider = providers[providerId]
            ?: throw ProviderNotFoundException(
                providerId = providerId,
                message = "Provider '$providerId' not found. Available providers: ${getAvailableProviders()}"
            )
        
        validateProviderIsReal(provider)
        
        return provider
    }
    
    override fun getAvailableProviders(): Set<String> {
        return providers.keys.toSet()
    }
    
    override fun isProviderRegistered(providerId: String): Boolean {
        return providers.containsKey(providerId)
    }
    
    override fun unregister(providerId: String): Boolean {
        val removed = providers.remove(providerId) != null
        if (removed) {
            logger.debug("Unregistered LLM provider: $providerId")
        }
        return removed
    }
    
    fun getProviderStats(): Map<String, ProviderStats> {
        return providers.mapValues { (_, provider) ->
            ProviderStats(
                providerId = provider.providerId,
                isHealthy = try {
                    kotlinx.coroutines.runBlocking { provider.isHealthy() }
                } catch (e: Exception) {
                    logger.warn("Health check failed for provider ${provider.providerId}: ${e.message}")
                    false
                }
            )
        }
    }
    
    private fun validateProviderIsReal(provider: LlmProvider) {
        val providerClass = provider::class.java.simpleName
        val providerId = provider.providerId
        
        val forbiddenPatterns = listOf("Mock", "Fake", "Stub", "Test")
        forbiddenPatterns.forEach { pattern ->
            if (providerClass.contains(pattern, ignoreCase = true)) {
                throw ProviderRegistrationException("""
                    MOCK PROVIDER FORBIDDEN!
                    
                    Attempted to register MOCK provider: $providerClass (ID: $providerId)
                    This system MUST use REAL infrastructure only.
                    
                    FORBIDDEN PATTERNS: ${forbiddenPatterns.joinToString(", ")}
                    DETECTED PATTERN: $pattern in $providerClass
                    
                    ALLOWED REAL PROVIDERS:
                    - OpenRouterLlmProvider (openrouter)
                    - OpenAiLlmProvider (openai)
                    
                    ACTION REQUIRED: Remove mock provider usage and use real LLM providers only.
                """.trimIndent())
            }
        }
        
        if ((providerId.contains("mock", ignoreCase = true) ||
            providerId.contains("fake", ignoreCase = true) ||
            providerId.contains("test", ignoreCase = true)) &&
            providerId != "deterministic") {
            throw ProviderRegistrationException("""
                🚨 MOCK PROVIDER ID FORBIDDEN! 🚨
                
                Provider ID '$providerId' contains forbidden mock pattern.
                This system MUST use REAL infrastructure only.
                
                REAL PROVIDER IDs ALLOWED:
                - openrouter
                - openai
                - deterministic (for testing)
                
                ACTION REQUIRED: Use real provider IDs only.
            """.trimIndent())
        }
        
        val realProviderClasses = setOf(
            "OpenRouterLlmProvider",
            "OpenAiLlmProvider",
            "DeterministicLlmProvider",  // for testing
            "ValidLlmProvider"  // for unit tests
        )
        
        if (!realProviderClasses.contains(providerClass)) {
            logger.warn("⚠️ Unknown provider class: $providerClass - ensure it's a real implementation")
        }
        
        logger.debug("✅ Validated REAL provider: $providerClass (ID: $providerId)")
    }
    
    private fun validateOnlyRealProvidersRegistered() {
        val mockProviders = providers.filter { (_, provider) ->
            val providerClass = provider::class.java.simpleName
            val providerId = provider.providerId
            
            (providerClass.contains("Mock", ignoreCase = true) ||
            providerClass.contains("Fake", ignoreCase = true) ||
            providerClass.contains("Stub", ignoreCase = true) ||
            providerId.contains("mock", ignoreCase = true) ||
            providerId.contains("fake", ignoreCase = true) ||
            providerId.contains("test", ignoreCase = true)) &&
            providerId != "deterministic" &&
            providerClass != "DeterministicLlmProvider" &&
            providerClass != "ValidLlmProvider"
        }
        
        if (mockProviders.isNotEmpty()) {
            val mockDetails = mockProviders.map { (id, provider) ->
                "${provider::class.java.simpleName} (ID: $id)"
            }.joinToString(", ")
            
            throw ProviderRegistrationException("""
                🚨 MOCK PROVIDERS DETECTED! 🚨
                
                Found ${mockProviders.size} mock provider(s): $mockDetails
                This system MUST use REAL infrastructure only.
                
                REAL PROVIDERS AVAILABLE: ${providers.filter { (_, provider) ->
                    val providerClass = provider::class.java.simpleName
                    providerClass.contains("OpenRouter") || providerClass.contains("OpenAi")
                }.keys.joinToString(", ")}
                
                ACTION REQUIRED: Remove all mock providers and use real LLM providers only.
            """.trimIndent())
        }
        
        logger.info("✅ All ${providers.size} registered providers are REAL: ${providers.keys.joinToString(", ")}")
    }
}

data class ProviderStats(
    val providerId: String,
    val isHealthy: Boolean
)