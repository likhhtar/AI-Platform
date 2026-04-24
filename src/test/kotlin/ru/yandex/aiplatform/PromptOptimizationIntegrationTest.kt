package ru.yandex.aiplatform

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import ru.yandex.diploma.aiplatform.AiPlatformApplication
import ru.yandex.diploma.aiplatform.application.usecase.OptimizationExperimentRunner
import ru.yandex.diploma.aiplatform.domain.model.*
import ru.yandex.diploma.aiplatform.domain.service.PromptOptimizerRegistry
import ru.yandex.diploma.aiplatform.infrastructure.service.OptimizationHtmlReportGenerator
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(classes = [AiPlatformApplication::class])
@ActiveProfiles("test")
class PromptOptimizationIntegrationTest {

    @Autowired
    private lateinit var optimizationRunner: OptimizationExperimentRunner

    @Autowired
    private lateinit var optimizerRegistry: PromptOptimizerRegistry

    @Autowired
    private lateinit var reportGenerator: OptimizationHtmlReportGenerator

    @Test
    fun `should have both optimizers available`() {
        val availableOptimizers = optimizerRegistry.getAvailableOptimizers()
        
        assertTrue(availableOptimizers.contains(OptimizerType.LLM))
        assertTrue(availableOptimizers.contains(OptimizerType.RULE_BASED))
    }

    @Test
    fun `should run rule-based optimization successfully`() = runBlocking {
        val agentConfig = AgentConfig.create("test-agent", "Ты полезный помощник.")
        
        val optimizationConfig = OptimizationConfig(
            enabled = true,
            mode = OptimizationMode.SUGGEST,
            type = OptimizerType.RULE_BASED,
            iterations = 1,
            ruleBasedConfig = RuleBasedOptimizerConfig(
                enableLengthOptimization = true,
                enableClarityOptimization = true,
                enableSpecificityOptimization = true
            )
        )

        val configYaml = """
suite:
  name: "Test Optimization Suite"
  version: "1.0"

prompts:
  - id: "test_prompt"
    template: "Ответь: {{question}}"

agents:
  - name: "test-agent"
    systemPrompt: "Ты полезный помощник."
    model: "deterministic-model-v1"
    provider: "deterministic"

test_cases:
  - prompt_id: "test_prompt"
    agents: ["test-agent"]
    variables:
      question: "Что такое AI?"
    expected: "Deterministic"
    evaluator: "contains"
        """.trimIndent()

        assertDoesNotThrow {
            val result = optimizationRunner.runExperimentWithOptimization(
                configurationSource = configYaml,
                agentConfig = agentConfig,
                optimizationConfig = optimizationConfig
            )

            assertNotNull(result)
            assertNotNull(result.optimizationResult)
            assertTrue(result.optimizationResult.suggestions.isNotEmpty())
            assertTrue(result.optimizationResult.confidence >= 0.0)
            assertTrue(result.optimizationResult.confidence <= 1.0)
        }
    }

    @Test
    fun `should run experiment without optimization when disabled`() = runBlocking {
        val agentConfig = AgentConfig.create("test-agent", "Ты полезный помощник.")
        
        val optimizationConfig = OptimizationConfig(enabled = false)

        val configYaml = """
suite:
  name: "Test Suite Without Optimization"

prompts:
  - id: "simple_prompt"
    template: "Скажи привет {{name}}"

agents:
  - name: "test-agent"
    systemPrompt: "Ты дружелюбный помощник."
    model: "deterministic-model-v1"
    provider: "deterministic"

test_cases:
  - prompt_id: "simple_prompt"
    agents: ["test-agent"]
    variables:
      name: "Мир"
    expected: "Deterministic"
    evaluator: "contains"
        """.trimIndent()

        assertDoesNotThrow {
            val result = optimizationRunner.runExperimentWithOptimization(
                configurationSource = configYaml,
                agentConfig = agentConfig,
                optimizationConfig = optimizationConfig
            )

            assertNotNull(result)
            assertNotNull(result.baselineResult)
            assertEquals(false, result.config.enabled)
            // When optimization is disabled, optimizedExperimentResult should be null
            assertEquals(null, result.optimizedExperimentResult)
        }
    }

    @Test
    fun `should generate optimization report`() = runBlocking {
        // Create a mock optimization result
        val originalPrompt = Prompt("test", "Test Prompt", "Answer: {{question}}")
        val optimizedPrompt = Prompt("test_opt", "Optimized Test Prompt", "Provide a detailed answer to: {{question}}")
        
        val suggestions = listOf(
            OptimizationSuggestion(
                type = SuggestionType.SPECIFICITY,
                description = "Make the instruction more specific",
                originalText = "Answer:",
                suggestedText = "Provide a detailed answer to:",
                impact = SuggestionImpact.MEDIUM,
                confidence = 0.8,
                reasoning = "More specific instructions lead to better responses"
            )
        )

        val optimizationResult = OptimizationResult(
            originalPrompt = originalPrompt,
            optimizedPrompt = optimizedPrompt,
            suggestions = suggestions,
            confidence = 0.8,
            reasoning = "Test optimization reasoning",
            executionTimeMs = 1000
        )

        val baselineResult = createMockExperimentResult("baseline")
        val optimizedExperimentResult = createMockExperimentResult("optimized")

        val optimizationExperimentResult = OptimizationExperimentResult(
            baselineResult = baselineResult,
            optimizationResult = optimizationResult,
            optimizedExperimentResult = optimizedExperimentResult,
            improvement = OptimizationImprovement(
                scoreImprovement = 0.15,
                latencyChange = -50.0,
                passRateImprovement = 0.2,
                significantImprovement = true
            ),
            config = OptimizationConfig(
                enabled = true,
                type = OptimizerType.RULE_BASED,
                ruleBasedConfig = RuleBasedOptimizerConfig()
            ),
            executionTimeMs = 2000
        )

        assertDoesNotThrow {
            val reportFile = reportGenerator.generateOptimizationReport(
                optimizationExperimentResult,
                "reports/"
            )
            
            assertNotNull(reportFile)
            assertTrue(reportFile.name.contains("optimization-report"))
            assertTrue(reportFile.name.endsWith(".html"))
        }
    }

    @Test
    fun `should compare optimization strategies`() = runBlocking {
        val agentConfig = AgentConfig.create("test-agent", "Ты помощник.")
        
        val llmConfig = OptimizationConfig(
            enabled = true,
            mode = OptimizationMode.SUGGEST,
            type = OptimizerType.LLM,
            llmConfig = LlmOptimizerConfig(
                provider = "mock",
                model = "mock-model"
            )
        )
        
        val ruleConfig = OptimizationConfig(
            enabled = true,
            mode = OptimizationMode.SUGGEST,
            type = OptimizerType.RULE_BASED
        )

        val configYaml = """
suite:
  name: "Strategy Comparison Test"

prompts:
  - id: "comparison_prompt"
    template: "Объясни {{topic}}"

agents:
  - name: "test-agent"
    systemPrompt: "Ты помощник."
    model: "deterministic-model-v1"
    provider: "deterministic"

test_cases:
  - prompt_id: "comparison_prompt"
    agents: ["test-agent"]
    variables:
      topic: "машинное обучение"
    expected: "машинное"
    evaluator: "contains"
        """.trimIndent()

        assertDoesNotThrow {
            val results = optimizationRunner.compareOptimizationStrategies(
                configurationSource = configYaml,
                agentConfig = agentConfig,
                optimizationConfigs = listOf(ruleConfig)
            )

            assertNotNull(results)
            assertTrue(results.containsKey(OptimizerType.RULE_BASED))
            
            val ruleResult = results[OptimizerType.RULE_BASED]
            assertNotNull(ruleResult)
            assertNotNull(ruleResult.optimizationResult)
        }
    }

    private fun createMockExperimentResult(name: String): ru.yandex.diploma.aiplatform.application.usecase.ExperimentResult {
        val agentConfig = AgentConfig.create(name, "Test agent")
        val run = ru.yandex.diploma.aiplatform.application.usecase.ExperimentRun(
            agentName = name,
            model = "mock",
            temperature = 0.7,
            result = null,
            success = true,
            error = null,
            executionTimeMs = 1000
        )
        val metrics = ru.yandex.diploma.aiplatform.application.usecase.ExperimentMetrics(
            totalRuns = 1,
            successfulRuns = 1,
            failedRuns = 0,
            averageLatency = 1000.0,
            averageScore = 0.8
        )
        
        return ru.yandex.diploma.aiplatform.application.usecase.ExperimentResult(
            agentConfig = agentConfig,
            runs = listOf(run),
            metrics = metrics,
            executionTimeMs = 1000,
            timestamp = "2024-01-01T00:00:00Z"
        )
    }
}