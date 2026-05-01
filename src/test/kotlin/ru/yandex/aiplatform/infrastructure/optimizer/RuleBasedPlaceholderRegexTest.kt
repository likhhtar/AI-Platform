package ru.yandex.aiplatform.infrastructure.optimizer

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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

    @Test
    fun `clarity rules strip vague words but keep placeholders`() = runBlocking {
        val optimizer = RuleBasedPromptOptimizer()
        val input = optimizationInput("Возможно, напиши рекламу для: {{product}}")
        val config =
            ruleBasedConfig(
                enableClarityOptimization = true,
                enableSpecificityOptimization = false,
                enableLengthOptimization = false,
            )
        val result = optimizer.optimize(input, config)
        val template = result.optimizedPrompt?.template.orEmpty()
        assertTrue(template.contains("{{product}}"), "placeholder must survive: $template")
        assertTrue(!template.contains("возможно", ignoreCase = true), "vague word should be stripped: $template")
    }

    @Test
    fun `full rule pipeline preserves exp-rulebased template variables`() = runBlocking {
        val optimizer = RuleBasedPromptOptimizer()
        val input =
            optimizationInput(
                "Напиши рекламу для: {{product}}",
                failures = 2,
            )
        val config =
            ruleBasedConfig(
                enableClarityOptimization = true,
                enableSpecificityOptimization = true,
                enableLengthOptimization = true,
            )
        val result = optimizer.optimize(input, config)
        val template = result.optimizedPrompt?.template.orEmpty()
        assertTrue(template.contains("{{product}}"), "product placeholder must survive: $template")
    }

    @Test
    fun `output contract and constraints are not re-injected when marker survives`() = runBlocking {
        val optimizer = RuleBasedPromptOptimizer()
        val template =
            """
            |Напиши рекламу для: {{product}}
            |<!-- rule:output-contract -->
            |Сначала ответь на запрос пользователя.
            |<!-- rule:constraints -->
            |- Не противоречь предыдущим инструкциям.
            """.trimMargin()
        val input = optimizationInput(template, failures = 2)
        val config =
            ruleBasedConfig(
                enableClarityOptimization = false,
                enableSpecificityOptimization = true,
                enableLengthOptimization = false,
            )
        val result = optimizer.optimize(input, config)
        val out = result.optimizedPrompt?.template.orEmpty()
        assertEquals(template, out)
        assertTrue("injectOutputContract" !in (result.metadata["stageNames"] as? List<*>).orEmpty())
        assertTrue("failureDrivenConstraints" !in (result.metadata["stageNames"] as? List<*>).orEmpty())
    }

    @Test
    fun `regex rule that would drop placeholder is skipped`() = runBlocking {
        val optimizer = RuleBasedPromptOptimizer()
        val input = optimizationInput("prefix {{product}} suffix")
        val config =
            ruleBasedConfig(
                enableClarityOptimization = false,
                enableSpecificityOptimization = false,
                enableLengthOptimization = false,
                rules =
                    listOf(
                        OptimizationRule(
                            name = "stripBraces",
                            description = "",
                            pattern = """\{\{.*?\}\}""",
                            replacement = "",
                            enabled = true,
                        ),
                    ),
            )
        val result = optimizer.optimize(input, config)
        assertEquals("prefix {{product}} suffix", result.optimizedPrompt?.template)
    }

    private fun optimizationInput(template: String, failures: Int = 0): OptimizationInput {
        val testCase =
            TestCase(
                promptId = "p1",
                agentName = "agent",
                variables = mapOf("product" to "x"),
                expected = "ok",
                evaluatorType = "exact",
            )
        val failed =
            TestResult(
                testCase = testCase,
                success = false,
                evaluationResult = EvaluationResult(passed = false, score = 0.2, explanation = "fail"),
                llmResponse = null,
                executionTimeMs = 1L,
                error = null,
            )
        val passed =
            TestResult(
                testCase = testCase,
                success = true,
                evaluationResult = EvaluationResult(passed = true, score = 1.0, explanation = ""),
                llmResponse = null,
                executionTimeMs = 1L,
                error = null,
            )
        val testResults =
            if (failures <= 0) {
                listOf(passed)
            } else {
                List(failures) { failed }
            }
        return OptimizationInput(
            originalPrompt = Prompt("p1", "n", template),
            testCases = listOf(testCase),
            testResults = testResults,
            agentConfig = AgentConfig.create("agent", "system"),
        )
    }

    private fun ruleBasedConfig(
        enableClarityOptimization: Boolean,
        enableSpecificityOptimization: Boolean,
        enableLengthOptimization: Boolean,
        rules: List<OptimizationRule> = emptyList(),
    ): OptimizationConfig =
        OptimizationConfig(
            enabled = true,
            mode = OptimizationMode.APPLY,
            type = OptimizerType.RULE_BASED,
            ruleBasedConfig =
                RuleBasedOptimizerConfig(
                    rules = rules,
                    enableClarityOptimization = enableClarityOptimization,
                    enableSpecificityOptimization = enableSpecificityOptimization,
                    enableLengthOptimization = enableLengthOptimization,
                ),
        )
}
