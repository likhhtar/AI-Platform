package ru.yandex.diploma.aiplatform.application.usecase

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import ru.yandex.diploma.aiplatform.domain.evaluator.EvaluatorRegistry
import ru.yandex.diploma.aiplatform.domain.model.*
import ru.yandex.diploma.aiplatform.domain.provider.LlmProvider
import ru.yandex.diploma.aiplatform.domain.provider.ProviderRegistry
import ru.yandex.diploma.aiplatform.domain.repository.TestConfiguration
import ru.yandex.diploma.aiplatform.domain.repository.TestConfigurationRepository
import ru.yandex.diploma.aiplatform.domain.service.ProviderValidationService
import ru.yandex.diploma.aiplatform.domain.service.ReportGenerator
import org.springframework.stereotype.Service
import java.io.File
import kotlin.time.Duration.Companion.minutes

@Service
class RunTestSuiteUseCase(
    private val configurationRepository: TestConfigurationRepository,
    private val providerRegistry: ProviderRegistry,
    private val evaluatorRegistry: EvaluatorRegistry,
    private val reportGenerator: ReportGenerator,
    private val providerValidationService: ProviderValidationService
) {
    private val logger = LoggerFactory.getLogger(RunTestSuiteUseCase::class.java)
    
    // configurationSource -- путь к файлу конфигурации или сама конфигурация
    suspend fun execute(
        configurationSource: String,
        executionConfig: ExecutionConfig = ExecutionConfig()
    ): TestSuiteResult {
        val startTime = System.currentTimeMillis()
        
        logger.info("Запуск выполнения тестового набора: maxParallelism=${executionConfig.maxParallelism}, timeout=${executionConfig.testTimeout}")
        
        val configuration = configurationRepository.loadConfiguration(configurationSource)
        val validationErrors = buildList {
            addAll(configuration.validate())
            addAll(providerValidationService.validateConfiguration(configuration))
        }
        if (validationErrors.isNotEmpty()) {
            throw TestSuiteException("Ошибка валидации конфигурации: ${validationErrors.joinToString(", ")}")
        }

        val testResults = if (executionConfig.enableParallelExecution) {
            executeTestsInParallel(configuration.tests, configuration, executionConfig)
        } else {
            executeTestsSequentially(configuration.tests, configuration, executionConfig)
        }

        val endTime = System.currentTimeMillis()
        val totalExecutionTime = endTime - startTime
        
        logger.info("Выполнение тестового набора завершено: ${testResults.size} тестов за ${totalExecutionTime}мс")

        val testSuiteResult = TestSuiteResult.create(
            results = testResults,
            executionTimeMs = totalExecutionTime
        )
        
        val reportFile = try {
            val testSuiteName = configuration.suiteMetadata.name
            reportGenerator.generate(testSuiteResult, testSuiteName)
        } catch (e: Exception) {
            logger.warn("Не удалось сгенерировать отчет: ${e.message}", e)
            null
        }
        
        return testSuiteResult.copy(reportFile = reportFile)
    }
    
    private suspend fun executeTestsInParallel(
        tests: List<TestCase>,
        configuration: TestConfiguration,
        executionConfig: ExecutionConfig
    ): List<TestResult> = coroutineScope {
        val semaphore = Semaphore(executionConfig.maxParallelism)
        
        tests.map { test ->
            async {
                semaphore.withPermit {
                    executeTestCaseWithTimeout(test, configuration, executionConfig)
                }
            }
        }.awaitAll()
    }
    
    private suspend fun executeTestsSequentially(
        tests: List<TestCase>,
        configuration: TestConfiguration,
        executionConfig: ExecutionConfig
    ): List<TestResult> {
        return tests.map { test ->
            executeTestCaseWithTimeout(test, configuration, executionConfig)
        }
    }
    
    private suspend fun executeTestCaseWithTimeout(
        testCase: TestCase,
        configuration: TestConfiguration,
        executionConfig: ExecutionConfig
    ): TestResult {
        return try {
            withTimeout(executionConfig.testTimeout) {
                executeTestCase(testCase, configuration)
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn("Тест превысил таймаут ${executionConfig.testTimeout}: ${testCase.promptId}")
            TestResult(
                testCase = testCase,
                success = false,
                evaluationResult = EvaluationResult(
                    passed = false,
                    score = 0.0,
                    explanation = "Выполнение теста превысило таймаут ${executionConfig.testTimeout}"
                ),
                llmResponse = null,
                executionTimeMs = executionConfig.testTimeout.inWholeMilliseconds,
                error = "Таймаут после ${executionConfig.testTimeout}",
                infrastructureError = true
            )
        } catch (e: Exception) {
            logger.error("Ошибка выполнения теста: ${testCase.promptId}", e)
            TestResult(
                testCase = testCase,
                success = false,
                evaluationResult = EvaluationResult(
                    passed = false,
                    score = 0.0,
                    explanation = "Ошибка выполнения теста: ${e.message}"
                ),
                llmResponse = null,
                executionTimeMs = 0,
                error = e.message,
                infrastructureError = true
            )
        }
    }

    private suspend fun executeTestCase(
        testCase: TestCase,
        configuration: TestConfiguration
    ): TestResult {
        return if (testCase.isMultiAgentTest()) {
            executeMultiAgentTestCase(testCase, configuration)
        } else {
            executeSingleAgentTestCase(testCase, configuration)
        }
    }
    
    private suspend fun executeSingleAgentTestCase(
        testCase: TestCase,
        configuration: TestConfiguration
    ): TestResult {
        val startTime = System.currentTimeMillis()

        val prompt = configuration.getPrompt(testCase.promptId)
            ?: throw TestSuiteException("Prompt not found: ${testCase.promptId}")
        
        val agentName = testCase.getAllAgentNames().first()
        val agent = configuration.getAgent(agentName)
            ?: throw TestSuiteException("Agent not found: $agentName")

        val missingVariables = prompt.validateVariables(testCase.variables)
        if (missingVariables.isNotEmpty()) {
            throw TestSuiteException("Missing variables for prompt ${prompt.id}: ${missingVariables.joinToString(", ")}")
        }

        val renderedPrompt = prompt.render(testCase.variables)
        val provider = providerRegistry.getProvider(agent.provider)

        val llmRequest = LlmRequest(
            prompt = renderedPrompt,
            systemPrompt = agent.systemPrompt,
            model = agent.model,
            temperature = agent.temperature,
            maxTokens = agent.maxTokens,
            topP = agent.topP,
            frequencyPenalty = agent.frequencyPenalty,
            presencePenalty = agent.presencePenalty,
            additionalParameters = agent.additionalParameters
        )

        val llmResponse = provider.generate(llmRequest)

        val evaluator = evaluatorRegistry.getEvaluator(testCase.evaluatorType)
        val evaluationResult = evaluator.evaluate(
            output = llmResponse.content,
            expected = testCase.expected,
            metadata = testCase.metadata
        )

        val endTime = System.currentTimeMillis()

        return TestResult(
            testCase = testCase,
            success = evaluationResult.passed,
            evaluationResult = evaluationResult,
            llmResponse = llmResponse,
            executionTimeMs = endTime - startTime,
            error = null,
            provider = agent.provider
        )
    }
    
    private suspend fun executeMultiAgentTestCase(
        testCase: TestCase,
        configuration: TestConfiguration
    ): TestResult {
        val startTime = System.currentTimeMillis()

        val prompt = configuration.getPrompt(testCase.promptId)
            ?: throw TestSuiteException("Prompt not found: ${testCase.promptId}")

        val missingVariables = prompt.validateVariables(testCase.variables)
        if (missingVariables.isNotEmpty()) {
            throw TestSuiteException("Missing variables for prompt ${prompt.id}: ${missingVariables.joinToString(", ")}")
        }

        val renderedPrompt = prompt.render(testCase.variables)
        val evaluator = evaluatorRegistry.getEvaluator(testCase.evaluatorType)

        val agentResults = coroutineScope {
            testCase.getAllAgentNames().map { agentName ->
                async {
                    try {
                        val agent = configuration.getAgent(agentName)
                            ?: throw TestSuiteException("Agent not found: $agentName")
                        
                        val agentStartTime = System.currentTimeMillis()
                        val provider = providerRegistry.getProvider(agent.provider)

                        val llmRequest = LlmRequest(
                            prompt = renderedPrompt,
                            systemPrompt = agent.systemPrompt,
                            model = agent.model,
                            temperature = agent.temperature,
                            maxTokens = agent.maxTokens,
                            topP = agent.topP,
                            frequencyPenalty = agent.frequencyPenalty,
                            presencePenalty = agent.presencePenalty,
                            additionalParameters = agent.additionalParameters
                        )

                        val llmResponse = provider.generate(llmRequest)
                        val evaluationResult = evaluator.evaluate(
                            output = llmResponse.content,
                            expected = testCase.expected,
                            metadata = testCase.metadata
                        )
                        
                        val agentEndTime = System.currentTimeMillis()

                        agentName to SingleAgentResult(
                            agentName = agentName,
                            success = evaluationResult.passed,
                            evaluationResult = evaluationResult,
                            llmResponse = llmResponse,
                            executionTimeMs = agentEndTime - agentStartTime,
                            error = null
                        )
                    } catch (e: Exception) {
                        agentName to SingleAgentResult(
                            agentName = agentName,
                            success = false,
                            evaluationResult = EvaluationResult(
                                passed = false,
                                score = 0.0,
                                explanation = "Agent execution failed: ${e.message}"
                            ),
                            llmResponse = null,
                            executionTimeMs = 0,
                            error = e.message
                        )
                    }
                }
            }.awaitAll().toMap()
        }

        val endTime = System.currentTimeMillis()
        
        val comparisonResult = ComparisonResult(
            testCase = testCase,
            agentResults = agentResults,
            executionTimeMs = endTime - startTime
        )

        val bestResult = agentResults.values.maxByOrNull { it.evaluationResult.score }
            ?: agentResults.values.first()
        val anyInfrastructureFailure = agentResults.values.any { it.error != null }

        return TestResult(
            testCase = testCase,
            success = comparisonResult.success,
            evaluationResult = bestResult.evaluationResult,
            llmResponse = bestResult.llmResponse,
            executionTimeMs = endTime - startTime,
            error = null,
            infrastructureError = anyInfrastructureFailure,
            provider = "multi-agent"
        )
    }
}

data class TestSuiteResult(
    val total: Int,
    val passed: Int,
    val failed: Int,
    val results: List<TestResult>,
    val executionTimeMs: Long,
    val metrics: TestSuiteMetrics,
    val reportFile: File? = null,
    val optimizationSummary: OptimizationSummary? = null,
    val qaSummary: QASummary? = null
) {
    val successRate: Double = if (total > 0) passed.toDouble() / total else 0.0
    
    companion object {
        fun create(
            results: List<TestResult>,
            executionTimeMs: Long,
            optimizationSummary: OptimizationSummary? = null,
            qaSummary: QASummary? = null
        ): TestSuiteResult {
            val metrics = TestSuiteMetrics.calculate(results)
            
            return TestSuiteResult(
                total = results.size,
                passed = results.count { it.success },
                failed = results.count { !it.success },
                results = results,
                executionTimeMs = executionTimeMs,
                metrics = metrics,
                optimizationSummary = optimizationSummary,
                qaSummary = qaSummary
            )
        }
    }
}

data class OptimizationSummary(
    val baselineAccuracy: Double,
    val optimizedAccuracy: Double,
    val improvement: Double,
    val hasOptimization: Boolean,
    val optimizedTestsCount: Int,
    val averageImprovement: Double
)

data class QASummary(
    val paraphraseAccuracy: Double,
    val trapAccuracy: Double,
    val unseenAccuracy: Double,
    val consistencyScore: Double,
    val generalizationScore: Double,
    val redFlags: List<String>,
    val suspiciousTestsCount: Int,
    val finalVerdict: QAVerdict
)

enum class QAVerdict {
    LEGITIMATE,
    SUSPICIOUS,
    FAILED
}

class TestSuiteException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)