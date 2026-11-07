package com.shuangdu.reader.ui.update

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shuangdu.reader.AppContainer
import com.shuangdu.reader.BuildConfig
import com.shuangdu.reader.update.UpdateInfo
import kotlinx.coroutines.launch

private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

@Composable
fun UpdateHost(container: AppContainer, trigger: Int) {
    val context = LocalContext.current
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE) }

    var info by remember { mutableStateOf<UpdateInfo?>(null) }
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var errorText by remember { mutableStateOf<String?>(null) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun check(silent: Boolean) {
        if (checking || downloading) return
        scope.launch {
            checking = true
            errorText = null
            val result = container.updater.check()
            checking = false
            prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
            result.onSuccess { remote ->
                if (remote != null && container.updater.isNewer(remote.versionName)) {
                    info = remote
                } else if (!silent) {
                    toast("已经是最新版 ${BuildConfig.VERSION_NAME}")
                }
            }.onFailure {
                if (!silent) toast("检查更新失败：${it.message ?: "网络异常"}")
            }
        }
    }

    LaunchedEffect(Unit) {
        val last = prefs.getLong("last_check", 0L)
        if (settings.autoCheckUpdate && System.currentTimeMillis() - last > CHECK_INTERVAL_MS) {
            check(silent = true)
        }
    }

    LaunchedEffect(trigger) {
        if (trigger > 0) check(silent = false)
    }

    info?.let { update ->
        AlertDialog(
            onDismissRequest = { if (!downloading) info = null },
            title = { Text("发现新版本 ${update.versionName}") },
            text = {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "当前版本 ${BuildConfig.VERSION_NAME}  ·  " +
                            if (update.apkSize > 0) "${update.apkSize / 1024 / 1024} MB" else "APK",
                        fontSize = 12.sp,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        update.changelog.ifBlank { "这次没有写更新说明。" },
                        fontSize = 13.5.sp,
                        lineHeight = 21.sp
                    )
                    if (downloading) {
                        Spacer(Modifier.height(16.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(4.dp)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text("正在下载 ${(progress * 100).toInt()}%", fontSize = 12.sp)
                    }
                    errorText?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, fontSize = 12.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !downloading,
                    onClick = {
                        scope.launch {
                            downloading = true
                            progress = 0f
                            errorText = null
                            val r = container.updater.download(update) { p -> progress = p }
                            downloading = false
                            r.onSuccess { file ->
                                if (!container.updater.canInstall()) {
                                    toast("请先允许「爽读爽听」安装应用，再点一次更新")
                                    runCatching { context.startActivity(container.updater.openInstallPermission()) }
                                } else {
                                    runCatching { context.startActivity(container.updater.installIntent(file)) }
                                        .onFailure { errorText = "无法调起安装器：${it.message}" }
                                }
                            }.onFailure {
                                errorText = "下载失败：${it.message ?: "网络异常"}"
                            }
                        }
                    }
                ) { Text(if (downloading) "下载中…" else "立即更新") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { info = null }, enabled = !downloading) { Text("以后再说") }
                }
            }
        )
    }
}
