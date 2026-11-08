package com.shuangdu.reader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.shuangdu.reader.ui.AppRoot

class MainActivity : ComponentActivity() {

    val pendingOpen = mutableStateOf<Uri?>(null)

    /** 从通知点进来时要打开的书 */
    val pendingBook = mutableStateOf<String?>(null)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝也不影响播放，只是没有通知 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as SdApp).container
        pendingOpen.value = extractUri(intent)
        pendingBook.value = intent?.getStringExtra("tts_book_id")
        askNotificationPermission()
        setContent {
            AppRoot(container, pendingOpen, pendingBook)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractUri(intent)?.let { pendingOpen.value = it }
        intent.getStringExtra("tts_book_id")?.let { pendingBook.value = it }
    }

    /** Android 13+ 前台服务通知需要运行时权限，首次进入申请一次 */
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun extractUri(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
    }
}
