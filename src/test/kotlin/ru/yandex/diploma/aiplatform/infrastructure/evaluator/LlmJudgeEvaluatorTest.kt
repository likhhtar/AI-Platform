package ru.yandex.diploma.aiplatform.infrastructure.evaluator

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ru.yandex.diploma.aiplatform.config.JudgeEvaluationProperties
import ru.yandex.diploma.aiplatform.domain.evaluator.JudgeEvaluationFailedException
import ru.yandex.diploma.aiplatform.domain.model.JudgeFallbackPolicy
import ru.yandex.diploma.aiplatform.domain.provider.ProviderRegistry

class LlmJudgeEvaluatorTest {

    @Test
    fun `FAIL_FAST surfaces judge failures`() {
        val registry = mockk<ProviderRegistry>()
        every { registry.getProvider(any()) } throws RuntimeException("provider down")

        val evaluator = LlmJudgeEvaluator(
            registry,
            JudgeEvaluationProperties(fallbackPolicy = JudgeFallbackPolicy.FAIL_FAST)
        )

        val ex = assertThrows<JudgeEvaluationFailedException> {
            evaluator.evaluate("actual", "expected", emptyMap())
        }
        assertTrue(ex.message.orEmpty().contains("FAIL_FAST", ignoreCase = true))
    }

    @Test
    fun `HEURISTIC preserves substring fallback`() {
        val registry = mockk<ProviderRegistry>()
        every { registry.getProvider(any()) } throws RuntimeException("provider down")

        val evaluator = LlmJudgeEvaluator(
            registry,
            JudgeEvaluationProperties(fallbackPolicy = JudgeFallbackPolicy.HEURISTIC)
        )

        val result = evaluator.evaluate("contains expected text", "expected", emptyMap())
        assertTrue(result.passed)
        assertEquals(0.7, result.score, 0.0001)
    }
}
