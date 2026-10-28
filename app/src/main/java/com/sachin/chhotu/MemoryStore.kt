package com.sachin.chhotu

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Chhotu ki yaaddasht. Sirf likhna (append) aur padhna hai.
 * Is class me jaan-boojh kar koi delete / update / reset function NAHI hai.
 */
data class Memory(val id: Long, val time: Long, val type: String, val text: String)

class MemoryStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "chhotu_memory.db", null, 1) {

    private val appContext = context.applicationContext

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE memories (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, type TEXT NOT NULL, text TEXT NOT NULL)"
        )
        // Database ke level par bhi delete aur update band
        db.execSQL("CREATE TRIGGER no_delete BEFORE DELETE ON memories BEGIN SELECT RAISE(ABORT, 'Chhotu memory is permanent'); END")
        db.execSQL("CREATE TRIGGER no_update BEFORE UPDATE ON memories BEGIN SELECT RAISE(ABORT, 'Chhotu memory is permanent'); END")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Kabhi table drop nahi karna. Future migrations sirf naye columns/tables jodenge.
    }

    @Synchronized
    fun add(type: String, text: String): Long {
        val v = ContentValues().apply {
            put("time", System.currentTimeMillis())
            put("type", type)
            put("text", text)
        }
        return writableDatabase.insert("memories", null, v)
    }

    fun all(): List<Memory> = query("SELECT id, time, type, text FROM memories ORDER BY id ASC", emptyArray())

    fun recent(type: String, limit: Int): List<Memory> =
        query("SELECT id, time, type, text FROM memories WHERE type = ? ORDER BY id DESC LIMIT $limit", arrayOf(type)).reversed()

    fun count(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM memories", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    private fun query(sql: String, args: Array<String>): List<Memory> {
        val out = ArrayList<Memory>()
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                out.add(Memory(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3)))
            }
        }
        return out
    }

    fun toJson(): String {
        val arr = JSONArray()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        for (m in all()) {
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("time", fmt.format(Date(m.time)))
                put("type", m.type)
                put("text", m.text)
            })
        }
        return JSONObject().apply {
            put("app", "Chhotu")
            put("exported_at", fmt.format(Date()))
            put("count", arr.length())
            put("memories", arr)
        }.toString(2)
    }

    /** Downloads/Chhotu folder me poori yaaddasht ki JSON copy. Return: file ka naam ya error. */
    fun exportToDownloads(): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "chhotu_memory_$stamp.json"
        val bytes = toJson().toByteArray(Charsets.UTF_8)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Chhotu")
                }
                val uri = appContext.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return "Error: file nahi bani"
                appContext.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                "Downloads/Chhotu/$name"
            } else {
                val dir = File(appContext.getExternalFilesDir(null), "Chhotu")
                dir.mkdirs()
                val f = File(dir, name)
                f.writeBytes(bytes)
                f.absolutePath
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    companion object {
        @Volatile private var instance: MemoryStore? = null
        fun get(context: Context): MemoryStore =
            instance ?: synchronized(this) { instance ?: MemoryStore(context).also { instance = it } }
    }
}
