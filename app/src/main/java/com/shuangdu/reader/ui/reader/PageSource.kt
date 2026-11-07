package com.shuangdu.reader.ui.reader

import android.graphics.Canvas

/** 一个章节排版后的可绘制源（纯文字 / 带图片） */
interface PageSource {
    val pageCount: Int
    fun drawPage(canvas: Canvas, page: Int)
    fun charOffsetAtPage(page: Int): Int
    fun pageOfCharOffset(offset: Int): Int
}

object EmptyPageSource : PageSource {
    override val pageCount: Int = 1
    override fun drawPage(canvas: Canvas, page: Int) = Unit
    override fun charOffsetAtPage(page: Int): Int = 0
    override fun pageOfCharOffset(offset: Int): Int = 0
}
