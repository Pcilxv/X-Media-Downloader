package cn.xinyu.xmedia.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import cn.xinyu.xmedia.core.MediaKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HistoryEntry(val id: Long, val uri: String, val postUrl: String, val title: String, val author: String,
                        val kind: MediaKind, val timestamp: Long, val quality: String)

class HistoryStore(context: Context, databaseName: String = "history.db") : SQLiteOpenHelper(context, databaseName, null, 1) {
    private val mutable = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries = mutable.asStateFlow()
    init { refresh() }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, uri TEXT NOT NULL, post_url TEXT NOT NULL, title TEXT NOT NULL, author TEXT NOT NULL, kind TEXT NOT NULL, timestamp INTEGER NOT NULL, quality TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    @Synchronized fun add(uri: String, postUrl: String, title: String, author: String, kind: MediaKind, quality: String) {
        val values = ContentValues().apply {
            put("uri", uri); put("post_url", postUrl); put("title", title); put("author", author)
            put("kind", kind.name); put("timestamp", System.currentTimeMillis()); put("quality", quality)
        }
        check(writableDatabase.insertOrThrow("history", null, values) > 0) { "保存历史记录失败。" }
        refresh()
    }
    @Synchronized fun refresh() {
        val items = mutableListOf<HistoryEntry>()
        readableDatabase.query("history", null, null, null, null, null, "timestamp DESC, id DESC").use { c ->
            while (c.moveToNext()) items += HistoryEntry(c.getLong(c.getColumnIndexOrThrow("id")),
                c.getString(c.getColumnIndexOrThrow("uri")), c.getString(c.getColumnIndexOrThrow("post_url")),
                c.getString(c.getColumnIndexOrThrow("title")), c.getString(c.getColumnIndexOrThrow("author")),
                MediaKind.valueOf(c.getString(c.getColumnIndexOrThrow("kind"))),
                c.getLong(c.getColumnIndexOrThrow("timestamp")), c.getString(c.getColumnIndexOrThrow("quality")))
        }
        mutable.value = items
    }
    @Synchronized fun removeRecord(id: Long) {
        writableDatabase.delete("history", "id = ?", arrayOf(id.toString()))
        refresh()
    }
}
