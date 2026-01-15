package ru.yandex.diploma.aiplatform

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import ru.yandex.diploma.aiplatform.config.JudgeEvaluationProperties

@SpringBootApplication
@EnableConfigurationProperties(JudgeEvaluationProperties::class)
class AiPlatformApplication

fun main(args: Array<String>) {
    runApplication<AiPlatformApplication>(*args)
}