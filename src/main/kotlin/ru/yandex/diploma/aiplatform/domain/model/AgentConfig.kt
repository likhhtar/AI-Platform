package ru.yandex.diploma.aiplatform.domain.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

data class AgentConfig(
    val name: String,
    val provider: String = "openai",
    val systemPrompt: String,
    val model: String? = null,
    val temperature: Double = 0.7, // степень случайности
    val maxTokens: Int? = null, // максимальная длина ответа
    val topP: Double? = null, // альтернативный способ контроля случайности
    val frequencyPenalty: Double? = null, // наказывает модель за повторение слов
    val presencePenalty: Double? = null, // стимулирует вводить новые слова
    val additionalParameters: Map<String, Any> = emptyMap(),
    
    val maxParallelism: Int = 4,
    val testTimeout: Duration = 5.minutes,
    val enableParallelExecution: Boolean = true
) {
    init {
        require(name.isNotBlank()) { "Agent name cannot be blank" }
        require(provider.isNotBlank()) { "Provider cannot be blank" }
        require(temperature >= 0.0 && temperature <= 2.0) { "Temperature must be between 0.0 and 2.0" }
        maxTokens?.let { require(it > 0) { "Max tokens must be positive" } }
        topP?.let { require(it >= 0.0 && it <= 1.0) { "Top P must be between 0.0 and 1.0" } }
        frequencyPenalty?.let { require(it >= -2.0 && it <= 2.0) { "Frequency penalty must be between -2.0 and 2.0" } }
        presencePenalty?.let { require(it >= -2.0 && it <= 2.0) { "Presence penalty must be between -2.0 and 2.0" } }
        require(maxParallelism > 0) { "Max parallelism must be positive" }
        require(testTimeout.isPositive()) { "Test timeout must be positive" }
    }
    
    fun toAgent(): AgentConfig {
        return this
    }
    
    fun toExecutionConfig(): ExecutionConfig {
        return ExecutionConfig(
            maxParallelism = maxParallelism,
            testTimeout = testTimeout,
            enableParallelExecution = enableParallelExecution
        )
    }
    
    companion object {
        fun from(agentConfig: AgentConfig, executionConfig: ExecutionConfig = ExecutionConfig()): AgentConfig {
            return AgentConfig(
                name = agentConfig.name,
                provider = agentConfig.provider,
                systemPrompt = agentConfig.systemPrompt,
                model = agentConfig.model,
                temperature = agentConfig.temperature,
                maxTokens = agentConfig.maxTokens,
                topP = agentConfig.topP,
                frequencyPenalty = agentConfig.frequencyPenalty,
                presencePenalty = agentConfig.presencePenalty,
                additionalParameters = agentConfig.additionalParameters,
                maxParallelism = executionConfig.maxParallelism,
                testTimeout = executionConfig.testTimeout,
                enableParallelExecution = executionConfig.enableParallelExecution
            )
        }
        
        fun create(
            name: String,
            systemPrompt: String,
            provider: String? = null,
            model: String? = null,
            temperature: Double = 0.7,
            maxTokens: Int? = null,
            topP: Double? = null,
            frequencyPenalty: Double? = null,
            presencePenalty: Double? = null,
            additionalParameters: Map<String, Any> = emptyMap(),
            maxParallelism: Int = 4,
            testTimeout: Duration = 5.minutes,
            enableParallelExecution: Boolean = true
        ): AgentConfig {
            return AgentConfig(
                name = name,
                provider = provider ?: "openai",
                systemPrompt = systemPrompt,
                model = model,
                temperature = temperature,
                maxTokens = maxTokens,
                topP = topP,
                frequencyPenalty = frequencyPenalty,
                presencePenalty = presencePenalty,
                additionalParameters = additionalParameters,
                maxParallelism = maxParallelism,
                testTimeout = testTimeout,
                enableParallelExecution = enableParallelExecution
            )
        }
    }
}