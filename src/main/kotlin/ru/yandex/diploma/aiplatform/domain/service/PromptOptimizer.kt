package ru.yandex.diploma.aiplatform.domain.service

import ru.yandex.diploma.aiplatform.application.usecase.ExperimentResult
import ru.yandex.diploma.aiplatform.domain.model.*

interface PromptOptimizer {
    val optimizerType: OptimizerType
    
    suspend fun optimize(input: OptimizationInput, config: OptimizationConfig): OptimizationResult
    
    suspend fun isAvailable(): Boolean
    
    fun getConfigurationRequirements(): List<String>
}

interface PromptOptimizerRegistry {
    fun getOptimizer(type: OptimizerType): PromptOptimizer?
    fun registerOptimizer(optimizer: PromptOptimizer)
    fun getAvailableOptimizers(): List<OptimizerType>
}

interface PromptOptimizationService {
    suspend fun runOptimizationExperiment(
        configurationSource: String,
        agentConfig: AgentConfig,
        optimizationConfig: OptimizationConfig
    ): OptimizationExperimentResult
    
    suspend fun optimizeFromExperimentResult(
        experimentResult: ExperimentResult,
        optimizationConfig: OptimizationConfig
    ): OptimizationExperimentResult
}

class PromptOptimizationException(
    message: String,
    cause: Throwable? = null,
    val optimizerType: OptimizerType? = null
) : Exception(message, cause)