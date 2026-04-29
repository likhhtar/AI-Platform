package ru.yandex.aiplatform

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.yandex.diploma.aiplatform.domain.model.*
import ru.yandex.diploma.aiplatform.domain.service.MultiMetricEvaluationService
import ru.yandex.diploma.aiplatform.domain.service.RegressionDetectionService
import ru.yandex.diploma.aiplatform.infrastructure.evaluator.DefaultMetricRegistry
import ru.yandex.diploma.aiplatform.domain.repository.BaselineLoadResult
import ru.yandex.diploma.aiplatform.infrastructure.repository.FileBaselineRepository
import java.nio.file.Files
import java.time.Instant

class BaselineRegressionSystemTest {

    private val regressionDetectionService = RegressionDetectionService()
    private val metricRegistry = DefaultMetricRegistry()
    private val multiMetricEvaluationService = MultiMetricEvaluationService(metricRegistry)

    @Test
    fun `file baseline repository persists and loads entries`() = runBlocking {
        val dir = Files.createTempDirectory("baseline-regression")
        val repo = FileBaselineRepository.forDirectory(dir)
        val suiteId = "unit_suite"
        val tcId = "p1:agent:{}"
        val entry = BaselineEntry(
            response = "hello",
            metrics = mapOf("correctness" to 0.95, "latency" to 0.8),
            createdAt = Instant.parse("2026-01-01T00:00:00Z")
        )
        repo.saveBaseline(suiteId, tcId, entry)
        assertEquals(entry.response, repo.getBaseline(suiteId, tcId)?.response)
        val afterSave = repo.loadAll(suiteId)
        assertTrue(afterSave is BaselineLoadResult.Loaded)
        assertEquals(1, (afterSave as BaselineLoadResult.Loaded).data.size)
        repo.deleteSuite(suiteId)
        val afterDelete = repo.loadAll(suiteId)
        assertTrue(
            afterDelete is BaselineLoadResult.Missing ||
                (afterDelete is BaselineLoadResult.Loaded && afterDelete.data.isEmpty()),
        )
    }

    @Test
    fun `persisted baseline regression detects score drop`() = runBlocking {
        val baselineRun = createTestRunRecord("baseline", 0.9, 800L, 400)
        val currentRun = createTestRunRecord("current", 0.7, 1200L, 600)
        val stored = baselineRun.results.associate { r ->
            TestCaseIdentifiers.stableTestCaseId(r.testCase) to r.toBaselineEntryForTest()
        }
        val rules = listOf(
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
            )
        )
        val analysis = regressionDetectionService.analyzePersistedBaselines(
            currentRun = currentRun,
            baselineMode = BaselinePersistenceMode.ASSERT,
            storedByTestCaseId = stored,
            regressionRules = rules,
            suiteId = "test-suite"
        )
        assertEquals("current", analysis.currentRunId)
        assertNotNull(analysis.baselineRunId)
        assertTrue(analysis.summary.testsWithRegressions > 0)
        assertEquals(RegressionStatus.FAILURE, analysis.summary.overallStatus)
        val correctnessHits = analysis.testAnalyses.flatMap { it.regressions }
            .filter { it.metricName == "correctness" && it.isRegression }
        assertTrue(
            correctnessHits.isNotEmpty(),
            "Expected correctness RegressionResult with isRegression=true when score drops vs persisted baseline"
        )
    }

    @Test
    fun `metric evaluation service still evaluates definitions`() = runBlocking {
        val testCase = TestCaseWithMetrics(
            promptId = "test-prompt",
            agentName = "test-agent",
            variables = mapOf("input" to "test"),
            expected = "expected output",
            metrics = listOf(
                MetricDefinition(
                    name = "correctness",
                    type = MetricType.CORRECTNESS,
                    evaluatorType = "correctness",
                    threshold = 0.5
                ),
                MetricDefinition(
                    name = "latency",
                    type = MetricType.LATENCY,
                    evaluatorType = "latency",
                    threshold = 0.7
                )
            )
        )
        val metrics = multiMetricEvaluationService.evaluateAllMetrics(
            output = "expected output",
            expected = "expected output",
            testCase = testCase,
            llmResponse = LlmResponse(
                content = "expected output",
                tokensUsed = 100,
                model = "test-model"
            ),
            executionTimeMs = 500L
        )
        assertTrue(metrics.containsKey("correctness"))
        assertTrue(metrics.containsKey("latency"))
        assertEquals(1.0, metrics["correctness"]!!.score)
    }

    private fun createTestRunRecord(
        runId: String,
        successRate: Double,
        avgLatency: Long,
        avgTokens: Int
    ): TestRunRecord {
        val testCase1 = TestCase(
            promptId = "prompt-1",
            agentName = "agent-1",
            variables = mapOf("input" to "test1"),
            expected = "output1",
            evaluatorType = "exact"
        )
        val testCase2 = TestCase(
            promptId = "prompt-2",
            agentName = "agent-1",
            variables = mapOf("input" to "test2"),
            expected = "output2",
            evaluatorType = "exact"
        )
        val result1 = TestResult(
            testCase = testCase1,
            success = successRate > 0.5,
            evaluationResult = EvaluationResult(successRate > 0.5, successRate, "Test evaluation"),
            metrics = mapOf(
                "correctness" to MetricResult("correctness", successRate, "Test result"),
                "latency" to MetricResult("latency", 0.8, "Latency result"),
                "token_usage" to MetricResult("token_usage", 0.7, "Token usage result")
            ),
            llmResponse = LlmResponse("output1", avgTokens, "test-model"),
            executionTimeMs = avgLatency,
            error = null
        )
        val result2 = TestResult(
            testCase = testCase2,
            success = successRate > 0.7,
            evaluationResult = EvaluationResult(successRate > 0.7, successRate * 0.9, "Test evaluation 2"),
            metrics = mapOf(
                "correctness" to MetricResult("correctness", successRate * 0.9, "Test result 2"),
                "latency" to MetricResult("latency", 0.9, "Latency result 2"),
                "token_usage" to MetricResult("token_usage", 0.8, "Token usage result 2")
            ),
            llmResponse = LlmResponse("output2", avgTokens + 50, "test-model"),
            executionTimeMs = avgLatency + 100,
            error = null
        )
        val suiteMetrics = TestSuiteMetrics(
            averageLatency = avgLatency.toDouble(),
            totalTokens = avgTokens * 2,
            averageScore = successRate
        )
        return TestRunRecord(
            runId = runId,
            suiteMetadata = TestSuiteMetadata("Test Suite", "1.0", "Test suite"),
            promptVersion = "1.0",
            configurationHash = "test-hash",
            results = listOf(result1, result2),
            metrics = suiteMetrics,
            executionTimeMs = avgLatency * 2,
            timestamp = Instant.now()
        )
    }

    private fun TestResult.toBaselineEntryForTest(): BaselineEntry {
        val m = LinkedHashMap<String, Double>()
        m["correctness"] = evaluationResult.score
        metrics.forEach { (k, v) -> m[k] = v.score }
        return BaselineEntry(
            response = llmResponse?.content ?: "",
            metrics = m,
            createdAt = Instant.now()
        )
    }
}
