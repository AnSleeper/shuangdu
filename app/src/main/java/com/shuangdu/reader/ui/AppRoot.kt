package com.shuangdu.reader.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import com.shuangdu.reader.AppContainer
import com.shuangdu.reader.data.ImportResult
import com.shuangdu.reader.ui.library.LibraryScreen
import com.shuangdu.reader.ui.reader.ReaderScreen
import com.shuangdu.reader.ui.theme.SdTheme
import com.shuangdu.reader.ui.theme.paletteOf
import com.shuangdu.reader.ui.tts.TtsSettingsScreen
import com.shuangdu.reader.ui.update.UpdateHost
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Route {
    data object Library : Route
    data object Tts : Route
    data class Reader(val bookId: String) : Route
}

@Composable
fun AppRoot(
    container: AppContainer,
    pendingOpen: MutableState<Uri?>,
    pendingBook: MutableState<String?> = remember { mutableStateOf(null) }
) {
    val settings by container.settings.state.collectAsStateWithLifecycle()
    SdTheme(settings.skin) {
        SystemBars(settings.skin.isDark, paletteOf(settings.skin).appBg)
        AppRootContent(container, pendingOpen, pendingBook)
    }
}

/** 状态栏/导航栏跟着皮肤走：透明底 + 深浅图标，顺手把窗口底色也换掉避免闪白 */
@Composable
private fun SystemBars(dark: Boolean, bg: androidx.compose.ui.graphics.Color) {
    val view = LocalView.current
    val context = LocalContext.current
    SideEffect {
        val activity = context.findActivity() ?: return@SideEffect
        val window = activity.window
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(bg.toArgb())
        )
        runCatching {
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
}

private fun android.content.Context.findActivity(): android.app.Activity? {
    var ctx: android.content.Context? = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Composable
private fun AppRootContent(
    container: AppContainer,
    pendingOpen: MutableState<Uri?>,
    pendingBook: MutableState<String?>
) {
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val pal = paletteOf(settings.skin)
    var route by remember { mutableStateOf<Route>(Route.Library) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var busyText by remember { mutableStateOf<String?>(null) }
    var updateTick by remember { mutableStateOf(0) }

    fun notify(msg: String) {
        scope.launch { snackbar.showSnackbar(msg) }
    }

    suspend fun importOne(uri: Uri): String? {
        busyText = "正在导入…"
        return try {
            when (val r = withContext(Dispatchers.IO) { container.repository.import(uri) }) {
                is ImportResult.Success -> {
                    if (r.existed) notify("《${r.book.title}》已经在书架里了") else notify("已导入《${r.book.title}》")
                    r.book.id
                }
                is ImportResult.Failure -> {
                    notify("导入失败：${r.message}")
                    null
                }
            }
        } finally {
            busyText = null
        }
    }

    fun importAndOpen(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            var lastId: String? = null
            var success = 0
            uris.forEach { uri ->
                val id = importOne(uri)
                if (id != null) {
                    lastId = id
                    success++
                }
            }
            if (uris.size > 1) notify("导入完成：$success/${uris.size}")
            if (success == 1 && uris.size == 1) lastId?.let { route = Route.Reader(it) }
        }
    }

    // 听书播放器：一章播完自动接下一章（下一章已转好的前提下），挂在播放器上跟界面无关
    LaunchedEffect(Unit) {
        val player = container.ttsPlayer
        player.dirProvider = { bookId ->
            java.io.File(container.repository.bookDir(bookId), "tts")
        }
        player.nextChapterProvider = { bookId, chapter ->
            val book = container.library.books.value.firstOrNull { it.id == bookId }
            val index = book?.let { container.ttsConverter.indexOf(it) }
            val segs = if (book != null && index != null &&
                chapter in 0 until container.repository.chapterCount(book)
            ) {
                index.segmentsOf(chapter)
            } else {
                emptyList()
            }
            if (segs.isNotEmpty() && segs.all { it.done }) segs else null
        }
        // 通知/书架迷你条要显示书名与章节名
        player.metaProvider = { bookId, chapter ->
            val book = container.library.books.value.firstOrNull { it.id == bookId }
            if (book == null) null else {
                val chTitle = runCatching {
                    container.repository.loadChapters(book).getOrNull(chapter)?.title.orEmpty()
                }.getOrDefault("")
                book.title to chTitle
            }
        }
    }

    // 从通知点进来：直接打开那本书
    LaunchedEffect(pendingBook.value) {
        val id = pendingBook.value ?: return@LaunchedEffect
        pendingBook.value = null
        if (container.library.find(id) != null) route = Route.Reader(id)
    }

    // 外部用别的 app 打开 txt/epub
    LaunchedEffect(pendingOpen.value) {
        val uri = pendingOpen.value ?: return@LaunchedEffect
        pendingOpen.value = null
        importAndOpen(listOf(uri))
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(pal.appBg, pal.appBg2)))
    ) {
        AnimatedContent(
            targetState = route,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) },
            label = "route"
        ) { r ->
            when (r) {
                is Route.Library -> LibraryScreen(
                    container = container,
                    snackbar = snackbar,
                    busyText = busyText,
                    onOpenBook = { route = Route.Reader(it) },
                    onImportUris = { importAndOpen(it) },
                    onNotify = { notify(it) },
                    onCheckUpdate = { updateTick++ },
                    onOpenTts = { route = Route.Tts }
                )

                is Route.Tts -> TtsSettingsScreen(
                    container = container,
                    onBack = { route = Route.Library }
                )

                is Route.Reader -> ReaderScreen(
                    container = container,
                    bookId = r.bookId,
                    onBack = { route = Route.Library },
                    onNotify = { notify(it) }
                )
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // 没有这层 padding 的话，提示条会画在系统导航栏底下（实测被三键导航切掉大半）
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        )

        UpdateHost(container = container, trigger = updateTick)
    }
}
