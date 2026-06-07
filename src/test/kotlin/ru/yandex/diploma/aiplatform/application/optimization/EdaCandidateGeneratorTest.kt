package ru.yandex.diploma.aiplatform.application.optimization

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import ru.yandex.diploma.aiplatform.domain.model.LlmRequest
import ru.yandex.diploma.aiplatform.domain.model.LlmResponse
import ru.yandex.diploma.aiplatform.domain.model.PromptVersion
import ru.yandex.diploma.aiplatform.domain.provider.LlmProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EdaCandidateGeneratorTest {

    private val llmProvider =
        mockk<LlmProvider> {
            every { providerId } returns "test-provider"
        }
    private val generator = EdaCandidateGenerator(listOf(llmProvider))

    @Test
    fun `returns null when promptHistory is empty`() = runTest {
        assertNull(generator.generate(promptHistory = emptyList()))
    }

    @Test
    fun `returns null when topN is zero`() = runTest {
        assertNull(
            generator.generate(
                promptHistory = listOf(PromptVersion(prompt = "x", score = 1.0)),
                topN = 0,
            ),
        )
    }

    @Test
    fun `returns null when all prompts are blank`() = runTest {
        assertNull(
            generator.generate(
                promptHistory =
                    listOf(
                        PromptVersion(prompt = null, score = 1.0),
                        PromptVersion(prompt = "   ", score = 0.9),
                    ),
            ),
        )
    }

    @Test
    fun `returns null when LlmProvider throws`() = runTest {
        coEvery { llmProvider.generate(any()) } throws RuntimeException("LLM down")

        assertNull(
            generator.generate(
                promptHistory = listOf(PromptVersion(prompt = "p", score = 0.5)),
            ),
        )
    }

    @Test
    fun `returns null when model output has no non-blank line`() = runTest {
        coEvery { llmProvider.generate(any()) } returns LlmResponse(content = "\n  \n")

        assertNull(
            generator.generate(
                promptHistory = listOf(PromptVersion(prompt = "task", score = 0.1)),
            ),
        )
    }

    @Test
    fun `calls LlmProvider and returns first non-blank line`() = runTest {
        coEvery { llmProvider.generate(any()) } returns
            LlmResponse(
                content =
                    """
                    |noise line
                    |  new instruction text

                    """.trimMargin(),
            )

        val out =
            generator.generate(
                promptHistory = listOf(PromptVersion(prompt = "old", score = 0.2)),
            )

        assertEquals("noise line", out)
        coVerify(exactly = 1) { llmProvider.generate(any()) }
    }

    @Test
    fun `prompt uses EDA template and lists only instruction bodies without scores`() = runTest {
        val requestSlot = slot<LlmRequest>()
        coEvery { llmProvider.generate(capture(requestSlot)) } returns LlmResponse(content = "out")

        generator.generate(
            promptHistory =
                listOf(
                    PromptVersion(prompt = "alpha body", score = 0.99),
                    PromptVersion(prompt = "beta body", score = 0.5),
                ),
            topN = 5,
        )

        val sent = requestSlot.captured.prompt
        assertTrue(sent.startsWith("Here are examples of instructions for the same task:"))
        assertTrue(sent.contains("- alpha body"))
        assertTrue(sent.contains("- beta body"))
        assertFalse(sent.contains("0.99"))
        assertFalse(sent.contains("0.5"))
        assertTrue(
            sent.contains(
                "Continue this list with one new instruction that fits the same task " +
                    "and style but is different from all of the above.",
            ),
        )
        assertTrue(sent.contains("Return only the instruction text, nothing else."))
    }

    @Test
    fun `blank entries are skipped and topN prefers higher scores before shuffle`() = runTest {
        val requestSlot = slot<LlmRequest>()
        coEvery { llmProvider.generate(capture(requestSlot)) } returns LlmResponse(content = "x")

        generator.generate(
            promptHistory =
                listOf(
                    PromptVersion(prompt = "low", score = 0.1),
                    PromptVersion(prompt = "", score = 0.99),
                    PromptVersion(prompt = "high", score = 0.9),
                    PromptVersion(prompt = "mid", score = 0.5),
                ),
            topN = 2,
        )

        val sent = requestSlot.captured.prompt
        assertTrue(sent.contains("- high"))
        assertTrue(sent.contains("- mid"))
        assertFalse(sent.contains("- low"))
    }

    @Test
    fun `null-scored prompts are included when topN covers the whole filtered list`() = runTest {
        val requestSlot = slot<LlmRequest>()
        coEvery { llmProvider.generate(capture(requestSlot)) } returns LlmResponse(content = "x")

        generator.generate(
            promptHistory =
                listOf(
                    PromptVersion(prompt = "no score", score = null),
                    PromptVersion(prompt = "rated", score = 0.2),
                ),
            topN = 2,
        )

        val sent = requestSlot.captured.prompt
        assertTrue(sent.contains("- rated"))
        assertTrue(sent.contains("- no score"))
    }
}
