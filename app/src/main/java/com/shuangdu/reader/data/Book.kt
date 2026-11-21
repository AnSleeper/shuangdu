package com.shuangdu.reader.data

import org.json.JSONObject

enum class BookFormat { TXT, EPUB }

/**
 * 一本书的元数据 + 阅读进度。
 * 内容本身存放在 files/books/<id>/content.txt，章节索引在同目录 index.json。
 */
data class Book(
    val id: String,
    val title: String,
    val author: String,
    val uri: String,
    val format: BookFormat,
    val size: Long,
    val hasCover: Boolean,
    val addedAt: Long,
    val lastReadAt: Long,
    val chapterIndex: Int,
    val charOffset: Int,
    val chapterTitle: String,
    val totalChapters: Int,
    val finished: Boolean = false,
    val groupId: String? = null,
    /**
     * 听书音频状态（落盘，书架直接读，不再为了一个角标去解析整本 tts 索引）：
     * -1 = 还没算过（老数据），0 = 没转，1 = 部分转好，2 = 整本转完
     */
    val audioState: Int = -1
) {
    val formatName: String get() = if (format == BookFormat.EPUB) "EPUB" else "TXT"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("author", author)
        put("uri", uri)
        put("format", format.name)
        put("size", size)
        put("hasCover", hasCover)
        put("addedAt", addedAt)
        put("lastReadAt", lastReadAt)
        put("chapterIndex", chapterIndex)
        put("charOffset", charOffset)
        put("chapterTitle", chapterTitle)
        put("totalChapters", totalChapters)
        put("finished", finished)
        put("groupId", groupId ?: "")
        put("audio", audioState)
    }

    companion object {
        fun fromJson(o: JSONObject): Book = Book(
            id = o.optString("id"),
            title = o.optString("title", "未命名"),
            author = o.optString("author", ""),
            uri = o.optString("uri", ""),
            format = runCatching { BookFormat.valueOf(o.optString("format", "TXT")) }
                .getOrDefault(BookFormat.TXT),
            size = o.optLong("size", 0L),
            hasCover = o.optBoolean("hasCover", false),
            addedAt = o.optLong("addedAt", 0L),
            lastReadAt = o.optLong("lastReadAt", 0L),
            chapterIndex = o.optInt("chapterIndex", 0),
            charOffset = o.optInt("charOffset", 0),
            chapterTitle = o.optString("chapterTitle", ""),
            totalChapters = o.optInt("totalChapters", 1),
            finished = o.optBoolean("finished", false),
            groupId = o.optString("groupId", "").ifBlank { null },
            audioState = o.optInt("audio", -1)
        )
    }
}

/** 书架分组 */
data class BookGroup(
    val id: String,
    val name: String,
    val createdAt: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject): BookGroup = BookGroup(
            id = o.optString("id"),
            name = o.optString("name", "未命名分组"),
            createdAt = o.optLong("createdAt", 0L)
        )
    }
}

/** 章节在 content.txt 中的字节区间 */
data class Chapter(
    val title: String,
    val start: Long,
    val end: Long
) {
    val length: Long get() = (end - start).coerceAtLeast(0L)
}
