package com.shuangdu.reader.tts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Fish Audio 的音色（社区模型库里的一项） */
data class VoiceItem(
    val id: String,
    val title: String,
    val author: String,
    val cover: String,
    val sampleUrl: String,
    val languages: List<String>,
    val tags: List<String>,
    val taskCount: Int
)

/** TTS 引擎模型（决定质量和额度） */
object TtsModels {
    const val S2_1_PRO_FREE = "s2.1-pro-free"
    const val S2_1_PRO = "s2.1-pro"
    const val S2_PRO = "s2-pro"
    const val S1 = "s1"

    val all = listOf(S2_1_PRO_FREE, S2_1_PRO, S2_PRO, S1)

    fun label(id: String): String = when (id) {
        S2_1_PRO_FREE -> "s2.1-pro-free（免费）"
        S2_1_PRO -> "s2.1-pro（付费·最强）"
        S2_PRO -> "s2-pro（付费）"
        S1 -> "s1（付费·旧版）"
        else -> id
    }
}

class FishAudioException(
    val statusCode: Int?,
    message: String,
    val retryAfterMs: Long? = null,
    val rateLimited: Boolean = false
) : IllegalStateException(message)

/**
 * Fish Audio 最小客户端。只用 HttpURLConnection，不引 OkHttp。
 * 端点：GET /model（音色列表）、POST /v1/tts（合成）。
 */
class FishAudioClient(private val keyStore: FishKeyStore) {

    private companion object {
        const val BASE = "https://api.fish.audio"
    }

    private fun apiKey(): String = keyStore.load() ?: throw FishAudioException(null, "还没有填写 Fish Audio 密钥")

    suspend fun listVoices(
        page: Int = 1,
        pageSize: Int = 24,
        keyword: String = "",
        language: String = "zh"
    ): Result<List<VoiceItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = StringBuilder("$BASE/model")
                    .append("?page_size=").append(pageSize)
                    .append("&page_number=").append(page)
                    .append("&self=false")
                    .append("&sort_by=score")
                if (keyword.isNotBlank()) url.append("&title=").append(java.net.URLEncoder.encode(keyword, "UTF-8"))
                if (language.isNotBlank()) url.append("&language=").append(language)
                val text = request(url.toString(), "GET")
                parseVoices(text)
            }
        }

    suspend fun synthesize(
        text: String,
        voiceId: String,
        modelId: String,
        speed: Float = 1.0f
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().apply {
                put("text", text)
                put("format", "mp3")
                put("sample_rate", 44100)
                put("mp3_bitrate", 64)
                put("latency", "normal")
                put("normalize", true)
                put("chunk_length", 300)
                if (voiceId.isNotBlank()) put("reference_id", voiceId)
                if (speed != 1.0f) {
                    put("prosody", JSONObject().apply { put("speed", speed) })
                }
            }.toString()
            val bytes = requestBytes("$BASE/v1/tts", "POST", body, modelId, accept = "audio/mpeg")
            if (bytes.size < 1024) throw FishAudioException(null, "返回的音频不完整")
            bytes
        }
    }

    /** 用一句很短的话试一遍，验证 Key / 模型 / 音色是否可用 */
    suspend fun testConnection(modelId: String, voiceId: String): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val start = System.currentTimeMillis()
            synthesize("你好，这是一次测试。", voiceId, modelId).getOrThrow()
            System.currentTimeMillis() - start
        }
    }

    private fun parseVoices(text: String): List<VoiceItem> {
        val root = JSONObject(text)
        val items = root.optJSONArray("items") ?: JSONArray()
        val out = ArrayList<VoiceItem>(items.length())
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val samples = o.optJSONArray("samples")
            var sampleUrl = ""
            var sampleText = ""
            if (samples != null && samples.length() > 0) {
                val s = samples.optJSONObject(0)
                sampleUrl = s?.optString("audio").orEmpty().ifBlank { s?.optString("audio_url").orEmpty() }
                sampleText = s?.optString("text").orEmpty()
            }
            out.add(
                VoiceItem(
                    id = o.optString("_id"),
                    title = o.optString("title").ifBlank { "未命名音色" },
                    author = o.optJSONObject("author")?.optString("nickname").orEmpty(),
                    cover = o.optString("cover_image"),
                    sampleUrl = sampleUrl,
                    languages = o.optJSONArray("languages")?.let { arr ->
                        (0 until arr.length()).map { arr.optString(it) }
                    }.orEmpty(),
                    tags = o.optJSONArray("tags")?.let { arr ->
                        (0 until arr.length()).map { arr.optString(it) }
                    }.orEmpty(),
                    taskCount = o.optInt("task_count")
                )
            )
        }
        return out
    }

    private fun request(url: String, method: String): String {
        val conn = open(url, method)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw errorFor(conn, code)
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    private fun requestBytes(
        url: String,
        method: String,
        body: String,
        modelId: String,
        accept: String
    ): ByteArray {
        val conn = open(url, method, modelId, accept).apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw errorFor(conn, code)
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, method: String, modelId: String? = null, accept: String = "application/json"): HttpURLConnection {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 90000
            instanceFollowRedirects = true
            setRequestProperty("Authorization", "Bearer ${apiKey()}")
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "ShuangDu-Reader/tts")
            if (modelId != null) setRequestProperty("model", modelId)
        }
        return conn
    }

    private fun errorFor(conn: HttpURLConnection, code: Int): FishAudioException {
        val detail = runCatching {
            val stream = conn.errorStream ?: return@runCatching ""
            val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
            runCatching { JSONObject(text).optString("message") }.getOrDefault(text.take(200))
        }.getOrDefault("")
        val retryAfter = conn.getHeaderField("Retry-After")?.toLongOrNull()?.times(1000)
        val summary = when (code) {
            401 -> "密钥无效或已过期"
            402 -> "账户额度不足"
            403 -> "这个音色当前密钥无权使用"
            404 -> "音色或接口不存在"
            422 -> "请求参数不合法"
            429 -> "请求太频繁（并发超限），稍后重试"
            in 500..599 -> "Fish Audio 服务暂时不可用"
            else -> "请求失败"
        }
        return FishAudioException(
            statusCode = code,
            message = "$summary（HTTP $code）${if (detail.isNotBlank()) "：$detail" else ""}",
            retryAfterMs = retryAfter,
            rateLimited = code == 429
        )
    }
}

internal fun httpStatusOf(e: Throwable): Int? = (e as? FishAudioException)?.statusCode

@Suppress("unused")
internal fun ioMessage(e: IOException): String = e.message ?: "网络异常"
