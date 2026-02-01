package ru.yandex.diploma.aiplatform.application.usecase

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ru.yandex.diploma.aiplatform.domain.evaluator.EvaluatorRegistry
import ru.yandex.diploma.aiplatform.domain.model.*
import ru.yandex.diploma.aiplatform.domain.provider.LlmProvider
import ru.yandex.diploma.aiplatform.domain.provider.ProviderRegistry
import ru.yandex.diploma.aiplatform.domain.repository.TestConfiguration
import ru.yandex.diploma.aiplatform.domain.repository.TestConfigurationRepository
import ru.yandex.diploma.aiplatform.domain.service.ProviderValidationService
import ru.yandex.diploma.aiplatform.infrastructure.evaluator.ExactMatchEvaluator
import ru.yandex.diploma.aiplatform.infrastructure.llm.DeterministicLlmProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunTestSuiteUseCaseTest {

    private val configurationRepository = mockk<TestConfigurationRepository>()
    private val providerRegistry = mockk<ProviderRegistry>()
    private val evaluatorRegistry = mockk<EvaluatorRegistry>()
    private val reportGenerator = mockk<ru.yandex.diploma.aiplatform.domain.service.ReportGenerator>()
    private val providerValidationService = mockk<ProviderValidationService>()
    
    private val useCase = RunTestSuiteUseCase(
        configurationRepository,
        providerRegistry,
        evaluatorRegistry,
        reportGenerator,
        providerValidationService
    )

    @Test
    fun `should execute test suite successfully`() = runTest {
        val configuration = createSimpleTestConfiguration()
        val deterministicProvider = DeterministicLlmProvider()
        val exactEvaluator = ExactMatchEvaluator()

        coEvery { configurationRepository.loadConfiguration("test-config") } returns configuration
        every { providerRegistry.getProvider("deterministic") } returns deterministicProvider
        every { evaluatorRegistry.getEvaluator("exact") } returns exactEvaluator
        every { reportGenerator.generate(any(), any()) } returns mockk<java.io.File>()
        every { providerValidationService.validateConfiguration(any()) } returns emptyList()

        val result = useCase.execute("test-config")

        assertEquals(1, result.total)
        assertTrue(result.results.first().llmResponse?.content?.isNotEmpty() == true)
        assertTrue(result.results.isNotEmpty())
    }

    @Test
    fun `should handle test failure correctly`() = runTest {
        val configuration = createFailingTestConfiguration()
        val deterministicProvider = DeterministicLlmProvider()
        val exactEvaluator = ExactMatchEvaluator()

        coEvery { configurationRepository.loadConfiguration("test-config") } returns configuration
        every { providerRegistry.getProvider("deterministic") } returns deterministicProvider
        every { evaluatorRegistry.getEvaluator("exact") } returns exactEvaluator
        every { reportGenerator.generate(any(), any()) } returns mockk<java.io.File>()
        every { providerValidationService.validateConfiguration(any()) } returns emptyList()

        val result = useCase.execute("test-config")

        assertEquals(1, result.total)
        assertEquals(0, result.passed)
        assertEquals(1, result.failed)
        assertEquals(0.0, result.successRate)
        assertFalse(result.results.first().success)
        assertTrue(result.results.first().llmResponse?.content?.isNotEmpty() == true)
    }

    @Test
    fun `should throw exception for invalid configuration`() = runTest {
        val invalidConfiguration = TestConfiguration(
            agents = listOf(
                AgentConfig("agent1", "deterministic", "system prompt")
            ),
            prompts = listOf(
                Prompt("prompt1", "Test", "Hello {{name}}")
            ),
            tests = listOf(
                TestCase(
                    promptId = "nonexistent-prompt",
                    agentNames = listOf("agent1"),
                    variables = emptyMap(),
                    expected = "expected",
                    evaluatorType = "exact"
                )
            )
        )

        coEvery { configurationRepository.loadConfiguration("invalid-config") } returns invalidConfiguration
        every { providerValidationService.validateConfiguration(any()) } returns emptyList()

        assertThrows<TestSuiteException> {
            useCase.execute("invalid-config")
        }
    }

    private fun createSimpleTestConfiguration(): TestConfiguration {
        return TestConfiguration(
            agents = listOf(
                AgentConfig("simple-agent", "deterministic", "You are a helpful assistant", temperature = 0.0)
            ),
            prompts = listOf(
                Prompt("simple", "Simple", "Say hello", emptySet())
            ),
            tests = listOf(
                TestCase(
                    promptId = "simple",
                    agentNames = listOf("simple-agent"),
                    variables = emptyMap(),
                    expected = "Hello! I'm a deterministic AI assistant.",
                    evaluatorType = "exact"
                )
            )
        )
    }

    private fun createTestConfiguration(): TestConfiguration {
        return TestConfiguration(
            agents = listOf(
                AgentConfig("translation-agent", "deterministic", "You are a translator", temperature = 0.2)
            ),
            prompts = listOf(
                Prompt("translate", "Translation", "Translate {{text}} to {{language}}", setOf("text", "language"))
            ),
            tests = listOf(
                TestCase(
                    promptId = "translate",
                    agentNames = listOf("translation-agent"),
                    variables = mapOf("text" to "Hello", "language" to "French"),
                    expected = "Deterministic response generated for prompt: Translate Hello to French",
                    evaluatorType = "exact"
                )
            )
        )
    }

    private fun createFailingTestConfiguration(): TestConfiguration {
        return TestConfiguration(
            agents = listOf(
                AgentConfig("translation-agent", "deterministic", "You are a translator", temperature = 0.2)
            ),
            prompts = listOf(
                Prompt("translate", "Translation", "Translate {{text}} to {{language}}", setOf("text", "language"))
            ),
            tests = listOf(
                TestCase(
                    promptId = "translate",
                    agentNames = listOf("translation-agent"),
                    variables = mapOf("text" to "Hello", "language" to "French"),
                    expected = "This will not match the deterministic response",
                    evaluatorType = "exact"
                )
            )
        )
    }
}