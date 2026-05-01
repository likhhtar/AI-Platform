package ru.yandex.diploma.aiplatform.application.optimization

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import ru.yandex.diploma.aiplatform.domain.model.LlmResponse
import ru.yandex.diploma.aiplatform.domain.provider.LlmProvider
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ZeroOrderFallbackGeneratorTest {

    private val llmProvider =
        mockk<LlmProvider> {
            every { providerId } returns "test-provider"
        }
    private val generator = ZeroOrderFallbackGenerator(listOf(llmProvider))

    @Test
    fun `parses first list item when answer starts with 1 dot`() = runTest {
        coEvery { llmProvider.generate(any()) } returns
            LlmResponse(
                content =
                    """
                    1. Do the task carefully
                    2. Other
                    """.trimIndent(),
            )

        assertEquals(
            "Do the task carefully",
            generator.generate(problemDescription = "solve X"),
        )
    }

    @Test
    fun `parses line that starts with 1 dot after leading whitespace`() = runTest {
        coEvery { llmProvider.generate(any()) } returns
            LlmResponse(
                content =
                    """
                    intro line
                       1. Indented instruction
                    """.trimIndent(),
            )

        assertEquals(
            "Indented instruction",
            generator.generate(problemDescription = "task"),
        )
    }

    @Test
    fun `returns null when only whitespace follows 1 dot`() = runTest {
        coEvery { llmProvider.generate(any()) } returns LlmResponse(content = "1.   \n")

        assertNull(generator.generate(problemDescription = "p"))
    }

    @Test
    fun `returns null when LlmProvider throws`() = runTest {
        coEvery { llmProvider.generate(any()) } throws RuntimeException("network")

        assertNull(generator.generate(problemDescription = "p"))
    }

    @Test
    fun `returns null when no line starts with 1 dot`() = runTest {
        coEvery { llmProvider.generate(any()) } returns LlmResponse(content = "no numbering here")

        assertNull(generator.generate(problemDescription = "p"))
    }
}
