package ru.yandex.diploma.aiplatform.domain.model

object TestCaseIdentifiers {
    fun stableTestCaseId(testCase: TestCase): String =
        "${testCase.promptId}:${testCase.getAllAgentNames().sorted().joinToString(",")}:${testCase.variables.toSortedMap()}"
}
