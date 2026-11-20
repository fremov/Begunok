package ru.begunok.health

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import kotlin.coroutines.coroutineContext

data class GadgetbridgeExtrasRead(
    val stress: List<StressSample> = emptyList(),
    val message: String? = null,
)

/** Reads only the standard SQLite export, never Gadgetbridge's ZIP backup or settings. */
class GadgetbridgeExtrasImporter(context: Context) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver

    suspend fun read(treeUri: Uri?, start: Instant, end: Instant): GadgetbridgeExtrasRead {
        if (treeUri == null || treeUri.toString().isBlank()) return GadgetbridgeExtrasRead()
        return withContext(Dispatchers.IO) {
            cacheLock.withLock {
                try {
                    require(start < end) { "Некорректный период чтения." }
                    val source = findExport(treeUri)
                    val database = cachedDatabase(treeUri, source)
                    SQLiteDatabase.openDatabase(
                        database.absolutePath, null,
                        SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                    ).use { db -> readSamples(db, start, end) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: SecurityException) {
                    GadgetbridgeExtrasRead(message = "Нет доступа к папке Gadgetbridge. Выберите папку заново и разрешите чтение.")
                } catch (error: Exception) {
                    GadgetbridgeExtrasRead(message = error.message?.takeIf { error is ExportProblem }
                        ?: "Не удалось прочитать экспорт Gadgetbridge. Дождитесь завершения автоэкспорта SQLite и проверьте выбранную папку.")
                }
            }
        }
    }

    private data class ExportFile(val uri: Uri, val size: Long?, val modified: Long?)
    private class ExportProblem(message: String) : IOException(message)

    private fun findExport(tree: Uri): ExportFile {
        if (!DocumentsContract.isTreeUri(tree)) {
            throw ExportProblem("Выберите папку Documents/MyLifeSensors через системный выбор папки.")
        }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val files = mutableListOf<ExportFile>()
        resolver.query(children, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        ), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1).orEmpty()
                val mime = cursor.getString(2).orEmpty()
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR || name.endsWith(".zip", true)) continue
                if (name.endsWith(".db", true) || name.endsWith(".sqlite", true) ||
                    name.endsWith(".sqlite3", true) || mime in SQLITE_MIME_TYPES) {
                    files += ExportFile(
                        DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)),
                        if (cursor.isNull(3)) null else cursor.getLong(3),
                        if (cursor.isNull(4)) null else cursor.getLong(4),
                    )
                }
            }
        } ?: throw ExportProblem("Папка недоступна. Выберите её заново и разрешите чтение.")
        if (files.isEmpty()) throw ExportProblem("В папке нет SQLite-экспорта. Включите в Gadgetbridge автоэкспорт базы .db в эту папку, не ZIP, и выполните экспорт.")
        if (files.size > 1) throw ExportProblem("В папке несколько баз данных. Оставьте в отдельной папке только текущий SQLite-автоэкспорт Gadgetbridge и выберите её.")
        return files.single()
    }

    private suspend fun digest(uri: Uri, output: OutputStream? = null): String {
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                output?.write(buffer, 0, count)
            }
        } ?: throw ExportProblem("Файл экспорта недоступен. Проверьте автоэкспорт Gadgetbridge и выберите папку заново.")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun cachedDatabase(tree: Uri, source: ExportFile): File {
        // Providers may omit metadata or replace a document without changing size/mtime.
        // Hash the source on every read; copy it only when its actual bytes change.
        val hash = digest(source.uri)
        val directory = File(context.cacheDir, "gadgetbridge-extras").apply { mkdirs() }
        val cached = File(directory, "$hash.db")
        if (cached.isFile) {
            if (findExport(tree) != source) throw ExportProblem("Экспорт обновляется. Дождитесь завершения экспорта Gadgetbridge.")
            return cached
        }
        val temporary = File.createTempFile("export-", ".tmp", directory)
        try {
            val copiedHash = temporary.outputStream().use { digest(source.uri, it) }
            if (copiedHash != hash || findExport(tree) != source) {
                throw ExportProblem("Экспорт обновляется. Дождитесь завершения экспорта Gadgetbridge.")
            }
            temporary.inputStream().use { input ->
                val header = ByteArray(16)
                if (input.read(header) != header.size || !header.contentEquals(SQLITE_HEADER)) {
                    throw ExportProblem("Файл не является базой SQLite. Выберите стандартный автоэкспорт .db Gadgetbridge, не ZIP.")
                }
            }
            if (!temporary.renameTo(cached)) throw IOException("Could not cache SQLite export")
            directory.listFiles()?.filter { it != cached }?.forEach { it.delete() }
            return cached
        } finally {
            temporary.delete()
        }
    }

    private fun readSamples(db: SQLiteDatabase, start: Instant, end: Instant): GadgetbridgeExtrasRead {
        // Official 2026.11.1 schema: GBDaoGenerator.java, XiaomiStressSampleProvider and DailyDetailsParser.
        // https://codeberg.org/Freeyourgadget/Gadgetbridge/src/tag/2026.11.1
        // Activity timestamps are epoch seconds; manual sample timestamps are milliseconds.
        val deviceIds = mutableListOf<Long>()
        db.rawQuery(
            "SELECT _id FROM DEVICE WHERE TYPE_NAME = 'MIBAND11ACTIVE' OR _id IN (SELECT DEVICE_ID FROM XIAOMI_ACTIVITY_SAMPLE UNION SELECT DEVICE_ID FROM XIAOMI_MANUAL_SAMPLE)",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) deviceIds += cursor.getLong(0)
        }
        if (deviceIds.isEmpty()) throw ExportProblem("В экспорте нет устройства Xiaomi. Проверьте, что выбрана база Gadgetbridge с данными браслета.")
        if (deviceIds.size != 1) throw ExportProblem("В базе несколько устройств Xiaomi. Для импорта нужна база с одним браслетом, чтобы не смешивать измерения.")
        val device = deviceIds.single().toString()
        val stress = mutableListOf<StressSample>()
        db.rawQuery(
            "SELECT TIMESTAMP, STRESS FROM XIAOMI_ACTIVITY_SAMPLE WHERE DEVICE_ID = ? AND TIMESTAMP >= ? AND TIMESTAMP <= ? AND STRESS IS NOT NULL ORDER BY TIMESTAMP",
            arrayOf(device, start.epochSecond.toString(), end.epochSecond.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val time = Instant.ofEpochSecond(cursor.getLong(0))
                val score = cursor.getInt(1)
                if (time >= start && time < end && score in 1..100) stress += StressSample(time, score)
            }
        }
        db.rawQuery(
            "SELECT TIMESTAMP, VALUE FROM XIAOMI_MANUAL_SAMPLE WHERE DEVICE_ID = ? AND TIMESTAMP >= ? AND TIMESTAMP <= ? AND TYPE = 19 AND VALUE IS NOT NULL ORDER BY TIMESTAMP",
            arrayOf(device, start.toEpochMilli().toString(), end.toEpochMilli().toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val time = Instant.ofEpochMilli(cursor.getLong(0))
                val score = cursor.getInt(1)
                if (time >= start && time < end && score in 1..100) stress += StressSample(time, score)
            }
        }
        return GadgetbridgeExtrasRead(stress.distinct().sortedBy { it.time })
    }

    private companion object {
        val cacheLock = Mutex()
        val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        val SQLITE_MIME_TYPES = setOf("application/x-sqlite3", "application/vnd.sqlite3", "application/x-sqlite")
    }
}
