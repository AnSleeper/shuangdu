package com.shuangdu.reader

import com.shuangdu.reader.format.ChapterSplitter
import com.shuangdu.reader.format.EpubParser
import com.shuangdu.reader.format.TextCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 用本地测试小说验证「导入解析」这条链路：编码识别 -> 分章 -> 章节正文。
 * 只校验结构（章节数、标题非空、正文长度），不打印正文内容。
 */
class ImportParseTest {

    private val sampleDir = File("E:/AI project/其他插件/爽读爽听app/测试用小说")

    @Test
    fun parseAllSamples() {
        if (!sampleDir.isDirectory) {
            println("跳过：测试目录不存在")
            return
        }
        val files = sampleDir.listFiles { f -> f.isFile && (f.extension.equals("txt", true) || f.extension.equals("epub", true)) }
            ?.sortedBy { it.name } ?: emptyList()
        assertTrue("测试目录里没有可解析的文件", files.isNotEmpty())

        for (f in files) {
            val bytes = f.readBytes()
            val isEpub = f.extension.equals("epub", true)

            val chapters: List<ChapterSplitter.Split>
            if (isEpub) {
                val parsed = EpubParser.parse(f)
                chapters = parsed.chapters
                println("EPUB  ${f.name}")
                println("      title=${parsed.title} author=${parsed.author} chapters=${chapters.size} cover=${parsed.cover?.size ?: 0}B")
            } else {
                val charset = TextCodec.detect(bytes)
                val text = TextCodec.decode(bytes)
                chapters = ChapterSplitter.split(text)
                println("TXT   ${f.name}")
                println("      charset=$charset chars=${text.length} chapters=${chapters.size}")
                assertTrue("$f 解码后含 CR", !text.contains('\r'))
            }

            assertTrue("${f.name} 没有解析出章节", chapters.isNotEmpty())
            assertTrue("${f.name} 存在空标题章节", chapters.all { it.title.isNotBlank() })

            val total = chapters.sumOf { it.body.length }
            assertTrue("${f.name} 正文总长为 0", total > 0)

            val empty = chapters.count { it.body.isBlank() }
            println("      titles=${chapters.take(3).joinToString(" | ") { it.title }} ... totalChars=$total emptyChapters=$empty")

            chapters.forEachIndexed { i, c ->
                assertEquals("${f.name} 第${i + 1}章标题带换行", false, c.title.contains('\n'))
            }
        }
    }
}
