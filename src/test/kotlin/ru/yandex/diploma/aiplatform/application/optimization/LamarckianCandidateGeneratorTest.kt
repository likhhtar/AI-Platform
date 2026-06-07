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

class LamarckianCandidateGeneratorTest {

    private val llmProvider =
        mockk<LlmProvider> {
            every { providerId } returns "test-provider"
        }
    private val generator = LamarckianCandidateGenerator(listOf(llmProvider))

    @Test
    fun `returns null when judgeExplanations is empty`() = runTest {
        assertNull(generator.generate(judgeExplanations = emptyList()))
    }

    @Test
    fun `returns null when all scores are below default threshold`() = runTest {
        assertNull(
            generator.generate(
                judgeExplanations =
                    listOf(
                        JudgeExplanation("ex1", 0.79),
                        JudgeExplanation("ex2", 0.0),
                    ),
            ),
        )
    }

    @Test
    fun `calls LlmProvider and returns trimmed text when high-score explanations exist`() = runTest {
        coEvery { llmProvider.generate(any()) } returns LlmResponse(content = "  fixed prompt  ")

        val out =
            generator.generate(
                judgeExplanations =
                    listOf(
                        JudgeExplanation("good trace", 0.85),
                    ),
            )

        assertEquals("fixed prompt", out)
        coVerify(exactly = 1) { llmProvider.generate(any()) }
    }

    @Test
    fun `returns null when LlmProvider throws`() = runTest {
        coEvery { llmProvider.generate(any()) } throws RuntimeException("LLM down")

        assertNull(
            generator.generate(
                judgeExplanations = listOf(JudgeExplanation("x", 1.0)),
            ),
        )
    }

    @Test
    fun `explanations with score below threshold are not included in prompt to LLM`() = runTest {
        val requestSlot = slot<LlmRequest>()
        coEvery { llmProvider.generate(capture(requestSlot)) } returns LlmResponse(content = "ok")

        generator.generate(
            judgeExplanations =
                listOf(
                    JudgeExplanation("DROP_LOW_SCORE", 0.5),
                    JudgeExplanation("KEEP_THIS_HIGH", 0.85),
                ),
        )

        val sent = requestSlot.captured.prompt
        assertTrue(sent.contains("KEEP_THIS_HIGH"))
        assertFalse(sent.contains("DROP_LOW_SCORE"))
    }

    @Test
    fun `uses PromptBreeder Lamarckian template prefix and suffix`() = runTest {
        val requestSlot = slot<LlmRequest>()
        coEvery { llmProvider.generate(capture(requestSlot)) } returns LlmResponse(content = "instr")

        generator.generate(
            judgeExplanations = listOf(JudgeExplanation("working A", 0.9)),
        )

        val sent = requestSlot.captured.prompt
        assertTrue(
            sent.startsWith(
                "I gave a friend an instruction and some advice. " +
                    "Here are the correct examples of his workings out: ",
            ),
        )
        assertTrue(sent.contains(" The instruction was:"))
        assertTrue(sent.contains("IMPORTANT: Write the instruction in Russian."))
    }
}
