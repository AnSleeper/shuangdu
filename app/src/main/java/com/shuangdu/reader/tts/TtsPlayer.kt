package com.shuangdu.reader.tts

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 听书播放器：ExoPlayer 播放列表，逐段无缝衔接。
 * state 里带「当前章 / 当前段」，阅读页据此高亮。
 */
class TtsPlayer(context: Context) {

    data class State(
        val playing: Boolean = false,
        val active: Boolean = false,
        val bookId: String = "",
        val chapter: Int = -1,
        val segment: Int = -1,
        val segments: List<TtsSegment> = emptyList(),
        val timerMinutes: Int = 0,
        val timerRemainingMs: Long = 0
    )

    private val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            true
        )
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** 一段播完、整章播完时的回调（用于接下一章） */
    var onSegmentEnd: ((chapter: Int, segment: Int) -> Unit)? = null
    var onQueueEnd: (() -> Unit)? = null

    /** 章节播完自动接下一章：返回该章已转好的段落，null 表示没有/没转完 */
    var nextChapterProvider: ((bookId: String, chapter: Int) -> List<TtsSegment>? )? = null
    var dirProvider: ((bookId: String) -> File)? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(playing = isPlaying)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val id = mediaItem?.mediaId ?: return
                val segIndex = id.toIntOrNull() ?: return
                val prev = _state.value
                _state.value = prev.copy(segment = segIndex, playing = player.isPlaying)
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    onSegmentEnd?.invoke(prev.chapter, segIndex - 1)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    _state.value = _state.value.copy(playing = false)
                    onQueueEnd?.invoke()
                    tryAutoAdvance()
                }
            }
        })
    }

    /** 一章播完自动接下一章（下一章必须已转好），逻辑挂在播放器上，跟界面无关 */
    private fun tryAutoAdvance() {
        val s = _state.value
        if (s.bookId.isEmpty() || s.chapter < 0) return
        val next = s.chapter + 1
        val segs = nextChapterProvider?.invoke(s.bookId, next) ?: return
        if (segs.isEmpty() || !segs.all { it.done }) return
        val dir = dirProvider?.invoke(s.bookId) ?: return
        // 从监听器里改播放器结构不安全，抛到主线程协程里做
        tickerScope.launch { play(s.bookId, next, segs, 0, dir) }
    }

    val currentChapter: Int get() = _state.value.chapter
    val currentSegment: Int get() = _state.value.segment

    /**
     * 从 [fromSegment] 开始播这一章已经转好的段。
     * 只把「有文件的段」放进播放列表，界面上图标序号用 mediaId 保持原段号。
     */
    fun play(bookId: String, chapter: Int, segments: List<TtsSegment>, fromSegment: Int, dir: File) {
        val playable = segments.filter { it.index >= fromSegment && it.done && File(dir, it.file).exists() }
        if (playable.isEmpty()) {
            stop()
            return
        }
        player.stop()
        player.clearMediaItems()
        playable.forEach { seg ->
            player.addMediaItem(
                MediaItem.Builder()
                    .setMediaId(seg.index.toString())
                    .setUri(File(dir, seg.file).toURI().toString())
                    .build()
            )
        }
        _state.value = State(
            playing = false,
            active = true,
            bookId = bookId,
            chapter = chapter,
            segment = playable.first().index,
            segments = playable,
            timerMinutes = _state.value.timerMinutes,
            timerRemainingMs = timerRemainingMs
        )
        player.prepare()
        player.play()
        startTimerTicker()
    }

    fun pause() {
        player.pause()
        timerJob?.cancel() // 倒计时跟播放一起停
        publishTimer()
    }

    fun resume() {
        if (player.mediaItemCount > 0) {
            player.play()
            startTimerTicker()
        }
    }

    fun stop() {
        player.stop()
        player.clearMediaItems()
        timerJob?.cancel()
        timerRemainingMs = 0
        _state.value = State()
    }

    fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(speed.coerceIn(0.5f, 2.0f))
    }

    // ---- 定时停止：倒计时只在播放时走，暂停就冻结 ----

    private val tickerScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main
    )
    private var timerJob: kotlinx.coroutines.Job? = null
    private var timerRemainingMs = 0L

    fun setTimerMinutes(min: Int) {
        timerJob?.cancel()
        if (min <= 0) {
            timerRemainingMs = 0
            _state.value = _state.value.copy(timerMinutes = 0, timerRemainingMs = 0)
            return
        }
        timerRemainingMs = min * 60_000L
        _state.value = _state.value.copy(timerMinutes = min, timerRemainingMs = timerRemainingMs)
        startTimerTicker()
    }

    private fun startTimerTicker() {
        timerJob?.cancel()
        publishTimer()
        if (timerRemainingMs <= 0) return
        if (!_state.value.playing) return
        timerJob = tickerScope.launch {
            while (timerRemainingMs > 0 && _state.value.playing) {
                delay(1000)
                timerRemainingMs = (timerRemainingMs - 1000).coerceAtLeast(0)
                publishTimer()
            }
            if (timerRemainingMs <= 0) {
                player.pause()
                publishTimer()
            }
        }
    }

    private fun publishTimer() {
        _state.value = _state.value.copy(timerRemainingMs = timerRemainingMs)
    }

    fun release() {
        player.release()
    }
}
