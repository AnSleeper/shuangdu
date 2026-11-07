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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Menu
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
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
import com.shuangdu.reader.data.ReadFont
import com.shuangdu.reader.ui.theme.ReadPalette
import com.shuangdu.reader.ui.theme.paletteOf
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
    val palette = paletteOf(settings.theme)
    val density = LocalDensity.current
    val view = LocalView.current

    var anchor by remember(bookId) { mutableIntStateOf(book.charOffset) }
    var showUi by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var textArea by remember { mutableStateOf(IntSize.Zero) }

    val statusBar = with(density) { WindowInsets.statusBars.asPaddingValues().calculateTopPadding() }
    val navBar = with(density) { WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() }
    val topPad = statusBar + 30.dp
    val bottomPad = navBar + if (settings.showStatus) 24.dp else 10.dp

    val style: PageStyle? = remember(
        textArea, settings.fontSize, settings.lineSpacing, settings.font, settings.indent, palette
    ) {
        if (textArea.width <= 0) null else PageStyle(
            textSizePx = with(density) { settings.fontSize.sp.toPx() },
            lineSpacingMult = settings.lineSpacing,
            typeface = typefaceOf(settings.font),
            color = palette.text.toArgb(),
            widthPx = textArea.width,
            indent = settings.indent
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
            val out = ArrayList<Triple<Int, String, PaginatedChapter>>(3)
            for (i in (center - 1)..(center + 1)) {
                if (i < 0 || i >= chapters.size) continue
                if (engine.layouts[i] != null) continue
                val text = engine.textOf(i) ?: repo.readChapter(book, i)
                if (text.isEmpty() && i != center) continue
                out.add(Triple(i, text, PaginatedChapter.build(text, st, h)))
            }
            out
        }
        built.forEach { (i, text, layout) ->
            engine.putText(i, text)
            engine.putLayout(i, layout)
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

    fun jumpToChapter(index: Int) {
        if (index !in chapters.indices) return
        showChapters = false
        anchor = 0
        engine.moveToChapter(index, 0)
    }

    // 进度落盘
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

    Box(Modifier.fillMaxSize().background(palette.bg)) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(start = settings.marginH.dp, end = settings.marginH.dp, top = topPad, bottom = bottomPad)
                .onSizeChanged { textArea = it }
        ) {
            ReaderPager(
                engine = engine,
                pageAnim = settings.pageAnim,
                background = palette.bg,
                onTurnForward = { goNext() },
                onTurnBackward = { goPrev() },
                onTapCenter = { showUi = !showUi }
            )
        }

        // 顶部章节名
        if (settings.showChapterTitle && !showUi) {
            Text(
                text = engine.titleOf(engine.chapterIndex).ifBlank { book.title },
                color = palette.sub,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = settings.marginH.dp, end = settings.marginH.dp, top = statusBar + 8.dp)
            )
        }

        // 底部时间电量进度
        if (settings.showStatus && !showUi) {
            ReaderStatusLine(
                palette = palette,
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
                    color = palette.sub,
                    strokeWidth = 2.dp
                )
            }
        }

        engine.error?.let { err ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(err, color = palette.sub, fontSize = 14.sp)
            }
        }

        // 工具栏
        AnimatedVisibility(
            visible = showUi,
            enter = fadeIn() + slideInVertically { -it / 4 },
            exit = fadeOut() + slideOutVertically { -it / 4 }
        ) {
            Column(Modifier.fillMaxSize()) {
                ReaderTopBar(
                    palette = palette,
                    title = book.title,
                    chapter = engine.titleOf(engine.chapterIndex),
                    onBack = {
                        persistProgress()
                        onBack()
                    }
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .pointerInput(Unit) { detectTapGestures { showUi = false } }
                )
                ReaderBottomBar(
                    palette = palette,
                    chapterIndex = engine.chapterIndex,
                    total = chapters.size,
                    onChapters = { showChapters = true },
                    onPrev = { goPrev() },
                    onNext = { goNext() },
                    onSettings = { showSettings = true },
                    onTts = { onNotify("听书功能会在下一版接入") },
                    onSeek = { jumpToChapter(it) }
                )
            }
        }
    }

    if (showChapters) {
        ChapterListSheet(
            titles = engine.chapterTitles,
            current = engine.chapterIndex,
            onSelect = { jumpToChapter(it) },
            onDismiss = { showChapters = false }
        )
    }

    if (showSettings) {
        ReaderSettingsSheet(
            settings = settings,
            onChange = { block -> container.settings.update(block) },
            onDismiss = { showSettings = false }
        )
    }
}

private fun typefaceOf(font: ReadFont): Typeface = when (font) {
    ReadFont.SYSTEM -> Typeface.create("sans-serif", Typeface.NORMAL)
    ReadFont.SERIF -> Typeface.create("serif", Typeface.NORMAL)
    ReadFont.KAI -> Typeface.create("serif", Typeface.NORMAL)
}

@Composable
private fun ReaderStatusLine(
    palette: ReadPalette,
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
        Text(time, color = palette.sub, fontSize = 10.5.sp)
        Spacer(Modifier.width(10.dp))
        if (battery >= 0) {
            Text("$battery%", color = palette.sub, fontSize = 10.5.sp)
        }
        Spacer(Modifier.weight(1f))
        Text(
            "${engine.chapterIndex + 1}/${total.coerceAtLeast(1)}",
            color = palette.sub,
            fontSize = 10.5.sp
        )
        Spacer(Modifier.width(8.dp))
        Text("$percent%", color = palette.sub, fontSize = 10.5.sp)
    }
}

@Composable
private fun ReaderTopBar(
    palette: ReadPalette,
    title: String,
    chapter: String,
    onBack: () -> Unit
) {
    val statusBar = with(LocalDensity.current) { WindowInsets.statusBars.asPaddingValues().calculateTopPadding() }
    Row(
        Modifier
            .fillMaxWidth()
            .background(palette.bg.copy(alpha = 0.98f))
            .padding(top = statusBar + 4.dp, bottom = 10.dp, start = 6.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Rounded.ArrowBackIosNew, "返回", tint = palette.text, modifier = Modifier.size(19.dp))
        }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                title,
                color = palette.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                chapter,
                color = palette.sub,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ReaderBottomBar(
    palette: ReadPalette,
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
            .background(palette.bg.copy(alpha = 0.98f))
            .padding(bottom = navBar + 8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToolButton("上一章", palette, onPrev) {
                Icon(Icons.Rounded.KeyboardArrowLeft, null, tint = palette.text, modifier = Modifier.size(24.dp))
            }
            ToolButton("目录", palette, onChapters) {
                Icon(Icons.Rounded.Menu, null, tint = palette.text, modifier = Modifier.size(21.dp))
            }
            ToolButton("下一章", palette, onNext) {
                Icon(Icons.Rounded.KeyboardArrowRight, null, tint = palette.text, modifier = Modifier.size(24.dp))
            }
            ToolButton("听书", palette, onTts) {
                Icon(Icons.Rounded.Headphones, null, tint = palette.text, modifier = Modifier.size(21.dp))
            }
            ToolButton("字号", palette, onSettings) {
                Icon(Icons.Rounded.FormatSize, null, tint = palette.text, modifier = Modifier.size(21.dp))
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
                    thumbColor = palette.text,
                    activeTrackColor = palette.text.copy(alpha = 0.55f),
                    inactiveTrackColor = palette.text.copy(alpha = 0.18f)
                ),
                modifier = Modifier.weight(1f).height(24.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "${(chapterIndex + 1).coerceAtMost(total.coerceAtLeast(1))}/${total.coerceAtLeast(1)}",
                color = palette.sub,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun ToolButton(label: String, palette: ReadPalette, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Column(
        Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.height(3.dp))
        Text(label, color = palette.sub, fontSize = 10.sp)
    }
}
