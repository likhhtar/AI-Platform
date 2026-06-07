package ru.yandex.diploma.aiplatform.application.optimization

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import ru.yandex.diploma.aiplatform.domain.model.LlmRequest
import ru.yandex.diploma.aiplatform.domain.model.LlmResponse
import ru.yandex.diploma.aiplatform.domain.provider.LlmProvider
import kotlin.test.assertEquals

class MutationPromptEvolverTest {

    private val llmProvider =
        mockk<LlmProvider> {
            every { providerId } returns "test-provider"
        }
    private val evolver = MutationPromptEvolver(listOf(llmProvider))

    @Test
    fun `returns trimmed content on successful LLM response`() = runTest {
        coEvery { llmProvider.generate(any()) } returns LlmResponse(content = "  evolved text\n")

        assertEquals(
            "evolved text",
            evolver.evolve(currentMutationPrompt = "old", successRate = 0.3),
        )
    }

    @Test
    fun `returns currentMutationPrompt when response is blank after trim`() = runTest {
        coEvery { llmProvider.generate(any()) } returns LlmResponse(content = "   \n")

        val cur = "unchanged mutation prompt"
        assertEquals(cur, evolver.evolve(currentMutationPrompt = cur, successRate = 0.2))
    }

    @Test
    fun `returns currentMutationPrompt when LlmProvider throws`() = runTest {
        coEvery { llmProvider.generate(any()) } throws RuntimeException("boom")

        val cur = "safe"
        assertEquals(cur, evolver.evolve(currentMutationPrompt = cur, successRate = 0.9))
    }

    @Test
    fun `formats successRate as percent in prompt passed to LLM`() = runTest {
        val slot = slot<LlmRequest>()
        coEvery { llmProvider.generate(capture(slot)) } returns LlmResponse(content = "x")

        evolver.evolve(currentMutationPrompt = "instr", successRate = 0.5)

        assertEquals(true, slot.captured.prompt.contains("50.0%"))
    }
}
