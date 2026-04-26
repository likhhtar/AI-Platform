package ru.yandex.diploma.aiplatform.infrastructure.evaluator

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExactMatchEvaluatorTest {

    private val evaluator = ExactMatchEvaluator()

    @Test
    fun `should return correct evaluator type`() {
        assertEquals("exact", evaluator.evaluatorType)
    }

    @Test
    fun `should pass when output exactly matches expected`() {
        val output = "Hello World"
        val expected = "Hello World"

        val result = evaluator.evaluate(output, expected)

        assertTrue(result.passed)
        assertEquals(1.0, result.score)
        assertEquals("Output exactly matches expected result", result.explanation)
    }

    @Test
    fun `should fail when output does not match expected`() {
        val output = "Hello World"
        val expected = "Goodbye World"

        val result = evaluator.evaluate(output, expected)

        assertFalse(result.passed)
        assertEquals(0.0, result.score)
        assertTrue(result.explanation.contains("Output does not match expected result"))
    }

    @Test
    fun `should handle case sensitivity correctly`() {
        val output = "Hello World"
        val expected = "hello world"

        val caseSensitiveResult = evaluator.evaluate(output, expected)

        assertFalse(caseSensitiveResult.passed)

        val caseInsensitiveResult = evaluator.evaluate(
            output, 
            expected, 
            mapOf("caseSensitive" to false)
        )

        assertTrue(caseInsensitiveResult.passed)
    }

    @Test
    fun `should handle whitespace trimming correctly`() {
        val output = "  Hello World  "
        val expected = "Hello World"

        val trimmedResult = evaluator.evaluate(output, expected)

        assertTrue(trimmedResult.passed)

        val untrimmedResult = evaluator.evaluate(
            output, 
            expected, 
            mapOf("trimWhitespace" to false)
        )

        assertFalse(untrimmedResult.passed)
    }

    @Test
    fun `should handle empty strings correctly`() {
        val output = ""
        val expected = ""

        val result = evaluator.evaluate(output, expected)

        assertTrue(result.passed)
        assertEquals(1.0, result.score)
    }
}