package ru.yandex.aiplatform.domain.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import ru.yandex.diploma.aiplatform.domain.model.AgentConfig
import ru.yandex.diploma.aiplatform.domain.model.ExecutionConfig
import ru.yandex.diploma.aiplatform.domain.model.LlmOptimizerConfig
import ru.yandex.diploma.aiplatform.domain.model.OptimizationConfig
import ru.yandex.diploma.aiplatform.domain.model.OptimizationMode
import ru.yandex.diploma.aiplatform.domain.model.OptimizerType
import ru.yandex.diploma.aiplatform.domain.model.Prompt
import ru.yandex.diploma.aiplatform.domain.model.TestCase
import ru.yandex.diploma.aiplatform.domain.repository.TestConfiguration

class TestConfigurationSemanticFingerprintTest {

    @Test
    fun `semantic fingerprint ignores metadata ordering and suite-only differences`() {
        val prompts =
            listOf(
                Prompt("a", "n", "Hello {{x}}", setOf("x")),
                Prompt("b", "n2", "Bye", emptySet()),
            )
        val tests =
            listOf(
                TestCase(
                    promptId = "a",
                    agentName = "ag",
                    variables = emptyMap(),
                    expected = "e",
                    evaluatorType = "exact",
                ),
            )
        val agents = listOf(AgentConfig.create("ag", "sys"))
        val opt =
            OptimizationConfig(
                enabled = true,
                mode = OptimizationMode.APPLY,
                type = OptimizerType.LLM,
                iterations = 2,
                llmConfig = LlmOptimizerConfig(provider = "p", model = "m"),
            )
        val c1 =
            TestConfiguration(
                agents = agents,
                prompts = prompts,
                tests = tests,
                metadata = mapOf("z" to 1, "a" to 2),
                suiteMetadata = ru.yandex.diploma.aiplatform.domain.model.TestSuiteMetadata(name = "S"),
                executionConfig = ExecutionConfig(),
                optimizationConfig = opt,
            )
        val c2 =
            TestConfiguration(
                agents = agents,
                prompts = listOf(prompts[1], prompts[0]),
                tests = tests,
                metadata = mapOf("a" to 2, "z" to 1),
                suiteMetadata = ru.yandex.diploma.aiplatform.domain.model.TestSuiteMetadata(name = "Other"),
                executionConfig = ExecutionConfig(),
                optimizationConfig = opt,
            )
        assertEquals(c1.semanticFingerprint(), c2.semanticFingerprint())
    }
}
