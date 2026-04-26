package ru.yandex.aiplatform

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import ru.yandex.diploma.aiplatform.AiPlatformApplication
import ru.yandex.diploma.aiplatform.application.usecase.BaselineExperimentRunner
import ru.yandex.diploma.aiplatform.domain.model.BaselinePersistenceMode
import ru.yandex.diploma.aiplatform.domain.model.RegressionConfiguration
import ru.yandex.diploma.aiplatform.domain.model.RegressionRule
import ru.yandex.diploma.aiplatform.domain.model.RegressionSeverity
import ru.yandex.diploma.aiplatform.domain.model.RegressionStatus
import ru.yandex.diploma.aiplatform.domain.model.RegressionType
import ru.yandex.diploma.aiplatform.infrastructure.repository.FileBaselineRepository
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(classes = [AiPlatformApplication::class])
@ActiveProfiles("test")
class BaselineSystemIntegrationTest {

    @Autowired
    private lateinit var baselineExperimentRunner: BaselineExperimentRunner

    @Autowired
    private lateinit var fileBaselineRepository: FileBaselineRepository

    private val integrationSuiteId = "baseline_integration_test"
    private val noBaselineSuiteId = "no_baseline_test"

    @BeforeEach
    fun cleanBaselines() = runBlocking {
        fileBaselineRepository.deleteSuite(integrationSuiteId)
        fileBaselineRepository.deleteSuite(noBaselineSuiteId)
    }

    @Test
    fun `test complete baseline and regression detection flow`() = runBlocking {
        val testConfig = """
            suite:
              name: "Baseline Integration Test"
              version: "1.0"
              description: "Test baseline system integration"
            
            prompts:
              - id: "test_prompt"
                template: "Say hello to {{name}}"
                
            agents:
              - name: "test_agent"
                systemPrompt: "You are a helpful assistant."
                model: "deterministic-model-v1"
                provider: "deterministic"
                
            test_cases:
              - prompt_id: "test_prompt"
                agent: "test_agent"
                variables:
                  name: "World"
                expected: "Hello! I'm a deterministic AI assistant."
                evaluator: "exact"
        """.trimIndent()

        val rules = listOf(
            RegressionRule(
                metricName = "correctness",
                threshold = 0.1,
                severity = RegressionSeverity.WARNING
            ),
            RegressionRule(
                metricName = "latency",
                threshold = 0.2,
                severity = RegressionSeverity.ERROR
            )
        )

        val recordConfig = RegressionConfiguration(
            rules = rules,
            failOnRegression = false,
            baselineMode = BaselinePersistenceMode.RECORD
        )

        val firstResult = baselineExperimentRunner.executeWithBaseline(
            configurationSource = testConfig,
            regressionConfig = recordConfig
        )

        assertNotNull(firstResult.runId)
        assertTrue(firstResult.testRun.results.isNotEmpty())
        assertEquals(RegressionStatus.PASS, firstResult.regressionAnalysis.summary.overallStatus)
        assertTrue(fileBaselineRepository.loadAll(integrationSuiteId).isNotEmpty())

        val assertConfig = RegressionConfiguration(
            rules = rules,
            failOnRegression = false,
            baselineMode = BaselinePersistenceMode.ASSERT
        )

        val secondResult = baselineExperimentRunner.executeWithBaseline(
            configurationSource = testConfig,
            regressionConfig = assertConfig
        )

        assertNotNull(secondResult.regressionAnalysis.baselineRunId)
        assertTrue(secondResult.regressionAnalysis.baselineRunId!!.startsWith("persisted:"))
        assertTrue(
            secondResult.regressionAnalysis.summary.overallStatus in
                listOf(RegressionStatus.PASS, RegressionStatus.WARNING)
        )

        val testResult = secondResult.testRun.results.first()
        assertTrue(testResult.metrics.containsKey("latency"))
        assertTrue(testResult.metrics.containsKey("token_usage"))
    }

    @Test
    fun `ASSERT detects regression when persisted correctness is better than current run`() = runBlocking {
        val testConfig = """
            suite:
              name: "Baseline Integration Test"
              version: "1.0"
              description: "Test baseline system integration"
            
            prompts:
              - id: "test_prompt"
                template: "Say hello to {{name}}"
                
            agents:
              - name: "test_agent"
                systemPrompt: "You are a helpful assistant."
                model: "deterministic-model-v1"
                provider: "deterministic"
                
            test_cases:
              - prompt_id: "test_prompt"
                agent: "test_agent"
                variables:
                  name: "World"
                expected: "__NOT_PRESENT_IN_DETERMINISTIC_OUTPUT__"
                evaluator: "contains"
                metadata:
                  caseSensitive: false
        """.trimIndent()

        val rules = listOf(
            RegressionRule(
                metricName = "correctness",
                threshold = 0.05,
                type = RegressionType.RELATIVE,
                severity = RegressionSeverity.ERROR
            )
        )

        baselineExperimentRunner.executeWithBaseline(
            configurationSource = testConfig,
            regressionConfig = RegressionConfiguration(
                rules = rules,
                failOnRegression = false,
                baselineMode = BaselinePersistenceMode.RECORD
            )
        )

        val stored = fileBaselineRepository.loadAll(integrationSuiteId)
        assertTrue(stored.isNotEmpty(), "RECORD should persist baselines before ASSERT regression scenario")
        stored.forEach { (testCaseId, entry) ->
            fileBaselineRepository.saveBaseline(
                integrationSuiteId,
                testCaseId,
                entry.copy(metrics = entry.metrics + ("correctness" to 1.0))
            )
        }

        val assertResult = baselineExperimentRunner.executeWithBaseline(
            configurationSource = testConfig,
            regressionConfig = RegressionConfiguration(
                rules = rules,
                failOnRegression = false,
                baselineMode = BaselinePersistenceMode.ASSERT
            )
        )

        assertEquals(RegressionStatus.FAILURE, assertResult.regressionAnalysis.summary.overallStatus)
        assertTrue(assertResult.regressionAnalysis.summary.testsWithRegressions > 0)
        assertTrue(
            assertResult.regressionAnalysis.testAnalyses.flatMap { it.regressions }
                .any { it.isRegression && it.metricName == "correctness" },
            "Inflated baseline correctness must surface as a correctness regression on the next run"
        )
    }

    @Test
    fun `test baseline system with no persisted baselines`() = runBlocking {
        val testConfig = """
            suite:
              name: "No Baseline Test"
              version: "1.0"
            
            prompts:
              - id: "simple_prompt"
                template: "Hello"
                
            agents:
              - name: "simple_agent"
                systemPrompt: "You are helpful."
                model: "deterministic-model-v1"
                provider: "deterministic"
                
            test_cases:
              - prompt_id: "simple_prompt"
                agents: ["simple_agent"]
                variables: {}
                expected: "Hello! I'm a deterministic AI assistant."
                evaluator: "exact"
        """.trimIndent()

        val result = baselineExperimentRunner.executeWithBaseline(
            configurationSource = testConfig,
            regressionConfig = RegressionConfiguration(
                rules = emptyList(),
                failOnRegression = false,
                baselineMode = BaselinePersistenceMode.ASSERT
            )
        )

        assertEquals(RegressionStatus.NO_BASELINE, result.regressionAnalysis.summary.overallStatus)
        assertEquals(null, result.regressionAnalysis.baselineRunId)
    }
}
