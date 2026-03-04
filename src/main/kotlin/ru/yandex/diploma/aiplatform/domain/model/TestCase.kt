package ru.yandex.diploma.aiplatform.domain.model

data class TestCase(
    val promptId: String,
    val agentName: String? = null,
    val agentNames: List<String> = emptyList(),
    val variables: Map<String, String>,
    val expected: String,
    val evaluatorType: String,
    val metadata: Map<String, Any> = emptyMap()
) {
    init {
        require(promptId.isNotBlank()) { "Prompt ID cannot be blank" }
        require(evaluatorType.isNotBlank()) { "Evaluator type cannot be blank" }
        
        val hasAgentName = !agentName.isNullOrBlank()
        val hasAgentNames = agentNames.isNotEmpty()
        require(hasAgentName || hasAgentNames) { "Either agentName or agentNames must be specified" }
        require(!(hasAgentName && hasAgentNames)) { "Cannot specify both agentName and agentNames" }
        
        if (hasAgentNames) {
            agentNames.forEach { name ->
                require(name.isNotBlank()) { "Agent names cannot be blank" }
            }
        }
    }
    
    fun getAllAgentNames(): List<String> {
        return when {
            !agentName.isNullOrBlank() -> listOf(agentName)
            agentNames.isNotEmpty() -> agentNames
            else -> emptyList()
        }
    }
    
    fun isMultiAgentTest(): Boolean = agentNames.isNotEmpty()
}