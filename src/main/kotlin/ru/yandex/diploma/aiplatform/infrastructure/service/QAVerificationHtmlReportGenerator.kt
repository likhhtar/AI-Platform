package ru.yandex.diploma.aiplatform.infrastructure.service

import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service
import ru.yandex.diploma.aiplatform.application.usecase.ExperimentResult
import ru.yandex.diploma.aiplatform.application.usecase.QAVerdict
import ru.yandex.diploma.aiplatform.application.usecase.TestSuiteResult
import ru.yandex.diploma.aiplatform.domain.model.*
import ru.yandex.diploma.aiplatform.domain.service.ReportGenerator
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Service
@Primary
class QAVerificationHtmlReportGenerator : ReportGenerator {
    
    override fun generate(result: TestSuiteResult, testSuiteName: String?): File {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm"))
        val fileName = "qa-verification-report-$timestamp.html"
        val file = File("reports/$fileName")
        
        val htmlContent = buildStandardHtmlReport(result, testSuiteName)
        file.parentFile?.mkdirs()
        file.writeText(htmlContent)
        return file
    }
    
    fun generateQAVerificationReport(
        optimizationResult: ExtendedOptimizationExperimentResult,
        outputPath: String = "reports/"
    ): File {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm"))
        val fileName = "qa-verification-report-$timestamp.html"
        val file = File(if (outputPath.endsWith("/")) "$outputPath$fileName" else "$outputPath/$fileName")
        
        val htmlContent = buildQAVerificationHtmlReport(optimizationResult)
        
        file.parentFile?.mkdirs()
        file.writeText(htmlContent)
        return file
    }
    
    private fun buildQAVerificationHtmlReport(result: ExtendedOptimizationExperimentResult): String {
        return """
<!DOCTYPE html>
<html lang="ru">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Отчёт QA-верификации — оптимизация промптов</title>
    <style>
        ${getEnhancedStyles()}
    </style>
    <script>
        ${getInteractiveScripts()}
    </script>
</head>
<body>
    <div class="container">
        <header class="header">
            <h1>🔍 Отчёт QA-верификации</h1>
            <div class="subtitle">Анализ и верификация результатов оптимизации промптов</div>
            <div class="timestamp">Сформирован: ${result.timestamp}</div>
            <div class="verification-status ${result.qaVerification.verificationStatus.name.lowercase()}">
                ${getStatusIcon(result.qaVerification.verificationStatus)} ${ruVerificationStatus(result.qaVerification.verificationStatus)}
            </div>
        </header>

        ${buildExecutiveSummary(result)}
        
        ${buildRedFlagsSection(result.qaVerification)}
        
        ${buildQAMetricsSection(result.qaVerification.metrics)}
        
        ${buildPromptComparisonSection(result)}
        
        ${buildCategoryBreakdownSection(result.qaVerification.metrics.categoryBreakdown)}
        
        ${buildOptimizationDetailsSection(result)}
        
        ${buildPerformanceComparisonSection(result)}
        
        ${buildRecommendationsSection(result.qaVerification)}
        
        <footer class="footer">
            <p>Отчёт QA-верификации • AI Platform • ${result.timestamp}</p>
        </footer>
    </div>
</body>
</html>
        """.trimIndent()
    }
    
    private fun buildExecutiveSummary(result: ExtendedOptimizationExperimentResult): String {
        val qa = result.qaVerification
        val metrics = qa.metrics
        
        return """
        <section class="section executive-summary">
            <h2>📊 Краткая сводка</h2>
            <div class="summary-grid">
                <div class="summary-card ${if (qa.isLegitimate) "success" else "danger"}">
                    <div class="card-icon">${if (qa.isLegitimate) "✅" else "❌"}</div>
                    <div class="card-title">Статус верификации</div>
                    <div class="card-value">${if (qa.isLegitimate) "НАДЁЖНО" else "ПОДОЗРИТЕЛЬНО"}</div>
                </div>
                
                <div class="summary-card ${getAccuracyClass(metrics.optimizedAccuracy)}">
                    <div class="card-icon">🎯</div>
                    <div class="card-title">Общая точность</div>
                    <div class="card-value">${String.format("%.1f", metrics.optimizedAccuracy * 100)}%</div>
                    <div class="card-subtitle">базовая: ${String.format("%.1f", metrics.baselineAccuracy * 100)}%</div>
                </div>
                
                <div class="summary-card ${getGeneralizationClass(metrics.generalizationScore)}">
                    <div class="card-icon">🌐</div>
                    <div class="card-title">Обобщение</div>
                    <div class="card-value">${String.format("%.1f", metrics.generalizationScore * 100)}%</div>
                    <div class="card-subtitle">качество вне домена обучения</div>
                </div>
                
                <div class="summary-card ${getRedFlagClass(qa.redFlags.size)}">
                    <div class="card-icon">🚩</div>
                    <div class="card-title">Красные флаги</div>
                    <div class="card-value">${qa.redFlags.size}</div>
                    <div class="card-subtitle">из них критических: ${qa.redFlags.count { it.severity == Severity.CRITICAL }}</div>
                </div>
                
                <div class="summary-card ${getConsistencyClass(metrics.consistencyScore)}">
                    <div class="card-icon">📈</div>
                    <div class="card-title">Согласованность</div>
                    <div class="card-value">${String.format("%.1f", metrics.consistencyScore * 100)}%</div>
                    <div class="card-subtitle">стабильность по категориям тестов</div>
                </div>
                
                <div class="summary-card info">
                    <div class="card-icon">⏱️</div>
                    <div class="card-title">Время выполнения</div>
                    <div class="card-value">${result.executionTimeMs} ms</div>
                    <div class="card-subtitle">полное время обработки</div>
                </div>
            </div>
        </section>
        """
    }
    
    private fun buildRedFlagsSection(qaVerification: QAVerificationResult): String {
        if (qaVerification.redFlags.isEmpty()) {
            return """
            <section class="section red-flags-section">
                <h2>🚩 Анализ красных флагов</h2>
                <div class="no-red-flags">
                    <div class="success-icon">✅</div>
                    <h3>Красные флаги не обнаружены</h3>
                    <p>Оптимизация выглядит обоснованной, подозрительных паттернов не найдено.</p>
                </div>
            </section>
            """
        }
        
        val flagsByCategory = qaVerification.redFlags.groupBy { it.severity }
        
        return """
        <section class="section red-flags-section">
            <h2>🚩 Анализ красных флагов</h2>
            <div class="red-flags-summary">
                <div class="flags-count critical">${flagsByCategory[Severity.CRITICAL]?.size ?: 0} критических</div>
                <div class="flags-count high">${flagsByCategory[Severity.HIGH]?.size ?: 0} высокого уровня</div>
                <div class="flags-count medium">${flagsByCategory[Severity.MEDIUM]?.size ?: 0} среднего</div>
                <div class="flags-count low">${flagsByCategory[Severity.LOW]?.size ?: 0} низкого</div>
            </div>
            
            <div class="red-flags-list">
                ${qaVerification.redFlags.sortedByDescending { it.severity }.map { flag ->
                    """
                    <div class="red-flag-item ${flag.severity.name.lowercase()}" onclick="toggleDetails(this)">
                        <div class="flag-header">
                            <div class="flag-icon">${getSeverityIcon(flag.severity)}</div>
                            <div class="flag-content">
                                <div class="flag-title">${ruRedFlagType(flag.type)}</div>
                                <div class="flag-description">${escapeHtml(flag.description)}</div>
                            </div>
                            <div class="flag-severity">
                                <span class="severity-badge ${flag.severity.name.lowercase()}">${ruSeverity(flag.severity)}</span>
                                <span class="confidence-badge">${String.format("%.0f", flag.confidence * 100)}%</span>
                            </div>
                            <div class="expand-icon">▼</div>
                        </div>
                        <div class="flag-details" style="display: none;">
                            <div class="detail-section">
                                <strong>Доказательства:</strong>
                                <div class="evidence-text">${escapeHtml(flag.evidence)}</div>
                            </div>
                            <div class="detail-section">
                                <strong>Рекомендация:</strong>
                                <div class="recommendation-text">${escapeHtml(flag.recommendation)}</div>
                            </div>
                        </div>
                    </div>
                    """
                }.joinToString("")}
            </div>
        </section>
        """
    }
    
    private fun buildQAMetricsSection(metrics: QAVerificationMetrics): String {
        return """
        <section class="section qa-metrics-section">
            <h2>📊 Метрики QA-верификации</h2>
            
            <div class="metrics-comparison">
                <div class="comparison-chart">
                    <h3>Сравнение по метрикам</h3>
                    <div class="chart-container">
                        <div class="metric-bar">
                            <div class="metric-label">Базовая</div>
                            <div class="bar-container">
                                <div class="bar baseline" style="width: ${metrics.baselineAccuracy * 100}%"></div>
                                <span class="bar-value">${String.format("%.1f", metrics.baselineAccuracy * 100)}%</span>
                            </div>
                        </div>
                        <div class="metric-bar">
                            <div class="metric-label">Оптимизированная</div>
                            <div class="bar-container">
                                <div class="bar optimized" style="width: ${metrics.optimizedAccuracy * 100}%"></div>
                                <span class="bar-value">${String.format("%.1f", metrics.optimizedAccuracy * 100)}%</span>
                            </div>
                        </div>
                        <div class="metric-bar">
                            <div class="metric-label">Перефразы</div>
                            <div class="bar-container">
                                <div class="bar paraphrase" style="width: ${metrics.paraphraseAccuracy * 100}%"></div>
                                <span class="bar-value">${String.format("%.1f", metrics.paraphraseAccuracy * 100)}%</span>
                            </div>
                        </div>
                        <div class="metric-bar">
                            <div class="metric-label">Ловушки</div>
                            <div class="bar-container">
                                <div class="bar trap" style="width: ${metrics.trapAccuracy * 100}%"></div>
                                <span class="bar-value">${String.format("%.1f", metrics.trapAccuracy * 100)}%</span>
                            </div>
                        </div>
                        <div class="metric-bar">
                            <div class="metric-label">Новые домены</div>
                            <div class="bar-container">
                                <div class="bar unseen" style="width: ${metrics.unseenAccuracy * 100}%"></div>
                                <span class="bar-value">${String.format("%.1f", metrics.unseenAccuracy * 100)}%</span>
                            </div>
                        </div>
                    </div>
                </div>
                
                <div class="key-indicators">
                    <h3>Ключевые индикаторы</h3>
                    <div class="indicators-grid">
                        <div class="indicator ${if (metrics.performanceDropOnUnseen < 0.2) "good" else "warning"}">
                            <div class="indicator-icon">${if (metrics.performanceDropOnUnseen < 0.2) "✅" else "⚠️"}</div>
                            <div class="indicator-label">Падение на unseen</div>
                            <div class="indicator-value">${String.format("%.1f", metrics.performanceDropOnUnseen * 100)}%</div>
                        </div>
                        <div class="indicator ${if (metrics.consistencyScore > 0.7) "good" else "warning"}">
                            <div class="indicator-icon">${if (metrics.consistencyScore > 0.7) "✅" else "⚠️"}</div>
                            <div class="indicator-label">Согласованность</div>
                            <div class="indicator-value">${String.format("%.1f", metrics.consistencyScore * 100)}%</div>
                        </div>
                        <div class="indicator ${if (metrics.trapAccuracy < 0.5) "good" else "danger"}">
                            <div class="indicator-icon">${if (metrics.trapAccuracy < 0.5) "✅" else "❌"}</div>
                            <div class="indicator-label">Точность на ловушках</div>
                            <div class="indicator-value">${String.format("%.1f", metrics.trapAccuracy * 100)}%</div>
                        </div>
                        <div class="indicator ${if (metrics.suspiciousPatternCount == 0) "good" else "warning"}">
                            <div class="indicator-icon">${if (metrics.suspiciousPatternCount == 0) "✅" else "⚠️"}</div>
                            <div class="indicator-label">Подозрительные паттерны</div>
                            <div class="indicator-value">${metrics.suspiciousPatternCount}</div>
                        </div>
                    </div>
                </div>
            </div>
        </section>
        """
    }
    
    private fun buildPromptComparisonSection(result: ExtendedOptimizationExperimentResult): String {
        val original = result.optimizationResult.originalPrompt
        val optimized = result.optimizationResult.optimizedPrompt
        
        return """
        <section class="section prompt-comparison-section">
            <h2>🔄 Сравнение промптов</h2>
            <div class="prompt-comparison">
                <div class="prompt-box original">
                    <div class="prompt-header">
                        <h3>Исходный промпт</h3>
                        <span class="prompt-badge original">БАЗОВЫЙ</span>
                    </div>
                    <div class="prompt-content">
                        <div class="prompt-meta">
                            <span><strong>ID:</strong> ${original.id}</span>
                            <span><strong>Переменные:</strong> ${original.variables.size}</span>
                        </div>
                        <div class="prompt-template">
                            <pre><code>${escapeHtml(original.template)}</code></pre>
                        </div>
                    </div>
                </div>
                
                ${if (optimized != null) """
                <div class="prompt-box optimized">
                    <div class="prompt-header">
                        <h3>Оптимизированный промпт</h3>
                        <span class="prompt-badge optimized">ОПТИМИЗИРОВАНО</span>
                    </div>
                    <div class="prompt-content">
                        <div class="prompt-meta">
                            <span><strong>ID:</strong> ${optimized.id}</span>
                            <span><strong>Переменные:</strong> ${optimized.variables.size}</span>
                        </div>
                        <div class="prompt-template">
                            <pre><code>${escapeHtml(optimized.template)}</code></pre>
                        </div>
                    </div>
                </div>
                """ else """
                <div class="prompt-box no-optimization">
                    <div class="prompt-header">
                        <h3>Оптимизированный промпт не создан</h3>
                        <span class="prompt-badge suggest">РЕЖИМ ПРЕДЛОЖЕНИЙ</span>
                    </div>
                    <div class="prompt-content">
                        <p class="no-optimization-text">
                            Запуск был в режиме предложений (suggest): готовый оптимизированный промпт не применялся — см. блок рекомендаций ниже.
                        </p>
                    </div>
                </div>
                """}
            </div>
        </section>
        """
    }
    
    private fun buildCategoryBreakdownSection(categoryBreakdown: Map<String, CategoryMetrics>): String {
        return """
        <section class="section category-breakdown-section">
            <h2>📋 Анализ по категориям тестов</h2>
            <div class="category-grid">
                ${categoryBreakdown.map { (category, metrics) ->
                    """
                    <div class="category-card ${metrics.status.name.lowercase()}">
                        <div class="category-header">
                            <h3>${ruTestCategory(category)}</h3>
                            <span class="category-status ${metrics.status.name.lowercase()}">
                                ${getCategoryStatusIcon(metrics.status)} ${ruCategoryStatus(metrics.status)}
                            </span>
                        </div>
                        <div class="category-metrics">
                            <div class="metric-row">
                                <span class="metric-label">Тестов:</span>
                                <span class="metric-value">${metrics.testCount}</span>
                            </div>
                            <div class="metric-row">
                                <span class="metric-label">Базовая:</span>
                                <span class="metric-value">${String.format("%.1f", metrics.baselineAccuracy * 100)}%</span>
                            </div>
                            <div class="metric-row">
                                <span class="metric-label">Оптимизация:</span>
                                <span class="metric-value">${String.format("%.1f", metrics.optimizedAccuracy * 100)}%</span>
                            </div>
                            <div class="metric-row improvement">
                                <span class="metric-label">Прирост:</span>
                                <span class="metric-value ${if (metrics.improvement >= 0) "positive" else "negative"}">
                                    ${if (metrics.improvement >= 0) "+" else ""}${String.format("%.1f", metrics.improvement * 100)}%
                                </span>
                            </div>
                        </div>
                    </div>
                    """
                }.joinToString("")}
            </div>
        </section>
        """
    }
    
    private fun buildOptimizationDetailsSection(result: ExtendedOptimizationExperimentResult): String {
        val optimizationResult = result.optimizationResult
        
        return """
        <section class="section optimization-details-section">
            <h2>💡 Детали оптимизации</h2>
            
            <div class="optimization-summary">
                <div class="summary-item">
                    <strong>Тип оптимизатора:</strong> ${ruOptimizerType(result.config.type)}
                </div>
                <div class="summary-item">
                    <strong>Режим:</strong> ${ruOptimizationMode(result.config.mode)}
                </div>
                <div class="summary-item">
                    <strong>Уверенность:</strong> ${String.format("%.1f", optimizationResult.confidence * 100)}%
                </div>
                <div class="summary-item">
                    <strong>Время выполнения:</strong> ${optimizationResult.executionTimeMs} ms
                </div>
            </div>
            
            <div class="optimization-reasoning">
                <h3>Обоснование</h3>
                <div class="reasoning-text">${escapeHtml(optimizationResult.reasoning)}</div>
            </div>
            
            <div class="optimization-suggestions">
                <h3>Предложения (${optimizationResult.suggestions.size})</h3>
                <div class="suggestions-list">
                    ${optimizationResult.suggestions.map { suggestion ->
                        """
                        <div class="suggestion-item ${suggestion.impact.name.lowercase()}">
                            <div class="suggestion-header">
                                <span class="suggestion-type">${ruSuggestionType(suggestion.type)}</span>
                                <span class="suggestion-impact ${suggestion.impact.name.lowercase()}">${ruSuggestionImpact(suggestion.impact)}</span>
                            </div>
                            <div class="suggestion-description">${escapeHtml(suggestion.description)}</div>
                            ${if (suggestion.originalText != null && suggestion.suggestedText != null) """
                            <div class="suggestion-change">
                                <div class="change-from">
                                    <strong>Было:</strong> <code>${escapeHtml(suggestion.originalText)}</code>
                                </div>
                                <div class="change-to">
                                    <strong>Стало:</strong> <code>${escapeHtml(suggestion.suggestedText)}</code>
                                </div>
                            </div>
                            """ else ""}
                            <div class="suggestion-reasoning">${escapeHtml(suggestion.reasoning)}</div>
                        </div>
                        """
                    }.joinToString("")}
                </div>
            </div>
        </section>
        """
    }
    
    private fun buildPerformanceComparisonSection(result: ExtendedOptimizationExperimentResult): String {
        val baseline = result.baselineResult.metrics
        val optimized = result.optimizedExperimentResult?.metrics
        val improvement = result.improvement
        
        return """
        <section class="section performance-comparison-section">
            <h2>📈 Сравнение производительности</h2>
            
            <div class="performance-grid">
                <div class="performance-card">
                    <h3>Базовая конфигурация</h3>
                    <div class="performance-metrics">
                        <div class="metric">
                            <span class="metric-label">Средняя оценка:</span>
                            <span class="metric-value">${baseline.averageScore}</span>
                        </div>
                        <div class="metric">
                            <span class="metric-label">Доля успеха:</span>
                            <span class="metric-value">${if (baseline.totalRuns > 0) (baseline.successfulRuns.toDouble() / baseline.totalRuns * 100).toInt() else 0}%</span>
                        </div>
                        <div class="metric">
                            <span class="metric-label">Средняя задержка:</span>
                            <span class="metric-value">${baseline.averageLatency} ms</span>
                        </div>
                    </div>
                </div>
                
                ${if (optimized != null) """
                <div class="performance-card optimized">
                    <h3>После оптимизации</h3>
                    <div class="performance-metrics">
                        <div class="metric">
                            <span class="metric-label">Средняя оценка:</span>
                            <span class="metric-value">${optimized.averageScore}</span>
                        </div>
                        <div class="metric">
                            <span class="metric-label">Доля успеха:</span>
                            <span class="metric-value">${if (optimized.totalRuns > 0) (optimized.successfulRuns.toDouble() / optimized.totalRuns * 100).toInt() else 0}%</span>
                        </div>
                        <div class="metric">
                            <span class="metric-label">Средняя задержка:</span>
                            <span class="metric-value">${optimized.averageLatency} ms</span>
                        </div>
                    </div>
                </div>
                """ else ""}
                
                ${if (improvement != null) """
                <div class="performance-card improvement">
                    <h3>Изменение метрик</h3>
                    <div class="performance-metrics">
                        <div class="metric">
                            <span class="metric-label">Изменение оценки:</span>
                            <span class="metric-value ${if (improvement.scoreImprovement >= 0) "positive" else "negative"}">
                                ${if (improvement.scoreImprovement >= 0) "+" else ""}${String.format("%.3f", improvement.scoreImprovement)}
                            </span>
                        </div>
                        <div class="metric">
                            <span class="metric-label">Изменение успешности:</span>
                            <span class="metric-value ${if (improvement.passRateImprovement >= 0) "positive" else "negative"}">
                                ${if (improvement.passRateImprovement >= 0) "+" else ""}${String.format("%.1f", improvement.passRateImprovement * 100)}%
                            </span>
                        </div>
                        <div class="metric">
                            <span class="metric-label">Значимо:</span>
                            <span class="metric-value ${if (improvement.significantImprovement) "positive" else "neutral"}">
                                ${if (improvement.significantImprovement) "Да" else "Нет"}
                            </span>
                        </div>
                    </div>
                </div>
                """ else ""}
            </div>
        </section>
        """
    }
    
    private fun buildRecommendationsSection(qaVerification: QAVerificationResult): String {
        return """
        <section class="section recommendations-section">
            <h2>💡 Рекомендации</h2>
            <div class="recommendation-content">
                <div class="recommendation-status ${qaVerification.verificationStatus.name.lowercase()}">
                    <div class="status-icon">${getStatusIcon(qaVerification.verificationStatus)}</div>
                    <div class="status-text">
                        <h3>Оптимизация — ${ruVerificationStatus(qaVerification.verificationStatus)}</h3>
                        <p>${getStatusDescription(qaVerification.verificationStatus)}</p>
                    </div>
                </div>
                
                <div class="recommendation-text">
                    <h3>Подробные рекомендации</h3>
                    <div class="recommendation-body">
                        ${escapeHtml(qaVerification.recommendation).replace("\n", "<br>")}
                    </div>
                </div>
                
                ${if (qaVerification.redFlags.isNotEmpty()) """
                <div class="next-steps">
                    <h3>Следующие шаги</h3>
                    <ul>
                        ${if (qaVerification.redFlags.any { it.severity == Severity.CRITICAL }) 
                            "<li><strong>Важно:</strong> устраните критические красные флаги до вывода в прод</li>" else ""}
                        ${if (qaVerification.redFlags.any { it.type == RedFlagType.HARDCODED_ANSWERS }) 
                            "<li>Проверьте оптимизированные промпты на признаки «зашитых» ответов</li>" else ""}
                        ${if (qaVerification.redFlags.any { it.type == RedFlagType.OVERFITTING }) 
                            "<li>Протестируйте на дополнительных доменах и паттернах (снижение переобучения)</li>" else ""}
                        <li>Проведите ручной разбор результатов работы алгоритма оптимизации</li>
                        <li>Включите дополнительные проверки качества и регрессии</li>
                    </ul>
                </div>
                """ else """
                <div class="next-steps success">
                    <h3>✅ Оптимизация одобрена</h3>
                    <p>Результат выглядит обоснованным; при наличии согласования процессов можно рассматривать использование оптимизированной версии.</p>
                </div>
                """}
            </div>
        </section>
        """
    }
    
    private fun buildStandardHtmlReport(result: TestSuiteResult, testSuiteName: String?): String {
        val effectiveSuiteName = testSuiteName ?: "Набор тестов"
        val successRatePercent = formatPercent(result.successRate)
        val averageScorePercent = formatPercent(result.metrics.averageScore)
        val qaSummary = result.qaSummary

        return """
<!DOCTYPE html>
<html lang="ru">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Отчёт QA-верификации</title>
    <style>${getEnhancedStyles()}</style>
</head>
<body>
    <div class="container">
        <header class="header">
            <h1>Отчёт QA-верификации</h1>
            <div class="subtitle">${escapeHtml(effectiveSuiteName)}</div>
            <div class="timestamp">Сформирован: ${LocalDateTime.now()}</div>
        </header>

        <section class="section">
            <h2>Сводка</h2>
            <div class="summary-grid">
                <div class="summary-card info">
                    <div class="card-title">Тестов всего</div>
                    <div class="card-value">${result.total}</div>
                </div>
                <div class="summary-card success">
                    <div class="card-title">Успешно</div>
                    <div class="card-value">${result.passed}</div>
                </div>
                <div class="summary-card danger">
                    <div class="card-title">Провалено</div>
                    <div class="card-value">${result.failed}</div>
                </div>
                <div class="summary-card ${if (result.successRate >= 0.7) "success" else "warning"}">
                    <div class="card-title">Доля успеха</div>
                    <div class="card-value">$successRatePercent</div>
                </div>
                <div class="summary-card ${if (result.metrics.averageScore >= 0.7) "success" else "warning"}">
                    <div class="card-title">Средняя оценка</div>
                    <div class="card-value">$averageScorePercent</div>
                </div>
                <div class="summary-card info">
                    <div class="card-title">Время выполнения</div>
                    <div class="card-value">${result.executionTimeMs} ms</div>
                </div>
            </div>
        </section>

        ${if (qaSummary != null) """
        <section class="section qa-summary-section">
            <h2>Проверка качества (QA)</h2>
            <div class="summary-grid">
                <div class="summary-card ${qaVerdictClass(qaSummary.finalVerdict)}">
                    <div class="card-title">Итоговый вердикт</div>
                    <div class="card-value">${ruQAVerdict(qaSummary.finalVerdict)}</div>
                </div>
                <div class="summary-card ${if (qaSummary.generalizationScore >= 0.7) "success" else "warning"}">
                    <div class="card-title">Обобщение</div>
                    <div class="card-value">${formatPercent(qaSummary.generalizationScore)}</div>
                </div>
                <div class="summary-card ${if (qaSummary.consistencyScore >= 0.7) "success" else "warning"}">
                    <div class="card-title">Согласованность</div>
                    <div class="card-value">${formatPercent(qaSummary.consistencyScore)}</div>
                </div>
                <div class="summary-card ${if (qaSummary.suspiciousTestsCount > 0) "warning" else "success"}">
                    <div class="card-title">Подозрительные тесты</div>
                    <div class="card-value">${qaSummary.suspiciousTestsCount}</div>
                </div>
            </div>
            ${if (qaSummary.redFlags.isNotEmpty()) """
            <div class="inline-note warning">
                <strong>Сигналы (red flags):</strong> ${escapeHtml(qaSummary.redFlags.joinToString("; "))}
            </div>
            """ else ""}
        </section>
        """ else ""}

        <section class="section">
            <h2>Результаты тестов</h2>
            <div class="table-wrapper">
                <table class="results-table">
                    <thead>
                        <tr>
                            <th>#</th>
                            <th>Промпт</th>
                            <th>Агент</th>
                            <th>Провайдер</th>
                            <th>Метод оценки</th>
                            <th>Балл</th>
                            <th>Статус</th>
                            <th>Время ответа</th>
                            <th>Ошибка</th>
                        </tr>
                    </thead>
                    <tbody>
                        ${result.results.mapIndexed { index, testResult ->
                            val statusClass = if (testResult.success) "success" else "danger"
                            val scorePercent = formatPercent(testResult.evaluationResult.score)
                            val agents = testResult.testCase.getAllAgentNames().joinToString(", ")
                            val errorText = testResult.error?.takeIf { it.isNotBlank() } ?: "-"
                            val model = testResult.llmResponse?.model ?: "-"
                            """
                            <tr class="$statusClass">
                                <td>${index + 1}</td>
                                <td>${escapeHtml(testResult.testCase.promptId)}</td>
                                <td>${escapeHtml(agents)}</td>
                                <td>${escapeHtml(testResult.provider ?: "-")}</td>
                                <td>${escapeHtml(testResult.testCase.evaluatorType)}</td>
                                <td><strong>$scorePercent</strong></td>
                                <td><span class="status-badge $statusClass">${if (testResult.success) "УСПЕХ" else "ПРОВАЛ"}</span></td>
                                <td>${testResult.executionTimeMs} ms</td>
                                <td class="error-cell">${escapeHtml(errorText)}</td>
                            </tr>
                            <tr class="details-row">
                                <td colspan="9">
                                    <details>
                                        <summary>Подробнее</summary>
                                        <div class="details-grid">
                                            <div><strong>Ожидалось:</strong> ${escapeHtml(testResult.testCase.expected)}</div>
                                            <div><strong>Ответ модели:</strong> ${escapeHtml(testResult.llmResponse?.content ?: "-")}</div>
                                            <div><strong>Пояснение оценки:</strong> ${escapeHtml(testResult.evaluationResult.explanation)}</div>
                                            <div><strong>Модель:</strong> ${escapeHtml(model)}</div>
                                            <div><strong>Переменные:</strong> ${escapeHtml(testResult.testCase.variables.toString())}</div>
                                        </div>
                                    </details>
                                </td>
                            </tr>
                            """
                        }.joinToString("")}
                    </tbody>
                </table>
            </div>
        </section>
    </div>
</body>
</html>
        """
    }

    private fun qaVerdictClass(verdict: QAVerdict): String = when (verdict) {
        QAVerdict.LEGITIMATE -> "success"
        QAVerdict.SUSPICIOUS -> "warning"
        QAVerdict.FAILED -> "danger"
    }

    private fun formatPercent(value: Double): String = "${String.format("%.1f", value * 100)}%"

    private fun ruVerificationStatus(status: VerificationStatus): String = when (status) {
        VerificationStatus.LEGITIMATE -> "Надёжный результат"
        VerificationStatus.SUSPICIOUS -> "Есть признаки риска"
        VerificationStatus.FAILED -> "Не пройдено"
        VerificationStatus.INCONCLUSIVE -> "Неопределённо"
    }

    private fun ruSeverity(severity: Severity): String = when (severity) {
        Severity.CRITICAL -> "Критично"
        Severity.HIGH -> "Высокая"
        Severity.MEDIUM -> "Средняя"
        Severity.LOW -> "Низкая"
    }

    private fun ruCategoryStatus(status: CategoryStatus): String = when (status) {
        CategoryStatus.GOOD -> "Хорошо"
        CategoryStatus.WARNING -> "Внимание"
        CategoryStatus.CRITICAL -> "Критично"
    }

    private fun ruQAVerdict(verdict: QAVerdict): String = when (verdict) {
        QAVerdict.LEGITIMATE -> "Надёжно"
        QAVerdict.SUSPICIOUS -> "Подозрительно"
        QAVerdict.FAILED -> "Провалено"
    }

    private fun ruRedFlagType(type: RedFlagType): String = when (type) {
        RedFlagType.HARDCODED_ANSWERS -> "Жёстко заданные ответы"
        RedFlagType.OVERFITTING -> "Переобучение под тестовый набор"
        RedFlagType.SUSPICIOUS_PATTERNS -> "Подозрительные паттерны"
        RedFlagType.PERFORMANCE_ANOMALY -> "Аномалии метрик качества"
        RedFlagType.PROMPT_LEAKAGE -> "Утечка инструкций промпта"
        RedFlagType.INCONSISTENT_OPTIMIZATION -> "Непоследовательная оптимизация"
    }

    private fun ruTestCategory(category: String): String = when (category.uppercase(Locale.ROOT)) {
        "BASELINE" -> "Базовые"
        "PARAPHRASE" -> "Перефразирование"
        "TRAP" -> "Ловушки"
        "UNSEEN" -> "Новые домены"
        "MISLEADING" -> "Обманчивая формулировка"
        "EDGE_CASE" -> "Граничные случаи"
        else -> category.lowercase(Locale.ROOT).replaceFirstChar { ch ->
            if (ch.isLowerCase()) ch.titlecase(Locale.getDefault()) else ch.toString()
        }
    }

    private fun ruOptimizationMode(mode: OptimizationMode): String = when (mode) {
        OptimizationMode.SUGGEST -> "Только предложения"
        OptimizationMode.APPLY -> "Применение изменений"
    }

    private fun ruOptimizerType(type: OptimizerType): String = when (type) {
        OptimizerType.LLM -> "LLM-оптимизатор"
        OptimizerType.RULE_BASED -> "Правила"
    }

    private fun ruSuggestionType(type: SuggestionType): String = when (type) {
        SuggestionType.CLARITY -> "Ясность"
        SuggestionType.SPECIFICITY -> "Конкретизация"
        SuggestionType.LENGTH -> "Объём"
        SuggestionType.STRUCTURE -> "Структура"
        SuggestionType.CONTEXT -> "Контекст"
        SuggestionType.EXAMPLES -> "Примеры"
        SuggestionType.CONSTRAINTS -> "Ограничения"
        SuggestionType.FORMAT -> "Формат"
        SuggestionType.TONE -> "Тон"
        SuggestionType.OTHER -> "Прочее"
    }

    private fun ruSuggestionImpact(impact: SuggestionImpact): String = when (impact) {
        SuggestionImpact.LOW -> "Низкое влияние"
        SuggestionImpact.MEDIUM -> "Среднее влияние"
        SuggestionImpact.HIGH -> "Высокое влияние"
        SuggestionImpact.CRITICAL -> "Критичное влияние"
    }
    
    private fun getStatusIcon(status: VerificationStatus): String = when (status) {
        VerificationStatus.LEGITIMATE -> "✅"
        VerificationStatus.SUSPICIOUS -> "⚠️"
        VerificationStatus.FAILED -> "❌"
        VerificationStatus.INCONCLUSIVE -> "❓"
    }
    
    private fun getSeverityIcon(severity: Severity): String = when (severity) {
        Severity.CRITICAL -> "🔴"
        Severity.HIGH -> "🟠"
        Severity.MEDIUM -> "🟡"
        Severity.LOW -> "🟢"
    }
    
    private fun getCategoryStatusIcon(status: CategoryStatus): String = when (status) {
        CategoryStatus.GOOD -> "✅"
        CategoryStatus.WARNING -> "⚠️"
        CategoryStatus.CRITICAL -> "❌"
    }
    
    private fun getStatusDescription(status: VerificationStatus): String = when (status) {
        VerificationStatus.LEGITIMATE -> "Изменение выглядит содержательным и устойчивым при текущих проверках."
        VerificationStatus.SUSPICIOUS ->
            "Обнаружены отдельные сигналы риска. Рекомендуется ручной разбор и дополнительные тесты."
        VerificationStatus.FAILED ->
            "Критические проблемы: такую оптимизацию использовать нельзя до устранения причин."
        VerificationStatus.INCONCLUSIVE ->
            "Недостаточно данных для вывода. Нужно расширить набор проверочных кейсов или повторить прогон."
    }
    
    private fun getAccuracyClass(accuracy: Double): String = when {
        accuracy >= 0.8 -> "success"
        accuracy >= 0.6 -> "warning"
        else -> "danger"
    }
    
    private fun getGeneralizationClass(score: Double): String = when {
        score >= 0.7 -> "success"
        score >= 0.5 -> "warning"
        else -> "danger"
    }
    
    private fun getRedFlagClass(count: Int): String = when {
        count == 0 -> "success"
        count <= 2 -> "warning"
        else -> "danger"
    }
    
    private fun getConsistencyClass(score: Double): String = when {
        score >= 0.8 -> "success"
        score >= 0.6 -> "warning"
        else -> "danger"
    }
    
    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
    
    private fun getEnhancedStyles(): String {
        return """
        * { box-sizing: border-box; }
        
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, Cantarell, sans-serif;
            margin: 0;
            padding: 20px;
            background: linear-gradient(135deg, #f5f7fa 0%, #c3cfe2 100%);
            color: #333;
            line-height: 1.6;
        }
        
        .container {
            max-width: 1400px;
            margin: 0 auto;
            background: white;
            border-radius: 12px;
            box-shadow: 0 8px 32px rgba(0,0,0,0.1);
            overflow: hidden;
        }
        
        .header {
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            color: white;
            padding: 40px;
            text-align: center;
            position: relative;
        }
        
        .header h1 {
            margin: 0;
            font-size: 3em;
            font-weight: 300;
            text-shadow: 0 2px 4px rgba(0,0,0,0.3);
        }
        
        .subtitle {
            font-size: 1.2em;
            opacity: 0.9;
            margin: 10px 0;
        }
        
        .timestamp {
            font-size: 0.9em;
            opacity: 0.8;
            margin: 5px 0;
        }
        
        .verification-status {
            display: inline-block;
            padding: 12px 24px;
            border-radius: 25px;
            font-weight: bold;
            font-size: 1.1em;
            margin-top: 20px;
            text-transform: uppercase;
            letter-spacing: 1px;
        }
        
        .verification-status.legitimate { background: rgba(40, 167, 69, 0.2); border: 2px solid #28a745; }
        .verification-status.suspicious { background: rgba(255, 193, 7, 0.2); border: 2px solid #ffc107; }
        .verification-status.failed { background: rgba(220, 53, 69, 0.2); border: 2px solid #dc3545; }
        .verification-status.inconclusive { background: rgba(108, 117, 125, 0.2); border: 2px solid #6c757d; }
        
        .section {
            padding: 40px;
            border-bottom: 1px solid #e9ecef;
        }
        
        .section:last-child { border-bottom: none; }
        
        .section h2 {
            margin: 0 0 30px 0;
            font-size: 2em;
            color: #495057;
            border-bottom: 3px solid #e9ecef;
            padding-bottom: 15px;
        }
        
        .executive-summary {
            background: linear-gradient(135deg, #f8f9fa 0%, #e9ecef 100%);
        }
        
        .summary-grid {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
            gap: 20px;
            margin-top: 20px;
        }
        
        .summary-card {
            background: white;
            padding: 25px;
            border-radius: 12px;
            text-align: center;
            box-shadow: 0 4px 12px rgba(0,0,0,0.1);
            border-left: 5px solid #dee2e6;
            transition: transform 0.2s, box-shadow 0.2s;
        }
        
        .summary-card:hover {
            transform: translateY(-2px);
            box-shadow: 0 8px 24px rgba(0,0,0,0.15);
        }
        
        .summary-card.success { border-left-color: #28a745; }
        .summary-card.warning { border-left-color: #ffc107; }
        .summary-card.danger { border-left-color: #dc3545; }
        .summary-card.info { border-left-color: #17a2b8; }
        
        .card-icon {
            font-size: 2.5em;
            margin-bottom: 10px;
        }
        
        .card-title {
            font-size: 0.9em;
            color: #6c757d;
            text-transform: uppercase;
            letter-spacing: 1px;
            margin-bottom: 5px;
        }
        
        .card-value {
            font-size: 2.2em;
            font-weight: bold;
            color: #495057;
            margin-bottom: 5px;
        }
        
        .card-subtitle {
            font-size: 0.8em;
            color: #6c757d;
        }
        
        .red-flags-section {
            background: #fff5f5;
        }
        
        .no-red-flags {
            text-align: center;
            padding: 60px 20px;
            background: white;
            border-radius: 12px;
            border: 2px dashed #28a745;
        }
        
        .success-icon {
            font-size: 4em;
            margin-bottom: 20px;
        }
        
        .red-flags-summary {
            display: flex;
            justify-content: center;
            gap: 20px;
            margin-bottom: 30px;
            flex-wrap: wrap;
        }
        
        .flags-count {
            padding: 10px 20px;
            border-radius: 20px;
            font-weight: bold;
            color: white;
        }
        
        .flags-count.critical { background: #dc3545; }
        .flags-count.high { background: #fd7e14; }
        .flags-count.medium { background: #ffc107; color: #212529; }
        .flags-count.low { background: #28a745; }
        
        .red-flags-list {
            space-y: 15px;
        }
        
        .red-flag-item {
            background: white;
            border-radius: 8px;
            margin-bottom: 15px;
            overflow: hidden;
            box-shadow: 0 2px 8px rgba(0,0,0,0.1);
            cursor: pointer;
            transition: all 0.2s;
        }
        
        .red-flag-item:hover {
            box-shadow: 0 4px 16px rgba(0,0,0,0.15);
        }
        
        .red-flag-item.critical { border-left: 5px solid #dc3545; }
        .red-flag-item.high { border-left: 5px solid #fd7e14; }
        .red-flag-item.medium { border-left: 5px solid #ffc107; }
        .red-flag-item.low { border-left: 5px solid #28a745; }
        
        .flag-header {
            display: flex;
            align-items: center;
            padding: 20px;
            gap: 15px;
        }
        
        .flag-icon {
            font-size: 1.5em;
        }
        
        .flag-content {
            flex: 1;
        }
        
        .flag-title {
            font-weight: bold;
            font-size: 1.1em;
            margin-bottom: 5px;
        }
        
        .flag-description {
            color: #6c757d;
        }
        
        .severity-badge {
            padding: 4px 12px;
            border-radius: 12px;
            font-size: 0.8em;
            font-weight: bold;
            text-transform: uppercase;
            margin-right: 8px;
        }
        
        .severity-badge.critical { background: #dc3545; color: white; }
        .severity-badge.high { background: #fd7e14; color: white; }
        .severity-badge.medium { background: #ffc107; color: #212529; }
        .severity-badge.low { background: #28a745; color: white; }
        
        .confidence-badge {
            padding: 4px 8px;
            background: #e9ecef;
            border-radius: 8px;
            font-size: 0.8em;
            color: #495057;
        }
        
        .expand-icon {
            transition: transform 0.2s;
        }
        
        .red-flag-item.expanded .expand-icon {
            transform: rotate(180deg);
        }
        
        .flag-details {
            padding: 0 20px 20px 20px;
            background: #f8f9fa;
        }
        
        .detail-section {
            margin-bottom: 15px;
        }
        
        .evidence-text, .recommendation-text {
            background: white;
            padding: 15px;
            border-radius: 6px;
            border-left: 3px solid #dee2e6;
            margin-top: 8px;
            font-family: 'Monaco', 'Consolas', monospace;
            font-size: 0.9em;
        }
        
        .qa-metrics-section {
            background: #f8f9fa;
        }
        
        .metrics-comparison {
            display: grid;
            grid-template-columns: 2fr 1fr;
            gap: 30px;
        }
        
        .comparison-chart h3, .key-indicators h3 {
            margin-bottom: 20px;
            color: #495057;
        }
        
        .chart-container {
            background: white;
            padding: 20px;
            border-radius: 8px;
            box-shadow: 0 2px 8px rgba(0,0,0,0.1);
        }
        
        .metric-bar {
            display: flex;
            align-items: center;
            margin-bottom: 15px;
            gap: 15px;
        }
        
        .metric-label {
            min-width: 120px;
            font-weight: 500;
        }
        
        .bar-container {
            flex: 1;
            position: relative;
            height: 30px;
            background: #e9ecef;
            border-radius: 15px;
            overflow: hidden;
        }
        
        .bar {
            height: 100%;
            border-radius: 15px;
            transition: width 0.8s ease;
            position: relative;
        }
        
        .bar.baseline { background: linear-gradient(90deg, #6c757d, #495057); }
        .bar.optimized { background: linear-gradient(90deg, #28a745, #20c997); }
        .bar.paraphrase { background: linear-gradient(90deg, #17a2b8, #20c997); }
        .bar.trap { background: linear-gradient(90deg, #dc3545, #fd7e14); }
        .bar.unseen { background: linear-gradient(90deg, #6f42c1, #e83e8c); }
        
        .bar-value {
            position: absolute;
            right: 10px;
            top: 50%;
            transform: translateY(-50%);
            color: white;
            font-weight: bold;
            font-size: 0.9em;
            text-shadow: 0 1px 2px rgba(0,0,0,0.5);
        }
        
        .indicators-grid {
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 15px;
        }
        
        .indicator {
            background: white;
            padding: 20px;
            border-radius: 8px;
            text-align: center;
            box-shadow: 0 2px 8px rgba(0,0,0,0.1);
        }
        
        .indicator.good { border-left: 4px solid #28a745; }
        .indicator.warning { border-left: 4px solid #ffc107; }
        .indicator.danger { border-left: 4px solid #dc3545; }
        
        .indicator-icon {
            font-size: 1.5em;
            margin-bottom: 8px;
        }
        
        .indicator-label {
            font-size: 0.8em;
            color: #6c757d;
            text-transform: uppercase;
            letter-spacing: 1px;
        }
        
        .indicator-value {
            font-size: 1.5em;
            font-weight: bold;
            color: #495057;
        }
        
        @media (max-width: 768px) {
            .container { margin: 10px; }
            .header { padding: 20px; }
            .header h1 { font-size: 2em; }
            .section { padding: 20px; }
            .summary-grid { grid-template-columns: 1fr; }
            .metrics-comparison { grid-template-columns: 1fr; }
        }

        .qa-summary-section {
            background: #f6fbff;
        }

        .inline-note {
            margin-top: 20px;
            padding: 12px 16px;
            border-radius: 8px;
            border-left: 4px solid #dee2e6;
        }

        .inline-note.warning {
            background: #fff8e1;
            border-left-color: #ffc107;
        }

        .table-wrapper {
            overflow-x: auto;
        }

        .results-table {
            width: 100%;
            border-collapse: collapse;
            margin-top: 10px;
            background: white;
            border: 1px solid #e9ecef;
            border-radius: 8px;
            overflow: hidden;
        }

        .results-table th, .results-table td {
            padding: 10px 12px;
            border-bottom: 1px solid #edf0f2;
            vertical-align: top;
            text-align: left;
            font-size: 0.92em;
        }

        .results-table thead th {
            background: #f3f5f7;
            font-weight: 700;
            white-space: nowrap;
        }

        .results-table tr.success td {
            background: #f8fff9;
        }

        .results-table tr.danger td {
            background: #fff8f8;
        }

        .status-badge {
            display: inline-block;
            font-size: 0.75em;
            font-weight: 700;
            padding: 4px 8px;
            border-radius: 12px;
            letter-spacing: 0.3px;
        }

        .status-badge.success {
            color: #116329;
            background: #d8f5df;
        }

        .status-badge.danger {
            color: #7a1720;
            background: #ffdfe2;
        }

        .error-cell {
            max-width: 280px;
            word-break: break-word;
        }

        .details-row td {
            background: #fcfdff;
            border-bottom: 1px solid #edf0f2;
        }

        .details-row details {
            margin: 0;
        }

        .details-row summary {
            cursor: pointer;
            font-weight: 600;
            color: #3b4b5a;
            margin-bottom: 8px;
        }

        .details-grid {
            display: grid;
            grid-template-columns: 1fr;
            gap: 8px;
            background: #f8fafc;
            border: 1px solid #e7ebef;
            border-radius: 8px;
            padding: 12px;
            white-space: pre-wrap;
            word-break: break-word;
        }
        """
    }
    
    private fun getInteractiveScripts(): String {
        return """
        function toggleDetails(element) {
            const details = element.querySelector('.flag-details');
            const icon = element.querySelector('.expand-icon');
            
            if (details.style.display === 'none' || details.style.display === '') {
                details.style.display = 'block';
                icon.style.transform = 'rotate(180deg)';
                element.classList.add('expanded');
            } else {
                details.style.display = 'none';
                icon.style.transform = 'rotate(0deg)';
                element.classList.remove('expanded');
            }
        }
        
        document.addEventListener('DOMContentLoaded', function() {
            const criticalFlags = document.querySelectorAll('.red-flag-item.critical');
            criticalFlags.forEach(flag => {
                const details = flag.querySelector('.flag-details');
                if (details) {
                    details.style.display = 'block';
                    flag.classList.add('expanded');
                    const icon = flag.querySelector('.expand-icon');
                    if (icon) icon.style.transform = 'rotate(180deg)';
                }
            });
        });
        """
    }
}