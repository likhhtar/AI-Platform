package ru.yandex.diploma.aiplatform.infrastructure.repository

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Repository
import ru.yandex.diploma.aiplatform.domain.model.BaselineEntry
import ru.yandex.diploma.aiplatform.domain.model.BaselineKeys
import ru.yandex.diploma.aiplatform.domain.repository.BaselineLoadResult
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

    private val logger = LoggerFactory.getLogger(FileBaselineRepository::class.java)

    companion object {
        fun forDirectory(directory: Path): FileBaselineRepository =
            FileBaselineRepository(directory.toAbsolutePath().normalize())
    }

    private val suiteLocks = ConcurrentHashMap<String, Mutex>()
    private val mapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .enable(SerializationFeature.INDENT_OUTPUT)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)

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
                val existing = readDiskFileResult(path)
                val disk =
                    when (existing) {
                        is BaselineLoadResult.Loaded -> existing.data.toMutableMap()
                        is BaselineLoadResult.Missing -> mutableMapOf()
                        is BaselineLoadResult.Corrupted -> {
                            logger.warn(
                                "baseline_corrupted_resetting_on_save suiteId={} path={} error={}",
                                suiteId,
                                existing.path,
                                existing.error,
                            )
                            mutableMapOf()
                        }
                    }
                disk[testCaseId] = entry
                val document = SuiteBaselineDocument(suiteId = suiteId, baselines = disk)
                val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
                mapper.writeValue(tmp.toFile(), document)
                Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    override suspend fun getBaseline(suiteId: String, testCaseId: String): BaselineEntry? =
        when (val r = loadAll(suiteId)) {
            is BaselineLoadResult.Loaded -> r.data[testCaseId]
            is BaselineLoadResult.Missing -> null
            is BaselineLoadResult.Corrupted -> {
                logger.warn(
                    "baseline_get_skipped_corrupted suiteId={} testCaseId={} path={} error={}",
                    suiteId,
                    testCaseId,
                    r.path,
                    r.error,
                )
                null
            }
        }

    override suspend fun loadAll(suiteId: String): BaselineLoadResult =
        mutexFor(suiteId).withLock {
            withContext(Dispatchers.IO) {
                val path = fileForSuite(suiteId)
                val result = readDiskFileResult(path)
                if (result is BaselineLoadResult.Corrupted) {
                    logger.warn(
                        "baseline_load_corrupted suiteId={} path={} error={}",
                        suiteId,
                        result.path,
                        result.error,
                    )
                }
                result
            }
        }

    suspend fun deleteSuite(suiteId: String) {
        mutexFor(suiteId).withLock {
            withContext(Dispatchers.IO) {
                Files.deleteIfExists(fileForSuite(suiteId))
            }
        }
    }

    private fun readDiskFileResult(path: Path): BaselineLoadResult {
        if (!Files.isRegularFile(path)) return BaselineLoadResult.Missing
        return try {
            val doc: SuiteBaselineDocument = mapper.readValue(path.toFile())
            BaselineLoadResult.Loaded(LinkedHashMap(doc.baselines))
        } catch (e: Exception) {
            BaselineLoadResult.Corrupted(
                error = e.message ?: e.javaClass.simpleName,
                path = path.toString(),
            )
        }
    }

    private data class SuiteBaselineDocument(
        val suiteId: String,
        val baselines: Map<String, BaselineEntry> = emptyMap()
    )
}
