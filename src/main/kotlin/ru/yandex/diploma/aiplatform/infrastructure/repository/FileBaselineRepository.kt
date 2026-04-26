package ru.yandex.diploma.aiplatform.infrastructure.repository

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Repository
import ru.yandex.diploma.aiplatform.domain.model.BaselineEntry
import ru.yandex.diploma.aiplatform.domain.model.BaselineKeys
import ru.yandex.diploma.aiplatform.domain.repository.BaselineRepository
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

@Repository
class FileBaselineRepository private constructor(
    private val root: Path
) : BaselineRepository {

    @Autowired
    constructor(
        @Value("\${baseline.storage.directory:data/baselines}") baseDirectory: String
    ) : this(Path.of(baseDirectory).toAbsolutePath().normalize())

    companion object {
        /** Test / non-Spring wiring with an explicit directory. */
        fun forDirectory(directory: Path): FileBaselineRepository =
            FileBaselineRepository(directory.toAbsolutePath().normalize())
    }

    private val suiteLocks = ConcurrentHashMap<String, Mutex>()
    private val mapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    init {
        Files.createDirectories(root)
    }

    private fun mutexKey(suiteId: String): String =
        BaselineKeys.sanitizeForStorage(suiteId).ifBlank { "suite" }

    private fun mutexFor(suiteId: String): Mutex =
        suiteLocks.computeIfAbsent(mutexKey(suiteId)) { Mutex() }

    private fun fileForSuite(suiteId: String): Path {
        val safe = mutexKey(suiteId)
        return root.resolve("$safe.json")
    }

    override suspend fun saveBaseline(suiteId: String, testCaseId: String, entry: BaselineEntry) {
        mutexFor(suiteId).withLock {
            withContext(Dispatchers.IO) {
                val path = fileForSuite(suiteId)
                Files.createDirectories(path.parent)
                val disk = readDiskFile(path).toMutableMap()
                disk[testCaseId] = entry
                val document = SuiteBaselineDocument(suiteId = suiteId, baselines = disk)
                val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
                mapper.writeValue(tmp.toFile(), document)
                Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    override suspend fun getBaseline(suiteId: String, testCaseId: String): BaselineEntry? =
        loadAll(suiteId)[testCaseId]

    override suspend fun loadAll(suiteId: String): Map<String, BaselineEntry> =
        mutexFor(suiteId).withLock {
            withContext(Dispatchers.IO) {
                readDiskFile(fileForSuite(suiteId))
            }
        }

    suspend fun deleteSuite(suiteId: String) {
        mutexFor(suiteId).withLock {
            withContext(Dispatchers.IO) {
                Files.deleteIfExists(fileForSuite(suiteId))
            }
        }
    }

    private fun readDiskFile(path: Path): Map<String, BaselineEntry> {
        if (!Files.isRegularFile(path)) return emptyMap()
        return runCatching {
            val doc: SuiteBaselineDocument = mapper.readValue(path.toFile())
            LinkedHashMap(doc.baselines)
        }.getOrElse { emptyMap() }
    }

    private data class SuiteBaselineDocument(
        val suiteId: String,
        val baselines: Map<String, BaselineEntry> = emptyMap()
    )
}
