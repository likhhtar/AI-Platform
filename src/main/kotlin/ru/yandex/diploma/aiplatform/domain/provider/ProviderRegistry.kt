package ru.yandex.diploma.aiplatform.domain.provider

interface ProviderRegistry {
    fun register(provider: LlmProvider)
    
    fun getProvider(providerId: String): LlmProvider
    
    fun getAvailableProviders(): Set<String>
    
    fun isProviderRegistered(providerId: String): Boolean
    
    fun unregister(providerId: String): Boolean
}

class ProviderNotFoundException(
    val providerId: String,
    message: String = "Provider not found: $providerId"
) : Exception(message)

class ProviderRegistrationException(
    val providerId: String,
    message: String = "Failed to register provider: $providerId",
    cause: Throwable? = null
) : Exception(message, cause)