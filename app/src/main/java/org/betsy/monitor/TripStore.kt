package org.betsy.monitor

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.Writer

data class RecordedTrip(
    val id: Long,
    val started: Long,
    val ended: Long?,
    val lastSample: Long?,
    val demo: Boolean,
    val adapter: String,
    val layout: String,
    val count: Int,
    val snapshot: TripSnapshot,
    val reason: String,
)

/** Private, offline, transactional trip journal. Call writes on the application's I/O worker. */
class TripStore(
    context: Context,
    databaseName: String = "prius-trips.db",
) {
    private val helper =
        object : SQLiteOpenHelper(context, databaseName, null, 1) {
            init {
                setWriteAheadLoggingEnabled(true)
            }

            override fun onConfigure(db: SQLiteDatabase) {
                db.setForeignKeyConstraintsEnabled(true)
            }

            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE trips (
                id INTEGER PRIMARY KEY AUTOINCREMENT, started INTEGER NOT NULL, ended INTEGER, last_sample INTEGER,
                demo INTEGER NOT NULL, adapter TEXT NOT NULL, layout TEXT NOT NULL,
                sample_count INTEGER NOT NULL DEFAULT 0, snapshot TEXT NOT NULL, reason TEXT NOT NULL DEFAULT ''
            )""",
                )
                db.execSQL(
                    """CREATE TABLE samples (
                id INTEGER PRIMARY KEY AUTOINCREMENT, trip_id INTEGER NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
                timestamp INTEGER NOT NULL, payload TEXT NOT NULL
            )""",
                )
                db.execSQL("CREATE INDEX samples_trip_time ON samples(trip_id, id)")
            }

            override fun onUpgrade(
                db: SQLiteDatabase,
                oldVersion: Int,
                newVersion: Int,
            ) {
                error("Migrare neimplementată")
            }
        }

    fun recoverInterrupted() {
        helper.writableDatabase.execSQL(
            "UPDATE trips SET ended=COALESCE(last_sample,started),reason='Procesul aplicației a fost întrerupt' WHERE ended IS NULL",
        )
    }

    fun start(
        demo: Boolean,
        adapter: String,
        layout: String,
        now: Long = System.currentTimeMillis(),
    ): Long =
        helper.writableDatabase.insertOrThrow(
            "trips",
            null,
            ContentValues().apply {
                put("started", now)
                put("demo", if (demo) 1 else 0)
                put("adapter", adapter)
                put("layout", layout)
                put("snapshot", SampleCodec.tripJson(TripSnapshot()).toString())
            },
        )

    fun record(
        id: Long,
        sample: LiveSample,
    ) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.insertOrThrow(
                "samples",
                null,
                ContentValues().apply {
                    put("trip_id", id)
                    put("timestamp", sample.time)
                    put("payload", SampleCodec.json(sample))
                },
            )
            db.execSQL(
                "UPDATE trips SET sample_count=sample_count+1,last_sample=?,snapshot=? WHERE id=?",
                arrayOf(sample.time, SampleCodec.tripJson(sample.trip).toString(), id),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun finish(
        id: Long,
        reason: String,
        now: Long = System.currentTimeMillis(),
    ) {
        helper.writableDatabase.update(
            "trips",
            ContentValues().apply {
                put("ended", now)
                put("reason", reason)
            },
            "id=?",
            arrayOf(id.toString()),
        )
    }

    private fun trip(c: Cursor): RecordedTrip {
        fun string(key: String) = c.getString(c.getColumnIndexOrThrow(key))

        fun long(key: String) = c.getLong(c.getColumnIndexOrThrow(key))

        fun nullableLong(key: String): Long? = c.getColumnIndexOrThrow(key).let { if (c.isNull(it)) null else c.getLong(it) }
        return RecordedTrip(
            long("id"),
            long("started"),
            nullableLong("ended"),
            nullableLong("last_sample"),
            long("demo") == 1L,
            string("adapter"),
            string("layout"),
            long("sample_count").toInt(),
            SampleCodec.tripFrom(JSONObject(string("snapshot"))),
            string("reason"),
        )
    }

    fun list(limit: Int = 100): List<RecordedTrip> {
        val result = mutableListOf<RecordedTrip>()
        helper.readableDatabase.query("trips", null, null, null, null, null, "id DESC", limit.coerceIn(1, 1000).toString()).use { c ->
            while (c.moveToNext()) result += trip(c)
        }
        return result
    }

    fun get(id: Long): RecordedTrip? =
        helper.readableDatabase.query("trips", null, "id=?", arrayOf(id.toString()), null, null, null).use { c ->
            if (c.moveToFirst()) trip(c) else null
        }

    fun samples(
        id: Long,
        limit: Int = 1800,
    ): List<LiveSample> {
        val result = mutableListOf<LiveSample>()
        helper.readableDatabase
            .query(
                "samples",
                arrayOf("payload"),
                "trip_id=?",
                arrayOf(id.toString()),
                null,
                null,
                "id DESC",
                limit.coerceIn(1, 10000).toString(),
            ).use { c ->
                while (c.moveToNext()) result += SampleCodec.fromJson(c.getString(0))
            }
        return result.reversed()
    }

    fun exportCsv(
        id: Long,
        writer: Writer,
    ) {
        writer.appendLine("trip_id;demo;" + SampleCodec.csvHeader)
        val trip = get(id) ?: error("Călătorie inexistentă")
        helper.readableDatabase.query("samples", arrayOf("payload"), "trip_id=?", arrayOf(id.toString()), null, null, "id ASC").use { c ->
            while (c.moveToNext()) writer.appendLine("$id;${trip.demo};" + SampleCodec.csvRow(SampleCodec.fromJson(c.getString(0))))
        }
    }

    fun delete(id: Long) {
        helper.writableDatabase.delete("trips", "id=?", arrayOf(id.toString()))
    }

    fun close() {
        helper.close()
    }
}
