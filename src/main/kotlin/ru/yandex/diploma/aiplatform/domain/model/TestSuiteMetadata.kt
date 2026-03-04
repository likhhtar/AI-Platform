package ru.yandex.diploma.aiplatform.domain.model

data class TestSuiteMetadata(
    val name: String = "Unnamed Test Suite",
    val version: String = "1.0",
    val description: String = "",
    val author: String = "",
    val tags: List<String> = emptyList(),
    val createdAt: String = "",
    val updatedAt: String = "",
    val executionMode: ExecutionMode? = null
)

data class JudgeConfig(
    val agentName: String = "judge-agent",
    val provider: String = "openai", 
    val model: String = "gpt-4",
    val temperature: Double = 0.1,
    val enabled: Boolean = false
)