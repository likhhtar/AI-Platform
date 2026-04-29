package ru.yandex.aiplatform.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import ru.yandex.diploma.aiplatform.domain.model.*

class OptimizationStatusDerivationTest {

    private val prompt = Prompt("p", "n", "t")

    @Test
    fun suggest_always_suggested() {
        assertEquals(
            OptimizationStatus.SUGGESTED,
            deriveOptimizationStatus(
                mode = OptimizationMode.SUGGEST,
                optimizedPrompt = prompt,
                improvement = null,
                harnessEvaluationFailed = false,
            ),
        )
    }

    @Test
    fun apply_harness_failed() {
        assertEquals(
            OptimizationStatus.FAILED,
            deriveOptimizationStatus(
                mode = OptimizationMode.APPLY,
                optimizedPrompt = null,
                improvement = null,
                harnessEvaluationFailed = true,
            ),
        )
    }

    @Test
    fun apply_rolled_back() {
        val imp =
            OptimizationImprovement(
                scoreImprovement = -0.1,
                latencyChange = 0.0,
                passRateImprovement = 0.0,
                significantImprovement = false,
                rolledBack = true,
            )
        assertEquals(
            OptimizationStatus.ROLLED_BACK,
            deriveOptimizationStatus(
                mode = OptimizationMode.APPLY,
                optimizedPrompt = null,
                improvement = imp,
                harnessEvaluationFailed = false,
            ),
        )
    }

    @Test
    fun apply_accepted() {
        val imp =
            OptimizationImprovement(
                scoreImprovement = 0.05,
                latencyChange = 0.0,
                passRateImprovement = 0.0,
                significantImprovement = true,
                rolledBack = false,
            )
        assertEquals(
            OptimizationStatus.APPLIED,
            deriveOptimizationStatus(
                mode = OptimizationMode.APPLY,
                optimizedPrompt = prompt,
                improvement = imp,
                harnessEvaluationFailed = false,
            ),
        )
    }
}
