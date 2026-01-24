package ru.yandex.diploma.aiplatform.domain.repository

import ru.yandex.diploma.aiplatform.domain.model.BaselineEntry

interface BaselineRepository {
    suspend fun saveBaseline(suiteId: String, testCaseId: String, entry: BaselineEntry)

    suspend fun getBaseline(suiteId: String, testCaseId: String): BaselineEntry?

    suspend fun loadAll(suiteId: String): Map<String, BaselineEntry>
}
