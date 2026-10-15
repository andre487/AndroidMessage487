package life.andre.message487

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.io.File
import java.io.ByteArrayOutputStream
import android.util.AtomicFile
import java.util.Base64

private const val ENTRY_COLUMNS = "id,type,source_name,created_at,state,attempts,outcome,http_code"

enum class QueueState { PENDING, SENDING, RETRY, BLOCKED, ACCEPTED, HTTP_SUCCESS }

data class QueueEntry(
    val id: String,
    val type: String,
    val sourceName: String,
    val createdAt: Long,
    val state: QueueState,
    val attempts: Int,
    val outcome: String?,
    val httpCode: Int?,
) {
    val delivered: Boolean get() = state == QueueState.ACCEPTED || state == QueueState.HTTP_SUCCESS
}

/** json contains the request without images; images are streamed from private files. */
data class QueuedRequest(val url: String, val requireAck: Boolean, val json: String, val authToken: String, val images: List<NotificationImage> = emptyList()) {
    override fun toString(): String = "QueuedRequest(redacted)"
}
data class Attempt(val token: String, val entry: QueueEntry, val request: QueuedRequest)

fun deliveryQueueState(result: DeliveryResult): QueueState = when (result.status) {
    DeliveryStatus.ACCEPTED -> QueueState.ACCEPTED
    DeliveryStatus.HTTP_SUCCESS -> QueueState.HTTP_SUCCESS
    DeliveryStatus.NETWORK_ERROR, DeliveryStatus.TIMEOUT -> QueueState.RETRY
    DeliveryStatus.INVALID_ACK -> QueueState.BLOCKED
    DeliveryStatus.HTTP_ERROR -> if (result.httpCode in listOf(408, 425, 429) || result.httpCode in 500..599) {
        QueueState.RETRY
    } else QueueState.BLOCKED
}

class Outbox(context: Context, private val cipher: PayloadCipher) : SQLiteOpenHelper(context, "outbox.db", null, 4) {
    internal val files by lazy { File(context.noBackupFilesDir, "outbox").apply { mkdirs() } }
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE events (
            id TEXT PRIMARY KEY, type TEXT NOT NULL, source_name TEXT NOT NULL,
            created_at INTEGER NOT NULL, state TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
            outcome TEXT, http_code INTEGER, payload BLOB, payload_file TEXT, attempt_token TEXT
        )""")
        db.execSQL("CREATE INDEX events_state ON events(state)")
        createDeduplicationTable(db)
        createWindowTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createDeduplicationTable(db)
            db.execSQL("DROP TABLE IF EXISTS notifications")
        }
        if (oldVersion < 3) createWindowTable(db)
        if (oldVersion < 4) {
            val hasFileColumn = db.rawQuery("PRAGMA table_info(events)", null).use { cursor ->
                var found = false
                while (cursor.moveToNext()) if (cursor.getString(1) == "payload_file") found = true
                found
            }
            if (!hasFileColumn) db.execSQL("ALTER TABLE events ADD COLUMN payload_file TEXT")
        }
    }

    private fun createWindowTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE message_windows (
            fingerprint TEXT NOT NULL, occurred_at INTEGER NOT NULL,
            PRIMARY KEY (fingerprint, occurred_at)
        )""")
    }

    private fun createDeduplicationTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE message_fingerprints (fingerprint TEXT PRIMARY KEY)")
    }

    @Synchronized
    fun enqueue(event: MessageEvent, settings: ForwardingSettings): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        var saved: String? = null
        var committed = false
        try {
            // Test requests are deliberate user actions and are never content-deduplicated.
            val captured = event.messageType in setOf("sms", "notification")
            val occurredAt = if (captured) java.time.Instant.parse(event.occurredAt).toEpochMilli() else 0L
            val fingerprint = if (captured) digest(
                org.json.JSONArray().put(event.source.packageName).put(event.text).apply {
                    if (event.images.isNotEmpty()) put(notificationImagesChecksum(event.images))
                }.toString()
            ) else null
            val deduplication = when (event.messageType) {
                "sms" -> settings.smsDeduplication
                "notification" -> settings.notificationDeduplication
                else -> false
            }
            if (deduplication && fingerprint != null) {
                val window = settings.deduplicationWindowSeconds.toLong() * 1000
                db.rawQuery(
                    "SELECT 1 FROM message_windows WHERE fingerprint = ? AND occurred_at BETWEEN ? AND ?",
                    arrayOf(fingerprint, (occurredAt - window).toString(), (occurredAt + window).toString())
                ).use { if (it.moveToFirst()) return false }
                if (event.images.isEmpty()) {
                    // Old hashes cannot reveal their content; retain exact matching for pre-upgrade history.
                    val legacy = digest(org.json.JSONArray().put(event.source.packageName)
                        .put(java.time.Instant.parse(event.occurredAt).toString()).put(event.text).toString())
                    db.rawQuery("SELECT 1 FROM message_fingerprints WHERE fingerprint = ?", arrayOf(legacy)).use {
                        if (it.moveToFirst()) return false
                    }
                }
            }
            db.rawQuery("SELECT id FROM events WHERE id = ?", arrayOf(event.eventId)).use {
                if (it.moveToFirst()) return false
            }
            val envelope = JSONObject().put("url", settings.url).put("require_ack", settings.requireAck).put("auth_token", settings.authToken)
                .put("event", JSONObject(event.copy(images = emptyList()).toJson()))
            saved = saveSnapshot(event.eventId, envelope, event.images)
            db.insertOrThrow("events", null, ContentValues().apply {
                put("id", event.eventId)
                put("type", event.messageType)
                put("source_name", event.source.name)
                put("created_at", System.currentTimeMillis())
                put("state", QueueState.PENDING.name)
                put("payload_file", saved)
            })
            if (fingerprint != null) {
                db.execSQL("INSERT OR IGNORE INTO message_windows (fingerprint, occurred_at) VALUES (?, ?)", arrayOf(fingerprint, occurredAt))
            }
            db.setTransactionSuccessful()
            committed = true
        } finally {
            try { db.endTransaction() } catch (error: Exception) { committed = false; throw error }
            finally { if (!committed) saved?.let { File(files, it).deleteRecursively() } }
        }
        changed()
        return true
    }

    @Synchronized
    fun pendingIds(): List<String> = readableDatabase.rawQuery(
        "SELECT id FROM events WHERE state IN ('PENDING', 'SENDING', 'RETRY') ORDER BY created_at", null
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    @Synchronized
    fun entries(): List<QueueEntry> = readableDatabase.rawQuery(
        "SELECT $ENTRY_COLUMNS FROM events ORDER BY CASE WHEN state IN ('ACCEPTED', 'HTTP_SUCCESS') THEN 1 ELSE 0 END, created_at DESC LIMIT 200", null
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.entry()) } }

    @Synchronized
    fun pendingCount(): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM events WHERE state NOT IN ('ACCEPTED', 'HTTP_SUCCESS')", null
    ).use { it.moveToFirst(); it.getInt(0) }

    @Synchronized
    fun beginAttempt(id: String): Attempt? {
        val db = writableDatabase
        val (entry, stored) = db.rawQuery("SELECT $ENTRY_COLUMNS,payload_file FROM events WHERE id = ?", arrayOf(id)).use {
            if (!it.moveToFirst()) return null
            val entry = it.entry()
            if (entry.delivered || entry.state == QueueState.BLOCKED) return null
            entry to it.getString(it.getColumnIndexOrThrow("payload_file"))
        }
        var directory = stored?.let(::snapshotDirectory)
        val envelope = if (directory != null) JSONObject(cipher.decrypt(AtomicFile(File(directory, "request.enc")).readFully()))
            else JSONObject(cipher.decrypt(readLegacyPayload(id)))
        val authToken = envelope.getString("auth_token")
        require(validAuthToken(authToken)) { "Missing queued authentication token" }
        if (directory == null) {
            val event = envelope.getJSONObject("event")
            val old = event.optJSONArray("images")
            val legacyImages = mutableListOf<NotificationImage>()
            val temporary = File(files, "capture-${UUID.randomUUID()}").apply { mkdirs() }
            try {
                if (old != null) for (index in 0 until old.length()) {
                    val image = old.getJSONObject(index)
                    val file = File.createTempFile("image-", ".image", temporary)
                    Base64.getDecoder().wrap(image.getString("data_base64").byteInputStream()).use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    }
                    legacyImages += NotificationImage(image.getString("mime_type"), image.getInt("width"), image.getInt("height"),
                        file, image.optString("kind", "attachment"), presentation = image.optString("presentation", "file"))
                }
                event.remove("images")
                val name = saveSnapshot(id, envelope, legacyImages)
                try {
                    db.update("events", ContentValues().apply { put("payload_file", name); putNull("payload") }, "id = ?", arrayOf(id))
                } catch (error: Exception) { File(files, name).deleteRecursively(); throw error }
                directory = snapshotDirectory(name)
            } finally { temporary.deleteRecursively() }
        }
        val event = envelope.getJSONObject("event")
        val storedImages = event.optJSONArray("images")
        val images = if (storedImages == null) emptyList() else (0 until storedImages.length()).map { index ->
            val image = storedImages.getJSONObject(index)
            val name = image.getString("file")
            require(name == File(name).name && name.startsWith("image-")) { "Invalid image filename" }
            val file = File(requireNotNull(directory), name)
            require(file.isFile && file.length() == image.getLong("bytes")) { "Queued image is unavailable" }
            NotificationImage(image.getString("mime_type"), image.getInt("width"), image.getInt("height"), file,
                image.getString("kind"), image.getString("checksum"), image.optString("presentation", "file"))
        }
        event.remove("images")
        val request = QueuedRequest(envelope.getString("url"), envelope.getBoolean("require_ack"), event.toString(), authToken, images)
        val token = UUID.randomUUID().toString()
        db.execSQL("UPDATE events SET state = 'SENDING', attempts = attempts + 1, attempt_token = ? WHERE id = ?", arrayOf(token, id))
        changed()
        return Attempt(token, entry, request)
    }

    @Synchronized
    fun finish(id: String, token: String, result: DeliveryResult): QueueState {
        val state = deliveryQueueState(result)
        val values = ContentValues().apply {
            put("state", state.name)
            put("outcome", result.status.name)
            result.httpCode?.let { put("http_code", it) } ?: putNull("http_code")
            putNull("attempt_token")
            if (state == QueueState.ACCEPTED || state == QueueState.HTTP_SUCCESS) {
                putNull("payload"); putNull("payload_file")
            }
        }
        val updated = writableDatabase.update("events", values, "id = ? AND attempt_token = ?", arrayOf(id, token)) > 0
        if (updated && state in setOf(QueueState.ACCEPTED, QueueState.HTTP_SUCCESS)) {
            snapshotDirectory("event-${digest(id)}").deleteRecursively()
        }
        writableDatabase.execSQL("""DELETE FROM events WHERE state IN ('ACCEPTED', 'HTTP_SUCCESS') AND id NOT IN
            (SELECT id FROM events WHERE state IN ('ACCEPTED', 'HTTP_SUCCESS') ORDER BY created_at DESC LIMIT 100)""")
        changed()
        return state
    }

    @Synchronized
    fun blockUnreadable(id: String) {
        writableDatabase.execSQL("UPDATE events SET state = 'BLOCKED', outcome = 'LOCAL_ERROR', attempt_token = NULL WHERE id = ? AND (payload IS NOT NULL OR payload_file IS NOT NULL)", arrayOf(id))
        changed()
    }

    @Synchronized
    fun retry(id: String): Boolean {
        val changed = writableDatabase.update("events", ContentValues().apply {
            put("state", QueueState.PENDING.name)
            putNull("outcome")
        }, "id = ? AND state IN ('BLOCKED', 'RETRY', 'PENDING')", arrayOf(id)) > 0
        if (changed) changed()
        return changed
    }

    @Synchronized
    fun delete(id: String) {
        if (writableDatabase.delete("events", "id = ? AND state != 'SENDING'", arrayOf(id)) > 0) {
            snapshotDirectory("event-${digest(id)}").deleteRecursively()
        }
        changed()
    }

    private fun snapshotDirectory(name: String): File {
        require(name.matches(Regex("event-[0-9a-f]{64}"))) { "Invalid snapshot directory" }
        return File(files, name)
    }

    private fun saveSnapshot(id: String, envelope: JSONObject, images: List<NotificationImage>): String {
        val directory = snapshotDirectory("event-${digest(id)}")
        // The caller has verified there is no referenced snapshot for this id. A previous crash may have left one before its DB commit.
        if (directory.exists()) check(directory.deleteRecursively()) { "Cannot remove abandoned snapshot" }
        val source = images.firstOrNull()?.file?.parentFile
        val moved = source != null && source.parentFile == files && source.name.startsWith("capture-") &&
            images.all { it.file.parentFile == source } && source.renameTo(directory)
        try {
            if (!moved) check(directory.mkdirs()) { "Cannot create snapshot directory" }
            val metadata = org.json.JSONArray()
            images.forEachIndexed { index, image ->
                val file = if (moved) File(directory, image.file.name) else File(directory, "image-$index").also { image.file.copyTo(it) }
                metadata.put(image.toJson().put("file", file.name).put("bytes", file.length()).put("checksum", image.checksum))
            }
            if (images.isNotEmpty()) envelope.getJSONObject("event").put("images", metadata)
            val atomic = AtomicFile(File(directory, "request.enc"))
            val encrypted = cipher.encrypt(envelope.toString())
            val output = atomic.startWrite()
            try { output.write(encrypted); atomic.finishWrite(output) }
            catch (error: Exception) { atomic.failWrite(output); throw error }
            return directory.name
        } catch (error: Exception) { directory.deleteRecursively(); throw error }
    }

    private fun readLegacyPayload(id: String): ByteArray {
        // Legacy bodies can exceed CursorWindow. Read only a small SQL substr in each cursor.
        val output = ByteArrayOutputStream()
        var offset = 1
        while (true) {
            val chunk = readableDatabase.rawQuery("SELECT substr(payload, ?, 262144) FROM events WHERE id = ?", arrayOf(offset.toString(), id)).use {
                check(it.moveToFirst() && !it.isNull(0)) { "Missing queued payload" }
                it.getBlob(0)
            }
            output.write(chunk)
            if (chunk.size < 262144) return output.toByteArray()
            offset += chunk.size
        }
    }

    @Synchronized
    fun cleanupFiles() {
        val referenced = readableDatabase.rawQuery("SELECT payload_file FROM events WHERE payload_file IS NOT NULL", null).use {
            buildSet { while (it.moveToNext()) add(it.getString(0)) }
        }
        val cutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(5)
        files.listFiles()?.filter { it.isDirectory && it.name !in referenced && it.lastModified() < cutoff }
            ?.forEach { it.deleteRecursively() }
    }

    private fun changed() { mutableRevision.value += 1 }

    private fun Cursor.entry() = QueueEntry(
        id = getString(getColumnIndexOrThrow("id")),
        type = getString(getColumnIndexOrThrow("type")),
        sourceName = getString(getColumnIndexOrThrow("source_name")),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        state = QueueState.valueOf(getString(getColumnIndexOrThrow("state"))),
        attempts = getInt(getColumnIndexOrThrow("attempts")),
        outcome = getString(getColumnIndexOrThrow("outcome")),
        httpCode = getColumnIndexOrThrow("http_code").let { if (isNull(it)) null else getInt(it) },
    )
}
