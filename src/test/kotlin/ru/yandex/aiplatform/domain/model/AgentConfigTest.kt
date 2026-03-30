package ru.yandex.aiplatform.domain.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ru.yandex.diploma.aiplatform.domain.model.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class AgentConfigTest {

    @Test
    fun `должен создать валидную конфигурацию агента с минимальными параметрами`() {
        val config = AgentConfig(
            name = "test-agent",
            systemPrompt = "Ты тестовый ассистент"
        )
        
        assertEquals("test-agent", config.name)
        assertEquals("openai", config.provider)
        assertEquals("Ты тестовый ассистент", config.systemPrompt)
        assertEquals(0.7, config.temperature)
        assertEquals(4, config.maxParallelism)
        assertEquals(5.minutes, config.testTimeout)
        assertTrue(config.enableParallelExecution)
    }

    @Test
    fun `должен создать конфигурацию со всеми параметрами`() {
        val config = AgentConfig(
            name = "advanced-agent",
            provider = "anthropic",
            systemPrompt = "Ты продвинутый ассистент",
            model = "claude-3.5-sonnet",
            temperature = 0.3,
            maxTokens = 1000,
            topP = 0.9,
            frequencyPenalty = 0.5,
            presencePenalty = -0.2,
            additionalParameters = mapOf("custom" to "value"),
            maxParallelism = 8,
            testTimeout = 10.minutes,
            enableParallelExecution = false
        )
        
        assertEquals("advanced-agent", config.name)
        assertEquals("anthropic", config.provider)
        assertEquals("claude-3.5-sonnet", config.model)
        assertEquals(0.3, config.temperature)
        assertEquals(1000, config.maxTokens)
        assertEquals(0.9, config.topP)
        assertEquals(0.5, config.frequencyPenalty)
        assertEquals(-0.2, config.presencePenalty)
        assertEquals(mapOf("custom" to "value"), config.additionalParameters)
        assertEquals(8, config.maxParallelism)
        assertEquals(10.minutes, config.testTimeout)
        assertEquals(false, config.enableParallelExecution)
    }

    @Test
    fun `должен выбрасывать исключение при пустом имени агента`() {
        val exception = assertThrows<IllegalArgumentException> {
            AgentConfig(
                name = "",
                systemPrompt = "Промпт"
            )
        }
        
        assertTrue(exception.message!!.contains("Agent name cannot be blank"))
    }

    @Test
    fun `должен выбрасывать исключение при пустом провайдере`() {
        val exception = assertThrows<IllegalArgumentException> {
            AgentConfig(
                name = "test",
                provider = "",
                systemPrompt = "Промпт"
            )
        }
        
        assertTrue(exception.message!!.contains("Provider cannot be blank"))
    }

    @Test
    fun `должен выбрасывать исключение при некорректной температуре`() {
        assertThrows<IllegalArgumentException> {
            AgentConfig(
                name = "test",
                systemPrompt = "Промпт",
                temperature = -0.1
            )
        }
        
        assertThrows<IllegalArgumentException> {
            AgentConfig(
                name = "test",
                systemPrompt = "Промпт",
                temperature = 2.1
            )
        }
    }

    @Test
    fun `должен выбрасывать исключение при некорректном количестве токенов`() {
        assertThrows<IllegalArgumentException> {
            AgentConfig(
                name = "test",
                systemPrompt = "Промпт",
                maxTokens = 0
            )
        }
    }

    @Test
    fun `должен выбрасывать исключение при некорректном topP`() {
        assertThrows<IllegalArgumentException> {
            AgentConfig(
                name = "test",
                systemPrompt = "Промпт",
                topP = -0.1
            )
        }
        
        assertThrows<IllegalArgumentException> {
            AgentConfig(
                name = "test",
                systemPrompt = "Промпт",
                topP = 1.1
            )
        }
    }

    @Test
    fun `должен выбрасывать исключение при некорректных penalty параметрах`() {
        assertThrows<IllegalArgumentException> {
            AgentConfig(
                name = "test",
                systemPrompt = "Промпт",
                frequencyPenalty = -2.1
            )
        }
        
        assertThrows<IllegalArgumentException> {
            AgentConfig(
                name = "test",
                systemPrompt = "Промпт",
                frequencyPenalty = 2.1
            )
        }
    }

    @Test
    fun `должен выбрасывать исключение при некорректном параллелизме`() {
        assertThrows<IllegalArgumentException> {
            AgentConfig(
                name = "test",
                systemPrompt = "Промпт",
                maxParallelism = 0
            )
        }
    }

    @Test
    fun `должен выбрасывать исключение при некорректном таймауте`() {
        assertThrows<IllegalArgumentException> {
            AgentConfig(
                name = "test",
                systemPrompt = "Промпт",
                testTimeout = 0.seconds
            )
        }
    }

    @Test
    fun `должен корректно преобразовывать в ExecutionConfig`() {
        val config = AgentConfig(
            name = "convert-agent",
            provider = "openai",
            systemPrompt = "Промпт для конвертации",
            model = "gpt-4",
            temperature = 0.5,
            maxTokens = 500,
            topP = 0.8,
            frequencyPenalty = 0.1,
            presencePenalty = 0.2,
            additionalParameters = mapOf("test" to "value"),
            maxParallelism = 3,
            testTimeout = 10.minutes,
            enableParallelExecution = false
        )
        
        val executionConfig = config.toExecutionConfig()
        
        assertEquals(config.maxParallelism, executionConfig.maxParallelism)
        assertEquals(config.testTimeout, executionConfig.testTimeout)
        assertEquals(config.enableParallelExecution, executionConfig.enableParallelExecution)
    }

    @Test
    fun `должен корректно преобразовывать в ExecutionConfig с настройками по умолчанию`() {
        val config = AgentConfig(
            name = "exec-agent",
            systemPrompt = "Промпт",
            maxParallelism = 6,
            testTimeout = 3.minutes,
            enableParallelExecution = false
        )
        
        val executionConfig = config.toExecutionConfig()
        
        assertEquals(config.maxParallelism, executionConfig.maxParallelism)
        assertEquals(config.testTimeout, executionConfig.testTimeout)
        assertEquals(config.enableParallelExecution, executionConfig.enableParallelExecution)
    }

    @Test
    fun `должен создавать AgentConfig из другого AgentConfig и ExecutionConfig`() {
        val sourceAgentConfig = AgentConfig(
            name = "source-agent",
            provider = "anthropic",
            systemPrompt = "Исходный промпт",
            model = "claude-3",
            temperature = 0.4,
            maxTokens = 800
        )
        
        val executionConfig = ExecutionConfig(
            maxParallelism = 2,
            testTimeout = 7.minutes,
            enableParallelExecution = false
        )
        
        val agentConfig = AgentConfig.from(sourceAgentConfig, executionConfig)
        
        assertEquals(sourceAgentConfig.name, agentConfig.name)
        assertEquals(sourceAgentConfig.provider, agentConfig.provider)
        assertEquals(sourceAgentConfig.systemPrompt, agentConfig.systemPrompt)
        assertEquals(sourceAgentConfig.model, agentConfig.model)
        assertEquals(sourceAgentConfig.temperature, agentConfig.temperature)
        assertEquals(sourceAgentConfig.maxTokens, agentConfig.maxTokens)
        assertEquals(executionConfig.maxParallelism, agentConfig.maxParallelism)
        assertEquals(executionConfig.testTimeout, agentConfig.testTimeout)
        assertEquals(executionConfig.enableParallelExecution, agentConfig.enableParallelExecution)
    }

    @Test
    fun `должен создавать AgentConfig через create с дефолтным провайдером`() {
        val config = AgentConfig.create(
            name = "created-agent",
            systemPrompt = "Созданный промпт",
            model = "gpt-3.5-turbo",
            temperature = 0.6
        )
        
        assertEquals("created-agent", config.name)
        assertEquals("openai", config.provider)
        assertEquals("Созданный промпт", config.systemPrompt)
        assertEquals("gpt-3.5-turbo", config.model)
        assertEquals(0.6, config.temperature)
    }

    @Test
    fun `должен создавать AgentConfig через create с указанным провайдером`() {
        val config = AgentConfig.create(
            name = "custom-agent",
            systemPrompt = "Кастомный промпт",
            provider = "gemini",
            model = "gemini-1.5-pro",
            temperature = 0.8,
            maxTokens = 1200
        )
        
        assertEquals("custom-agent", config.name)
        assertEquals("gemini", config.provider)
        assertEquals("Кастомный промпт", config.systemPrompt)
        assertEquals("gemini-1.5-pro", config.model)
        assertEquals(0.8, config.temperature)
        assertEquals(1200, config.maxTokens)
    }

    @Test
    fun `должен принимать граничные значения параметров`() {
        val config = AgentConfig(
            name = "boundary-agent",
            systemPrompt = "Граничный тест",
            temperature = 0.0,
            topP = 1.0,
            frequencyPenalty = -2.0,
            presencePenalty = 2.0,
            maxParallelism = 1,
            testTimeout = 1.seconds
        )
        
        assertEquals(0.0, config.temperature)
        assertEquals(1.0, config.topP)
        assertEquals(-2.0, config.frequencyPenalty)
        assertEquals(2.0, config.presencePenalty)
        assertEquals(1, config.maxParallelism)
        assertEquals(1.seconds, config.testTimeout)
    }
}