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
        val finished: Boolean = false,
        val lastError: String? = null
    )

    private val _state = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val state: StateFlow<Map<String, Progress>> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val retryJobs = ConcurrentHashMap<String, Job>()
    private var batchJob: Job? = null

    private companion object {
        /** 失败段自动重试轮数；每轮之间的等待（秒） */
        const val MAX_AUTO_RETRY = 3
        val AUTO_RETRY_DELAYS_SEC = listOf(20, 40, 80)
    }

    /** 批量转换队列：排队中（含正在转）的 bookId，按顺序 */
    private val _batchQueue = MutableStateFlow<List<String>>(emptyList())
    val batchQueue: StateFlow<List<String>> = _batchQueue.asStateFlow()

    /** 多本批量转：逐本排队执行，主页显示队列进度 */
    fun convertMany(books: List<Book>) {
        if (batchJob?.isActive == true) return
        batchJob = scope.launch {
            try {
                _batchQueue.value = books.map { it.id }
                for (b in books) {
                    convert(b)
                    // 等这本真正结算：主任务在跑、或还有自动重试排着队，都算没结束。
                    // 不依赖 onFinished 回调——暂停/异常时回调可能永远不来，队列会卡死。
                    var guard = 0
                    var first = true
                    while (guard < 21600) {
                        if (first) delay(300) else delay(500)
                        first = false
                        val busy = isRunning(b.id) || retryJobs[b.id]?.isActive == true
                        if (!busy) break
                        guard++
                    }
                    retryJobs.remove(b.id)?.cancel()
                    _batchQueue.value = _batchQueue.value - b.id
                    clearFinished(b.id)
                }
            } finally {
                // 无论正常结束、取消还是异常，队列都必须清空
                _batchQueue.value = emptyList()
            }
        }
    }

    @Volatile
    private var gateUntil = 0L

    private val indexes = ConcurrentHashMap<String, TtsIndex>()

    /** 换音色后待重转的书：当前转换跑完 → 删旧音频 → 只重转原有章节 */
    private val pendingReconvert = ConcurrentHashMap<String, Boolean>()

    /** 用户主动要求重转：删掉指定章节（null=整本）的音频重新转。正在转换就先停掉它 */
    fun reconvert(book: Book, chapters: List<Int>?) {
        pause(book.id)
        retryJobs.remove(book.id)?.cancel()
        val index = indexOf(book)
        index.resetChapters(chapters ?: index.completedChapters().ifEmpty { (0 until repo.chapterCount(book)).toList() })
        convert(book, chapters)
    }

    /**
     * 换音色后重转：只重转「之前已经转好的那些章节」，绝不扩大到整本。
     * 当前有转换在跑时不打断，跑完后再自动执行。
     */
    fun markReconvert(book: Book) {
        if (isRunning(book.id)) {
            pendingReconvert[book.id] = true
            update(book.id) { it.copy(message = "换音色：等当前转换结束后自动重转") }
        } else {
            val index = indexOf(book)
            val chapters = index.completedChapters()
            index.resetAudio()
            if (chapters.isNotEmpty()) convert(book, chapters)
        }
    }

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
        retryJobs.remove(bookId)?.cancel()
        update(bookId) { it.copy(running = false, finished = false, message = "已暂停") }
    }

    fun progressOf(bookId: String): Progress? = _state.value[bookId]

    /**
     * 开始转换。[chapters] 为空表示整本。已存在的音频会跳过。
     * 有失败段时会自动延时重试若干轮，仍失败才把结果定稿并通知队列。
     */
    fun convert(
        book: Book,
        chapters: List<Int>? = null,
        onFinished: (() -> Unit)? = null,
        retryRound: Int = 0
    ) {
        if (isRunning(book.id)) {
            onFinished?.invoke()
            return
        }
        // 用户手动发起新一轮，取消还排着的自动重试（内部重试轮次除外）
        if (retryRound == 0) retryJobs.remove(book.id)?.cancel()
        val key = keyStore.load()
        if (key == null) {
            update(book.id) { Progress(book.id, message = "还没填 Fish Audio 密钥", lastError = "还没填 Fish Audio 密钥") }
            onFinished?.invoke()
            return
        }
        val tts = settings.current
        val totalChapters = repo.chapterCount(book)
        val targets = (chapters ?: (0 until totalChapters).toList()).filter { it in 0 until totalChapters }
        val chapterTitleList = runCatching { repo.loadChapters(book) }.getOrDefault(emptyList())

        jobs[book.id] = scope.launch {
            val index = indexOf(book)
            val voiceId = index.voiceId.ifBlank { tts.globalVoiceId }
            // 先建好索引，统计总量
            val pending = ArrayList<Pair<Int, TtsSegment>>()
            for (ch in targets) {
                val text = repo.readChapter(book, ch)
                if (text.isBlank()) continue
                val chTitle = chapterTitleList.getOrNull(ch)?.title.orEmpty()
                val segs = index.ensureChapter(ch, text, chTitle)
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
            val lastError = java.util.concurrent.atomic.AtomicReference<String?>(null)
            update(book.id) {
                Progress(
                    book.id,
                    running = true,
                    done = 0,
                    total = total,
                    chapter = targets.first(),
                    message = if (retryRound > 0) "自动重试第 $retryRound 轮" else null
                )
            }

            val limit = tts.concurrency.coerceIn(1, 30)
            val sem = Semaphore(limit)
            var lastSave = System.currentTimeMillis()

            try {
                coroutineScope {
                    pending.map { (ch, seg) ->
                        async {
                            sem.withPermit {
                                val err = synthesizeOne(book, index, ch, seg, tts, voiceId)
                                if (err == null) {
                                    doneCounter.incrementAndGet()
                                } else {
                                    failCounter.incrementAndGet()
                                    lastError.set(err)
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
                val reason = lastError.get()
                if (failed > 0 && retryRound < MAX_AUTO_RETRY) {
                    // 只补失败段（它们仍是 done=false），延时自动重试
                    val waitSec = AUTO_RETRY_DELAYS_SEC[retryRound.coerceIn(0, AUTO_RETRY_DELAYS_SEC.size - 1)]
                    update(book.id) {
                        it.copy(
                            running = false,
                            finished = false,
                            failed = failed,
                            lastError = reason,
                            message = "$failed 段失败，${waitSec} 秒后自动重试（第 ${retryRound + 1}/$MAX_AUTO_RETRY 轮）"
                        )
                    }
                    scheduleRetry(book, chapters, onFinished, retryRound + 1, waitSec * 1000L)
                } else {
                    update(book.id) {
                        it.copy(
                            running = false,
                            finished = true,
                            failed = failed,
                            lastError = if (failed > 0) reason else null,
                            message = if (failed == 0) "全部完成"
                            else "$failed 段失败，稍后点「继续转换」补转：${reason ?: "网络异常"}"
                        )
                    }
                    onFinished?.invoke()
                }
            } catch (e: Exception) {
                index.save()
                update(book.id) {
                    it.copy(running = false, lastError = e.message, message = "已停止：${e.message ?: "未知原因"}")
                }
                onFinished?.invoke()
            } finally {
                jobs.remove(book.id)
                if (pendingReconvert.remove(book.id) == true) {
                    retryJobs.remove(book.id)?.cancel()
                    val chapters = index.completedChapters()
                    index.resetAudio()
                    if (chapters.isNotEmpty()) scope.launch { convert(book, chapters) }
                }
            }
        }
    }

    /** 延时自动重试：只重跑仍缺音频的段，不打断用户手动暂停 */
    private fun scheduleRetry(
        book: Book,
        chapters: List<Int>?,
        onFinished: (() -> Unit)?,
        round: Int,
        delayMs: Long
    ) {
        retryJobs[book.id]?.cancel()
        retryJobs[book.id] = scope.launch {
            delay(delayMs)
            if (!isRunning(book.id)) convert(book, chapters, onFinished, round)
        }
    }

    private suspend fun synthesizeOne(
        book: Book,
        index: TtsIndex,
        chapter: Int,
        seg: TtsSegment,
        tts: TtsSettings,
        voiceId: String
    ): String? {
        var attempts = 0
        var backoff = 5000L
        var lastMsg: String? = null
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
                return null
            }
            val err = result.exceptionOrNull()
            lastMsg = err?.message ?: "网络异常"
            if (err is FishAudioException && err.rateLimited) {
                gateUntil = System.currentTimeMillis() + backoff
                backoff = (backoff * 2).coerceAtMost(60000)
                if (attempts++ >= 6) return lastMsg
                continue
            }
            if (attempts++ >= 2) {
                android.util.Log.w("TtsConverter", "段 ${seg.file} 失败：$lastMsg")
                return lastMsg
            }
            delay(listOf(5000L, 15000L, 30000L)[attempts - 1])
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
