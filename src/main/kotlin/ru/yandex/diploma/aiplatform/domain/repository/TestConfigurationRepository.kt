package ru.yandex.diploma.aiplatform.domain.repository

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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
    val optimizationConfig: OptimizationConfig? = null,
    val regressionConfiguration: RegressionConfiguration? = null
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

    fun semanticFingerprint(): String {
        val md = MessageDigest.getInstance("SHA-256")
        val sb = StringBuilder(512)
        sb.append("semanticFp:v1\n")
        for (p in prompts.sortedBy { it.id }) {
            sb.append("P:").append(p.id).append('\u001e')
            sb.append("N:").append(p.name).append('\u001e')
            sb.append("T:").append(p.template).append('\u001e')
            sb.append("V:")
            for (vk in p.variables.sorted()) {
                sb.append(vk).append(';')
            }
            sb.append('\n')
        }
        val oc = optimizationConfig
        sb.append("OPT:")
        if (oc == null || !oc.enabled) {
            sb.append("disabled\n")
        } else {
            sb.append("on|")
                .append(oc.mode.name).append('|')
                .append(oc.type.name).append('|')
                .append(oc.iterations).append('|')
                .append(oc.plateauScoreEpsilon).append('|')
                .append(oc.rollbackMedianThreshold).append('\n')
        }
        val digest = md.digest(sb.toString().toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { b -> "%02x".format(b) }
    }
}

class ConfigurationLoadException(
    message: String,
    cause: Throwable? = null,
    val source: String? = null
) : Exception(message, cause)