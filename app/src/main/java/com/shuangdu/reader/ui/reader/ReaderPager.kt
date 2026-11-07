package com.shuangdu.reader.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.shuangdu.reader.data.PageAnim
import kotlinx.coroutines.launch

private const val TAP_ZONE_EDGE = 0.30f

/**
 * 翻页容器。自己实现拖动 + 回弹：临近章末时邻居章的首页已经排好版，
 * 位移完全连续，不会出现翻到章末卡一下再跳章。
 */
@Composable
fun ReaderPager(
    engine: ReaderEngine,
    pageAnim: PageAnim,
    bgBrush: Brush,
    onTurnForward: () -> Boolean,
    onTurnBackward: () -> Boolean,
    onTapCenter: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val anim = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val widthPx = viewport.width.toFloat().coerceAtLeast(1f)

    fun forwardPage(): Pair<Int, Int>? {
        val cur = engine.current ?: return null
        if (engine.page + 1 < cur.pageCount) return engine.chapterIndex to engine.page + 1
        val nx = engine.chapterIndex + 1
        val layout = engine.layouts[nx] ?: return null
        if (layout.pageCount <= 0) return null
        return nx to 0
    }

    fun backwardPage(): Pair<Int, Int>? {
        val cur = engine.current ?: return null
        if (engine.page > 0) return engine.chapterIndex to engine.page - 1
        val pv = engine.chapterIndex - 1
        val layout = engine.layouts[pv] ?: return null
        return pv to (layout.pageCount - 1).coerceAtLeast(0)
    }

    fun settle(velocity: Float) {
        scope.launch {
            val start = dragValue
            anim.snapTo(start)
            dragging = false
            dragValue = 0f

            val w = widthPx
            val canForward = forwardPage() != null
            val canBackward = backwardPage() != null
            val goForward = (start < -w * 0.16f || velocity < -850f) && canForward
            val goBackward = (start > w * 0.16f || velocity > 850f) && canBackward
            when {
                goForward -> {
                    anim.animateTo(-w, tween(200, easing = FastOutSlowInEasing))
                    onTurnForward()
                    anim.snapTo(0f)
                }

                goBackward -> {
                    anim.animateTo(w, tween(200, easing = FastOutSlowInEasing))
                    onTurnBackward()
                    anim.snapTo(0f)
                }

                else -> {
                    if (start != 0f) {
                        anim.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow))
                    }
                }
            }
        }
    }

    fun turn(forward: Boolean) {
        if (pageAnim == PageAnim.NONE) {
            if (forward) onTurnForward() else onTurnBackward()
            return
        }
        if (dragging || anim.isRunning) return
        scope.launch {
            val w = widthPx
            if (forward) {
                if (forwardPage() == null) return@launch
                anim.animateTo(-w, tween(190, easing = FastOutSlowInEasing))
                onTurnForward()
                anim.snapTo(0f)
            } else {
                if (backwardPage() == null) return@launch
                anim.animateTo(w, tween(190, easing = FastOutSlowInEasing))
                onTurnBackward()
                anim.snapTo(0f)
            }
        }
    }

    val draggableState = rememberDraggableState { delta ->
        val limit = widthPx
        val target = dragValue + delta
        val damped = when {
            target > 0 && backwardPage() == null -> target * 0.32f
            target < 0 && forwardPage() == null -> target * 0.32f
            else -> target
        }
        dragValue = damped.coerceIn(-limit, limit)
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewport = it }
            .pointerInput(pageAnim, engine.chapterIndex) {
                detectTapGestures { pos ->
                    val x = pos.x / size.width.toFloat()
                    when {
                        x < TAP_ZONE_EDGE -> turn(false)
                        x > 1f - TAP_ZONE_EDGE -> turn(true)
                        else -> onTapCenter()
                    }
                }
            }
            .draggable(
                orientation = Orientation.Horizontal,
                enabled = pageAnim != PageAnim.NONE,
                state = draggableState,
                onDragStarted = {
                    dragValue = anim.value
                    dragging = true
                    scope.launch { anim.stop() }
                },
                onDragStopped = { velocity -> settle(velocity) }
            )
    ) {
        drawRect(brush = bgBrush)

        val off = if (dragging) dragValue else anim.value
        val w = size.width
        val h = size.height
        val coverMode = pageAnim == PageAnim.COVER

        fun paint(chapter: Int, page: Int, dx: Float, opaque: Boolean) {
            val src = engine.layouts[chapter] ?: return
            if (opaque) {
                // 上一页必须有自己的不透明底，否则文字会透到下一页上形成拖影
                drawRect(brush = bgBrush, topLeft = Offset(dx, 0f), size = Size(w, h))
            }
            drawIntoCanvas { canvas ->
                val nc = canvas.nativeCanvas
                val save = nc.save()
                nc.translate(dx, 0f)
                nc.clipRect(0, 0, w.toInt(), h.toInt())
                src.drawPage(nc, page)
                nc.restoreToCount(save)
            }
        }

        fun edgeShadow(dx: Float) {
            val sw = 26f
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.16f), Color.Transparent),
                    startX = dx,
                    endX = dx + sw
                ),
                topLeft = Offset(dx, 0f),
                size = Size(sw, h)
            )
        }

        when (pageAnim) {
            PageAnim.COVER -> {
                when {
                    off < -0.5f -> {
                        forwardPage()?.let { (c, p) -> paint(c, p, 0f, true) }
                        val cur = engine.layouts[engine.chapterIndex]
                        if (cur != null) {
                            paint(engine.chapterIndex, engine.page, off, true)
                            edgeShadow(off + w - 26f)
                        }
                    }

                    off > 0.5f -> {
                        val cur = engine.layouts[engine.chapterIndex]
                        if (cur != null) paint(engine.chapterIndex, engine.page, 0f, true)
                        backwardPage()?.let { (c, p) ->
                            paint(c, p, off - w, true)
                            edgeShadow(off - 26f)
                        }
                    }

                    else -> {
                        val cur = engine.layouts[engine.chapterIndex]
                        if (cur != null) paint(engine.chapterIndex, engine.page, 0f, false)
                    }
                }
            }

            PageAnim.NONE -> {
                val cur = engine.layouts[engine.chapterIndex]
                if (cur != null) paint(engine.chapterIndex, engine.page, 0f, false)
            }

            else -> {
                if (off < -0.5f) forwardPage()?.let { (c, p) -> paint(c, p, off + w, false) }
                if (off > 0.5f) backwardPage()?.let { (c, p) -> paint(c, p, off - w, false) }
                val cur = engine.layouts[engine.chapterIndex]
                if (cur != null) paint(engine.chapterIndex, engine.page, off, false)
            }
        }
    }
}
