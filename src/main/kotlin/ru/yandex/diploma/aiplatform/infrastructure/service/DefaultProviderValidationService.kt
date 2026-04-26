package ru.yandex.diploma.aiplatform.infrastructure.service

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.yandex.diploma.aiplatform.config.JudgeEvaluationProperties
import ru.yandex.diploma.aiplatform.domain.model.AgentConfig
import ru.yandex.diploma.aiplatform.domain.model.OptimizerType
import ru.yandex.diploma.aiplatform.domain.provider.ProviderNotFoundException
import ru.yandex.diploma.aiplatform.domain.provider.ProviderRegistry
import ru.yandex.diploma.aiplatform.domain.repository.TestConfiguration
import ru.yandex.diploma.aiplatform.domain.service.ProviderHealthStatus
import ru.yandex.diploma.aiplatform.domain.service.ProviderValidationService
import ru.yandex.diploma.aiplatform.config.OpenAiModels

@Service
class DefaultProviderValidationService(
    private val providerRegistry: ProviderRegistry,
    private val judgeEvaluationProperties: JudgeEvaluationProperties
) : ProviderValidationService {
    
    private val logger = LoggerFactory.getLogger(DefaultProviderValidationService::class.java)
    
    override fun validateConfiguration(configuration: TestConfiguration): List<String> {
        val errors = mutableListOf<String>()
        
        configuration.agents.forEach { agent ->
            errors.addAll(validateAgent(agent))
        }
        
        val agentNames = configuration.agents.map { it.name }
        val duplicateNames = agentNames.groupBy { it }.filter { it.value.size > 1 }.keys
        if (duplicateNames.isNotEmpty()) {
            errors.add("Duplicate agent names found: ${duplicateNames.joinToString(", ")}")
        }
        
        val requiredProviders = configuration.agents.map { it.provider }.toSet()
        val availableProviders = providerRegistry.getAvailableProviders()
        val missingProviders = requiredProviders - availableProviders
        if (missingProviders.isNotEmpty()) {
            errors.add("Required providers not registered: ${missingProviders.joinToString(", ")}. Available: ${availableProviders.joinToString(", ")}")
        }

        configuration.tests.forEach { test ->
            if (test.evaluatorType == "llm-judge") {
                val judgeProvider =
                    (test.metadata["judgeProvider"] as? String) ?: judgeEvaluationProperties.defaultProvider
                if (!providerRegistry.isProviderRegistered(judgeProvider)) {
                    errors.add(
                        "Test '${test.promptId}' uses llm-judge but judge provider '$judgeProvider' is not registered " +
                            "(available: ${availableProviders.joinToString(", ")})."
                    )
                }
            }
        }

        val opt = configuration.optimizationConfig
        if (opt != null && opt.enabled && opt.type == OptimizerType.LLM) {
            val optProvider = opt.llmConfig?.provider
            if (optProvider.isNullOrBlank()) {
                errors.add("Optimization is enabled with LLM type but optimizer.llm.provider is missing.")
            } else if (!providerRegistry.isProviderRegistered(optProvider)) {
                errors.add(
                    "Optimization LLM provider '$optProvider' is not registered " +
                        "(available: ${availableProviders.joinToString(", ")})."
                )
            }
        }
        
        return errors
    }
    
    override fun validateAgent(agent: AgentConfig): List<String> {
        val errors = mutableListOf<String>()
        
        if (!providerRegistry.isProviderRegistered(agent.provider)) {
            errors.add("Provider '${agent.provider}' for agent '${agent.name}' is not registered")
            return errors
        }
        
        agent.model?.let { model ->
            val modelValidationError = validateModelForProvider(agent.provider, model)
            if (modelValidationError != null) {
                errors.add("Agent '${agent.name}': $modelValidationError")
            }
        }
        
        try {
            AgentConfig(
                name = agent.name,
                provider = agent.provider,
                systemPrompt = agent.systemPrompt,
                model = agent.model,
                temperature = agent.temperature,
                maxTokens = agent.maxTokens,
                topP = agent.topP,
                frequencyPenalty = agent.frequencyPenalty,
                presencePenalty = agent.presencePenalty,
                additionalParameters = agent.additionalParameters
            )
        } catch (e: IllegalArgumentException) {
            errors.add("Agent '${agent.name}' has invalid parameters: ${e.message}")
        }
        
        return errors
    }
    
    override suspend fun isProviderHealthy(providerId: String): Boolean {
        return try {
            val provider = providerRegistry.getProvider(providerId)
            provider.isHealthy()
        } catch (e: ProviderNotFoundException) {
            logger.warn("Provider '$providerId' not found during health check")
            false
        } catch (e: Exception) {
            logger.warn("Health check failed for provider '$providerId': ${e.message}")
            false
        }
    }
    
    override suspend fun getProviderHealthReport(): Map<String, ProviderHealthStatus> = coroutineScope {
        val availableProviders = providerRegistry.getAvailableProviders()
        
        availableProviders.map { providerId ->
            async {
                val status = try {
                    val provider = providerRegistry.getProvider(providerId)
                    val isHealthy = provider.isHealthy()
                    ProviderHealthStatus(
                        providerId = providerId,
                        isRegistered = true,
                        isHealthy = isHealthy,
                        errorMessage = if (!isHealthy) "Health check failed" else null
                    )
                } catch (e: Exception) {
                    ProviderHealthStatus(
                        providerId = providerId,
                        isRegistered = true,
                        isHealthy = false,
                        errorMessage = "Health check error: ${e.message}"
                    )
                }
                providerId to status
            }
        }.awaitAll().toMap()
    }
    
    private fun validateModelForProvider(provider: String, model: String): String? {
        return when (provider.lowercase()) {
            "openai" -> {
                if (!OpenAiModels.isValidModel(model)) {
                    "Model '$model' is not a valid OpenAI model. Valid models: ${OpenAiModels.getAllModels().joinToString(", ")}"
                } else null
            }
            "mock" -> {
                null
            }
            else -> {
                logger.warn("Cannot validate model '$model' for provider '$provider' - validation not implemented")
                null
            }
        }
    }
}