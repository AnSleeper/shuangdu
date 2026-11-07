package com.shuangdu.reader.ui.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.BatteryManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shuangdu.reader.AppContainer
import com.shuangdu.reader.data.PageAnim
import com.shuangdu.reader.data.ReadFont
import com.shuangdu.reader.ui.theme.SkinPalette
import com.shuangdu.reader.ui.theme.paletteOf
import com.shuangdu.reader.util.findActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun ReaderScreen(
    container: AppContainer,
    bookId: String,
    onBack: () -> Unit,
    onNotify: (String) -> Unit
) {
    val book = remember(bookId) { container.library.find(bookId) }
    if (book == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val repo = container.repository
    val chapters = remember(bookId) { repo.loadChapters(book) }
    val engine = remember(bookId) {
        ReaderEngine(book.chapterIndex).apply { setChapters(chapters.map { it.title }) }
    }
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val pal = paletteOf(settings.skin)
    val density = LocalDensity.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    var anchor by remember(bookId) { mutableIntStateOf(book.charOffset) }
    var showUi by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showTts by remember { mutableStateOf(false) }
    var textArea by remember { mutableStateOf(IntSize.Zero) }
    val ttsIndex = remember(book.id) { container.ttsConverter.indexOf(book) }
    val playerState by container.ttsPlayer.state.collectAsStateWithLifecycle()

    val statusBar = with(density) { WindowInsets.statusBars.asPaddingValues().calculateTopPadding() }
    val navBar = with(density) { WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() }
    val topPad = statusBar + 46.dp
    val bottomPad = navBar + if (settings.showStatus) 24.dp else 10.dp

    val style: PageStyle? = remember(
        textArea, settings.fontSize, settings.lineSpacing, settings.font, settings.indent,
        settings.paragraphGap, pal
    ) {
        if (textArea.width <= 0) null else PageStyle(
            textSizePx = with(density) { settings.fontSize.sp.toPx() },
            lineSpacingMult = settings.lineSpacing,
            typeface = typefaceOf(settings.font),
            color = pal.readText.toArgb(),
            widthPx = textArea.width,
            indent = settings.indent,
            paragraphGap = settings.paragraphGap
        )
    }

    // 排版：字号/行距/字体/边距/章节变化时重排前中后三章
    LaunchedEffect(style, textArea.height, engine.chapterIndex, chapters.size) {
        val st = style ?: return@LaunchedEffect
        if (chapters.isEmpty()) {
            engine.error = "这本书没有可读内容"
            return@LaunchedEffect
        }
        if (engine.styleToken != st) {
            engine.clearLayouts()
            engine.styleToken = st
        }
        engine.loading = true
        val center = engine.chapterIndex
        val h = textArea.height
        val built = withContext(Dispatchers.Default) {
            val out = ArrayList<Triple<Int, String, PageSource>>(3)
            for (i in (center - 1)..(center + 1)) {
                if (i < 0 || i >= chapters.size) continue
                if (engine.layouts[i] != null) continue
                val text = engine.textOf(i) ?: repo.readChapter(book, i)
                if (text.isEmpty() && i != center) continue
                val blocked = BlockedChapter.build(text, st, h, { idx ->
                    repo.loadChapterImage(book, idx.toString(), st.widthPx)
                }, engine.titleOf(i))
                val src: PageSource = blocked ?: PaginatedChapter.build(text, st, h, engine.titleOf(i))
                out.add(Triple(i, text, src))
            }
            out
        }
        built.forEach { (i, text, src) ->
            engine.putText(i, text)
            engine.putLayout(i, src)
        }
        engine.page = engine.current?.pageOfCharOffset(anchor) ?: 0
        anchor = engine.charOffsetNow()
        engine.loading = false
    }

    fun persistProgress() {
        if (chapters.isEmpty()) return
        container.library.updateProgress(
            id = book.id,
            chapterIndex = engine.chapterIndex,
            charOffset = engine.charOffsetNow(),
            chapterTitle = engine.titleOf(engine.chapterIndex),
            totalChapters = chapters.size
        )
    }

    fun goNext(): Boolean {
        val cur = engine.current ?: return false
        if (engine.page + 1 < cur.pageCount) {
            engine.page += 1
            anchor = engine.charOffsetNow()
            return true
        }
        val nx = engine.chapterIndex + 1
        if (nx >= chapters.size || engine.layouts[nx] == null) return false
        engine.moveToChapter(nx, 0)
        anchor = 0
        return true
    }

    fun goPrev(): Boolean {
        if (engine.page > 0) {
            engine.page -= 1
            anchor = engine.charOffsetNow()
            return true
        }
        val pv = engine.chapterIndex - 1
        val layout = engine.layouts[pv] ?: return false
        engine.moveToChapter(pv, (layout.pageCount - 1).coerceAtLeast(0))
        anchor = engine.charOffsetNow()
        return true
    }

    fun nextChapter() {
        val nx = engine.chapterIndex + 1
        if (nx >= chapters.size) return
        anchor = 0
        engine.moveToChapter(nx, 0)
    }

    fun jumpToChapter(index: Int) {
        if (index !in chapters.indices) return
        showChapters = false
        anchor = 0
        engine.moveToChapter(index, 0)
    }

    /** 当前页第一个落在这页上的段号，用于「从本页开始听」 */
    fun startSegmentOfCurrentPage(): Int {
        val src = engine.current as? PaginatedChapter ?: return 0
        val pageStart = src.charOffsetAtPage(engine.page)
        val text = src.displayText
        val segs = ttsIndex.segmentsOf(engine.chapterIndex)
        for (s in segs) {
            val i = text.indexOf(s.text)
            if (i >= pageStart) return s.index
        }
        return segs.firstOrNull()?.index ?: 0
    }

    // 听书：高亮当前段 + 自动翻页
    var snapTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(
        playerState.bookId, playerState.chapter, playerState.segment,
        engine.chapterIndex, pal, snapTick
    ) {
        val src = engine.current as? PaginatedChapter ?: return@LaunchedEffect
        val playingHere = playerState.bookId == book.id &&
            playerState.chapter == engine.chapterIndex &&
            playerState.segment >= 0
        if (!playingHere) {
            src.highlightByText("", 0)
            engine.highlightVersion++
            return@LaunchedEffect
        }
        val seg = playerState.segments.firstOrNull { it.index == playerState.segment }
            ?: ttsIndex.segmentsOf(engine.chapterIndex).firstOrNull { it.index == playerState.segment }
            ?: return@LaunchedEffect
        val color = (pal.primary.copy(alpha = 0.22f)).toArgb()
        val idx = src.highlightByText(seg.text, color)
        engine.highlightVersion++
        if (idx >= 0) {
            val target = src.pageOfCharOffset(idx)
            if (target != engine.page) {
                engine.page = target
                anchor = src.charOffsetAtPage(target)
            }
        }
    }

    LaunchedEffect(engine.chapterIndex, engine.page) {
        delay(700)
        persistProgress()
    }
    DisposableEffect(book.id) {
        onDispose { persistProgress() }
    }
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // 阅读亮度
    val ctx = LocalContext.current
    DisposableEffect(settings.brightness) {
        val act = ctx.findActivity()
        val old = act?.window?.attributes?.screenBrightness ?: -1f
        if (act != null) {
            val attrs = act.window.attributes
            attrs.screenBrightness = if (settings.brightness <= 0.02f) {
                android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            } else {
                settings.brightness
            }
            act.window.attributes = attrs
        }
        onDispose {
            val a = ctx.findActivity() ?: return@onDispose
            val attrs = a.window.attributes
            attrs.screenBrightness = old
            a.window.attributes = attrs
        }
    }

    BackHandler {
        when {
            showChapters -> showChapters = false
            showSettings -> showSettings = false
            showUi -> showUi = false
            else -> {
                persistProgress()
                onBack()
            }
        }
    }

    val bgBrush = remember(pal) {
        Brush.verticalGradient(listOf(pal.readBg, pal.readBg2))
    }

    Box(Modifier.fillMaxSize().background(bgBrush)) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(start = settings.marginH.dp, end = settings.marginH.dp, top = topPad, bottom = bottomPad)
                .onSizeChanged { textArea = it }
        ) {
            if (settings.pageAnim == PageAnim.SCROLL) {
                ScrollReader(
                    engine = engine,
                    pageHeightPx = textArea.height,
                    hasNextChapter = engine.chapterIndex + 1 < chapters.size,
                    onTapCenter = { showUi = !showUi },
                    onNextChapter = { nextChapter() },
                    onPageSettled = { offset -> anchor = offset }
                )
            } else {
                ReaderPager(
                    engine = engine,
                    pageAnim = settings.pageAnim,
                    bgBrush = bgBrush,
                    onTurnForward = { goNext() },
                    onTurnBackward = { goPrev() },
                    onTapCenter = { showUi = !showUi }
                )
            }
        }

        if (settings.showChapterTitle && !showUi) {
            Text(
                text = engine.titleOf(engine.chapterIndex).ifBlank { book.title },
                color = pal.readSub,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = settings.marginH.dp, end = settings.marginH.dp, top = statusBar + 10.dp)
            )
        }

        if (settings.showStatus && !showUi) {
            ReaderStatusLine(
                pal = pal,
                engine = engine,
                total = chapters.size,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = settings.marginH.dp, end = settings.marginH.dp, bottom = navBar + 4.dp)
            )
        }

        if (engine.loading && engine.current == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    color = pal.readSub,
                    strokeWidth = 2.dp
                )
            }
        }

        engine.error?.let { err ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(err, color = pal.readSub, fontSize = 14.sp)
            }
        }

        // 工具栏：上栏从上滑下，下栏从下升起
        AnimatedVisibility(
            visible = showUi,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { showUi = false } }
            )
        }

        AnimatedVisibility(
            visible = showUi,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it }
        ) {
            ReaderTopBar(
                pal = pal,
                title = book.title,
                chapter = engine.titleOf(engine.chapterIndex),
                onBack = {
                    persistProgress()
                    onBack()
                }
            )
        }

        AnimatedVisibility(
            visible = showUi,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it },
            exit = slideOutVertically { it }
        ) {
            ReaderBottomBar(
                pal = pal,
                chapterIndex = engine.chapterIndex,
                total = chapters.size,
                onChapters = { showChapters = true },
                onPrev = { goPrev() },
                onNext = { goNext() },
                onSettings = { showSettings = true },
                onTts = { showTts = true },
                onSeek = { jumpToChapter(it) }
            )
        }

        // 听书悬浮条：播放时挂在右侧，可暂停、看倒计时、跳到朗读处，点条身打开听书面板
        if (playerState.bookId == book.id && (playerState.playing || playerState.timerRemainingMs > 0)) {
            Row(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable { showTts = true }
                    .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (playerState.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (playerState.playing) "暂停" else "继续",
                    tint = Color.White,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable {
                            if (playerState.playing) container.ttsPlayer.pause()
                            else container.ttsPlayer.resume()
                        }
                )
                if (playerState.timerRemainingMs > 0) {
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "%d:%02d".format(
                            playerState.timerRemainingMs / 60000,
                            (playerState.timerRemainingMs / 1000) % 60
                        ),
                        fontSize = 10.sp,
                        color = Color.White
                    )
                }
                Spacer(Modifier.width(5.dp))
                Icon(
                    Icons.Rounded.CenterFocusStrong,
                    "跳到正在读的段落",
                    tint = Color.White,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { snapTick++ }
                )
            }
        }
    }

    if (showTts) {
        TtsPanel(
            container = container,
            book = book,
            chapterTitles = engine.chapterTitles,
            currentChapter = engine.chapterIndex,
            startSegment = startSegmentOfCurrentPage(),
            onDismiss = { showTts = false },
            onJumpToChapter = { jumpToChapter(it) }
        )
    }

    ChapterDrawer(
        visible = showChapters,
        bookTitle = book.title,
        titles = engine.chapterTitles,
        current = engine.chapterIndex,
        audioChapters = ttsIndex.completedChapters().toSet(),
        onSelect = { jumpToChapter(it) },
        onDismiss = { showChapters = false }
    )

    if (showSettings) {
        ReaderSettingsSheet(
            settings = settings,
            onChange = { block -> container.settings.update(block) },
            onDismiss = { showSettings = false }
        )
    }
}

/** 上下滑动模式：一页一个块，竖向连续滚，滚到底点「下一章」 */
@Composable
private fun ScrollReader(
    engine: ReaderEngine,
    pageHeightPx: Int,
    hasNextChapter: Boolean,
    onTapCenter: () -> Unit,
    onNextChapter: () -> Unit,
    onPageSettled: (Int) -> Unit
) {
    val pages = engine.currentPageCount()
    val initial = engine.page.coerceIn(0, (pages - 1).coerceAtLeast(0))
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initial)
    val density = LocalDensity.current
    var lastChapter by remember { mutableIntStateOf(engine.chapterIndex) }

    LaunchedEffect(engine.chapterIndex) {
        if (engine.chapterIndex != lastChapter) {
            lastChapter = engine.chapterIndex
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(listState, pages) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { idx ->
            if (idx in 0 until pages && idx != engine.page) {
                engine.page = idx
                onPageSettled(engine.charOffsetNow())
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures { onTapCenter() }
        },
        contentPadding = PaddingValues(0.dp)
    ) {
        items(pages) { i ->
            val src = engine.current
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(with(density) { pageHeightPx.toDp() })
            ) {
                drawIntoCanvas {
                    it.nativeCanvas.save()
                    src?.drawPage(it.nativeCanvas, i)
                    it.nativeCanvas.restore()
                }
            }
        }
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 26.dp),
                contentAlignment = Alignment.Center
            ) {
                if (hasNextChapter) {
                    Text(
                        "下一章",
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clickable { onNextChapter() }
                            .padding(horizontal = 22.dp, vertical = 10.dp)
                    )
                } else {
                    Text("已经是最后一章了", fontSize = 12.sp)
                }
            }
        }
    }
}

private fun typefaceOf(font: ReadFont): Typeface = when (font) {
    ReadFont.SYSTEM -> Typeface.create("sans-serif", Typeface.NORMAL)
    ReadFont.SERIF -> Typeface.create("serif", Typeface.NORMAL)
    ReadFont.LIGHT -> Typeface.create("sans-serif-light", Typeface.NORMAL)
}

@Composable
private fun ReaderStatusLine(
    pal: SkinPalette,
    engine: ReaderEngine,
    total: Int,
    modifier: Modifier = Modifier
) {
    var time by remember { mutableStateOf("") }
    var battery by remember { mutableIntStateOf(-1) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        val fmt = DateTimeFormatter.ofPattern("HH:mm")
        while (true) {
            time = LocalTime.now().format(fmt)
            delay(20000)
        }
    }

    DisposableEffect(Unit) {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val l = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val s = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                if (l >= 0 && s > 0) battery = l * 100 / s
            }
        }
        val sticky = context.registerReceiver(receiver, filter)
        if (sticky != null) {
            val l = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val s = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (l >= 0 && s > 0) battery = l * 100 / s
        }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    val pages = engine.currentPageCount().coerceAtLeast(1)
    val inChapter = (engine.page + 1).toFloat() / pages.toFloat()
    val percent = if (total <= 0) 0 else {
        (((engine.chapterIndex + inChapter) / total.toFloat()) * 100).toInt().coerceIn(0, 100)
    }

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(time, color = pal.readSub, fontSize = 10.5.sp)
        Spacer(Modifier.width(10.dp))
        if (battery >= 0) {
            Text("$battery%", color = pal.readSub, fontSize = 10.5.sp)
        }
        Spacer(Modifier.weight(1f))
        Text(
            "${engine.chapterIndex + 1}/${total.coerceAtLeast(1)}",
            color = pal.readSub,
            fontSize = 10.5.sp
        )
        Spacer(Modifier.width(8.dp))
        Text("$percent%", color = pal.readSub, fontSize = 10.5.sp)
    }
}

@Composable
private fun ReaderTopBar(
    pal: SkinPalette,
    title: String,
    chapter: String,
    onBack: () -> Unit
) {
    val statusBar = with(LocalDensity.current) { WindowInsets.statusBars.asPaddingValues().calculateTopPadding() }
    Row(
        Modifier
            .fillMaxWidth()
            .background(pal.readBg)
            .padding(top = statusBar + 4.dp, bottom = 10.dp, start = 6.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Rounded.ArrowBackIosNew, "返回", tint = pal.readText, modifier = Modifier.size(19.dp))
        }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                title,
                color = pal.readText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                chapter,
                color = pal.readSub,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ReaderBottomBar(
    pal: SkinPalette,
    chapterIndex: Int,
    total: Int,
    onChapters: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSettings: () -> Unit,
    onTts: () -> Unit,
    onSeek: (Int) -> Unit
) {
    val navBar = with(LocalDensity.current) { WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() }
    var sliderValue by remember(chapterIndex) { mutableStateOf(chapterIndex.toFloat()) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(pal.readBg2)
            .padding(bottom = navBar + 8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToolButton("上一章", pal, onPrev) {
                Icon(Icons.Rounded.KeyboardArrowLeft, null, tint = pal.readText, modifier = Modifier.size(24.dp))
            }
            ToolButton("目录", pal, onChapters) {
                Icon(Icons.Rounded.Menu, null, tint = pal.readText, modifier = Modifier.size(21.dp))
            }
            ToolButton("下一章", pal, onNext) {
                Icon(Icons.Rounded.KeyboardArrowRight, null, tint = pal.readText, modifier = Modifier.size(24.dp))
            }
            ToolButton("听书", pal, onTts) {
                Icon(Icons.Rounded.Headphones, null, tint = pal.readText, modifier = Modifier.size(21.dp))
            }
            ToolButton("设置", pal, onSettings) {
                Icon(Icons.Rounded.Tune, null, tint = pal.readText, modifier = Modifier.size(21.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Slider(
                value = sliderValue.coerceIn(0f, (total - 1).coerceAtLeast(0).toFloat()),
                onValueChange = { sliderValue = it },
                onValueChangeFinished = { onSeek(sliderValue.toInt()) },
                valueRange = 0f..(total - 1).coerceAtLeast(1).toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = pal.readText,
                    activeTrackColor = pal.readText.copy(alpha = 0.55f),
                    inactiveTrackColor = pal.readText.copy(alpha = 0.18f)
                ),
                modifier = Modifier.weight(1f).height(24.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "${(chapterIndex + 1).coerceAtMost(total.coerceAtLeast(1))}/${total.coerceAtLeast(1)}",
                color = pal.readSub,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun ToolButton(label: String, pal: SkinPalette, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Column(
        Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.height(3.dp))
        Text(label, color = pal.readSub, fontSize = 10.sp)
    }
}
