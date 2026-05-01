package ru.yandex.aiplatform

import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import ru.yandex.diploma.aiplatform.application.usecase.*
import ru.yandex.diploma.aiplatform.config.JudgeEvaluationProperties
import ru.yandex.diploma.aiplatform.domain.evaluator.*
import ru.yandex.diploma.aiplatform.domain.model.*
import ru.yandex.diploma.aiplatform.domain.provider.*
import ru.yandex.diploma.aiplatform.domain.repository.*
import ru.yandex.diploma.aiplatform.domain.service.*
import ru.yandex.diploma.aiplatform.infrastructure.evaluator.*
import ru.yandex.diploma.aiplatform.infrastructure.llm.*
import ru.yandex.diploma.aiplatform.infrastructure.repository.*
import ru.yandex.diploma.aiplatform.infrastructure.service.*
import ru.yandex.diploma.aiplatform.infrastructure.yaml.*

class StrictEndToEndVerificationTest {
    
    private lateinit var baselineRepository: FileBaselineRepository
    private lateinit var configurationRepository: TestConfigurationRepository
    private lateinit var providerRegistry: ProviderRegistry
    private lateinit var evaluatorRegistry: EvaluatorRegistry
    private lateinit var multiMetricEvaluationService: MultiMetricEvaluationService
    private lateinit var regressionDetectionService: RegressionDetectionService
    private lateinit var runTestSuiteUseCase: RunTestSuiteUseCase
    private lateinit var baselineExperimentRunner: BaselineExperimentRunner
    private lateinit var reportGenerator: ReportGenerator
    
    @BeforeEach
    fun setup() {
        baselineRepository = FileBaselineRepository.forDirectory(java.nio.file.Files.createTempDirectory("verify-e2e"))

        val deterministicProvider = DeterministicLlmProvider()
        providerRegistry = DefaultProviderRegistry(listOf(deterministicProvider))
        assertTrue(
            providerRegistry.getAvailableProviders().isNotEmpty(),
            "Provider registry must not be empty"
        )

        evaluatorRegistry = DefaultEvaluatorRegistry(listOf(
            ExactMatchEvaluator(),
            ContainsEvaluator()
        ))

        val metricRegistry = DefaultMetricRegistry()
        multiMetricEvaluationService = MultiMetricEvaluationService(metricRegistry)

        regressionDetectionService = RegressionDetectionService()

        reportGenerator = QAVerificationHtmlReportGenerator()

        configurationRepository = createTestConfigurationRepository()

        val providerValidationService = DefaultProviderValidationService(providerRegistry, JudgeEvaluationProperties())
        runTestSuiteUseCase = RunTestSuiteUseCase(
            configurationRepository = configurationRepository,
            providerRegistry = providerRegistry,
            evaluatorRegistry = evaluatorRegistry,
            reportGenerator = reportGenerator,
            providerValidationService = providerValidationService
        )
        
        baselineExperimentRunner = BaselineExperimentRunner(
            runTestSuiteUseCase = runTestSuiteUseCase,
            optimizationExperimentRunner = mockk(relaxed = true),
            configurationRepository = configurationRepository,
            baselineRepository = baselineRepository,
            regressionDetectionService = regressionDetectionService,
            providerValidationService = providerValidationService,
            optimizationHtmlReportGenerator = OptimizationHtmlReportGenerator(),
        )
    }
    
    @Test
    fun `STRICT VERIFICATION - Full pipeline with evaluation consistency validation`() = runBlocking {
        val recordCfg = createStrictRegressionConfig().copy(
            failOnRegression = false,
            baselineMode = BaselinePersistenceMode.RECORD
        )
        val baselineResult = baselineExperimentRunner.executeWithBaseline(
            configurationSource = "test-config",
            executionConfig = ExecutionConfig(enableParallelExecution = false),
            regressionConfig = recordCfg
        )
        
        validateEvaluationConsistency(baselineResult.testRun, "BASELINE")

        val assertCfg = createStrictRegressionConfig().copy(
            failOnRegression = false,
            baselineMode = BaselinePersistenceMode.ASSERT
        )
        val currentResult = baselineExperimentRunner.executeWithBaseline(
            configurationSource = "test-config",
            executionConfig = ExecutionConfig(enableParallelExecution = false),
            regressionConfig = assertCfg
        )
        
        validateEvaluationConsistency(currentResult.testRun, "CURRENT")

        validateDataIntegrityAcrossConversions(
            originalResult = currentResult,
            baselineResult = baselineResult
        )

        validateRegressionCorrectness(
            currentRun = currentResult.testRun,
            regressionAnalysis = currentResult.regressionAnalysis
        )

        validateMetricsIndependence(currentResult.testRun)

        assertEquals(RegressionStatus.PASS, currentResult.regressionAnalysis.summary.overallStatus)
        assertEquals(0, currentResult.regressionAnalysis.summary.testsWithRegressions)
    }

    private suspend fun validateEvaluationConsistency(testRun: TestRunRecord, phase: String) {
        testRun.results.forEachIndexed { index, result ->
            assertNotNull(result.evaluationResult,
                "$phase: TestResult[$index] must have evaluationResult")

            val evaluator = evaluatorRegistry.getEvaluator(result.testCase.evaluatorType)
            val freshEvaluation = evaluator.evaluate(
                output = result.llmResponse?.content ?: "",
                expected = result.testCase.expected,
                metadata = result.testCase.metadata
            )

            assertEquals(
                freshEvaluation.passed, 
                result.evaluationResult.passed,
                "$phase: TestResult[$index] evaluationResult.passed must match fresh evaluator output"
            )
            
            assertEquals(
                freshEvaluation.score, 
                result.evaluationResult.score, 
                0.0001,
                "$phase: TestResult[$index] evaluationResult.score must match fresh evaluator output"
            )

            if (result.metrics.isNotEmpty()) {
                val metricsBasedScore = result.metrics.values.map { it.score }.average()
                assertNotEquals(
                    metricsBasedScore,
                    result.evaluationResult.score,
                    0.0001,
                    "$phase: TestResult[$index] evaluationResult.score must NOT be derived from metrics average"
                )
            }
        }
    }

    private fun validateDataIntegrityAcrossConversions(
        originalResult: EnhancedTestSuiteResult,
        @Suppress("UNUSED_PARAMETER") baselineResult: EnhancedTestSuiteResult
    ) {
        originalResult.testRun.results.forEachIndexed { index, convertedResult ->
            assertNotNull(convertedResult.evaluationResult,
                "Converted TestResult[$index] must preserve evaluationResult")

            assertTrue(convertedResult.evaluationResult.score >= 0.0 && convertedResult.evaluationResult.score <= 1.0,
                "Converted TestResult[$index] evaluationResult.score must be in valid range")
        }

        assertNotNull(originalResult.testRun, "EnhancedTestSuiteResult must contain testRun")
        assertNotNull(originalResult.regressionAnalysis, "EnhancedTestSuiteResult must contain regressionAnalysis")
    }

    private fun validateRegressionCorrectness(
        currentRun: TestRunRecord,
        regressionAnalysis: SuiteRegressionAnalysis
    ) {
        assertTrue(
            regressionAnalysis.baselineRunId?.startsWith("persisted:") == true,
            "Regression analysis must reference persisted baselines"
        )
        
        assertEquals(currentRun.runId, regressionAnalysis.currentRunId,
            "Regression analysis must reference correct current run")
        
        regressionAnalysis.testAnalyses.forEachIndexed { index, testAnalysis ->
            val currentResult = testAnalysis.currentResult
            val baselineEntry = testAnalysis.baselineEntry ?: return@forEachIndexed
            
            val correctnessRegression = testAnalysis.regressions.find { it.metricName == "correctness" }
            if (correctnessRegression != null) {
                assertEquals(
                    currentResult.evaluationResult.score,
                    correctnessRegression.currentValue,
                    0.0001,
                    "Regression analysis must use evaluationResult.score for correctness"
                )
                
                val storedCorrectness = baselineEntry.metrics["correctness"]
                assertNotNull(storedCorrectness)
                assertEquals(
                    storedCorrectness!!,
                    correctnessRegression.baselineValue,
                    0.0001,
                    "Regression analysis must use persisted correctness score"
                )
            }
            
            testAnalysis.regressions.filter { it.metricName != "correctness" }.forEach { regression ->
                val currentMetric = currentResult.metrics[regression.metricName]
                val baselineStored = baselineEntry.metrics[regression.metricName]
                
                if (currentMetric != null && baselineStored != null) {
                    assertEquals(
                        currentMetric.score,
                        regression.currentValue,
                        0.0001,
                        "Regression analysis must use metric score for ${regression.metricName}"
                    )
                    
                    assertEquals(
                        baselineStored,
                        regression.baselineValue,
                        0.0001,
                        "Regression analysis must use persisted metric for ${regression.metricName}"
                    )
                }
            }
        }
    }

    private suspend fun validateMetricsIndependence(testRun: TestRunRecord) {
        testRun.results.forEachIndexed { index, result ->
            assertTrue(result.metrics.isNotEmpty(),
                "TestResult[$index] must have metrics")

            val evaluationScore = result.evaluationResult.score
            val metricsScores = result.metrics.values.map { it.score }

            metricsScores.forEach { metricScore ->
                if (kotlin.math.abs(evaluationScore - metricScore) < 0.0001) {
                    val evaluator = evaluatorRegistry.getEvaluator(result.testCase.evaluatorType)
                    val freshEvaluation = evaluator.evaluate(
                        output = result.llmResponse?.content ?: "",
                        expected = result.testCase.expected,
                        metadata = result.testCase.metadata
                    )
                    
                    assertEquals(
                        freshEvaluation.score,
                        evaluationScore,
                        0.0001,
                        "TestResult[$index] evaluationResult must come from evaluator, not metrics"
                    )
                }
            }

            result.metrics.forEach { (metricName, metric) ->
                assertTrue(metric.score >= 0.0 && metric.score <= 1.0,
                    "TestResult[$index] metric '$metricName' score must be in valid range")
                assertNotNull(metric.explanation,
                    "TestResult[$index] metric '$metricName' must have explanation")
            }
        }
    }

    private fun createTestConfigurationRepository(): TestConfigurationRepository {
        val testConfig = TestConfiguration(
            suiteMetadata = TestSuiteMetadata(
                name = "Strict Verification Test Suite",
                version = "1.0",
                description = "Test suite for strict end-to-end verification"
            ),
            prompts = listOf(
                Prompt(
                    id = "test-prompt-1",
                    name = "Greeting Prompt 1",
                    template = "Say hello to {{name}}",
                    variables = setOf("name")
                ),
                Prompt(
                    id = "test-prompt-2",
                    name = "Greeting Prompt 2",
                    template = "Say hello to {{name}}",
                    variables = setOf("name")
                )
            ),
            agents = listOf(
                AgentConfig(
                    name = "test-agent",
                    provider = "deterministic",
                    model = "deterministic-model-v1",
                    systemPrompt = "You are a helpful assistant.",
                    temperature = 0.7,
                    maxTokens = 100
                )
            ),
            tests = listOf(
                TestCase(
                    promptId = "test-prompt-1",
                    agentName = "test-agent",
                    variables = mapOf("name" to "Alice"),
                    expected = "Hello",
                    evaluatorType = "contains",
                    metadata = mapOf("caseSensitive" to false)
                ),
                TestCase(
                    promptId = "test-prompt-2",
                    agentName = "test-agent",
                    variables = mapOf("name" to "Bob"),
                    expected = "Hello",
                    evaluatorType = "contains",
                    metadata = mapOf("caseSensitive" to false)
                ),
                TestCase(
                    promptId = "test-prompt-1",
                    agentName = "test-agent",
                    variables = mapOf("name" to "Carol"),
                    expected = "Hello",
                    evaluatorType = "contains",
                    metadata = mapOf("caseSensitive" to false)
                )
            )
        )
        
        return object : TestConfigurationRepository {
            override suspend fun loadConfiguration(source: String): TestConfiguration = testConfig
            override suspend fun validateConfiguration(source: String): List<String> = emptyList()
        }
    }

    private fun createStrictRegressionConfig(): RegressionConfiguration {
        return RegressionConfiguration(
            rules = listOf(
                RegressionRule(
                    metricName = "correctness",
                    threshold = 0.1,
                    type = RegressionType.RELATIVE,
                    severity = RegressionSeverity.ERROR
                ),
                RegressionRule(
                    metricName = "latency",
                    threshold = 0.2,
                    type = RegressionType.RELATIVE,
                    severity = RegressionSeverity.WARNING
                ),
                RegressionRule(
                    metricName = "token_usage",
                    threshold = 0.15,
                    type = RegressionType.RELATIVE,
                    severity = RegressionSeverity.WARNING
                )
            ),
            failOnRegression = true
        )
    }
}