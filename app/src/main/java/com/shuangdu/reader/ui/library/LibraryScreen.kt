package com.shuangdu.reader.ui.library

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shuangdu.reader.AppContainer
import com.shuangdu.reader.data.Book
import com.shuangdu.reader.data.BookFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    container: AppContainer,
    snackbar: SnackbarHostState,
    busyText: String?,
    onOpenBook: (String) -> Unit,
    onImportUris: (List<Uri>) -> Unit,
    onNotify: (String) -> Unit,
    onCheckUpdate: () -> Unit
) {
    val books by container.library.books.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    var menuOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Book?>(null) }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onImportUris(uris)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val found = withContext(Dispatchers.IO) { collectDocs(context, uri) }
            if (found.isEmpty()) onNotify("这个文件夹里没找到 txt / epub") else onImportUris(found)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "书架",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (books.isEmpty()) "还没有书" else "${books.size} 本 · 全部在本地",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { pickFiles.launch(arrayOf("*/*")) }) {
                Icon(Icons.Rounded.Add, contentDescription = "导入", tint = MaterialTheme.colorScheme.onBackground)
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = "更多",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("导入文件夹") },
                        leadingIcon = { Icon(Icons.Rounded.FolderOpen, null) },
                        onClick = {
                            menuOpen = false
                            pickFolder.launch(null)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("检查更新") },
                        leadingIcon = { Icon(Icons.Rounded.Refresh, null) },
                        onClick = {
                            menuOpen = false
                            onCheckUpdate()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("阅读设置") },
                        leadingIcon = { Icon(Icons.Rounded.Settings, null) },
                        onClick = { menuOpen = false }
                    )
                }
            }
        }

        if (busyText != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LinearProgressIndicator(
                    modifier = Modifier.weight(1f).height(3.dp).clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(Modifier.width(10.dp))
                Text(busyText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (books.isEmpty()) {
            EmptyState(onPick = { pickFiles.launch(arrayOf("*/*")) })
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(13.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                items(books, key = { it.id }) { book ->
                    BookCard(
                        book = book,
                        coverPath = container.repository.coverFile(book)?.absolutePath,
                        onClick = { onOpenBook(book.id) },
                        onLongClick = { pendingDelete = book }
                    )
                }
            }
        }
    }

    pendingDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("移出书架") },
            text = { Text("《${book.title}》会被从书架移除，并删掉导入时保存的副本。手机里原文件不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    container.repository.delete(book)
                    pendingDelete = null
                    onNotify("已移除《${book.title}》")
                }) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }
        )
    }
}

private fun collectDocs(context: android.content.Context, treeUri: Uri): List<Uri> {
    val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
    val out = ArrayList<Uri>()
    fun walk(dir: DocumentFile, depth: Int) {
        if (depth > 6) return
        val children = runCatching { dir.listFiles() }.getOrDefault(emptyArray())
        for (f in children) {
            if (f.isDirectory) {
                walk(f, depth + 1)
            } else {
                val name = f.name.orEmpty().lowercase()
                if (name.endsWith(".txt") || name.endsWith(".epub")) out.add(f.uri)
            }
        }
    }
    walk(root, 0)
    return out
}

@Composable
private fun EmptyState(onPick: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text("书", fontSize = 40.sp, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(20.dp))
        Text("书架还是空的", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(6.dp))
        Text(
            "导入手机里的 TXT / EPUB 就能开始读",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(22.dp))
        FilledTonalButton(onClick = onPick) {
            Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("选择文件")
        }
        Spacer(Modifier.height(40.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    book: Book,
    coverPath: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val progress = if (book.totalChapters > 1) {
        (book.chapterIndex.toFloat() / (book.totalChapters - 1).toFloat()).coerceIn(0f, 1f)
    } else 0f

    Column(
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFFE7E2D8))
        ) {
            val bitmap = rememberBookCover(coverPath)
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = book.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                PlaceholderCover(book)
            }
            Surface(
                color = Color.Black.copy(alpha = 0.34f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
            ) {
                Text(
                    book.formatName,
                    fontSize = 8.sp,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
            if (progress > 0.001f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(2.5.dp),
                    color = Color(0xFF7ECFB6),
                    trackColor = Color.Black.copy(alpha = 0.25f)
                )
            }
            if (progress >= 0.999f) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    null,
                    tint = Color(0xFF7ECFB6),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(14.dp)
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            book.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 17.sp
        )
        if (book.author.isNotBlank()) {
            Text(
                book.author,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private val COVER_GRADIENTS = listOf(
    Color(0xFF3E5B52) to Color(0xFF7FA892),
    Color(0xFF5B4A63) to Color(0xFFA896B5),
    Color(0xFF6B4A3E) to Color(0xFFB58F72),
    Color(0xFF3C4A63) to Color(0xFF8CA0BE),
    Color(0xFF635236) to Color(0xFFAF9A6C),
    Color(0xFF44584A) to Color(0xFF87A98C),
    Color(0xFF5A3F45) to Color(0xFFA87F86),
    Color(0xFF354B52) to Color(0xFF7EA0A8)
)

@Composable
private fun PlaceholderCover(book: Book) {
    val pair = COVER_GRADIENTS[(book.title.hashCode().let { if (it < 0) -it else it }) % COVER_GRADIENTS.size]
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(pair.first, pair.second))),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = book.title.take(8),
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 17.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(10.dp)
        )
    }
}

@Composable
private fun rememberBookCover(path: String?): androidx.compose.ui.graphics.ImageBitmap? {
    val state = androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null,
        key1 = path
    ) {
        value = if (path == null) null else withContext(Dispatchers.IO) {
            runCatching {
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
            }.getOrNull()
        }
    }
    return state.value
}
