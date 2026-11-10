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
 * 而不是念出来 —— 结果就是一段听不出人话的杂音。`{}` 实测同样有问题。
 *
 * 所以这里把这两类括号直接去掉（保留里面的字），让它回到普通文本。
 * 注意：只在合成时清洗，段落的原始文本（用于阅读页高亮定位）保持不动。
 */
object TtsText {

    /** 会被模型当控制标记的括号字符 */
    private val BRACKET_CHARS = charArrayOf('[', ']', '{', '}')

    private val BRACKET_REGEX = Regex("[\\[\\]{}]")

    fun sanitize(raw: String): String {
        if (raw.isEmpty()) return raw
        if (raw.none { it in BRACKET_CHARS }) return raw
        // 成对、落单、被分段切开的一半，一律去掉；保留括号内的正文
        return BRACKET_REGEX.replace(raw, "").trim()
    }
}
