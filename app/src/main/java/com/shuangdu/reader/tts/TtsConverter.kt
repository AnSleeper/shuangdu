package com.shuangdu.reader.tts

import com.shuangdu.reader.data.Book
import com.shuangdu.reader.data.BookRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 把整本书（或指定章节）转成音频。
 * - 只补「没有音频」的段，天然支持中断后续转
 * - 触发 429 时全队退避，退避时间翻倍（5s→10s→20s→40s→60s 封顶）
 * - 任何一段失败不阻塞其他段，最后汇总失败数
 */
class TtsConverter(
    private val repo: BookRepository,
    private val client: FishAudioClient,
    private val keyStore: FishKeyStore,
    private val settings: TtsSettingsStore
) {

    data class Progress(
        val bookId: String,
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val chapter: Int = -1,
        val message: String? = null,
        val failed: Int = 0,
        val finished: Boolean = false
    )

    private val _state = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val state: StateFlow<Map<String, Progress>> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    @Volatile
    private var gateUntil = 0L

    private val indexes = ConcurrentHashMap<String, TtsIndex>()

    /** 同一本书共用一份索引实例，界面和转换才不会各读各的 */
    fun indexOf(book: Book): TtsIndex =
        indexes.getOrPut(book.id) {
            TtsIndex(File(repo.bookDir(book.id), "tts")).also { it.load() }
        }

    fun forget(bookId: String) {
        indexes.remove(bookId)
    }

    fun isRunning(bookId: String): Boolean = jobs[bookId]?.isActive == true

    fun pause(bookId: String) {
        jobs.remove(bookId)?.cancel()
        update(bookId) { it.copy(running = false, message = "已暂停") }
    }

    fun progressOf(bookId: String): Progress? = _state.value[bookId]

    /**
     * 开始转换。[chapters] 为空表示整本。已存在的音频会跳过。
     */
    fun convert(book: Book, chapters: List<Int>? = null, onFinished: (() -> Unit)? = null) {
        if (isRunning(book.id)) return
        val key = keyStore.load()
        if (key == null) {
            update(book.id) { Progress(book.id, message = "还没填 Fish Audio 密钥") }
            return
        }
        val tts = settings.current
        val totalChapters = repo.chapterCount(book)
        val targets = (chapters ?: (0 until totalChapters).toList()).filter { it in 0 until totalChapters }

        jobs[book.id] = scope.launch {
            val index = indexOf(book)
            val voiceId = index.voiceId.ifBlank { tts.globalVoiceId }
            // 先建好索引，统计总量
            val pending = ArrayList<Pair<Int, TtsSegment>>()
            for (ch in targets) {
                val text = repo.readChapter(book, ch)
                if (text.isBlank()) continue
                val segs = index.ensureChapter(ch, text)
                segs.filter { !it.done || !index.audioFile(it.file).exists() }.forEach { seg ->
                    pending.add(ch to seg)
                }
            }
            val total = pending.size
            if (total == 0) {
                index.save()
                update(book.id) {
                    Progress(book.id, done = 0, total = 0, finished = true, message = "这本书已经全部转好了")
                }
                onFinished?.invoke()
                jobs.remove(book.id)
                return@launch
            }

            val doneCounter = AtomicInteger(0)
            val failCounter = AtomicInteger(0)
            update(book.id) {
                Progress(book.id, running = true, done = 0, total = total, chapter = targets.first())
            }

            val limit = tts.concurrency.coerceIn(1, 7)
            val sem = Semaphore(limit)
            var lastSave = System.currentTimeMillis()

            try {
                coroutineScope {
                    pending.map { (ch, seg) ->
                        async {
                            sem.withPermit {
                                val ok = synthesizeOne(book, index, ch, seg, tts, voiceId)
                                if (ok) {
                                    doneCounter.incrementAndGet()
                                } else {
                                    failCounter.incrementAndGet()
                                }
                                val done = doneCounter.get() + failCounter.get()
                                update(book.id) {
                                    it.copy(
                                        running = true,
                                        done = done,
                                        failed = failCounter.get(),
                                        chapter = ch,
                                        message = when {
                                            gateUntil > System.currentTimeMillis() -> "触发限流，退避中…"
                                            else -> null
                                        }
                                    )
                                }
                                if (System.currentTimeMillis() - lastSave > 3000) {
                                    lastSave = System.currentTimeMillis()
                                    index.save()
                                }
                            }
                        }
                    }.awaitAll()
                }
                index.save()
                val failed = failCounter.get()
                update(book.id) {
                    it.copy(
                        running = false,
                        finished = true,
                        message = if (failed == 0) "全部完成" else "完成，$failed 段失败（可再点转换重试）"
                    )
                }
                onFinished?.invoke()
            } catch (e: Exception) {
                index.save()
                update(book.id) {
                    it.copy(running = false, message = "已停止：${e.message ?: "未知原因"}")
                }
            } finally {
                jobs.remove(book.id)
            }
        }
    }

    private suspend fun synthesizeOne(
        book: Book,
        index: TtsIndex,
        chapter: Int,
        seg: TtsSegment,
        tts: TtsSettings,
        voiceId: String
    ): Boolean {
        var attempts = 0
        var backoff = 5000L
        while (true) {
            awaitGate()
            val result = client.synthesize(
                text = seg.text,
                voiceId = voiceId,
                modelId = tts.modelId
            )
            result.onSuccess { bytes ->
                runCatching {
                    index.audioFile(seg.file).writeBytes(bytes)
                    index.update(chapter, seg.copy(done = true))
                }
                return true
            }
            val err = result.exceptionOrNull()
            if (err is FishAudioException && err.rateLimited) {
                gateUntil = System.currentTimeMillis() + backoff
                backoff = (backoff * 2).coerceAtMost(60000)
                if (attempts++ > 6) return false
                continue
            }
            android.util.Log.w("TtsConverter", "段 ${seg.file} 失败：${err?.message}")
            if (attempts++ >= 2) return false
            delay(1500L * attempts)
        }
    }

    private suspend fun awaitGate() {
        while (true) {
            val left = gateUntil - System.currentTimeMillis()
            if (left <= 0) return
            delay(left.coerceAtMost(1000L))
        }
    }

    private fun update(bookId: String, block: (Progress) -> Progress) {
        val current = _state.value[bookId] ?: Progress(bookId)
        val next = block(current)
        _state.value = _state.value.toMutableMap().apply { put(bookId, next) }
    }

    fun clearFinished(bookId: String) {
        val p = _state.value[bookId] ?: return
        if (!p.running) _state.value = _state.value.toMutableMap().apply { remove(bookId) }
    }
}
