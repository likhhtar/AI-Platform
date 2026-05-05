package ru.yandex.aiplatform

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
import java.nio.file.Files

class StrictEndToEndFailureDetectionTest {
    
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
        baselineRepository = FileBaselineRepository.forDirectory(Files.createTempDirectory("failure-e2e-baseline"))
        
        val deterministicProvider = DeterministicLlmProvider()
        providerRegistry = DefaultProviderRegistry(listOf(deterministicProvider))
        assertTrue(
            providerRegistry.getAvailableProviders().isNotEmpty(),
            "Provider registry must not be empty — regression checks require a registered LLM provider"
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
            configurationRepository = configurationRepository,
            baselineRepository = baselineRepository,
            regressionDetectionService = regressionDetectionService,
            providerValidationService = providerValidationService
        )
    }
    
    @Test
    fun `FAILURE DETECTION - Test with corrupted evaluationResult should be detected`() = runBlocking {
        val recordCfg = createStrictRegressionConfig().copy(
            failOnRegression = false,
            baselineMode = BaselinePersistenceMode.RECORD
        )
        baselineExperimentRunner.executeWithBaseline(
            configurationSource = "test-config",
            executionConfig = ExecutionConfig(enableParallelExecution = false),
            regressionConfig = recordCfg
        )
        
        val assertCfg = createStrictRegressionConfig().copy(
            failOnRegression = false,
            baselineMode = BaselinePersistenceMode.ASSERT
        )
        val currentResult = baselineExperimentRunner.executeWithBaseline(
            configurationSource = "test-config",
            executionConfig = ExecutionConfig(enableParallelExecution = false),
            regressionConfig = assertCfg
        )
        
        val corruptedResults = currentResult.testRun.results.map { result ->
            result.copy(
                evaluationResult = result.evaluationResult.copy(
                    score = 0.5,
                    explanation = "CORRUPTED: This should not match the evaluator output"
                )
            )
        }
        
        val corruptedTestRun = currentResult.testRun.copy(results = corruptedResults)
        
        var detectedCorruption = false
        try {
            validateEvaluationConsistency(corruptedTestRun, "CORRUPTED")
        } catch (e: AssertionError) {
            detectedCorruption = true
        }
        
        assertTrue(detectedCorruption, "Validation should have detected the corrupted evaluationResult")
    }
    
    @Test
    fun `FAILURE DETECTION - Test with metrics-derived evaluationResult should be detected`() = runBlocking {
        val result = baselineExperimentRunner.executeWithBaseline(
            configurationSource = "test-config",
            executionConfig = ExecutionConfig(enableParallelExecution = false)
        )
        
        val corruptedResults = result.testRun.results.map { testResult ->
            val metricsAverage = testResult.metrics.values.map { it.score }.average()
            testResult.copy(
                evaluationResult = testResult.evaluationResult.copy(
                    score = metricsAverage,
                    explanation = "CORRUPTED: Derived from metrics average"
                )
            )
        }
        
        val corruptedTestRun = result.testRun.copy(results = corruptedResults)
        
        var detectedMetricsDerivedScore = false
        try {
            validateMetricsIndependence(corruptedTestRun)
        } catch (e: AssertionError) {
            detectedMetricsDerivedScore = true
        }
        
        assertTrue(detectedMetricsDerivedScore, 
            "Validation should have detected evaluationResult derived from metrics")
    }
    
    @Test
    fun `SUCCESS CASE - Valid system should pass all validations`() = runBlocking {
        val recordCfg = createStrictRegressionConfig().copy(
            failOnRegression = false,
            baselineMode = BaselinePersistenceMode.RECORD
        )
        val baselineResult = baselineExperimentRunner.executeWithBaseline(
            configurationSource = "test-config",
            executionConfig = ExecutionConfig(enableParallelExecution = false),
            regressionConfig = recordCfg
        )
        
        val assertCfg = createStrictRegressionConfig().copy(
            failOnRegression = false,
            baselineMode = BaselinePersistenceMode.ASSERT
        )
        val currentResult = baselineExperimentRunner.executeWithBaseline(
            configurationSource = "test-config",
            executionConfig = ExecutionConfig(enableParallelExecution = false),
            regressionConfig = assertCfg
        )

        runBlocking {
            validateEvaluationConsistency(currentResult.testRun, "VALID")
        }
        validateDataIntegrityAcrossConversions(currentResult, baselineResult)
        validateRegressionCorrectness(currentResult.regressionAnalysis)
        validateMetricsIndependence(currentResult.testRun)

        assertEquals(RegressionStatus.PASS, currentResult.regressionAnalysis.summary.overallStatus)
        assertEquals(0, currentResult.regressionAnalysis.summary.testsWithRegressions)
        assertTrue(currentResult.regressionAnalysis.baselineRunId?.startsWith("persisted:") == true)
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
        }
    }
    
    private fun validateDataIntegrityAcrossConversions(
        originalResult: EnhancedTestSuiteResult,
        baselineResult: EnhancedTestSuiteResult
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
        regressionAnalysis: SuiteRegressionAnalysis
    ) {
        assertTrue(
            regressionAnalysis.baselineRunId?.startsWith("persisted:") == true,
            "Regression analysis must reference persisted baselines"
        )
    }
    
    private fun validateMetricsIndependence(testRun: TestRunRecord) {
        testRun.results.forEachIndexed { index, result ->
            assertTrue(result.metrics.isNotEmpty(),
                "TestResult[$index] must have metrics")
            
            val evaluationScore = result.evaluationResult.score
            val metricsScores = result.metrics.values.map { it.score }
            
            val metricsAverage = metricsScores.average()
            if (kotlin.math.abs(evaluationScore - metricsAverage) < 0.0001) {
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
    }
    
    private fun createTestConfigurationRepository(): TestConfigurationRepository {
        val testConfig = TestConfiguration(
            suiteMetadata = TestSuiteMetadata(
                name = "Failure Detection Test Suite",
                version = "1.0",
                description = "Test suite for failure detection verification"
            ),
            prompts = listOf(
                Prompt(
                    id = "test-prompt-1",
                    name = "Greeting Prompt",
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
                    variables = mapOf("name" to "World"),
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
                )
            ),
            failOnRegression = true
        )
    }
}