package com.shuangdu.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shuangdu.reader.AppContainer
import com.shuangdu.reader.data.Book
import com.shuangdu.reader.tts.TtsSegment
import com.shuangdu.reader.ui.tts.VoicePickerSheet
import kotlinx.coroutines.delay
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsPanel(
    container: AppContainer,
    book: Book,
    chapterTitles: List<String>,
    currentChapter: Int,
    startSegment: Int,
    onDismiss: () -> Unit,
    onJumpToChapter: (Int) -> Unit
) {
    val tts by container.ttsSettings.state.collectAsStateWithLifecycle()
    val progMap by container.ttsConverter.state.collectAsStateWithLifecycle()
    val playerState by container.ttsPlayer.state.collectAsStateWithLifecycle()
    val prog = progMap[book.id]

    val index = remember(book.id) { container.ttsConverter.indexOf(book) }
    val dir = remember(book.id) { File(container.repository.bookDir(book.id), "tts") }

    var showVoicePicker by remember { mutableStateOf(false) }
    var showReconvert by remember { mutableStateOf(false) }
    var playingChapter by remember { mutableIntStateOf(currentChapter) }

    val isThisBook = playerState.bookId == book.id && playerState.active
    val isPlaying = isThisBook && playerState.playing

    fun playChapter(ch: Int, from: Int) {
        val segs = index.segmentsOf(ch)
        if (segs.isEmpty()) {
            container.ttsConverter.convert(book, listOf(ch))
            return
        }
        if (segs.none { it.done }) {
            container.ttsConverter.convert(book, listOf(ch))
            return
        }
        playingChapter = ch
        container.ttsPlayer.play(book.id, ch, segs, from, dir)
        container.ttsPlayer.setSpeed(tts.speed)
    }

    // 自动接下一章已移到 TtsPlayer（挂 nextChapterProvider），不依赖面板是否打开

    // 定时停止由播放器自己管：只在播放时走，暂停就冻结
    val timerMinutes = playerState.timerMinutes
    val timerRemaining = playerState.timerRemainingMs

    val chaptersWithAudio = index.completedChapters()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Headphones,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("听书", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                Text(
                    "已有音频 ${chaptersWithAudio.size} 章",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))

            // 音色
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        index.voiceName.ifBlank { tts.globalVoiceName.ifBlank { "未选择音色" } },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        if (index.voiceId.isBlank()) "跟随全局音色" else "本书专用音色",
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { showVoicePicker = true }) { Text("更换") }
                if (index.voiceId.isNotBlank()) {
                    TextButton(onClick = {
                        container.ttsSettings.update {
                            it.copy(globalVoiceId = index.voiceId, globalVoiceName = index.voiceName)
                        }
                    }) { Text("设为全局") }
                }
            }

            Spacer(Modifier.height(14.dp))

            // 播放控制
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable {
                            when {
                                isPlaying -> container.ttsPlayer.pause()
                                isThisBook && playerState.chapter == playingChapter -> container.ttsPlayer.resume()
                                else -> playChapter(playingChapter, 0)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (isPlaying) "暂停" else "播放",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(30.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (isPlaying) "正在播放" else "准备播放",
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        chapterTitles.getOrNull(playingChapter)?.ifBlank { "第${playingChapter + 1}章" }
                            ?: "第${playingChapter + 1}章",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val segs = index.segmentsOf(playingChapter)
                    val doneCount = segs.count { it.done }
                    Text(
                        if (segs.isEmpty()) "这一章还没转音频" else "已转 $doneCount / ${segs.size} 段",
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { playChapter(currentChapter, startSegment) }) { Text("从本页听") }
                TextButton(onClick = { container.ttsPlayer.stop() }) { Text("退出听书") }
            }

            // 转换进度
            prog?.let { p ->
                if (p.running || p.message != null) {
                    Spacer(Modifier.height(10.dp))
                    if (p.total > 0) {
                        LinearProgressIndicator(
                            progress = { if (p.total == 0) 0f else (p.done + p.failed).toFloat() / p.total },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape)
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            p.message ?: "正在转换 ${p.done + p.failed}/${p.total}（第 ${p.chapter + 1} 章）",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        if (p.running) {
                            TextButton(onClick = { container.ttsConverter.pause(book.id) }) { Text("暂停转换") }
                        } else if (!p.finished) {
                            TextButton(onClick = {
                                container.ttsConverter.convert(book, null)
                            }) { Text("继续转换") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionChip("转本章") {
                    container.ttsConverter.convert(book, listOf(currentChapter))
                }
                ActionChip("转整本") {
                    container.ttsConverter.convert(book, null)
                }
                ActionChip(if (timerMinutes > 0) "定时 ${timerMinutes}分" else "定时 关") {
                    val next = when (timerMinutes) {
                        0 -> 15
                        15 -> 30
                        30 -> 60
                        else -> 0
                    }
                    container.ttsPlayer.setTimerMinutes(next)
                }
                if (timerRemaining > 0) {
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            "剩 ${formatCountdown(timerRemaining)}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "倍速",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(36.dp)
                )
                Slider(
                    value = tts.speed,
                    onValueChange = { v ->
                        val s = (v * 10).toInt() / 10f
                        container.ttsSettings.update { it.copy(speed = s) }
                        container.ttsPlayer.setSpeed(s)
                    },
                    valueRange = 1f..2f,
                    steps = 9,
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
                )
                Text(
                    "x%.1f".format(tts.speed),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(38.dp)
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "悬浮窗",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(48.dp)
                )
                Switch(checked = tts.showPill, onCheckedChange = { v ->
                    container.ttsSettings.update { it.copy(showPill = v) }
                })
                Spacer(Modifier.width(10.dp))
                Text(
                    "透明度",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = tts.pillOpacity,
                    onValueChange = { v ->
                        container.ttsSettings.update { it.copy(pillOpacity = v) }
                    },
                    valueRange = 0.3f..1f,
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
                )
                Text(
                    "${(tts.pillOpacity * 100).toInt()}%",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(38.dp)
                )
            }
            Text(
                "悬浮窗可以按住拖动，松手自动吸附到屏幕左右边缘",
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(14.dp))
            Text(
                "章节音频",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 300.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                // 用进度当 key 的一部分，转换过程中列表状态能实时刷新
                val refresh = (prog?.done ?: 0) * 1000 + (prog?.failed ?: 0) + (prog?.total ?: 0)
                items(chapterTitles.indices.toList(), key = { "$it-$refresh" }) { ch ->
                    val (done, total) = index.chapterProgress(ch)
                    val complete = total > 0 && done == total
                    val current = ch == currentChapter
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                // 跳文本 + 播放器定位到这一章；原本暂停就保持暂停
                                onJumpToChapter(ch)
                                val segs = index.segmentsOf(ch)
                                if (segs.any { it.done }) {
                                    val wasPlaying = container.ttsPlayer.state.value.playing
                                    container.ttsPlayer.play(book.id, ch, segs, 0, dir)
                                    container.ttsPlayer.setSpeed(tts.speed)
                                    if (!wasPlaying) container.ttsPlayer.pause()
                                }
                            }
                            .padding(horizontal = 6.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Headphones,
                            null,
                            tint = when {
                                complete -> MaterialTheme.colorScheme.primary
                                done > 0 -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.outline
                            },
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            chapterTitles[ch].ifBlank { "第${ch + 1}章" },
                            fontSize = 13.sp,
                            color = if (current) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            when {
                                complete -> "已转好"
                                total == 0 -> "未转"
                                else -> "$done/$total"
                            },
                            fontSize = 10.5.sp,
                            color = if (complete) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (total > 0 && !complete) {
                            TextButton(onClick = {
                                container.ttsConverter.convert(book, listOf(ch))
                            }) { Text("转", fontSize = 11.sp) }
                        }
                    }
                }
            }
        }
    }

    if (showVoicePicker) {
        VoicePickerSheet(
            container = container,
            currentId = index.voiceId.ifBlank { tts.globalVoiceId },
            onDismiss = { showVoicePicker = false },
            onPick = { v ->
                val hadAudio = index.completedChapters().isNotEmpty() ||
                    container.ttsConverter.isRunning(book.id)
                index.voiceId = v.id
                index.voiceName = v.title
                index.save()
                showVoicePicker = false
                if (hadAudio) showReconvert = true
            }
        )
    }

    if (showReconvert) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showReconvert = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("音色已更换") },
            text = {
                Text(
                    "这本书已经转好的音频还是旧音色读的。\n\n要删掉这些音频、用新音色重新转换吗？\n（正在进行的转换不会被打断，跑完后会自动重转）\n\n选「先不重转」的话，之后新转的段落会用新音色，前后音色会不一致。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showReconvert = false
                    container.ttsConverter.markReconvert(book)
                }) { Text("删除并重转") }
            },
            dismissButton = {
                TextButton(onClick = { showReconvert = false }) { Text("先不重转") }
            }
        )
    }
}

@Composable
private fun ActionChip(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatCountdown(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
