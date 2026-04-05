package ru.yandex.aiplatform.domain.model

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.yandex.diploma.aiplatform.domain.model.MetricDirection
import ru.yandex.diploma.aiplatform.domain.model.RegressionResult
import ru.yandex.diploma.aiplatform.domain.model.RegressionRule
import ru.yandex.diploma.aiplatform.domain.model.RegressionSeverity
import ru.yandex.diploma.aiplatform.domain.model.RegressionType

class RegressionResultTest {

    @Test
    fun `HIGHER_IS_BETTER RELATIVE - improvement is not regression`() {
        val rule = RegressionRule(
            metricName = "correctness",
            threshold = 0.05,
            type = RegressionType.RELATIVE,
            direction = MetricDirection.HIGHER_IS_BETTER,
            severity = RegressionSeverity.ERROR
        )
        val r = RegressionResult.calculate("correctness", currentValue = 0.95, baselineValue = 0.80, rule = rule)
        assertFalse(r.isRegression)
    }

    @Test
    fun `HIGHER_IS_BETTER RELATIVE - degradation beyond threshold is regression`() {
        val rule = RegressionRule(
            metricName = "correctness",
            threshold = 0.05,
            type = RegressionType.RELATIVE,
            direction = MetricDirection.HIGHER_IS_BETTER,
            severity = RegressionSeverity.ERROR
        )
        val r = RegressionResult.calculate("correctness", currentValue = 0.70, baselineValue = 0.90, rule = rule)
        assertTrue(r.isRegression)
    }

    @Test
    fun `LOWER_IS_BETTER RELATIVE - increase in raw latency is regression`() {
        val rule = RegressionRule(
            metricName = "average_latency",
            threshold = 0.5,
            type = RegressionType.RELATIVE,
            direction = MetricDirection.LOWER_IS_BETTER,
            severity = RegressionSeverity.WARNING
        )
        val r = RegressionResult.calculate("average_latency", currentValue = 300.0, baselineValue = 100.0, rule = rule)
        assertTrue(r.isRegression)
    }

    @Test
    fun `LOWER_IS_BETTER RELATIVE - decrease in latency is not regression`() {
        val rule = RegressionRule(
            metricName = "average_latency",
            threshold = 0.5,
            type = RegressionType.RELATIVE,
            direction = MetricDirection.LOWER_IS_BETTER,
            severity = RegressionSeverity.WARNING
        )
        val r = RegressionResult.calculate("average_latency", currentValue = 80.0, baselineValue = 100.0, rule = rule)
        assertFalse(r.isRegression)
    }

    @Test
    fun `HIGHER_IS_BETTER ABSOLUTE - success rate drop`() {
        val rule = RegressionRule(
            metricName = "success_rate",
            threshold = 0.05,
            type = RegressionType.ABSOLUTE,
            direction = MetricDirection.HIGHER_IS_BETTER,
            severity = RegressionSeverity.ERROR
        )
        assertTrue(
            RegressionResult.calculate("success_rate", 0.90, 0.98, rule).isRegression
        )
        assertFalse(
            RegressionResult.calculate("success_rate", 0.97, 0.98, rule).isRegression
        )
    }

    @Test
    fun `PERCENTAGE type respects direction`() {
        val ruleH = RegressionRule(
            metricName = "correctness",
            threshold = 10.0,
            type = RegressionType.PERCENTAGE,
            direction = MetricDirection.HIGHER_IS_BETTER,
            severity = RegressionSeverity.ERROR
        )
        // 0.8 -> 0.95: +18.75% relative improvement, percentageDelta positive -> not regression
        assertFalse(
            RegressionResult.calculate("correctness", 0.95, 0.80, ruleH).isRegression
        )
    }
}
