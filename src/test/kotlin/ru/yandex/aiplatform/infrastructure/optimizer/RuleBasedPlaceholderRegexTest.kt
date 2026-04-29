package ru.yandex.aiplatform.infrastructure.optimizer

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import ru.yandex.diploma.aiplatform.domain.model.AgentConfig
import ru.yandex.diploma.aiplatform.domain.model.EvaluationResult
import ru.yandex.diploma.aiplatform.domain.model.OptimizationConfig
import ru.yandex.diploma.aiplatform.domain.model.OptimizationInput
import ru.yandex.diploma.aiplatform.domain.model.OptimizationMode
import ru.yandex.diploma.aiplatform.domain.model.OptimizationRule
import ru.yandex.diploma.aiplatform.domain.model.OptimizerType
import ru.yandex.diploma.aiplatform.domain.model.Prompt
import ru.yandex.diploma.aiplatform.domain.model.RuleBasedOptimizerConfig
import ru.yandex.diploma.aiplatform.domain.model.TestCase
import ru.yandex.diploma.aiplatform.domain.model.TestResult
import ru.yandex.diploma.aiplatform.infrastructure.optimizer.RuleBasedPromptOptimizer

class RuleBasedPlaceholderRegexTest {

    @Test
    fun `configurable regex does not mutate inside placeholder spans`() = runBlocking {
        val optimizer = RuleBasedPromptOptimizer()
        val testCase =
            TestCase(
                promptId = "p1",
                agentName = "agent",
                variables = emptyMap(),
                expected = "ok",
                evaluatorType = "exact",
            )
        val testResult =
            TestResult(
                testCase = testCase,
                success = true,
                evaluationResult = EvaluationResult(passed = true, score = 1.0, explanation = ""),
                llmResponse = null,
                executionTimeMs = 1L,
                error = null,
            )
        val input =
            OptimizationInput(
                originalPrompt = Prompt("p1", "n", "foo and {{ foo }} end"),
                testCases = listOf(testCase),
                testResults = listOf(testResult),
                agentConfig = AgentConfig.create("agent", "system"),
            )
        val config =
            OptimizationConfig(
                enabled = true,
                mode = OptimizationMode.APPLY,
                type = OptimizerType.RULE_BASED,
                ruleBasedConfig =
                    RuleBasedOptimizerConfig(
                        rules =
                            listOf(
                                OptimizationRule("stripFoo", "", "foo", "baz", enabled = true),
                            ),
                        enableClarityOptimization = false,
                        enableSpecificityOptimization = false,
                        enableLengthOptimization = false,
                    ),
            )
        val result = optimizer.optimize(input, config)
        assertEquals("baz and {{ foo }} end", result.optimizedPrompt?.template)
    }
}
