package ru.yandex.aiplatform.application.usecase

import kotlin.test.Test
import kotlin.test.assertEquals
import ru.yandex.diploma.aiplatform.application.usecase.ExperimentMetrics
import ru.yandex.diploma.aiplatform.application.usecase.ExperimentResult
import ru.yandex.diploma.aiplatform.application.usecase.ExperimentRun
import ru.yandex.diploma.aiplatform.application.usecase.TestSuiteResult
import ru.yandex.diploma.aiplatform.application.usecase.safePassRate
import ru.yandex.diploma.aiplatform.application.usecase.safeTestPassRate
import ru.yandex.diploma.aiplatform.domain.model.AgentConfig
import ru.yandex.diploma.aiplatform.domain.model.EvaluationResult
import ru.yandex.diploma.aiplatform.domain.model.TestCase
import ru.yandex.diploma.aiplatform.domain.model.TestResult
import ru.yandex.diploma.aiplatform.domain.model.TestSuiteMetrics

class ExperimentResultMetricsTest {

    @Test
    fun `safeTestPassRate uses per test evaluation passed`() {
        val suite =
            TestSuiteResult(
                total = 4,
                passed = 2,
                failed = 2,
                results =
                    listOf(
                        tr("t1", passed = true),
                        tr("t2", passed = true),
                        tr("t3", passed = false),
                        tr("t4", passed = false),
                    ),
                executionTimeMs = 1L,
                metrics = TestSuiteMetrics(averageLatency = 1.0, totalTokens = 0, averageScore = 0.5),
            )
        val run =
            ExperimentRun(
                agentName = "a",
                model = "m",
                temperature = 0.0,
                result = suite,
                success = true,
                error = null,
            )
        val er =
            ExperimentResult(
                agentConfig = AgentConfig.create("a", "s"),
                runs = listOf(run),
                metrics =
                    ExperimentMetrics(
                        totalRuns = 1,
                        successfulRuns = 1,
                        failedRuns = 0,
                        averageLatency = 1.0,
                        averageScore = 0.5,
                    ),
                executionTimeMs = 1L,
                timestamp = "t",
            )
        assertEquals(0.5, er.safeTestPassRate(), 1e-9)
        assertEquals(1.0, er.safePassRate(), 1e-9)
    }

    @Test
    fun `safeTestPassRate aggregates across multiple successful runs`() {
        val suite1 =
            TestSuiteResult(
                total = 2,
                passed = 1,
                failed = 1,
                results = listOf(tr("t1", true), tr("t2", false)),
                executionTimeMs = 1L,
                metrics = TestSuiteMetrics(1.0, 0, 0.5),
            )
        val suite2 =
            TestSuiteResult(
                total = 2,
                passed = 2,
                failed = 0,
                results = listOf(tr("t3", true), tr("t4", true)),
                executionTimeMs = 1L,
                metrics = TestSuiteMetrics(1.0, 0, 1.0),
            )
        val er =
            ExperimentResult(
                agentConfig = AgentConfig.create("a", "s"),
                runs =
                    listOf(
                        ExperimentRun("a", "m", 0.0, suite1, true, null),
                        ExperimentRun("a", "m", 0.0, suite2, true, null),
                    ),
                metrics = ExperimentMetrics(2, 2, 0, 1.0, 0.75),
                executionTimeMs = 1L,
                timestamp = "t",
            )
        assertEquals(3.0 / 4.0, er.safeTestPassRate(), 1e-9)
    }

    @Test
    fun `safeTestPassRate empty results is zero`() {
        val suite =
            TestSuiteResult(
                total = 0,
                passed = 0,
                failed = 0,
                results = emptyList(),
                executionTimeMs = 0L,
                metrics = TestSuiteMetrics(averageLatency = 0.0, totalTokens = 0, averageScore = 0.0),
            )
        val er =
            ExperimentResult(
                agentConfig = AgentConfig.create("a", "s"),
                runs =
                    listOf(
                        ExperimentRun("a", "m", 0.0, suite, true, null),
                    ),
                metrics =
                    ExperimentMetrics(1, 1, 0, 0.0, 0.0),
                executionTimeMs = 0L,
                timestamp = "t",
            )
        assertEquals(0.0, er.safeTestPassRate(), 0.0)
    }

    private fun tr(id: String, passed: Boolean): TestResult =
        TestResult(
            testCase =
                TestCase(
                    promptId = id,
                    agentName = "ag",
                    variables = emptyMap(),
                    expected = "e",
                    evaluatorType = "exact",
                ),
            success = passed,
            evaluationResult =
                EvaluationResult(
                    passed = passed,
                    score = if (passed) 1.0 else 0.0,
                    explanation = "",
                ),
            llmResponse = null,
            executionTimeMs = 1L,
            error = null,
        )
}
