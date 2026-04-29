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
import ru.yandex.diploma.aiplatform.domain.model.EnhancedTestSuiteResult
import ru.yandex.diploma.aiplatform.domain.model.RegressionConfiguration
import ru.yandex.diploma.aiplatform.domain.model.RegressionRule
import ru.yandex.diploma.aiplatform.domain.model.RegressionSeverity
import ru.yandex.diploma.aiplatform.domain.model.RegressionStatus
import ru.yandex.diploma.aiplatform.domain.model.RegressionType
import ru.yandex.diploma.aiplatform.domain.repository.BaselineLoadResult
import ru.yandex.diploma.aiplatform.infrastructure.repository.FileBaselineRepository
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(classes = [AiPlatformApplication::class])
@ActiveProfiles("test")
class StrictQAEndToEndTest {

    @Autowired
    private lateinit var baselineExperimentRunner: BaselineExperimentRunner

    @Autowired
    private lateinit var fileBaselineRepository: FileBaselineRepository

    @BeforeEach
    fun setup() = runBlocking {
        fileBaselineRepository.deleteSuite("qa_comprehensive_test_suite")
    }

    @Test
    fun `baseline record then assert detects regressions when expectations worsen`() = runBlocking {
        val baselineConfig = comprehensiveYaml()
        val baselineResult = executeBaselineRun(baselineConfig)

        assertEquals(RegressionStatus.PASS, baselineResult.regressionAnalysis.summary.overallStatus)
        assertTrue(baselineResult.runId.isNotEmpty())
        val baselines = fileBaselineRepository.loadAll("qa_comprehensive_test_suite")
        assertTrue(baselines is BaselineLoadResult.Loaded && baselines.data.isNotEmpty())

        val regressionResult = executeRegressionRun(createRegressionScenario(baselineConfig))

        assertNotNull(regressionResult.regressionAnalysis.baselineRunId)
        assertTrue(regressionResult.regressionAnalysis.baselineRunId!!.startsWith("persisted:"))
        assertTrue(regressionResult.regressionAnalysis.summary.testsWithRegressions > 0)
        assertTrue(
            regressionResult.regressionAnalysis.testAnalyses.flatMap { it.regressions }
                .any { it.isRegression },
            "At least one RegressionResult must have isRegression=true"
        )
        assertTrue(
            regressionResult.regressionAnalysis.summary.overallStatus in
                listOf(RegressionStatus.FAILURE, RegressionStatus.WARNING),
            "Worsened expectations vs baseline should surface as FAILURE or WARNING, was ${regressionResult.regressionAnalysis.summary.overallStatus}"
        )

        assertMultiMetricShape(regressionResult)
        assertFileBaselineRoundTrip()
    }

    private fun comprehensiveYaml(): String = """
            suite:
              name: "QA Comprehensive Test Suite"
              version: "1.0"
              description: "Comprehensive test for baseline and regression detection"
            
            prompts:
              - id: "greeting_prompt"
                template: "Say hello to {{name}}"
                
            agents:
              - name: "test_agent_1"
                systemPrompt: "You are a precise assistant."
                model: "deterministic-model-v1"
                provider: "deterministic"
              - name: "test_agent_2"
                systemPrompt: "You are a friendly assistant."
                model: "deterministic-model-v1"
                provider: "deterministic"
                
            test_cases:
              - prompt_id: "greeting_prompt"
                agents: ["test_agent_1"]
                variables:
                  name: "Alice"
                expected: "Hello"
                evaluator: "contains"
              - prompt_id: "greeting_prompt"
                agents: ["test_agent_2"]
                variables:
                  name: "Bob"
                expected: "Hello"
                evaluator: "contains"
              - prompt_id: "greeting_prompt"
                agents: ["test_agent_1"]
                variables:
                  name: "Carol"
                expected: "Hello"
                evaluator: "contains"
    """.trimIndent()

    private suspend fun executeBaselineRun(config: String): EnhancedTestSuiteResult {
        val regressionConfig = RegressionConfiguration(
            rules = listOf(
                RegressionRule("correctness", 0.1, RegressionType.RELATIVE, RegressionSeverity.WARNING),
                RegressionRule("latency", 0.2, RegressionType.RELATIVE, RegressionSeverity.ERROR),
                RegressionRule("token_usage", 0.15, RegressionType.RELATIVE, RegressionSeverity.WARNING)
            ),
            failOnRegression = false,
            baselineMode = BaselinePersistenceMode.RECORD
        )
        return baselineExperimentRunner.executeWithBaseline(config, regressionConfig = regressionConfig)
    }

    private fun createRegressionScenario(originalConfig: String): String =
        originalConfig.replace("expected: \"Hello\"", "expected: \"ZZZ_IMPOSSIBLE_SUBSTRING\"")

    private suspend fun executeRegressionRun(config: String): EnhancedTestSuiteResult {
        val regressionConfig = RegressionConfiguration(
            rules = listOf(
                RegressionRule("correctness", 0.1, RegressionType.RELATIVE, RegressionSeverity.WARNING),
                RegressionRule("latency", 0.2, RegressionType.RELATIVE, RegressionSeverity.ERROR),
                RegressionRule("token_usage", 0.15, RegressionType.RELATIVE, RegressionSeverity.WARNING)
            ),
            failOnRegression = false,
            baselineMode = BaselinePersistenceMode.ASSERT
        )
        return baselineExperimentRunner.executeWithBaseline(config, regressionConfig = regressionConfig)
    }

    private fun assertMultiMetricShape(result: EnhancedTestSuiteResult) {
        assertTrue(result.testRun.results.isNotEmpty())
        result.testRun.results.forEach { testResult ->
            listOf("latency", "token_usage").forEach { name ->
                assertTrue(testResult.metrics.containsKey(name), "Missing metric $name")
                val score = testResult.metrics[name]!!.score
                assertTrue(score in 0.0..1.0, "Metric $name score must be normalized: $score")
            }
        }
    }

    private suspend fun assertFileBaselineRoundTrip() {
        val dir = Files.createTempDirectory("qa-strict-baseline")
        val repo = FileBaselineRepository.forDirectory(dir)
        val entry = ru.yandex.diploma.aiplatform.domain.model.BaselineEntry(
            response = "x",
            metrics = mapOf("correctness" to 1.0),
            createdAt = java.time.Instant.now()
        )
        repo.saveBaseline("edge_suite", "tc1", entry)
        assertEquals("x", repo.getBaseline("edge_suite", "tc1")?.response)
        repo.deleteSuite("edge_suite")
    }
}
