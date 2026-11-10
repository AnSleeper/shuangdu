package com.shuangdu.reader.format

/**
 * 章节切分。中文小说章节名规则五花八门，这里用一组常见模式匹配「整行就是章节名」的行，
 * 匹配不到再按长度硬切，保证任何 TXT 都能分章节读。
 */
object ChapterSplitter {

    private val CHAPTER_REGEXES = listOf(
        Regex("^第[0-9０-９零一二三四五六七八九十百千万两〇]{1,12}[章节回卷集幕话篇][^。！？]{0,60}$"),
        Regex("^(序章|序言|序|楔子|引子|前言|后记|尾声|终章|大结局|番外)[^。！？]{0,60}$"),
        Regex("^Chapter\\s*[0-9IVXLCivxlc]{1,8}[^。！？]{0,60}$", RegexOption.IGNORE_CASE),
        // 单集式标题：EP0004 / EP.4 / ep-12 / EP 7 标题（后面只允许「空格 + 副标题」）
        Regex(
            "^EP[\\s\\u3000.．·—:：-]*[0-9]{1,6}([\\s\\u3000]+\\S[^。！？]{0,50})?$",
            RegexOption.IGNORE_CASE
        ),
        Regex("^[0-9]{1,4}[、.．]\\s*\\S[^。！？]{0,56}$"),
        Regex("^[（(【\\[]?[0-9]{1,4}[）)】\\]]\\s*\\S[^。！？]{0,56}$")
    )

    private const val MAX_TITLE_LEN = 78

    data class Split(val title: String, val body: String)

    fun split(text: String): List<Split> {
        if (text.isBlank()) return listOf(Split("正文", ""))

        val lines = text.split('\n')
        val boundaries = ArrayList<Pair<Int, String>>() // charIndex, title
        var cursor = 0
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && trimmed.length <= MAX_TITLE_LEN && isChapterTitle(trimmed)) {
                boundaries.add(cursor to trimmed)
            }
            cursor += line.length + 1
        }

        // 头部正文（第一个章节名之前的内容）
        val prefaceEnd = boundaries.firstOrNull()?.first ?: text.length

        if (boundaries.size < 2) {
            return fallbackSplit(text)
        }

        val result = ArrayList<Split>(boundaries.size + 1)
        if (prefaceEnd > 0) {
            val head = text.substring(0, prefaceEnd).trim()
            if (head.length > 60) {
                result.add(Split("序", head))
            }
        }
        for (idx in boundaries.indices) {
            val (startIdx, title) = boundaries[idx]
            val end = if (idx + 1 < boundaries.size) boundaries[idx + 1].first else text.length
            val lineEnd = text.indexOf('\n', startIdx).let { if (it < 0) text.length else it + 1 }
            val body = text.substring(lineEnd.coerceAtMost(end), end).trim()
            if (body.isNotEmpty() || idx == boundaries.lastIndex) {
                result.add(Split(title, body))
            }
        }
        return if (result.isEmpty()) fallbackSplit(text) else result
    }

    private fun isChapterTitle(line: String): Boolean {
        if (line.endsWith("。") || line.endsWith("，") || line.endsWith("！") || line.endsWith("？")) return false
        return CHAPTER_REGEXES.any { it.matches(line) }
    }

    /** 超长章节（整本书塞进一个 xhtml 的情况）按段落再切一刀，避免排版时一次排几万字 */
    fun splitOversized(splits: List<Split>, limit: Int = 60000): List<Split> {
        if (splits.none { it.body.length > limit }) return splits
        val out = ArrayList<Split>(splits.size)
        for (s in splits) {
            if (s.body.length <= limit) {
                out.add(s)
                continue
            }
            var start = 0
            var part = 1
            val parts = ArrayList<String>()
            while (start < s.body.length) {
                var end = minOf(start + limit, s.body.length)
                if (end < s.body.length) {
                    val nl = s.body.lastIndexOf('\n', end)
                    if (nl > start + limit / 3) end = nl
                }
                parts.add(s.body.substring(start, end))
                start = end
                part++
            }
            parts.forEachIndexed { i, body ->
                out.add(Split(if (parts.size > 1) "${s.title}（${i + 1}）" else s.title, body.trim()))
            }
        }
        return out
    }

    /** 没有章节标记时按长度切成小节，切在空行处 */    private fun fallbackSplit(text: String): List<Split> {
        val chunk = 6000
        if (text.length <= chunk * 2) {
            return listOf(Split("正文", text))
        }
        val out = ArrayList<Split>()
        var start = 0
        var part = 1
        while (start < text.length) {
            var end = minOf(start + chunk, text.length)
            if (end < text.length) {
                val nl = text.indexOf("\n\n", end - 800.coerceAtLeast(start))
                if (nl in (start + 500)..(start + chunk + 800)) {
                    end = nl
                }
            }
            val body = text.substring(start, end).trim()
            if (body.isNotEmpty()) out.add(Split("第${part}节", body))
            start = end
            part++
        }
        return out
    }
}
