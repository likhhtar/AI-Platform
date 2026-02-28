package ru.yandex.diploma.aiplatform.domain.evaluator

class JudgeEvaluationFailedException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
