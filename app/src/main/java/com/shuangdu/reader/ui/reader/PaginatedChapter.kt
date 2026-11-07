package com.shuangdu.reader.ui.reader

import android.graphics.Canvas
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.RelativeSizeSpan

data class PageStyle(
    val textSizePx: Float,
    val lineSpacingMult: Float,
    val typeface: Typeface,
    val color: Int,
    val widthPx: Int,
    val indent: Boolean,
    val paragraphGap: Float = 0.45f
)

/**
 * 纯文字章节的排版结果。整章用一张 StaticLayout 排完，翻页只做画布平移，
 * 所以翻页是纯位移，不会重新排版，任何段落都不会跳字。
 * 段落之间可以用「零宽字符 + 相对字号」做可调间距，所以分页按实际行高累积，不用固定行数。
 */
class PaginatedChapter private constructor(
    val layout: StaticLayout,
    private val pageStarts: IntArray,
    val pageHeight: Int
) : PageSource {

    override val pageCount: Int get() = pageStarts.size.coerceAtLeast(1)

    override fun drawPage(canvas: Canvas, page: Int) {
        if (pageStarts.isEmpty()) return
        val first = pageStarts[page.coerceIn(0, pageStarts.size - 1)]
        canvas.save()
        canvas.clipRect(0, 0, layout.width, pageHeight)
        canvas.translate(0f, -layout.getLineTop(first).toFloat())
        layout.draw(canvas)
        canvas.restore()
    }

    override fun charOffsetAtPage(page: Int): Int {
        if (pageStarts.isEmpty()) return 0
        val line = pageStarts[page.coerceIn(0, pageStarts.size - 1)]
        return layout.getLineStart(line)
    }

    override fun pageOfCharOffset(offset: Int): Int {
        if (pageStarts.isEmpty()) return 0
        val off = offset.coerceIn(0, (layout.text.length - 1).coerceAtLeast(0))
        val line = layout.getLineForOffset(off)
        var page = 0
        for (i in pageStarts.indices) {
            if (pageStarts[i] <= line) page = i else break
        }
        return page.coerceIn(0, pageCount - 1)
    }

    companion object {
        /**
         * 段落规范化：每行 = 一段，剥掉行首尾空白后统一补两个全角空格。
         * gap = 段间距占一行高度的比例，0 表示不留空，1 表示整整一行。
         */
        fun buildParagraphs(raw: String, gap: Float): CharSequence {
            val sb = SpannableStringBuilder()
            var first = true
            val g = gap.coerceIn(0f, 1f)
            for (line in raw.split('\n')) {
                val t = line.trim(' ', '\t', '\u3000', '\u00A0')
                if (t.isEmpty()) continue
                if (!first) {
                    sb.append('\n')
                    if (g > 0.06f) {
                        val start = sb.length
                        sb.append('\u200B')
                        sb.append('\n')
                        sb.setSpan(
                            RelativeSizeSpan(g),
                            start,
                            start + 1,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }
                sb.append("\u3000\u3000").append(t)
                first = false
            }
            return sb
        }

        fun normalizeParagraphs(raw: String): String = buildParagraphs(raw, 1f).toString()

        fun textPaint(style: PageStyle): TextPaint = TextPaint().apply {
            isAntiAlias = true
            isSubpixelText = true
            textSize = style.textSizePx
            typeface = style.typeface
            color = style.color
        }

        fun build(rawText: String, style: PageStyle, heightPx: Int): PaginatedChapter {
            val cs: CharSequence = if (style.indent) {
                buildParagraphs(rawText, style.paragraphGap)
            } else {
                rawText.trim()
            }
            val paint = textPaint(style)
            val layout = StaticLayout.Builder
                .obtain(cs, 0, cs.length, paint, style.widthPx.coerceAtLeast(1))
                .setLineSpacing(0f, style.lineSpacingMult)
                .setIncludePad(false)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build()

            val lineCount = layout.lineCount
            val lineHeight = if (lineCount >= 2) {
                val a = layout.getLineTop(1) - layout.getLineTop(0)
                val b = if (lineCount >= 3) layout.getLineTop(2) - layout.getLineTop(1) else a
                maxOf(a, b).coerceAtLeast(1)
            } else {
                val fm = paint.fontMetrics
                ((fm.descent - fm.ascent) * style.lineSpacingMult).toInt().coerceAtLeast(1)
            }
            val usable = heightPx.coerceAtLeast(lineHeight)

            val starts = ArrayList<Int>(lineCount / 4 + 2)
            var line = 0
            while (line < lineCount) {
                starts.add(line)
                val top = layout.getLineTop(line)
                var next = line
                while (next < lineCount && layout.getLineBottom(next) - top <= usable) next++
                line = if (next > line) next else line + 1
            }
            if (starts.isEmpty()) starts.add(0)
            return PaginatedChapter(layout, starts.toIntArray(), usable)
        }
    }
}
