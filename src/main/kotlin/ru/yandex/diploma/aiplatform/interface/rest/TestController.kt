package ru.yandex.diploma.aiplatform.`interface`.rest

import ru.yandex.diploma.aiplatform.application.usecase.*
import ru.yandex.diploma.aiplatform.domain.model.BaselinePersistenceMode
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import jakarta.validation.Valid

@RestController
@RequestMapping("/api/tests")
class TestController(
    private val baselineExperimentRunner: BaselineExperimentRunner
) {
    @PostMapping("/run")
    suspend fun runTests(@Valid @RequestBody request: RunTestsRequest): ResponseEntity<RunTestsResponse> {
        return try {
            val enhancedResult = baselineExperimentRunner.executeWithBaseline(
                configurationSource = request.configuration,
                baselineModeOverride = request.baselineMode ?: BaselinePersistenceMode.ASSERT
            )
            val result = enhancedResult.testRun
            
            ResponseEntity.ok(
                RunTestsResponse(
                    success = true,
                    total = result.results.size,
                    passed = result.results.count { it.success },
                    failed = result.results.count { !it.success },
                    successRate = result.successRate,
                    executionTimeMs = result.executionTimeMs,
                    reportPath = enhancedResult.reportFile?.path,
                    runId = enhancedResult.runId,
                    regressionStatus = enhancedResult.regressionAnalysis.summary.overallStatus.name,
                    regressionCount = enhancedResult.regressionAnalysis.summary.testsWithRegressions,
                    results = result.results.map { testResult ->
                        TestResultDto(
                            promptId = testResult.testCase.promptId,
                            agentName = testResult.testCase.getAllAgentNames().joinToString(","),
                            variables = testResult.testCase.variables,
                            expected = testResult.testCase.expected,
                            actual = testResult.llmResponse?.content,
                            passed = testResult.success,
                            score = testResult.evaluationResult.score,
                            explanation = testResult.evaluationResult.explanation,
                            executionTimeMs = testResult.executionTimeMs,
                            error = testResult.error,
                            metrics = testResult.metrics.mapValues { it.value.score }
                        )
                    }
                )
            )
        } catch (e: TestSuiteException) {
            ResponseEntity.badRequest().body(
                RunTestsResponse(
                    success = false,
                    error = e.message,
                    total = 0,
                    passed = 0,
                    failed = 0,
                    successRate = 0.0,
                    executionTimeMs = 0,
                    results = emptyList()
                )
            )
        } catch (e: Exception) {
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                RunTestsResponse(
                    success = false,
                    error = "Internal server error: ${e.message}",
                    total = 0,
                    passed = 0,
                    failed = 0,
                    successRate = 0.0,
                    executionTimeMs = 0,
                    results = emptyList()
                )
            )
        }
    }
    
    @PostMapping("/run/file")
    suspend fun runTestsFromFile(@RequestParam("file") file: MultipartFile): ResponseEntity<RunTestsResponse> {
        return try {
            if (file.isEmpty) {
                return ResponseEntity.badRequest().body(
                    RunTestsResponse(
                        success = false,
                        error = "File is empty",
                        total = 0,
                        passed = 0,
                        failed = 0,
                        successRate = 0.0,
                        executionTimeMs = 0,
                        results = emptyList()
                    )
                )
            }
            
            val yamlContent = String(file.bytes)
            val enhancedResult = baselineExperimentRunner.executeWithBaseline(yamlContent)
            val result = enhancedResult.testRun
            
            ResponseEntity.ok(
                RunTestsResponse(
                    success = true,
                    total = result.results.size,
                    passed = result.results.count { it.success },
                    failed = result.results.count { !it.success },
                    successRate = result.successRate,
                    executionTimeMs = result.executionTimeMs,
                    reportPath = enhancedResult.reportFile?.path,
                    runId = enhancedResult.runId,
                    regressionStatus = enhancedResult.regressionAnalysis.summary.overallStatus.name,
                    regressionCount = enhancedResult.regressionAnalysis.summary.testsWithRegressions,
                    results = result.results.map { testResult ->
                        TestResultDto(
                            promptId = testResult.testCase.promptId,
                            agentName = testResult.testCase.getAllAgentNames().joinToString(","),
                            variables = testResult.testCase.variables,
                            expected = testResult.testCase.expected,
                            actual = testResult.llmResponse?.content,
                            passed = testResult.success,
                            score = testResult.evaluationResult.score,
                            explanation = testResult.evaluationResult.explanation,
                            executionTimeMs = testResult.executionTimeMs,
                            error = testResult.error,
                            metrics = testResult.metrics.mapValues { it.value.score }
                        )
                    }
                )
            )
        } catch (e: TestSuiteException) {
            ResponseEntity.badRequest().body(
                RunTestsResponse(
                    success = false,
                    error = e.message,
                    total = 0,
                    passed = 0,
                    failed = 0,
                    successRate = 0.0,
                    executionTimeMs = 0,
                    results = emptyList()
                )
            )
        } catch (e: Exception) {
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                RunTestsResponse(
                    success = false,
                    error = "File processing error: ${e.message}",
                    total = 0,
                    passed = 0,
                    failed = 0,
                    successRate = 0.0,
                    executionTimeMs = 0,
                    results = emptyList()
                )
            )
        }
    }
}