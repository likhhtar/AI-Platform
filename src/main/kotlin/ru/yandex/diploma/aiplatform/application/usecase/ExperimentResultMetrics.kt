package ru.yandex.diploma.aiplatform.application.usecase

fun ExperimentResult.safePassRate(): Double {
    if (metrics.totalRuns <= 0) return 0.0
    return metrics.successfulRuns.toDouble() / metrics.totalRuns
}

fun ExperimentResult.safeTestPassRate(): Double {
    val successfulRuns = runs.filter { it.success && it.result != null }
    if (successfulRuns.isEmpty()) return 0.0
    var passed = 0
    var total = 0
    for (run in successfulRuns) {
        val suiteResults = run.result!!.results
        total += suiteResults.size
        passed += suiteResults.count { it.evaluationResult.passed }
    }
    if (total == 0) return 0.0
    return passed.toDouble() / total
}
