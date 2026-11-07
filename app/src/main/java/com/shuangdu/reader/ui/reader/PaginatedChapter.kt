package com.shuangdu.reader.ui.reader

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LineHeightSpan

/**
 * 把某一行的高度压到指定像素 —— 段落间距就靠它。
 * 因为 LineHeightSpan 是 ParagraphStyle，作用在只有零宽字符的那一行上，正好把那段空白压矮。
 */
private class GapLineHeightSpan(private val heightPx: Int) : LineHeightSpan {
    override fun chooseHeight(
        text: CharSequence,
        start: Int,
        end: Int,
        spanstartv: Int,
        lineHeight: Int,
        fm: Paint.FontMetricsInt
    ) {
        val current = fm.descent - fm.ascent
        if (current <= heightPx) return
        val delta = current - heightPx
        fm.ascent += delta / 2
        fm.descent -= delta - delta / 2
    }
}

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

    /** 听书写用：当前排版后的完整文本 */
    val displayText: CharSequence get() = layout.text

    private var hlStart = -1
    private var hlEnd = -1
    private var hlColor = 0

    /** 听书高亮：把当前朗读的那段所在行刷一层底色 */
    override fun highlightByText(text: String, color: Int): Int {
        if (text.isBlank()) {
            hlStart = -1
            hlEnd = -1
            return -1
        }
        val hay = layout.text
        var idx = hay.indexOf(text)
        if (idx < 0 && text.length > 12) {
            idx = hay.indexOf(text.take(12))
            if (idx >= 0) {
                hlStart = idx
                hlEnd = (idx + text.length).coerceAtMost(hay.length)
                hlColor = color
                return idx
            }
        }
        if (idx < 0) return -1
        hlStart = idx
        hlEnd = (idx + text.length).coerceAtMost(hay.length)
        hlColor = color
        return idx
    }

    override fun drawPage(canvas: Canvas, page: Int) {
        if (pageStarts.isEmpty()) return
        val first = pageStarts[page.coerceIn(0, pageStarts.size - 1)]
        canvas.save()
        canvas.clipRect(0, 0, layout.width, pageHeight)
        canvas.translate(0f, -layout.getLineTop(first).toFloat())
        if (hlStart >= 0 && hlEnd > hlStart) {
            val from = layout.getLineForOffset(hlStart.coerceIn(0, layout.text.length - 1))
            val to = layout.getLineForOffset((hlEnd - 1).coerceIn(0, layout.text.length - 1))
            if (from in 0 until layout.lineCount && to in 0 until layout.lineCount) {
                hlPaint.color = hlColor
                for (l in from..to) {
                    canvas.drawRect(
                        0f,
                        layout.getLineTop(l).toFloat(),
                        layout.width.toFloat(),
                        layout.getLineBottom(l).toFloat(),
                        hlPaint
                    )
                }
            }
        }
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
        private val hlPaint = Paint()
        /**
         * 段落规范化：每行 = 一段，剥掉行首尾空白后统一补两个全角空格。
         * gap = 段间距占一行高度的比例，0 表示不留空，1 表示整整一行。
         * 段间距用「零宽字符 + LineHeightSpan 指定行高」实现 —— 这是真正能改行高的做法，
         * RelativeSizeSpan 改字号是无效的（StaticLayout 不按它压行）。
         */
        fun buildParagraphs(raw: String, gap: Float, baseLinePx: Int = 0): CharSequence {
            val sb = SpannableStringBuilder()
            var first = true
            val g = gap.coerceIn(0f, 1f)
            for (line in raw.split('\n')) {
                val t = line.trim(' ', '\t', '\u3000', '\u00A0')
                if (t.isEmpty()) continue
                if (!first) {
                    sb.append('\n')
                    if (g > 0.04f) {
                        val start = sb.length
                        sb.append('\u200B')
                        sb.append('\n')
                        val h = if (baseLinePx > 0) (baseLinePx * g).toInt().coerceAtLeast(1) else 0
                        if (h > 0) {
                            sb.setSpan(
                                GapLineHeightSpan(h),
                                start,
                                start + 1,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        }
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
            val paint = textPaint(style)
            val rawFm = paint.fontMetrics
            val baseLine = (rawFm.descent - rawFm.ascent).toInt().coerceAtLeast(1)
            val cs: CharSequence = if (style.indent) {
                buildParagraphs(rawText, style.paragraphGap, baseLine)
            } else {
                rawText.trim()
            }
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
