package com.shuangdu.reader.tts

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一句/一段音频。file 是相对 tts 目录的文件名 */
data class TtsSegment(
    val index: Int,
    val text: String,
    val file: String,
    val durationMs: Long = 0L,
    val done: Boolean = false
)

/**
 * 一本书的听书索引：章节 -> 段落列表。
 * 存在 books/<id>/tts/index.json，音频存在同目录。
 */
class TtsIndex(private val dir: File) {

    private val file = File(dir, "index.json")
    private val chapters = LinkedHashMap<Int, MutableList<TtsSegment>>()

    /** 这本书用的音色（留空表示跟随全局） */
    var voiceId: String = ""
    var voiceName: String = ""
    private var loaded = false

    fun audioFile(name: String): File = File(dir, name)

    fun load() {
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
                    list.add(
                        TtsSegment(
                            index = s.optInt("i", j),
                            text = s.optString("t"),
                            file = s.optString("f"),
                            durationMs = s.optLong("d", 0L),
                            done = s.optBoolean("ok", false) && audioFile(s.optString("f")).exists()
                        )
                    )
                }
                chapters[idx] = list
            }
        }
    }

    fun save() {
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
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }

    fun segmentsOf(chapter: Int): List<TtsSegment> = chapters[chapter].orEmpty()

    /** 没建过就按正文切分建一份 */
    fun ensureChapter(chapter: Int, text: String, title: String = ""): List<TtsSegment> {
        load()
        val existing = chapters[chapter]
        if (existing != null && existing.isNotEmpty()) return existing
        val segs = Segmenter.split(chapter, text, title)
        chapters[chapter] = segs.toMutableList()
        return chapters[chapter]!!
    }

    fun update(chapter: Int, segment: TtsSegment) {
        val list = chapters[chapter] ?: return
        val i = list.indexOfFirst { it.index == segment.index }
        if (i >= 0) list[i] = segment
    }

    fun chapterProgress(chapter: Int): Pair<Int, Int> {
        val segs = chapters[chapter].orEmpty()
        return segs.count { it.done } to segs.size
    }

    fun isChapterComplete(chapter: Int): Boolean {
        val segs = chapters[chapter].orEmpty()
        return segs.isNotEmpty() && segs.all { it.done }
    }

    fun hasAnyChapterComplete(): Boolean = chapters.any { (_, segs) -> segs.isNotEmpty() && segs.all { it.done } }

    fun completedChapters(): List<Int> = chapters.filter { (_, s) -> s.isNotEmpty() && s.all { it.done } }.keys.toList()

    /** 换音色后重转：删掉所有音频文件，段落全部标记为未转 */
    fun resetAudio() {
        runCatching {
            dir.listFiles()?.forEach { if (it.name != "index.json" && it.name != "index.json.tmp") it.delete() }
        }
        chapters.forEach { (_, list) ->
            for (i in list.indices) list[i] = list[i].copy(done = false)
        }
        save()
    }

    fun deleteAll() {
        runCatching { dir.deleteRecursively() }
        chapters.clear()
        loaded = false
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
