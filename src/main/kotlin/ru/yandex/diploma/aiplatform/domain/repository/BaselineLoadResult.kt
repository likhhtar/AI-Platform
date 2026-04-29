package ru.yandex.diploma.aiplatform.domain.repository

import ru.yandex.diploma.aiplatform.domain.model.BaselineEntry

sealed class BaselineLoadResult {
    data class Loaded(val data: Map<String, BaselineEntry>) : BaselineLoadResult()

    data object Missing : BaselineLoadResult()

    data class Corrupted(
        val error: String,
        val path: String,
    ) : BaselineLoadResult()
}
