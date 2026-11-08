package com.shuangdu.reader.tts

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
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

    data class Result(
        val success: Boolean,
        val fileName: String,
        val location: String,
        val segmentCount: Int = 0,
        val error: String? = null
    )

    /** 导出结果统一回调，界面据此弹提醒 */
    fun exportBook(book: Book, onResult: (Result) -> Unit) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { doExport(book) }
                    .getOrElse { Result(false, "", "", 0, it.message ?: "未知错误") }
            }
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    private fun doExport(book: Book): Result {
        val index = converter.indexOf(book)
        val total = repo.chapterCount(book)
        val safe = book.title.replace(Regex("[\\\\/:*?\"<>|\\n]"), "_")
        val name = "$safe.mp3"
        fun fail(msg: String) = Result(false, name, "", 0, msg)

        // 没有任何已转好的段时不创建空文件，也免得在「下载」里留垃圾
        val segments = (0 until total).sumOf { ch -> index.segmentsOf(ch).count { it.done } }
        if (segments == 0) return fail("这本书还没有转好的音频，请先转换再导出")

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return fail("无法创建导出文件")
            try {
                resolver.openOutputStream(uri)?.use { os -> writeAll(index, total, os) }
                    ?: return fail("无法写入文件")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                Result(true, name, "手机「下载」目录（Download）", segments)
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                fail(t.message ?: "写入失败")
            }
        } else {
            val dir = context.getExternalFilesDir("export") ?: return fail("存储不可用")
            val out = File(dir, name)
            try {
                out.outputStream().use { os -> writeAll(index, total, os) }
                Result(true, name, out.absolutePath, segments)
            } catch (t: Throwable) {
                runCatching { out.delete() }
                fail(t.message ?: "写入失败")
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
