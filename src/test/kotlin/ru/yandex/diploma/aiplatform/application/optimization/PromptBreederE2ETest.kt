package ru.yandex.diploma.aiplatform.application.optimization

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.yandex.diploma.aiplatform.application.usecase.ExperimentRunner
import ru.yandex.diploma.aiplatform.application.usecase.RunTestSuiteUseCase
import ru.yandex.diploma.aiplatform.config.JudgeEvaluationProperties
import ru.yandex.diploma.aiplatform.domain.evaluator.EvaluatorRegistry
import ru.yandex.diploma.aiplatform.domain.model.AgentConfig
import ru.yandex.diploma.aiplatform.domain.model.BaselineKeys
import ru.yandex.diploma.aiplatform.domain.model.LlmRequest
import ru.yandex.diploma.aiplatform.domain.model.LlmResponse
import ru.yandex.diploma.aiplatform.domain.provider.LlmProvider
import ru.yandex.diploma.aiplatform.domain.provider.ProviderNotFoundException
import ru.yandex.diploma.aiplatform.domain.provider.ProviderRegistry
import ru.yandex.diploma.aiplatform.domain.repository.BaselineLoadResult
import ru.yandex.diploma.aiplatform.domain.repository.BaselineRepository
import ru.yandex.diploma.aiplatform.domain.repository.TestConfigurationRepository
import ru.yandex.diploma.aiplatform.domain.service.ReportGenerator
import ru.yandex.diploma.aiplatform.infrastructure.evaluator.ContainsEvaluator
import ru.yandex.diploma.aiplatform.infrastructure.evaluator.DefaultEvaluatorRegistry
import ru.yandex.diploma.aiplatform.infrastructure.evaluator.ExactMatchEvaluator
import ru.yandex.diploma.aiplatform.infrastructure.optimizer.DefaultPromptOptimizerRegistry
import ru.yandex.diploma.aiplatform.infrastructure.optimizer.LlmPromptOptimizer
import ru.yandex.diploma.aiplatform.infrastructure.repository.FileBaselineRepository
import ru.yandex.diploma.aiplatform.infrastructure.service.DefaultPromptOptimizationService
import ru.yandex.diploma.aiplatform.infrastructure.service.DefaultProviderValidationService
import ru.yandex.diploma.aiplatform.infrastructure.service.QAVerificationHtmlReportGenerator
import ru.yandex.diploma.aiplatform.infrastructure.yaml.YamlTestConfigurationRepository

class PromptBreederE2ETest {

    private val originalMutationPrompt = "Rewrite this instruction to be clearer and more specific"
    private val originalPromptTemplate = "Answer the question briefly: {{question}}"

    @TempDir
    lateinit var baselineDir: Path

    @Test
    fun `default prompt optimization service evolves mutation prompt over six iterations`() = runBlocking {
        val yamlPath =
            Path.of(
                javaClass.classLoader.getResource("test-configs/prompt-breeder-e2e.yaml")!!.toURI(),
            ).toString()

        val llmProvider = mockk<LlmProvider>()
        every { llmProvider.providerId } returns "openrouter"
        coEvery { llmProvider.isHealthy() } returns true

        val optimizerCallSeq = AtomicInteger(0)
        val capturedPrompts = mutableListOf<String>()
        val requestSlot = slot<LlmRequest>()

        coEvery { llmProvider.generate(capture(requestSlot)) } coAnswers {
            val prompt = requestSlot.captured.prompt
            capturedPrompts.add(prompt)
            routeLlmResponse(prompt, optimizerCallSeq)
        }

        val baselineRepository: BaselineRepository = FileBaselineRepository.forDirectory(baselineDir)
        val configurationRepository: TestConfigurationRepository = YamlTestConfigurationRepository()
        val providerRegistry: ProviderRegistry = SingleProviderRegistry(llmProvider)
        val evaluatorRegistry: EvaluatorRegistry =
            DefaultEvaluatorRegistry(
                listOf(ExactMatchEvaluator(), ContainsEvaluator()),
            )
        val reportGenerator: ReportGenerator = QAVerificationHtmlReportGenerator()
        val providerValidationService =
            DefaultProviderValidationService(providerRegistry, JudgeEvaluationProperties())

        val runTestSuiteUseCase =
            RunTestSuiteUseCase(
                configurationRepository = configurationRepository,
                providerRegistry = providerRegistry,
                evaluatorRegistry = evaluatorRegistry,
                reportGenerator = reportGenerator,
                providerValidationService = providerValidationService,
            )

        val experimentRunner = ExperimentRunner(runTestSuiteUseCase)
        val optimizerRegistry =
            DefaultPromptOptimizerRegistry(
                listOf(LlmPromptOptimizer(providerRegistry)),
            )

        val service =
            DefaultPromptOptimizationService(
                optimizerRegistry = optimizerRegistry,
                experimentRunner = experimentRunner,
                testConfigurationRepository = configurationRepository,
                baselineRepository = baselineRepository,
                lineageAwareOptimizationPromptBuilder = LineageAwareOptimizationPromptBuilder(),
                lamarckianCandidateGenerator = LamarckianCandidateGenerator(listOf(llmProvider)),
                mutationPromptEvolver = MutationPromptEvolver(listOf(llmProvider)),
                zeroOrderFallbackGenerator = ZeroOrderFallbackGenerator(listOf(llmProvider)),
                edaCandidateGenerator = EdaCandidateGenerator(listOf(llmProvider)),
                textGradCandidateGenerator = TextGradCandidateGenerator(listOf(llmProvider)),
            )

        val loaded = configurationRepository.loadConfiguration(yamlPath)
        val optimizationConfig =
            requireNotNull(loaded.optimizationConfig) {
                "prompt-breeder-e2e.yaml must define optimizer config"
            }
        val agentConfig =
            loaded.agents.firstOrNull { it.name == "test-agent" }
                ?: AgentConfig.create("test-agent", "You are a helpful assistant.")

        val result =
            service.runOptimizationExperiment(
                configurationSource = yamlPath,
                agentConfig = agentConfig,
                optimizationConfig = optimizationConfig,
            )

        assertNotNull(result.optimizationResult)
        assertTrue(result.iterationSummaries.size >= 6, "expected six iteration summaries, got ${result.iterationSummaries.size}")

        val suiteId = BaselineKeys.suiteId(yamlPath, loaded.suiteMetadata)
        val baselines =
            when (val load = baselineRepository.loadAll(suiteId)) {
                is BaselineLoadResult.Loaded -> load.data.values.toList()
                else -> emptyList()
            }

        assertTrue(baselines.isNotEmpty(), "baseline repository should contain recorded entries")
        assertTrue(
            baselines.any { !it.promptVersion?.mutationPrompt.isNullOrBlank() },
            "recorded baselines should carry mutationPrompt",
        )

        val iterationsFourToSix =
            baselines.mapNotNull { it.promptVersion }.filter { pv ->
                val i = pv.iteration ?: return@filter false
                i in 4..6
            }
        assertTrue(iterationsFourToSix.isNotEmpty(), "expected PromptVersion entries for iterations 4–6")
        assertTrue(
            iterationsFourToSix.all { it.mutationPrompt != originalMutationPrompt },
            "mutationPrompt should differ from original after evolve at iteration 3",
        )

        assertTrue(
            baselines.mapNotNull { it.promptVersion?.prompt }.any { it != originalPromptTemplate },
            "at least one recorded prompt version should differ from the original template",
        )

        assertTrue(
            capturedPrompts.any { it.contains("# Prompt optimization") },
            "optimizer should have invoked the LLM with the optimization meta-prompt",
        )
    }

    private fun routeLlmResponse(prompt: String, optimizerCallSeq: AtomicInteger): LlmResponse =
        when {
            prompt.contains("A list of 10") ->
                LlmResponse(
                    content = "1. Answer concisely and directly\n2. Be specific",
                )

            prompt.contains("summarize and improve") ->
                LlmResponse(content = "Make the instruction more precise and action-oriented")

            prompt.contains("Write an improved instruction") ->
                LlmResponse(content = "Answer the question directly and concisely: {{question}}")

            prompt.contains("# Prompt optimization") || prompt.contains("\"optimizedPrompt\"") -> {
                val n = optimizerCallSeq.incrementAndGet()
                LlmResponse(
                    content =
                        """
                        {"confidence":0.93,"reasoning":"iteration_$n","suggestions":[],"optimizedPrompt":{"template":"Answer briefly and precisely (v$n): {{question}}","name":"Optimized"}}
                        """.trimIndent(),
                )
            }

            prompt.contains("2+2") -> LlmResponse(content = "The answer is 4")
            prompt.contains("France") -> LlmResponse(content = "Paris")
            prompt.contains("sky") -> LlmResponse(content = "blue")

            else ->
                LlmResponse(
                    content =
                        """
                        {"confidence":0.9,"reasoning":"fallback","suggestions":[],"optimizedPrompt":{"template":"Answer briefly and precisely: {{question}}","name":"Optimized"}}
                        """.trimIndent(),
                )
        }

    private class SingleProviderRegistry(
        private val provider: LlmProvider,
    ) : ProviderRegistry {
        private val id: String = provider.providerId

        override fun register(provider: LlmProvider) {
            throw UnsupportedOperationException()
        }

        override fun getProvider(providerId: String): LlmProvider =
            if (providerId == id) {
                provider
            } else {
                throw ProviderNotFoundException(providerId)
            }

        override fun getAvailableProviders(): Set<String> = setOf(id)

        override fun isProviderRegistered(providerId: String): Boolean = providerId == id

        override fun unregister(providerId: String): Boolean = false
    }
}
