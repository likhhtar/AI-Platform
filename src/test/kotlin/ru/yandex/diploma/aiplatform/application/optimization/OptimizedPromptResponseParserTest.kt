package ru.yandex.diploma.aiplatform.application.optimization

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OptimizedPromptResponseParserTest {

    @Test
    fun `returns null on empty or blank input`() {
        assertNull(OptimizedPromptResponseParser.extractPrompt(""))
        assertNull(OptimizedPromptResponseParser.extractPrompt("   \n\t"))
    }

    @Test
    fun `returns plain text unchanged after trim`() {
        assertEquals(
            "Я — копирайтер Яндекс Директ.",
            OptimizedPromptResponseParser.extractPrompt("  Я — копирайтер Яндекс Директ.  \n"),
        )
    }

    @Test
    fun `strips trailing fenced json metadata block`() {
        val raw =
            """
            Я — копирайтер Яндекс Директ. Создай объявление, только текст, не более 3 предложений.

            ```json
            {"confidence": 0.95, "reasoning": "x", "suggestions": []}
            ```
            """.trimIndent()

        assertEquals(
            "Я — копирайтер Яндекс Директ. Создай объявление, только текст, не более 3 предложений.",
            OptimizedPromptResponseParser.extractPrompt(raw),
        )
    }

    @Test
    fun `strips trailing inline json object that starts with confidence`() {
        val raw =
            """
            Я — копирайтер Яндекс Директ. Создай объявление, только текст, не более 3 предложений.

            {"confidence": 0.95, "reasoning": "...", "suggestions": []}
            """.trimIndent()

        assertEquals(
            "Я — копирайтер Яндекс Директ. Создай объявление, только текст, не более 3 предложений.",
            OptimizedPromptResponseParser.extractPrompt(raw),
        )
    }

    @Test
    fun `strips trailing inline json regardless of leading key name`() {
        val raw = "instruction body\n{\"reasoning\": \"why\"}"
        assertEquals(
            "instruction body",
            OptimizedPromptResponseParser.extractPrompt(raw),
        )
    }

    @Test
    fun `extracts prompt field when whole response is a json object`() {
        val raw =
            """{"prompt": "Я — копирайтер Яндекс Директ. {{topic}}", "confidence": 0.9}"""
        assertEquals(
            "Я — копирайтер Яндекс Директ. {{topic}}",
            OptimizedPromptResponseParser.extractPrompt(raw),
        )
    }

    @Test
    fun `extracts template field when whole response is a json object`() {
        val raw = """{"template": "do thing carefully", "confidence": 0.5}"""
        assertEquals("do thing carefully", OptimizedPromptResponseParser.extractPrompt(raw))
    }

    @Test
    fun `extracts instruction field and unescapes newlines`() {
        val raw = """{"instruction": "step one\nstep two", "extra": 1}"""
        assertEquals("step one\nstep two", OptimizedPromptResponseParser.extractPrompt(raw))
    }

    @Test
    fun `returns null when whole response is json with no known field`() {
        val raw = """{"confidence": 0.7, "reasoning": "boom"}"""
        assertNull(OptimizedPromptResponseParser.extractPrompt(raw))
    }

    @Test
    fun `returns null when placeholder was present in raw but stripped together with json body`() {
        val raw =
            """
            Some unrelated preamble.

            {"prompt": "rewrite {{topic}}", "confidence": 0.9}
            """.trimIndent()

        assertNull(OptimizedPromptResponseParser.extractPrompt(raw))
    }

    @Test
    fun `keeps placeholders that survive in the cleaned prompt body`() {
        val raw =
            """
            Я — копирайтер. Тема: {{topic}}. Сделай объявление.

            ```json
            {"confidence": 0.9, "reasoning": "ok"}
            ```
            """.trimIndent()

        assertEquals(
            "Я — копирайтер. Тема: {{topic}}. Сделай объявление.",
            OptimizedPromptResponseParser.extractPrompt(raw),
        )
    }

    @Test
    fun `case insensitive json fence marker is still stripped`() {
        val raw = "body text\n```JSON\n{\"confidence\":0.8}\n```"
        assertEquals("body text", OptimizedPromptResponseParser.extractPrompt(raw))
    }
}
