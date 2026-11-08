package com.shuangdu.reader.tts

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.os.Environment
import com.shuangdu.reader.data.Book
import com.shuangdu.reader.data.BookRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 把整本书已转好的音频按章节顺序合并导出成一个 MP3 */
class TtsExporter(
    private val context: Context,
    private val repo: BookRepository,
    private val converter: TtsConverter
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** onResult(message)：成功时 message 为空串，失败为原因 */
    fun exportBook(book: Book, onResult: (String) -> Unit) {
        scope.launch {
            val msg = withContext(Dispatchers.IO) { runCatching { doExport(book) }.getOrElse { "失败：${it.message}" } }
            withContext(Dispatchers.Main) { onResult(msg) }
        }
    }

    private fun doExport(book: Book): String {
        val index = converter.indexOf(book)
        val total = repo.chapterCount(book)
        val safe = book.title.replace(Regex("[\\\\/:*?\"<>|\\n]"), "_")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "$safe.mp3")
                put(MediaStore.Downloads.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return "失败：无法创建导出文件"
            return try {
                resolver.openOutputStream(uri)?.use { os ->
                    writeAll(index, total, os)
                } ?: return "失败：无法写入文件"
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                ""
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                "失败：${t.message}"
            }
        } else {
            val dir = context.getExternalFilesDir("export") ?: return "失败：存储不可用"
            val out = File(dir, "$safe.mp3")
            return try {
                out.outputStream().use { os -> writeAll(index, total, os) }
                "已导出到 ${out.absolutePath}"
            } catch (t: Throwable) {
                "失败：${t.message}"
            }
        }
    }

    private fun writeAll(index: TtsIndex, total: Int, os: java.io.OutputStream) {
        for (ch in 0 until total) {
            for (seg in index.segmentsOf(ch)) {
                if (!seg.done) continue
                val f = index.audioFile(seg.file)
                if (f.exists()) f.inputStream().use { it.copyTo(os) }
            }
        }
    }
}
