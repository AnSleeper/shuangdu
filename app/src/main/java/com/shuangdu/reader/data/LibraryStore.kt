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
import org.json.JSONObject
import java.io.File

/** 书架索引 + 分组，落盘在 filesDir/library.json */
class LibraryStore(context: Context) {

    private val file = File(context.filesDir, "library.json")
    private val _books = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = _books.asStateFlow()

    private val _groups = MutableStateFlow<List<BookGroup>>(emptyList())
    val groups: StateFlow<List<BookGroup>> = _groups.asStateFlow()

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
            val trimmed = text.trim()
            val booksJson: JSONArray
            if (trimmed.startsWith("[")) {
                // 老格式：只有书
                booksJson = JSONArray(trimmed)
            } else {
                val root = JSONObject(trimmed)
                booksJson = root.optJSONArray("books") ?: JSONArray()
                val groupsJson = root.optJSONArray("groups")
                if (groupsJson != null) {
                    val gl = ArrayList<BookGroup>(groupsJson.length())
                    for (i in 0 until groupsJson.length()) {
                        runCatching { gl.add(BookGroup.fromJson(groupsJson.getJSONObject(i))) }
                    }
                    _groups.value = gl
                }
            }
            val list = ArrayList<Book>(booksJson.length())
            for (i in 0 until booksJson.length()) {
                runCatching { list.add(Book.fromJson(booksJson.getJSONObject(i))) }
            }
            _books.value = sortBooks(list)
        }
    }

    private fun sortBooks(list: List<Book>) =
        list.sortedWith(compareByDescending<Book> { it.lastReadAt }.thenByDescending { it.addedAt })

    fun find(id: String): Book? = _books.value.firstOrNull { it.id == id }

    fun groupOf(book: Book): BookGroup? = book.groupId?.let { gid -> _groups.value.firstOrNull { it.id == gid } }

    fun upsert(book: Book, save: Boolean = true) {
        val list = _books.value.toMutableList()
        val idx = list.indexOfFirst { it.id == book.id }
        if (idx >= 0) list[idx] = book else list.add(0, book)
        _books.value = sortBooks(list)
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

    fun removeMany(ids: Collection<String>) {
        val set = ids.toSet()
        _books.value = _books.value.filterNot { it.id in set }
        saveAsync()
    }

    fun assignGroup(ids: Collection<String>, groupId: String?) {
        val set = ids.toSet()
        _books.value = sortBooks(_books.value.map { if (it.id in set) it.copy(groupId = groupId) else it })
        saveAsync()
    }

    fun createGroup(name: String): BookGroup {
        val g = BookGroup(
            id = java.util.UUID.randomUUID().toString().replace("-", "").take(12),
            name = name.trim().ifBlank { "未命名分组" },
            createdAt = System.currentTimeMillis()
        )
        _groups.value = _groups.value + g
        saveAsync()
        return g
    }

    fun renameGroup(id: String, name: String) {
        _groups.value = _groups.value.map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it }
        saveAsync()
    }

    fun deleteGroup(id: String) {
        _groups.value = _groups.value.filterNot { it.id == id }
        _books.value = sortBooks(_books.value.map { if (it.groupId == id) it.copy(groupId = null) else it })
        saveAsync()
    }

    fun saveAsync() {
        scope.launch { persist() }
    }

    suspend fun persist() = mutex.withLock {
        runCatching {
            val root = JSONObject()
            val arr = JSONArray()
            _books.value.forEach { arr.put(it.toJson()) }
            root.put("books", arr)
            val garr = JSONArray()
            _groups.value.forEach { garr.put(it.toJson()) }
            root.put("groups", garr)
            val tmp = File(file.parentFile, "library.json.tmp")
            tmp.writeText(root.toString())
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }
}
