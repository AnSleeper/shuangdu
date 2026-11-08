package com.shuangdu.reader.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.shuangdu.reader.MainActivity
import com.shuangdu.reader.SdApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 听书的前台播放服务。只为两件事存在：
 * 1. 让系统知道这是用户可见的音频播放，息屏/切后台不被冻结（跨章续播才可靠）
 * 2. 给一条常驻通知，点一下能回到正在读的那本书
 *
 * 播放器本身仍由 AppContainer 持有（同进程），这里只负责保持前台 + 更新通知。
 */
class TtsPlaybackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null
    private var inForeground = false

    private val player: TtsPlayer
        get() = (application as SdApp).container.ttsPlayer

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }

            ACTION_TOGGLE -> {
                val st = player.state.value
                if (st.playing) player.pause() else player.resume()
            }

            else -> ensureForeground()
        }
        return START_STICKY
    }

    private fun ensureForeground() {
        createChannel()
        val st = player.state.value
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(
                    bookTitle = st.bookTitle.ifBlank { "爽读爽听" },
                    chapterTitle = chapterLine(st),
                    playing = st.playing,
                    bookId = st.bookId
                ),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                } else 0
            )
            inForeground = true
        } catch (t: Throwable) {
            // 后台启动受限等情况：不崩，退回普通播放
            inForeground = false
        }
        observeState()
    }

    /** 只关心「书名/章/播放态」，倒计时每秒变化不触发通知重建 */
    private fun observeState() {
        if (observeJob?.isActive == true) return
        observeJob = scope.launch {
            player.state
                .map { s ->
                    Snapshot(
                        active = s.active,
                        playing = s.playing,
                        bookId = s.bookId,
                        chapter = s.chapter,
                        bookTitle = s.bookTitle,
                        chapterTitle = s.chapterTitle
                    )
                }
                .distinctUntilChanged()
                .collect { snap ->
                    if (!snap.active) {
                        shutdown()
                        return@collect
                    }
                    if (!inForeground) return@collect
                    val manager = NotificationManagerCompat.from(this@TtsPlaybackService)
                    if (!manager.areNotificationsEnabled()) return@collect
                    runCatching {
                        manager.notify(
                            NOTIFICATION_ID,
                            buildNotification(
                                bookTitle = snap.bookTitle.ifBlank { "爽读爽听" },
                                chapterTitle = if (snap.chapterTitle.isBlank()) "第 ${snap.chapter + 1} 章"
                                else snap.chapterTitle,
                                playing = snap.playing,
                                bookId = snap.bookId
                            )
                        )
                    }
                }
        }
    }

    private fun chapterLine(st: TtsPlayer.State): String =
        st.chapterTitle.ifBlank { "第 ${st.chapter + 1} 章" }

    private fun buildNotification(
        bookTitle: String,
        chapterTitle: String,
        playing: Boolean,
        bookId: String
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(EXTRA_BOOK_ID, bookId)
        }
        val contentPending = PendingIntent.getActivity(
            this,
            1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleIntent = Intent(this, TtsPlaybackService::class.java).setAction(ACTION_TOGGLE)
        val togglePending = PendingIntent.getService(
            this,
            2,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(bookTitle)
            .setContentText(chapterTitle + if (playing) "" else " · 已暂停")
            .setContentIntent(contentPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "暂停" else "继续",
                togglePending
            )
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "听书播放",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "正在听书时显示的书名与章节"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun shutdown() {
        observeJob?.cancel()
        observeJob = null
        if (inForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            inForeground = false
        }
        stopSelf()
    }

    override fun onDestroy() {
        observeJob?.cancel()
        observeJob = null
        super.onDestroy()
    }

    private data class Snapshot(
        val active: Boolean,
        val playing: Boolean,
        val bookId: String,
        val chapter: Int,
        val bookTitle: String,
        val chapterTitle: String
    )

    companion object {
        const val EXTRA_BOOK_ID = "tts_book_id"
        private const val CHANNEL_ID = "tts_playback"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.shuangdu.reader.tts.START"
        private const val ACTION_STOP = "com.shuangdu.reader.tts.STOP"
        private const val ACTION_TOGGLE = "com.shuangdu.reader.tts.TOGGLE"

        /** 开始/确保前台（用户点播放时调用，App 在前台，符合 Android 12+ 限制） */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, TtsPlaybackService::class.java).setAction(ACTION_START)
                )
            }
        }

        /** 退出听书：立即移除通知与服务 */
        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, TtsPlaybackService::class.java))
            }
        }
    }
}
