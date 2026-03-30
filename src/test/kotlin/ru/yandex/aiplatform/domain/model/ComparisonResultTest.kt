package ru.yandex.aiplatform.domain.model

import org.junit.jupiter.api.Test
import ru.yandex.diploma.aiplatform.domain.model.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ComparisonResultTest {

    @Test
    fun `должен корректно определять успешность теста`() {
        val testCase = createTestCase()
        val agentResults = mapOf(
            "agent1" to createSingleAgentResult("agent1", true, 0.8),
            "agent2" to createSingleAgentResult("agent2", false, 0.2)
        )
        
        val result = ComparisonResult(testCase, agentResults, 1000)
        
        assertTrue(result.success)
        assertEquals("agent1", result.bestPerformingAgent)
    }

    @Test
    fun `должен корректно обрабатывать случай когда все агенты неуспешны`() {
        val testCase = createTestCase()
        val agentResults = mapOf(
            "agent1" to createSingleAgentResult("agent1", false, 0.2),
            "agent2" to createSingleAgentResult("agent2", false, 0.1)
        )
        
        val result = ComparisonResult(testCase, agentResults, 1000)
        
        assertFalse(result.success)
        assertEquals(null, result.bestPerformingAgent)
    }

    @Test
    fun `должен выбирать лучшего агента по наивысшему скору`() {
        val testCase = createTestCase()
        val agentResults = mapOf(
            "agent1" to createSingleAgentResult("agent1", true, 0.7),
            "agent2" to createSingleAgentResult("agent2", true, 0.9),
            "agent3" to createSingleAgentResult("agent3", true, 0.6)
        )
        
        val result = ComparisonResult(testCase, agentResults, 1500)
        
        assertTrue(result.success)
        assertEquals("agent2", result.bestPerformingAgent)
    }

    @Test
    fun `должен корректно работать с одним агентом`() {
        val testCase = createTestCase()
        val agentResults = mapOf(
            "single-agent" to createSingleAgentResult("single-agent", true, 0.95)
        )
        
        val result = ComparisonResult(testCase, agentResults, 800)
        
        assertTrue(result.success)
        assertEquals("single-agent", result.bestPerformingAgent)
        assertEquals(1, result.agentResults.size)
        assertEquals(800, result.executionTimeMs)
    }

    @Test
    fun `должен корректно обрабатывать смешанные результаты`() {
        val testCase = createTestCase()
        val agentResults = mapOf(
            "successful-agent" to createSingleAgentResult("successful-agent", true, 0.8),
            "failed-agent" to createSingleAgentResult("failed-agent", false, 0.0, "Ошибка выполнения"),
            "another-successful" to createSingleAgentResult("another-successful", true, 0.6)
        )
        
        val result = ComparisonResult(testCase, agentResults, 2000)
        
        assertTrue(result.success)
        assertEquals("successful-agent", result.bestPerformingAgent)
        
        val successfulResult = result.agentResults["successful-agent"]!!
        assertTrue(successfulResult.success)
        assertEquals(0.8, successfulResult.evaluationResult.score)
        
        val failedResult = result.agentResults["failed-agent"]!!
        assertFalse(failedResult.success)
        assertNotNull(failedResult.error)
    }

    private fun createTestCase(): TestCase {
        return TestCase(
            promptId = "test-prompt",
            agentName = "test-agent",
            variables = mapOf("input" to "test input"),
            expected = "expected output",
            evaluatorType = "exact"
        )
    }

    private fun createSingleAgentResult(
        agentName: String, 
        success: Boolean, 
        score: Double,
        error: String? = null
    ): SingleAgentResult {
        return SingleAgentResult(
            agentName = agentName,
            success = success,
            evaluationResult = EvaluationResult(
                passed = success,
                score = score,
                explanation = if (success) "Тест пройден" else "Тест не пройден"
            ),
            llmResponse = if (success) LlmResponse(
                content = "Ответ от $agentName",
                tokensUsed = 20,
                model = "test-model"
            ) else null,
            executionTimeMs = 1000,
            error = error
        )
    }
}