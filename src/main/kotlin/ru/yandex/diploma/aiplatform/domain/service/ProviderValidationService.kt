package ru.yandex.diploma.aiplatform.domain.service

import ru.yandex.diploma.aiplatform.domain.model.AgentConfig
import ru.yandex.diploma.aiplatform.domain.provider.ProviderRegistry
import ru.yandex.diploma.aiplatform.domain.repository.TestConfiguration

interface ProviderValidationService {
    fun validateConfiguration(configuration: TestConfiguration): List<String>
    
    fun validateAgent(agent: AgentConfig): List<String>
    
    suspend fun isProviderHealthy(providerId: String): Boolean
    
    suspend fun getProviderHealthReport(): Map<String, ProviderHealthStatus>
}

data class ProviderHealthStatus(
    val providerId: String,
    val isRegistered: Boolean,
    val isHealthy: Boolean,
    val errorMessage: String? = null,
    val lastChecked: Long = System.currentTimeMillis()
)