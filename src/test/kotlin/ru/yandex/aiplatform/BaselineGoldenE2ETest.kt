package ru.yandex.aiplatform

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.yandex.diploma.aiplatform.application.usecase.BaselineExperimentRunner
import ru.yandex.diploma.aiplatform.application.usecase.RunTestSuiteUseCase
import ru.yandex.diploma.aiplatform.domain.evaluator.EvaluatorRegistry
import ru.yandex.diploma.aiplatform.domain.model.AgentConfig
import ru.yandex.diploma.aiplatform.domain.model.BaselineKeys
import ru.yandex.diploma.aiplatform.domain.model.BaselinePersistenceMode
import ru.yandex.diploma.aiplatform.domain.model.ExecutionConfig
import ru.yandex.diploma.aiplatform.domain.model.Prompt
import ru.yandex.diploma.aiplatform.domain.model.RegressionConfiguration
import ru.yandex.diploma.aiplatform.domain.model.RegressionRule
import ru.yandex.diploma.aiplatform.domain.model.RegressionSeverity
import ru.yandex.diploma.aiplatform.domain.model.RegressionStatus
import ru.yandex.diploma.aiplatform.domain.model.RegressionType
import ru.yandex.diploma.aiplatform.domain.model.TestCase
import ru.yandex.diploma.aiplatform.domain.model.LlmRequest
import ru.yandex.diploma.aiplatform.domain.model.LlmResponse
import ru.yandex.diploma.aiplatform.domain.model.TestSuiteMetadata
import ru.yandex.diploma.aiplatform.domain.provider.LlmProvider
import ru.yandex.diploma.aiplatform.domain.provider.LlmProviderException
import ru.yandex.diploma.aiplatform.domain.provider.ProviderRegistry
import ru.yandex.diploma.aiplatform.domain.repository.TestConfiguration
import ru.yandex.diploma.aiplatform.domain.repository.TestConfigurationRepository
import ru.yandex.diploma.aiplatform.domain.service.RegressionDetectionService
import ru.yandex.diploma.aiplatform.config.JudgeEvaluationProperties
import ru.yandex.diploma.aiplatform.infrastructure.evaluator.DefaultEvaluatorRegistry
import ru.yandex.diploma.aiplatform.infrastructure.evaluator.ContainsEvaluator
import ru.yandex.diploma.aiplatform.infrastructure.evaluator.ExactMatchEvaluator
import ru.yandex.diploma.aiplatform.infrastructure.llm.DefaultProviderRegistry
import ru.yandex.diploma.aiplatform.infrastructure.llm.DeterministicLlmProvider
import ru.yandex.diploma.aiplatform.infrastructure.repository.FileBaselineRepository
import ru.yandex.diploma.aiplatform.infrastructure.service.DefaultProviderValidationService
import ru.yandex.diploma.aiplatform.infrastructure.service.QAVerificationHtmlReportGenerator
import java.nio.file.Files
import kotlin.test.assertFailsWith

class BaselineGoldenE2ETest {

    private lateinit var baselineRepository: FileBaselineRepository
    private lateinit var goldenConfiguration: TestConfiguration
    private lateinit var configurationRepository: TestConfigurationRepository
    private lateinit var baselineExperimentRunner: BaselineExperimentRunner

    private val configurationSource = "golden-e2e-inline"

    @BeforeEach
    fun setup() {
        baselineRepository = FileBaselineRepository.forDirectory(Files.createTempDirectory("golden-e2e"))

        goldenConfiguration = TestConfiguration(
            suiteMetadata = TestSuiteMetadata(
                name = "Golden Baseline E2E Suite",
                version = "1.0",
                description = "Golden path baseline + regression"
            ),
            prompts = listOf(
                Prompt(
                    id = "golden-prompt",
                    name = "Greeting",
                    template = "Say hello to {{name}}",
                    variables = setOf("name")
                )
            ),
            agents = listOf(
                AgentConfig(
                    name = "golden-agent",
                    provider = "deterministic",
                    model = "deterministic-model-v1",
                    systemPrompt = "You are a test assistant.",
                    temperature = 0.0,
                    maxTokens = 100
                )
            ),
            tests = listOf(
                TestCase(
                    promptId = "golden-prompt",
                    agentName = "golden-agent",
                    variables = mapOf("name" to "World"),
                    expected = "__NEVER_PRESENT_IN_DETERMINISTIC_OUTPUT__",
                    evaluatorType = "contains",
                    metadata = mapOf("caseSensitive" to false)
                )
            )
        )

        configurationRepository = object : TestConfigurationRepository {
            override suspend fun loadConfiguration(source: String): TestConfiguration = goldenConfiguration
            override suspend fun validateConfiguration(source: String): List<String> = emptyList()
        }

        val providerRegistry: ProviderRegistry = DefaultProviderRegistry(listOf(DeterministicLlmProvider()))
        assertTrue(
            providerRegistry.getAvailableProviders().isNotEmpty(),
            "Golden E2E requires a non-empty provider registry"
        )

        val evaluatorRegistry: EvaluatorRegistry = DefaultEvaluatorRegistry(
            listOf(ExactMatchEvaluator(), ContainsEvaluator())
        )

        val providerValidationService = DefaultProviderValidationService(providerRegistry, JudgeEvaluationProperties())
        val runTestSuiteUseCase = RunTestSuiteUseCase(
            configurationRepository = configurationRepository,
            providerRegistry = providerRegistry,
            evaluatorRegistry = evaluatorRegistry,
            reportGenerator = QAVerificationHtmlReportGenerator(),
            providerValidationService = providerValidationService
        )

        baselineExperimentRunner = BaselineExperimentRunner(
            runTestSuiteUseCase = runTestSuiteUseCase,
            configurationRepository = configurationRepository,
            baselineRepository = baselineRepository,
            regressionDetectionService = RegressionDetectionService(),
            providerValidationService = providerValidationService
        )
    }

    @Test
    fun `golden path disk baseline tamper then ASSERT detects regression`() = runBlocking {
        val suiteId = BaselineKeys.suiteId(configurationSource, goldenConfiguration.suiteMetadata)

        val recordConfig = RegressionConfiguration(
            rules = listOf(
                RegressionRule(
                    metricName = "correctness",
                    threshold = 0.05,
                    type = RegressionType.RELATIVE,
                    severity = RegressionSeverity.ERROR
                )
            ),
            failOnRegression = false,
            baselineMode = BaselinePersistenceMode.RECORD
        )

        baselineExperimentRunner.executeWithBaseline(
            configurationSource = configurationSource,
            executionConfig = ExecutionConfig(enableParallelExecution = false),
            regressionConfig = recordConfig
        )

        val storedAfterRecord = baselineRepository.loadAll(suiteId)
        assertEquals(1, storedAfterRecord.size, "RECORD should persist exactly one baseline entry")
        val (testCaseId, entry) = storedAfterRecord.entries.single()
        assertEquals(0.0, entry.metrics["correctness"]!!, 0.0001)

        baselineRepository.saveBaseline(
            suiteId,
            testCaseId,
            entry.copy(metrics = entry.metrics + ("correctness" to 1.0))
        )

        val assertConfig = RegressionConfiguration(
            rules = listOf(
                RegressionRule(
                    metricName = "correctness",
                    threshold = 0.05,
                    type = RegressionType.RELATIVE,
                    severity = RegressionSeverity.ERROR
                )
            ),
            failOnRegression = false,
            baselineMode = BaselinePersistenceMode.ASSERT
        )

        val assertResult = baselineExperimentRunner.executeWithBaseline(
            configurationSource = configurationSource,
            executionConfig = ExecutionConfig(enableParallelExecution = false),
            regressionConfig = assertConfig
        )

        val analysis = assertResult.regressionAnalysis
        assertTrue(analysis.summary.testsWithRegressions > 0, "Expected at least one test with regressions")
        assertEquals(RegressionStatus.FAILURE, analysis.summary.overallStatus)
        val anyRegression = analysis.testAnalyses.flatMap { it.regressions }.any { it.isRegression }
        assertTrue(anyRegression, "Expected at least one RegressionResult with isRegression=true")
    }

    @Test
    fun `RECORD mode does not persist when infrastructure errors occur`() = runBlocking {
        val suiteId = BaselineKeys.suiteId(configurationSource, goldenConfiguration.suiteMetadata)

        val faultyRegistry = DefaultProviderRegistry(listOf(FaultyGenerateProvider()))
        val evaluatorRegistry: EvaluatorRegistry = DefaultEvaluatorRegistry(
            listOf(ExactMatchEvaluator(), ContainsEvaluator())
        )
        val providerValidationService = DefaultProviderValidationService(faultyRegistry, JudgeEvaluationProperties())
        val runTestSuiteUseCase = RunTestSuiteUseCase(
            configurationRepository = configurationRepository,
            providerRegistry = faultyRegistry,
            evaluatorRegistry = evaluatorRegistry,
            reportGenerator = QAVerificationHtmlReportGenerator(),
            providerValidationService = providerValidationService
        )
        val runner = BaselineExperimentRunner(
            runTestSuiteUseCase = runTestSuiteUseCase,
            configurationRepository = configurationRepository,
            baselineRepository = baselineRepository,
            regressionDetectionService = RegressionDetectionService(),
            providerValidationService = providerValidationService
        )

        val recordConfig = RegressionConfiguration(
            rules = listOf(
                RegressionRule("correctness", 0.1, RegressionType.RELATIVE, RegressionSeverity.ERROR)
            ),
            failOnRegression = false,
            baselineMode = BaselinePersistenceMode.RECORD
        )

        val error = assertFailsWith<IllegalStateException> {
            runner.executeWithBaseline(
                configurationSource = configurationSource,
                executionConfig = ExecutionConfig(enableParallelExecution = false),
                regressionConfig = recordConfig
            )
        }
        assertTrue(
            error.message.orEmpty().contains("infrastructure", ignoreCase = true),
            "Message should mention infrastructure errors: ${error.message}"
        )
        assertTrue(
            baselineRepository.loadAll(suiteId).isEmpty(),
            "No baseline files should be written when RECORD is blocked"
        )
    }

    private class FaultyGenerateProvider : LlmProvider {
        override val providerId: String = "deterministic"

        override suspend fun generate(request: LlmRequest): LlmResponse {
            throw LlmProviderException("Simulated LLM failure for infrastructure error path")
        }

        override suspend fun isHealthy(): Boolean = true
    }
}
