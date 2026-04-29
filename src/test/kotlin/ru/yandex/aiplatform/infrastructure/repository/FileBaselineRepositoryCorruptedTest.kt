package ru.yandex.aiplatform.infrastructure.repository

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import ru.yandex.diploma.aiplatform.domain.repository.BaselineLoadResult
import ru.yandex.diploma.aiplatform.infrastructure.repository.FileBaselineRepository
import java.nio.file.Files

class FileBaselineRepositoryCorruptedTest {

    @Test
    fun `corrupted baseline json yields Corrupted result`() = runBlocking {
        val dir = Files.createTempDirectory("baseline-corrupt")
        val repo = FileBaselineRepository.forDirectory(dir)
        val path = dir.resolve("suite.json")
        Files.writeString(path, "{ not valid json {{{")

        val suiteId = "suite"
        val result = repo.loadAll(suiteId)
        assertTrue(result is BaselineLoadResult.Corrupted)
        val c = result as BaselineLoadResult.Corrupted
        assertTrue(c.path.endsWith("suite.json"))
        assertTrue(c.error.isNotBlank())
    }
}
