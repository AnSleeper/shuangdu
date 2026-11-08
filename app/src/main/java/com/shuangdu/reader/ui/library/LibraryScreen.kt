package com.shuangdu.reader.ui.library

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shuangdu.reader.AppContainer
import com.shuangdu.reader.data.Book
import com.shuangdu.reader.data.Skin
import com.shuangdu.reader.ui.reader.SkinOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val FILTER_ALL = "all"
private const val FILTER_NONE = "none"

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    container: AppContainer,
    snackbar: SnackbarHostState,
    busyText: String?,
    onOpenBook: (String) -> Unit,
    onImportUris: (List<Uri>) -> Unit,
    onNotify: (String) -> Unit,
    onCheckUpdate: () -> Unit,
    onOpenTts: () -> Unit
) {
    val books by container.library.books.collectAsStateWithLifecycle()
    val groups by container.library.groups.collectAsStateWithLifecycle()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val ttsMap by container.ttsConverter.state.collectAsStateWithLifecycle()
    val batchQueue by container.ttsConverter.batchQueue.collectAsStateWithLifecycle()
    val playerState by container.ttsPlayer.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    // 音频状态由转换层维护推送，书架只订阅（0=无 1=部分 2=整本）
    val audioStates by container.ttsConverter.audioStates.collectAsStateWithLifecycle()
    LaunchedEffect(books) {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            container.ttsConverter.ensureAudioStates(books)
        }
    }

    var menuOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<List<Book>>(emptyList()) }
    var selectionMode by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    var filter by remember { mutableStateOf(FILTER_ALL) }
    var showGroupPicker by remember { mutableStateOf(false) }
    var showNewGroup by remember { mutableStateOf(false) }
    var showSkinPicker by remember { mutableStateOf(false) }
    var newGroupName by remember { mutableStateOf("") }
    var assignAfterCreate by remember { mutableStateOf(false) }

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

    val visible = when (filter) {
        FILTER_ALL -> books
        FILTER_NONE -> books.filter { it.groupId == null }
        else -> books.filter { it.groupId == filter }
    }

    fun exitSelection() {
        selectionMode = false
        selected.clear()
    }

    BackHandler(enabled = selectionMode) { exitSelection() }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        // 顶栏
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                Text(
                    "已选 ${selected.size}",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    if (visible.isNotEmpty() && selected.size >= visible.size) {
                        selected.clear()
                    } else {
                        selected.clear()
                        selected.addAll(visible.map { it.id })
                    }
                }) {
                    Icon(Icons.Rounded.DoneAll, "全选", tint = MaterialTheme.colorScheme.onBackground)
                }
                IconButton(onClick = {
                    pendingDelete = books.filter { it.id in selected }
                }) {
                    Icon(Icons.Rounded.DeleteOutline, "删除", tint = MaterialTheme.colorScheme.onBackground)
                }
                IconButton(onClick = {
                    val targets = books.filter { it.id in selected }
                    if (targets.isEmpty()) return@IconButton
                    exitSelection()
                    onNotify("已加入转换队列：${targets.size} 本（书架封面显示进度）")
                    container.ttsConverter.convertMany(targets)
                }) {
                    Icon(Icons.Rounded.Headphones, "转音频", tint = MaterialTheme.colorScheme.onBackground)
                }
                IconButton(onClick = { showGroupPicker = true }) {
                    Icon(Icons.Rounded.DriveFileMove, "分组", tint = MaterialTheme.colorScheme.onBackground)
                }
                IconButton(onClick = { exitSelection() }) {
                    Icon(Icons.Rounded.Close, "取消", tint = MaterialTheme.colorScheme.onBackground)
                }
            } else {
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
                    Icon(Icons.Rounded.Add, "导入", tint = MaterialTheme.colorScheme.onBackground)
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Rounded.MoreVert,
                            "更多",
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
                            text = { Text("多选管理") },
                            leadingIcon = { Icon(Icons.Rounded.Check, null) },
                            onClick = {
                                menuOpen = false
                                selectionMode = true
                                selected.clear()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("新建分组") },
                            leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, null) },
                            onClick = {
                                menuOpen = false
                                newGroupName = ""
                                assignAfterCreate = false
                                showNewGroup = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("听书设置") },
                            leadingIcon = { Icon(Icons.Rounded.Headphones, null) },
                            onClick = {
                                menuOpen = false
                                onOpenTts()
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
                            text = { Text("皮肤") },
                            leadingIcon = { Icon(Icons.Rounded.Palette, null) },
                            onClick = {
                                menuOpen = false
                                showSkinPicker = true
                            }
                        )
                    }
                }
            }
        }

        // 分组筛选
        if (books.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip("全部", books.size, filter == FILTER_ALL) { filter = FILTER_ALL }
                }
                item {
                    val n = books.count { it.groupId == null }
                    FilterChip("未分组", n, filter == FILTER_NONE) { filter = FILTER_NONE }
                }
                items(groups, key = { it.id }) { g ->
                    val n = books.count { it.groupId == g.id }
                    FilterChip(
                        g.name,
                        n,
                        filter == g.id,
                        onLongClick = {
                            container.library.deleteGroup(g.id)
                            if (filter == g.id) filter = FILTER_ALL
                            onNotify("已删除分组「${g.name}」，书回到未分组")
                        }
                    ) { filter = g.id }
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
                Text(
                    busyText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (visible.isEmpty()) {
            if (books.isEmpty()) {
                EmptyState(onPick = { pickFiles.launch(arrayOf("*/*")) })
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "这个分组里还没有书",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 6.dp,
                    // 有迷你播放条时底部留出空间，别遮住最后一行书
                    bottom = if (playerState.active && !selectionMode) 84.dp else 28.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(13.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                items(visible, key = { it.id }) { book ->
                    val p = ttsMap[book.id]
                    val qIdx = batchQueue.indexOf(book.id)
                    BookCard(
                        book = book,
                        coverPath = container.repository.coverFile(book)?.absolutePath,
                        selectionMode = selectionMode,
                        selected = book.id in selected,
                        audioState = audioStates[book.id] ?: 0,
                        ttsRunning = p?.running == true,
                        ttsProgress = p?.let {
                            if (it.total <= 0) 0f else (it.done + it.failed).toFloat() / it.total
                        } ?: 0f,
                        queueLabel = if (qIdx >= 0 && p?.running != true && (audioStates[book.id] ?: 0) != 2) {
                            "排队 #${qIdx + 1}"
                        } else null,
                        onClick = {
                            if (selectionMode) {
                                if (book.id in selected) selected.remove(book.id) else selected.add(book.id)
                            } else {
                                onOpenBook(book.id)
                            }
                        },
                        onLongClick = {
                            if (!selectionMode) {
                                selectionMode = true
                                selected.clear()
                            }
                            if (book.id in selected) selected.remove(book.id) else selected.add(book.id)
                        }
                    )
                }
            }
        }
    }

        // 迷你播放条：听书会话进行中（含暂停）时贴在底部，点条身回到那本书
        if (playerState.active && !selectionMode) {
            MiniPlayerBar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 14.dp)
                    .padding(bottom = 12.dp),
                title = playerState.bookTitle.ifBlank { "正在听书" },
                subtitle = if (playerState.chapterTitle.isBlank()) {
                    "第 ${playerState.chapter + 1} 章"
                } else {
                    playerState.chapterTitle
                },
                playing = playerState.playing,
                onOpen = {
                    val id = playerState.bookId
                    if (id.isNotBlank() && container.library.find(id) != null) onOpenBook(id)
                },
                onToggle = {
                    if (playerState.playing) container.ttsPlayer.pause() else container.ttsPlayer.resume()
                },
                onClose = { container.ttsPlayer.stop() }
            )
        }
    }

    // 删除确认
    if (pendingDelete.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { pendingDelete = emptyList() },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(if (pendingDelete.size == 1) "移出书架" else "移出 ${pendingDelete.size} 本书") },
            text = {
                Text(
                    if (pendingDelete.size == 1) {
                        "《${pendingDelete.first().title}》会被从书架移除，并删掉导入时保存的副本。手机里原文件不受影响。"
                    } else {
                        "选中的 ${pendingDelete.size} 本书会被移除，并删掉导入时保存的副本。手机里原文件不受影响。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete.forEach { container.repository.delete(it) }
                    onNotify(if (pendingDelete.size == 1) "已移除" else "已移除 ${pendingDelete.size} 本")
                    pendingDelete = emptyList()
                    exitSelection()
                }) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = emptyList() }) { Text("取消") } }
        )
    }

    // 新建分组
    if (showNewGroup) {
        AlertDialog(
            onDismissRequest = { showNewGroup = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("新建分组") },
            text = {
                OutlinedTextField(
                    value = newGroupName,
                    onValueChange = { newGroupName = it },
                    singleLine = true,
                    placeholder = { Text("比如：在看 / 已读完") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val g = container.library.createGroup(newGroupName)
                    if (assignAfterCreate) {
                        container.library.assignGroup(selected, g.id)
                        exitSelection()
                        onNotify("已新建「${g.name}」并移入")
                    } else {
                        onNotify("已新建分组「${g.name}」")
                    }
                    assignAfterCreate = false
                    showNewGroup = false
                    filter = g.id
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showNewGroup = false }) { Text("取消") } }
        )
    }

    // 移动到分组
    if (showGroupPicker) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showGroupPicker = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.padding(bottom = 26.dp)) {
                Text(
                    "移到分组（${selected.size} 本）",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 22.dp, bottom = 8.dp)
                )
                GroupRow("未分组") {
                    container.library.assignGroup(selected, null)
                    showGroupPicker = false
                    exitSelection()
                    onNotify("已移到未分组")
                }
                groups.forEach { g ->
                    GroupRow(g.name) {
                        container.library.assignGroup(selected, g.id)
                        showGroupPicker = false
                        exitSelection()
                        onNotify("已移到「${g.name}」")
                    }
                }
                GroupRow("＋ 新建分组并移入") {
                    showGroupPicker = false
                    newGroupName = ""
                    assignAfterCreate = true
                    showNewGroup = true
                }
            }
        }
    }

    // 皮肤
    if (showSkinPicker) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showSkinPicker = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.padding(start = 22.dp, end = 22.dp, bottom = 30.dp)) {
                Text("皮肤", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    "书架和阅读页一起换",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Skin.entries.forEach { s ->
                        SkinOption(s, selected = settings.skin == s) {
                            container.settings.setSkin(s)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilterChip(
    label: String,
    count: Int,
    selected: Boolean,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 13.dp, vertical = 6.dp)
    ) {
        Text(
            "$label $count",
            fontSize = 12.sp,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
        )
    }
}

@Composable
private fun GroupRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun collectDocs(context: android.content.Context, treeUri: Uri): List<Uri> {    val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
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
private fun MiniPlayerBar(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    playing: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onClose: () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.Headphones,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onToggle) {
                Icon(
                    if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (playing) "暂停" else "继续",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Rounded.Close,
                    "退出听书",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
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
            Text(
                "书",
                fontSize = 40.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold
            )
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
    selectionMode: Boolean,
    selected: Boolean,
    audioState: Int,
    ttsRunning: Boolean,
    ttsProgress: Float,
    queueLabel: String?,
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
                .background(MaterialTheme.colorScheme.surfaceVariant)
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
                color = Color.Black.copy(alpha = 0.42f),
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
            // 整本转完：实心高亮耳机 + 主色圆底；部分转好：半透明描边感耳机 + 暗底
            if (audioState > 0 && !selectionMode) {
                val complete = audioState == 2
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(
                            if (complete) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                            else Color.Black.copy(alpha = 0.45f)
                        )
                        .padding(3.dp)
                ) {
                    Icon(
                        Icons.Rounded.Headphones,
                        if (complete) "整本已转完" else "部分章节已转",
                        tint = if (complete) MaterialTheme.colorScheme.onPrimary
                        else Color(0xFF9FD8C0).copy(alpha = 0.55f),
                        modifier = Modifier.size(11.dp)
                    )
                }
            }
            if (ttsRunning) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        "转 ${(ttsProgress * 100).toInt()}%",
                        fontSize = 8.sp,
                        color = Color(0xFF9FD8C0)
                    )
                }
            }
            if (!ttsRunning && queueLabel != null) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        queueLabel,
                        fontSize = 8.sp,
                        color = Color(0xFFC8D8C0)
                    )
                }
            }
            if (selectionMode) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = if (selected) 0.28f else 0.10f))
                )
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else Color.Black.copy(alpha = 0.35f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
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
