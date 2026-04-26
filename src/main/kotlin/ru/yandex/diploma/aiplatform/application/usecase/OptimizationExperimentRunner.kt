package ru.yandex.diploma.aiplatform.application.usecase

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.yandex.diploma.aiplatform.domain.model.*
import ru.yandex.diploma.aiplatform.domain.service.PromptOptimizationService
import ru.yandex.diploma.aiplatform.domain.service.PromptOptimizationException

@Service
class OptimizationExperimentRunner(
    private val baseExperimentRunner: ExperimentRunner,
    private val promptOptimizationService: PromptOptimizationService
) {
    private val logger = LoggerFactory.getLogger(OptimizationExperimentRunner::class.java)
    
    suspend fun runExperimentWithOptimization(
        configurationSource: String,
        agentConfig: AgentConfig = AgentConfig.create("default", "Ты полезный ассистент."),
        optimizationConfig: OptimizationConfig? = null
    ): OptimizationExperimentResult {
        val startTime = System.currentTimeMillis()
        
        try {
            logger.info("Starting experiment with optimization support")
            
            if (optimizationConfig == null || !optimizationConfig.enabled) {
                logger.info("Optimization disabled, running standard experiment")
                val baselineResult = baseExperimentRunner.runExperiment(configurationSource, agentConfig)
                
                return OptimizationExperimentResult(
                    baselineResult = baselineResult,
                    optimizationResult = createEmptyOptimizationResult(),
                    optimizedExperimentResult = null,
                    improvement = null,
                    config = optimizationConfig ?: OptimizationConfig(enabled = false),
                    executionTimeMs = System.currentTimeMillis() - startTime
                )
            }
            
            logger.info("Running optimization experiment with ${optimizationConfig.type} optimizer")
            return promptOptimizationService.runOptimizationExperiment(
                configurationSource,
                agentConfig,
                optimizationConfig
            )
            
        } catch (e: PromptOptimizationException) {
            logger.error("Optimization experiment failed", e)
            throw e
        } catch (e: Exception) {
            logger.error("Experiment with optimization failed", e)
            
            try {
                logger.info("Falling back to standard experiment")
                val baselineResult = baseExperimentRunner.runExperiment(configurationSource, agentConfig)
                
                return OptimizationExperimentResult(
                    baselineResult = baselineResult,
                    optimizationResult = createErrorOptimizationResult(e),
                    optimizedExperimentResult = null,
                    improvement = null,
                    config = optimizationConfig ?: OptimizationConfig(enabled = false),
                    executionTimeMs = System.currentTimeMillis() - startTime
                )
            } catch (fallbackException: Exception) {
                logger.error("Fallback experiment also failed", fallbackException)
                throw PromptOptimizationException(
                    "Both optimization and fallback experiments failed: ${e.message}",
                    cause = e
                )
            }
        }
    }
    
    suspend fun runIterativeOptimization(
        configurationSource: String,
        agentConfig: AgentConfig,
        optimizationConfig: OptimizationConfig
    ): List<OptimizationExperimentResult> {
        val results = mutableListOf<OptimizationExperimentResult>()
        var currentConfigSource = configurationSource
        var currentAgentConfig = agentConfig
        
        logger.info("Starting iterative optimization with ${optimizationConfig.iterations} iterations")
        
        for (iteration in 1..optimizationConfig.iterations) {
            logger.info("Running optimization iteration $iteration/${optimizationConfig.iterations}")
            
            try {
                val result = runExperimentWithOptimization(
                    currentConfigSource,
                    currentAgentConfig,
                    optimizationConfig
                )
                
                results.add(result)
                
                if (result.optimizedExperimentResult != null && 
                    result.improvement?.significantImprovement == true) {
                    
                    logger.info("Iteration $iteration showed significant improvement, using optimized prompt for next iteration")
                }
                
            } catch (e: Exception) {
                logger.error("Optimization iteration $iteration failed", e)
                break
            }
        }
        
        logger.info("Iterative optimization completed with ${results.size} successful iterations")
        return results
    }
    
    suspend fun compareOptimizationStrategies(
        configurationSource: String,
        agentConfig: AgentConfig,
        optimizationConfigs: List<OptimizationConfig>
    ): Map<OptimizerType, OptimizationExperimentResult> {
        val results = mutableMapOf<OptimizerType, OptimizationExperimentResult>()
        
        logger.info("Comparing ${optimizationConfigs.size} optimization strategies")
        
        for (config in optimizationConfigs) {
            if (!config.enabled) continue
            
            try {
                logger.info("Testing ${config.type} optimizer")
                val result = runExperimentWithOptimization(configurationSource, agentConfig, config)
                results[config.type] = result
                
            } catch (e: Exception) {
                logger.error("Failed to test ${config.type} optimizer", e)
            }
        }
        
        logger.info("Strategy comparison completed with ${results.size} successful tests")
        return results
    }
    
    private fun createEmptyOptimizationResult(): OptimizationResult {
        return OptimizationResult(
            originalPrompt = Prompt("empty", "Empty", ""),
            optimizedPrompt = null,
            suggestions = emptyList(),
            confidence = 0.0,
            reasoning = "Optimization was not enabled",
            executionTimeMs = 0
        )
    }
    
    private fun createErrorOptimizationResult(error: Exception): OptimizationResult {
        return OptimizationResult(
            originalPrompt = Prompt("error", "Error", ""),
            optimizedPrompt = null,
            suggestions = listOf(
                OptimizationSuggestion(
                    type = SuggestionType.OTHER,
                    description = "Optimization failed",
                    originalText = null,
                    suggestedText = null,
                    impact = SuggestionImpact.LOW,
                    confidence = 0.0,
                    reasoning = "Optimization failed: ${error.message}"
                )
            ),
            confidence = 0.0,
            reasoning = "Optimization failed: ${error.message}",
            executionTimeMs = 0
        )
    }
}