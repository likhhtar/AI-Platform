package ru.yandex.diploma.aiplatform.domain.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PromptTest {

    @Test
    fun `should render prompt with variables correctly`() {
        val prompt = Prompt(
            id = "test-prompt",
            name = "Test Prompt",
            template = "Translate {{text}} to {{language}}",
            variables = setOf("text", "language")
        )
        val variables = mapOf("text" to "Hello", "language" to "French")

        val rendered = prompt.render(variables)

        assertEquals("Translate Hello to French", rendered)
    }

    @Test
    fun `should validate variables correctly`() {
        val prompt = Prompt(
            id = "test-prompt",
            name = "Test Prompt",
            template = "Translate {{text}} to {{language}}",
            variables = setOf("text", "language")
        )

        val noMissing = prompt.validateVariables(mapOf("text" to "Hello", "language" to "French"))
        
        assertTrue(noMissing.isEmpty())

        val missing = prompt.validateVariables(mapOf("text" to "Hello"))
        
        assertEquals(listOf("language"), missing)
    }

    @Test
    fun `should handle empty variables`() {
        val prompt = Prompt(
            id = "test-prompt",
            name = "Test Prompt",
            template = "Simple prompt without variables",
            variables = emptySet()
        )

        val rendered = prompt.render(emptyMap())
        val missing = prompt.validateVariables(emptyMap())

        assertEquals("Simple prompt without variables", rendered)
        assertTrue(missing.isEmpty())
    }

    @Test
    fun `should handle partial variable replacement`() {
        val prompt = Prompt(
            id = "test-prompt",
            name = "Test Prompt",
            template = "Hello {{name}}, welcome to {{place}}",
            variables = setOf("name", "place")
        )
        
        val variables = mapOf("name" to "John")
        val rendered = prompt.render(variables)

        assertEquals("Hello John, welcome to {{place}}", rendered)
    }
}