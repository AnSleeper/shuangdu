package com.shuangdu.reader.tts

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一句/一段音频。file 是相对 tts 目录的文件名；voice 是转这段时用的音色 id（空 = 未知，1.3.10 及更早转的） */
data class TtsSegment(
    val index: Int,
    val text: String,
    val file: String,
    val durationMs: Long = 0L,
    val done: Boolean = false,
    val voice: String = ""
)

/** 已转音频按音色归类：当前音色 / 别的音色（真混音）/ 来源未知（老版本转的） */
data class VoiceStats(
    val current: Int,
    val other: Int,
    val unknown: Int
) {
    val totalDone: Int get() = current + other + unknown
    val needsAttention: Boolean get() = other > 0 || unknown > 0
}

/**
 * 一本书的听书索引：章节 -> 段落列表。
 * 存在 books/<id>/tts/index.json，音频存在同目录。
 *
 * 线程模型：转换时最多 20 个协程并发写，所以所有对 chapters / 统计的读写都在 lock 里，
 * 统计一律「从段列表重算」而不是增量 +1 —— 增量在并发下会丢更新，把章节永远卡在 149/150。
 */
class TtsIndex(private val dir: File) {

    private val lock = Any()
    private val file = File(dir, "index.json")
    private val chapters = LinkedHashMap<Int, MutableList<TtsSegment>>()

    /** 增量维护的派生统计，只在 lock 内改；值永远由段列表重算，不靠自增 */
    private val donePerChapter = HashMap<Int, Int>()
    private val completeChapters = HashSet<Int>()

    /** 这本书用的音色（留空表示跟随全局） */
    @Volatile
    var voiceId: String = ""
    @Volatile
    var voiceName: String = ""
    private var loaded = false

    fun audioFile(name: String): File = File(dir, name)

    fun load() {
        synchronized(lock) {
            if (loaded) return
            loaded = true
            dir.mkdirs()
            if (!file.exists()) return
            runCatching {
                val root = JSONObject(file.readText())
                voiceId = root.optString("voiceId")
                voiceName = root.optString("voiceName")
                val arr = root.optJSONArray("chapters") ?: return@runCatching
                for (i in 0 until arr.length()) {
                    val ch = arr.optJSONObject(i) ?: continue
                    val idx = ch.optInt("c", -1)
                    if (idx < 0) continue
                    val segs = ch.optJSONArray("segs") ?: continue
                    val list = ArrayList<TtsSegment>(segs.length())
                    for (j in 0 until segs.length()) {
                        val s = segs.optJSONObject(j) ?: continue
                        val name = s.optString("f")
                        list.add(
                            TtsSegment(
                                index = s.optInt("i", j),
                                text = s.optString("t"),
                                file = name,
                                durationMs = s.optLong("d", 0L),
                                done = s.optBoolean("ok", false) && audioFile(name).exists(),
                                voice = s.optString("voice", "")
                            )
                        )
                    }
                    chapters[idx] = list
                }
                recomputeStats()
            }
        }
    }

    /** 全量重算（load 后调用）。必须在 lock 内 */
    private fun recomputeStats() {
        donePerChapter.clear()
        completeChapters.clear()
        chapters.forEach { (ch, segs) -> recountChapter(ch, segs) }
    }

    /** 单章统计重算：直接数 done，不做增量。必须在 lock 内 */
    private fun recountChapter(chapter: Int, segs: List<TtsSegment>) {
        val done = segs.count { it.done }
        donePerChapter[chapter] = done
        if (segs.isNotEmpty() && done == segs.size) completeChapters.add(chapter)
        else completeChapters.remove(chapter)
    }

    private fun refreshChapterStats(chapter: Int) {
        val segs = chapters[chapter]
        if (segs == null) {
            donePerChapter.remove(chapter)
            completeChapters.remove(chapter)
            return
        }
        recountChapter(chapter, segs)
    }

    fun save() {
        synchronized(lock) {
            runCatching {
                dir.mkdirs()
                val root = JSONObject()
                val arr = JSONArray()
                chapters.forEach { (idx, segs) ->
                    val ch = JSONObject()
                    ch.put("c", idx)
                    val segArr = JSONArray()
                    segs.forEach { s ->
                        segArr.put(
                            JSONObject()
                                .put("i", s.index)
                                .put("t", s.text)
                                .put("f", s.file)
                                .put("d", s.durationMs)
                                .put("ok", s.done)
                                .put("voice", s.voice)
                        )
                    }
                    ch.put("segs", segArr)
                    arr.put(ch)
                }
                root.put("voiceId", voiceId)
                root.put("voiceName", voiceName)
                root.put("chapters", arr)
                val tmp = File(dir, "index.json.tmp")
                tmp.writeText(root.toString())
                // rename 覆盖是原子的；失败才退化成「删了再改名」，避免中途留下没有索引的状态
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            }
        }
    }

    /** 返回副本：播放器/导出线程会遍历它，不能把内部可变表交出去 */
    fun segmentsOf(chapter: Int): List<TtsSegment> = synchronized(lock) {
        chapters[chapter]?.toList().orEmpty()
    }

    fun segmentIndices(chapter: Int): List<Int> = synchronized(lock) {
        chapters[chapter]?.map { it.index }.orEmpty()
    }

    /** 没建过就按正文切分建一份 */
    fun ensureChapter(chapter: Int, text: String, title: String = ""): List<TtsSegment> {
        load()
        synchronized(lock) {
            val existing = chapters[chapter]
            if (existing != null && existing.isNotEmpty()) return existing.toList()
            val segs = Segmenter.split(chapter, text, title)
            chapters[chapter] = segs.toMutableList()
            refreshChapterStats(chapter)
            return chapters[chapter]!!.toList()
        }
    }

    /** 一段转好了：标记 done 并记下用的音色 */
    fun markDone(chapter: Int, segmentIndex: Int, voice: String) {
        synchronized(lock) {
            val list = chapters[chapter] ?: return
            val i = list.indexOfFirst { it.index == segmentIndex }
            if (i < 0) return
            list[i] = list[i].copy(done = true, voice = voice)
            refreshChapterStats(chapter)
        }
    }

    fun chapterProgress(chapter: Int): Pair<Int, Int> = synchronized(lock) {
        val total = chapters[chapter]?.size ?: 0
        (donePerChapter[chapter] ?: 0) to total
    }

    fun isChapterComplete(chapter: Int): Boolean = synchronized(lock) { chapter in completeChapters }

    fun hasAnyChapterComplete(): Boolean = synchronized(lock) { completeChapters.isNotEmpty() }

    fun completedChapterCount(): Int = synchronized(lock) { completeChapters.size }

    fun completedChapters(): List<Int> = synchronized(lock) { completeChapters.sorted() }

    /** 段落实际用的音色：本书专用优先，否则跟随全局 */
    fun effectiveVoiceId(globalVoiceId: String): String = voiceId.ifBlank { globalVoiceId }

    fun voiceStats(globalVoiceId: String): VoiceStats = synchronized(lock) {
        val cur = effectiveVoiceId(globalVoiceId)
        var other = 0
        var unknown = 0
        var same = 0
        chapters.values.forEach { list ->
            for (s in list) {
                if (!s.done) continue
                when {
                    s.voice.isBlank() -> unknown++
                    s.voice == cur -> same++
                    else -> other++
                }
            }
        }
        VoiceStats(current = same, other = other, unknown = unknown)
    }

    /**
     * 需要重转的段（章, 段号）。
     * - voice 是别的音色 → 真混音，默认纳入
     * - voice 为空（老数据）→ 只有 includeUnknown 时才纳入，绝不默认动
     */
    fun segmentsWithStaleVoice(globalVoiceId: String, includeUnknown: Boolean): List<Pair<Int, Int>> =
        synchronized(lock) {
            val cur = effectiveVoiceId(globalVoiceId)
            val out = ArrayList<Pair<Int, Int>>()
            chapters.forEach { (ch, list) ->
                for (s in list) {
                    if (!s.done) continue
                    if (s.voice.isNotBlank() && s.voice == cur) continue
                    if (s.voice.isBlank() && !includeUnknown) continue
                    out.add(ch to s.index)
                }
            }
            out
        }

    fun deleteAll() {
        synchronized(lock) {
            runCatching { dir.deleteRecursively() }
            chapters.clear()
            donePerChapter.clear()
            completeChapters.clear()
            loaded = false
        }
    }
}

/** 把一章正文切成适合合成的短段：按段落切，太长的按句子再拆 */
object Segmenter {

    private const val MAX_CHARS = 140
    private const val MIN_SPLIT = 40

    private val SENTENCE_END = charArrayOf('。', '！', '？', '…', '」', '』', '”', '!', '?')

    fun split(chapter: Int, text: String, title: String = ""): List<TtsSegment> {
        val out = ArrayList<TtsSegment>()
        var index = 0
        val t = title.trim()
        // 章节标题也读出来，作为第一段
        if (t.isNotEmpty() && t.any { it.isLetterOrDigit() }) {
            out.add(TtsSegment(index = index, text = t, file = "${chapter}_${index}.mp3"))
            index++
        }
        for (raw in text.split('\n')) {
            val para = raw.trim().replace('\u3000', ' ').trim()
            if (para.isEmpty()) continue
            // 纯符号（……、————、====）念不出声，直接跳过，不然接口会返回空音频
            if (para.none { it.isLetterOrDigit() }) continue
            for (piece in splitParagraph(para)) {
                if (piece.none { it.isLetterOrDigit() }) continue
                out.add(TtsSegment(index = index, text = piece, file = "${chapter}_${index}.mp3"))
                index++
            }
        }
        return out
    }

    private fun splitParagraph(para: String): List<String> {
        if (para.length <= MAX_CHARS) return listOf(para)
        val sentences = ArrayList<String>()
        val sb = StringBuilder()
        for (c in para) {
            sb.append(c)
            if (c in SENTENCE_END) {
                sentences.add(sb.toString())
                sb.setLength(0)
            }
        }
        if (sb.isNotEmpty()) sentences.add(sb.toString())

        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (s in sentences) {
            if (s.length > MAX_CHARS) {
                if (cur.isNotEmpty()) {
                    out.add(cur.toString())
                    cur.setLength(0)
                }
                out.addAll(hardSplit(s))
                continue
            }
            if (cur.length + s.length > MAX_CHARS && cur.isNotEmpty()) {
                out.add(cur.toString())
                cur.setLength(0)
            }
            cur.append(s)
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out.filter { it.isNotBlank() }.ifEmpty { listOf(para.take(MAX_CHARS)) }
    }

    /** 单句还是太长：先按逗号，再按字数硬切 */
    private fun hardSplit(s: String): List<String> {
        val parts = ArrayList<String>()
        val cur = StringBuilder()
        for (c in s) {
            cur.append(c)
            if ((c == '，' || c == '、' || c == '；' || c == '：') && cur.length >= MIN_SPLIT) {
                parts.add(cur.toString())
                cur.setLength(0)
            }
        }
        if (cur.isNotEmpty()) parts.add(cur.toString())
        val out = ArrayList<String>()
        for (p in parts) {
            if (p.length <= MAX_CHARS) out.add(p) else {
                var i = 0
                while (i < p.length) {
                    val end = minOf(i + MAX_CHARS, p.length)
                    out.add(p.substring(i, end))
                    i = end
                }
            }
        }
        return out
    }
}
