package com.shuangdu.reader.ui.reader

import android.graphics.Canvas
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

data class PageStyle(
    val textSizePx: Float,
    val lineSpacingMult: Float,
    val typeface: Typeface,
    val color: Int,
    val widthPx: Int,
    val indent: Boolean
)

/**
 * 纯文字章节的排版结果。整章用一张 StaticLayout 排完，翻页只做画布平移，
 * 所以翻页是纯位移，不会重新排版，任何段落都不会跳字。
 */
class PaginatedChapter private constructor(
    val layout: StaticLayout,
    val lineCount: Int,
    val linesPerPage: Int,
    val pageHeight: Int
) : PageSource {

    override val pageCount: Int = if (lineCount == 0) 1 else (lineCount + linesPerPage - 1) / linesPerPage
    val text: String get() = layout.text.toString()
    val lineHeight: Int get() = if (lineCount >= 2) layout.getLineTop(1) - layout.getLineTop(0) else pageHeight

    override fun drawPage(canvas: Canvas, page: Int) {
        if (lineCount == 0) return
        val first = (page * linesPerPage).coerceIn(0, lineCount - 1)
        canvas.save()
        canvas.clipRect(0, 0, layout.width, pageHeight)
        canvas.translate(0f, -layout.getLineTop(first).toFloat())
        layout.draw(canvas)
        canvas.restore()
    }

    override fun charOffsetAtPage(page: Int): Int {
        if (lineCount == 0) return 0
        val line = (page * linesPerPage).coerceIn(0, lineCount - 1)
        return layout.getLineStart(line)
    }

    override fun pageOfCharOffset(offset: Int): Int {
        if (lineCount == 0) return 0
        val off = offset.coerceIn(0, (text.length - 1).coerceAtLeast(0))
        var line = layout.getLineForOffset(off)
        if (line >= lineCount) line = lineCount - 1
        return (line / linesPerPage).coerceIn(0, pageCount - 1)
    }

    companion object {
        /**
         * 段落规范化：每一行 = 一个段落，先剥掉行首行尾所有空白，再统一补两个全角空格，
         * 段落之间留一个空行。这样不管源文件原来缩进几个字，看到的都一样。
         */
        fun normalizeParagraphs(raw: String): String {
            val sb = StringBuilder(raw.length + raw.length / 10)
            var first = true
            for (line in raw.split('\n')) {
                val t = line.trim(' ', '\t', '\u3000', '\u00A0')
                if (t.isEmpty()) continue
                if (!first) sb.append("\n\n")
                sb.append("\u3000\u3000").append(t)
                first = false
            }
            return sb.toString()
        }

        fun indentText(raw: String): String = normalizeParagraphs(raw)

        fun textPaint(style: PageStyle): TextPaint = TextPaint().apply {
            isAntiAlias = true
            isSubpixelText = true
            textSize = style.textSizePx
            typeface = style.typeface
            color = style.color
        }

        fun build(rawText: String, style: PageStyle, heightPx: Int): PaginatedChapter {
            val text = if (style.indent) normalizeParagraphs(rawText) else rawText.trim()
            val paint = textPaint(style)
            val layout = StaticLayout.Builder
                .obtain(text, 0, text.length, paint, style.widthPx.coerceAtLeast(1))
                .setLineSpacing(0f, style.lineSpacingMult)
                .setIncludePad(false)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build()

            val lineCount = layout.lineCount
            val lineHeight = if (lineCount >= 2) {
                (layout.getLineTop(1) - layout.getLineTop(0)).coerceAtLeast(1)
            } else {
                val fm = paint.fontMetrics
                ((fm.descent - fm.ascent) * style.lineSpacingMult).toInt().coerceAtLeast(1)
            }
            val usable = heightPx.coerceAtLeast(lineHeight)
            val linesPerPage = (usable / lineHeight).coerceAtLeast(1)
            return PaginatedChapter(layout, lineCount, linesPerPage, linesPerPage * lineHeight)
        }
    }
}
