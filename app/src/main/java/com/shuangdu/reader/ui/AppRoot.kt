package com.shuangdu.reader.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.shuangdu.reader.AppContainer
import com.shuangdu.reader.data.ImportResult
import com.shuangdu.reader.ui.library.LibraryScreen
import com.shuangdu.reader.ui.reader.ReaderScreen
import com.shuangdu.reader.ui.update.UpdateHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Route {
    data object Library : Route
    data class Reader(val bookId: String) : Route
}

@Composable
fun AppRoot(container: AppContainer, pendingOpen: MutableState<Uri?>) {
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

    // 外部用别的 app 打开 txt/epub
    LaunchedEffect(pendingOpen.value) {
        val uri = pendingOpen.value ?: return@LaunchedEffect
        pendingOpen.value = null
        importAndOpen(listOf(uri))
    }

    Box(Modifier.fillMaxSize()) {
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
                    onCheckUpdate = { updateTick++ }
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
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        UpdateHost(container = container, trigger = updateTick)
    }
}
