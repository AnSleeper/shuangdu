package com.shuangdu.reader

import com.shuangdu.reader.tts.TtsText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 送 TTS 前的文本清洗：括号类会被 Fish Audio 当「演法提示」，必须去掉符号、保留正文。
 */
class TtsTextTest {

    @Test
    fun stripsHalfWidthBrackets() {
        assertEquals("请输入您理想的形态参数。", TtsText.sanitize("[请输入您理想的形态参数。]"))
        assertEquals("参数确认完毕。开始重构。", TtsText.sanitize("[参数确认完毕。开始重构。]"))
        assertEquals("系统", TtsText.sanitize("{系统}"))
        assertEquals("他笑了", TtsText.sanitize("他[笑]了"))
    }

    @Test
    fun stripsFullWidthBrackets() {
        assertEquals("叮！签到成功", TtsText.sanitize("【叮！签到成功】"))
        assertEquals("宿主已获得奖励", TtsText.sanitize("【宿主已获得奖励】"))
        assertEquals("全角方括号", TtsText.sanitize("［全角方括号］"))
        assertEquals("全角花括号", TtsText.sanitize("｛全角花括号｝"))
        assertEquals("六角括号", TtsText.sanitize("〔六角括号〕"))
    }

    @Test
    fun stripsUnpairedBrackets() {
        assertEquals("他笑", TtsText.sanitize("他[笑"))
        assertEquals("未完待续", TtsText.sanitize("未完待续】"))
    }

    @Test
    fun keepsCjkQuotesAndParentheses() {
        // 中文引号、圆括号都不该动（S2 不把圆括号当标签，中文引号本来就是正文标点）
        val s = "他说：「你来了。」（笑）"
        assertSame(s, TtsText.sanitize(s))
    }

    @Test
    fun plainTextUntouched() {
        val s = "这是一段没有括号的普通正文，数字 123，英文 ABC。"
        assertSame(s, TtsText.sanitize(s))
    }
}
