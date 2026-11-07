package com.shuangdu.reader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.shuangdu.reader.format.ChapterSplitter
import com.shuangdu.reader.format.EpubParser
import com.shuangdu.reader.format.TextCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

sealed class ImportResult {
    data class Success(val book: Book, val existed: Boolean = false) : ImportResult()
    data class Failure(val message: String) : ImportResult()
}

/**
 * 导入与读取。导入时把内容统一转成 UTF-8 的 content.txt + 章节字节索引 index.json，
 * 之后读章节只做一次随机读，跟原始编码/格式无关。
 */
class BookRepository(
    private val context: Context,
    private val library: LibraryStore,
    private val settings: SettingsStore
) {

    private val booksDir = File(context.filesDir, "books").apply { mkdirs() }
    private val chapterCache = ConcurrentHashMap<String, List<Chapter>>()
    private val imageCache = android.util.LruCache<String, android.graphics.Bitmap>(16)
    private val textCache = object : LinkedHashMap<String, String>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 6
    }

    fun bookDir(id: String): File = File(booksDir, id)
    fun contentFile(id: String): File = File(bookDir(id), "content.txt")
    fun indexFile(id: String): File = File(bookDir(id), "index.json")
    fun imageDir(id: String): File = File(bookDir(id), "images")
    fun coverFile(book: Book): File? {
        if (!book.hasCover) return null
        val f = File(bookDir(book.id), "cover.img")
        return if (f.exists() && f.length() > 0) f else null
    }

    /** 章节正文里的图片，按序号读文件并缩放到正文宽度 */
    fun loadChapterImage(book: Book, name: String, targetWidth: Int): android.graphics.Bitmap? {
        val f = File(imageDir(book.id), "$name.img")
        if (!f.exists()) return null
        val key = "${book.id}:$name:$targetWidth"
        imageCache.get(key)?.let { return it }
        return runCatching {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.absolutePath, bounds)
            var sample = 1
            var w = bounds.outWidth
            while (w > 0 && w / sample > targetWidth * 1.5f) {
                sample *= 2
                w = bounds.outWidth / sample
            }
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts)
            if (bmp != null) imageCache.put(key, bmp)
            bmp
        }.getOrNull()
    }

    suspend fun import(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "未命名.txt"
            val mime = context.contentResolver.getType(uri).orEmpty()
            val ext = name.substringAfterLast('.', "").lowercase()
            val format = when {
                ext == "epub" || mime.contains("epub") -> BookFormat.EPUB
                else -> BookFormat.TXT
            }
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext ImportResult.Failure("读取文件失败")
            if (bytes.isEmpty()) return@withContext ImportResult.Failure("文件是空的")
            if (bytes.size > 120 * 1024 * 1024) return@withContext ImportResult.Failure("文件太大（超过 120MB）")

            importBytes(bytes, name, format, uri.toString())
        } catch (e: Exception) {
            ImportResult.Failure(e.message ?: "导入失败")
        }
    }

    suspend fun importBytes(
        bytes: ByteArray,
        fileName: String,
        format: BookFormat,
        uriString: String
    ): ImportResult = withContext(Dispatchers.IO) {
        try {
            val parsedName = parseFileName(fileName)
            var title = parsedName.first
            var author = parsedName.second
            var chapters: List<ChapterSplitter.Split>
            var cover: ByteArray? = null
            var images: List<ByteArray> = emptyList()

            if (format == BookFormat.EPUB) {
                val tmp = File(context.cacheDir, "import_${System.currentTimeMillis()}.epub")
                tmp.writeBytes(bytes)
                val parsed = try {
                    EpubParser.parse(tmp)
                } finally {
                    tmp.delete()
                }
                chapters = parsed.chapters
                if (parsed.title.isNotBlank()) title = parsed.title
                if (parsed.author.isNotBlank()) author = parsed.author
                cover = parsed.cover
                images = parsed.images
            } else {
                val text = TextCodec.decode(bytes)
                if (text.isBlank()) return@withContext ImportResult.Failure("这个 TXT 是空的")
                chapters = ChapterSplitter.split(text)
            }

            if (chapters.isEmpty()) return@withContext ImportResult.Failure("没有解析到任何章节")

            chapters = ChapterSplitter.splitOversized(chapters)

            // 同名同大小视为已存在
            val exist = library.books.value.firstOrNull {
                it.title == title && it.size == bytes.size.toLong() && it.format == format
            }
            if (exist != null) return@withContext ImportResult.Success(exist, existed = true)

            val id = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
            val dir = bookDir(id).apply { mkdirs() }
            val index = writeChapters(dir, chapters)
            cover?.let { runCatching { File(dir, "cover.img").writeBytes(it) } }
            if (images.isNotEmpty()) {
                val imgDir = File(dir, "images").apply { mkdirs() }
                images.forEachIndexed { i, data ->
                    runCatching { File(imgDir, "$i.img").writeBytes(data) }
                }
            }

            val now = System.currentTimeMillis()
            val book = Book(
                id = id,
                title = title.ifBlank { fileName.substringBeforeLast('.') },
                author = author,
                uri = uriString,
                format = format,
                size = bytes.size.toLong(),
                hasCover = cover != null && cover.isNotEmpty(),
                addedAt = now,
                lastReadAt = now,
                chapterIndex = 0,
                charOffset = 0,
                chapterTitle = index.firstOrNull()?.title.orEmpty(),
                totalChapters = index.size
            )
            chapterCache[id] = index
            library.upsert(book)
            ImportResult.Success(book)
        } catch (e: Exception) {
            ImportResult.Failure(e.message ?: "导入失败")
        }
    }

    private fun writeChapters(dir: File, splits: List<ChapterSplitter.Split>): List<Chapter> {
        val index = ArrayList<Chapter>(splits.size)
        val arr = JSONArray()
        FileOutputStream(File(dir, "content.txt")).use { fos ->
            var offset = 0L
            for (s in splits) {
                val body = s.body.trim().trimEnd() + "\n"
                val data = body.toByteArray(Charsets.UTF_8)
                fos.write(data)
                val ch = Chapter(s.title.trim().ifBlank { "第${index.size + 1}章" }, offset, offset + data.size)
                index.add(ch)
                arr.put(
                    JSONObject()
                        .put("title", ch.title)
                        .put("start", ch.start)
                        .put("end", ch.end)
                )
                offset += data.size
            }
            fos.flush()
        }
        File(dir, "index.json").writeText(arr.toString())
        return index
    }

    fun loadChapters(book: Book): List<Chapter> {
        chapterCache[book.id]?.let { return it }
        val f = indexFile(book.id)
        if (!f.exists()) return emptyList()
        val list = runCatching {
            val arr = JSONArray(f.readText())
            val out = ArrayList<Chapter>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Chapter(o.optString("title"), o.optLong("start"), o.optLong("end")))
            }
            out
        }.getOrDefault(emptyList())
        if (list.isNotEmpty()) chapterCache[book.id] = list
        return list
    }

    fun chapterCount(book: Book): Int = loadChapters(book).size

    fun readChapter(book: Book, index: Int): String {
        val cacheKey = "${book.id}:$index"
        synchronized(textCache) { textCache[cacheKey]?.let { return it } }
        val chapters = loadChapters(book)
        if (index !in chapters.indices) return ""
        val ch = chapters[index]
        val len = (ch.end - ch.start).toInt()
        if (len <= 0) return ""
        val text = runCatching {
            RandomAccessFile(contentFile(book.id), "r").use { raf ->
                raf.seek(ch.start)
                val buf = ByteArray(len)
                raf.readFully(buf)
                String(buf, Charsets.UTF_8)
            }
        }.getOrDefault("")
        synchronized(textCache) { textCache[cacheKey] = text }
        return text
    }

    fun progressPercent(book: Book): Float {
        val total = book.totalChapters.coerceAtLeast(1)
        val chapters = loadChapters(book)
        val chapter = chapters.getOrNull(book.chapterIndex) ?: return 0f
        val len = chapter.length.coerceAtLeast(1L)
        val inChapter = (book.charOffset.toFloat() / len.toFloat()).coerceIn(0f, 1f)
        return ((book.chapterIndex + inChapter) / total.toFloat()).coerceIn(0f, 1f)
    }

    fun delete(book: Book) {
        chapterCache.remove(book.id)
        synchronized(textCache) {
            textCache.keys.filter { it.startsWith(book.id) }.forEach { textCache.remove(it) }
        }
        runCatching { bookDir(book.id).deleteRecursively() }
        library.remove(book.id)
    }

    fun queryDisplayName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) c.getString(idx) else null
                    } else null
                }
        }.getOrNull()
    }

    /** 从文件名里拆书名与作者：「书名_作者：xxx.txt」 */
    private fun parseFileName(fileName: String): Pair<String, String> {
        var base = fileName.substringBeforeLast('.', fileName).trim()
        var author = ""
        val m = Regex("(作者|著者|作\\s*者)\\s*[:：]?\\s*(.+)$").find(base)
        if (m != null) {
            author = m.groupValues[2].trim().trim('【', '】', '[', ']', '(', ')', '_', '-').trim()
            base = base.substring(0, m.range.first).trim()
        }
        base = base.trim('_', '-', '—', ' ', '.', '、', '【', '】', '[', ']')
        if (base.isBlank()) base = fileName.substringBeforeLast('.')
        return base to author
    }
}
