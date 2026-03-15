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
    message: String = "Провайдер не найден: $providerId"
) : Exception(message)

class ProviderRegistrationException(
    val providerId: String,
    message: String = "Не удалось зарегистрировать провайдер: $providerId",
    cause: Throwable? = null
) : Exception(message, cause)