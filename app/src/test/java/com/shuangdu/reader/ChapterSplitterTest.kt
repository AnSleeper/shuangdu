package com.shuangdu.reader

import com.shuangdu.reader.format.ChapterSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 章节识别规则的单测：重点覆盖 EP 单集式标题（EP0004 / EP.4 / ep-12 / EP 7 标题），
 * 以及几条不该被误判成章节的正文行。
 */
class ChapterSplitterTest {

    private fun body(n: Int = 3): String =
        (1..n).joinToString("\n") { "这是第${it}段正文，用来占位。" }

    private fun splitOf(vararg lines: String) = ChapterSplitter.split(lines.joinToString("\n"))

    @Test
    fun epTitlesAreChapters() {
        val text = listOf(
            "EP0001", body(), "EP0002", body(), "EP0003", body()
        ).joinToString("\n")
        val chapters = ChapterSplitter.split(text)
        assertEquals(3, chapters.size)
        assertEquals(listOf("EP0001", "EP0002", "EP0003"), chapters.map { it.title })
        assertTrue(chapters.all { it.body.isNotBlank() })
    }

    @Test
    fun epTitleVariants() {
        val text = listOf(
            "EP.4", body(),
            "ep-12", body(),
            "Ep 7 初次见面", body(),
            "EP　8", body()   // 全角空格分隔
        ).joinToString("\n")
        val chapters = ChapterSplitter.split(text)
        assertEquals(4, chapters.size)
        assertEquals(listOf("EP.4", "ep-12", "Ep 7 初次见面", "EP　8"), chapters.map { it.title })
    }

    @Test
    fun epInsideBodyIsNotChapter() {
        val text = listOf(
            "第一章 开端",
            "他说这是EP2的剧情，别当真。",
            "EP2的剧情很精彩",          // 以 EP 开头，但数字后面不是空格，不该算标题
            "后来又在EP 3里出现过一次。", // 句末有句号，不该算标题
            body(),
            "第二章 结束",
            body()
        ).joinToString("\n")
        val chapters = ChapterSplitter.split(text)
        assertEquals(2, chapters.size)
        assertEquals(listOf("第一章 开端", "第二章 结束"), chapters.map { it.title })
    }

    @Test
    fun existingRulesStillWork() {
        val text = listOf(
            "第一章 开端", body(),
            "番外 一", body(),
            "Chapter 3", body(),
            "12、小节", body(),
            "（5）尾声", body()
        ).joinToString("\n")
        val chapters = ChapterSplitter.split(text)
        assertEquals(5, chapters.size)
        assertEquals(
            listOf("第一章 开端", "番外 一", "Chapter 3", "12、小节", "（5）尾声"),
            chapters.map { it.title }
        )
    }
}
