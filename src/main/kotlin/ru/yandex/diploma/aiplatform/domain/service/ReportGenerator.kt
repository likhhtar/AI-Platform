package ru.yandex.diploma.aiplatform.domain.service

import ru.yandex.diploma.aiplatform.application.usecase.TestSuiteResult
import java.io.File

interface ReportGenerator {
    fun generate(result: TestSuiteResult, testSuiteName: String? = null): File
}