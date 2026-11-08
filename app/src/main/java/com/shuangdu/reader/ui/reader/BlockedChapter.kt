package com.shuangdu.reader.ui.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout

/**
 * 带图片的章节排版。正文按「段落 -> 图片 -> 段落」切成块，逐块往下堆，
 * 一屏放不下就换页。图片按正文宽度等比缩放，最高占 80% 屏高。
 */
class BlockedChapter private constructor(
    private val pages: List<List<Op>>,
    private val pageOffsets: List<Int>,
    private val pageHeight: Int
) : PageSource {

    override val pageCount: Int get() = pages.size.coerceAtLeast(1)

    private sealed interface Op
    private class TextOp(
        val layout: StaticLayout,
        val firstLine: Int,
        val lastLine: Int,
        val top: Int
    ) : Op

    private class ImageOp(
        val bitmap: Bitmap,
        val top: Int,
        val height: Int,
        val dstWidth: Int
    ) : Op

    override fun drawPage(canvas: Canvas, page: Int) {
        val ops = pages.getOrNull(page) ?: return
        for (op in ops) {
            when (op) {
                is TextOp -> {
                    val bottom = op.layout.getLineBottom(op.lastLine - 1)
                    canvas.save()
                    canvas.clipRect(0, op.top, op.layout.width, op.top + (bottom - op.layout.getLineTop(op.firstLine)))
                    canvas.translate(0f, (op.top - op.layout.getLineTop(op.firstLine)).toFloat())
                    op.layout.draw(canvas)
                    canvas.restore()
                }

                is ImageOp -> {
                    val dst = Rect(0, op.top, op.dstWidth, op.top + op.height)
                    canvas.drawBitmap(op.bitmap, null, dst, IMAGE_PAINT)
                }
            }
        }
    }

    override fun charOffsetAtPage(page: Int): Int = pageOffsets.getOrElse(page) { 0 }

    override fun pageOfCharOffset(offset: Int): Int {
        if (pageOffsets.isEmpty()) return 0
        var page = 0
        for (i in pageOffsets.indices) {
            if (pageOffsets[i] <= offset) page = i else break
        }
        return page.coerceIn(0, pageCount - 1)
    }

    companion object {
        private val IMAGE_PAINT = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        const val MARKER = '\u0001'

        fun hasImageMarker(text: String): Boolean = text.indexOf(MARKER) >= 0

        private fun splitBlocks(text: String): List<Any> {
            val out = ArrayList<Any>()
            var i = 0
            while (i < text.length) {
                val start = text.indexOf(MARKER, i)
                if (start < 0) {
                    out.add(text.substring(i))
                    break
                }
                if (start > i) out.add(text.substring(i, start))
                val end = text.indexOf(MARKER, start + 1)
                if (end < 0) {
                    out.add(text.substring(start + 1))
                    break
                }
                val idx = text.substring(start + 1, end).trim().toIntOrNull()
                if (idx == null) out.add(text.substring(start + 1, end)) else out.add(idx)
                i = end + 1
            }
            return out
        }

        /**
         * 排版。返回 null 表示这章没有图片，调用方走纯文字快路径。
         * [loadImage] 按图片序号取已解码的 Bitmap。
         */
        fun build(
            rawText: String,
            style: PageStyle,
            heightPx: Int,
            loadImage: (Int) -> Bitmap?,
            title: String = ""
        ): BlockedChapter? {
            if (!hasImageMarker(rawText)) return null

            val blocks = splitBlocks(rawText)
            val paint = PaginatedChapter.textPaint(style)
            val titleSb = if (title.isNotBlank()) {
                val fm = paint.fontMetrics
                PaginatedChapter.buildTitle(title, ((fm.descent - fm.ascent) * 0.6f).toInt())
            } else null
            var titlePlaced = false

            val lineHeight = ((paint.fontMetrics.descent - paint.fontMetrics.ascent) * style.lineSpacingMult)
                .toInt().coerceAtLeast(1)

            val pages = ArrayList<List<Op>>()
            var ops = ArrayList<Op>()
            var offsets = ArrayList<Int>()
            var pageStartOffset = 0
            var charCursor = 0
            var y = 0
            val usable = heightPx.coerceAtLeast(lineHeight * 2)
            val width = style.widthPx.coerceAtLeast(1)

            fun flush() {
                if (ops.isNotEmpty()) {
                    pages.add(ops)
                    offsets.add(pageStartOffset)
                    ops = ArrayList()
                }
                y = 0
                pageStartOffset = charCursor
            }

            fun newTextLayout(t: CharSequence): StaticLayout = StaticLayout.Builder
                .obtain(t, 0, t.length, paint, width)
                // 同 PaginatedChapter：垫行底空间，防 emoji/系统字体字形溢出行框
                .setLineSpacing(
                    (paint.fontMetrics.descent - paint.fontMetrics.ascent) * 0.16f,
                    style.lineSpacingMult
                )
                .setIncludePad(false)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build()

            for (block in blocks) {
                if (block is String) {
                    var body: CharSequence = if (style.indent) {
                        val fm = paint.fontMetrics
                        PaginatedChapter.buildParagraphs(
                            block,
                            style.paragraphGap,
                            (fm.descent - fm.ascent).toInt().coerceAtLeast(1)
                        )
                    } else block.trim()
                    if (body.isBlank()) continue
                    // 章节标题拼在第一个文字块的开头
                    if (titleSb != null && !titlePlaced) {
                        body = SpannableStringBuilder().append(titleSb).append(body)
                        titlePlaced = true
                    }
                    val layout = newTextLayout(body)
                    val lineCount = layout.lineCount
                    var line = 0
                    val base = charCursor
                    charCursor += body.length
                    while (line < lineCount) {
                        if (y + lineHeight > usable) flush()
                        val top = y
                        val first = line
                        var last = line
                        while (last < lineCount && y + lineHeight <= usable) {
                            y += lineHeight
                            last++
                        }
                        if (last == first) last = first + 1
                        ops.add(TextOp(layout, first, last, top))
                        line = last
                    }
                } else if (block is Int) {
                    val bmp = loadImage(block) ?: continue
                    if (bmp.width <= 0 || bmp.height <= 0) continue
                    var h = (width.toFloat() * bmp.height / bmp.width).toInt()
                    val maxH = (usable * 0.8f).toInt()
                    if (h > maxH) h = maxH
                    if (y + h > usable && y > 0) flush()
                    ops.add(ImageOp(bmp, y, h, width))
                    y += h + lineHeight / 3
                }
            }
            flush()
            if (pages.isEmpty()) return null
            val usedOffsets = offsets.take(pages.size).toMutableList()
            while (usedOffsets.size < pages.size) usedOffsets.add(0)
            return BlockedChapter(pages, usedOffsets, usable)
        }
    }
}
