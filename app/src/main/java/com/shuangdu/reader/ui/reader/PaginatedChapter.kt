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
 * 一章排版结果。用 StaticLayout 一次性排完整章，翻页时只做画布平移，
 * 所以翻页是纯位移，不会重新排版，任何段落都不会跳字。
 */
class PaginatedChapter private constructor(
    val layout: StaticLayout,
    val lineCount: Int,
    val linesPerPage: Int,
    val pageHeight: Int
) {
    val pageCount: Int = if (lineCount == 0) 1 else (lineCount + linesPerPage - 1) / linesPerPage
    val text: String get() = layout.text.toString()

    fun drawPage(canvas: Canvas, page: Int) {
        if (lineCount == 0) return
        val first = (page * linesPerPage).coerceIn(0, lineCount - 1)
        canvas.save()
        canvas.clipRect(0, 0, layout.width, pageHeight)
        canvas.translate(0f, -layout.getLineTop(first).toFloat())
        layout.draw(canvas)
        canvas.restore()
    }

    fun charOffsetAtPage(page: Int): Int {
        if (lineCount == 0) return 0
        val line = (page * linesPerPage).coerceIn(0, lineCount - 1)
        return layout.getLineStart(line)
    }

    fun pageOfCharOffset(offset: Int): Int {
        if (lineCount == 0) return 0
        val off = offset.coerceIn(0, (text.length - 1).coerceAtLeast(0))
        var line = layout.getLineForOffset(off)
        if (line >= lineCount) line = lineCount - 1
        return (line / linesPerPage).coerceIn(0, pageCount - 1)
    }

    companion object {
        fun indentText(raw: String): String {
            if (raw.isEmpty()) return raw
            val sb = StringBuilder(raw.length + raw.length / 24)
            var lineStart = true
            for (c in raw) {
                if (lineStart) {
                    if (c == '\n') {
                        sb.append('\n')
                        continue
                    }
                    if (c != ' ' && c != '\u3000' && c != '\t') {
                        sb.append("\u3000\u3000")
                        lineStart = false
                    }
                }
                sb.append(c)
                if (c == '\n') lineStart = true
            }
            return sb.toString()
        }

        fun build(rawText: String, style: PageStyle, heightPx: Int): PaginatedChapter {
            val text = if (style.indent) indentText(rawText.trim()) else rawText.trim()
            val paint = TextPaint().apply {
                isAntiAlias = true
                isSubpixelText = true
                textSize = style.textSizePx
                typeface = style.typeface
                color = style.color
            }
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
