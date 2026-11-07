package com.shuangdu.reader.ui.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 阅读器运行态：当前章节、当前页、以及前中后三章的排版缓存。
 * 翻页时如果跨章，邻居页已经排好，直接平移过去，不会有白屏。
 */
class ReaderEngine(initialChapter: Int) {

    var chapterIndex by mutableStateOf(initialChapter)
        private set

    var page by mutableIntStateOf(0)

    var layouts by mutableStateOf<Map<Int, PaginatedChapter>>(emptyMap())
        private set

    /** 排版参数指纹，变了就要全部重排 */
    var styleToken: Any? = null

    fun clearLayouts() {
        layouts = emptyMap()
    }

    var chapterTexts by mutableStateOf<Map<Int, String>>(emptyMap())
        private set

    var chapterTitles by mutableStateOf<List<String>>(emptyList())
        private set

    var loading by mutableStateOf(true)

    var error by mutableStateOf<String?>(null)

    val current: PaginatedChapter? get() = layouts[chapterIndex]

    fun currentPageCount(): Int = current?.pageCount ?: 0

    fun titleOf(index: Int): String = chapterTitles.getOrNull(index).orEmpty()

    fun setChapters(titles: List<String>) {
        chapterTitles = titles
    }

    fun putLayout(index: Int, layout: PaginatedChapter) {
        val map = layouts.toMutableMap()
        map[index] = layout
        // 只保留前后各一章
        val keep = map.keys.filter { it < chapterIndex - 1 || it > chapterIndex + 1 }
        keep.forEach { map.remove(it) }
        layouts = map
    }

    fun putText(index: Int, text: String) {
        if (chapterTexts.containsKey(index)) return
        val map = chapterTexts.toMutableMap()
        map[index] = text
        val drop = map.keys.filter { it < chapterIndex - 1 || it > chapterIndex + 1 }
        drop.forEach { map.remove(it) }
        chapterTexts = map
    }

    fun textOf(index: Int): String? = chapterTexts[index]
    fun moveToChapter(index: Int, targetPage: Int) {
        chapterIndex = index
        page = targetPage
    }

    fun ensureLayout(index: Int, builder: (String) -> PaginatedChapter?): PaginatedChapter? {
        layouts[index]?.let { return it }
        val text = chapterTexts[index] ?: return null
        val layout = builder(text) ?: return null
        putLayout(index, layout)
        return layout
    }

    fun pageOf(chapter: Int, charOffset: Int): Int = layouts[chapter]?.pageOfCharOffset(charOffset) ?: 0

    fun charOffsetNow(): Int = current?.charOffsetAtPage(page) ?: 0

    fun hasNextPage(): Boolean {
        val count = currentPageCount()
        return page + 1 < count || chapterIndex + 1 < chapterTitles.size
    }

    fun hasPrevPage(): Boolean = page > 0 || chapterIndex > 0
}
