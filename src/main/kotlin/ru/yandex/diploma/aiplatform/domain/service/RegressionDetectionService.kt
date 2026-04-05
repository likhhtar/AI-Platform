package ru.yandex.diploma.aiplatform.domain.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.yandex.diploma.aiplatform.domain.model.*

@Service
class RegressionDetectionService {

    private val logger = LoggerFactory.getLogger(RegressionDetectionService::class.java)

    suspend fun analyzePersistedBaselines(
        currentRun: TestRunRecord,
        baselineMode: BaselinePersistenceMode,
        storedByTestCaseId: Map<String, BaselineEntry>,
        regressionRules: List<RegressionRule>,
        suiteId: String
    ): SuiteRegressionAnalysis {
        val testAnalyses = currentRun.results.map { currentResult ->
            val testCaseId = TestCaseIdentifiers.stableTestCaseId(currentResult.testCase)
            val entry = storedByTestCaseId[testCaseId]
            val missing = baselineMode == BaselinePersistenceMode.ASSERT && entry == null
            if (missing) {
                logger.warn("Missing baseline for testCaseId=$testCaseId (suiteId=$suiteId)")
            }
            val regressions = when {
                baselineMode != BaselinePersistenceMode.ASSERT -> emptyList()
                entry == null -> emptyList()
                else -> detectMetricRegressionsFromEntry(currentResult, entry, regressionRules)
            }
            TestRegressionAnalysis(
                testCaseId = testCaseId,
                currentResult = currentResult,
                baselineResult = null,
                baselineEntry = entry,
                missingPersistedBaseline = missing,
                regressions = regressions
            )
        }

        val summary = summarizePersisted(testAnalyses, aggregateRegressions = emptyList())

        val baselineRef = when {
            storedByTestCaseId.isNotEmpty() -> "persisted:$suiteId"
            else -> null
        }

        return SuiteRegressionAnalysis(
            currentRunId = currentRun.runId,
            baselineRunId = baselineRef,
            testAnalyses = testAnalyses,
            aggregateRegressions = emptyList(),
            summary = summary
        )
    }

    private fun detectMetricRegressionsFromEntry(
        current: TestResult,
        baseline: BaselineEntry,
        regressionRules: List<RegressionRule>
    ): List<RegressionResult> {
        val regressions = mutableListOf<RegressionResult>()
        val correctnessRule = regressionRules.find { it.metricName == "correctness" }
        if (correctnessRule != null) {
            val baselineScore = baseline.metrics["correctness"]
            if (baselineScore != null) {
                regressions.add(
                    RegressionResult.calculate(
                        metricName = "correctness",
                        currentValue = current.evaluationResult.score,
                        baselineValue = baselineScore,
                        rule = correctnessRule
                    )
                )
            }
        }
        current.metrics.forEach { (metricName, currentMetric) ->
            if (metricName == "correctness") return@forEach
            val baselineScore = baseline.metrics[metricName] ?: return@forEach
            val rule = regressionRules.find { it.metricName == metricName } ?: return@forEach
            regressions.add(
                RegressionResult.calculate(
                    metricName = metricName,
                    currentValue = currentMetric.score,
                    baselineValue = baselineScore,
                    rule = rule
                )
            )
        }
        return regressions
    }

    private fun summarizePersisted(
        testAnalyses: List<TestRegressionAnalysis>,
        aggregateRegressions: List<RegressionResult>
    ): RegressionSummary {
        val regressionsOnly =
            (testAnalyses.flatMap { it.regressions } + aggregateRegressions).filter { it.isRegression }
        val regressionsByMetric = regressionsOnly.groupingBy { it.metricName }.eachCount()
        val regressionsBySeverity = regressionsOnly.groupingBy { it.severity }.eachCount()
        val missing = testAnalyses.count { it.missingPersistedBaseline }
        val total = testAnalyses.size

        val overallStatus = when {
            regressionsOnly.any { it.severity in listOf(RegressionSeverity.ERROR, RegressionSeverity.CRITICAL) } ->
                RegressionStatus.FAILURE
            regressionsOnly.any { it.severity == RegressionSeverity.WARNING } ->
                RegressionStatus.WARNING
            total > 0 && missing == total ->
                RegressionStatus.NO_BASELINE
            missing > 0 ->
                RegressionStatus.WARNING
            else ->
                RegressionStatus.PASS
        }

        return RegressionSummary(
            totalTests = testAnalyses.size,
            testsWithRegressions = testAnalyses.count { it.hasRegressions },
            regressionsByMetric = regressionsByMetric,
            regressionsBySeverity = regressionsBySeverity,
            overallStatus = overallStatus
        )
    }
}
