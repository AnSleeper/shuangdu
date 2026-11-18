package com.shuangdu.reader.tts

/**
 * 送 TTS 之前的文本清洗（只影响请求，不改索引里的原文）。
 *
 * Fish Audio 的 S2 系模型（s2.1-pro-free / s2.1-pro / s2-pro）把 `[方括号]` 里的内容
 * 当成「演法提示」而不是朗读内容 —— 官方原话：
 * "S2.1-Pro and S2-Pro treat [bracket] tags as standard text rather than dedicated
 *  control tokens"，并支持 [whisper] [laugh] [sigh] 这类自由描述的标签。
 *
 * 于是中文小说里「[请输入您理想的形态参数。]」这种系统提示行，会被模型拿去"理解成语气指令"，
 * 而不是念出来 —— 结果就是一段杂音。
 *
 * 实测还有两批踩坑的：半角 `{}`，以及全角 `【】`（中文语料里 【系统提示】 这种写法太多，
 * 模型同样按标记处理）。同类全角方括号/花括号一起清掉，避免一个个撞。
 *
 * 这里只去掉括号符号、保留里面的字；只在合成时清洗，段落原始文本（阅读页高亮定位）保持不动。
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

    private val BRACKET_REGEX = Regex("[\\[\\]{}【】［］｛｝〔〕〖〗]")

    fun sanitize(raw: String): String {
        if (raw.isEmpty()) return raw
        if (raw.none { it in BRACKET_CHARS }) return raw
        // 成对、落单、被分段切开的一半，一律去掉；保留括号内的正文
        return BRACKET_REGEX.replace(raw, "").trim()
    }

    /** 这段原文送合成前会不会被清洗（含括号）—— 用来找出需要重转的老段 */
    fun needsSanitize(raw: String): Boolean = raw.any { it in BRACKET_CHARS }
}
