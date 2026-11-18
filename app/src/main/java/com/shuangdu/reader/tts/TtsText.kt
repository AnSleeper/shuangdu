package com.shuangdu.reader.tts

/**
 * 送 TTS 之前的文本清洗（只影响请求，不改索引里的原文）。
 *
 * 三类噪音：
 * 1. **括号**：Fish Audio 的 S2 系把 `[方括号]` 里的内容当成「演法提示」而不是朗读内容
 *    （官方：S2.1-Pro / S2-Pro treat [bracket] tags as standard text rather than dedicated
 *    control tokens）。中文小说里的 `[系统提示]`、`【叮！签到成功】` 会被"演"出来，听起来是杂音。
 *    半角 `{}` 与全角 `【】［］｛｝〔〕〖〗` 实测同样有问题。
 * 2. **表情/装饰符号**：❤ ♥ ★ ♪ 😀 这类会被念出来或者破坏韵律，直接去掉。
 * 3. **连串重复标点**：`！！！`、`？？？`、`……`、`~~~~` 这种，模型容易读出怪腔调，
 *    压成一个字符即可。注意只压符号，`啊啊啊`（拖长音）这类文字不能动。
 *
 * 只去掉符号、保留正文；只在合成时清洗，段落原始文本（阅读页高亮定位）保持不动。
 */
object TtsText {

    /**
     * 会被模型当控制标记的括号字符。
     * 半角：`[ ] { }`；全角族：`【】` `［］` `｛｝` `〔〕` `〖〗`。
     */
    private val BRACKET_CHARS = charArrayOf(
        '[', ']', '{', '}',
        '【', '】', '［', '］', '｛', '｝', '〔', '〕', '〖', '〗'
    )

    private val BRACKET_SET: Set<Int> = BRACKET_CHARS.map { it.code }.toHashSet()

    /** 表情 / 装饰符号所在的区段（不碰 ° © ® ※ 这类有意义的符号） */
    private fun isEmojiOrDecoration(cp: Int): Boolean = when (cp) {
        in 0x1F000..0x1FAFF -> true   // 麻将/扑克/表情/交通/补充符号（❤️😀🎉…）
        in 0x2600..0x27BF -> true     // 杂项符号 + Dingbats（❤ ♥ ★ ♪ ✓ ☀ …）
        in 0x2B00..0x2BFF -> true     // 杂项符号与箭头（⭐ ⬛ …）
        in 0xFE00..0xFE0F -> true     // 变体选择符（❤️ 里的那个）
        0x200D, 0x20E3 -> true        // ZWJ、键帽组合符
        else -> false
    }

    /** 重复时要压成一个的字符：标点与符号（文字、数字、`啊——` 里的汉字都不动） */
    private fun isCollapsible(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.CONNECTOR_PUNCTUATION,
        Character.DASH_PUNCTUATION,
        Character.START_PUNCTUATION,
        Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION,
        Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION,
        Character.MATH_SYMBOL,
        Character.CURRENCY_SYMBOL,
        Character.MODIFIER_SYMBOL,
        Character.OTHER_SYMBOL -> true
        else -> false
    }

    fun sanitize(raw: String): String {
        if (raw.isEmpty()) return raw
        val sb = StringBuilder(raw.length)
        var lastCp = -1
        var i = 0
        var changed = false
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            i += Character.charCount(cp)
            if (cp in BRACKET_SET || isEmojiOrDecoration(cp)) {
                changed = true
                continue
            }
            if (cp == lastCp && isCollapsible(cp)) {
                // 连续重复的符号只留一个
                changed = true
                continue
            }
            sb.appendCodePoint(cp)
            lastCp = cp
        }
        val trimmed = sb.toString().trim()
        if (!changed && trimmed.length == raw.length) return raw
        return trimmed
    }

    /** 这段原文送合成前会不会被清洗 —— 用来找出需要重转的老段 */
    fun needsSanitize(raw: String): Boolean = sanitize(raw) != raw.trim()
}
