package com.shuangdu.reader.tts

import com.shuangdu.reader.data.Book
import com.shuangdu.reader.data.BookRepository
import kotlinx.coroutines.CancellationException
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

/**
 * 把整本书（或指定章节、指定段）转成音频。
 *
 * 模型：每本书一份「待办段集合」(Run)，一个 worker 反复取段合成，直到集合空。
 * 转整本 / 转本章 / 重转指定段 / 批量追加 —— 全都只是往集合里加段，所以：
 * - 正在转的时候追加，会自动并进去，不需要「等当前跑完」那套挂起逻辑；
 * - 暂停就是 cancel，不会有挂起的东西在暂停后又触发删文件；
 * - 重转是「覆盖写」，不先删、不改 done 标记，失败保留旧音频。
 */
class TtsConverter(
    private val repo: BookRepository,
    private val client: FishAudioClient,
    private val keyStore: FishKeyStore,
    private val settings: TtsSettingsStore
) {

    /**
     * [done] / [failed] 都只统计「本次任务」的段数：done 只算成功，failed 只算最终放弃的。
     * 界面上「已处理」= done + failed，永远 <= total。
     * [total] 是本次任务出现过的不同段数，运行中追加会变大。
     */
    data class Progress(
        val bookId: String,
        val running: Boolean = false,
        val done: Int = 0,
        val failed: Int = 0,
        val total: Int = 0,
        val chapter: Int = -1,
        val message: String? = null,
        val finished: Boolean = false,
        val lastError: String? = null
    ) {
        val processed: Int get() = (done + failed).coerceAtMost(total)
        val fraction: Float get() = if (total <= 0) 0f else processed.toFloat() / total
    }

    private val _state = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val state: StateFlow<Map<String, Progress>> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val indexes = ConcurrentHashMap<String, TtsIndex>()

    /**
     * 每本书的音频状态：0=没转 1=部分转好 2=整本转完。
     * 由转换层维护，书架直接订阅 —— 不用每次重组遍历全书章节。
     */
    private val _audioStates = MutableStateFlow<Map<String, Int>>(emptyMap())
    val audioStates: StateFlow<Map<String, Int>> = _audioStates.asStateFlow()

    /** 每本书正在进行的任务 */
    private val runs = ConcurrentHashMap<String, Run>()

    /** 一本书的一次转换任务 */
    private class Run(val book: Book) {
        val lock = Any()
        val pending = ArrayDeque<String>()
        val queued = HashSet<String>()
        val inFlight = HashSet<String>()
        val failedKeys = HashSet<String>()
        var success = 0
        var fail = 0
        var retryRound = 0
        var finished = false
        var lastError: String? = null
        var job: Job? = null
    }

    private companion object {
        /** 失败段自动重试轮数；每轮之间的等待（秒） */
        const val MAX_AUTO_RETRY = 3
        val AUTO_RETRY_DELAYS_SEC = listOf(20, 40, 80)

        /** 一批最多取多少段，保证暂停/追加能及时生效 */
        const val BATCH_FACTOR = 4

        fun key(chapter: Int, seg: Int) = "$chapter:$seg"
    }

    @Volatile
    private var gateUntil = 0L

    // ---------------------------------------------------------------- 状态查询

    fun indexOf(book: Book): TtsIndex =
        indexes.getOrPut(book.id) {
            TtsIndex(File(repo.bookDir(book.id), "tts")).also { it.load() }
        }

    fun isRunning(bookId: String): Boolean = runs[bookId]?.job?.isActive == true

    fun progressOf(bookId: String): Progress? = _state.value[bookId]

    /** 算一本书的状态并广播（O(1)，不遍历段） */
    fun refreshAudioState(book: Book) {
        val index = indexOf(book)
        val done = index.completedChapterCount()
        val total = repo.chapterCount(book)
        val state = when {
            done <= 0 -> 0
            total > 0 && done >= total -> 2
            else -> 1
        }
        if (_audioStates.value[book.id] != state) {
            _audioStates.value = _audioStates.value.toMutableMap().apply { put(book.id, state) }
        }
    }

    /** 书架首次加载时补算还没算过的书（IO 线程调用） */
    fun ensureAudioStates(books: List<Book>) {
        var changed = false
        val map = _audioStates.value.toMutableMap()
        books.forEach { b ->
            if (map.containsKey(b.id)) return@forEach
            val index = indexOf(b)
            val done = index.completedChapterCount()
            val total = repo.chapterCount(b)
            map[b.id] = when {
                done <= 0 -> 0
                total > 0 && done >= total -> 2
                else -> 1
            }
            changed = true
        }
        if (changed) _audioStates.value = map
    }

    fun forget(bookId: String) {
        runs[bookId]?.let { run ->
            synchronized(run.lock) { run.pending.clear() }
            run.job?.cancel()
        }
        runs.remove(bookId)
        indexes.remove(bookId)
        if (_audioStates.value.containsKey(bookId)) {
            _audioStates.value = _audioStates.value.toMutableMap().apply { remove(bookId) }
        }
    }

    // ---------------------------------------------------------------- 入口

    /** 开始转换（只补缺音频的段）。[chapters] 为空表示整本 */
    fun convert(book: Book, chapters: List<Int>? = null) {
        if (keyStore.load() == null) {
            update(book.id) {
                Progress(book.id, message = "还没填 Fish Audio 密钥", lastError = "还没填 Fish Audio 密钥")
            }
            return
        }
        scope.launch { enqueueMissing(book, chapters) }
    }

    /** 用户确认「重新转」：这些章全部重转（覆盖写，不删旧文件） */
    fun reconvertChapters(book: Book, chapters: List<Int>?) {
        if (keyStore.load() == null) {
            update(book.id) {
                Progress(book.id, message = "还没填 Fish Audio 密钥", lastError = "还没填 Fish Audio 密钥")
            }
            return
        }
        scope.launch {
            val index = indexOf(book)
            val total = repo.chapterCount(book)
            val targets = (chapters ?: (0 until total).toList()).filter { it in 0 until total }
            val titles = runCatching { repo.loadChapters(book) }.getOrDefault(emptyList())
            val keys = ArrayList<String>()
            for (ch in targets) {
                val text = repo.readChapter(book, ch)
                if (text.isBlank()) continue
                index.ensureChapter(ch, text, titles.getOrNull(ch)?.title.orEmpty())
                    .forEach { keys.add(key(ch, it.index)) }
            }
            enqueue(book, keys)
        }
    }

    /**
     * 把「不是当前音色」的段重转一遍。
     * [includeUnknown] 为 true 时连「来源未知」的老段也一起（会消耗额度，界面上必须明确询问）。
     */
    fun reconvertStaleVoice(book: Book, includeUnknown: Boolean) {
        if (keyStore.load() == null) return
        scope.launch {
            val index = indexOf(book)
            val targets = index.segmentsWithStaleVoice(settings.current.globalVoiceId, includeUnknown)
            if (targets.isEmpty()) return@launch
            enqueue(book, targets.map { key(it.first, it.second) })
        }
    }

    /** 多本批量转：逐本排队执行；已经在排/在转的书会被跳过并返回实际新增数 */
    private val _batchQueue = MutableStateFlow<List<String>>(emptyList())
    val batchQueue: StateFlow<List<String>> = _batchQueue.asStateFlow()

    private val batchBooks = ArrayDeque<String>()
    private val batchLookup = HashMap<String, Book>()
    private val batchLock = Any()
    private var batchJob: Job? = null

    fun convertMany(books: List<Book>): Int {
        var added = 0
        synchronized(batchLock) {
            val cur = _batchQueue.value.toMutableList()
            for (b in books) {
                if (b.id in cur) continue
                cur.add(b.id)
                batchBooks.add(b.id)
                batchLookup[b.id] = b
                added++
            }
            _batchQueue.value = cur
            if (added > 0 && batchJob?.isActive != true) {
                batchJob = scope.launch { drainBatch() }
            }
        }
        return added
    }

    private fun nextBatchBook(): Book? = synchronized(batchLock) {
        val id = batchBooks.removeFirstOrNull() ?: return@synchronized null
        val book = batchLookup[id]
        if (book == null) null else book
    }

    private suspend fun drainBatch() {
        try {
            while (true) {
                val book = nextBatchBook() ?: break
                // 已经在转的书（多半是用户从听书面板手动开的）不占队列，直接把缺口并进它的任务
                val alreadyRunning = isRunning(book.id)
                convert(book, null)
                if (!alreadyRunning) waitIdle(book.id)
                synchronized(batchLock) {
                    _batchQueue.value = _batchQueue.value - book.id
                    batchLookup.remove(book.id)
                }
                clearFinished(book.id)
            }
        } finally {
            synchronized(batchLock) {
                _batchQueue.value = emptyList()
                batchBooks.clear()
                batchLookup.clear()
            }
        }
    }

    private suspend fun waitIdle(bookId: String) {
        // 先等任务真正起来（convert 还在 IO 线程收集待办时别误判空闲）
        var guard = 0
        while (guard < 100 && !isRunning(bookId)) {
            delay(100)
            guard++
        }
        guard = 0
        while (guard < 21600) {
            if (!isRunning(bookId)) return
            delay(500)
            guard++
        }
    }

    /** 暂停：停掉任务，待办清空。没有「挂起的重转」这种东西了 */
    fun pause(bookId: String) {
        // 先把 run 摘掉：否则刚暂停就点「继续转换」时，新排的段会加进这个即将被收尾清空的任务里
        val run = runs.remove(bookId) ?: return
        synchronized(run.lock) {
            run.pending.clear()
            run.finished = true
            run.inFlight.clear()
        }
        run.job?.cancel()
        update(bookId) {
            it.copy(
                running = false,
                finished = false,
                done = run.success,
                failed = run.fail,
                message = "已暂停"
            )
        }
    }

    // ---------------------------------------------------------------- 队列

    private suspend fun enqueueMissing(book: Book, chapters: List<Int>?) {
        val index = indexOf(book)
        val total = repo.chapterCount(book)
        val targets = (chapters ?: (0 until total).toList()).filter { it in 0 until total }
        val titles = runCatching { repo.loadChapters(book) }.getOrDefault(emptyList())
        val keys = ArrayList<String>()
        for (ch in targets) {
            val text = repo.readChapter(book, ch)
            if (text.isBlank()) continue
            val segs = index.ensureChapter(ch, text, titles.getOrNull(ch)?.title.orEmpty())
            segs.filter { !it.done || !index.audioFile(it.file).exists() }
                .forEach { keys.add(key(ch, it.index)) }
        }
        if (keys.isEmpty()) {
            update(book.id) {
                Progress(book.id, total = 0, finished = true, message = "这本书已经全部转好了")
            }
            return
        }
        enqueue(book, keys)
    }

    private fun enqueue(book: Book, keys: Collection<String>) {
        if (keys.isEmpty()) return
        val run = runs.compute(book.id) { _, cur ->
            if (cur != null && !cur.finished) cur else Run(book)
        } ?: return
        synchronized(run.lock) {
            for (k in keys) {
                if (k in run.inFlight) continue      // 正在合成，别重复排队
                run.queued.add(k)
                if (k !in run.pending) run.pending.add(k)
            }
            run.finished = false
        }
        publish(run, running = true)
        startWorker(run)
    }

    private fun startWorker(run: Run) {
        synchronized(run.lock) {
            if (run.job?.isActive == true) return
            run.job = scope.launch { work(run) }
        }
    }

    private fun publish(run: Run, running: Boolean, message: String? = null) {
        val (total, done, failed) = synchronized(run.lock) {
            Triple(run.queued.size, run.success, run.fail)
        }
        update(run.book.id) { cur ->
            cur.copy(
                running = running,
                done = done,
                failed = failed,
                total = total,
                message = message ?: cur.message
            )
        }
    }

    // ---------------------------------------------------------------- worker

    private suspend fun work(run: Run) {
        val book = run.book
        val index = indexOf(book)
        try {
            while (true) {
                val limit = settings.current.concurrency.coerceIn(1, 30)
                val batch = synchronized(run.lock) {
                    val cap = limit * BATCH_FACTOR
                    val list = ArrayList<String>(minOf(cap, run.pending.size))
                    while (list.size < cap && run.pending.isNotEmpty()) list.add(run.pending.removeFirst())
                    run.inFlight.addAll(list)
                    list
                }
                if (batch.isEmpty()) break
                // 新一轮开始：把上一轮的「N 段失败，X 秒后重试」换成带进度的重试轮次
                synchronized(run.lock) {
                    val okNow = run.success
                    val badNow = run.fail
                    val totalNow = run.queued.size
                    val roundNow = run.retryRound
                    val chapterNow = batch.firstOrNull()?.substringBefore(':')?.toIntOrNull() ?: -1
                    update(book.id) { cur ->
                        cur.copy(
                            running = true,
                            done = okNow,
                            failed = badNow,
                            total = totalNow,
                            chapter = chapterNow,
                            message = if (roundNow > 0) {
                                "重试第 $roundNow/$MAX_AUTO_RETRY 轮 ${(okNow + badNow).coerceAtMost(totalNow)}/$totalNow"
                            } else null
                        )
                    }
                }

                val failedNow = java.util.Collections.synchronizedList(ArrayList<String>())
                val sem = Semaphore(limit)
                coroutineScope {
                    batch.map { k -> async { sem.withPermit { process(run, index, k, failedNow) } } }.awaitAll()
                }
                synchronized(run.lock) { run.inFlight.removeAll(batch.toSet()) }
                index.save()
                refreshAudioState(book)

                if (failedNow.isNotEmpty()) {
                    val round = synchronized(run.lock) { ++run.retryRound }
                    if (round <= MAX_AUTO_RETRY) {
                        val waitSec = AUTO_RETRY_DELAYS_SEC[(round - 1).coerceIn(0, AUTO_RETRY_DELAYS_SEC.size - 1)]
                        val n = failedNow.size
                        update(book.id) {
                            it.copy(
                                running = false,
                                finished = false,
                                failed = run.fail,
                                lastError = run.lastError,
                                message = "$n 段失败，${waitSec} 秒后自动重试（第 $round/$MAX_AUTO_RETRY 轮）"
                            )
                        }
                        delay(waitSec * 1000L)
                        synchronized(run.lock) {
                            // 重试的段仍然是「已处理」，进度条不回跳；重试成功后由 process 里 fail--
                            for (k in failedNow) if (k !in run.inFlight) run.pending.add(k)
                        }
                        continue
                    }
                }
                // 收尾前再确认一次：刚判空就被追加的段不能丢
                if (synchronized(run.lock) { run.pending.isNotEmpty() }) continue
                break
            }

            val (failed, ok, queuedTotal) = synchronized(run.lock) {
                Triple(run.fail, run.success, run.queued.size)
            }
            val reason = run.lastError
            update(book.id) {
                it.copy(
                    running = false,
                    finished = true,
                    done = ok,
                    failed = failed,
                    total = queuedTotal,
                    lastError = if (failed > 0) reason else null,
                    message = if (failed == 0) "全部完成"
                    else "$failed 段失败，稍后点「继续转换」补转：${reason ?: "网络异常"}"
                )
            }
        } catch (c: CancellationException) {
            update(book.id) {
                it.copy(running = false, finished = false, message = "已暂停")
            }
            throw c
        } catch (e: Exception) {
            update(book.id) {
                it.copy(
                    running = false,
                    lastError = e.message,
                    message = "已停止：${e.message ?: "未知原因"}"
                )
            }
        } finally {
            index.save()
            refreshAudioState(book)
            val leftover = synchronized(run.lock) {
                val l = run.pending.toList()
                run.finished = true
                run.pending.clear()
                run.inFlight.clear()
                l
            }
            runs.remove(book.id, run)
            // 收尾那一刻被追加进来的段：开一轮新任务接着转，不能丢
            if (leftover.isNotEmpty()) enqueue(book, leftover)
        }
    }

    private suspend fun process(run: Run, index: TtsIndex, key: String, failed: MutableList<String>) {
        val book = run.book
        val sep = key.indexOf(':')
        if (sep <= 0) return
        val ch = key.substring(0, sep).toIntOrNull() ?: return
        val segIdx = key.substring(sep + 1).toIntOrNull() ?: return
        val seg = index.segmentsOf(ch).firstOrNull { it.index == segIdx } ?: return

        val tts = settings.current
        val voice = index.effectiveVoiceId(tts.globalVoiceId)
        val err = synthesizeOne(index, ch, seg, tts, voice)

        synchronized(run.lock) {
            if (err == null) {
                run.success++
                if (run.failedKeys.remove(key)) run.fail--
            } else {
                if (run.failedKeys.add(key)) run.fail++
                run.lastError = err
                failed.add(key)
            }
        }
        update(book.id) {
            it.copy(
                running = true,
                done = run.success,
                failed = run.fail,
                chapter = ch,
                message = if (gateUntil > System.currentTimeMillis()) "触发限流，退避中…" else null
            )
        }
    }

    // ---------------------------------------------------------------- 合成

    private suspend fun synthesizeOne(
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
            val bytes = result.getOrNull()
            if (bytes != null) return writeAudio(index, chapter, seg, voiceId, bytes)

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

    /** 先写 .tmp 再 rename，覆盖正在播的文件也不会读到半截 */
    private fun writeAudio(
        index: TtsIndex,
        chapter: Int,
        seg: TtsSegment,
        voiceId: String,
        bytes: ByteArray
    ): String? = runCatching {
        val f = index.audioFile(seg.file)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) throw java.io.IOException("无法写入音频文件")
        }
        index.markDone(chapter, seg.index, voiceId)
    }.exceptionOrNull()?.let { "写入音频失败：${it.message ?: "未知原因"}" }

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
