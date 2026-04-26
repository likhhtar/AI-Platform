package ru.yandex.diploma.aiplatform.infrastructure.service

import org.springframework.stereotype.Service
import ru.yandex.diploma.aiplatform.application.usecase.ExperimentResult
import ru.yandex.diploma.aiplatform.application.usecase.TestSuiteResult
import ru.yandex.diploma.aiplatform.domain.model.*
import ru.yandex.diploma.aiplatform.domain.service.ReportGenerator
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Service
class OptimizationHtmlReportGenerator : ReportGenerator {
    
    override fun generate(result: TestSuiteResult, testSuiteName: String?): File {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm"))
        val fileName = "report-$timestamp.html"
        val file = File("reports/$fileName")
        
        val htmlContent = buildStandardHtmlReport(result)
        file.parentFile?.mkdirs()
        file.writeText(htmlContent)
        return file
    }
    
    fun generateOptimizationReport(
        optimizationResult: OptimizationExperimentResult,
        outputPath: String = "reports/"
    ): File {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm"))
        val fileName = "optimization-report-$timestamp.html"
        val file = File(if (outputPath.endsWith("/")) "$outputPath$fileName" else "$outputPath/$fileName")
        
        val htmlContent = buildOptimizationHtmlReport(optimizationResult)
        
        file.parentFile?.mkdirs()
        file.writeText(htmlContent)
        return file
    }
    
    private fun buildOptimizationHtmlReport(optimizationResult: OptimizationExperimentResult): String {
        return """
<!DOCTYPE html>
<html lang="ru">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Отчет по оптимизации промптов</title>
    <style>
        ${getCommonStyles()}
        .optimization-section {
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            color: white;
            padding: 20px;
            border-radius: 10px;
            margin: 20px 0;
        }
        .suggestion-card {
            background: #f8f9fa;
            border-left: 4px solid #007bff;
            padding: 15px;
            margin: 10px 0;
            border-radius: 5px;
        }
        .suggestion-high { border-left-color: #dc3545; }
        .suggestion-medium { border-left-color: #ffc107; }
        .suggestion-low { border-left-color: #28a745; }
        .improvement-positive { color: #28a745; font-weight: bold; }
        .improvement-negative { color: #dc3545; font-weight: bold; }
        .improvement-neutral { color: #6c757d; }
        .prompt-comparison {
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 20px;
            margin: 20px 0;
        }
        .prompt-box {
            background: #f8f9fa;
            border: 1px solid #dee2e6;
            border-radius: 8px;
            padding: 15px;
        }
        .prompt-original { border-left: 4px solid #6c757d; }
        .prompt-optimized { border-left: 4px solid #28a745; }
        .confidence-bar {
            width: 100%;
            height: 20px;
            background: #e9ecef;
            border-radius: 10px;
            overflow: hidden;
        }
        .confidence-fill {
            height: 100%;
            background: linear-gradient(90deg, #dc3545 0%, #ffc107 50%, #28a745 100%);
            transition: width 0.3s ease;
        }
    </style>
</head>
<body>
    <div class="container">
        <header class="header">
            <h1>🚀 Отчет по оптимизации промптов</h1>
            <div class="timestamp">Сгенерирован: ${optimizationResult.timestamp}</div>
        </header>

        ${buildOptimizationSummary(optimizationResult)}
        
        ${buildPromptComparison(optimizationResult)}
        
        ${buildOptimizationSuggestions(optimizationResult.optimizationResult)}
        
        ${buildPerformanceComparison(optimizationResult)}
        
        ${buildBaselineResults(optimizationResult.baselineResult)}
        
        ${if (optimizationResult.optimizedExperimentResult != null) 
            buildOptimizedResults(optimizationResult.optimizedExperimentResult) 
          else ""}
        
        <footer class="footer">
            <p>Отчет сгенерирован AI Platform • ${optimizationResult.timestamp}</p>
        </footer>
    </div>
</body>
</html>
        """.trimIndent()
    }
    
    private fun buildOptimizationSummary(result: OptimizationExperimentResult): String {
        val config = result.config
        val improvement = result.improvement
        
        return """
        <section class="optimization-section">
            <h2>📊 Сводка по оптимизации</h2>
            <div class="metrics-grid">
                <div class="metric-card">
                    <div class="metric-value">${config.type.name}</div>
                    <div class="metric-label">Тип оптимизатора</div>
                </div>
                <div class="metric-card">
                    <div class="metric-value">${config.mode.name}</div>
                    <div class="metric-label">Режим</div>
                </div>
                <div class="metric-card">
                    <div class="metric-value">${result.optimizationResult.confidence * 100}%</div>
                    <div class="metric-label">Уверенность</div>
                </div>
                <div class="metric-card">
                    <div class="metric-value">${result.executionTimeMs}ms</div>
                    <div class="metric-label">Время выполнения</div>
                </div>
            </div>
            
            ${if (improvement != null) """
            <div class="improvement-summary">
                <h3>🎯 Улучшения</h3>
                <div class="metrics-grid">
                    <div class="metric-card">
                        <div class="metric-value ${getImprovementClass(improvement.scoreImprovement)}">
                            ${formatImprovement(improvement.scoreImprovement)}
                        </div>
                        <div class="metric-label">Изменение оценки</div>
                    </div>
                    <div class="metric-card">
                        <div class="metric-value ${getImprovementClass(improvement.passRateImprovement)}">
                            ${formatImprovement(improvement.passRateImprovement * 100)}%
                        </div>
                        <div class="metric-label">Изменение успешности</div>
                    </div>
                    <div class="metric-card">
                        <div class="metric-value ${if (improvement.significantImprovement) "improvement-positive" else "improvement-neutral"}">
                            ${if (improvement.significantImprovement) "Да" else "Нет"}
                        </div>
                        <div class="metric-label">Значимое улучшение</div>
                    </div>
                </div>
            </div>
            """ else ""}
        </section>
        """
    }
    
    private fun buildPromptComparison(result: OptimizationExperimentResult): String {
        val original = result.optimizationResult.originalPrompt
        val optimized = result.optimizationResult.optimizedPrompt
        
        return """
        <section class="section">
            <h2>🔄 Сравнение промптов</h2>
            <div class="prompt-comparison">
                <div class="prompt-box prompt-original">
                    <h3>Исходный промпт</h3>
                    <p><strong>ID:</strong> ${original.id}</p>
                    <p><strong>Название:</strong> ${original.name}</p>
                    <div class="prompt-template">
                        <strong>Шаблон:</strong><br>
                        <code>${escapeHtml(original.template)}</code>
                    </div>
                    <p><strong>Переменные:</strong> ${original.variables.joinToString(", ")}</p>
                </div>
                
                ${if (optimized != null) """
                <div class="prompt-box prompt-optimized">
                    <h3>Оптимизированный промпт</h3>
                    <p><strong>ID:</strong> ${optimized.id}</p>
                    <p><strong>Название:</strong> ${optimized.name}</p>
                    <div class="prompt-template">
                        <strong>Шаблон:</strong><br>
                        <code>${escapeHtml(optimized.template)}</code>
                    </div>
                    <p><strong>Переменные:</strong> ${optimized.variables.joinToString(", ")}</p>
                </div>
                """ else """
                <div class="prompt-box">
                    <h3>Оптимизированный промпт</h3>
                    <p class="text-muted">Оптимизированный промпт не был создан (режим "suggest")</p>
                </div>
                """}
            </div>
        </section>
        """
    }
    
    private fun buildOptimizationSuggestions(optimizationResult: OptimizationResult): String {
        return """
        <section class="section">
            <h2>💡 Рекомендации по оптимизации</h2>
            
            <div class="confidence-section">
                <h3>Уверенность в рекомендациях</h3>
                <div class="confidence-bar">
                    <div class="confidence-fill" style="width: ${optimizationResult.confidence * 100}%"></div>
                </div>
                <p>${optimizationResult.confidence * 100}% уверенности</p>
            </div>
            
            <div class="reasoning">
                <h3>Обоснование</h3>
                <p>${escapeHtml(optimizationResult.reasoning)}</p>
            </div>
            
            <div class="suggestions">
                <h3>Конкретные рекомендации</h3>
                ${optimizationResult.suggestions.map { suggestion ->
                    """
                    <div class="suggestion-card suggestion-${suggestion.impact.name.lowercase()}">
                        <div class="suggestion-header">
                            <strong>${suggestion.type.name}</strong>
                            <span class="badge badge-${suggestion.impact.name.lowercase()}">${suggestion.impact.name}</span>
                        </div>
                        <p><strong>Описание:</strong> ${escapeHtml(suggestion.description)}</p>
                        ${if (suggestion.originalText != null) 
                            "<p><strong>Исходный текст:</strong> <code>${escapeHtml(suggestion.originalText)}</code></p>" 
                          else ""}
                        ${if (suggestion.suggestedText != null) 
                            "<p><strong>Предлагаемый текст:</strong> <code>${escapeHtml(suggestion.suggestedText)}</code></p>" 
                          else ""}
                        <p><strong>Обоснование:</strong> ${escapeHtml(suggestion.reasoning)}</p>
                        <p><strong>Уверенность:</strong> ${suggestion.confidence * 100}%</p>
                    </div>
                    """
                }.joinToString("")}
            </div>
        </section>
        """
    }
    
    private fun buildPerformanceComparison(result: OptimizationExperimentResult): String {
        val baseline = result.baselineResult.metrics
        val optimized = result.optimizedExperimentResult?.metrics
        
        return """
        <section class="section">
            <h2>📈 Сравнение производительности</h2>
            <div class="metrics-grid">
                <div class="metric-card">
                    <div class="metric-value">${baseline.averageScore}</div>
                    <div class="metric-label">Базовая оценка</div>
                </div>
                ${if (optimized != null) """
                <div class="metric-card">
                    <div class="metric-value">${optimized.averageScore}</div>
                    <div class="metric-label">Оптимизированная оценка</div>
                </div>
                """ else ""}
                <div class="metric-card">
                    <div class="metric-value">${baseline.averageLatency}ms</div>
                    <div class="metric-label">Базовая задержка</div>
                </div>
                ${if (optimized != null) """
                <div class="metric-card">
                    <div class="metric-value">${optimized.averageLatency}ms</div>
                    <div class="metric-label">Оптимизированная задержка</div>
                </div>
                """ else ""}
            </div>
        </section>
        """
    }
    
    private fun buildBaselineResults(result: ExperimentResult): String {
        return """
        <section class="section">
            <h2>📋 Базовые результаты</h2>
            ${buildExperimentResultContent(result)}
        </section>
        """
    }
    
    private fun buildOptimizedResults(result: ExperimentResult): String {
        return """
        <section class="section">
            <h2>🎯 Оптимизированные результаты</h2>
            ${buildExperimentResultContent(result)}
        </section>
        """
    }
    
    private fun buildExperimentResultContent(result: ExperimentResult): String {
        return """
        <div class="metrics-grid">
            <div class="metric-card">
                <div class="metric-value">${result.runs.size}</div>
                <div class="metric-label">Всего запусков</div>
            </div>
            <div class="metric-card">
                <div class="metric-value">${result.runs.count { it.success }}</div>
                <div class="metric-label">Успешных</div>
            </div>
            <div class="metric-card">
                <div class="metric-value">${result.metrics.averageScore}</div>
                <div class="metric-label">Средняя оценка</div>
            </div>
            <div class="metric-card">
                <div class="metric-value">${result.executionTimeMs}ms</div>
                <div class="metric-label">Время выполнения</div>
            </div>
        </div>
        """
    }
    
    private fun buildStandardHtmlReport(result: TestSuiteResult): String {
        // Simplified standard report for backward compatibility
        return """
<!DOCTYPE html>
<html lang="ru">
<head>
    <meta charset="UTF-8">
    <title>Отчет о тестировании</title>
    <style>${getCommonStyles()}</style>
</head>
<body>
    <div class="container">
        <h1>Отчет о тестировании</h1>
        <p>Результаты: ${result.results.size} тестов</p>
        <p>Успешных: ${result.results.count { it.evaluationResult.passed }}</p>
        <p>Время выполнения: ${result.executionTimeMs}ms</p>
    </div>
</body>
</html>
        """
    }
    
    private fun getCommonStyles(): String {
        return """
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; margin: 0; padding: 20px; background: #f8f9fa; }
        .container { max-width: 1200px; margin: 0 auto; background: white; border-radius: 10px; box-shadow: 0 4px 6px rgba(0,0,0,0.1); overflow: hidden; }
        .header { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; padding: 30px; text-align: center; }
        .header h1 { margin: 0; font-size: 2.5em; }
        .timestamp { opacity: 0.9; margin-top: 10px; }
        .section { padding: 30px; border-bottom: 1px solid #e9ecef; }
        .section:last-child { border-bottom: none; }
        .metrics-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 20px; margin: 20px 0; }
        .metric-card { background: #f8f9fa; padding: 20px; border-radius: 8px; text-align: center; border: 1px solid #e9ecef; }
        .metric-value { font-size: 2em; font-weight: bold; color: #495057; }
        .metric-label { color: #6c757d; margin-top: 5px; }
        .badge { padding: 4px 8px; border-radius: 4px; font-size: 0.8em; font-weight: bold; }
        .badge-high { background: #dc3545; color: white; }
        .badge-medium { background: #ffc107; color: black; }
        .badge-low { background: #28a745; color: white; }
        .badge-critical { background: #6f42c1; color: white; }
        .text-muted { color: #6c757d; }
        .footer { background: #f8f9fa; padding: 20px; text-align: center; color: #6c757d; }
        code { background: #f8f9fa; padding: 2px 4px; border-radius: 3px; font-family: 'Monaco', 'Consolas', monospace; }
        """
    }
    
    private fun getImprovementClass(value: Double): String {
        return when {
            value > 0.05 -> "improvement-positive"
            value < -0.05 -> "improvement-negative"
            else -> "improvement-neutral"
        }
    }
    
    private fun formatImprovement(value: Double): String {
        return if (value >= 0) "+%.3f".format(value) else "%.3f".format(value)
    }
    
    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}