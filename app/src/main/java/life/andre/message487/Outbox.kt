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

data class QueuedRequest(val url: String, val requireAck: Boolean, val json: String)
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

class Outbox(context: Context, private val cipher: PayloadCipher) : SQLiteOpenHelper(context, "outbox.db", null, 1) {
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE events (
            id TEXT PRIMARY KEY, type TEXT NOT NULL, source_name TEXT NOT NULL,
            created_at INTEGER NOT NULL, state TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
            outcome TEXT, http_code INTEGER, payload BLOB, attempt_token TEXT
        )""")
        db.execSQL("CREATE INDEX events_state ON events(state)")
        db.execSQL("CREATE TABLE notifications (notification_key TEXT PRIMARY KEY, fingerprint TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Missing forward migration from $oldVersion to $newVersion")
    }

    @Synchronized
    fun enqueue(event: MessageEvent, settings: ForwardingSettings, notificationKey: String? = null, fingerprint: String? = null): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (notificationKey != null) {
                db.rawQuery("SELECT fingerprint FROM notifications WHERE notification_key = ?", arrayOf(notificationKey)).use {
                    if (it.moveToFirst() && it.getString(0) == fingerprint) return false
                }
            }
            db.rawQuery("SELECT id FROM events WHERE id = ?", arrayOf(event.eventId)).use {
                if (it.moveToFirst()) return false
            }
            val envelope = JSONObject().put("url", settings.url).put("require_ack", settings.requireAck)
                .put("event", JSONObject(event.toJson())).toString()
            db.insertOrThrow("events", null, ContentValues().apply {
                put("id", event.eventId)
                put("type", event.messageType)
                put("source_name", event.source.name)
                put("created_at", System.currentTimeMillis())
                put("state", QueueState.PENDING.name)
                put("payload", cipher.encrypt(envelope))
            })
            if (notificationKey != null) {
                db.insertWithOnConflict("notifications", null, ContentValues().apply {
                    put("notification_key", notificationKey)
                    put("fingerprint", requireNotNull(fingerprint))
                }, SQLiteDatabase.CONFLICT_REPLACE)
                db.execSQL("DELETE FROM notifications WHERE rowid NOT IN (SELECT rowid FROM notifications ORDER BY rowid DESC LIMIT 2000)")
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changed()
        return true
    }

    @Synchronized
    fun forgetNotification(key: String) {
        writableDatabase.delete("notifications", "notification_key = ?", arrayOf(key))
    }

    @Synchronized
    fun pendingIds(): List<String> = readableDatabase.rawQuery(
        "SELECT id FROM events WHERE state IN ('PENDING', 'SENDING', 'RETRY') ORDER BY created_at", null
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    @Synchronized
    fun entries(): List<QueueEntry> = readableDatabase.rawQuery(
        "SELECT * FROM events ORDER BY CASE WHEN state IN ('ACCEPTED', 'HTTP_SUCCESS') THEN 1 ELSE 0 END, created_at DESC LIMIT 200", null
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.entry()) } }

    @Synchronized
    fun pendingCount(): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM events WHERE state NOT IN ('ACCEPTED', 'HTTP_SUCCESS')", null
    ).use { it.moveToFirst(); it.getInt(0) }

    @Synchronized
    fun beginAttempt(id: String): Attempt? {
        val db = writableDatabase
        val (entry, request) = db.rawQuery("SELECT * FROM events WHERE id = ?", arrayOf(id)).use {
            if (!it.moveToFirst()) return null
            val entry = it.entry()
            if (entry.delivered || entry.state == QueueState.BLOCKED) return null
            val envelope = JSONObject(cipher.decrypt(it.getBlob(it.getColumnIndexOrThrow("payload"))))
            entry to QueuedRequest(envelope.getString("url"), envelope.getBoolean("require_ack"), envelope.getJSONObject("event").toString())
        }
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
            if (state == QueueState.ACCEPTED || state == QueueState.HTTP_SUCCESS) putNull("payload")
        }
        writableDatabase.update("events", values, "id = ? AND attempt_token = ?", arrayOf(id, token))
        writableDatabase.execSQL("""DELETE FROM events WHERE state IN ('ACCEPTED', 'HTTP_SUCCESS') AND id NOT IN
            (SELECT id FROM events WHERE state IN ('ACCEPTED', 'HTTP_SUCCESS') ORDER BY created_at DESC LIMIT 100)""")
        changed()
        return state
    }

    @Synchronized
    fun blockUnreadable(id: String) {
        writableDatabase.execSQL("UPDATE events SET state = 'BLOCKED', outcome = 'LOCAL_ERROR', attempt_token = NULL WHERE id = ? AND payload IS NOT NULL", arrayOf(id))
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
        writableDatabase.delete("events", "id = ? AND state != 'SENDING'", arrayOf(id))
        changed()
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
