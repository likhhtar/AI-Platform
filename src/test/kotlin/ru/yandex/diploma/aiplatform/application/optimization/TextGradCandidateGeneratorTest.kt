package ru.yandex.diploma.aiplatform.application.optimization

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import ru.yandex.diploma.aiplatform.domain.model.JudgeExplanation
import ru.yandex.diploma.aiplatform.domain.model.LlmRequest
import ru.yandex.diploma.aiplatform.domain.model.LlmResponse
import ru.yandex.diploma.aiplatform.domain.provider.LlmProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextGradCandidateGeneratorTest {

    private val llmProvider =
        mockk<LlmProvider> {
            every { providerId } returns "test-provider"
        }
    private val generator = TextGradCandidateGenerator(listOf(llmProvider))

    @Test
    fun `returns null when judgeExplanations is empty`() = runTest {
        assertNull(
            generator.generate(
                originalPrompt = "do the thing",
                judgeExplanations = emptyList(),
            ),
        )
    }

    @Test
    fun `returns null when all scores are at or above default threshold`() = runTest {
        assertNull(
            generator.generate(
                originalPrompt = "x",
                judgeExplanations =
                    listOf(
                        JudgeExplanation("ex1", 0.8),
                        JudgeExplanation("ex2", 1.0),
                    ),
            ),
        )
    }

    @Test
    fun `calls LlmProvider and returns trimmed text when low-score explanations exist`() = runTest {
        coEvery { llmProvider.generate(any()) } returns LlmResponse(content = "  improved  ")

        val out =
            generator.generate(
                originalPrompt = "orig",
                judgeExplanations = listOf(JudgeExplanation("bad", 0.5)),
            )

        assertEquals("improved", out)
        coVerify(exactly = 1) { llmProvider.generate(any()) }
    }

    @Test
    fun `returns null when LlmProvider throws`() = runTest {
        coEvery { llmProvider.generate(any()) } throws RuntimeException("LLM down")

        assertNull(
            generator.generate(
                originalPrompt = "p",
                judgeExplanations = listOf(JudgeExplanation("x", 0.1)),
            ),
        )
    }

    @Test
    fun `explanations at or above threshold are not included as textual gradients`() = runTest {
        val requestSlot = slot<LlmRequest>()
        coEvery { llmProvider.generate(capture(requestSlot)) } returns LlmResponse(content = "ok")

        generator.generate(
            originalPrompt = "ORIGINAL_BODY",
            judgeExplanations =
                listOf(
                    JudgeExplanation("DROP_HIGH_SCORE", 0.85),
                    JudgeExplanation("KEEP_LOW_SCORE", 0.5),
                ),
        )

        val sent = requestSlot.captured.prompt
        assertTrue(sent.contains("KEEP_LOW_SCORE"))
        assertFalse(sent.contains("DROP_HIGH_SCORE"))
    }

    @Test
    fun `prompt lists textual gradients then original instruction then improvement request`() =
        runTest {
            val requestSlot = slot<LlmRequest>()
            coEvery { llmProvider.generate(capture(requestSlot)) } returns LlmResponse(content = "out")

            generator.generate(
                originalPrompt = "Step one. Step two.",
                judgeExplanations =
                    listOf(
                        JudgeExplanation("first failure", 0.2),
                        JudgeExplanation("second failure", 0.3),
                    ),
            )

            val sent = requestSlot.captured.prompt
            assertTrue(sent.contains("Textual gradient 1: first failure"))
            assertTrue(sent.contains("Textual gradient 2: second failure"))
            assertTrue(sent.contains("Original instruction:"))
            assertTrue(sent.contains("Step one. Step two."))
            assertTrue(
                sent.contains(
                    "Based on the above feedback, write an improved version of the instruction " +
                        "that addresses the described failures. Return only the instruction text, nothing else.",
                ),
            )
        }

    @Test
    fun `uses custom failureScoreThreshold`() = runTest {
        val requestSlot = slot<LlmRequest>()
        coEvery { llmProvider.generate(capture(requestSlot)) } returns LlmResponse(content = "ok")

        generator.generate(
            originalPrompt = "p",
            judgeExplanations =
                listOf(
                    JudgeExplanation("above_custom_threshold", 0.55),
                    JudgeExplanation("below_custom_threshold", 0.45),
                ),
            failureScoreThreshold = 0.5,
        )

        val sent = requestSlot.captured.prompt
        assertTrue(sent.contains("below_custom_threshold"))
        assertFalse(sent.contains("above_custom_threshold"))
    }
}
