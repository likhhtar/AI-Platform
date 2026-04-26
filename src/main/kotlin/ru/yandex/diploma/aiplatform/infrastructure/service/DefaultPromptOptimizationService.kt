package ru.yandex.diploma.aiplatform.infrastructure.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.yandex.diploma.aiplatform.application.usecase.ExperimentResult
import ru.yandex.diploma.aiplatform.application.usecase.ExperimentRunner
import ru.yandex.diploma.aiplatform.application.usecase.RunTestSuiteUseCase
import ru.yandex.diploma.aiplatform.domain.model.*
import ru.yandex.diploma.aiplatform.domain.repository.TestConfigurationRepository
import ru.yandex.diploma.aiplatform.domain.service.PromptOptimizationService
import ru.yandex.diploma.aiplatform.domain.service.PromptOptimizerRegistry
import ru.yandex.diploma.aiplatform.domain.service.PromptOptimizationException

@Service
class DefaultPromptOptimizationService(
    private val optimizerRegistry: PromptOptimizerRegistry,
    private val experimentRunner: ExperimentRunner,
    private val runTestSuiteUseCase: RunTestSuiteUseCase,
    private val testConfigurationRepository: TestConfigurationRepository
) : PromptOptimizationService {
    
    private val logger = LoggerFactory.getLogger(DefaultPromptOptimizationService::class.java)
    
    override suspend fun runOptimizationExperiment(
        configurationSource: String,
        agentConfig: AgentConfig,
        optimizationConfig: OptimizationConfig
    ): OptimizationExperimentResult {
        val startTime = System.currentTimeMillis()
        
        try {
            logger.info("Starting optimization experiment with optimizer: ${optimizationConfig.type}")
            
            if (!optimizationConfig.enabled) {
                throw PromptOptimizationException("Optimization is disabled in configuration")
            }
            
            logger.info("Running baseline experiment...")
            val baselineResult = experimentRunner.runExperiment(configurationSource, agentConfig)
            
            if (!baselineResult.runs.any { it.success }) {
                throw PromptOptimizationException("Baseline experiment failed - cannot proceed with optimization")
            }
            
            val optimizationResult = optimizeFromExperimentResult(baselineResult, optimizationConfig)
            
            val executionTime = System.currentTimeMillis() - startTime
            
            logger.info("Optimization experiment completed in ${executionTime}ms")
            
            return OptimizationExperimentResult(
                baselineResult = baselineResult,
                optimizationResult = optimizationResult.optimizationResult,
                optimizedExperimentResult = optimizationResult.optimizedExperimentResult,
                improvement = optimizationResult.improvement,
                config = optimizationConfig,
                executionTimeMs = executionTime
            )
            
        } catch (e: PromptOptimizationException) {
            throw e
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            logger.error("Optimization experiment failed", e)
            throw PromptOptimizationException(
                "Optimization experiment failed: ${e.message}",
                cause = e
            )
        }
    }
    
    override suspend fun optimizeFromExperimentResult(
        experimentResult: ExperimentResult,
        optimizationConfig: OptimizationConfig
    ): OptimizationExperimentResult {
        val startTime = System.currentTimeMillis()
        
        try {
            logger.info("Optimizing from existing experiment result")
            
            val optimizer = optimizerRegistry.getOptimizer(optimizationConfig.type)
                ?: throw PromptOptimizationException("Optimizer ${optimizationConfig.type} not available")
            
            if (!optimizer.isAvailable()) {
                throw PromptOptimizationException("Optimizer ${optimizationConfig.type} is not available")
            }
            
            val optimizationInput = extractOptimizationInput(experimentResult)
            
            val optimizationResult = optimizer.optimize(optimizationInput, optimizationConfig)
            
            var optimizedExperimentResult: ExperimentResult? = null
            var improvement: OptimizationImprovement? = null
            
            if (optimizationConfig.mode == OptimizationMode.APPLY && optimizationResult.optimizedPrompt != null) {
                logger.info("Re-running experiment with optimized prompt...")
                
                try {
                    val modifiedConfigSource = createModifiedConfiguration(experimentResult, optimizationResult.optimizedPrompt)
                    
                    optimizedExperimentResult = experimentRunner.runExperiment(
                        modifiedConfigSource,
                        experimentResult.agentConfig
                    )
                    
                    improvement = calculateImprovement(experimentResult, optimizedExperimentResult)
                    
                } catch (e: Exception) {
                    logger.warn("Failed to re-run experiment with optimized prompt", e)
                }
            }
            
            val executionTime = System.currentTimeMillis() - startTime
            
            return OptimizationExperimentResult(
                baselineResult = experimentResult,
                optimizationResult = optimizationResult,
                optimizedExperimentResult = optimizedExperimentResult,
                improvement = improvement,
                config = optimizationConfig,
                executionTimeMs = executionTime
            )
            
        } catch (e: PromptOptimizationException) {
            throw e
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            logger.error("Optimization from experiment result failed", e)
            throw PromptOptimizationException(
                "Optimization from experiment result failed: ${e.message}",
                cause = e
            )
        }
    }
    
    private fun extractOptimizationInput(experimentResult: ExperimentResult): OptimizationInput {
        val successfulRun = experimentResult.runs.firstOrNull { it.success && it.result != null }
            ?: throw PromptOptimizationException("No successful runs found in experiment result")
        
        val testSuiteResult = successfulRun.result!!
        
        val firstTestResult = testSuiteResult.results.firstOrNull()
            ?: throw PromptOptimizationException("No test results found")
        
        val prompt = Prompt(
            id = firstTestResult.testCase.promptId,
            name = firstTestResult.testCase.promptId,
            template = "Extracted from test case", // This would need to be properly extracted
            variables = firstTestResult.testCase.variables.keys
        )
        
        return OptimizationInput(
            originalPrompt = prompt,
            testCases = testSuiteResult.results.map { it.testCase },
            testResults = testSuiteResult.results,
            agentConfig = experimentResult.agentConfig
        )
    }
    
    private suspend fun createModifiedConfiguration(
        experimentResult: ExperimentResult,
        optimizedPrompt: Prompt
    ): String {
        return """
suite:
  name: "Optimized Test Suite"
  version: "1.0"
  description: "Test suite with optimized prompt"

prompts:
  - id: "${optimizedPrompt.id}"
    template: ${yamlDoubleQuoted(optimizedPrompt.template)}

agents:
  - name: "${experimentResult.agentConfig.name}"
    systemPrompt: ${yamlDoubleQuoted(experimentResult.agentConfig.systemPrompt)}
    model: "${experimentResult.agentConfig.model ?: "default"}"
    provider: "${experimentResult.agentConfig.provider}"

tests:
  - promptId: "${optimizedPrompt.id}"
    agents: ["${experimentResult.agentConfig.name}"]
    variables: {}
    expected: "test"
    evaluator: "contains"
        """.trimIndent()
    }

    private fun yamlDoubleQuoted(value: String): String =
        '"' + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + '"'
    
    private fun calculateImprovement(
        baseline: ExperimentResult,
        optimized: ExperimentResult
    ): OptimizationImprovement {
        val baselineMetrics = baseline.metrics
        val optimizedMetrics = optimized.metrics
        
        val scoreImprovement = optimizedMetrics.averageScore - baselineMetrics.averageScore
        val latencyChange = optimizedMetrics.averageLatency - baselineMetrics.averageLatency
        val passRateImprovement = (optimizedMetrics.successfulRuns.toDouble() / optimizedMetrics.totalRuns) -
                (baselineMetrics.successfulRuns.toDouble() / baselineMetrics.totalRuns)
        
        val significantImprovement = scoreImprovement > 0.1 || passRateImprovement > 0.1
        
        return OptimizationImprovement(
            scoreImprovement = scoreImprovement,
            latencyChange = latencyChange,
            passRateImprovement = passRateImprovement,
            significantImprovement = significantImprovement,
            detailedMetrics = mapOf(
                "baselineAverageScore" to baselineMetrics.averageScore,
                "optimizedAverageScore" to optimizedMetrics.averageScore,
                "baselineLatency" to baselineMetrics.averageLatency,
                "optimizedLatency" to optimizedMetrics.averageLatency,
                "baselinePassRate" to (baselineMetrics.successfulRuns.toDouble() / baselineMetrics.totalRuns),
                "optimizedPassRate" to (optimizedMetrics.successfulRuns.toDouble() / optimizedMetrics.totalRuns)
            )
        )
    }
}