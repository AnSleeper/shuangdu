package com.shuangdu.reader.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.io.File

/** 书架索引，落盘在 filesDir/library.json */
class LibraryStore(context: Context) {

    private val file = File(context.filesDir, "library.json")
    private val _books = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = _books.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    init {
        load()
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val text = file.readText()
            if (text.isBlank()) return
            val arr = JSONArray(text)
            val list = ArrayList<Book>(arr.length())
            for (i in 0 until arr.length()) {
                runCatching { list.add(Book.fromJson(arr.getJSONObject(i))) }
            }
            _books.value = list.sortedWith(compareByDescending<Book> { it.lastReadAt }.thenByDescending { it.addedAt })
        }
    }

    fun find(id: String): Book? = _books.value.firstOrNull { it.id == id }

    fun upsert(book: Book, save: Boolean = true) {
        val list = _books.value.toMutableList()
        val idx = list.indexOfFirst { it.id == book.id }
        if (idx >= 0) list[idx] = book else list.add(0, book)
        _books.value = list.sortedWith(compareByDescending<Book> { it.lastReadAt }.thenByDescending { it.addedAt })
        if (save) saveAsync()
    }

    fun updateProgress(id: String, chapterIndex: Int, charOffset: Int, chapterTitle: String, totalChapters: Int) {
        val book = find(id) ?: return
        val updated = book.copy(
            chapterIndex = chapterIndex,
            charOffset = charOffset,
            chapterTitle = chapterTitle,
            totalChapters = if (totalChapters > 0) totalChapters else book.totalChapters,
            lastReadAt = System.currentTimeMillis()
        )
        upsert(updated)
    }

    fun remove(id: String) {
        _books.value = _books.value.filterNot { it.id == id }
        saveAsync()
    }

    fun saveAsync() {
        scope.launch { persist() }
    }

    suspend fun persist() = mutex.withLock {
        runCatching {
            val arr = JSONArray()
            _books.value.forEach { arr.put(it.toJson()) }
            val tmp = File(file.parentFile, "library.json.tmp")
            tmp.writeText(arr.toString())
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }
}
