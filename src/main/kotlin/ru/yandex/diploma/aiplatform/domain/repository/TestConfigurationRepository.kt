package ru.yandex.diploma.aiplatform.domain.repository

import ru.yandex.diploma.aiplatform.domain.model.*

interface TestConfigurationRepository {
    suspend fun loadConfiguration(source: String): TestConfiguration
    
    suspend fun validateConfiguration(source: String): List<String>
}

data class TestConfiguration(
    val agents: List<AgentConfig>,
    val prompts: List<Prompt>,
    val tests: List<TestCase>,
    val metadata: Map<String, Any> = emptyMap(),
    val suiteMetadata: TestSuiteMetadata = TestSuiteMetadata(),
    val executionConfig: ExecutionConfig = ExecutionConfig(),
    val optimizationConfig: OptimizationConfig? = null
) {
    fun getAgent(name: String): AgentConfig? = agents.find { it.name == name }
    
    fun getPrompt(id: String): Prompt? = prompts.find { it.id == id }
    
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        
        tests.forEach { test ->
            if (getPrompt(test.promptId) == null) {
                errors.add("Test references unknown prompt: ${test.promptId}")
            }
            test.getAllAgentNames().forEach { agentName ->
                if (getAgent(agentName) == null) {
                    errors.add("Test references unknown agent: $agentName")
                }
            }
        }
        
        val agentNames = agents.map { it.name }
        val duplicateAgents = agentNames.groupBy { it }.filter { it.value.size > 1 }.keys
        duplicateAgents.forEach { name ->
            errors.add("Duplicate agent name: $name")
        }
        
        val promptIds = prompts.map { it.id }
        val duplicatePrompts = promptIds.groupBy { it }.filter { it.value.size > 1 }.keys
        duplicatePrompts.forEach { id ->
            errors.add("Duplicate prompt ID: $id")
        }

        errors.addAll(ExecutionModeRules.validate(this))
        
        return errors
    }
}

class ConfigurationLoadException(
    message: String,
    cause: Throwable? = null,
    val source: String? = null
) : Exception(message, cause)