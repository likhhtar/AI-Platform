package ru.yandex.aiplatform

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import ru.yandex.diploma.aiplatform.application.usecase.*
import ru.yandex.diploma.aiplatform.domain.evaluator.*
import ru.yandex.diploma.aiplatform.domain.model.*
import ru.yandex.diploma.aiplatform.domain.provider.*
import ru.yandex.diploma.aiplatform.domain.repository.*
import ru.yandex.diploma.aiplatform.domain.service.*
import ru.yandex.diploma.aiplatform.infrastructure.evaluator.*
import ru.yandex.diploma.aiplatform.infrastructure.llm.*
import ru.yandex.diploma.aiplatform.infrastructure.repository.*
import ru.yandex.diploma.aiplatform.infrastructure.service.QAVerificationHtmlReportGenerator
import ru.yandex.diploma.aiplatform.config.JudgeEvaluationProperties
import ru.yandex.diploma.aiplatform.config.OpenRouterModels
import ru.yandex.diploma.aiplatform.infrastructure.service.DefaultProviderValidationService

@SpringBootTest(classes = [ru.yandex.diploma.aiplatform.AiPlatformApplication::class])
@ActiveProfiles("real-llm")
@Tag("REAL_LLM")
@EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")
class StrictRealExecutionTest {
    
    @Autowired
    private lateinit var providerRegistry: ProviderRegistry
    
    @Autowired
    private lateinit var evaluatorRegistry: EvaluatorRegistry
    
    @Autowired
    private lateinit var runTestSuiteUseCase: RunTestSuiteUseCase

    @Test
    fun `STRICT VERIFICATION - Full pipeline with REAL LLM and NO MOCKS`() = runBlocking {
        verifyNoMocksInDependencyInjection()

        verifyRealImplementationsUsed()

        val testResults = executeRealPipelineWithMultiplePrompts()

        verifyResponsesAreNotHardcoded(testResults)

        verifyResponsesAreDifferentAndMeaningful(testResults)

        verifyFullDataFlowIntegrity(testResults)
    }

    private fun verifyNoMocksInDependencyInjection() {
        val providerClass = providerRegistry::class.java.simpleName
        
        assertFalse(
            providerClass.contains("Mock", ignoreCase = true) ||
            providerClass.contains("Fake", ignoreCase = true) ||
            providerClass.contains("Stub", ignoreCase = true),
            "FAILURE: ProviderRegistry is using mock implementation: $providerClass"
        )

        val evaluatorClass = evaluatorRegistry::class.java.simpleName

        assertFalse(
            evaluatorClass.contains("Mock", ignoreCase = true) ||
            evaluatorClass.contains("Fake", ignoreCase = true) ||
            evaluatorClass.contains("Stub", ignoreCase = true),
            "FAILURE: EvaluatorRegistry is using mock implementation: $evaluatorClass"
        )

        val useCaseClass = runTestSuiteUseCase::class.java.simpleName

        assertFalse(
            useCaseClass.contains("Mock", ignoreCase = true) ||
            useCaseClass.contains("Fake", ignoreCase = true) ||
            useCaseClass.contains("Stub", ignoreCase = true),
            "FAILURE: RunTestSuiteUseCase is using mock implementation: $useCaseClass"
        )
    }

    private fun verifyRealImplementationsUsed() {
        assertTrue(
            providerRegistry.isProviderRegistered("openrouter"),
            "FAILURE: OpenRouter provider not registered"
        )
        
        val openRouterProvider = providerRegistry.getProvider("openrouter")
        val providerImplClass = openRouterProvider::class.java.simpleName

        assertEquals(
            "OpenRouterLlmProvider",
            providerImplClass,
            "FAILURE: Expected OpenRouterLlmProvider, got: $providerImplClass"
        )
        
        val isHealthy = runBlocking { openRouterProvider.isHealthy() }
        assumeTrue(
            isHealthy,
            "Skipping REAL_LLM test: OpenRouter health check failed (invalid OPENROUTER_API_KEY, network, or quota)."
        )

        val exactEvaluator = evaluatorRegistry.getEvaluator("exact")
        val exactEvaluatorClass = exactEvaluator::class.java.simpleName

        assertEquals(
            "ExactMatchEvaluator",
            exactEvaluatorClass,
            "FAILURE: Expected ExactMatchEvaluator, got: $exactEvaluatorClass"
        )
    }

    private suspend fun executeRealPipelineWithMultiplePrompts(): List<TestSuiteResult> {
        val testConfigurations = listOf(
            createMathPromptConfiguration(),
            createCreativePromptConfiguration(),
            createAnalyticalPromptConfiguration()
        )
        
        val results = mutableListOf<TestSuiteResult>()
        
        testConfigurations.forEachIndexed { index, config ->
            val configRepo = createInMemoryConfigRepository(config)
            val tempUseCase = RunTestSuiteUseCase(
                configurationRepository = configRepo,
                providerRegistry = providerRegistry,
                evaluatorRegistry = evaluatorRegistry,
                reportGenerator = QAVerificationHtmlReportGenerator(),
                providerValidationService = DefaultProviderValidationService(
                    providerRegistry,
                    JudgeEvaluationProperties()
                )
            )
            
            val result = tempUseCase.execute(
                configurationSource = "test-config-${index}",
                executionConfig = ExecutionConfig(enableParallelExecution = false)
            )
            
            results.add(result)
        }

        return results
    }

    private fun verifyResponsesAreNotHardcoded(testResults: List<TestSuiteResult>) {
        testResults.forEach { suiteResult ->
            suiteResult.results.forEach { testResult ->
                val response = testResult.llmResponse?.content
                assertNotNull(response, "FAILURE: LLM response is null - no real API call made")
                
                val hardcodedPatterns = listOf(
                    "Test response",
                    "Mock response",
                    "Fake response",
                    "Stub response",
                    "Default response"
                )
                
                hardcodedPatterns.forEach { pattern ->
                    assertFalse(
                        response!!.contains(pattern, ignoreCase = true),
                        "FAILURE: Response contains hardcoded pattern '$pattern': $response"
                    )
                }
                
                assertTrue(
                    response!!.length > 5,
                    "FAILURE: Response too short, likely hardcoded: '$response'"
                )
                
                val tokensUsed = testResult.llmResponse?.tokensUsed
                assertNotNull(tokensUsed, "FAILURE: No token usage reported - likely fake response")
                assertTrue(
                    tokensUsed!! > 0,
                    "FAILURE: Zero tokens used - likely fake response"
                )
            }
        }
    }

    private fun verifyResponsesAreDifferentAndMeaningful(testResults: List<TestSuiteResult>) {
        val allResponses = testResults.flatMap { suiteResult ->
            suiteResult.results.mapNotNull { it.llmResponse?.content }
        }
        
        assertTrue(
            allResponses.size >= 3,
            "FAILURE: Expected at least 3 responses, got ${allResponses.size}"
        )
        
        val uniqueResponses = allResponses.toSet()
        assertTrue(
            uniqueResponses.size >= 2,
            "FAILURE: All responses are identical - likely deterministic/hardcoded: ${allResponses.first()}"
        )

        val mathResponse = allResponses.find { it.contains("2") || it.contains("4") || it.contains("math", ignoreCase = true) }
        val creativeResponse = allResponses.find { it.contains("story", ignoreCase = true) || it.contains("once", ignoreCase = true) }
        val analyticalResponse = allResponses.find { it.contains("advantage", ignoreCase = true) || it.contains("benefit", ignoreCase = true) }
        
        val topicSpecificCount = listOfNotNull(mathResponse, creativeResponse, analyticalResponse).size
        assertTrue(
            topicSpecificCount >= 1,
            "FAILURE: No topic-specific responses found - likely generic/template responses"
        )
    }

    private fun verifyFullDataFlowIntegrity(testResults: List<TestSuiteResult>) {
        testResults.forEach { suiteResult ->
            assertTrue(suiteResult.total > 0, "FAILURE: No tests executed")
            assertTrue(suiteResult.executionTimeMs > 0, "FAILURE: No execution time recorded")
            
            suiteResult.results.forEach { testResult ->
                assertNotNull(testResult.testCase, "FAILURE: TestCase is null")
                assertNotNull(testResult.evaluationResult, "FAILURE: EvaluationResult is null")
                assertNotNull(testResult.llmResponse, "FAILURE: LlmResponse is null")
                
                val metadata = testResult.llmResponse!!.metadata
                assertEquals(
                    "openrouter",
                    metadata["provider"],
                    "FAILURE: Provider metadata incorrect"
                )
                
                assertTrue(
                    testResult.evaluationResult.score >= 0.0 && testResult.evaluationResult.score <= 1.0,
                    "FAILURE: Invalid evaluation score: ${testResult.evaluationResult.score}"
                )
                
                assertNotNull(
                    testResult.evaluationResult.explanation,
                    "FAILURE: No evaluation explanation"
                )
            }
        }
    }

    private fun createMathPromptConfiguration(): TestConfiguration {
        return TestConfiguration(
            suiteMetadata = TestSuiteMetadata(
                name = "Math Problem Test Suite",
                version = "1.0",
                description = "Tests mathematical reasoning with real LLM"
            ),
            prompts = listOf(
                Prompt(
                    id = "math-prompt",
                    name = "Math Problem",
                    template = "Solve this math problem step by step: {{problem}}",
                    variables = setOf("problem")
                )
            ),
            agents = listOf(
                AgentConfig(
                    name = "math-agent",
                    provider = "openrouter",
                    model = OpenRouterModels.DEEPSEEK_CHAT,
                    systemPrompt = "You are a mathematics tutor. Solve problems step by step.",
                    temperature = 0.3,
                    maxTokens = 200
                )
            ),
            tests = listOf(
                TestCase(
                    promptId = "math-prompt",
                    agentName = "math-agent",
                    variables = mapOf("problem" to "What is 15 + 27?"),
                    expected = "42",
                    evaluatorType = "contains"
                )
            )
        )
    }

    private fun createCreativePromptConfiguration(): TestConfiguration {
        return TestConfiguration(
            suiteMetadata = TestSuiteMetadata(
                name = "Creative Writing Test Suite",
                version = "1.0",
                description = "Tests creative writing with real LLM"
            ),
            prompts = listOf(
                Prompt(
                    id = "creative-prompt",
                    name = "Story Prompt",
                    template = "Write a short story about {{topic}} in exactly 2 sentences.",
                    variables = setOf("topic")
                )
            ),
            agents = listOf(
                AgentConfig(
                    name = "creative-agent",
                    provider = "openrouter",
                    model = OpenRouterModels.DEEPSEEK_CHAT,
                    systemPrompt = "You are a creative writer. Write engaging, original stories.",
                    temperature = 0.8,
                    maxTokens = 150
                )
            ),
            tests = listOf(
                TestCase(
                    promptId = "creative-prompt",
                    agentName = "creative-agent",
                    variables = mapOf("topic" to "a robot learning to paint"),
                    expected = "story",
                    evaluatorType = "contains"
                )
            )
        )
    }

    private fun createAnalyticalPromptConfiguration(): TestConfiguration {
        return TestConfiguration(
            suiteMetadata = TestSuiteMetadata(
                name = "Analytical Reasoning Test Suite",
                version = "1.0",
                description = "Tests analytical reasoning with real LLM"
            ),
            prompts = listOf(
                Prompt(
                    id = "analytical-prompt",
                    name = "Analysis Prompt",
                    template = "Analyze the advantages and disadvantages of {{topic}}. Provide 2 points for each.",
                    variables = setOf("topic")
                )
            ),
            agents = listOf(
                AgentConfig(
                    name = "analytical-agent",
                    provider = "openrouter",
                    model = OpenRouterModels.DEEPSEEK_CHAT,
                    systemPrompt = "You are an analytical expert. Provide balanced, thoughtful analysis.",
                    temperature = 0.5,
                    maxTokens = 300
                )
            ),
            tests = listOf(
                TestCase(
                    promptId = "analytical-prompt",
                    agentName = "analytical-agent",
                    variables = mapOf("topic" to "remote work"),
                    expected = "advantage",
                    evaluatorType = "contains"
                )
            )
        )
    }

    private fun createInMemoryConfigRepository(config: TestConfiguration): TestConfigurationRepository {
        return object : TestConfigurationRepository {
            override suspend fun loadConfiguration(source: String): TestConfiguration = config
            override suspend fun validateConfiguration(source: String): List<String> = emptyList()
        }
    }
}