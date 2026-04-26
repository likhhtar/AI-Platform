package ru.yandex.aiplatform.application.usecase

import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import ru.yandex.diploma.aiplatform.application.usecase.*
import ru.yandex.diploma.aiplatform.domain.model.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExperimentRunnerTest {

    private val mockRunTestSuiteUseCase = mockk<RunTestSuiteUseCase>()
    private val experimentRunner = ExperimentRunner(mockRunTestSuiteUseCase)

    @Test
    fun `должен успешно выполнить эксперимент с одним агентом`() = runBlocking {
        val agentConfig = AgentConfig.create(
            name = "test-agent",
            systemPrompt = "Ты тестовый ассистент",
            model = "gpt-4",
            temperature = 0.7
        )
        
        val mockTestResult = TestResult(
            testCase = TestCase(
                promptId = "test-prompt",
                agentName = "test-agent",
                variables = mapOf("input" to "test"),
                expected = "expected",
                evaluatorType = "exact"
            ),
            success = true,
            evaluationResult = EvaluationResult(
                passed = true,
                score = 1.0,
                explanation = "Тест пройден"
            ),
            llmResponse = LlmResponse(
                content = "Тестовый ответ",
                tokensUsed = 15,
                model = "gpt-4"
            ),
            executionTimeMs = 1000,
            error = null
        )
        
        val mockSuiteResult = TestSuiteResult.create(
            results = listOf(mockTestResult),
            executionTimeMs = 1000
        )
        
        coEvery { mockRunTestSuiteUseCase.execute(any(), any()) } returns mockSuiteResult
        
        val result = experimentRunner.runExperiment("test-config.yaml", agentConfig)
        
        assertEquals(agentConfig, result.agentConfig)
        assertEquals(1, result.runs.size)
        
        val run = result.runs.first()
        assertEquals("test-agent", run.agentName)
        assertEquals("gpt-4", run.model)
        assertEquals(0.7, run.temperature)
        assertTrue(run.success)
        assertEquals(mockSuiteResult, run.result)
    }

    @Test
    fun `должен корректно обрабатывать ошибки при выполнении эксперимента`() = runBlocking {
        val agentConfig = AgentConfig.create(
            name = "failing-agent",
            systemPrompt = "Ты ассистент, который падает",
            model = "gpt-3.5-turbo",
            temperature = 0.5
        )
        
        val errorMessage = "Ошибка выполнения теста"
        coEvery { mockRunTestSuiteUseCase.execute(any(), any()) } throws RuntimeException(errorMessage)
        
        val result = experimentRunner.runExperiment("failing-config.yaml", agentConfig)
        
        assertEquals(1, result.runs.size)
        
        val run = result.runs.first()
        assertEquals("failing-agent", run.agentName)
        assertEquals("gpt-3.5-turbo", run.model)
        assertEquals(0.5, run.temperature)
        assertFalse(run.success)
        assertEquals(errorMessage, run.error)
        assertEquals(null, run.result)
    }

    @Test
    fun `должен выполнить мульти-агентный эксперимент параллельно`() = runBlocking {
        val agentConfigs = listOf(
            AgentConfig.create(
                name = "agent-1",
                systemPrompt = "Первый агент",
                model = "gpt-4",
                temperature = 0.3
            ),
            AgentConfig.create(
                name = "agent-2",
                systemPrompt = "Второй агент",
                model = "gpt-3.5-turbo",
                temperature = 0.8
            )
        )
        
        val mockTestResult1 = createMockTestResult("agent-1", true, 0.9)
        val mockTestResult2 = createMockTestResult("agent-2", true, 0.7)
        
        val mockSuiteResult1 = TestSuiteResult.create(listOf(mockTestResult1), 800)
        val mockSuiteResult2 = TestSuiteResult.create(listOf(mockTestResult2), 1200)
        
        coEvery { mockRunTestSuiteUseCase.execute(any(), any()) } returnsMany listOf(mockSuiteResult1, mockSuiteResult2)
        
        val result = experimentRunner.runMultiAgentExperiment("multi-config.yaml", agentConfigs)
        
        assertEquals(2, result.runs.size)
        
        val run1 = result.runs.find { it.agentName == "agent-1" }!!
        assertEquals("gpt-4", run1.model)
        assertEquals(0.3, run1.temperature)
        assertTrue(run1.success)
        
        val run2 = result.runs.find { it.agentName == "agent-2" }!!
        assertEquals("gpt-3.5-turbo", run2.model)
        assertEquals(0.8, run2.temperature)
        assertTrue(run2.success)
        
        coVerify(exactly = 2) { mockRunTestSuiteUseCase.execute(any(), any()) }
    }

    @Test
    fun `должен корректно вычислять метрики эксперимента`() = runBlocking {
        val agentConfigs = listOf(
            AgentConfig.create("successful-agent", "Успешный агент"),
            AgentConfig.create("failing-agent", "Неуспешный агент")
        )
        
        val successfulResult = createMockTestResult("successful-agent", true, 0.8)
        val mockSuiteResult = TestSuiteResult.create(listOf(successfulResult), 1000)
        
        coEvery { mockRunTestSuiteUseCase.execute(any(), any()) } returnsMany listOf(mockSuiteResult) andThenThrows RuntimeException("Ошибка")
        
        val result = experimentRunner.runMultiAgentExperiment("mixed-config.yaml", agentConfigs)
        
        assertEquals(2, result.metrics.totalRuns)
        assertEquals(1, result.metrics.successfulRuns)
        assertEquals(1, result.metrics.failedRuns)
        assertEquals(1000.0, result.metrics.averageLatency)
        assertEquals(0.8, result.metrics.averageScore)
    }

    @Test
    fun `должен использовать модель и температуру из конфигурации агента`() = runBlocking {
        val specificModel = "claude-3.5-sonnet"
        val specificTemperature = 0.2
        
        val agentConfig = AgentConfig.create(
            name = "specific-agent",
            systemPrompt = "Агент с конкретными параметрами",
            model = specificModel,
            temperature = specificTemperature
        )
        
        val mockTestResult = createMockTestResult("specific-agent", true, 1.0)
        val mockSuiteResult = TestSuiteResult.create(listOf(mockTestResult), 500)
        
        coEvery { mockRunTestSuiteUseCase.execute(any(), any()) } returns mockSuiteResult
        
        val result = experimentRunner.runExperiment("specific-config.yaml", agentConfig)
        
        val run = result.runs.first()
        assertEquals(specificModel, run.model)
        assertEquals(specificTemperature, run.temperature)
        
        coVerify {
            mockRunTestSuiteUseCase.execute(
                "specific-config.yaml",
                match { executionConfig ->
                    executionConfig.maxParallelism == agentConfig.maxParallelism &&
                    executionConfig.testTimeout == agentConfig.testTimeout &&
                    executionConfig.enableParallelExecution == agentConfig.enableParallelExecution
                }
            )
        }
    }

    private fun createMockTestResult(agentName: String, success: Boolean, score: Double): TestResult {
        return TestResult(
            testCase = TestCase(
                promptId = "test-prompt",
                agentName = agentName,
                variables = mapOf("test" to "value"),
                expected = "expected",
                evaluatorType = "exact"
            ),
            success = success,
            evaluationResult = EvaluationResult(
                passed = success,
                score = score,
                explanation = if (success) "Успешно" else "Неуспешно"
            ),
            llmResponse = if (success) LlmResponse(
                content = "Ответ от $agentName",
                tokensUsed = 20,
                model = "test-model"
            ) else null,
            executionTimeMs = 1000,
            error = if (!success) "Ошибка теста" else null
        )
    }
}