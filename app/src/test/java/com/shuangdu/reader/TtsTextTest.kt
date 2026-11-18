package com.shuangdu.reader

import com.shuangdu.reader.tts.TtsText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 送 TTS 前的文本清洗：括号、表情/装饰符号、连串重复标点。
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
        assertEquals("六角括号", TtsText.sanitize("〔六角括号〕"))
    }

    @Test
    fun stripsEmojiAndDecoration() {
        assertEquals("我爱你", TtsText.sanitize("我爱你❤"))
        assertEquals("我爱你", TtsText.sanitize("我爱你❤️"))
        assertEquals("她是我的", TtsText.sanitize("她是我的♥★"))
        assertEquals("笑死", TtsText.sanitize("笑死😀"))
    }

    @Test
    fun collapsesRepeatedPunctuation() {
        assertEquals("好耶！", TtsText.sanitize("好耶！！！"))
        assertEquals("真的吗？", TtsText.sanitize("真的吗？？？"))
        assertEquals("等一下…", TtsText.sanitize("等一下……"))
        assertEquals("分割线", TtsText.sanitize("分割线===="))
    }

    @Test
    fun keepsRepeatedLetters() {
        // 拖长音的文字不能压，只有符号才压
        assertEquals("啊啊啊—好痛", TtsText.sanitize("啊啊啊——好痛"))
        assertEquals("不—要—啦", TtsText.sanitize("不——要——啦"))
    }

    @Test
    fun stripsUnpairedBrackets() {
        assertEquals("他笑", TtsText.sanitize("他[笑"))
        assertEquals("未完待续", TtsText.sanitize("未完待续】"))
    }

    @Test
    fun keepsCjkQuotesAndParentheses() {
        val s = "他说：「你来了。」（笑）"
        assertSame(s, TtsText.sanitize(s))
    }

    @Test
    fun plainTextUntouched() {
        val s = "这是一段没有括号的普通正文，数字 123，英文 ABC。"
        assertSame(s, TtsText.sanitize(s))
    }
}
