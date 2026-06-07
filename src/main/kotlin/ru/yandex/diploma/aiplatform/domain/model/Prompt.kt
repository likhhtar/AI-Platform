package ru.yandex.diploma.aiplatform.domain.model

data class Prompt(
    val id: String,
    val name: String,
    val template: String,
    val variables: Set<String> = emptySet()
) {
    fun render(variableValues: Map<String, String>): String {
        var rendered = template
        variableValues.forEach { (key, value) ->
            rendered = rendered.replace("{{$key}}", value)
        }
        return rendered
    }

    fun validateVariables(variableValues: Map<String, String>): List<String> {
        val missingVariables = variables - variableValues.keys
        return missingVariables.toList()
    }

    fun orphanVariableKeys(variableValues: Map<String, String>): Set<String> =
        variableValues.keys - variables
}