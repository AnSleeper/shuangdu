package com.shuangdu.reader.ui.tts

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shuangdu.reader.AppContainer
import com.shuangdu.reader.tts.TtsModels
import com.shuangdu.reader.tts.VoiceItem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsSettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val keyConfigured by container.fishKey.configured.collectAsStateWithLifecycle()
    val tts by container.ttsSettings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    androidx.activity.compose.BackHandler { onBack() }

    var keyInput by remember { mutableStateOf("") }
    var showKeyInput by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }
    var showVoicePicker by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBackIosNew, "返回", modifier = Modifier.size(19.dp))
                }
                Text("听书设置", style = MaterialTheme.typography.titleLarge)
            }

            LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
                item {
                    SectionTitle("1. Fish Audio 密钥")
                    Text(
                        "打开 fish.audio 注册账号 → 右上角头像 → API Keys → 新建密钥，复制粘贴到这里。" +
                            "密钥用系统密钥库加密保存在本机，不会上传到任何服务器，只用于向 Fish Audio 发起合成请求。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(12.dp))

                    if (keyConfigured && !showKeyInput) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.Check,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                container.fishKey.masked(),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                showKeyInput = true
                                keyInput = ""
                            }) { Text("更换") }
                            TextButton(onClick = {
                                container.fishKey.clear()
                                testResult = null
                            }) { Text("清除") }
                        }
                    } else {
                        OutlinedTextField(
                            value = keyInput,
                            onValueChange = { keyInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("粘贴 API Key（sk-...）") },
                            visualTransformation = PasswordVisualTransformation()
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                runCatching { container.fishKey.save(keyInput) }
                                    .onSuccess {
                                        keyInput = ""
                                        showKeyInput = false
                                        testResult = null
                                    }
                            },
                            enabled = keyInput.isNotBlank()
                        ) { Text("保存密钥") }
                    }
                }

                item {
                    Spacer(Modifier.height(24.dp))
                    SectionTitle("2. 合成模型")
                    Text(
                        "默认 s2.1-pro-free，免费、无每日上限，按并发限流。付费模型质量和速度更好。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TtsModels.all.forEach { id ->
                            ModelRow(
                                title = TtsModels.label(id),
                                selected = tts.modelId == id,
                                onClick = { container.ttsSettings.update { it.copy(modelId = id) } }
                            )
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(24.dp))
                    SectionTitle("3. 默认音色")
                    Text(
                        "所有书默认用这个音色朗读，单本书可以在阅读页的听书面板里单独改。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { showVoicePicker = true }
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                tts.globalVoiceName.ifBlank { "还没有选择音色" },
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                if (tts.globalVoiceId.isBlank()) "点击从社区音色库里挑一个" else tts.globalVoiceId,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            Icons.Rounded.KeyboardArrowDown,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                item {
                    Spacer(Modifier.height(24.dp))
                    SectionTitle("4. 并发数")
                    Text(
                        "越大转得越快，但更容易触发限流。默认 5；被限流时程序会自动降并发并退避重试。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Slider(
                            value = tts.concurrency.toFloat(),
                            onValueChange = { v ->
                                container.ttsSettings.update { it.copy(concurrency = v.toInt().coerceIn(1, 7)) }
                            },
                            valueRange = 1f..7f,
                            steps = 5,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("${tts.concurrency}", style = MaterialTheme.typography.titleMedium)
                    }
                }

                item {
                    Spacer(Modifier.height(18.dp))
                    Button(
                        onClick = {
                            testing = true
                            testResult = null
                            scope.launch {
                                val r = container.fishClient.testConnection(tts.modelId, tts.globalVoiceId)
                                testing = false
                                r.onSuccess { ms ->
                                    testOk = true
                                    testResult = "连接正常，试听合成用了 ${ms / 1000.0} 秒"
                                }.onFailure {
                                    testOk = false
                                    testResult = it.message ?: "测试失败"
                                }
                            }
                        },
                        enabled = !testing && keyConfigured
                    ) {
                        if (testing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("正在测试…")
                        } else {
                            Text("测试连接")
                        }
                    }
                    testResult?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (testOk) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                    }
                    if (!keyConfigured) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "填好密钥才能测试",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showVoicePicker) {
        VoicePickerSheet(
            container = container,
            currentId = tts.globalVoiceId,
            onDismiss = { showVoicePicker = false },
            onPick = { v ->
                container.ttsSettings.update { it.copy(globalVoiceId = v.id, globalVoiceName = v.title) }
                showVoicePicker = false
            }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
private fun ModelRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(
                Icons.Rounded.Check,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicePickerSheet(
    container: AppContainer,
    currentId: String,
    onDismiss: () -> Unit,
    onPick: (VoiceItem) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var voices by remember { mutableStateOf<List<VoiceItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var keyword by remember { mutableStateOf("") }
    var zhOnly by remember { mutableStateOf(true) }
    var favOnly by remember { mutableStateOf(false) }
    val favorites by container.ttsSettings.favorites.collectAsStateWithLifecycle()

    val player = remember { PreviewPlayer() }
    DisposableEffect(Unit) { onDispose { player.release() } }

    fun load(q: String, zh: Boolean) {
        loading = true
        error = null
        scope.launch {
            container.fishClient.listVoices(keyword = q, language = if (zh) "zh" else "")
                .onSuccess { voices = it }
                .onFailure { error = it.message ?: "加载失败" }
            loading = false
        }
    }

    LaunchedEffect(Unit) { load("", true) }

    // 收藏模式直接展示本地收藏，不走网络
    val displayVoices: List<VoiceItem> = if (favOnly) {
        favorites.map { VoiceItem(it.id, it.title, "", "", "", emptyList(), emptyList(), 0) }
    } else voices

    // 点了音色先确认，防误触
    var pendingPick by remember { mutableStateOf<VoiceItem?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.fillMaxWidth().heightIn(min = 420.dp)) {
            Text(
                "选择音色",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 20.dp, bottom = 10.dp)
            )
            OutlinedTextField(
                value = keyword,
                onValueChange = {
                    keyword = it
                    if (it.length >= 2 || it.isEmpty()) load(it, zhOnly)
                },
                singleLine = true,
                placeholder = { Text("搜音色名字，比如 男声 / 少女") },
                leadingIcon = { Icon(Icons.Rounded.Search, null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            )
            Row(
                Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LangChip("收藏 ${favorites.size}", favOnly) {
                    favOnly = true
                    loading = false
                    error = null
                }
                LangChip("只看中文", !favOnly && zhOnly) {
                    favOnly = false
                    zhOnly = true
                    load(keyword, true)
                }
                LangChip("全部语言", !favOnly && !zhOnly) {
                    favOnly = false
                    zhOnly = false
                    load(keyword, false)
                }
            }

            when {
                loading -> Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }

                error != null -> Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { load(keyword, zhOnly) }) { Text("重试") }
                    }
                }

                displayVoices.isEmpty() -> Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (favOnly) "还没有收藏音色，在列表里点 ★ 收藏" else "没有找到音色",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                    items(displayVoices, key = { it.id }) { v ->
                        val selected = v.id == currentId
                        val fav = favorites.any { it.id == v.id }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { pendingPick = v }
                                .padding(start = 20.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    v.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val sub = buildList {
                                    if (v.author.isNotBlank()) add(v.author)
                                    if (v.languages.isNotEmpty()) add(v.languages.take(3).joinToString("/"))
                                    if (v.taskCount > 0) add("${v.taskCount} 次合成")
                                }.joinToString(" · ")
                                if (sub.isNotBlank()) {
                                    Text(
                                        sub,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (v.sampleUrl.isNotBlank()) {
                                IconButton(onClick = { player.toggle(v.sampleUrl) }) {
                                    Icon(
                                        if (player.playingUrl == v.sampleUrl) Icons.Rounded.Stop
                                        else Icons.Rounded.PlayArrow,
                                        "试听",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            IconButton(
                                onClick = { container.ttsSettings.toggleFavorite(v.id, v.title) },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    if (fav) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                    "收藏",
                                    tint = if (fav) Color(0xFFF5C518) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                            if (selected) {
                                Icon(
                                    Icons.Rounded.Check,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    pendingPick?.let { v ->
        AlertDialog(
            onDismissRequest = { pendingPick = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("使用这个音色？") },
            text = { Text("「${v.title}」${v.author.takeIf(String::isNotBlank)?.let { "（$it）" } ?: ""}") },
            confirmButton = {
                TextButton(onClick = {
                    pendingPick = null
                    onPick(v)
                }) { Text("使用") }
            },
            dismissButton = {
                TextButton(onClick = { pendingPick = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun LangChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 6.dp)
    ) {
        Text(
            text,
            fontSize = 12.sp,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 音色试听：单个 MediaPlayer，够用 */
private class PreviewPlayer {
    private var player: MediaPlayer? = null
    var playingUrl by mutableStateOf<String?>(null)
        private set

    fun toggle(url: String) {
        if (playingUrl == url) {
            stop()
            return
        }
        stop()
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(url)
                setOnCompletionListener { stop() }
                setOnErrorListener { _, _, _ ->
                    stop()
                    true
                }
                prepareAsync()
                setOnPreparedListener { it.start() }
            }
            playingUrl = url
        }.onFailure { stop() }
    }

    fun stop() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        playingUrl = null
    }

    fun release() = stop()
}
