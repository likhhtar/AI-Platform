package ru.yandex.diploma.aiplatform.infrastructure.service

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import ru.yandex.diploma.aiplatform.application.optimization.LamarckianCandidateGenerator
import ru.yandex.diploma.aiplatform.application.optimization.LineageAwareOptimizationPromptBuilder
import ru.yandex.diploma.aiplatform.application.optimization.MutationPromptEvolver
import ru.yandex.diploma.aiplatform.application.optimization.ZeroOrderFallbackGenerator
import ru.yandex.diploma.aiplatform.application.usecase.ExperimentResult
import ru.yandex.diploma.aiplatform.application.usecase.ExperimentRun
import ru.yandex.diploma.aiplatform.application.usecase.ExperimentMetrics
import ru.yandex.diploma.aiplatform.application.usecase.TestSuiteResult
import ru.yandex.diploma.aiplatform.domain.model.AgentConfig
import ru.yandex.diploma.aiplatform.domain.model.EvaluationResult
import ru.yandex.diploma.aiplatform.domain.model.LlmOptimizerConfig
import ru.yandex.diploma.aiplatform.domain.model.OptimizationConfig
import ru.yandex.diploma.aiplatform.domain.model.OptimizationMode
import ru.yandex.diploma.aiplatform.domain.model.OptimizationResult
import ru.yandex.diploma.aiplatform.domain.model.OptimizationStatus
import ru.yandex.diploma.aiplatform.domain.model.OptimizerType
import ru.yandex.diploma.aiplatform.domain.model.Prompt
import ru.yandex.diploma.aiplatform.domain.model.PromptVersion
import ru.yandex.diploma.aiplatform.domain.model.TestCase
import ru.yandex.diploma.aiplatform.domain.model.TestSuiteMetadata
import java.time.Instant
import ru.yandex.diploma.aiplatform.domain.model.BaselineEntry
import ru.yandex.diploma.aiplatform.domain.repository.BaselineLoadResult
import ru.yandex.diploma.aiplatform.domain.repository.TestConfiguration
import ru.yandex.diploma.aiplatform.domain.repository.TestConfigurationRepository
import ru.yandex.diploma.aiplatform.domain.model.TestResult
import ru.yandex.diploma.aiplatform.domain.repository.BaselineRepository
import ru.yandex.diploma.aiplatform.domain.service.PromptOptimizer
import ru.yandex.diploma.aiplatform.domain.service.PromptOptimizerRegistry
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultPromptOptimizationServiceTest {

    private val yamlSource = "/tmp/default-prompt-opt-service-test.yaml"

    private fun testConfiguration(): TestConfiguration {
        val prompt = Prompt("p1", "n", "Hello {{q}}", setOf("q"))
        val agent = AgentConfig.create("agent1", "sys", provider = "p", model = "m")
        val testCase =
            TestCase(
                promptId = "p1",
                agentName = "agent1",
                variables = mapOf("q" to "x"),
                expected = "ok",
                evaluatorType = "contains",
            )
        return TestConfiguration(
            agents = listOf(agent),
            prompts = listOf(prompt),
            tests = listOf(testCase),
            suiteMetadata = TestSuiteMetadata(name = "default-prompt-opt-test-suite"),
        )
    }

    private fun baselineExperiment(agent: AgentConfig): ExperimentResult {
        val testCase =
            TestCase(
                promptId = "p1",
                agentName = "agent1",
                variables = mapOf("q" to "x"),
                expected = "ok",
                evaluatorType = "contains",
            )
        val testResult =
            TestResult(
                testCase = testCase,
                success = true,
                evaluationResult =
                    EvaluationResult(
                        passed = true,
                        score = 0.5,
                        explanation = "",
                    ),
                llmResponse = null,
                executionTimeMs = 1L,
                error = null,
            )
        val suite = TestSuiteResult.create(listOf(testResult), 1L)
        val run =
            ExperimentRun(
                agentName = agent.name,
                model = "m",
                temperature = 0.0,
                result = suite,
                success = true,
                error = null,
            )
        return ExperimentResult(
            agentConfig = agent,
            runs = listOf(run),
            metrics =
                ExperimentMetrics(
                    totalRuns = 1,
                    successfulRuns = 1,
                    failedRuns = 0,
                    averageLatency = 1.0,
                    averageScore = 0.5,
                ),
            executionTimeMs = 1L,
            timestamp = "t",
            configurationSource = yamlSource,
        )
    }

    private fun optimizationConfig(
        useLineage: Boolean = true,
        useLamarckian: Boolean = true,
    ): OptimizationConfig =
        OptimizationConfig(
            enabled = true,
            mode = OptimizationMode.SUGGEST,
            type = OptimizerType.LLM,
            iterations = 1,
            llmConfig = LlmOptimizerConfig(provider = "p", model = "m"),
            mutationPrompt = "mutation",
            useLineage = useLineage,
            useLamarckian = useLamarckian,
        )

    @Test
    fun `useLineage false passes empty versions to LineageAwareOptimizationPromptBuilder`() = runBlocking {
        val lineageBuilder = mockk<LineageAwareOptimizationPromptBuilder>()
        val versionsSlot = slot<List<PromptVersion>>()
        every {
            lineageBuilder.buildPrompt(any(), any(), capture(versionsSlot))
        } returns "meta-prompt"

        val baselineRepo = mockk<BaselineRepository>(relaxed = true)
        coEvery { baselineRepo.loadAll(any()) } returns
            BaselineLoadResult.Loaded(
                mapOf(
                    "k" to
                        BaselineEntry(
                            response = "",
                            metrics = emptyMap(),
                            createdAt = Instant.now(),
                            promptVersion =
                                PromptVersion(
                                    prompt = "old",
                                    score = 0.1,
                                ),
                        ),
                ),
            )

        val testConfigRepo = mockk<TestConfigurationRepository>()
        coEvery { testConfigRepo.loadConfiguration(any()) } returns testConfiguration()

        val prompt =
            Prompt("p1", "n", "Hello {{q}}", setOf("q"))
        val optimizer = mockk<PromptOptimizer>()
        coEvery { optimizer.isAvailable() } returns true
        coEvery { optimizer.optimize(any(), any()) } returns
            OptimizationResult(
                originalPrompt = prompt,
                optimizedPrompt = prompt.copy(template = "improved {{q}}"),
                suggestions = emptyList(),
                confidence = 0.9,
                reasoning = "r",
                executionTimeMs = 0L,
                status = OptimizationStatus.SUGGESTED,
            )

        val registry = mockk<PromptOptimizerRegistry>()
        every { registry.getOptimizer(OptimizerType.LLM) } returns optimizer

        val service =
            DefaultPromptOptimizationService(
                optimizerRegistry = registry,
                experimentRunner = mockk(relaxed = true),
                testConfigurationRepository = testConfigRepo,
                baselineRepository = baselineRepo,
                lineageAwareOptimizationPromptBuilder = lineageBuilder,
                lamarckianCandidateGenerator = mockk(relaxed = true),
                mutationPromptEvolver = mockk(relaxed = true),
                zeroOrderFallbackGenerator = mockk(relaxed = true),
                edaCandidateGenerator = mockk(relaxed = true),
                textGradCandidateGenerator = mockk(relaxed = true),
            )

        val agent = AgentConfig.create("agent1", "sys", provider = "p", model = "m")
        service.optimizeFromExperimentResult(
            experimentResult = baselineExperiment(agent),
            optimizationConfig = optimizationConfig(useLineage = false),
            configurationSource = yamlSource,
        )

        assertTrue(versionsSlot.isCaptured)
        assertEquals(emptyList(), versionsSlot.captured)
        coVerify(exactly = 0) { baselineRepo.loadAll(any()) }
    }

    @Test
    fun `useLamarckian false does not call LamarckianCandidateGenerator`() = runBlocking {
        val lamarckian = mockk<LamarckianCandidateGenerator>(relaxed = true)
        val lineageBuilder = mockk<LineageAwareOptimizationPromptBuilder>()
        every { lineageBuilder.buildPrompt(any(), any(), any()) } returns "meta"

        val testConfigRepo = mockk<TestConfigurationRepository>()
        coEvery { testConfigRepo.loadConfiguration(any()) } returns testConfiguration()

        val prompt =
            Prompt("p1", "n", "Hello {{q}}", setOf("q"))
        val optimizer = mockk<PromptOptimizer>()
        coEvery { optimizer.isAvailable() } returns true
        coEvery { optimizer.optimize(any(), any()) } returns
            OptimizationResult(
                originalPrompt = prompt,
                optimizedPrompt = prompt.copy(template = "improved {{q}}"),
                suggestions = emptyList(),
                confidence = 0.9,
                reasoning = "r",
                executionTimeMs = 0L,
                status = OptimizationStatus.SUGGESTED,
            )

        val registry = mockk<PromptOptimizerRegistry>()
        every { registry.getOptimizer(OptimizerType.LLM) } returns optimizer

        val service =
            DefaultPromptOptimizationService(
                optimizerRegistry = registry,
                experimentRunner = mockk(relaxed = true),
                testConfigurationRepository = testConfigRepo,
                baselineRepository = mockk(relaxed = true),
                lineageAwareOptimizationPromptBuilder = lineageBuilder,
                lamarckianCandidateGenerator = lamarckian,
                mutationPromptEvolver = mockk(relaxed = true),
                zeroOrderFallbackGenerator = mockk(relaxed = true),
                edaCandidateGenerator = mockk(relaxed = true),
                textGradCandidateGenerator = mockk(relaxed = true),
            )

        val agent = AgentConfig.create("agent1", "sys", provider = "p", model = "m")
        service.optimizeFromExperimentResult(
            experimentResult = baselineExperiment(agent),
            optimizationConfig = optimizationConfig(useLamarckian = false),
            configurationSource = yamlSource,
        )

        coVerify(exactly = 0) {
            lamarckian.generate(any(), any())
        }
    }
}
