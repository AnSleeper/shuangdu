package com.shuangdu.reader.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.shuangdu.reader.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionName: String,
    val changelog: String,
    val apkUrl: String,
    val apkSize: Long,
    val publishedAt: String,
    val htmlUrl: String
)

/**
 * 通过 GitHub Releases 检查更新。
 * 仓库必须是公开的（未登录的 API 调用只能看公开仓库）。
 */
class UpdateChecker(private val context: Context) {

    private val owner = BuildConfig.GITHUB_OWNER
    private val repo = BuildConfig.GITHUB_REPO

    suspend fun check(): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("https://api.github.com/repos/$owner/$repo/releases/latest")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 12000
                readTimeout = 12000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "ShuangDu-Reader/${BuildConfig.VERSION_NAME}")
            }
            try {
                val code = conn.responseCode
                if (code == 404) throw IllegalStateException("仓库不存在或尚未发布版本")
                if (code == 403) throw IllegalStateException("请求太频繁，稍后再试")
                if (code !in 200..299) throw IllegalStateException("检查失败（HTTP $code）")
                val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                val json = JSONObject(body)
                val tag = json.optString("tag_name").ifBlank { json.optString("name") }
                val version = normalizeVersion(tag)
                val assets = json.optJSONArray("assets")
                var apkUrl = ""
                var apkSize = 0L
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        val name = a.optString("name")
                        if (name.endsWith(".apk", true)) {
                            apkUrl = a.optString("browser_download_url")
                            apkSize = a.optLong("size")
                            break
                        }
                    }
                }
                if (apkUrl.isBlank()) return@runCatching null
                UpdateInfo(
                    versionName = version,
                    changelog = json.optString("body").trim(),
                    apkUrl = apkUrl,
                    apkSize = apkSize,
                    publishedAt = json.optString("published_at"),
                    htmlUrl = json.optString("html_url")
                )
            } finally {
                conn.disconnect()
            }
        }
    }

    fun isNewer(remote: String, local: String = BuildConfig.VERSION_NAME): Boolean {
        val a = remote.split('.', '-', '+').mapNotNull { it.toIntOrNull() }
        val b = local.split('.', '-', '+').mapNotNull { it.toIntOrNull() }
        val size = maxOf(a.size, b.size)
        for (i in 0 until size) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun normalizeVersion(tag: String): String =
        tag.trim().removePrefix("v").removePrefix("V").substringBefore(' ').trim()

    suspend fun download(
        info: UpdateInfo,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "update").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "shuangdu-${info.versionName}.apk")
            val conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 60000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "ShuangDu-Reader/${BuildConfig.VERSION_NAME}")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw IllegalStateException("下载失败（HTTP $code）")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.apkSize
                var read = 0L
                conn.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            read += n
                            if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                onProgress(1f)
                if (target.length() < 1024) throw IllegalStateException("下载的文件不完整")
                target
            } finally {
                conn.disconnect()
            }
        }
    }

    fun canInstall(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.packageManager.canRequestPackageInstalls() else true

    fun openInstallPermission(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    fun installIntent(apk: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
